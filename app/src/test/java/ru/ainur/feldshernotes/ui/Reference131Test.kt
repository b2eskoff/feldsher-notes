package ru.ainur.feldshernotes.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.ainur.feldshernotes.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Reference131Test {
 private val context=ApplicationProvider.getApplicationContext<Context>()
 @Test fun entireCystPhraseKeepsItsMeaning() {
  val repo=IcdRepository(context)
  for(q in listOf("киста головного","киста головного мозга","головного мозга киста","кисту головного мозга","!киста головного мозга")) {
   val hits=repo.search(q,terminalOnly=true)
   assertEquals(q,"G93.0",hits.first().code)
   assertTrue(q,hits.all {it.code in setOf("G93.0","Q04.6")})
  }
  assertTrue(repo.search("киста",terminalOnly=true).any {it.code=="G93.0"})
 }
 @Test fun qualifiersAreNotDropped() {
  val repo=IcdRepository(context)
  assertEquals("Q04.6",repo.search("врожденная киста головного мозга",terminalOnly=true).first().code)
  assertTrue(repo.search("киста головного мозга несуществующееуточнение",terminalOnly=true).isEmpty())
  assertFalse(repo.search("киста спинного мозга",terminalOnly=true).any {it.code=="G93.0"})
 }
 @Test fun editorNeverReturnsParentNodes() {
  val repo=IcdRepository(context)
  for(q in listOf("I11","гипертонич","киста","диабет","пневмония")) {
   val hits=repo.search(q,terminalOnly=true)
   assertTrue(q,hits.isNotEmpty())
   assertTrue(q,hits.none {repo.hasChildren(it.id)})
  }
  assertEquals(setOf("I11.0","I11.9"),repo.search("I11",terminalOnly=true).map {it.code}.toSet())
  assertEquals("I10",repo.search("I10",terminalOnly=true).first().code)
  assertTrue(repo.search("I11").any {it.code=="I11"})
 }
 @Test fun hypertensionIsVisibleBeforeUnrelatedObstetricResults() {
  val top=IcdRepository(context).search("гипертонич",terminalOnly=true).take(5)
  assertTrue(top.any {it.code=="I11.9"});assertTrue(top.any {it.code=="I10"})
  assertTrue(top.none {it.code.startsWith("O")})
 }
 @Test fun markerReplacesOnlyTheRequestedFragment() {
  val text="Сопутствующее со слов пациента !киста головного мозга"
  val q=ReferenceLinks.diagnosisCandidate(text,text.length,emptyList())!!
  assertEquals(text.indexOf('!'),q.first);assertEquals("киста головного мозга",q.second)
  val next=text.replaceRange(q.first,text.length,"Церебральная киста [G93.0]")
  assertEquals("Сопутствующее со слов пациента Церебральная киста [G93.0]",next)
  val plain="киста головного мозга"
  assertEquals(0 to plain,ReferenceLinks.diagnosisCandidate(plain,plain.length,emptyList()))
 }
 @Test fun magnesiumIsOneCardAndAllOldIdsStillResolve() {
  val repo=MedicineRepository(context)
  val hits=repo.search("магнезия")
  assertEquals(1,hits.size);assertEquals("Магния сульфат",hits.single().title)
  for(id in listOf("med-e34a3f0001527af86f20","med-89d5509f5aa12c393efb","med-cc56804b76eae7b1a29b","med-a10e0d574b11ed5903d3"))
   assertEquals(hits.single().id,repo.item(id)?.id)
  assertTrue(hits.single().medicineForms.any {it.label.contains("250 мг/мл")})
  assertTrue(hits.single().medicineForms.any {it.label.contains("порошок")})
 }
 @Test fun combinationsStayAccessibleWithoutClutteringIngredientSearch() {
  val repo=MedicineRepository(context)
  assertEquals(listOf("Парацетамол"),repo.search("парацетамол").map {it.title})
  assertTrue(repo.search("парацетамол",includeCombinations=true).any {'+' in it.inn})
  assertTrue(repo.search("терафлю").any {'+' in it.inn})
  assertTrue(repo.search("ибупрофен+парацетамол").any {'+' in it.inn})
 }
 @Test fun commonLanguageKeepsOfficialTitles() {
  val repo=IcdRepository(context)
  assertEquals("J18.9",repo.search("воспаление легких",terminalOnly=true).first().code)
  assertEquals("E11.9",repo.search("сахарный диабет 2 типа",terminalOnly=true).first().code)
  assertEquals("Церебральная киста",repo.search("киста головного мозга",terminalOnly=true).first().title)
 }
}
