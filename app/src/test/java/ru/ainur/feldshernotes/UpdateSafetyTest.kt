package ru.ainur.feldshernotes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ainur.feldshernotes.data.*
import ru.ainur.feldshernotes.audio.PacketRing
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UpdateSafetyTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val fixture=CallRecord(id="old-call",date="2026-08-04",time=null,title="Старый вызов",description="Сохранить всё: АД 210/110",blocks=BlockKind.entries.map{NoteBlock(kind=it,values=mapOf("text" to "Данные ${it.name}","bp" to "210/110"))},createdAt=1000,updatedAt=2000)
    private fun seed(){
        context.deleteDatabase("feldsher-notes.db")
        val path=context.getDatabasePath("feldsher-notes.db");path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use{db->
            db.execSQL("CREATE TABLE calls (id TEXT NOT NULL PRIMARY KEY,date TEXT NOT NULL,time TEXT,title TEXT NOT NULL,description TEXT NOT NULL,blocksJson TEXT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_calls_date ON calls(date)")
            db.execSQL("CREATE TABLE draft (slot INTEGER NOT NULL PRIMARY KEY,json TEXT NOT NULL,isNew INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE preferences (`key` TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO calls VALUES (?,?,?,?,?,?,?,?)",arrayOf<Any?>(fixture.id,fixture.date,fixture.time,fixture.title,fixture.description,RecordCodec.blocksToJson(fixture.blocks),fixture.createdAt,fixture.updatedAt))
            db.version=1
        }
    }
    @Test fun migrationPreservesEveryFieldAndCreatesSafetyCopy()=runBlocking {
        seed();val db=NotesDatabase.open(context)
        try{assertEquals(fixture,db.notes().getCall(fixture.id)!!.record());assertTrue(db.notes().allCrew().isEmpty())
            db.notes().putCrew(CrewEntity(fixture.date,"Водитель","Первый","Второй"));assertEquals(1,db.notes().allCrew().size)
            assertEquals(1,searchCalls(db.notes().allCalls().map{it.record()},"210/110").size)
            val copies=File(context.noBackupFilesDir,"safety").listFiles()!!.filter{it.extension=="sqlite"};assertTrue(copies.isNotEmpty())
            SQLiteDatabase.openDatabase(copies.last().path,null,SQLiteDatabase.OPEN_READONLY).use{assertEquals(1,it.version)}
        }finally{db.close()}
    }
    @Test fun migrationFailureRollsBackWithoutDeletingOldCall()=runBlocking {
        seed();MigrationSafety.beforeOpen(context)
        val broken=Room.databaseBuilder(context,NotesDatabase::class.java,"feldsher-notes.db").addMigrations(object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("CREATE TABLE interrupted (id INTEGER)");error("injected failure")}}).build()
        try{try{broken.notes().allCalls();fail("Migration must fail")}catch(_:IllegalStateException){}}finally{broken.close()}
        SQLiteDatabase.openDatabase(context.getDatabasePath("feldsher-notes.db").path,null,SQLiteDatabase.OPEN_READONLY).use{db->
            assertEquals(1,db.version);db.rawQuery("SELECT description FROM calls WHERE id='old-call'",null).use{assertTrue(it.moveToFirst());assertEquals(fixture.description,it.getString(0))}
            db.rawQuery("SELECT name FROM sqlite_master WHERE name='interrupted'",null).use{assertFalse(it.moveToFirst())}
        }
        val good=NotesDatabase.open(context);try{assertEquals(fixture,good.notes().getCall(fixture.id)!!.record())}finally{good.close()}
    }
    @Test fun failedSafetyCopyDoesNotStartMigration()=runBlocking {
        seed()
        val blocker=File(context.noBackupFilesDir,"safety")
        blocker.deleteRecursively();blocker.writeText("Storage unavailable")
        try {
            try { NotesDatabase.open(context).close();fail("Opening must fail before migration") }
            catch(e:IllegalStateException) { assertTrue(e.message.orEmpty().contains("Защитная копия")) }
            SQLiteDatabase.openDatabase(context.getDatabasePath("feldsher-notes.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db->
                assertEquals(1,db.version)
                db.rawQuery("SELECT description,blocksJson FROM calls WHERE id='old-call'",null).use { c->
                    assertTrue(c.moveToFirst());assertEquals(fixture.description,c.getString(0))
                    assertEquals(RecordCodec.blocksToJson(fixture.blocks),c.getString(1))
                }
                db.rawQuery("SELECT name FROM sqlite_master WHERE name='crew'",null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { blocker.delete() }
        val retry=NotesDatabase.open(context)
        try { assertEquals(fixture,retry.notes().getCall(fixture.id)!!.record()) } finally { retry.close() }
    }
    @Test fun backupRoundTripAndInvalidRestoreAreSafe()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,NotesDatabase::class.java).build();val store=BackupStore(context,db)
        try{db.notes().upsertCall(CallEntity.from(fixture));db.notes().putCrew(CrewEntity(fixture.date,"Иванов"))
            val bytes=byteArrayOf(1,2,3,4,5);val recording=AudioEntity("audio-test","test.m4a","Фрагмент",1000,3000,2000)
            File(store.audioDir,recording.fileName).writeBytes(bytes);db.notes().putAudio(recording)
            val output=ByteArrayOutputStream();store.export(output);db.notes().deleteCall(fixture.id)
            db.notes().deleteAudio(recording.id);File(store.audioDir,recording.fileName).delete()
            val another=fixture.copy(id="unrelated",description="Не заменять");db.notes().upsertCall(CallEntity.from(another))
            store.restore(ByteArrayInputStream(output.toByteArray()),true)
            assertEquals(fixture,db.notes().getCall(fixture.id)!!.record());assertEquals(another,db.notes().getCall(another.id)!!.record())
            val restoredAudio=db.notes().allAudio().single();assertEquals(recording.title,restoredAudio.title);assertArrayEquals(bytes,File(store.audioDir,restoredAudio.fileName).readBytes())
            assertTrue(store.copies.listFiles()!!.any{it.name.startsWith("before-restore-") && it.extension=="zip"})
            try{store.restore(ByteArrayInputStream("{}".toByteArray()),false);fail("Invalid backup must fail")}catch(_:Exception){}
            assertEquals(2,db.notes().allCalls().size)
            repeat(9){store.autoBackup()};assertEquals(7,store.copies.listFiles()!!.count{it.name.startsWith("auto-") && it.extension=="json"})
        }finally{db.close()}
    }
    @Test fun snapshotStaysIntactThroughRotationAndOverlappingSave(){
        val directory=File(context.cacheDir,"ring-test-${System.nanoTime()}");val ring=PacketRing(directory)
        try{for(second in 0..1800)ring.append(second*1_000_000L,byteArrayOf(1,2,3))
            val first=ring.snapshot();for(second in 1801..2100)ring.append(second*1_000_000L,byteArrayOf(4,5))
            val second=ring.snapshot();val firstTimes=mutableListOf<Long>();val secondTimes=mutableListOf<Long>()
            ring.read(first){pts,_->firstTimes+=pts};ring.read(second){pts,_->secondTimes+=pts}
            assertEquals(900_000_000L,firstTimes.first());assertEquals(1800_000_000L,firstTimes.last());assertEquals(901,firstTimes.size)
            assertEquals(1200_000_000L,secondTimes.first());assertEquals(2100_000_000L,secondTimes.last())
            ring.release(first);ring.release(second);assertTrue(directory.listFiles()!!.size<=32);assertEquals(900000L,ring.durationMs())
        }finally{ring.close();directory.deleteRecursively()}
    }
}
