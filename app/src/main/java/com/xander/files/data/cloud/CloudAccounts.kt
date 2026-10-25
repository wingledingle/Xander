package com.xander.files.data.cloud

import com.xander.files.data.db.CloudAccountDao
import com.xander.files.data.db.CloudAccountEntity
import com.xander.files.data.security.SecretCipher
import com.xander.files.domain.fs.FsException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class CloudService(val label: String) {
    GOOGLE_DRIVE("Google Drive"), DROPBOX("Dropbox"), ONEDRIVE("OneDrive"), BOX("Box"),
    YANDEX("Yandex Disk"), MEGA("MEGA"), NEXTCLOUD("Nextcloud"),
}

/** Everything a drive needs to talk to its API; encrypted at rest. */
@Serializable
data class CloudCredentials(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    /** Nextcloud server URL, Mega session id, etc. */
    val server: String = "",
    val user: String = "",
    val password: String = "",
    /** Mega: base64url master key. */
    val masterKey: String = "",
)

data class CloudAccount(
    val id: String,
    val service: CloudService,
    val displayName: String,
    val email: String,
    val credentials: CloudCredentials,
)

@Singleton
class CloudAccountRepository @Inject constructor(
    private val dao: CloudAccountDao,
    private val cipher: SecretCipher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache = ConcurrentHashMap<String, CloudAccount>()
    val listeners = mutableListOf<(String) -> Unit>()

    val all: Flow<List<CloudAccount>> = dao.observeAll().map { l -> l.map { it.toModel() } }

    suspend fun get(id: String): CloudAccount =
        cache[id] ?: dao.get(id)?.toModel()?.also { cache[id] = it } ?: throw FsException.Generic("Cloud account not found")

    suspend fun add(service: CloudService, displayName: String, email: String, creds: CloudCredentials): CloudAccount {
        // Re-signing into the same account replaces it instead of duplicating.
        val existing = dao.get(stableId(service, email))
        val id = existing?.id ?: stableId(service, email)
        val a = CloudAccount(id, service, displayName, email, creds)
        save(a)
        return a
    }

    private fun stableId(service: CloudService, email: String) =
        service.name.lowercase().take(3) + "-" + UUID.nameUUIDFromBytes("$service|${email.lowercase()}".toByteArray()).toString().take(8)

    suspend fun save(a: CloudAccount) {
        dao.upsert(CloudAccountEntity(a.id, a.service.name, a.displayName, a.email, cipher.encrypt(json.encodeToString(CloudCredentials.serializer(), a.credentials))))
        cache[a.id] = a
    }

    suspend fun updateCredentials(id: String, creds: CloudCredentials) {
        val a = get(id).copy(credentials = creds)
        dao.updateSecret(id, cipher.encrypt(json.encodeToString(CloudCredentials.serializer(), creds)))
        cache[id] = a
    }

    suspend fun remove(id: String) { dao.delete(id); cache.remove(id); listeners.forEach { it(id) } }

    private fun CloudAccountEntity.toModel() = CloudAccount(
        id, CloudService.valueOf(service), displayName, email,
        runCatching { json.decodeFromString(CloudCredentials.serializer(), cipher.decrypt(secret)) }.getOrDefault(CloudCredentials()),
    )
}
