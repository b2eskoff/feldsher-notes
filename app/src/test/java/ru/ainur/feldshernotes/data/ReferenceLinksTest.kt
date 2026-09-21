package ru.ainur.feldshernotes.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReferenceLinksTest {
    private val label="Урапидил 5 мг/мл"
    private val original="Шприц 20,0: $label\nНатрия хлорид"
    private val link=ReferenceLink(12,12+label.length,label,"med-urapidil")
    @Test fun referencesSurviveRecordBackupAndRestore() {
        val block=NoteBlock(kind=BlockKind.MEDICINES,values=mapOf("text" to original,"references" to ReferenceLinks.encode(listOf(link))))
        val r=CallRecord(description="Существующий вызов",blocks=listOf(block,NoteBlock(kind=BlockKind.DIAGNOSIS,values=mapOf("text" to "Моя формулировка без кода"))))
        val copy=RecordCodec.decode(RecordCodec.encode(r))
        assertEquals(r,copy)
        assertEquals(listOf(link),ReferenceLinks.decode(copy.blocks[0].value("references"),original))
    }
    @Test fun insertionBeforeLinkShiftsIt() {
        assertEquals(link.copy(start=14,end=link.end+2),ReferenceLinks.edit(original,"! "+original,listOf(link)).single())
    }
    @Test fun modifyingDrugInvalidatesOnlyThatLink() {
        assertTrue(ReferenceLinks.edit(original,original.replace("Урапидил","Другой"),listOf(link)).isEmpty())
    }
    @Test fun typingAfterLinkKeepsIt() {
        assertEquals(listOf(link),ReferenceLinks.edit(original,original+"\nещё",listOf(link)))
    }
    @Test fun newlineAndSyringePrefixStayOutsideQuery() {
        assertEquals(12 to "ура",ReferenceLinks.candidates("Шприц 20,0: ура",15,emptyList()).last())
        assertEquals("натрий хл",ReferenceLinks.candidates("Шприц 20,0: ура\nнатрий хл",25,emptyList()).first().second)
    }
    @Test fun staleOffsetsNeverCreateWrongLinks() {
        assertTrue(ReferenceLinks.decode(ReferenceLinks.encode(listOf(link)),"Другой текст").isEmpty())
    }
    @Test fun partialDeletionDropsLinkAndRetainsOtherText() {
        val updated=original.removeRange(13,16)
        assertTrue(ReferenceLinks.edit(original,updated,listOf(link)).isEmpty())
    }
}
