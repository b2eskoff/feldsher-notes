package ru.ainur.feldshernotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One offline classifier shared by the reference and editor links. No personal data writes. */
@Composable internal fun MkbScreen(compact: Boolean,onBack: ()->Unit,initialId: String="") {
    val context=LocalContext.current
    val repo=remember {IcdRepository(context)}
    var nodeId by rememberSaveable(initialId) {mutableStateOf(initialId)}
    var query by rememberSaveable {mutableStateOf("")}
    var savedQuery by rememberSaveable {mutableStateOf("")}
    var searchOrigin by rememberSaveable {mutableStateOf("")}
    var searchReturn by rememberSaveable {mutableStateOf(false)}
    var current by remember {mutableStateOf<ReferenceItem?>(null)}
    var rows by remember {mutableStateOf(emptyList<ReferenceItem>())}
    var ancestors by remember {mutableStateOf(emptyList<ReferenceItem>())}
    var guide by remember {mutableStateOf<IcdGuide?>(null)}
    var loaded by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    var limit by remember {mutableIntStateOf(40)}
    val list=rememberLazyListState()
    val margin=if(compact) 12.dp else 22.dp
    fun back() {
        when {
            query.isNotBlank() -> query=""
            searchReturn -> {nodeId=searchOrigin;query=savedQuery;searchReturn=false}
            ancestors.isNotEmpty() -> nodeId=ancestors.last().id
            nodeId.isNotBlank() && initialId.isBlank() -> nodeId=""
            else -> onBack()
        }
    }
    BackHandler {back()}
    fun open(item: ReferenceItem) {
        if(query.isNotBlank()) {savedQuery=query;searchOrigin=nodeId;searchReturn=true;query=""}
        nodeId=item.id
    }
    LaunchedEffect(query,nodeId) {limit=40;list.scrollToItem(0)}
    LaunchedEffect(query,nodeId,limit,retry) {
        loaded=false;error=false;rows=emptyList();guide=null;current=null;ancestors=emptyList()
        try {
            if(query.isNotBlank()) delay(160)
            val result=withContext(Dispatchers.IO) {
                val item=nodeId.takeIf(String::isNotBlank)?.let(repo::item)
                val parents=mutableListOf<ReferenceItem>();var parent=item?.let {repo.parentId(it.id)}
                while(parent!=null && parents.size<12) {
                    val p=repo.item("icd-nsi-$parent") ?: break
                    parents.add(0,p);parent=repo.parentId(p.id)
                }
                val children=when {
                    query.isNotBlank() -> repo.search(query,limit)
                    item!=null -> repo.children(item.id.removePrefix("icd-nsi-").toLong())
                    nodeId.isBlank() -> repo.chapters()
                    else -> emptyList()
                }
                MkbPage(item,children,parents,item?.let {IcdGuides.forCode(context,it.code)})
            }
            current=result.item;rows=result.rows;ancestors=result.parents;guide=result.guide
        } catch(e: CancellationException) {throw e}
        catch(_: Exception) {error=true}
        finally {loaded=true}
    }
    Column(Modifier.fillMaxSize().testTag("mkbScreen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=margin,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton({back()}) {Icon(Icons.Outlined.ArrowBack,"Назад")}
            Text("МКБ",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            if(nodeId.isNotBlank()) TextButton({nodeId="";query="";searchReturn=false}) {Text("Классы")}
        }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=margin).testTag("mkbSearch"),
            placeholder={Text("Код / диагноз / обычные слова")},singleLine=true,shape=RoundedCornerShape(24.dp),
            leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty()) IconButton({query=""}) {Icon(Icons.Outlined.Close,"Очистить поиск")}})
        Text(if(query.isBlank()) "Офлайн · МКБ-10 РФ · 15 038 рубрик" else "Поиск по всей МКБ · учитываю всю фразу",fontSize=11.sp,color=Muted,modifier=Modifier.padding(horizontal=margin,vertical=8.dp))
        if(query.isBlank() && ancestors.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=margin)) {
            ancestors.forEach {parent -> TextButton({nodeId=parent.id;searchReturn=false}) {Text(parent.code)}}
        }
        if(!loaded) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Accent)
        LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(start=margin,end=margin,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(query.isBlank() && current!=null) item(key="heading") {
                MkbCodeHeader(current!!)
                if(rows.isNotEmpty()) Text("Выбери уточнение",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp))
            }
            items(rows,key={it.id}) {entry ->
                Surface(onClick={open(entry)},color=Paper,modifier=Modifier.fillMaxWidth().testTag("mkbRow:${entry.code}")) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(mkbCodeLabel(entry.code),color=Accent,fontWeight=FontWeight.Bold,fontSize=19.sp)
                                Text(if(entry.code in chapterRanges) entry.title.lowercase().replaceFirstChar {it.uppercase()} else entry.title,fontSize=if(compact) 15.sp else 17.sp)
                                if(entry.searchHint.isNotBlank()) Text(entry.searchHint,color=Muted,fontSize=11.sp)
                            }
                            Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
                        }
                        HorizontalDivider(color=Muted.copy(alpha=.2f))
                    }
                }
            }
            if(query.isNotBlank() && rows.size>=limit && limit<200) item {TextButton({limit+=40}) {Text("Ещё результаты")}}
            if(loaded && !error && query.isNotBlank() && rows.isEmpty()) item {
                Text("По этой фразе ничего не найдено",fontWeight=FontWeight.SemiBold)
                Text("Попробуй другое название или код. Поиск не отбрасывает неизвестные слова и не превращает симптомы в диагноз. В карте можно оставить свою формулировку.",color=Muted)
            }
            if(loaded && !error && query.isBlank() && current!=null) {
                val clinical=guide
                if(clinical!=null) item(key="guide") {MkbClinicalCard(clinical,current!!.code)}
                else item {
                    Text("Для этой рубрики клинический материал пока не подготовлен. Код и название доступны полностью.",color=Muted,fontSize=12.sp)
                }
            }
            if(query.isBlank() && nodeId.isBlank()) item {
                Text("35 клинических тем · 299 рубрик с тематической справкой. Классификатор полный; клиническое наполнение пока не для всех заболеваний. Поиск понимает синонимы и одну опечатку; окончательную формулировку выбираешь ты.",fontSize=12.sp,color=Muted)
            }
            if(error) item {
                Text("Не удалось открыть МКБ. Записи вызовов не затронуты.",color=Accent)
                TextButton({retry++}) {Text("Повторить")}
            }
        }
    }
}
private data class MkbPage(val item: ReferenceItem?,val rows: List<ReferenceItem>,val parents: List<ReferenceItem>,val guide: IcdGuide?)
private val chapterRanges=mapOf("I" to "A00–B99","II" to "C00–D48","III" to "D50–D89","IV" to "E00–E90","V" to "F00–F99","VI" to "G00–G99","VII" to "H00–H59","VIII" to "H60–H95","IX" to "I00–I99","X" to "J00–J99","XI" to "K00–K93","XII" to "L00–L99","XIII" to "M00–M99","XIV" to "N00–N99","XV" to "O00–O99","XVI" to "P00–P96","XVII" to "Q00–Q99","XVIII" to "R00–R99","XIX" to "S00–T98","XX" to "V01–Y98","XXI" to "Z00–Z99","XXII" to "U00–U85")
private fun mkbCodeLabel(code: String): String = chapterRanges[code]?.let {"Класс $code ($it)"} ?: code

@Suppress("DEPRECATION")
@Composable private fun MkbCodeHeader(item: ReferenceItem) {
    val clipboard=LocalClipboardManager.current
    var copied by remember(item.id) {mutableStateOf(false)}
    LaunchedEffect(copied) {if(copied) {delay(1800);copied=false}}
    Surface(onClick={clipboard.setText(AnnotatedString("${item.code} — ${item.title}"));copied=true},shape=RoundedCornerShape(20.dp),color=Tint,modifier=Modifier.fillMaxWidth().testTag("mkbCopy")) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(mkbCodeLabel(item.code),color=Accent,fontWeight=FontWeight.Bold,fontSize=24.sp)
            Text(item.title,style=MaterialTheme.typography.titleMedium)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.ContentCopy,null,Modifier.size(15.dp),tint=Muted)
                Text(if(copied) "  Скопировано" else "  Нажми, чтобы скопировать код и название",fontSize=11.sp,color=Muted)
            }
        }
    }
}
@Composable private fun MkbClinicalCard(guide: IcdGuide,code: String) {
    val uri=LocalUriHandler.current
    var linkError by remember {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.padding(vertical=14.dp).testTag("mkbClinical")) {
        Text("${guide.title} · клиническая справка",fontWeight=FontWeight.Bold)
        Text("${guide.scope}\nОбщая справка по теме, не отдельный протокол для $code.",fontSize=12.sp,color=Muted)
        listOf("Что это" to guide.summary,"Симптомы" to guide.symptoms,"Опасные признаки" to guide.danger,"Тактика для фельдшера СМП" to guide.tactics,"Препараты выбора · по показаниям" to guide.medications).forEach { (title,text) ->
            if(text.isNotBlank()) Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(title,fontWeight=FontWeight.SemiBold,color=if(title=="Опасные признаки") Accent else Ink)
                SelectionContainer {Text(text,lineHeight=23.sp)}
            }
        }
        HorizontalDivider()
        Text("Источники · просмотрены ${guide.reviewed}",fontSize=11.sp,color=Muted)
        guide.sources.forEach {s ->
            Text(s.type,fontSize=11.sp,color=Muted)
            TextButton({try {uri.openUri(s.url)} catch(_: Exception) {linkError=true}},contentPadding=PaddingValues(0.dp)) {Text(s.title,fontSize=12.sp)}
        }
        if(linkError) Text("Не удалось открыть ссылку. Текст справки доступен без интернета.",fontSize=11.sp,color=Muted)
        Text("Применяй действующий локальный алгоритм СМП с учётом оснащения и полномочий. Дозы и противопоказания сверяй с инструкцией; справка не является листом назначений. Авторская краткая сводка по источникам; независимая клиническая рецензия не проведена. Международные материалы не заменяют рекомендации и порядок оказания помощи в РФ.",fontSize=11.sp,color=Muted)
    }
}
