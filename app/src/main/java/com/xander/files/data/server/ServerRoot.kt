package com.xander.files.data.server

import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath

/** Maps client-visible paths ("/DCIM/a.jpg") onto a backend folder, never allowing escape above it. */
class ServerRoot(val root: FsPath, private val registry: FileSystemRegistry, val readOnly: Boolean) {
    val provider: FileSystemProvider get() = registry.provider(root)

    fun virtual(cwd: String, arg: String): String =
        FsPath.normalize(if (arg.startsWith("/")) arg else "${cwd.trimEnd('/')}/$arg")

    fun toFs(virtual: String): FsPath {
        val v = FsPath.normalize(virtual)
        if (v == "/") return root
        return FsPath(root.scheme, root.authority, FsPath.normalize(root.path.trimEnd('/') + v))
    }
}
