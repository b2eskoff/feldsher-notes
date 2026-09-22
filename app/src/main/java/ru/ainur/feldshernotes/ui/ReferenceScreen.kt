package ru.ainur.feldshernotes.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable fun ReferenceScreen(compact: Boolean, onBack: () -> Unit, initialId: String = "") {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("reference_favorites_v1",Context.MODE_PRIVATE) }
    val medicines=remember { MedicineRepository(context) }; val icd=remember { IcdRepository(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("Препараты") }
    if(initialId.startsWith("icd-nsi-")) {MkbScreen(compact,onBack,initialId);return}
    if(tab=="МКБ-10") {MkbScreen(compact,{tab="Препараты"});return}
    var openedId by rememberSaveable(initialId) { mutableStateOf(initialId) }
    if(openedId.startsWith("icd-nsi-")) {MkbScreen(compact,{openedId=""},openedId);return}
    var history by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("ids",emptySet()).orEmpty().toSet()) }
    var recent by remember { mutableStateOf(prefs.getString("recent","").orEmpty().split('|').filter(String::isNotBlank)) }
    var results by remember { mutableStateOf(emptyList<ReferenceItem>()) }
    var opened by remember { mutableStateOf<ReferenceItem?>(null) }
    var children by remember { mutableStateOf(emptyList<ReferenceItem>()) }
    var loading by remember { mutableStateOf(false) };var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) };var limit by remember { mutableIntStateOf(40) }
    var combinations by rememberSaveable {mutableStateOf(false)}
    var favoritesReady by remember {mutableStateOf(false)}
    val list=rememberLazyListState()
    fun back() { if(history.isNotEmpty()) { openedId=history.last();history=history.dropLast(1) } else if(openedId.isNotEmpty() && initialId.isEmpty()) openedId="" else onBack() }
    BackHandler { back() }
    fun open(item: ReferenceItem) {
        if(openedId.isNotBlank()) history=history+openedId
        openedId=item.id
        recent=(listOf(item.id)+recent).distinct().take(12)
        prefs.edit().putString("recent",recent.joinToString("|")).apply()
    }
    fun favorite(item: ReferenceItem) {
        favorites=if(item.id in favorites) favorites-item.id else favorites+item.id
        prefs.edit().putStringSet("ids",favorites).apply()
    }
    suspend fun resolve(id: String): ReferenceItem? = withContext(Dispatchers.IO) {
        if(id.startsWith("icd-nsi-")) icd.item(id) else medicines.item(id) ?: ReferenceCatalog.items.firstOrNull { it.id==id }
    }
    LaunchedEffect(Unit) {
        try {
            val migrated=withContext(Dispatchers.IO) {
                fun canonical(id: String)=if(id.startsWith("med-") || id.startsWith("drug-grls-")) medicines.item(id)?.id ?: id else id
                favorites.map(::canonical).toSet() to recent.map(::canonical).distinct()
            }
            favorites=migrated.first;recent=migrated.second
            prefs.edit().putStringSet("ids",favorites).putString("recent",recent.joinToString("|")).apply()
        } catch(e: CancellationException) {throw e}
        catch(_: Exception) { /* Keep the original bookmarks if the reference cannot open. */ }
        finally {favoritesReady=true}
    }
    LaunchedEffect(query,tab) { limit=40;list.scrollToItem(0) }
    LaunchedEffect(query,tab,openedId,favorites,retry,limit,combinations,favoritesReady) {
        loading=true;error=false;opened=null
        if(!favoritesReady) return@LaunchedEffect
        try {
            if(openedId.isNotBlank()) {
                opened=resolve(openedId)
                children=if(opened?.kind==ReferenceKind.ICD) withContext(Dispatchers.IO) { icd.childrenByCode(opened!!.code) } else emptyList()
            } else {
                results=emptyList()
                if(query.isNotBlank()) delay(140)
                results=withContext(Dispatchers.IO) {
                    when(tab) {
                        "МКБ-10" -> if(query.isBlank()) icd.chapters() else icd.search(query,limit)
                        "Избранное" -> (medicines.favorites(favorites)+icd.favorites(favorites)+ReferenceCatalog.items.filter { it.id in favorites }).distinctBy { it.id }
                            .filter { query.isBlank() || ReferenceSearch.find(listOf(it),query).isNotEmpty() }
                        "Недавние" -> recent.mapNotNull { id -> if(id.startsWith("icd-nsi-")) icd.item(id) else medicines.item(id) }
                            .filter { query.isBlank() || ReferenceSearch.find(listOf(it),query).isNotEmpty() }
                        else -> if(query.isBlank()) medicines.quick() else medicines.search(query,limit,includeCombinations=combinations)
                    }
                }
            }
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { error=true }
        finally { loading=false }
    }
    val margin=if(compact) 12.dp else 22.dp
    Column(Modifier.fillMaxSize().background(Paper).imePadding().testTag("referenceScreen")) {
        PageHeader(if(openedId.isBlank()) "Справочник" else "Карточка",compact,::back)
        if(openedId.isBlank()) Column(Modifier.padding(horizontal=margin)) {
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().testTag("referenceSearch"),singleLine=true,
                placeholder={Text(if(tab=="МКБ-10") "Диагноз или код МКБ…" else "Препарат, МНН или привычное название…",maxLines=1)},
                leadingIcon={Icon(Icons.Outlined.Search,null,tint=Accent)},
                trailingIcon={if(query.isNotEmpty()) IconButton({query=""}) {Icon(Icons.Outlined.Close,"Очистить")}},
                shape=RoundedCornerShape(20.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Color.White,focusedContainerColor=Color.White))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                listOf("Препараты","МКБ-10","Избранное","Недавние").forEach { label -> FilterChip(tab==label,{tab=label},label={Text(label)}) }
            }
            if(tab=="Препараты" && query.isNotBlank()) FilterChip(combinations,{combinations=!combinations},label={Text("Комбинированные препараты",fontSize=12.sp)})
            Text(if(query.isBlank()) when(tab) { "Препараты" -> "Часто нужны · всё доступно без интернета";"МКБ-10" -> "Все разделы классификатора";else -> "Карточки на устройстве" } else "Результаты поиска",color=Muted,fontSize=12.sp,modifier=Modifier.padding(bottom=6.dp))
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Accent)
        if(openedId.isNotBlank()) {
            val item=opened
            if(item!=null) key(item.id) {
                LazyColumn(contentPadding=PaddingValues(margin),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { ReferenceDetail(item,item.id in favorites,compact) { favorite(item) } }
                    if(children.isNotEmpty()) item {Text("Уточнить код",fontWeight=FontWeight.SemiBold)}
                    items(children,key={it.id}) { child -> ReferenceResult(child,child.id in favorites,compact,{open(child)},{favorite(child)}) }
                }
            } else if(!loading && !error) Text("Карточка недоступна. Текст записи сохранён.",Modifier.padding(margin),color=Muted)
        } else LazyColumn(state=list,contentPadding=PaddingValues(start=margin,end=margin,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(results.isEmpty() && !loading && !error) item {
                Surface(shape=RoundedCornerShape(22.dp),color=Color.White) { Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(if(tab=="Избранное") "Сохрани нужное под рукой" else if(tab=="Недавние") "Здесь будут открытые карточки" else "Ничего не нашлось",fontWeight=FontWeight.SemiBold)
                    Text(if(tab=="Избранное") "Нажми закладку рядом с препаратом или диагнозом." else "Можно искать по части названия, торговому наименованию или коду.",color=Muted)
                } }
            }
            items(results,key={it.id}) { item -> ReferenceResult(item,item.id in favorites,compact,{open(item)},{favorite(item)}) }
            if(query.isNotBlank() && results.size>=limit && limit<120) item { TextButton({limit+=40}) {Text("Показать ещё") } }
        }
        if(error) Column(Modifier.padding(margin)) {
            Text("Не удалось открыть справочник. Проверь свободное место и попробуй ещё раз.",color=Accent)
            TextButton({retry++}) {Text("Повторить")}
        }
    }
}

@Composable private fun ReferenceResult(item: ReferenceItem, favorite: Boolean, compact: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(onClick=onOpen,shape=RoundedCornerShape(20.dp),color=Color.White,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start=if(compact) 13.dp else 17.dp,top=12.dp,bottom=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                if(item.kind==ReferenceKind.ICD) Text(item.code,color=Accent,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                val combination=item.kind==ReferenceKind.MEDICINE && '+' in item.inn
                Text(if(combination) item.tradeNames.firstOrNull { '+' !in it } ?: item.title else item.title,fontWeight=FontWeight.SemiBold,fontSize=if(compact) 15.sp else 17.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                if(combination) Text(item.inn.replace("+"," + "),color=Muted,fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                if(item.latin.isNotBlank()) Text(item.latin,color=Accent,fontSize=12.sp)
                if(item.kind==ReferenceKind.MEDICINE) {
                    Text(item.tradeNames.filterNot { it.equals(item.title,true) }.take(5).joinToString(" · ").ifBlank { item.description },color=Muted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(if(item.clinical) "Применение · формы · важное" else "Формы · торговые названия",color=Muted,fontSize=11.sp)
                }
            }
            IconButton(onToggle) {Icon(if(favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,"Избранное",tint=Accent)}
        }
    }
}

@Composable private fun ReferenceDetail(item: ReferenceItem, favorite: Boolean, compact: Boolean, onToggle: () -> Unit) {
    val clipboard=LocalClipboardManager.current;val uri=LocalUriHandler.current
    var namesExpanded by rememberSaveable {mutableStateOf(false)}
    var formsExpanded by rememberSaveable {mutableStateOf(false)}
    var sourceExpanded by rememberSaveable {mutableStateOf(false)}
    Surface(shape=RoundedCornerShape(22.dp),color=Color.White,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if(compact) 15.dp else 20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                SmallTag(if(item.kind==ReferenceKind.ICD) "МКБ-10 · ${item.code}" else "Действующее вещество")
                Spacer(Modifier.weight(1f));IconButton(onToggle) {Icon(if(favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,"Избранное",tint=Accent)}
            }
            Text(item.title,style=MaterialTheme.typography.headlineSmall)
            if(item.latin.isNotBlank()) Text(item.latin,color=Accent,fontSize=17.sp)
            if(item.prescriptionLatin.isNotBlank()) Text("В латинской записи: ${item.prescriptionLatin}",color=Muted,fontSize=13.sp)
            if(item.kind==ReferenceKind.ICD) {
                TextButton({clipboard.setText(AnnotatedString("${item.code} — ${item.title}"))}) {Text("Копировать диагноз и код")}
                if(item.description.isNotBlank()) ReferenceDetailRow("Коротко",item.description)
                if(item.symptoms.isNotBlank()) ReferenceDetailRow("Проявления",item.symptoms)
                if(item.notes.isNotBlank()) ReferenceDetailRow("Примечание",item.notes)
            } else {
                ReferenceDetailRow(if(item.clinical) "Для чего применяется" else "Фармакотерапевтическая группа",item.description.ifBlank { "В этой редакции сведения не указаны." })
                if(item.dosing.isNotBlank()) ReferenceDetailRow("Введение · по указанной инструкции",item.dosing)
                if(item.notes.isNotBlank()) ReferenceDetailRow("Разведение и совместимость",item.notes)
                if(item.precautions.isNotBlank()) ReferenceDetailRow("Что важно учесть",item.precautions)
                if(!item.clinical) Text("Клиническая справка для этого вещества пока не добавлена. Здесь доступны названия и зарегистрированные формы.",color=Muted,fontSize=12.sp)
                HorizontalDivider(color=Paper)
                Text("Формы и концентрации",fontWeight=FontWeight.SemiBold)
                val forms=item.medicineForms
                forms.take(if(formsExpanded) forms.size else 6).forEach { form ->
                    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(form.label,fontSize=14.sp)
                        Text(form.brands.take(3).joinToString(" · "),fontSize=11.sp,color=Muted)
                    }
                }
                if(forms.isEmpty() && item.forms.isNotBlank()) Text(item.forms,color=Muted)
                if(forms.size>6) TextButton({formsExpanded=!formsExpanded}) {Text(if(formsExpanded) "Свернуть формы" else "Все формы (${forms.size})")}
                Text("Указан размер ампулы/упаковки, а не введённая пациенту доза.",fontSize=12.sp,color=Muted)
                if(item.tradeNames.isNotEmpty()) {
                    ReferenceDetailRow("Торговые названия",item.tradeNames.take(if(namesExpanded) item.tradeNames.size else 8).joinToString(" · "))
                    if(item.tradeNames.size>8) TextButton({namesExpanded=!namesExpanded}) {Text(if(namesExpanded) "Свернуть названия" else "Все названия (${item.tradeNames.size})")}
                }
            }
            HorizontalDivider(color=Paper)
            TextButton({sourceExpanded=!sourceExpanded}) {Text(if(sourceExpanded) "Скрыть источник" else "Источник и редакция")}
            if(sourceExpanded) {
                Text(item.source,color=Muted,fontSize=12.sp)
                Text("Редакция: ${item.checkedOn}",color=Muted,fontSize=12.sp)
                if(item.sourceUrl.isNotBlank()) TextButton({runCatching {uri.openUri(item.sourceUrl)}}) {Text("Открыть источник в интернете")}
                if(item.kind==ReferenceKind.MEDICINE) Text("Краткая справка не заменяет полную инструкцию и протокол. Показания, противопоказания, разведение и скорость сверяются для конкретной формы.",color=Muted,fontSize=12.sp)
            }
        }
    }
}
@Composable private fun ReferenceDetailRow(title: String,content: String) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(title,fontWeight=FontWeight.SemiBold,fontSize=15.sp);Text(content,style=MaterialTheme.typography.bodyMedium,color=Ink) }
}
