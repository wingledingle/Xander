package com.xander.files.data.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.PrintWriter
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku process (shell uid 2000), which can read and write Android/data and Android/obb
 * on Android 11-16. Must keep a public no-arg constructor.
 */
class FileService : IFileService.Stub() {

    override fun destroy() { exitProcess(0) }

    private fun line(f: File): String {
        val mode = try { Os.lstat(f.absolutePath).st_mode } catch (e: Exception) { 0 }
        return "${if (f.isDirectory) 1 else 0}|${f.length()}|${f.lastModified()}|$mode|${f.name}"
    }

    private fun pipe(writer: (PrintWriter) -> Unit): ParcelFileDescriptor {
        val fds = ParcelFileDescriptor.createPipe()
        Thread {
            PrintWriter(ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).bufferedWriter()).use { pw ->
                try { writer(pw) } catch (_: Exception) { }
            }
        }.start()
        return fds[0]
    }

    override fun list(path: String): ParcelFileDescriptor {
        val children = File(path).listFiles() ?: throw SecurityException("cannot list $path")
        return pipe { pw -> children.forEach { pw.println(line(it)) } }
    }

    override fun stat(path: String): String? {
        val f = File(path)
        return if (f.exists()) line(f) else null
    }

    override fun openRead(path: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun openWrite(path: String): ParcelFileDescriptor {
        val f = File(path)
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(
            f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
    }

    override fun mkdir(path: String) = File(path).mkdirs()
    override fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override fun delete(path: String) = File(path).deleteRecursively()
    override fun createFile(path: String) = File(path).createNewFile()
    override fun exists(path: String) = File(path).exists()

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    /** Emits "<parent dir>/|<stat line>" for every match below [root]. */
    override fun find(root: String, needle: String, includeHidden: Boolean): ParcelFileDescriptor = pipe { pw ->
        val stack = ArrayDeque<File>().apply { add(File(root)) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (!includeHidden && k.name.startsWith(".")) continue
                if (needle.isEmpty() || k.name.contains(needle, ignoreCase = true)) pw.println("${k.parent}/|" + line(k))
                if (k.isDirectory) stack.add(k)
            }
        }
    }
}
