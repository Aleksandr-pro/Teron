package app.teron.superapp.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class TeronFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "app.teron.superapp.provider"

        fun getUriForSharedFile(context: Context, file: File): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("shared")
                .appendPath(file.name)
                .build()
        }

        fun sharedDir(context: Context): File {
            val dir = File(context.cacheDir, "shared")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    override fun onCreate(): Boolean = true

    private fun resolveFile(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "shared") {
            throw FileNotFoundException("Invalid URI: $uri")
        }
        val safeName = File(segments[1]).name
        val target = File(sharedDir(ctx), safeName)
        if (!target.exists()) throw FileNotFoundException("Not found: $safeName")
        return target
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = Array<Any?>(cols.size) { i ->
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment ?: return "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/png"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
