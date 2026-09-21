package ru.ainur.feldshernotes.ui

import org.junit.Assert.*
import org.junit.Test

class ReferenceSearchTest {
    private val entries = ReferenceCatalog.items

    @Test fun tradeNameFindsActiveIngredient() {
        val hits = ReferenceSearch.find(entries, "Церукал")
        assertEquals(listOf("Метоклопрамид"), hits.map { it.item.title })
        assertFalse(hits.single().related)
    }
    @Test fun compositeConditionSuggestsRelatedTopicsNotExactCodes() {
        val hits = ReferenceSearch.find(entries, "холецистопанкреатит", ReferenceKind.ICD)
        assertEquals(setOf("Холецистит", "Острый панкреатит"), hits.map { it.item.title }.toSet())
        assertTrue(hits.all { it.related })
        assertEquals(setOf("K81", "K85"), hits.map { it.item.code }.toSet())
    }
    @Test fun partialCompoundTermSuggestsTwoRelatedTopics() {
        val hits = ReferenceSearch.find(entries, "холецистопа", ReferenceKind.ICD)
        assertEquals(setOf("K81", "K85"), hits.map { it.item.code }.toSet())
        assertTrue(hits.all { it.related })
    }
    @Test fun latinAndKnownTradeNamesAreSearchable() {
        listOf("Metoclopramidum", "Metoclopramidi", "Церук", "Перинорм", "Метоклопрамид-ЭСКОМ")
            .forEach { assertEquals("med-metoclopramide", ReferenceSearch.find(entries, it).single().item.id) }
    }
    @Test fun notEveryPancreatitisIsAutomaticallyAcutePancreatitis() {
        val hits = ReferenceSearch.find(entries, "панкреатит", ReferenceKind.ICD)
        assertTrue(hits.any { it.item.code == "K85" && it.related })
    }
    @Test fun normalizationAndKindFilterAreIndependentOfNetwork() {
        assertEquals("холецистит", ReferenceSearch.normalize("  ХОЛЕЦИСТИТ!!!  "))
        assertEquals("е е", ReferenceSearch.normalize("Ё Ё"))
        assertTrue(ReferenceSearch.find(entries, "Церукал", ReferenceKind.ICD).isEmpty())
    }
    @Test fun emptyQueryDoesNotReturnUnrelatedMedicalEntries() {
        assertTrue(ReferenceSearch.find(entries, " ").isEmpty())
        assertTrue(ReferenceSearch.find(entries, "абракадабра").isEmpty())
    }
    @Test fun fullLatinFormGoesToCorrectReviewedCard() {
        for (input in listOf("Sol. Natrii chloridi 0,9%", "Solutio Natrii chloridi 0,9%")) {
            val hits = ReferenceSearch.find(entries, input, ReferenceKind.MEDICINE)
            assertEquals("med-sodium-chloride-09", hits.first().item.id)
            assertFalse(hits.first().related)
        }
        assertTrue(ReferenceSearch.find(entries, "Sol. Natrii chloridi 10%", ReferenceKind.MEDICINE).isEmpty())
    }
}
