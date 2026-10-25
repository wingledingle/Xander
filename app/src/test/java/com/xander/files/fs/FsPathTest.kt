package com.xander.files.fs

import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsPathTest {
    @Test fun parseAndFormatRoundTrip() {
        val p = FsPath.parse("smb://conn1/share/dir/file.txt")
        assertEquals("smb", p.scheme); assertEquals("conn1", p.authority); assertEquals("/share/dir/file.txt", p.path)
        assertEquals("smb://conn1/share/dir/file.txt", p.toString())
        assertEquals(FsPath.local("/storage/emulated/0"), FsPath.parse("/storage/emulated/0"))
    }

    @Test fun normalizesDotsAndSlashes() {
        assertEquals("/a/c", FsPath.normalize("//a/./b/../c/"))
        assertEquals("/", FsPath.normalize("/.."))
    }

    @Test fun parentChildAndContains() {
        val p = FsPath.local("/a/b")
        assertEquals(FsPath.local("/a"), p.parent)
        assertEquals(FsPath.local("/a/b/c"), p.child("c"))
        assertTrue(p.contains(FsPath.local("/a/b/c/d")))
        assertFalse(p.contains(FsPath.local("/a/bc")))
        assertEquals(listOf("/", "/a", "/a/b"), p.segments().map { it.path })
    }

    @Test fun archiveAuthorityRoundTrip() {
        val zip = FsPath.local("/sdcard/x y/a+b.zip")
        val inside = FsPath.archive(zip, "/docs/readme.md")
        assertEquals(zip.toString(), FsPath.decodeAuthority(inside.authority))
        assertFalse(inside.authority.contains('/'))
        assertEquals(inside, FsPath.parse(inside.toString()))
    }

    @Test fun searchQueryMatchesWildcardsAndFilters() {
        val e = FileEntry(FsPath.local("/x/Holiday-2024.JPG"), false, size = 5_000_000, lastModified = 10)
        assertTrue(SearchQuery("holiday").matches(e))
        assertTrue(SearchQuery("*.jpg").matches(e))
        assertTrue(SearchQuery("Holiday-????.jpg").matches(e))
        assertFalse(SearchQuery("*.png").matches(e))
        assertTrue(SearchQuery(categories = setOf(FileCategory.IMAGE)).matches(e))
        assertFalse(SearchQuery(minSize = 10_000_000).matches(e))
        assertFalse(SearchQuery("holiday", includeHidden = false).matches(e.copy(path = FsPath.local("/x/.holiday.jpg"))))
    }
}
