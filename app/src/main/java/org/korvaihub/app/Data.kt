package org.korvaihub.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "korvais")
data class Korvai(
    @PrimaryKey val key: String,
    val talam: String = "", val kaalam: String = "", val nadai: String = "",
    val edam: String = "", val akshara: String = "", val mathirai: String = "",
    val purvangaMathirai: String = "", val madhyangaMathirai: String = "",
    val utarangaMathirai: String = "", val tag: String = "", val notes: String = "",
    val favorite: Boolean = false
)
@Dao interface KorvaiDao {
    @Query("SELECT * FROM korvais ORDER BY key") fun observe(): Flow<List<Korvai>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertNew(rows: List<Korvai>)
    @Update suspend fun update(row: Korvai)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(rows: List<Korvai>)
}
@Database(entities = [Korvai::class], version = 1, exportSchema = false)
abstract class KorvaiDb : RoomDatabase() {
    abstract fun dao(): KorvaiDao
    companion object {
        @Volatile private var instance: KorvaiDb? = null
        fun get(context: Context): KorvaiDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KorvaiDb::class.java, "korvaihub.db").build().also { instance = it }
        }
    }
}
