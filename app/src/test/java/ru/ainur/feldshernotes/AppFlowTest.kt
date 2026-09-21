package ru.ainur.feldshernotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.ainur.feldshernotes.data.*
import ru.ainur.feldshernotes.ui.*
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: NotesDatabase;private lateinit var repo: NotesRepository;private lateinit var model: NotesViewModel
    private lateinit var renderedView: View;private var cover by mutableStateOf(false)
    private var textScale = 1f
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),NotesDatabase::class.java).build()
        repo = NotesRepository(db.notes());model = NotesViewModel(repo,SavedStateHandle())
    }
    @After fun teardown() { model.viewModelScope.cancel();db.close() }
    private fun awaitState(condition: () -> Boolean) { compose.waitUntil(15000) { compose.waitForIdle();condition() } }
    private fun launch() {
        compose.setContent { renderedView = LocalView.current;NotesTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,textScale)) {
                Box(Modifier.size(if (cover) 320.dp else 412.dp,if (cover) 340.dp else 915.dp)) { NotesApp(model) }
            }
        } }
        awaitState { model.state.value.ready }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle();val bounds = compose.onNodeWithTag("appRoot").fetchSemanticsNode().boundsInRoot
        val out = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(),bounds.height.roundToInt(),Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap);canvas.translate(-bounds.left,-bounds.top);renderedView.draw(canvas)
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun createOldUntimedCallEditSearchAndKeepDate() {
        launch();compose.runOnIdle { model.selectDate("2026-08-04") }
        compose.onNodeWithTag("newCall").performClick()
        compose.onNodeWithTag("descriptionField").performTextInput("Женщина 68 лет. Головная боль. АД 210/110.")
        compose.onNodeWithText("Без времени").assertExists();compose.onNodeWithTag("saveCall").performClick()
        awaitState { model.state.value.screen == Screen.JOURNAL && model.state.value.calls.size == 1 }
        assertNull(model.state.value.calls.single().time);assertEquals("2026-08-04",model.state.value.selectedDate)
        compose.onNodeWithText("Женщина 68 лет. Головная боль. АД 210/110.").performClick()
        awaitState { model.state.value.screen == Screen.DETAIL }
        compose.onNodeWithTag("editCall").performClick()
        awaitState { model.state.value.screen == Screen.EDITOR }
        compose.onNodeWithTag("descriptionField").performTextReplacement("После помощи лучше. Оставлена дома.")
        compose.onNodeWithTag("saveCall").performClick();awaitState { model.state.value.draft == null && model.state.value.screen == Screen.JOURNAL }
        compose.onNodeWithContentDescription("Поиск вызовов").performClick();compose.onNodeWithTag("searchField").performTextInput("оставлена")
        compose.onNodeWithText("После помощи лучше. Оставлена дома.").assertExists()
        compose.runOnIdle { model.back();model.newCall() };assertEquals("2026-08-04",model.state.value.draft?.date);assertNull(model.state.value.draft?.time)
    }
    @Test fun coverToInnerResizeRetainsDraft() {
        cover = true;launch();compose.onNodeWithTag("journalCompact").assertExists();compose.onNodeWithTag("newCall").performClick()
        compose.onNodeWithTag("descriptionField").performTextInput("Начато на внешнем экране")
        val id = model.state.value.draft!!.id;screenshot("editor-cover")
        compose.runOnIdle { cover = false };compose.onNodeWithTag("descriptionField").assertTextContains("Начато на внешнем экране")
        assertEquals(id,model.state.value.draft!!.id);screenshot("editor-inner")
        compose.onNodeWithTag("saveCall").performClick();awaitState { model.state.value.calls.size == 1 && model.state.value.draft == null }
        assertEquals("Начато на внешнем экране",runBlocking { repo.get(id) }!!.description)
    }
    @Test fun coverLargeTextKeepsSaveAndBackAccessible() {
        cover = true;textScale = 2f;launch()
        compose.onNodeWithTag("newCall").performClick()
        compose.onNodeWithTag("descriptionField").performTextInput("Крупный текст на внешнем экране")
        compose.onNodeWithTag("saveCall").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
        screenshot("editor-cover-large-text")
        compose.onNodeWithTag("saveCall").performClick()
        awaitState { model.state.value.calls.size == 1 && model.state.value.draft == null }
    }
    @Test fun calendarTimeAndOnlyOneVitalWork() {
        launch();compose.onNodeWithTag("newCall").performClick();compose.onNodeWithTag("editDate").performClick()
        compose.onNodeWithText("Ввести дату").performClick();compose.onNodeWithTag("manualDate").performTextReplacement("04082026")
        compose.onNodeWithText("Выбрать",useUnmergedTree = true).performClick();assertEquals("2026-08-04",model.state.value.draft!!.date)
        compose.onNodeWithTag("editTime").performClick();compose.onNodeWithTag("timeHour").performTextInput("20");compose.onNodeWithTag("timeMinute").performTextInput("37")
        compose.onNodeWithText("Готово").performClick();assertEquals("20:37",model.state.value.draft!!.time)
        compose.onNodeWithTag("editTime").performClick();compose.onNodeWithTag("noTime").performClick();assertNull(model.state.value.draft!!.time)
        compose.onNodeWithTag("addBlock").performScrollTo().performClick();compose.onNodeWithText("Показатели").performClick()
        compose.onNodeWithTag("vital_bp").performScrollTo().performTextInput("170/100");compose.onNodeWithTag("saveCall").performClick()
        awaitState { model.state.value.calls.size == 1 && model.state.value.draft == null }
        val r = model.state.value.calls.single();assertTrue(r.description.isEmpty());assertEquals("170/100",r.blocks.single().value("bp"));assertTrue(r.blocks.single().value("pulse").isEmpty())
    }
    @Test fun topMenuWorksOnCoverAndKeepsNewCallVisibleAfterResize() {
        cover = true;launch()
        compose.onNodeWithTag("newCall").assertIsDisplayed()
        compose.onNodeWithTag("sectionMenu").performClick();screenshot("menu-cover")
        compose.onNodeWithTag("section_MEMORY").performClick()
        compose.onNodeWithText("Запустить буфер").assertIsDisplayed()
        compose.onNodeWithTag("sectionMenu").performClick()
        compose.onNodeWithTag("section_MORE").performClick()
        compose.onNodeWithText("Создать резервную копию").assertIsDisplayed()
        compose.onNodeWithTag("sectionMenu").performClick()
        compose.onNodeWithTag("section_JOURNAL").performClick()
        compose.onNodeWithTag("newCall").assertIsDisplayed()
        compose.runOnIdle { cover = false }
        compose.onNodeWithTag("sectionMenu").performClick();screenshot("menu-inner")
        compose.onNodeWithTag("section_JOURNAL").performClick()
        compose.onNodeWithTag("newCall").assertIsDisplayed()
    }
    @Test fun renderMemoryBothSizes() {
        launch();compose.runOnIdle { model.openSection(Screen.MEMORY) };compose.onNodeWithText("Запустить буфер").assertIsDisplayed();screenshot("memory-inner")
        compose.runOnIdle { cover=true };compose.onNodeWithText("Запустить буфер").assertIsDisplayed();screenshot("memory-cover")
    }
    @Test fun renderJournalBothSizesWithFictionalFixtures() {
        val today = LocalDate.now().toString()
        runBlocking {
            repo.save(CallRecord(date = today,time = "20:37",title = "Боль в груди",blocks = listOf(
                NoteBlock(kind = BlockKind.PERSON,values = mapOf("sex" to "М","age" to "54")),NoteBlock(kind = BlockKind.VITALS,values = mapOf("bp" to "170/100","spo2" to "96")),
                NoteBlock(kind = BlockKind.MEDICINES,values = mapOf("text" to "Аспирин")),NoteBlock(kind = BlockKind.OUTCOME,values = mapOf("text" to "Госпитализация")))))
            repo.save(CallRecord(date = today,title = "Высокое давление",blocks = listOf(NoteBlock(kind = BlockKind.PERSON,values = mapOf("sex" to "Ж","age" to "68")),
                NoteBlock(kind = BlockKind.VITALS,values = mapOf("bp" to "210/110")),NoteBlock(kind = BlockKind.OUTCOME,values = mapOf("text" to "Оказана помощь, оставлена дома")))))
            repo.save(CallRecord(date = today,time = "10:15",title = "Одышка",description = "Жалобы на одышку несколько часов. Подробности вызова — в заметке."))
        }
        launch();awaitState { model.state.value.calls.size == 3 };screenshot("journal-inner")
        compose.runOnIdle { cover = true };compose.onNodeWithTag("journalCompact").assertExists();screenshot("journal-cover")
        compose.onNodeWithText("Боль в груди").performClick();compose.onNodeWithText("Редактировать").assertExists();screenshot("detail-cover")
    }
}
