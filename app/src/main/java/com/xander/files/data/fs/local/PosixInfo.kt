package com.xander.files.data.fs.local

import android.system.Os
import android.system.OsConstants

object PosixInfo {
    private val userNames = HashMap<Int, String>()

    fun permissionString(mode: Int): String {
        val sb = StringBuilder()
        sb.append(
            when {
                OsConstants.S_ISDIR(mode) -> 'd'
                OsConstants.S_ISLNK(mode) -> 'l'
                OsConstants.S_ISCHR(mode) -> 'c'
                OsConstants.S_ISBLK(mode) -> 'b'
                OsConstants.S_ISFIFO(mode) -> 'p'
                OsConstants.S_ISSOCK(mode) -> 's'
                else -> '-'
            }
        )
        sb.append(rwx(mode shr 6, mode and 0b100000000000 != 0, 's'))
        sb.append(rwx(mode shr 3, mode and 0b10000000000 != 0, 's'))
        sb.append(rwx(mode, mode and 0b1000000000 != 0, 't'))
        return sb.toString()
    }

    fun octalPermissionString(mode: Int): String = permissionString(mode)

    private fun rwx(bits: Int, special: Boolean, ch: Char): String {
        val r = if (bits and 4 != 0) 'r' else '-'
        val w = if (bits and 2 != 0) 'w' else '-'
        val x = when {
            special && bits and 1 != 0 -> ch
            special -> ch.uppercaseChar()
            bits and 1 != 0 -> 'x'
            else -> '-'
        }
        return "$r$w$x"
    }

    fun userName(uid: Int): String = synchronized(userNames) {
        userNames.getOrPut(uid) {
            androidName(uid)
        }
    }

    /** Android has no /etc/passwd entries for app uids; mimic `ls` output (u0_a123). */
    private fun androidName(uid: Int): String {
        val user = uid / 100000
        val app = uid % 100000
        return when {
            app == 0 -> "root"
            app == 1000 -> "system"
            app == 1015 || app == 1023 || app == 1028 -> "media_rw"
            app == 2000 -> "shell"
            app >= 10000 -> "u${user}_a${app - 10000}"
            else -> uid.toString()
        }
    }

    /** Parses "rwxr-x---" (9 chars, optionally with leading type) into an octal mode. */
    fun parseMode(s: String): Int {
        val p = if (s.length == 10) s.substring(1) else s
        var m = 0
        p.forEachIndexed { i, c ->
            val bit = 1 shl (8 - i)
            if (c != '-' && c != 'S' && c != 'T') m = m or bit
        }
        if (p.getOrNull(2) in listOf('s', 'S')) m = m or 0b100000000000
        if (p.getOrNull(5) in listOf('s', 'S')) m = m or 0b10000000000
        if (p.getOrNull(8) in listOf('t', 'T')) m = m or 0b1000000000
        return m
    }
}
