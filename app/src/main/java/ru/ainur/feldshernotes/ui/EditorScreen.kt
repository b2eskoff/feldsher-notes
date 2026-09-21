package ru.ainur.feldshernotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ainur.feldshernotes.NotesViewModel
import ru.ainur.feldshernotes.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable fun EditorScreen(r: CallRecord,isNew: Boolean,saving: Boolean,compact: Boolean,model: NotesViewModel) {
    var datePicker by rememberSaveable { mutableStateOf(false) };var timePicker by rememberSaveable { mutableStateOf(false) }
    var blockPicker by rememberSaveable { mutableStateOf(false) };var titleVisible by rememberSaveable(r.id) { mutableStateOf(r.title.isNotBlank()) }
    var menu by remember { mutableStateOf(false) };var discard by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) };val list = rememberLazyListState();val focus = LocalFocusManager.current
    val save = { focus.clearFocus();model.save() }
    val noteFocus = remember { FocusRequester() }
    LaunchedEffect(r.id) { if (isNew && !r.hasContent) noteFocus.requestFocus() }
    LaunchedEffect(r.blocks.size) { if (adding && r.blocks.isNotEmpty()) { adding = false;list.animateScrollToItem(r.blocks.size+1) } }
    Column(Modifier.fillMaxSize().imePadding().testTag("editor")) {
        PageHeader(if (compact) "Запись" else if (isNew) "Новый вызов" else "Редактирование",compact,model::back) {
            if (compact) TextButton(save,enabled = !saving,modifier = Modifier.testTag("saveCall")) { Text(if (saving) "…" else "Сохранить",fontSize = 13.sp) }
            Box {
                IconButton({ menu = true },enabled = !saving) { Icon(Icons.Outlined.MoreHoriz,"Действия с черновиком") }
                DropdownMenu(menu,{ menu = false }) { DropdownMenuItem(text = { Text("Удалить черновик") },onClick = { menu = false;discard = true },leadingIcon = { Icon(Icons.Outlined.DeleteOutline,null) }) }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("editorList"),state = list,contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 22.dp,vertical = if (compact) 5.dp else 15.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            item(key = "date-time") {
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetaButton("Дата",LocalDate.parse(r.date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),Icons.Outlined.CalendarToday,
                        { focus.clearFocus();datePicker = true },compact,!saving,Modifier.weight(1f).testTag("editDate"))
                    MetaButton("Время",r.time ?: "Без времени",Icons.Outlined.Schedule,
                        { focus.clearFocus();timePicker = true },compact,!saving,Modifier.weight(1f).testTag("editTime"))
                }
            }
            item(key = "note") {
                Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(if (compact) 21.dp else 26.dp),color = Color.White) {
                    Column(Modifier.padding(if (compact) 6.dp else 10.dp)) {
                        if (titleVisible) PlainField(r.title,{ value -> model.changeDraft { it.copy(title = value) } },"Название вызова","Например, высокое давление",
                            Modifier.fillMaxWidth().testTag("titleField"),!saving,singleLine = true,textStyle = TextStyle(fontSize = 20.sp,fontWeight = FontWeight.SemiBold,color = Ink))
                        else TextButton({ titleVisible = true },enabled = !saving,contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.ShortText,null,Modifier.size(20.dp),tint = Muted);Spacer(Modifier.width(7.dp));Text("Добавить название",color = Muted,fontSize = 13.sp)
                        }
                        PlainField(r.description,{ value -> model.changeDraft { it.copy(description = value) } },"Описание вызова","Что было на вызове?\nМожно записать всё одним текстом…",
                            Modifier.fillMaxWidth().focusRequester(noteFocus).testTag("descriptionField"),!saving,minLines = if (compact) 3 else 7)
                    }
                }
            }
            itemsIndexed(r.blocks,key = { _,b -> b.id }) { index,b -> BlockEditor(b,index,r.blocks.size,compact,!saving,
                { key,value -> model.updateBlock(b.id,key,value) },{ model.removeBlock(b.id) },{ model.moveBlock(b.id,it) },{ model.updateBlockValues(b.id,it) }) }
            item(key = "addBlock") {
                OutlinedButton({ focus.clearFocus();blockPicker = true },Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp).testTag("addBlock"),
                    enabled = !saving,shape = RoundedCornerShape(18.dp),border = androidx.compose.foundation.BorderStroke(1.dp,Color(0xFFE6E1DF))) {
                    Icon(Icons.Outlined.Add,null,Modifier.size(19.dp));Spacer(Modifier.width(7.dp));Text("Добавить блок")
                }
                if (!compact) Text("Только нужные подробности. Все блоки необязательны.",Modifier.fillMaxWidth().padding(horizontal = 10.dp,vertical = 12.dp),
                    textAlign = TextAlign.Center,color = Muted,fontSize = 11.sp,lineHeight = 17.sp)
            }
        }
        if (!compact) Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp,vertical = 10.dp)) {
            PrimaryButton(if (saving) "Сохраняем…" else "Сохранить вызов",save,Modifier.testTag("saveCall"),icon = Icons.Outlined.Check,enabled = !saving)
        }
    }
    if (datePicker) DateDialog(r.date,onDismiss = { datePicker = false }) { value -> model.changeDraft { it.copy(date = value) };datePicker = false }
    if (timePicker) TimeDialog(r.time,{ timePicker = false }) { value -> model.changeDraft { it.copy(time = value) };timePicker = false }
    if (blockPicker) BlockPicker({ blockPicker = false }) { adding = true;model.addBlock(it);blockPicker = false }
    if (discard) AlertDialog({ discard = false },title = { Text("Удалить черновик?") },
        text = { Text(if (isNew) "Текст незавершённой записи будет удалён." else "Несохранённые изменения будут удалены. Сохранённый вызов останется в журнале.") },
        confirmButton = { TextButton({ discard = false;model.discardDraft() }) { Text("Удалить черновик") } },
        dismissButton = { TextButton({ discard = false }) { Text("Продолжить запись") } })
}
@Composable fun MetaButton(label: String,value: String,icon: ImageVector,onClick: () -> Unit,compact: Boolean,enabled: Boolean,modifier: Modifier) {
    Surface(onClick,modifier,enabled = enabled,shape = RoundedCornerShape(19.dp),color = Color.White) {
        Column(Modifier.padding(horizontal = if (compact) 12.dp else 17.dp,vertical = 13.dp),verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon,null,Modifier.size(14.dp),tint = Muted);Spacer(Modifier.width(6.dp));Text(label,color = Muted,fontSize = 11.sp) }
            Text(value,fontSize = if (compact) 13.sp else 15.sp,fontWeight = FontWeight.Medium,color = if (value == "Без времени") Muted else Ink)
        }
    }
}
@Composable fun PlainField(value: String,onChange: (String) -> Unit,label: String,hint: String,modifier: Modifier = Modifier,enabled: Boolean = true,
    singleLine: Boolean = false,minLines: Int = 1,keyboardType: KeyboardType = KeyboardType.Text,textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    TextField(value,onChange,modifier,enabled = enabled,label = { Text(label,fontSize = 12.sp) },
        placeholder = { Text(hint,color = Color(0xFFB5AFB2),fontSize = 15.sp,lineHeight = 24.sp) },singleLine = singleLine,minLines = minLines,
        shape = RoundedCornerShape(16.dp),textStyle = textStyle,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType,capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,unfocusedContainerColor = Color.Transparent,disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,unfocusedIndicatorColor = Color.Transparent,disabledIndicatorColor = Color.Transparent,
            unfocusedLabelColor = Muted,focusedLabelColor = Accent,disabledTextColor = Ink,disabledLabelColor = Muted))
}
@Composable fun BlockEditor(b: NoteBlock,index: Int,count: Int,compact: Boolean,enabled: Boolean,update: (String,String) -> Unit,remove: () -> Unit,move: (Int) -> Unit,updateValues: (Map<String,String>) -> Unit) {
    var menu by remember { mutableStateOf(false) };var deleting by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(24.dp),color = Color.White) {
        Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).background(Tint,RoundedCornerShape(10.dp)),contentAlignment = Alignment.Center) { Icon(blockIcon(b.kind),null,Modifier.size(17.dp),tint = Accent) }
                Spacer(Modifier.width(9.dp));Text(b.kind.label,Modifier.weight(1f),fontWeight = FontWeight.SemiBold,fontSize = if (compact) 13.sp else 15.sp,lineHeight = 20.sp)
                Box {
                    IconButton({ menu = true },enabled = enabled) { Icon(Icons.Outlined.MoreHoriz,"Действия: ${b.kind.label}",tint = Muted) }
                    DropdownMenu(menu,{ menu = false }) {
                        DropdownMenuItem(text = { Text("Выше") },onClick = { menu = false;move(-1) },enabled = index > 0,leadingIcon = { Icon(Icons.Outlined.ArrowUpward,null) })
                        DropdownMenuItem(text = { Text("Ниже") },onClick = { menu = false;move(1) },enabled = index < count-1,leadingIcon = { Icon(Icons.Outlined.ArrowDownward,null) })
                        DropdownMenuItem(text = { Text("Удалить блок",color = Accent) },onClick = { menu = false;if (b.hasContent) deleting = true else remove() },leadingIcon = { Icon(Icons.Outlined.DeleteOutline,null,tint = Accent) })
                    }
                }
            }
            when(b.kind) {
                BlockKind.MEDICINES, BlockKind.DIAGNOSIS -> ReferenceField(b,compact,enabled,updateValues)
                BlockKind.PERSON -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp,vertical = 8.dp),horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf("М" to "Мужской","Ж" to "Женский").forEach { (value,label) ->
                            val selected = b.value("sex") == value
                            FilterChip(selected,{ update("sex",if (selected) "" else value) },label = { Text(if (compact) value else label,fontSize = 14.sp) },enabled = enabled,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),shape = RoundedCornerShape(14.dp),
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Tint,selectedLabelColor = Accent))
                        }
                    }
                    PlainField(b.value("age"),{ if (it.length <= 3 && it.all(Char::isDigit)) update("age",it) },"Возраст, лет","Например, 68",Modifier.fillMaxWidth(),enabled,singleLine = true,keyboardType = KeyboardType.Number)
                }
                BlockKind.VITALS -> VitalFields.chunked(if (compact) 1 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (key,label) -> PlainField(b.value(key),{ value ->
                            val allowed = if (key == "bp") "0123456789/ " else "0123456789.,"
                            if (value.length <= 16 && value.all { it in allowed }) update(key,value)
                        },when(key) { "temperature" -> "$label, °C";"glucose" -> "$label, ммоль/л";"spo2" -> "$label, %";else -> label },
                            when(key) { "bp" -> "170/100";"pulse" -> "92";"spo2" -> "96";else -> "" },Modifier.weight(1f).testTag("vital_$key"),enabled,singleLine = true,
                            keyboardType = if (key == "bp") KeyboardType.Text else KeyboardType.Decimal) }
                    }
                }
                else -> PlainField(b.value("text"),{ update("text",it) },if (b.kind == BlockKind.OUTCOME) "Чем закончился вызов" else "Заметка",b.kind.hint,Modifier.fillMaxWidth(),enabled,minLines = if (compact) 2 else 3)
            }
        }
    }
    if (deleting) AlertDialog({ deleting = false },title = { Text("Удалить блок?") },text = { Text("«${b.kind.label}» и его содержимое будут удалены из этой записи.") },
        confirmButton = { TextButton({ deleting = false;remove() }) { Text("Удалить") } },dismissButton = { TextButton({ deleting = false }) { Text("Оставить") } })
}
