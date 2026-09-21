package ru.ainur.feldshernotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.ainur.feldshernotes.*
import ru.ainur.feldshernotes.data.*
import java.time.LocalDate

@Composable fun NotesApp(model: NotesViewModel) {
    val application=androidx.compose.ui.platform.LocalContext.current.applicationContext as NotesApplication
    val maintenance by application.maintenance.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it,duration = SnackbarDuration.Short);model.clearMessage() } }
    BackHandler(state.screen != Screen.JOURNAL || maintenance) { if(!maintenance)model.back() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().testTag("appRoot")) {
        val compact = maxWidth < 360.dp || maxHeight < 480.dp
        Box(Modifier.fillMaxSize(),contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                if (!state.ready || state.loadFailed) Column(Modifier.align(Alignment.Center).padding(32.dp),horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandLogo();Spacer(Modifier.height(24.dp))
                    if (state.loadFailed) Text("Не удалось открыть журнал. Перезапустите приложение.")
                    else CircularProgressIndicator(Modifier.size(24.dp),color = Accent,strokeWidth = 2.dp)
                } else Column {
                    Box(Modifier.weight(1f)) { Crossfade(state.screen,animationSpec = tween(150),label = "screen") {
                    when(it) {
                        Screen.MEMORY -> MemoryScreen(compact,model::back,model::openSection)
                        Screen.PROFILE -> ProfileScreen(compact,model::back)
                        Screen.REFERENCE -> ReferenceScreen(compact,model::back)
                        Screen.MORE -> MoreScreen(compact,model)
                        Screen.JOURNAL -> JournalScreen(state,compact,model)
                        Screen.DETAIL -> DetailScreen(state,compact,model)
                        Screen.SEARCH -> SearchScreen(state,compact,model)
                        Screen.EDITOR -> state.draft?.let { r -> EditorScreen(r,state.draftIsNew,state.saving,compact,model) }
                    }
                } }

                }
            }
            if(maintenance) Surface(Modifier.fillMaxSize().clickable(enabled=true,onClick={}),color=Paper.copy(alpha=.96f)) {
                Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color=Accent);Spacer(Modifier.height(20.dp));Text("Восстанавливаем данные…");Text("Сначала сохраняем текущее состояние",color=Muted)
                }
            }
            SnackbarHost(snack,Modifier.align(Alignment.BottomCenter).imePadding().padding(start = 12.dp,end = 12.dp,bottom = if(state.screen == Screen.EDITOR || state.screen == Screen.DETAIL) 82.dp else 12.dp))
        }
    }
}
@Composable fun JournalScreen(s: NotesState,compact: Boolean,model: NotesViewModel) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState();val margin = if (compact) 12.dp else 22.dp
    LaunchedEffect(s.selectedDate,s.highlightId) {
        val index = s.dayCalls.indexOfFirst { it.id == s.highlightId }
        list.scrollToItem(if (index >= 0) index + if (s.draft != null) 1 else 0 else 0)
    }
    Column(Modifier.fillMaxSize().testTag(if (compact) "journalCompact" else "journalFull")) {
        AppHeader(compact,model::openSearch,model::openSection)
        Column(Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = if (compact) 0.dp else 13.dp,bottom = if (compact) 8.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 14.dp)) {
            Column {
                if (!compact) Text("ЛИЧНЫЙ ЖУРНАЛ",Modifier.padding(bottom = 9.dp),fontSize = 10.sp,letterSpacing = 1.8.sp,fontWeight = FontWeight.SemiBold,color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { calendar = true }.padding(vertical = 4.dp).testTag("selectDate"),verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (compact) shortDate(s.selectedDate) else longDate(s.selectedDate),Modifier.weight(1f,fill = false),
                                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium)
                            Icon(Icons.Outlined.ExpandMore,"Открыть календарь",Modifier.padding(start = 3.dp).size(20.dp),tint = Muted)
                        }
                    }
                    IconButton({ model.shiftDate(-1) },Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronLeft,"Предыдущий день") }
                    IconButton({ model.shiftDate(1) },Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronRight,"Следующий день") }
                }
                if (!compact) Text(callCount(s.dayCalls.size),style = MaterialTheme.typography.bodyMedium,color = Muted)
                if (!compact && s.selectedDate != LocalDate.now().toString()) TextButton({ model.selectDate(LocalDate.now().toString()) },contentPadding = PaddingValues(0.dp)) { Text("Вернуться к сегодня") }
            }
            PrimaryButton(if (s.draft == null) { if (compact) "Вызов" else "Новый вызов" } else if (compact) "Продолжить" else "Продолжить запись",
                model::newCall,Modifier.testTag("newCall"),compact,if (s.draft == null) Icons.Outlined.Add else Icons.Outlined.EditNote)
        }
        LazyColumn(Modifier.weight(1f),state = list,contentPadding = PaddingValues(start = margin,end = margin,bottom = 24.dp),verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)) {
            if (s.draft != null) item(key = "draft") {
                Surface(model::resumeDraft,color = Tint,shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.EditNote,null,tint = Accent,modifier = Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Незавершённая запись",fontWeight = FontWeight.Medium,fontSize = 13.sp,color = Accent)
                            Text(shortDate(s.draft.date),fontSize = 12.sp,color = Muted)
                        }
                        Icon(Icons.Outlined.ChevronRight,null,tint = Accent)
                    }
                }
            }
            if (s.dayCalls.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp,vertical = if (compact) 8.dp else 48.dp),horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!compact) {
                        Box(Modifier.size(78.dp).background(Color(0xFFEFEEEC),RoundedCornerShape(26.dp)),contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.EditNote,null,Modifier.size(36.dp),tint = Color(0xFFB7B1B2))
                        };Spacer(Modifier.height(23.dp))
                    }
                    Text("Пока чистый лист",fontWeight = FontWeight.SemiBold,fontSize = if (compact) 15.sp else 20.sp)
                    if (!compact) Text("Запишите первый вызов за этот день.\nДостаточно одной заметки.",Modifier.padding(top = 9.dp),style = MaterialTheme.typography.bodyMedium,color = Muted,textAlign = TextAlign.Center)
                }
            }
            items(s.dayCalls,key = { it.id }) { r -> Box(Modifier.animateItem()) { CallCard(r,compact,{ model.openCall(r.id) },s.highlightId == r.id) } }
            item(key = "crew") { CrewCard(s.selectedDate) }
            if (s.dayCalls.isNotEmpty() && !compact) item(key = "end") {
                Text("·  Всё за этот день",Modifier.fillMaxWidth().padding(top = 8.dp),fontSize = 11.sp,color = Muted,textAlign = TextAlign.Center)
            }
        }
    }
    if (calendar) DateDialog(s.selectedDate,s.calls.map { it.date }.toSet(),{ calendar = false }) { model.selectDate(it);calendar = false }

}
@Composable fun DetailScreen(s: NotesState,compact: Boolean,model: NotesViewModel) {
    var referenceId by rememberSaveable { mutableStateOf("") }
    var remove by rememberSaveable { mutableStateOf(false) };val r = s.openedCall
    Column(Modifier.fillMaxSize()) {
        PageHeader("Запись",compact,model::back) {
            IconButton({ remove = true },enabled = r != null && !s.saving) { Icon(Icons.Outlined.DeleteOutline,"Удалить вызов",tint = Muted) }
        }
        if (r != null) {
            LazyColumn(Modifier.weight(1f),contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 24.dp,vertical = 12.dp),verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
                item {
                    Text(listOfNotNull(longDate(r.date),r.time).joinToString("  ·  "),color = Accent,fontSize = 13.sp,fontWeight = FontWeight.Medium)
                    if (r.title.isNotBlank()) Text(r.title,Modifier.padding(top = 12.dp),style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge)
                }
                if (r.description.isNotBlank()) item {
                    Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(24.dp),color = Color.White) {
                        SelectionContainer { Text(r.description,Modifier.padding(if (compact) 16.dp else 22.dp),style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                items(r.blocks.filter { it.hasContent },key = { it.id }) { b ->
                    Surface(Modifier.fillMaxWidth(),shape = RoundedCornerShape(22.dp),color = Color.White) {
                        Column(Modifier.padding(if (compact) 16.dp else 20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(blockIcon(b.kind),null,Modifier.size(19.dp),tint = Accent);Spacer(Modifier.width(9.dp))
                                Text(b.kind.label,fontSize = 14.sp,fontWeight = FontWeight.SemiBold)
                            }
                            if(b.kind == BlockKind.MEDICINES || b.kind == BlockKind.DIAGNOSIS) LinkedNoteText(b) {referenceId=it}
                            else SelectionContainer { Text(blockSummary(b),style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 22.dp,vertical = 10.dp)) {
                PrimaryButton("Редактировать",model::editCall,Modifier.testTag("editCall"),compact = compact,icon = Icons.Outlined.Edit,enabled = !s.saving)
            }
        } else Text("Запись не найдена",Modifier.padding(24.dp),color = Muted)
    }
    if(referenceId.isNotBlank()) ReferenceOverlay(referenceId,compact) {referenceId=""}
    if (remove) AlertDialog({ remove = false },title = { Text("Удалить вызов?") },text = { Text("Эта запись будет удалена с устройства.") },
        confirmButton = { TextButton({ remove = false;model.deleteOpened() }) { Text("Удалить") } },dismissButton = { TextButton({ remove = false }) { Text("Оставить") } })
}
@Composable fun SearchScreen(s: NotesState,compact: Boolean,model: NotesViewModel) {
    val margin = if (compact) 12.dp else 22.dp
    val requester = remember { FocusRequester() };val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { requester.requestFocus() }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader("Найти вызов",compact,model::back)
        OutlinedTextField(s.query,model::search,Modifier.fillMaxWidth().padding(horizontal = margin).focusRequester(requester).testTag("searchField"),singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            shape = RoundedCornerShape(20.dp),placeholder = { Text("Что помните о вызове?") },leadingIcon = { Icon(Icons.Outlined.Search,null) },
            trailingIcon = { if (s.query.isNotEmpty()) IconButton({ model.search("") }) { Icon(Icons.Outlined.Close,"Очистить поиск") } },
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,focusedBorderColor = Accent.copy(alpha = .35f),unfocusedContainerColor = Color.White,focusedContainerColor = Color.White))
        Text(if (s.query.isBlank()) "По всем датам, заметкам и блокам" else "Найдено: ${callCount(s.searchResults.size)}",Modifier.padding(horizontal = margin+4.dp,vertical = 13.dp),fontSize = 12.sp,color = Muted)
        LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(horizontal = margin,vertical = 4.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.query.isNotBlank() && s.searchResults.isEmpty()) item { Text("Ничего не нашлось.\nПопробуйте другое слово или часть слова.",Modifier.padding(14.dp),color = Muted) }
            items(s.searchResults,key = { it.id }) { r -> CallCard(r,compact,{ model.openCall(r.id) },showDate = true) }
        }
    }
}
