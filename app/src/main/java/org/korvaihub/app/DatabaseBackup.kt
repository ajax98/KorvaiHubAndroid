package org.korvaihub.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Export a consistent SQLite snapshot, and restore classification rows without replacing a live Room file. */
object DatabaseBackup {
    suspend fun export(context: Context, db: KorvaiDb, destination: Uri): Int = withContext(Dispatchers.IO) {
        val snapshot = File.createTempFile("korvaihub-export-", ".sqlite", context.cacheDir)
        try {
            // Logical snapshot works on Android 8+, whose SQLite lacks VACUUM INTO.
            val rows = db.withTransaction { db.dao().snapshot() }
            SQLiteDatabase.openOrCreateDatabase(snapshot, null).use { backup ->
                backup.execSQL("CREATE TABLE korvais (`key` TEXT PRIMARY KEY NOT NULL, talam TEXT NOT NULL, kaalam TEXT NOT NULL, nadai TEXT NOT NULL, edam TEXT NOT NULL, akshara TEXT NOT NULL, mathirai TEXT NOT NULL, purvangaMathirai TEXT NOT NULL, madhyangaMathirai TEXT NOT NULL, utarangaMathirai TEXT NOT NULL, tag TEXT NOT NULL, notes TEXT NOT NULL, favorite INTEGER NOT NULL)")
                backup.beginTransaction()
                try {
                    rows.forEach { row ->
                        backup.execSQL("INSERT INTO korvais VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(row.key, row.talam, row.kaalam, row.nadai, row.edam, row.akshara, row.mathirai, row.purvangaMathirai, row.madhyangaMathirai, row.utarangaMathirai, row.tag, row.notes, if (row.favorite) 1 else 0))
                    }
                    backup.setTransactionSuccessful()
                } finally { backup.endTransaction() }
            }
            context.contentResolver.openOutputStream(destination)?.use { output ->
                snapshot.inputStream().use { it.copyTo(output) }
            } ?: error("Unable to open backup destination")
            rows.size
        } finally { snapshot.delete() }
    }

    suspend fun restore(context: Context, db: KorvaiDb, source: Uri): Int = withContext(Dispatchers.IO) {
        val file = File.createTempFile("korvaihub-import-", ".sqlite", context.cacheDir)
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: error("Unable to read selected backup")
            require(file.length() in 100L..100_000_000L) { "Backup is empty or too large" }
            val restored = mutableListOf<Korvai>()
            val sourceDb = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val hasTable = sourceDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='korvais'", null).use { it.moveToFirst() }
                require(hasTable) { "Not a KorvaiHub database backup" }
                sourceDb.rawQuery("SELECT `key`,talam,kaalam,nadai,edam,akshara,mathirai,purvangaMathirai,madhyangaMathirai,utarangaMathirai,tag,notes,favorite FROM korvais", null).use { c ->
                    while (c.moveToNext()) {
                        fun str(i: Int) = c.getString(i) ?: ""
                        restored.add(Korvai(str(0), str(1), str(2), str(3), str(4), str(5), str(6), str(7), str(8), str(9), str(10), str(11), c.getInt(12) != 0))
                    }
                }
            } finally { sourceDb.close() }
            // Merge by stable S3 key: restores existing classifications and keeps any newer objects.
            db.withTransaction { db.dao().upsertAll(restored) }
            restored.size
        } finally { file.delete() }
    }
}
