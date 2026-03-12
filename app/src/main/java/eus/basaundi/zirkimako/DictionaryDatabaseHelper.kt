package eus.basaundi.zirkimako

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.FileOutputStream

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {

    companion object {
        private const val DATABASE_NAME = "dict.db"
    }

    init {
        copyDatabaseIfNotExists()
    }

    private fun copyDatabaseIfNotExists() {
        val dbPath = context.getDatabasePath(DATABASE_NAME)
        if (!dbPath.exists()) {
            // Ensure the directory exists
            dbPath.parentFile?.mkdirs()
            
            context.assets.open(DATABASE_NAME).use { input ->
                FileOutputStream(dbPath).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // No logic needed here because we are providing the pre-filled file
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun getSuggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isBlank()) return emptyList()
        val suggestions = mutableListOf<String>()
        
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT word FROM words WHERE word LIKE ? ORDER BY frequency DESC LIMIT ?", 
                arrayOf("$prefix%", limit.toString())
            )
            
            while (cursor.moveToNext()) {
                suggestions.add(cursor.getString(0))
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return suggestions
    }
}
