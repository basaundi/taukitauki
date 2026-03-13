package eus.basaundi.zirkimako

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.FileOutputStream
import java.io.File
import kotlin.concurrent.thread

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {

    companion object {
        private const val DATABASE_NAME = "dict.db"
    }

    init {
        // Warning: If dict.db is large, doing this in init (Main Thread) will cause UI lag.
        // It's pushed to a background thread here to prevent blocking the keyboard's startup.
        thread {
            copyDatabaseIfNotExists()
        }
    }

    private fun copyDatabaseIfNotExists() {
        val dbPath = context.getDatabasePath(DATABASE_NAME)
        if (!dbPath.exists()) {
            dbPath.parentFile?.mkdirs()
            
            try {
                context.assets.open(DATABASE_NAME).use { input ->
                    FileOutputStream(dbPath).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Pre-filled file used, no logic needed
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun getSuggestions(prefix: String, limit: Int = 5): List<String> {
        if (prefix.isBlank()) return emptyList()
        val suggestions = mutableListOf<String>()
        
        try {
            val db = readableDatabase
            // Use Kotlin's .use{} to ensure the cursor is strictly closed, preventing memory leaks
            db.rawQuery(
                "SELECT word FROM words WHERE word LIKE ? ORDER BY frequency DESC LIMIT ?", 
                arrayOf("$prefix%", limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    suggestions.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return suggestions
    }
}
