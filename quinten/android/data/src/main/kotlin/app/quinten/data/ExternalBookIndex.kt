package app.quinten.data

import android.content.Context
import java.io.File

/**
 * Xander integration: when the host app already keeps an index of the phone's files, the library scan reads book
 * files from it instead of walking every storage volume again. Null (standalone Quinten) means walk as usual.
 */
object ExternalBookIndex {
    @Volatile var provider: ((Context) -> List<File>)? = null
}
