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
class Mkb140UiTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var view: View
 private fun start(compact:Boolean=false) {compose.setContent {view=LocalView.current;NotesTheme {MkbScreen(compact,{})}}}
 private fun await(tag:String) {compose.waitUntil(20000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
 private fun shot(name:String) {compose.waitForIdle();compose.runOnIdle {
  val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b))
  val f=File("build/screenshots/$name.png");f.parentFile!!.mkdirs();f.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
 }}
 @Test fun hierarchyMatchesTheFourRequestedLevelsAndOpensClinicalCard() {
  start();await("mkbRow:I");shot("mkb-classes")
  compose.onNodeWithTag("mkbRow:I").performClick();await("mkbRow:A00-A09");shot("mkb-groups")
  compose.onNodeWithTag("mkbRow:A00-A09").performClick();await("mkbRow:A00");shot("mkb-diseases")
  compose.onNodeWithTag("mkbRow:A00").performClick();await("mkbRow:A00.9");shot("mkb-subcodes")
  compose.onNodeWithTag("mkbRow:A00.9").performClick();await("mkbClinical")
  compose.onNodeWithTag("mkbCopy").performClick()
  compose.onNodeWithText("  Скопировано").assertExists();shot("mkb-cholera-card")
 }
 @Test fun wholePhraseTypingSynonymsAndReturnFromResult() {
  start();compose.onNodeWithTag("mkbSearch").performTextInput("киста")
  await("mkbRow:G93.0")
  compose.onNodeWithTag("mkbSearch").performTextInput(" головного")
  await("mkbRow:G93.0")
  compose.onNodeWithTag("mkbSearch").performTextInput(" мозга")
  await("mkbRow:G93.0");shot("mkb-whole-phrase")
  compose.onNodeWithTag("mkbRow:G93.0").performClick();await("mkbClinical");shot("mkb-cyst-card")
  compose.onNodeWithContentDescription("Назад").performClick();await("mkbRow:G93.0")
  compose.onNodeWithTag("mkbSearch").assertTextContains("киста головного мозга")
 }
 @Test @Config(sdk=[35],qualifiers="w320dp-h340dp-mdpi") fun compactSearchAndCodeRemainAccessible() {
  start(true);compose.onNodeWithTag("mkbSearch").performTextInput("отек квинке")
  await("mkbRow:T78.3");shot("mkb-compact-search")
  compose.onNodeWithTag("mkbRow:T78.3").performClick();await("mkbCopy");shot("mkb-compact-card")
 }
}
