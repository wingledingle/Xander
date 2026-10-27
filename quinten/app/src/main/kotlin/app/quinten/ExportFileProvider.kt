package app.quinten

/** Own subclass so this provider doesn't merge with the host app's FileProvider entry (same class name = same key). */
class ExportFileProvider : androidx.core.content.FileProvider()
