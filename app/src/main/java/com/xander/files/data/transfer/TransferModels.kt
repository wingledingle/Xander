package com.xander.files.data.transfer

import kotlinx.serialization.Serializable

enum class TransferType { COPY, MOVE, DELETE, RECYCLE, RESTORE, EXTRACT, COMPRESS, ENCRYPT, DECRYPT, UPLOAD_APK }
enum class TransferState { QUEUED, RUNNING, PAUSED, WAITING, DONE, FAILED, CANCELLED }

enum class ConflictChoice { OVERWRITE, SKIP, RENAME, MERGE }

data class ConflictRequest(
    val transferId: String,
    val sourceName: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val targetPath: String,
    val targetSize: Long,
    val targetModified: Long,
    val isDirectory: Boolean,
)

data class ConflictAnswer(val choice: ConflictChoice, val applyToAll: Boolean)

/** Per-type options stored as JSON in TransferEntity.optionsJson. */
@Serializable
data class TransferOptions(
    val defaultConflict: ConflictChoice? = null,
    val preserveTimestamps: Boolean = true,
    // archives
    val archiveFormat: String = "zip",
    val archiveName: String = "",
    val password: String = "",
    val compressionLevel: Int = 5,
    val selectedEntries: List<String> = emptyList(),
    // encryption
    val deleteOriginal: Boolean = false,
    val encryptedExtension: String = "xenc",
    // recycle restore ids
    val recycleIds: List<String> = emptyList(),
    /** Set when a job is resumed after the process died: identical files are skipped silently. */
    val resumed: Boolean = false,
)

data class TransferRequest(
    val type: TransferType,
    val sources: List<String>,
    val destination: String?,
    val title: String,
    val options: TransferOptions = TransferOptions(),
)
