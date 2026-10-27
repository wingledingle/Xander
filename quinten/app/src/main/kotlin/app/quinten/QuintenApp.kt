package app.quinten

import android.content.Context
import app.quinten.data.LibraryDatabase
import app.quinten.data.LibraryRepository
import app.quinten.ocr.TesseractOcrEngine
import app.quinten.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-wide singletons (manual dependency injection keeps the app small and fast). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val ocr = TesseractOcrEngine(context)
    val documents = AppDocuments(context, ocr)
    val db = LibraryDatabase.create(context)
    val library = LibraryRepository(context, db) { file, format, bookId -> documents.openForMetadata(file, format, bookId) }
}

/**
 * Inside Xander, Quinten is a library and Xander owns the Application class, so the container is created
 * on first use (by the reader's activities) instead of in Application.onCreate.
 */
object Quinten {
    @Volatile private var instance: AppContainer? = null
    fun container(context: Context): AppContainer =
        instance ?: synchronized(this) { instance ?: AppContainer(context.applicationContext).also { instance = it } }
}

val Context.container: AppContainer get() = Quinten.container(this)
