package com.truesteps.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DayTotal(val day: String, val walkSteps: Int, val vehicleSteps: Int)

data class LogEntry(val id: Long, val startMs: Long, val steps: Int, val kind: Kind, val reason: String)

class StepDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "truesteps.db", null, 1) {

    companion object {
        @Volatile private var instance: StepDatabase? = null

        fun get(context: Context): StepDatabase =
            instance ?: synchronized(this) {
                instance ?: StepDatabase(context).also { instance = it }
            }

        fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE windows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                steps INTEGER NOT NULL,
                kind TEXT NOT NULL,
                reason TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_windows_day ON windows(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(w: CommittedWindow) {
        val v = ContentValues().apply {
            put("day", dayKey(w.startMs))
            put("start_ms", w.startMs)
            put("end_ms", w.endMs)
            put("steps", w.steps)
            put("kind", w.kind.name)
            put("reason", w.reason)
        }
        writableDatabase.insert("windows", null, v)
    }

    fun dayTotal(day: String): DayTotal {
        var walk = 0
        var vehicle = 0
        readableDatabase.rawQuery(
            "SELECT kind, SUM(steps) FROM windows WHERE day = ? GROUP BY kind", arrayOf(day)
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == Kind.WALK.name) walk = c.getInt(1) else vehicle = c.getInt(1)
            }
        }
        return DayTotal(day, walk, vehicle)
    }

    /** Approximate walking time: kept 30-second blocks with real walking in them. */
    fun walkMinutes(day: String): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM windows WHERE day = ? AND kind = ? AND steps >= 15",
            arrayOf(day, Kind.WALK.name)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) / 2 else 0 }
    }

    /** Manual correction from the decisions list. */
    fun setKind(id: Long, kind: Kind) {
        val v = ContentValues().apply {
            put("kind", kind.name)
            put("reason", if (kind == Kind.WALK) "Corrected by you: walking" else "Corrected by you: vehicle")
        }
        writableDatabase.update("windows", v, "id = ?", arrayOf(id.toString()))
    }

    /** Totals for the last [days] days, newest first (days without data are included as zero). */
    fun recentDays(days: Int, nowMs: Long = System.currentTimeMillis()): List<DayTotal> =
        (0 until days).map { dayTotal(dayKey(nowMs - it * 86_400_000L)) }

    fun recentLog(limit: Int): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        readableDatabase.rawQuery(
            "SELECT id, start_ms, steps, kind, reason FROM windows ORDER BY start_ms DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += LogEntry(c.getLong(0), c.getLong(1), c.getInt(2), Kind.valueOf(c.getString(3)), c.getString(4))
            }
        }
        return out
    }
}
