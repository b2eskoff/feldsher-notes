package ru.ainur.feldshernotes.ui
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceScreen131UiTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var view:View
 private fun start(compact:Boolean=false) {compose.setContent {view=LocalView.current;NotesTheme {ReferenceScreen(compact,{})}}}
 private fun shot(name:String) {
  compose.waitForIdle()
  compose.runOnIdle {
   val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
   val out=File("build/screenshots/$name.png");out.parentFile!!.mkdirs();out.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
  }
 }
 @Test fun magnesiumSearchIsOneReadableCard() {
  start();compose.onNodeWithTag("referenceSearch").performTextInput("магнезия")
  compose.waitUntil(15000) {compose.onAllNodesWithText("Магния сульфат",substring=false).fetchSemanticsNodes().size==1}
  shot("reference-magnesium")
  compose.onNodeWithText("Магния сульфат",substring=false).performClick()
  compose.waitUntil(15000) {compose.onAllNodesWithText("Для чего применяется").fetchSemanticsNodes().isNotEmpty()}
  shot("reference-magnesium-detail")
 }
 @Test fun wholeBrainCystQueryVisibleInReference() {
  start();compose.onNodeWithText("МКБ-10",substring=false).performClick()
  compose.onNodeWithTag("referenceSearch").performTextInput("киста головного мозга")
  compose.waitUntil(15000) {compose.onAllNodesWithText("Церебральная киста",substring=false).fetchSemanticsNodes().isNotEmpty()}
  shot("reference-brain-cyst")
 }
 @Test @Config(sdk=[35],qualifiers="w320dp-h340dp-mdpi") fun coverStillFindsMagnesium() {
  start(true);compose.onNodeWithTag("referenceSearch").performTextInput("магнезия")
  compose.waitUntil(15000) {compose.onAllNodesWithText("Магния сульфат",substring=false).fetchSemanticsNodes().size==1}
  shot("reference-magnesium-cover")
 }
}
