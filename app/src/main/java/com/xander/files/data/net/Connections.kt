package com.xander.files.data.net

import com.xander.files.data.db.ConnectionDao
import com.xander.files.data.db.ConnectionEntity
import com.xander.files.data.security.SecretCipher
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnType(val scheme: String, val defaultPort: Int) {
    SMB(FsPath.SCHEME_SMB, 445), FTP(FsPath.SCHEME_FTP, 21), FTPS(FsPath.SCHEME_FTP, 21), SFTP(FsPath.SCHEME_SFTP, 22),
    WEBDAV(FsPath.SCHEME_WEBDAV, 80), NFS(FsPath.SCHEME_NFS, 2049), HTTP(FsPath.SCHEME_HTTP, 80)
}

@Serializable
data class ConnOptions(
    val passive: Boolean = true,
    val encoding: String = "UTF-8",
    val implicitTls: Boolean = false,
    val https: Boolean = false,
    val anonymous: Boolean = false,
    /** PEM/OpenSSH private key for SFTP (stored encrypted in [ConnectionEntity.secret] alongside the password). */
    val usePrivateKey: Boolean = false,
    val hostKeyFingerprint: String = "",
    val trustSelfSigned: Boolean = false,
    val timeoutSec: Int = 20,
    val nfsUid: Int = 0,
    val nfsGid: Int = 0,
)

@Serializable
private data class Secrets(val password: String = "", val privateKey: String = "", val keyPassphrase: String = "")

data class Connection(
    val id: String,
    val type: ConnType,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val domain: String,
    val remotePath: String,
    val options: ConnOptions,
    val privateKey: String = "",
    val keyPassphrase: String = "",
) {
    val root: FsPath get() = FsPath(type.scheme, id, FsPath.normalize(remotePath.ifBlank { "/" }))
    val displayUrl: String get() = "${type.name.lowercase()}://${if (username.isNotBlank() && !options.anonymous) "$username@" else ""}$host:$port${FsPath.normalize(remotePath)}"
}

@Singleton
class ConnectionRepository @Inject constructor(
    private val dao: ConnectionDao,
    private val cipher: SecretCipher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache = ConcurrentHashMap<String, Connection>()

    val all: Flow<List<Connection>> = dao.observeAll().map { l -> l.map { it.toModel() } }

    suspend fun get(id: String): Connection = cache[id] ?: (dao.get(id)?.toModel()?.also { cache[id] = it } ?: throw FsException.Generic("Connection not found"))

    suspend fun save(c: Connection): Connection {
        val id = c.id.ifEmpty { UUID.randomUUID().toString().take(8) }
        val secrets = json.encodeToString(Secrets.serializer(), Secrets(c.password, c.privateKey, c.keyPassphrase))
        dao.upsert(ConnectionEntity(id, c.type.name, c.name.ifBlank { c.host }, c.host.trim(), c.port, c.username, cipher.encrypt(secrets),
            c.domain, c.remotePath, json.encodeToString(ConnOptions.serializer(), c.options)))
        val saved = c.copy(id = id)
        cache[id] = saved
        listeners.forEach { it(id) }
        return saved
    }

    suspend fun updateOptions(id: String, transform: (ConnOptions) -> ConnOptions) { val c = get(id); save(c.copy(options = transform(c.options))) }

    suspend fun delete(id: String) { dao.delete(id); cache.remove(id); listeners.forEach { it(id) } }

    /** Providers drop pooled sessions when a connection is edited or removed. */
    val listeners = mutableListOf<(String) -> Unit>()

    private fun ConnectionEntity.toModel(): Connection {
        val s = runCatching { json.decodeFromString(Secrets.serializer(), cipher.decrypt(secret)) }.getOrDefault(Secrets())
        return Connection(id, ConnType.valueOf(type), name, host, port, username, s.password, domain, remotePath,
            runCatching { json.decodeFromString(ConnOptions.serializer(), optionsJson) }.getOrDefault(ConnOptions()), s.privateKey, s.keyPassphrase)
    }
}
