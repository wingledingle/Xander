package com.xander.files.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.xander.files.domain.fs.FileCategory
import com.xander.files.ui.theme.CategoryColors

object FileIcons {
    fun icon(c: FileCategory): ImageVector = when (c) {
        FileCategory.FOLDER -> Icons.Filled.Folder
        FileCategory.IMAGE -> Icons.Filled.Image
        FileCategory.VIDEO -> Icons.Filled.VideoFile
        FileCategory.AUDIO -> Icons.Filled.AudioFile
        FileCategory.DOCUMENT -> Icons.Filled.Description
        FileCategory.APK -> Icons.Filled.Android
        FileCategory.ARCHIVE -> Icons.Filled.FolderZip
        FileCategory.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
        FileCategory.CODE -> Icons.Filled.Code
        FileCategory.PDF -> Icons.Filled.PictureAsPdf
        FileCategory.EBOOK -> Icons.Filled.MenuBook
        FileCategory.ENCRYPTED -> Icons.Filled.Lock
        FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    fun color(c: FileCategory): Color = when (c) {
        FileCategory.FOLDER -> CategoryColors.folder
        FileCategory.IMAGE -> CategoryColors.image
        FileCategory.VIDEO -> CategoryColors.video
        FileCategory.AUDIO -> CategoryColors.audio
        FileCategory.DOCUMENT, FileCategory.TEXT -> CategoryColors.document
        FileCategory.PDF -> Color(0xFFE53935)
        FileCategory.APK -> CategoryColors.apk
        FileCategory.ARCHIVE -> CategoryColors.archive
        FileCategory.CODE -> Color(0xFF546E7A)
        FileCategory.EBOOK -> Color(0xFF6A1B9A)
        FileCategory.ENCRYPTED -> Color(0xFF37474F)
        FileCategory.OTHER -> CategoryColors.other
    }
}
