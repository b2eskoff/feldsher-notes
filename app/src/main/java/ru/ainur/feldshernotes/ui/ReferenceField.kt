package ru.ainur.feldshernotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.ainur.feldshernotes.data.*

internal fun referenceAnnotation(text: String,links: List<ReferenceLink>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    links.filter { it.start>=0 && it.end<=text.length && it.end>it.start }.forEach {
        addStyle(SpanStyle(color=color,textDecoration=TextDecoration.Underline),it.start,it.end)
        addStringAnnotation("reference",it.target,it.start,it.end)
    }
}
@Suppress("DEPRECATION")
@Composable internal fun LinkedNoteText(block: NoteBlock, onOpen: (String)->Unit) {
    val text=block.value("text")
    val links=remember(text,block.value("references")) { ReferenceLinks.decode(block.value("references"),text) }
    ClickableText(referenceAnnotation(text,links,Accent),style=MaterialTheme.typography.bodyLarge.copy(color=Ink),modifier=Modifier.testTag("linkedNote"),onClick={ offset ->
        links.firstOrNull { offset>=it.start && offset<it.end }?.let { onOpen(it.target) }
    })
}
@Composable internal fun ReferenceOverlay(id: String,compact: Boolean,onDismiss: ()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {ReferenceScreen(compact,onDismiss,initialId=id)}
    }
}
private data class Suggestion(val item: ReferenceItem,val start: Int,val end: Int,val original: String)

@Composable internal fun ReferenceField(block: NoteBlock,compact: Boolean,enabled: Boolean,update: (Map<String,String>)->Unit) {
    val context=LocalContext.current
    val medicines=remember {MedicineRepository(context)}; val icd=remember {IcdRepository(context)}
    var field by rememberSaveable(block.id,stateSaver=TextFieldValue.Saver) {mutableStateOf(TextFieldValue(block.value("text")))}
    var focused by remember {mutableStateOf(false)}
    var suggestions by remember {mutableStateOf(emptyList<Suggestion>())}
    var selected by remember {mutableStateOf<Suggestion?>(null)}
    var referenceId by rememberSaveable {mutableStateOf("")}
    var linksMenu by remember {mutableStateOf(false)}
    var failed by remember {mutableStateOf(false)}
    val links=remember(block.value("text"),block.value("references")) {ReferenceLinks.decode(block.value("references"),block.value("text"))}
    LaunchedEffect(block.value("text")) {
        if(field.text!=block.value("text")) field=TextFieldValue(block.value("text"),TextRange(field.selection.end.coerceAtMost(block.value("text").length)))
    }
    LaunchedEffect(field.text,field.selection,focused) {
        suggestions=emptyList();failed=false
        if(!focused || !field.selection.collapsed || !enabled) return@LaunchedEffect
        val snapshot=field.text;val cursor=field.selection.end
        val candidates=ReferenceLinks.candidates(snapshot,cursor,links)
        if(candidates.isEmpty()) return@LaunchedEffect
        delay(180)
        try {
            suggestions=withContext(Dispatchers.IO) {
                var found=emptyList<Suggestion>()
                for((start,term) in candidates) {
                    val hits=if(block.kind==BlockKind.DIAGNOSIS) icd.search(term,12) else medicines.search(term,8)
                    if(hits.isNotEmpty()) {found=hits.map {Suggestion(it,start,cursor,snapshot)};break}
                }
                found
            }
        } catch(e: CancellationException) {throw e}
        catch(_: Exception) {failed=true}
    }
    fun insert(s: Suggestion,label: String) {
        if(field.text!=s.original || s.end>field.text.length) {selected=null;return}
        val newText=field.text.replaceRange(s.start,s.end,label)
        val nextLinks=ReferenceLinks.replace(links,s.start,s.end,label.length)+ReferenceLink(s.start,s.start+label.length,label,s.item.id)
        field=TextFieldValue(newText,TextRange(s.start+label.length))
        update(mapOf("text" to newText,"references" to ReferenceLinks.encode(nextLinks)))
        suggestions=emptyList();selected=null
    }
    Column {
        TextField(value=field,onValueChange={ next ->
            if(next.text!=field.text) update(mapOf("text" to next.text,"references" to ReferenceLinks.encode(ReferenceLinks.edit(field.text,next.text,links))))
            field=next
        },modifier=Modifier.fillMaxWidth().onFocusChanged {focused=it.isFocused}.testTag(if(block.kind==BlockKind.DIAGNOSIS) "diagnosisField" else "medicinesField"),
            enabled=enabled,minLines=if(compact) 2 else 3,
            label={Text(if(block.kind==BlockKind.DIAGNOSIS) "Диагноз · свободный ввод" else "Препараты · свободный ввод")},
            placeholder={Text(block.kind.hint,color=Muted)},
            visualTransformation=VisualTransformation {TransformedText(referenceAnnotation(it.text,links,Accent),OffsetMapping.Identity)},
            shape=RoundedCornerShape(16.dp),
            colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,disabledContainerColor=Color.Transparent,
                focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,disabledIndicatorColor=Color.Transparent))
        if(suggestions.isNotEmpty() && focused) Column(Modifier.fillMaxWidth().padding(horizontal=5.dp).background(Tint,RoundedCornerShape(16.dp)).padding(6.dp)) {
            Text(if(block.kind==BlockKind.DIAGNOSIS) "Выбери подходящую формулировку" else "Добавить из справочника",color=Muted,fontSize=11.sp,modifier=Modifier.padding(8.dp))
            suggestions.take(if(compact) 3 else 5).forEach { s ->
                Surface(onClick={if(s.item.kind==ReferenceKind.ICD) insert(s,"${s.item.title} [${s.item.code}]") else selected=s},color=Color.Transparent,shape=RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(if(s.item.kind==ReferenceKind.ICD) "${s.item.code} · ${s.item.title}" else s.item.title,fontWeight=FontWeight.Medium,fontSize=14.sp)
                            if(s.item.kind==ReferenceKind.MEDICINE) Text(listOf(s.item.latin,s.item.tradeNames.take(3).joinToString(" · ")).filter(String::isNotBlank).joinToString("\n"),color=Muted,fontSize=11.sp)
                        }
                        Icon(Icons.Outlined.Add,null,tint=Accent,modifier=Modifier.size(19.dp))
                    }
                }
            }
            Text("Можно продолжить писать без выбора.",fontSize=11.sp,color=Muted,modifier=Modifier.padding(8.dp))
        }
        if(failed) Text("Подсказки пока недоступны. Текст можно сохранить как обычно.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(12.dp))
        if(links.isNotEmpty()) Box {
            TextButton({linksMenu=true}) { Icon(Icons.Outlined.MenuBook,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("Связанные карточки · ${links.size}",fontSize=12.sp) }
            DropdownMenu(linksMenu,{linksMenu=false}) {
                links.distinctBy {it.target}.forEach { link -> DropdownMenuItem(text={Text(link.label,maxLines=3)},onClick={linksMenu=false;referenceId=link.target}) }
            }
        }
    }
    selected?.let { s -> MedicineFormPicker(s.item,compact,{selected=null},onReference={referenceId=s.item.id}) { label ->insert(s,label)} }
    if(referenceId.isNotBlank()) ReferenceOverlay(referenceId,compact) {referenceId=""}
}

@Composable private fun MedicineFormPicker(item: ReferenceItem,compact: Boolean,onDismiss: ()->Unit,onReference: ()->Unit,onSelect: (String)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var latin by rememberSaveable {mutableStateOf(false)}
    val forms=remember(item,query) {val terms=ReferenceSearch.normalize(query).split(' ').filter(String::isNotBlank)
        item.medicineForms.filter { f ->val text=ReferenceSearch.normalize(f.label+" "+f.brands.joinToString(" "));terms.all(text::contains)} }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().imePadding()) {
            PageHeader("Выбрать форму",compact,onDismiss)
            Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text(item.title,style=MaterialTheme.typography.titleLarge)
                if(item.latin.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(latin,{latin=it});Text("Вставлять латинское название",fontSize=13.sp)
                }
                OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("Концентрация, объём или название…")},modifier=Modifier.fillMaxWidth().testTag("formSearch"),shape=RoundedCornerShape(16.dp))
                Text("Выбери имеющуюся форму. Фактически введённый объём можно дописать в записи.",color=Muted,fontSize=12.sp)
                Row {TextButton(onReference) {Text("Открыть справку")};TextButton({onSelect(if(latin && item.latin.isNotBlank()) item.latin else item.title)}) {Text("Только название")}}
            }
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(forms.isEmpty()) item {Text("Нет такой формы. Можно вставить название и дописать сведения вручную.",color=Muted)}
                items(forms,key={it.label}) { form ->
                    Surface(onClick={onSelect(medicineInsertion(item,form,latin))},shape=RoundedCornerShape(18.dp),color=Color.White) {
                        Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(form.label,fontWeight=FontWeight.Medium)
                            Text(form.brands.take(4).joinToString(" · "),fontSize=12.sp,color=Muted)
                        }
                    }
                }
            }
        }
    }
}
internal fun medicineInsertion(item: ReferenceItem,form: MedicineForm,latin: Boolean): String {
    val name=if(latin && item.latin.isNotBlank()) item.latin else item.title
    // A verified solution line is used only when its exact form/concentration is known.
    val saline=if(latin) VerifiedLatin.solutionLine(item.inn,form.label) else null
    val label=form.label.replace("раствор для внутривенного и внутримышечного введения","р-р в/в и в/м")
        .replace("раствор для внутривенного введения","р-р в/в").replace("раствор для внутримышечного введения","р-р в/м")
        .replace("раствор для инъекций","р-р для инъекций").replace("раствор для инфузий","р-р для инфузий")
    return "${saline ?: name} ($label)"
}
