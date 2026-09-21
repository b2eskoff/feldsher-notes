package ru.ainur.feldshernotes.ui
import org.junit.Assert.*
import org.junit.Test
class MedicineFormattingTest {
 @Test fun salineLatinDoesNotRelabelHypertonicSolution() {
  val item=ReferenceItem("test",ReferenceKind.MEDICINE,"Натрия хлорид",inn="Натрия хлорид",latin="Natrii chloridum")
  val good=medicineInsertion(item,MedicineForm("раствор для инфузий, 0.9%, 200 мл",emptyList()),true)
  val other=medicineInsertion(item,MedicineForm("раствор для инфузий, 10%, 200 мл",emptyList()),true)
  assertTrue(good.contains("Sol. Natrii chloridi 0,9%"));assertFalse(other.contains("0,9%"))
 }
}
