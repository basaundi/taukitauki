package eus.basaundi.taukitauki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.FileOutputStream
import kotlin.concurrent.thread

class DictionaryDatabaseHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {

    companion object {
        private const val DATABASE_NAME = "dict.db"
    }

    init {
        thread { copyDatabaseIfNotExists() }
    }

    private fun copyDatabaseIfNotExists() {
        val dbPath = context.getDatabasePath(DATABASE_NAME)
        if (!dbPath.exists()) {
            dbPath.parentFile?.mkdirs()
            try {
                context.assets.open(DATABASE_NAME).use { input ->
                    FileOutputStream(dbPath).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {}
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun getSuggestions(prefix: String, limit: Int = 5): List<String> {
        if (prefix.isBlank()) return emptyList()
        val suggestions = mutableListOf<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT word FROM words WHERE word LIKE ? ORDER BY frequency DESC LIMIT ?",
                arrayOf("$prefix%", limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) suggestions.add(cursor.getString(0))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return suggestions
    }

    fun getBigramSuggestions(prevWord: String, limit: Int = 5): List<String> {
        if (prevWord.isBlank()) return emptyList()
        val suggestions = mutableListOf<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT next_word FROM bigrams WHERE prev_word = ? ORDER BY frequency DESC LIMIT ?",
                arrayOf(prevWord.lowercase(), limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) suggestions.add(cursor.getString(0))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return suggestions
    }

    fun getEmojiSuggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT emoji FROM emojis WHERE name LIKE ? LIMIT ?",
                arrayOf("$prefix%", limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) results.add(cursor.getString(0))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

}
