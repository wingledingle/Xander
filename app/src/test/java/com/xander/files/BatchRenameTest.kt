package com.xander.files

import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.ops.BatchRename
import com.xander.files.ui.ops.CaseMode
import com.xander.files.ui.ops.RenameMode
import com.xander.files.ui.ops.RenameSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatchRenameTest {
    private val files = listOf("IMG_001.jpg", "IMG_002.jpg", "notes.txt").map { FileEntry(FsPath.local("/d/$it"), false) }

    @Test fun patternNumbering() {
        val r = BatchRename.apply(files, RenameSpec(RenameMode.PATTERN, "Trip_{n}", start = 7, pad = 3))
        assertEquals(listOf("Trip_007.jpg", "Trip_008.jpg", "Trip_009.txt"), r.map { it.second })
    }

    @Test fun regexReplace() {
        val r = BatchRename.apply(files, RenameSpec(RenameMode.REPLACE, find = "IMG_(\\d+)", replace = "photo-$1", regex = true))
        assertEquals(listOf("photo-001.jpg", "photo-002.jpg", "notes.txt"), r.map { it.second })
    }

    @Test fun caseAndAffix() {
        assertEquals("IMG_001.jpg", BatchRename.apply(files, RenameSpec(RenameMode.CASE, case = CaseMode.UPPER))[0].second)
        assertEquals("x-notes-y.txt", BatchRename.apply(files, RenameSpec(RenameMode.AFFIX, prefix = "x-", suffix = "-y"))[2].second)
    }

    @Test fun detectsCollisions() {
        val same = BatchRename.apply(files, RenameSpec(RenameMode.PATTERN, "same"))
        assertNotNull(BatchRename.validate(same.take(2), setOf()))
        val ok = BatchRename.apply(files, RenameSpec(RenameMode.PATTERN, "{name}_{n}"))
        assertNull(BatchRename.validate(ok, files.map { it.name }.toSet()))
        assertNotNull(BatchRename.validate(listOf(files[0] to "other.jpg"), setOf("other.jpg")))
    }
}
