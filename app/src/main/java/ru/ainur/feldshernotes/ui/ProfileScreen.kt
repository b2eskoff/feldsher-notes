package ru.ainur.feldshernotes.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import ru.ainur.feldshernotes.NotesApplication
import ru.ainur.feldshernotes.data.*
import ru.ainur.feldshernotes.profile.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

private val dateView = DateTimeFormatter.ofPattern("dd.MM.uuuu")
private val timeView = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm")
private val strictDate = dateView.withResolverStyle(ResolverStyle.STRICT)
private val strictTime = timeView.withResolverStyle(ResolverStyle.STRICT)

// Database values stay ISO-8601. Only text presented to the user uses DD.MM.YYYY.
internal fun profileDate(value: String): String = runCatching { LocalDate.parse(value).format(dateView) }.getOrDefault(value)
internal fun profileDateTime(value: String): String = runCatching { LocalDateTime.parse(value).format(timeView) }.getOrDefault(value)
internal fun parseProfileDate(value: String): String = LocalDate.parse(value.trim(), strictDate).toString()
internal fun parseProfileTime(value: String): String = LocalDateTime.parse(value.trim(), strictTime).toString()

@Composable private fun ProfileCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(23.dp)
    if (onClick == null) Surface(Modifier.fillMaxWidth(), shape = shape, color = Color.White) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    } else Surface(onClick, Modifier.fillMaxWidth(), shape = shape, color = Color.White) {
        Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun ProfileJump(title: String, icon: ImageVector, subtitle: String = "", onClick: () -> Unit) {
    Surface(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), color = Color.White) {
        Row(Modifier.padding(horizontal = 17.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(21.dp), tint = Accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp), tint = Muted)
        }
    }
}

private fun medalPicture(category: String): ImageVector = when (category) {
    "calls" -> Icons.Outlined.MedicalServices
    "shifts" -> Icons.Outlined.CalendarMonth
    "nights" -> Icons.Outlined.NightlightRound
    "series" -> Icons.Outlined.LocalFireDepartment
    else -> Icons.Outlined.FavoriteBorder
}
private fun medalName(category: String) = when(category) {
    "calls" -> "На линии"
    "shifts" -> "В ритме смен"
    "nights" -> "Ночная жизнь"
    "series" -> "Верность записям"
    else -> "Вместе с Записками"
}
@Composable private fun MedalPicture(category: String, obtained: Boolean, size: Int = 42) {
    val color = if (obtained) Color(0xFFB8893F) else Color(0xFFB8B4B1)
    Box(Modifier.size(size.dp).background(color.copy(alpha = if (obtained) .12f else .08f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(medalPicture(category), null, Modifier.size((size * .52f).dp), tint = color)
    }
}
@Composable private fun ProfileAvatar(encoded: String, size: Int = 62) {
    val bitmap = remember(encoded) { runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
    }.getOrNull() }
    Box(Modifier.size(size.dp).clip(CircleShape).background(Tint), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "Аватар", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.PersonOutline, "Аватар не выбран", Modifier.size((size * .54f).dp), tint = Accent)
    }
}
@Composable private fun KeyNumber(value: String, caption: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = Accent, maxLines = 1)
            Text(caption, fontSize = 12.sp, color = Muted, maxLines = 2, lineHeight = 16.sp)
        }
    }
}
@Composable private fun SectionHeading(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick) { Text("Все", color = Accent) }
    }
}

@Composable fun ProfileScreen(compact: Boolean, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NotesApplication
    val store = remember { app.profile }
    val d by store.data.collectAsStateWithLifecycle()
    val storeError by store.error.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { message = e.message ?: "Не удалось сохранить" }
            finally { busy = false }
        }
    }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(60_000) } }
    fun goBack() { page = if (page == "edit") "card" else "home" }
    BackHandler(page != "home") { goBack() }
    val stats = ProfileMath.statistics(d.calls, d.shifts, d.links, now)
    val level = ProfileMath.level(stats.xp)
    val series = ProfileMath.series(d.calls, d.shifts, d.links, now)
    var usageSince by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(d.ready) {
        if (d.ready) usageSince = withContext(Dispatchers.IO) {
            app.database.notes().preference("profileUsageSince")?.let(LocalDate::parse) ?: LocalDate.now()
        }
    }
    val progress = ProfileMath.progress(d.calls, d.shifts, d.links, now, usageSince)
    val margin = if (compact) 12.dp else 22.dp
    Column(Modifier.fillMaxSize()) {
        PageHeader(when (page) {
            "home" -> "Профиль"; "card" -> "Личная карточка"; "edit" -> "Редактирование"
            "shifts" -> "Смены и серия"; "links" -> "Вызовы и смены"
            "medals" -> "Достижения"; "stats" -> "Моя статистика"
            else -> "Документы"
        }, compact, { if (page == "home") onBack() else goBack() })
        if (!d.ready) { CircularProgressIndicator(Modifier.padding(24.dp)); return@Column }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        if (message != null || storeError != null) Text(message ?: storeError.orEmpty(), Modifier.padding(12.dp), color = Accent)
        when (page) {
            "card" -> ProfileIdentity(d.profile, margin) { page = "edit" }
            "edit" -> ProfileEditor(d.profile, busy) { p ->
                action { store.saveProfile(p); withContext(Dispatchers.Main) { page = "card" } }
            }
            "shifts" -> ShiftList(d, now, busy, { s -> action { store.saveShift(s) } },
                { id -> action { store.deleteShift(id) } }, { page = "links" }, margin)
            "links" -> LinkList(d, busy, { call, shift -> action { store.link(call, shift) } }, margin)
            "medals" -> MedalCollection(d, progress, margin)
            "stats" -> ReportView(d, now, margin)
            "documents" -> DocumentsScreen()
            else -> LazyColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                item {
                    ProfileCard(onClick = { page = "card" }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            ProfileAvatar(d.profile.avatar)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(listOf(d.profile.name, d.profile.surname).filter(String::isNotBlank).joinToString(" ").ifBlank { "Мой профиль" },
                                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(d.profile.role.ifBlank { "Фельдшер" }, color = Muted, style = MaterialTheme.typography.bodyMedium)
                                if (d.profile.started.isNotBlank()) Text("В профессии с ${profileDate(d.profile.started)}", color = Muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp), tint = Muted)
                        }
                        HorizontalDivider(color = Paper)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("УРОВЕНЬ ${level.level}", Modifier.weight(1f), color = Accent,
                                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = .9.sp)
                            Text("${level.total} XP", color = Muted, fontSize = 12.sp)
                        }
                        LinearProgressIndicator(progress = { level.fraction }, Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            color = Accent, trackColor = Field)
                        if (level.level < 100) Text("До следующего уровня: ${level.next - level.intoLevel} XP", color = Muted, fontSize = 12.sp)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyNumber(stats.calls.toString(), "Вызовов", Modifier.weight(1f))
                        KeyNumber(stats.shifts.toString(), "Смен", Modifier.weight(1f))
                        KeyNumber(series.first.toString(), "Серия смен", Modifier.weight(1f))
                    }
                }
                item {
                    ProfileCard(onClick = { page = "shifts" }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp), tint = Accent)
                            Spacer(Modifier.width(10.dp))
                            Text("Смены и серия", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp), tint = Muted)
                        }
                        Text("Текущая серия — ${series.first} · личный рекорд — ${series.second}", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item { SectionHeading("Достижения") { page = "medals" } }
                item {
                    val obtained = ProfileMath.medals.filter { medal -> d.unlocks.any { it.id == medal.id } }
                        .groupBy { it.category }.mapNotNull { (_, items) -> items.maxByOrNull { it.threshold } }.take(3)
                    if (obtained.isEmpty()) ProfileCard(onClick = { page = "medals" }) {
                        Text("Первые достижения впереди", style = MaterialTheme.typography.titleMedium)
                        Text("Здесь появятся награды за сохранённые вызовы и смены.", color = Muted)
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        obtained.forEach { medal ->
                            Surface({ page = "medals" }, Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = Color.White) {
                                Column(Modifier.padding(vertical = 14.dp, horizontal = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MedalPicture(medal.category, true)
                                    Text(medalName(medal.category), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, lineHeight = 14.sp)
                                    Text("${medal.threshold} ${medal.unit}", fontSize = 10.sp, color = Muted, maxLines = 2, lineHeight = 13.sp)
                                }
                            }
                        }
                    }
                }
                item { ProfileJump("Моя статистика", Icons.Outlined.QueryStats, "Всё время · Месяц · Год") { page = "stats" } }
                item { ProfileJump("Мои документы", Icons.Outlined.FolderOpen, "Защищённое хранилище") { page = "documents" } }
            }
        }
    }
}

@Composable private fun ProfileIdentity(profile: UserProfile, margin: androidx.compose.ui.unit.Dp, edit: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = margin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            ProfileCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileAvatar(profile.avatar, 92)
                    Text(listOf(profile.surname, profile.name, profile.patronymic).filter(String::isNotBlank)
                        .joinToString(" ").ifBlank { "Моя личная карточка" }, style = MaterialTheme.typography.titleLarge)
                    Text(profile.role.ifBlank { "Фельдшер" }, color = Accent, fontWeight = FontWeight.SemiBold)
                    if (profile.workplace.isNotBlank()) Text(profile.workplace, color = Muted)
                }
                HorizontalDivider(color = Paper)
                if (profile.education.isNotBlank()) IdentityDetail("Образование", profile.education)
                if (profile.started.isNotBlank()) IdentityDetail("Начало работы", profileDate(profile.started))
                if (profile.education.isBlank() && profile.started.isBlank() && profile.workplace.isBlank())
                    Text("Заполни карточку, чтобы здесь появилась информация о тебе.", color = Muted)
            }
        }
        item { PrimaryButton("Редактировать карточку", edit, icon = Icons.Outlined.Edit) }
    }
}
@Composable private fun IdentityDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(Russian), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = .8.sp)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable private fun MedalCollection(d: ProfileData, progress: Map<String, Int>, margin: androidx.compose.ui.unit.Dp) {
    val categories = listOf("calls", "shifts", "nights", "series", "months")
    var selected by rememberSaveable { mutableStateOf("calls") }
    var detail by remember { mutableStateOf<Medal?>(null) }
    val medals = ProfileMath.medals.filter { it.category == selected }
    val current = progress[selected] ?: 0
    val obtainedIds = remember(d.unlocks) { d.unlocks.map { it.id }.toSet() }
    LazyColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Text("Награды за ведение личных записей, а не за качество медицинской помощи.", color = Muted, style = MaterialTheme.typography.bodyMedium) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(categories) { category ->
                    FilterChip(selected == category, { selected = category }, label = { Text(medalName(category), maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Tint, selectedLabelColor = Accent))
                }
            }
        }
        item { Text("${medalName(selected)} · $current", style = MaterialTheme.typography.titleLarge) }
        items(medals, key = { it.id }) { medal ->
            val obtained = medal.id in obtainedIds
            val value = (current.toFloat() / medal.threshold).coerceIn(0f, 1f)
            ProfileCard(onClick = { detail = medal }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MedalPicture(medal.category, obtained, 50)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${medal.title} · ${medal.threshold} ${medal.unit}", style = MaterialTheme.typography.titleMedium)
                        Text(if (obtained) "Получена" else "${current.coerceAtMost(medal.threshold)} / ${medal.threshold} · осталось ${(medal.threshold - current).coerceAtLeast(0)}",
                            color = if (obtained) SoftGreen else Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                }
                LinearProgressIndicator(progress = { value }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = if (obtained) Color(0xFFB8893F) else Accent, trackColor = Field)
            }
        }
    }
    detail?.let { medal ->
        val unlock = d.unlocks.firstOrNull { it.id == medal.id }
        AlertDialog(onDismissRequest = { detail = null }, icon = { MedalPicture(medal.category, unlock != null, 60) },
            title = { Text(medal.title) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Условие: ${medal.threshold} ${medal.unit}")
                    Text(if (unlock != null) "Открыта ${profileDate(Instant.ofEpochMilli(unlock.unlockedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())}"
                        else "Прогресс: $current / ${medal.threshold}", color = Muted)
                }
            }, confirmButton = { TextButton({ detail = null }) { Text("Закрыть") } })
    }
}

@Composable private fun ProfileEditor(initial: UserProfile, busy: Boolean, onSave: (UserProfile) -> Unit) {
    var p by remember(initial) { mutableStateOf(initial) }
    var dateInput by remember(initial.started) { mutableStateOf(if (initial.started.isBlank()) "" else profileDate(initial.started)) }
    var error by remember { mutableStateOf<String?>(null) }
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch {
        runCatching { withContext(Dispatchers.IO) {
            val raw=context.contentResolver.openInputStream(uri)!!.use{it.readBytesLimited(12*1024*1024)}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0);var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2
            val bitmap=BitmapFactory.decodeByteArray(raw,0,raw.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: error("Не удалось прочитать изображение")
            java.io.ByteArrayOutputStream().use{out->bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out);bitmap.recycle();Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}
        } }.onSuccess{p=p.copy(avatar=it)}.onFailure{error="Не удалось загрузить аватар"}
    }}

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                ProfileAvatar(p.avatar, 68)
                Column {
                    TextButton({ picker.launch(arrayOf("image/*")) }) { Text("Выбрать фотографию") }
                    if (p.avatar.isNotBlank()) TextButton({ p = p.copy(avatar = "") }) { Text("Удалить фотографию") }
                }
            }
        }
        ProfileCard {
            Field("Фамилия", p.surname) { p = p.copy(surname = it) }
            Field("Имя", p.name) { p = p.copy(name = it) }
            Field("Отчество", p.patronymic) { p = p.copy(patronymic = it) }
            Field("Должность", p.role) { p = p.copy(role = it) }
            Field("Место работы", p.workplace) { p = p.copy(workplace = it) }
            Field("Образование", p.education) { p = p.copy(education = it) }
            Field("Начало работы · ДД.ММ.ГГГГ", dateInput) { dateInput = it; error = null }
            Text("Например, 03.08.2026", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (error != null) Text(error.orEmpty(), color = Accent)
        PrimaryButton("Сохранить", {
            runCatching {
                val saved = if (dateInput.isBlank()) "" else parseProfileDate(dateInput)
                require(saved.isBlank() || LocalDate.parse(saved) <= LocalDate.now()) { "Дата начала работы не может быть в будущем" }
                p.copy(started = saved)
            }.onSuccess(onSave).onFailure { error = "Проверь дату: ДД.ММ.ГГГГ" }
        }, enabled = !busy)
    }
}
@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp), singleLine = label.startsWith("Начало работы"))
}
internal fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray { val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=read(b);if(n<0)break;require(out.size()+n<=limit){"Файл слишком большой"};out.write(b,0,n)};return out.toByteArray() }

@Composable private fun ShiftList(d: ProfileData, now: LocalDateTime, busy: Boolean,
    save: (WorkShift) -> Unit, delete: (String) -> Unit, links: () -> Unit, margin: androidx.compose.ui.unit.Dp) {
    var edit by remember { mutableStateOf<WorkShift?>(null) }
    var remove by remember { mutableStateOf<WorkShift?>(null) }
    var chooseDate by remember { mutableStateOf<LocalDate?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val assignments = ProfileMath.assignments(d.calls, d.shifts, d.links)
    val series = ProfileMath.series(d.calls, d.shifts, d.links, now)
    val shiftsByDate = remember(d.shifts) { d.shifts.sortedByDescending { it.start }.associateBy { it.startTime.toLocalDate() } }
    val countsByShift = remember(assignments) { assignments.values.groupingBy { it }.eachCount() }
    val today = now.toLocalDate()

    LazyColumn(contentPadding = PaddingValues(horizontal = margin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ProfileCard {
                Text("Текущая серия", fontSize = 12.sp, color = Muted)
                Text("${series.first} смен", style = MaterialTheme.typography.headlineMedium, color = Accent)
                Text("Личный рекорд — ${series.second}", style = MaterialTheme.typography.bodyMedium)
                Text("Теперь смены можно отмечать прямо в календаре: нажми на дату и выбери дневную или ночную смену. Подтверждённые смены учитываются автоматически, когда заканчиваются.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ProfileCard {
                ShiftCalendarHeader(month = month, onPrevious = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) })
                ShiftCalendarMonth(month = month, today = today, shiftsByDate = shiftsByDate, countsByShift = countsByShift,
                    onDateClick = { chooseDate = it })
            }
        }
        item { ProfileJump("Распределить вызовы", Icons.Outlined.Link, "Ручная привязка к сменам", links) }
        item {
            Text("Список смен", style = MaterialTheme.typography.titleMedium)
        }
        items(d.shifts.sortedByDescending { it.start }, key = { it.id }) { shift ->
            val count = countsByShift[shift.id] ?: 0
            ProfileCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (shift.night) Icons.Outlined.NightlightRound else Icons.Outlined.WbSunny, null,
                        Modifier.size(23.dp), tint = Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(if (shift.night) "Ночная смена" else "Дневная смена", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    SmallTag("$count выз.")
                }
                Text(profileDateTime(shift.start) + " — " + profileDateTime(shift.end),
                    style = MaterialTheme.typography.bodyMedium)
                Text(if (shift.endTime <= now && (shift.completed || shift.confirmed)) "Будет учтена в статистике" else if (shift.endTime > now) "Будущая смена" else "Не завершена",
                    color = Muted, fontSize = 12.sp)
                Text(if (shift.confirmed) "Отмечена вручную" else if (count > 0) "Есть записи" else "Нет подтверждения",
                    color = if (shift.confirmed || count > 0) SoftGreen else Muted, fontSize = 12.sp)
                HorizontalDivider(color = Paper)
                Row {
                    TextButton({ edit = shift }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Подробно") }
                    TextButton({ remove = shift }, enabled = !busy) { Text("Удалить", color = Muted) }
                }
            }
        }
    }
    chooseDate?.let { date ->
        ShiftPresetDialog(date = date, current = shiftsByDate[date], busy = busy, now = now,
            onDismiss = { chooseDate = null },
            onChoose = { night ->
                save(presetShift(date, night, shiftsByDate[date], now))
                chooseDate = null
            },
            onDelete = {
                shiftsByDate[date]?.let { delete(it.id) }
                chooseDate = null
            },
            onDetails = {
                edit = shiftsByDate[date] ?: presetShift(date, false, null, now)
                chooseDate = null
            })
    }
    edit?.let { shift -> ShiftDialog(shift, { edit = null }) { save(it); edit = null } }
    remove?.let { shift -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Удалить смену?") },
        text = { Text("Вызовы сохранятся. XP и серия будут пересчитаны.") },
        confirmButton = { TextButton({ delete(shift.id); remove = null }) { Text("Удалить") } },
        dismissButton = { TextButton({ remove = null }) { Text("Отмена") } }) }
}

private fun presetShift(date: LocalDate, night: Boolean, existing: WorkShift?, now: LocalDateTime): WorkShift {
    val start = if (night) date.atTime(20, 0) else date.atTime(8, 0)
    val end = start.plusHours(12)
    return WorkShift(
        id = existing?.id ?: UUID.randomUUID().toString(),
        start = start.toString(),
        end = end.toString(),
        night = night,
        completed = end <= now,
        confirmed = true
    )
}

@Composable private fun ShiftCalendarHeader(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Russian)).replaceFirstChar { it.titlecase(Russian) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(onPrevious, Modifier.size(36.dp)) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий месяц", tint = Muted) }
        IconButton(onNext, Modifier.size(36.dp)) { Icon(Icons.Outlined.ChevronRight, "Следующий месяц", tint = Muted) }
    }
}

@Composable private fun ShiftCalendarMonth(
    month: YearMonth,
    today: LocalDate,
    shiftsByDate: Map<LocalDate, WorkShift>,
    countsByShift: Map<String, Int>,
    onDateClick: (LocalDate) -> Unit
) {
    val labels = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { label ->
            Text(label, Modifier.weight(1f), fontSize = 12.sp, color = Muted)
        }
    }
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val days = buildList<LocalDate?> {
        repeat(offset) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
        while (size % 7 != 0) add(null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { date ->
                    if (date == null) Spacer(Modifier.weight(1f).aspectRatio(.84f))
                    else {
                        val shift = shiftsByDate[date]
                        val count = shift?.let { countsByShift[it.id] ?: 0 } ?: 0
                        val selectedColor = when {
                            shift == null -> Color.White
                            shift.night -> Color(0xFFF4EEF8)
                            else -> Tint
                        }
                        Surface(
                            onClick = { onDateClick(date) },
                            modifier = Modifier.weight(1f).aspectRatio(.84f),
                            shape = RoundedCornerShape(9.dp),
                            color = selectedColor,
                            border = if (date == today) BorderStroke(1.dp, Accent.copy(alpha = .35f)) else null
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(date.dayOfMonth.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (date == today) Accent else Ink)
                                if (shift != null) {
                                    Box(Modifier.size(width = 19.dp, height = 5.dp).background(
                                        if (shift.night) Color(0xFF9E81BB) else Accent, CircleShape))
                                    if (count > 0) Text(count.toString(), fontSize = 10.sp, color = SoftGreen, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ShiftPresetDialog(
    date: LocalDate,
    current: WorkShift?,
    busy: Boolean,
    now: LocalDateTime,
    onDismiss: () -> Unit,
    onChoose: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profileDate(date.toString())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        current == null -> "На эту дату смена ещё не назначена."
                        current.night -> "Сейчас отмечена ночная смена."
                        else -> "Сейчас отмечена дневная смена."
                    },
                    color = Muted
                )
                Text(
                    if (date.atStartOfDay().isAfter(now)) "Для будущих дат смена сохранится и автоматически начнёт учитываться после окончания."
                    else "Подтверждённая смена будет учтена в статистике и серии.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                Button(onClick = { onChoose(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Дневная смена") }
                OutlinedButton(onClick = { onChoose(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Ночная смена") }
                OutlinedButton(onClick = onDetails, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Точная настройка") }
                if (current != null) TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Убрать смену", color = Accent) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Закрыть") } }
    )
}

@Composable private fun ShiftDialog(initial: WorkShift, dismiss: () -> Unit, save: (WorkShift) -> Unit) {
    var start by remember(initial.id) { mutableStateOf(profileDateTime(initial.start)) }
    var end by remember(initial.id) { mutableStateOf(profileDateTime(initial.end)) }
    var night by remember(initial.id) { mutableStateOf(initial.night) }
    var completed by remember(initial.id) { mutableStateOf(initial.completed) }
    var confirmed by remember(initial.id) { mutableStateOf(initial.confirmed) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Смена") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Формат: ДД.ММ.ГГГГ ЧЧ:ММ", color = Muted, style = MaterialTheme.typography.bodySmall)
            Field("Начало", start) { start = it; error = null }
            Field("Окончание", end) { end = it; error = null }
            Toggle("Ночная", night) { night = it }
            Toggle("Смена завершена", completed) { completed = it }
            Toggle("Подтверждена вручную", confirmed) { confirmed = it }
            if (error != null) Text(error.orEmpty(), color = Accent)
        }
    }, confirmButton = { TextButton({
        runCatching {
            initial.copy(start = parseProfileTime(start), end = parseProfileTime(end), night = night,
                completed = completed, confirmed = confirmed).also {
                it.validate()
                require(!it.completed || it.endTime <= LocalDateTime.now()) { "Смена ещё не закончилась" }
            }
        }.onSuccess(save).onFailure { error = it.message ?: "Проверь даты: ДД.ММ.ГГГГ ЧЧ:ММ" }
    }) { Text("Сохранить") } }, dismissButton = { TextButton(dismiss) { Text("Отмена") } })
}
@Composable internal fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(value, change); Text(label, Modifier.weight(1f)) }
}

@Composable private fun LinkList(d: ProfileData, busy: Boolean, save: (String, String) -> Unit,
    margin: androidx.compose.ui.unit.Dp) {
    var call by remember { mutableStateOf<CallRecord?>(null) }
    val assigned = ProfileMath.assignments(d.calls, d.shifts, d.links)
    LazyColumn(contentPadding = PaddingValues(horizontal = margin, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Вызовы автоматически связываются со сменой, если время позволяет определить её однозначно. Остальные можно распределить вручную.", color = Muted) }
        items(sortCalls(d.calls), key = { it.id }) { c ->
            ProfileJump("${profileDate(c.date)} ${c.time.orEmpty()}", Icons.Outlined.EditNote,
                assigned[c.id]?.let { id -> d.shifts.find { it.id == id }?.let { "Смена ${profileDateTime(it.start)}" } } ?: "Без смены") {
                if (!busy) call = c
            }
        }
    }
    if (call != null) AlertDialog(onDismissRequest = { call = null }, title = { Text("Выбрать смену") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton({ save(call!!.id, ""); call = null }) { Text("Оставить без смены") }
            d.shifts.sortedByDescending { it.start }.forEach { s ->
                TextButton({ save(call!!.id, s.id); call = null }) {
                    Text("${profileDateTime(s.start)} · ${if (s.night) "Ночь" else "День"}")
                }
            }
        }
    }, confirmButton = { TextButton({ call = null }) { Text("Закрыть") } })
}

@Composable private fun ReportView(d: ProfileData, now: LocalDateTime, margin: androidx.compose.ui.unit.Dp) {
    var period by rememberSaveable { mutableStateOf("all") }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(now).toString()) }
    var year by rememberSaveable { mutableIntStateOf(now.year) }
    val selected = YearMonth.parse(month)
    val from = when (period) {
        "month" -> selected.atDay(1)
        "year" -> LocalDate.of(year, 1, 1)
        else -> null
    }
    val until = when (period) {
        "month" -> from!!.plusMonths(1)
        "year" -> from!!.plusYears(1)
        else -> null
    }
    val stats = ProfileMath.statistics(d.calls, d.shifts, d.links, now, from, until)
    val before = if (from == null) 0L else ProfileMath.statistics(d.calls, d.shifts, d.links, now, null, from).xp
    val first = ProfileMath.firstDate(d.calls, d.shifts)
    val monthDays = if (period == "month") (1..selected.lengthOfMonth()).filter {
        selected < YearMonth.from(now) || it <= now.dayOfMonth
    }.map { selected.atDay(it) } else emptyList()
    val yearMonths = if (period == "year") (1..12).filter { year < now.year || it <= now.monthValue }
        .map { LocalDate.of(year, it, 1) } else emptyList()
    LazyColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("all" to "Всё время", "month" to "Месяц", "year" to "Год").forEach { (key, label) ->
                        val active = period == key
                        Surface({ period = key }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            color = if (active) Tint else Color.White) {
                            Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (active) Accent else Muted, fontSize = 13.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        if (period != "all") item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (period == "year") year-- else month = selected.minusMonths(1).toString() }) {
                    Icon(Icons.Outlined.ChevronLeft, "Предыдущий период")
                }
                Text(if (period == "year") "$year год" else selected.format(DateTimeFormatter.ofPattern("LLLL uuuu", Russian)),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                IconButton({ if (period == "year") year++ else month = selected.plusMonths(1).toString() },
                    enabled = if (period == "year") year < now.year else selected < YearMonth.from(now)) {
                    Icon(Icons.Outlined.ChevronRight, "Следующий период")
                }
            }
        }
        item {
            ProfileCard {
                Text(when (period) {
                    "month" -> if (until!! > now.toLocalDate()) "ПРЕДВАРИТЕЛЬНЫЕ ИТОГИ МЕСЯЦА" else "ИТОГИ МЕСЯЦА"
                    "year" -> if (until!! > now.toLocalDate()) "ПРЕДВАРИТЕЛЬНЫЕ ИТОГИ ГОДА" else "ИТОГИ ГОДА"
                    else -> "ЗА ВСЁ ВРЕМЯ"
                }, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text("${stats.calls}", style = MaterialTheme.typography.headlineLarge, color = Accent)
                Text("сохранённых вызовов", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(color = Paper)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${stats.shifts}", style = MaterialTheme.typography.titleLarge)
                        Text("Смен", color = Muted, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${stats.shifts - stats.nights}", style = MaterialTheme.typography.titleLarge)
                        Text("Дневных", color = Muted, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${stats.nights}", style = MaterialTheme.typography.titleLarge)
                        Text("Ночных", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyNumber(stats.average?.let { String.format(Russian, "%.1f", it) } ?: "—", "Среднее за смену", Modifier.weight(1f))
                KeyNumber(stats.maximum?.toString() ?: "—", "Рекорд за смену", Modifier.weight(1f))
            }
        }
        item {
            ProfileCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bolt, null, tint = Accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Опыт", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("${stats.xp} XP", color = Accent, fontWeight = FontWeight.SemiBold)
                }
                Text("Уровень ${ProfileMath.level(before).level} → ${ProfileMath.level(before + stats.xp).level}", color = Muted)
                Text("Вызовы учитываются по дате записи, показатели смен — по дате их начала.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (period != "all") item {
            ProfileCard {
                Text(if (period == "month") "Вызовы по дням" else "Активность по месяцам", style = MaterialTheme.typography.titleMedium)
                val bins = if (period == "month") monthDays else yearMonths
                val values = bins.map { start ->
                    val end = if (period == "month") start.plusDays(1) else start.plusMonths(1)
                    d.calls.count { call -> runCatching {
                        val day = LocalDate.parse(call.date); day >= start && day < end
                    }.getOrDefault(false) }
                }
                if (values.all { it == 0 }) Text("В выбранном периоде пока нет сохранённых вызовов.", color = Muted,
                    style = MaterialTheme.typography.bodyMedium)
                else {
                    val maximum = (values.maxOrNull() ?: 1).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(if (period == "month") 6.dp else 11.dp),
                        verticalAlignment = Alignment.Bottom) {
                        bins.forEachIndexed { index, date ->
                            val number = values[index]
                            Column(Modifier.width(if (period == "month") 23.dp else 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (number == 0) "" else "$number", color = Accent, fontSize = 10.sp)
                                Box(Modifier.fillMaxWidth().height(86.dp), contentAlignment = Alignment.BottomCenter) {
                                    Box(Modifier.fillMaxWidth(.65f).fillMaxHeight((number.toFloat() / maximum).coerceAtLeast(.035f))
                                        .clip(RoundedCornerShape(5.dp)).background(if (number == 0) Field else Accent))
                                }
                                Text(if (period == "month") date.dayOfMonth.toString()
                                    else date.format(DateTimeFormatter.ofPattern("MMM", Russian)).take(3), color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        if (first != null) item {
            Text("Записки ведутся с ${profileDate(first.toString())}. Отсутствие записей не означает отсутствие работы.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val awards = d.unlocks.filter {
            val unlocked = Instant.ofEpochMilli(it.unlockedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            (from == null || unlocked >= from) && (until == null || unlocked < until)
        }.sortedByDescending { it.unlockedAt }
        if (awards.isNotEmpty()) item {
            ProfileCard {
                Text("Полученные достижения", style = MaterialTheme.typography.titleMedium)
                awards.take(5).forEach { award ->
                    ProfileMath.medals.find { it.id == award.id }?.let { medal ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MedalPicture(medal.category, true, 33)
                            Text("${medal.title} · ${medal.threshold} ${medal.unit}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (awards.size > 5) Text("И ещё ${awards.size - 5}", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
