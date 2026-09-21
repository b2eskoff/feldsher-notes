package ru.ainur.feldshernotes.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OfflineReferenceIntegrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun urapidilHasFormsAndClinicalInformationOffline() {
        val repo=MedicineRepository(context)
        val item=repo.search("ура").first {it.title=="Урапидил"}
        assertEquals("Urapidilum",item.latin)
        assertTrue(item.notes.contains("0,9%"))
        assertTrue(item.medicineForms.any {it.label.contains("5 мг/мл") && it.label.contains(", 5 мл")})
        assertTrue(item.medicineForms.any {it.label.contains("5 мг/мл") && it.label.contains(", 10 мл")})
        assertEquals(item.id,repo.item(item.id)?.id)
    }
    @Test fun tradeNamesResolveToOneInnAndCombinationsStaySeparate() {
        val repo=MedicineRepository(context)
        assertEquals("Метоклопрамид",repo.search("церукал").first().title)
        assertEquals(repo.search("церукал").first().id,repo.search("метоклопрамид").first().id)
        assertEquals("Бисопролол",repo.search("конкор").first {it.title=="Бисопролол"}.title)
        assertTrue(repo.search("конкор").any { it.title.contains("+") || it.title.contains("Амлодипин") })
    }
    @Test fun salineCanBeFoundByColloquialPartialAndLatinNames() {
        val repo=MedicineRepository(context)
        listOf("физраствор","натрий хл","Natrii chloridi").forEach { q ->assertTrue(q,repo.search(q).any {it.title=="Натрия хлорид"}) }
    }
    @Test fun hypertensionOffersPreciseCodesAndCyrillicIWorks() {
        val repo=IcdRepository(context)
        val results=repo.search("гипертонич")
        assertTrue(results.any {it.code=="I10"})
        assertTrue(results.any {it.code=="I11.9"})
        assertTrue(results.any {it.code=="I11.0"})
        assertEquals("I11.9",repo.search("и11.9").first().code)
    }
    @Test fun noClinicalAdviceIsInventedForUnreviewedDrug() {
        val repo=MedicineRepository(context)
        val item=repo.search("абатацепт").first()
        assertFalse(item.clinical)
        assertTrue(item.dosing.isBlank())
    }
}
