package com.xander.files.net

import com.xander.files.data.net.ConnOptions
import com.xander.files.data.net.ConnType
import com.xander.files.data.net.ftp.FtpFileSystemProvider
import com.xander.files.data.net.http.HttpBrowseProvider
import com.xander.files.data.net.sftp.HostKeyUnknownException
import com.xander.files.data.net.sftp.SftpFileSystemProvider
import com.xander.files.data.net.smb.SmbFileSystemProvider
import com.xander.files.data.net.webdav.WebDavFileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkIntegrationTest {
    @Before fun servers() = assumeServers()

    @Test fun ftp() = runBlocking {
        val r = repo(); val p = FtpFileSystemProvider(r)
        val c = r.add(ConnType.FTP, 2121)
        exerciseProvider(p, FsPath(FsPath.SCHEME_FTP, c.id, "/"))
    }

    @Test fun ftpsExplicit() = runBlocking {
        val r = repo(); val p = FtpFileSystemProvider(r)
        val c = r.add(ConnType.FTPS, 2990, path = "/tmp/xander-it/ftp", options = ConnOptions(trustSelfSigned = true))
        exerciseProvider(p, FsPath(FsPath.SCHEME_FTP, c.id, "/tmp/xander-it/ftp"))
    }

    @Test fun ftpWrongPasswordIsAuthError() = runBlocking {
        val r = repo(); val p = FtpFileSystemProvider(r)
        val c = r.add(ConnType.FTP, 2121, pass = "bad")
        val e = runCatching { p.list(FsPath(FsPath.SCHEME_FTP, c.id, "/")) }.exceptionOrNull()
        assertTrue("$e", e is FsException.AuthRequired)
    }

    @Test fun sftpTofuThenFullContract() = runBlocking {
        val r = repo(); val p = SftpFileSystemProvider(r)
        val c = r.add(ConnType.SFTP, 2222, path = "/tmp/xander-it/sftp")
        val root = FsPath(FsPath.SCHEME_SFTP, c.id, "/tmp/xander-it/sftp")
        val unknown = runCatching { p.list(root) }.exceptionOrNull()
        assertTrue("$unknown", unknown is HostKeyUnknownException)
        p.trustHostKey(c.id, (unknown as HostKeyUnknownException).fingerprint)
        exerciseProvider(p, root)
        // chmod through the POSIX extension
        val f = p.mkdir(root, "perm-${System.nanoTime()}")
        p.chmod(f, "700".toInt(8), false)
        assertEquals("drwx------", p.stat(f).permissions)
        p.delete(f)
    }

    @Test fun webdavBasicAuth() = runBlocking {
        val r = repo(); val p = WebDavFileSystemProvider(r)
        val c = r.add(ConnType.WEBDAV, 8088)
        exerciseProvider(p, FsPath(FsPath.SCHEME_WEBDAV, c.id, "/"))
        assertTrue(p.copyInternal(p.mkdir(FsPath(FsPath.SCHEME_WEBDAV, c.id, "/"), "cp-${System.nanoTime()}").also { }, FsPath(FsPath.SCHEME_WEBDAV, c.id, "/"), "cp-copy-${System.nanoTime()}", null) != null)
    }

    @Test fun webdavWrongPassword() = runBlocking {
        val r = repo(); val p = WebDavFileSystemProvider(r)
        val c = r.add(ConnType.WEBDAV, 8088, pass = "bad")
        val e = runCatching { p.list(FsPath(FsPath.SCHEME_WEBDAV, c.id, "/")) }.exceptionOrNull()
        assertTrue("$e", e is FsException.AuthRequired)
    }

    @Test fun httpDirectoryListing() = runBlocking {
        val r = repo(); val p = HttpBrowseProvider(r)
        val c = r.add(ConnType.HTTP, 8099, user = "", pass = "")
        val root = FsPath(FsPath.SCHEME_HTTP, c.id, "/")
        val names = p.list(root).associateBy { it.name }
        assertTrue(names["sub"]!!.isDirectory)
        assertEquals(false, names["readme.txt"]!!.isDirectory)
        assertEquals("hello http\n", p.openInput(root.child("sub").child("hello.txt")).use { String(it.readBytes()) })
    }

    @Test fun smb() = runBlocking {
        org.junit.Assume.assumeTrue("smbd not running", runCatching { java.net.Socket("127.0.0.1", 4455).close() }.isSuccess)
        val r = repo(); val p = SmbFileSystemProvider(r)
        val c = r.add(ConnType.SMB, 4455)
        val shares = p.list(FsPath(FsPath.SCHEME_SMB, c.id, "/")).map { it.name }
        assertTrue("shares=$shares", "test" in shares)
        exerciseProvider(p, FsPath(FsPath.SCHEME_SMB, c.id, "/test"))
    }
}
