package com.miniproject.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "SoundLogs.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_LOGS = "logs"
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_SOUND_CLASS = "soundClass"
        const val COLUMN_DB_LEVEL = "dbLevel"
        const val COLUMN_CONFIDENCE = "confidence"
        const val COLUMN_IS_EMERGENCY = "isEmergency"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_LOGS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TIMESTAMP INTEGER,
                $COLUMN_SOUND_CLASS TEXT,
                $COLUMN_DB_LEVEL REAL,
                $COLUMN_CONFIDENCE REAL,
                $COLUMN_IS_EMERGENCY INTEGER
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    /**
     * Inserts a new log entry. Keeps only the last 500 entries to save space.
     */
    fun insertLog(entry: LogEntry): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_TIMESTAMP, entry.timestamp)
            put(COLUMN_SOUND_CLASS, entry.soundClass)
            put(COLUMN_DB_LEVEL, entry.dbLevel)
            put(COLUMN_CONFIDENCE, entry.confidence)
            put(COLUMN_IS_EMERGENCY, if (entry.isEmergency) 1 else 0)
        }
        val id = db.insert(TABLE_LOGS, null, values)
        
        // Trim old logs to keep max 500
        val countCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOGS", null)
        if (countCursor.moveToFirst() && countCursor.getInt(0) > 500) {
            db.execSQL("DELETE FROM $TABLE_LOGS WHERE $COLUMN_ID NOT IN (SELECT $COLUMN_ID FROM $TABLE_LOGS ORDER BY $COLUMN_TIMESTAMP DESC LIMIT 500)")
        }
        countCursor.close()
        
        return id
    }

    /**
     * Retrieves all logs ordered by timestamp descending.
     */
    fun getAllLogs(): List<LogEntry> {
        val logList = mutableListOf<LogEntry>()
        val db = this.readableDatabase
        val sortOrder = "$COLUMN_TIMESTAMP DESC"
        val cursor: Cursor = db.query(TABLE_LOGS, null, null, null, null, null, sortOrder)

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                val soundClass = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SOUND_CLASS))
                val dbLevel = cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_DB_LEVEL))
                val confidence = cursor.getFloat(cursor.getColumnIndexOrThrow(COLUMN_CONFIDENCE))
                val isEmergency = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_EMERGENCY)) == 1

                logList.add(LogEntry(id, timestamp, soundClass, dbLevel, confidence, isEmergency))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return logList
    }
    
    fun clearLogs() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM $TABLE_LOGS")
    }
}
