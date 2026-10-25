package com.xander.files.domain.fs

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileSystemRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards FileSystemProvider>,
) {
    private val byScheme: Map<String, FileSystemProvider> = providers.associateBy { it.scheme }

    fun provider(path: FsPath): FileSystemProvider =
        byScheme[path.scheme] ?: throw FsException.Unsupported("scheme ${path.scheme}")

    fun providerOrNull(scheme: String): FileSystemProvider? = byScheme[scheme]

    operator fun get(path: FsPath): FileSystemProvider = provider(path)

    val schemes: Set<String> get() = byScheme.keys
}
