package ru.ainur.feldshernotes.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Mkb140Test {
 private val context=ApplicationProvider.getApplicationContext<Context>()
 private val repo=IcdRepository(context)
 @Test fun completeHierarchyAndOrderedBranches() {
  val chapters=repo.chapters();assertEquals(22,chapters.size)
  val groups=repo.children(chapters.first().id.removePrefix("icd-nsi-").toLong())
  val group=groups.first {it.code=="A00-A09"}
  val disease=repo.children(group.id.removePrefix("icd-nsi-").toLong()).first {it.code=="A00"}
  assertEquals(listOf("A00.0","A00.1","A00.9"),repo.children(disease.id.removePrefix("icd-nsi-").toLong()).map {it.code})
  val cancer=repo.children(chapters[1].id.removePrefix("icd-nsi-").toLong())
  assertTrue(cancer.first().code.startsWith("C"))
 }
 @Test fun everydayPhrasesAndAbbreviationsKeepTheirMeaning() {
  val cases=mapOf("воспаление легких" to "J18.9","камни в почках" to "N20.0","мерцательная аритмия" to "I48","отек квинке" to "T78.3","сгм" to "S06.0","тэла" to "I26","белая горячка" to "F10.4","боль в пояснице" to "M54.5","церебральную кисту" to "G93.0","низкий сахар" to "E16.2","воспаление желчного пузыря" to "K81","онмк неуточненное" to "I64")
  for((query,prefix) in cases) {
   val hits=repo.search(query,terminalOnly=true)
   assertTrue("$query => ${hits.map {it.code}}",hits.any {IcdLanguage.inFamily(it.code,prefix)})
   assertTrue(query,hits.none {repo.hasChildren(it.id)})
  }
 }
 @Test fun fifthCharacterCodesInheritAliasesWithoutLeakingToOtherDiseases() {
  assertEquals(setOf("S06.00","S06.01"),repo.search("сгм",terminalOnly=true).map {it.code}.toSet())
  assertTrue(IcdLanguage.inFamily("S06.01","S06.0"))
  assertFalse(IcdLanguage.inFamily("S06.10","S06.0"))
  assertFalse(IcdLanguage.inFamily("I110","I11"))
  assertFalse(IcdLanguage.inFamily("T40.5","T40.2"))
 }
 @Test fun typoIsLabeledAndUnknownContextIsNotDiscarded() {
  val hits=repo.search("пневмания",terminalOnly=true)
  assertTrue(hits.any {it.code=="J18.9" && it.searchHint.contains("написание")})
  assertTrue(repo.search("киста головного мозга выдуманноеуточнение",terminalOnly=true).isEmpty())
  assertTrue(repo.search("киста спинного мозга",terminalOnly=true).none {it.code=="G93.0"})
 }
 @Test fun stagesDoNotSilentlySelectTargetOrganDisease() {
  val hits=repo.search("гипертоническая болезнь 2 стадии",terminalOnly=true)
  assertTrue(hits.any {it.code=="I10"})
  assertTrue(hits.any {it.code=="I11.9"})
  assertTrue(hits.all {it.searchHint.contains("Стадия")})
 }
 @Test fun combinedDiagnosisIsExplicitlyOnlyAComponent() {
  val hits=repo.search("холецистопанкреатит",terminalOnly=true)
  assertTrue(hits.any {it.code.startsWith("K81")})
  assertTrue(hits.any {it.code.startsWith("K85")})
  assertTrue(hits.all {it.searchHint.contains("не полный диагноз")})
 }
 @Test fun codesAcceptCyrillicLookalikesButDoNotAutocorrectDigits() {
  assertEquals("I11.9",repo.search("и11.9").first().code)
  assertEquals("K85.9",repo.search("к85.9").first().code)
  assertEquals("A00.9",repo.search("а00.9").first().code)
  assertTrue(repo.search("I1199").isEmpty())
 }
 @Test fun negationAndCongenitalQualifierArePreserved() {
  assertTrue(repo.search("гипертоническая болезнь без сердечной недостаточности",terminalOnly=true).none {it.code=="I11.0"})
  assertEquals("Q04.6",repo.search("врожденная киста головного мозга",terminalOnly=true).first().code)
  assertFalse(repo.search("приобретенная киста мозга",terminalOnly=true).any {it.code=="Q04.6"})
 }
 @Test fun guidesAreSourcedAndMappedOnlyToExistingCodes() {
  val guides=IcdGuides.all(context);assertEquals(35,guides.size)
  for(g in guides) {
   assertTrue(g.title,g.sources.isNotEmpty());assertTrue(g.scope.isNotBlank())
   assertTrue(g.summary.isNotBlank());assertTrue(g.symptoms.isNotBlank())
   assertTrue(g.tactics.isNotBlank());assertTrue(g.medications.isNotBlank())
   assertTrue(g.sources.all {it.url.startsWith("https://") && it.type.isNotBlank()})
   for(c in g.codes) assertTrue("Missing $c",repo.search(c).any {it.code==c})
  }
  assertEquals("Церебральная киста",IcdGuides.forCode(context,"G93.0")?.title)
  assertEquals("Холера",IcdGuides.forCode(context,"A00.9")?.title)
  assertNull(IcdGuides.forCode(context,"T40.5")) // Cocaine is not an opioid.
  assertNull(IcdGuides.forCode(context,"K81.1")) // Do not present acute care as chronic disease care.
  assertNull(IcdGuides.forCode(context,"C34.9")) // No fabricated fallback treatment.
 }
}
