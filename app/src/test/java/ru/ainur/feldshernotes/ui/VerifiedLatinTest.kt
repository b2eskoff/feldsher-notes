package ru.ainur.feldshernotes.ui

import org.junit.Assert.*
import org.junit.Test

class VerifiedLatinTest {
    @Test fun sodiumChlorideHasExactSubstanceCases() {
        val latin = VerifiedLatin.byExactInn("Натрия хлорид")!!
        assertEquals("Natrii chloridum", latin.nominative)
        assertEquals("Natrii chloridi", latin.genitive)
        assertEquals("Sol. Natrii chloridi 0,9%", VerifiedLatin.solutionLine("Натрия хлорид", "раствор для инфузий, 0.9%, 400 мл"))
        assertEquals("Sol. Natrii chloridi 0,9%", VerifiedLatin.solutionLine("Натрия хлорид", "раствор для инъекций, 9 мг/мл, 5 мл"))
    }
    @Test fun noLatinForCompoundOrWrongForm() {
        assertNull(VerifiedLatin.byExactInn("Калия хлорид+Натрия хлорид"))
        assertNull(VerifiedLatin.solutionLine("Натрия хлорид", "спрей назальный, 0.9%, 20 мл"))
        assertNull(VerifiedLatin.solutionLine("Натрия хлорид", "раствор для инфузий, 0.9%, 400 мл; спрей назальный, 0.65%, 20 мл"))
        assertNull(VerifiedLatin.solutionLine("Натрия хлорид", "раствор для инфузий, 10%, 100 мл"))
        assertNull(VerifiedLatin.solutionLine("Калия хлорид+Натрия хлорид", "раствор для инфузий, 0.9%, 200 мл"))
    }
    @Test fun latinPartialAndSodiumPrescriptionNameFindRealInn() {
        for (q in listOf("Natrii chloridi", "sol natrii chloridi 0,9%", "Natrii chlori", "физраствор")) {
            assertEquals("Натрия хлорид", VerifiedLatin.russianInnForSearch(q))
        }
        assertEquals("Метоклопрамид", VerifiedLatin.russianInnForSearch("Metoclopramidi"))
        assertEquals("Эпинефрин", VerifiedLatin.russianInnForSearch("Adrenalin"))
        assertNull(VerifiedLatin.russianInnForSearch("Noradrenalin"))
    }
    @Test fun exactSalineFormCannotSilentlyBecomeAnotherConcentration() {
        assertTrue(VerifiedLatin.exactSaline09Query("Sol. Natrii chloridi 0,9%"))
        assertTrue(VerifiedLatin.exactSaline09Query("solutio natrii chloridi 0.9 %"))
        for (different in listOf("Sol. Natrii chloridi 10%", "Sol. Natrii chloridi 0,65%", "Sol. Natrii chloridi 0,9% + Kalii chloridum")) {
            assertFalse(VerifiedLatin.exactSaline09Query(different))
            assertNull(VerifiedLatin.russianInnForSearch(different))
        }
    }
}
