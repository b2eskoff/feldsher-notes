package ru.ainur.feldshernotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.ainur.feldshernotes.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Composable fun NotesDialog(title: String,onDismiss: () -> Unit,body: @Composable ColumnScope.() -> Unit,actions: @Composable ColumnScope.() -> Unit = {}) {
    Dialog(onDismiss,properties = DialogProperties(usePlatformDefaultWidth = false,decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(680.dp)),shape = RoundedCornerShape(26.dp),color = Color.White) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp,end = 6.dp),verticalAlignment = Alignment.CenterVertically) {
                        Text(title,Modifier.weight(1f),fontSize = 18.sp,fontWeight = FontWeight.SemiBold)
                        IconButton(onDismiss) { Icon(Icons.Outlined.Close,"Закрыть") }
                    }
                    Column(Modifier.weight(1f,fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),content = body)
                    Column(Modifier.fillMaxWidth().padding(12.dp),content = actions)
                }
            }
        }
    }
}
@Composable fun DateDialog(initialDate: String,recordedDays: Set<String> = emptySet(),onDismiss: () -> Unit,onSelect: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initialDate) };var monthString by rememberSaveable { mutableStateOf(YearMonth.from(LocalDate.parse(initialDate)).toString()) }
    var manual by rememberSaveable { mutableStateOf(false) };var input by rememberSaveable { mutableStateOf(LocalDate.parse(initialDate).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
    var error by rememberSaveable { mutableStateOf<String?>(null) };val month = YearMonth.parse(monthString)
    NotesDialog("Выбрать дату",onDismiss,body = {
        if (manual) {
            OutlinedTextField(input,{ input = it;error = null },Modifier.fillMaxWidth().testTag("manualDate"),label = { Text("Дата: ДД.ММ.ГГГГ") },
                singleLine = true,isError = error != null,shape = RoundedCornerShape(16.dp),keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),supportingText = { Text(error ?: "Например, 04.08.2026") })
            TextButton({ manual = false;error = null }) { Text("Открыть календарь") }
        } else {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                IconButton({ monthString = month.minusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronLeft,"Предыдущий месяц") }
                TextButton({ manual = true },Modifier.weight(1f),contentPadding = PaddingValues(0.dp)) {
                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Russian)).replaceFirstChar { it.titlecase(Russian) },fontSize = 15.sp,textAlign = TextAlign.Center,color = Ink)
                }
                IconButton({ monthString = month.plusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronRight,"Следующий месяц") }
            }
            Row(Modifier.fillMaxWidth()) { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { Text(it,Modifier.weight(1f).padding(vertical = 8.dp),color = Muted,fontSize = 11.sp,textAlign = TextAlign.Center) } }
            val offset = month.atDay(1).dayOfWeek.value-1;val cells = (offset+month.lengthOfMonth()+6)/7*7
            (0 until cells step 7).forEach { row -> Row(Modifier.fillMaxWidth()) {
                (row until row+7).forEach { slot ->
                    val day = slot-offset+1
                    if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp))
                    else {
                        val date = month.atDay(day).toString();val active = date == selected
                        Column(Modifier.weight(1f).height(48.dp).background(if (active) Accent else Color.Transparent,RoundedCornerShape(13.dp)).semantics { contentDescription = longDate(date) + if (date in recordedDays) ", есть вызовы" else "" }.clickable { selected = date },
                            horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.Center) {
                            Text(day.toString(),fontSize = 14.sp,fontWeight = if (active || date == LocalDate.now().toString()) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) Color.White else if (date == LocalDate.now().toString()) Accent else Ink)
                            if (date in recordedDays) Box(Modifier.padding(top = 2.dp).size(3.dp).background(if (active) Color.White else Accent,CircleShape))
                        }
                    }
                }
            } }
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ selected = LocalDate.now().toString();monthString = YearMonth.now().toString() }) { Text("Сегодня",fontSize = 12.sp) }
                TextButton({ manual = true }) { Text("Ввести дату",fontSize = 12.sp) }
            }
        }
    },actions = { PrimaryButton("Выбрать",{
        if (manual) {
            val normalized = input.trim().let { if (it.matches(Regex("\\d{8}"))) "${it.take(2)}.${it.substring(2,4)}.${it.takeLast(4)}" else it }
            val date = runCatching { LocalDate.parse(normalized,DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
            if (date == null || date.year !in 1..9999) error = "Проверьте дату" else onSelect(date.toString())
        } else onSelect(selected)
    },compact = true) })
}
@Composable fun TimeDialog(initialTime: String?,onDismiss: () -> Unit,onSelect: (String?) -> Unit) {
    var hour by rememberSaveable { mutableStateOf(initialTime?.substringBefore(':') ?: "") };var minute by rememberSaveable { mutableStateOf(initialTime?.substringAfter(':') ?: "") }
    var error by rememberSaveable { mutableStateOf(false) }
    NotesDialog("Время вызова",onDismiss,body = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(hour,{ if (it.length <= 2 && it.all(Char::isDigit)) { hour = it;error = false } },Modifier.weight(1f).testTag("timeHour"),
                label = { Text("Часы") },placeholder = { Text("ЧЧ") },singleLine = true,isError = error,shape = RoundedCornerShape(16.dp),keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text(":",fontSize = 22.sp,color = Muted)
            OutlinedTextField(minute,{ if (it.length <= 2 && it.all(Char::isDigit)) { minute = it;error = false } },Modifier.weight(1f).testTag("timeMinute"),
                label = { Text("Минуты") },placeholder = { Text("ММ") },singleLine = true,isError = error,shape = RoundedCornerShape(16.dp),keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (error) Text("Часы: 0–23, минуты: 0–59",Modifier.padding(8.dp),color = Accent,fontSize = 12.sp)
        TextButton({ val now = LocalTime.now();hour = "%02d".format(now.hour);minute = "%02d".format(now.minute);error = false }) { Text("Текущее время") }
        Text("Если время неизвестно, оставьте запись без него.",Modifier.padding(4.dp),fontSize = 12.sp,color = Muted)
    },actions = {
        PrimaryButton("Готово",{
            val h = hour.toIntOrNull();val m = minute.toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) onSelect(LocalTime.of(h,m).format(DateTimeFormatter.ofPattern("HH:mm"))) else error = true
        },compact = true)
        TextButton({ onSelect(null) },Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("noTime")) { Text("Без времени") }
    })
}
@Composable fun BlockPicker(onDismiss: () -> Unit,onSelect: (BlockKind) -> Unit) {
    NotesDialog("Добавить блок",onDismiss,body = { BlockKind.entries.forEach { kind ->
        Surface({ onSelect(kind) },Modifier.fillMaxWidth(),shape = RoundedCornerShape(16.dp),color = Color.White) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp,vertical = 11.dp),verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Tint,RoundedCornerShape(12.dp)),contentAlignment = Alignment.Center) { Icon(blockIcon(kind),null,Modifier.size(20.dp),tint = Accent) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(kind.label,fontSize = 14.sp,lineHeight = 20.sp,fontWeight = FontWeight.Medium);Text(kind.hint,fontSize = 11.sp,color = Muted,lineHeight = 16.sp) }
                Icon(Icons.Outlined.Add,null,Modifier.size(17.dp),tint = Muted)
            }
        }
    } })
}
