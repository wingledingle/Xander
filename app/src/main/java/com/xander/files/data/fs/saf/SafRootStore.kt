package com.xander.files.data.fs.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.xander.files.data.db.SafRootDao
import com.xander.files.data.db.SafRootEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class SafRootKind { SD_CARD, USB, ANDROID_DATA, ANDROID_OBB, FOLDER }

/** Persisted SAF tree grants, addressable as `saf://<id>/...`. */
@Singleton
class SafRootStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SafRootDao,
) {
    private val cache = ConcurrentHashMap<String, SafRootEntity>()

    fun observe(): Flow<List<SafRootEntity>> = dao.observeAll()

    suspend fun get(id: String): SafRootEntity? = cache[id] ?: dao.get(id)?.also { cache[id] = it }

    suspend fun all(): List<SafRootEntity> = dao.all().onEach { cache[it.id] = it }

    suspend fun add(treeUri: Uri, label: String, kind: SafRootKind, volumeUuid: String?, volumePath: String?): SafRootEntity {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val id = docId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "root" }
        val e = SafRootEntity(id, treeUri.toString(), label, volumeUuid, volumePath, kind.name)
        dao.upsert(e); cache[id] = e
        return e
    }

    suspend fun remove(id: String) {
        get(id)?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        dao.delete(id); cache.remove(id)
    }

    /** Finds a grant covering [absolutePath] (a raw file path on a volume), returning root + relative path. */
    suspend fun findForLocalPath(absolutePath: String): Pair<SafRootEntity, String>? {
        val roots = all()
        for (r in roots.sortedByDescending { it.volumePath?.length ?: 0 }) {
            val base = r.volumePath ?: continue
            val treeDoc = DocumentsContract.getTreeDocumentId(Uri.parse(r.treeUri))
            val treeRel = treeDoc.substringAfter(':', "")
            val treeBase = if (treeRel.isEmpty()) base else "$base/$treeRel"
            if (absolutePath == treeBase || absolutePath.startsWith("$treeBase/")) {
                if (!isGranted(r)) continue
                return r to absolutePath.removePrefix(treeBase).ifEmpty { "/" }
            }
        }
        return null
    }

    fun isGranted(r: SafRootEntity): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == r.treeUri && it.isWritePermission }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /** Initial URI hint for ACTION_OPEN_DOCUMENT_TREE pointing at a specific folder on a volume. */
        fun initialUri(volumeId: String, relative: String): Uri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$volumeId:$relative")
    }
}
