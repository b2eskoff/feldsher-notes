package ru.ainur.feldshernotes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ainur.feldshernotes.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private lateinit var context: Context;private lateinit var db: NotesDatabase;private lateinit var repo: NotesRepository
    @Before fun setup() { context = ApplicationProvider.getApplicationContext();context.deleteDatabase("test.db");reopen() }
    private fun reopen() { db = Room.databaseBuilder(context,NotesDatabase::class.java,"test.db").build();repo = NotesRepository(db.notes()) }
    @After fun teardown() { db.close();context.deleteDatabase("test.db") }
    @Test fun textOnlyUntimedCallSurvivesReopen() = runBlocking {
        val r = CallRecord(date = "2026-08-04",description = "Женщина. После помощи состояние лучше.")
        repo.save(r);db.close();reopen()
        assertEquals(r,repo.get(r.id));assertNull(repo.get(r.id)!!.time);assertEquals("2026-08-04",repo.selectedDate())
    }
    @Test fun draftIsSeparateAndCommitClearsIt() = runBlocking {
        val r = CallRecord(date = "2026-08-03",description = "Перевозка")
        repo.saveDraft(r,true);assertTrue(repo.calls.first().isEmpty());db.close();reopen()
        assertEquals(r,repo.draft()?.first);assertTrue(repo.draft()!!.second)
        repo.save(r);assertNull(repo.draft());assertEquals(r,repo.calls.first().single())
    }
    @Test fun allBlocksOrderRemovalAndEditSurviveReopen() = runBlocking {
        val blocks = BlockKind.entries.map { kind -> NoteBlock(kind = kind,values = when(kind) {
            BlockKind.PERSON -> mapOf("sex" to "Ж","age" to "68")
            BlockKind.VITALS -> mapOf("bp" to "210/110","pulse" to "")
            else -> mapOf("text" to "Текст ${kind.label}\nВторая строка")
        }) }
        val r = CallRecord(date = "2026-08-04",time = "20:37",blocks = blocks);repo.save(r)
        val edit = r.copy(time = null,date = "2026-08-05",blocks = blocks.drop(1).reversed(),updatedAt = r.updatedAt+50)
        repo.save(edit);db.close();reopen();assertEquals(edit,repo.get(r.id));assertEquals(1,repo.calls.first().size)
    }
    @Test fun untimedOrderStableAfterEditAndMidnightIsRealTime() = runBlocking {
        val first = CallRecord(id = "first",date = "2026-08-04",description = "Первый",createdAt = 10)
        val second = first.copy(id = "second",description = "Второй",createdAt = 20)
        val midnight = first.copy(id = "midnight",time = "00:00",createdAt = 30)
        val evening = first.copy(id = "evening",time = "20:37",createdAt = 40)
        listOf(second,evening,first,midnight).forEach { repo.save(it) };repo.save(first.copy(description = "Уточнение",updatedAt = 1000))
        assertEquals(listOf("evening","midnight","first","second"),repo.calls.first().map { it.id })
    }
    @Test fun russianSearchMatchesOldDatesAndBlocks() {
        val records = listOf(CallRecord(id = "a",date = "2026-08-04",description = "Одышка",blocks = listOf(NoteBlock(kind = BlockKind.ECG,values = mapOf("text" to "Синусовый ритм")))),
            CallRecord(id = "b",date = "2026-09-09",title = "Перевозка",description = "Тяжёлое состояние"))
        assertEquals("a",searchCalls(records,"ОДЫШКА ритм").single().id);assertEquals("b",searchCalls(records,"тяжелое").single().id)
        assertEquals("a",searchCalls(records,"04.08.2026").single().id)
        assertTrue(searchCalls(records,"").isEmpty());assertTrue(searchCalls(records,"несуществующее слово").isEmpty())
    }
    @Test fun rejectEmptyRecordAndPersistDeletion() = runBlocking {
        assertFalse(validRecord(CallRecord(description = "  ",blocks = listOf(NoteBlock(kind = BlockKind.VITALS)))))
        assertFalse(validRecord(CallRecord(description = "Текст",time = "29:00")))
        val r = CallRecord(title = "Перевозка");repo.save(r);repo.delete(r.id);db.close();reopen();assertNull(repo.get(r.id))
    }
}
