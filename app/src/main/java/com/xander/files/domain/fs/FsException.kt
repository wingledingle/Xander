package com.xander.files.domain.fs

import java.io.IOException

/** Typed failures surfaced to the UI with clear messages. */
sealed class FsException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class NotFound(val path: FsPath) : FsException("Not found: ${path.name.ifEmpty { path.toString() }}")
    class AlreadyExists(val path: FsPath) : FsException("Already exists: ${path.name}")
    class PermissionDenied(val path: FsPath?, cause: Throwable? = null) : FsException("Permission denied${path?.let { ": ${it.path}" } ?: ""}", cause)
    class NetworkLost(cause: Throwable? = null) : FsException("Network connection lost", cause)
    class AuthRequired(val authority: String, cause: Throwable? = null) : FsException("Sign-in required or expired", cause)
    class OutOfSpace(cause: Throwable? = null) : FsException("Not enough free space", cause)
    class PasswordRequired(val path: FsPath) : FsException("Password required")
    class WrongPassword(cause: Throwable? = null) : FsException("Wrong password", cause)
    class Unsupported(op: String) : FsException("Not supported here: $op")
    class NotADirectory(val path: FsPath) : FsException("Not a folder: ${path.name}")
    class InvalidName(name: String) : FsException("Invalid name: $name")
    class Cancelled : FsException("Cancelled")
    open class Generic(message: String, cause: Throwable? = null) : FsException(message, cause)

    val retryable: Boolean get() = this is NetworkLost

    companion object {
        /** Maps arbitrary platform/library exceptions to typed ones. */
        fun wrap(t: Throwable, path: FsPath? = null): FsException = when (t) {
            is FsException -> t
            is kotlinx.coroutines.CancellationException -> Cancelled()
            is SecurityException -> PermissionDenied(path, t)
            is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.UnknownHostException,
            is java.net.NoRouteToHostException, is java.net.SocketException, is javax.net.ssl.SSLException -> NetworkLost(t)
            is java.io.FileNotFoundException -> {
                val m = t.message.orEmpty()
                when {
                    m.contains("EACCES") || m.contains("Permission denied") -> PermissionDenied(path, t)
                    m.contains("ENOSPC") -> OutOfSpace(t)
                    path != null -> NotFound(path)
                    else -> Generic(m, t)
                }
            }
            else -> {
                val m = t.message.orEmpty()
                when {
                    m.contains("ENOSPC") || m.contains("No space left") -> OutOfSpace(t)
                    m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied") -> PermissionDenied(path, t)
                    else -> Generic(m.ifBlank { t.javaClass.simpleName }, t)
                }
            }
        }
    }
}
