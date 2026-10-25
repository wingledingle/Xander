package com.xander.files.domain.fs

enum class FileCategory {
    FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, APK, ARCHIVE, TEXT, CODE, PDF, EBOOK, ENCRYPTED, GAME, OTHER;

    companion object {
        private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico", "tif", "tiff", "dng", "raw", "cr2", "nef", "arw")
        private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "3g2", "m4v", "flv", "wmv", "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "ogv", "rmvb", "f4v")
        private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "amr", "mid", "midi", "ape", "alac", "aiff", "aif", "mka", "m4b")
        val documents = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv", "pages", "numbers", "key", "wps", "et", "dps")
        private val archive = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "lz", "lzma", "z", "jar", "obb", "cab", "iso", "zst", "apks", "xapk", "apkm")
        private val text = setOf("txt", "log", "md", "markdown", "ini", "cfg", "conf", "properties", "prop", "srt", "ass", "vtt", "nfo", "rc", "env")
        private val code = setOf("java", "kt", "kts", "js", "ts", "jsx", "tsx", "py", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "rb", "php", "html", "htm", "xml", "css", "scss", "json", "yaml", "yml", "toml", "sh", "bash", "bat", "ps1", "sql", "gradle", "lua", "pl", "dart", "vue", "smali")
        /** Console ROMs, disc images used by emulators, and emulator saves/memory cards. */
        val games = setOf(
            "nes", "fds", "unf", "sfc", "smc", "fig", "swc", "gb", "gbc", "gba", "agb", "nds", "dsi", "3ds", "cia", "cci", "cxi", "3dsx",
            "n64", "z64", "v64", "ndd", "gcm", "gcz", "rvz", "wbfs", "wad", "wia", "ciso", "dol", "xci", "nsp", "nsz", "xcz", "nro",
            "gen", "smd", "sms", "gg", "sg", "32x", "pce", "sgx", "a26", "a52", "a78", "lnx", "jag", "j64", "ws", "wsc", "ngp", "ngc", "vb", "vec",
            "cso", "zso", "chd", "pbp", "ecm", "gdi", "cdi", "cue", "mds", "ccd", "psv", "vpk", "xex",
            "sav", "srm", "state", "mcr", "mcd", "dsv", "sgm",
        )
        private val ebook = setOf("epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr")

        fun of(name: String, mime: String? = null): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "apk" -> APK
                ext == "pdf" -> PDF
                ext == ENCRYPTED_EXTENSION -> ENCRYPTED
                ext in games -> GAME
                ext in image -> IMAGE
                ext in video -> VIDEO
                ext in audio -> AUDIO
                ext in documents -> DOCUMENT
                ext in ebook -> EBOOK
                ext in archive -> ARCHIVE
                ext in code -> CODE
                ext in text -> TEXT
                mime?.startsWith("image/") == true -> IMAGE
                mime?.startsWith("video/") == true -> VIDEO
                mime?.startsWith("audio/") == true -> AUDIO
                mime?.startsWith("text/") == true -> TEXT
                else -> OTHER
            }
        }

        const val ENCRYPTED_EXTENSION = "xenc"
    }
}
