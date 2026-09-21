package ru.ainur.feldshernotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ainur.feldshernotes.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w360dp-h740dp")
class ReferenceEditorUiTest {
 @get:Rule val compose=createComposeRule()
 @Test fun syringeTextAutocompleteFormSelectionAndLinks() {
  var block by mutableStateOf(NoteBlock(kind=BlockKind.MEDICINES))
  compose.setContent { NotesTheme { Column { ReferenceField(block,false,true) { values ->block=block.copy(values=block.values+values) } } } }
  compose.onNodeWithTag("medicinesField").performTextInput("Шприц 20,0: ура")
  compose.waitUntil(15000) {compose.onAllNodesWithText("Урапидил",substring=false).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Урапидил",substring=false).performClick()
  compose.onNodeWithTag("formSearch").performTextInput("5 мг/мл 5 мл")
  compose.onAllNodesWithText("раствор для внутривенного введения, 5 мг/мл, 5 мл · ампула").onFirst().performClick()
  compose.runOnIdle {
   assertTrue(block.value("text").startsWith("Шприц 20,0: Урапидил"))
   assertEquals(1,ReferenceLinks.decode(block.value("references"),block.value("text")).size)
  }
 }
 @Test fun diagnosisAcceptsFreeTextWithoutCode() {
  var block by mutableStateOf(NoteBlock(kind=BlockKind.DIAGNOSIS))
  compose.setContent {NotesTheme { ReferenceField(block,true,true) {values ->block=block.copy(values=block.values+values)} } }
  compose.onNodeWithTag("diagnosisField").performTextInput("Моя формулировка вне МКБ")
  compose.runOnIdle {assertEquals("Моя формулировка вне МКБ",block.value("text"));assertTrue(ReferenceLinks.decode(block.value("references"),block.value("text")).isEmpty())}
 }
}
