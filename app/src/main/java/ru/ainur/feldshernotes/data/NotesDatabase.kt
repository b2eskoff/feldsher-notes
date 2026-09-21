package ru.ainur.feldshernotes.data

import android.content.Context
import ru.ainur.feldshernotes.profile.*
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "calls", indices = [Index(value = ["date"])])
data class CallEntity(@PrimaryKey val id: String,val date: String,val time: String?,val title: String,val description: String,val blocksJson: String,val createdAt: Long,val updatedAt: Long) {
    fun record() = CallRecord(id,date,time,title,description,RecordCodec.blocksFromJson(blocksJson),createdAt,updatedAt)
    companion object { fun from(r: CallRecord) = CallEntity(r.id,r.date,r.time,r.title,r.description,RecordCodec.blocksToJson(r.blocks),r.createdAt,r.updatedAt) }
}
@Entity(tableName = "draft") data class DraftEntity(@PrimaryKey val slot: Int = 1,val json: String,val isNew: Boolean)
@Entity(tableName = "preferences") data class PreferenceEntity(@PrimaryKey val key: String,val value: String)
@Entity(tableName = "crew") data class CrewEntity(@PrimaryKey val date: String,val driver: String = "",val first: String = "",val second: String = "") {
    val summary get() = listOf(driver,first,second).filter(String::isNotBlank).joinToString(" · ")
}
@Entity(tableName = "audio") data class AudioEntity(@PrimaryKey val id: String,val fileName: String,val title: String,val startedAt: Long,val endedAt: Long,val durationMs: Long)
@Dao interface NotesDao {
    @Query("SELECT * FROM work_shifts ORDER BY start") fun observeShifts(): Flow<List<WorkShift>>
    @Query("SELECT * FROM work_shifts ORDER BY start") suspend fun allShifts(): List<WorkShift>
    @Upsert suspend fun putShift(shift: WorkShift)
    @Query("DELETE FROM work_shifts WHERE id = :id") suspend fun deleteShiftRow(id: String)
    @Query("SELECT * FROM call_shift_links") fun observeLinks(): Flow<List<CallShiftLink>>
    @Query("SELECT * FROM call_shift_links") suspend fun allLinks(): List<CallShiftLink>
    @Upsert suspend fun putLink(link: CallShiftLink)
    @Query("DELETE FROM call_shift_links WHERE shiftId = :id") suspend fun unlinkShift(id: String)
    @Transaction suspend fun deleteShift(id: String) { unlinkShift(id);deleteShiftRow(id) }
    @Query("SELECT * FROM achievement_unlocks") fun observeUnlocks(): Flow<List<AchievementUnlock>>
    @Query("SELECT * FROM achievement_unlocks") suspend fun allUnlocks(): List<AchievementUnlock>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun unlock(award: AchievementUnlock)
    @Query("SELECT value FROM preferences WHERE `key` = :key") fun observePreference(key: String): Flow<String?>
    @Query("SELECT * FROM calls") suspend fun allCalls(): List<CallEntity>
    @Query("SELECT * FROM preferences") suspend fun allPreferences(): List<PreferenceEntity>
    @Query("SELECT * FROM crew") suspend fun allCrew(): List<CrewEntity>
    @Query("SELECT * FROM audio ORDER BY endedAt DESC") suspend fun allAudio(): List<AudioEntity>
    @Query("SELECT * FROM crew") fun observeCrew(): Flow<List<CrewEntity>>
    @Query("SELECT * FROM audio ORDER BY endedAt DESC") fun observeAudio(): Flow<List<AudioEntity>>
    @Upsert suspend fun putCrew(crew: CrewEntity)
    @Upsert suspend fun putAudio(audio: AudioEntity)
    @Query("DELETE FROM audio WHERE id = :id") suspend fun deleteAudio(id: String)
    @Query("SELECT * FROM calls") fun observeCalls(): Flow<List<CallEntity>>
    @Query("SELECT * FROM calls WHERE id = :id") suspend fun getCall(id: String): CallEntity?
    @Upsert suspend fun upsertCall(call: CallEntity)
    @Query("DELETE FROM calls WHERE id = :id") suspend fun deleteCall(id: String)
    @Query("SELECT * FROM draft WHERE slot = 1") suspend fun draft(): DraftEntity?
    @Upsert suspend fun putDraft(draft: DraftEntity)
    @Query("DELETE FROM draft") suspend fun clearDraft()
    @Query("SELECT value FROM preferences WHERE `key` = :key") suspend fun preference(key: String): String?
    @Upsert suspend fun putPreference(preference: PreferenceEntity)
    @Transaction suspend fun commit(record: CallEntity) { upsertCall(record);clearDraft();putPreference(PreferenceEntity("selectedDate",record.date)) }
}
@Database(entities = [CallEntity::class,DraftEntity::class,PreferenceEntity::class,CrewEntity::class,AudioEntity::class,WorkShift::class,CallShiftLink::class,AchievementUnlock::class],version = 3,exportSchema = true)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun notes(): NotesDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS crew (date TEXT NOT NULL, driver TEXT NOT NULL, first TEXT NOT NULL, second TEXT NOT NULL, PRIMARY KEY(date))")
                db.execSQL("CREATE TABLE IF NOT EXISTS audio (id TEXT NOT NULL, fileName TEXT NOT NULL, title TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2,3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS work_shifts (id TEXT NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL, night INTEGER NOT NULL, completed INTEGER NOT NULL, confirmed INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_work_shifts_start_end ON work_shifts(start,end)")
                db.execSQL("CREATE TABLE IF NOT EXISTS call_shift_links (callId TEXT NOT NULL, shiftId TEXT NOT NULL, PRIMARY KEY(callId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS achievement_unlocks (id TEXT NOT NULL, unlockedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        fun open(context: Context): NotesDatabase {
            MigrationSafety.beforeOpen(context)
            return Room.databaseBuilder(context.applicationContext,NotesDatabase::class.java,"feldsher-notes.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
        }
    }
}
class NotesRepository(private val dao: NotesDao,private val afterChange: suspend () -> Unit = {}) {
    val calls = dao.observeCalls().map { sortCalls(it.map(CallEntity::record)) }
    suspend fun get(id: String) = dao.getCall(id)?.record()
    suspend fun draft() = dao.draft()?.let { RecordCodec.decode(it.json) to it.isNew }
    suspend fun saveDraft(record: CallRecord,isNew: Boolean) = dao.putDraft(DraftEntity(json = RecordCodec.encode(record),isNew = isNew))
    suspend fun discardDraft() = dao.clearDraft()
    suspend fun save(record: CallRecord) { require(validRecord(record));dao.commit(CallEntity.from(record));afterChange() }
    suspend fun delete(id: String) { dao.deleteCall(id);afterChange() }
    suspend fun selectedDate() = dao.preference("selectedDate")
    suspend fun selectDate(date: String) = dao.putPreference(PreferenceEntity("selectedDate",date))
}
