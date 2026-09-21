package ru.ainur.feldshernotes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import ru.ainur.feldshernotes.*
import ru.ainur.feldshernotes.audio.*
import ru.ainur.feldshernotes.data.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable private fun app()=LocalContext.current.applicationContext as NotesApplication
@Composable private fun SoftCard(content:@Composable ColumnScope.()->Unit){
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=Color.White){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}
}
@Composable private fun Input(value:String,label:String,change:(String)->Unit){OutlinedTextField(value,change,Modifier.fillMaxWidth(),label={Text(label)},shape=RoundedCornerShape(16.dp))}
@Composable fun CrewCard(date:String){
    val application=app();val rows by application.database.notes().observeCrew().collectAsStateWithLifecycle(emptyList())
    val crew=rows.firstOrNull{it.date==date} ?: CrewEntity(date)
    var edit by rememberSaveable(date){mutableStateOf(false)}
    Surface({edit=true},Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Color.White){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){
            Text("Состав бригады",fontWeight=FontWeight.Medium);Text(crew.summary.ifBlank{"Не указан"},style=MaterialTheme.typography.bodySmall,color=Muted)
        };Icon(Icons.Outlined.ChevronRight,null,tint=Muted)}
    }
    if(edit){
        var driver by rememberSaveable{mutableStateOf(crew.driver)};var first by rememberSaveable{mutableStateOf(crew.first)};var second by rememberSaveable{mutableStateOf(crew.second)}
        var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};val scope=rememberCoroutineScope()
        AlertDialog(onDismissRequest={if(!busy)edit=false},title={Text("Состав бригады")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(shortDate(date),color=Muted);Input(driver,"Водитель"){driver=it};Input(first,"Фельдшер 1"){first=it};Input(second,"Фельдшер 2"){second=it};error?.let{Text(it,color=Accent)}
        }},confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{
            try{withContext(Dispatchers.IO){application.backups.gate.withLock{application.database.notes().putCrew(CrewEntity(date,driver.trim(),first.trim(),second.trim()))};runCatching{application.backups.autoBackup()}};edit=false}
            catch(e:Exception){error="Не удалось сохранить: ${e.localizedMessage}"}finally{busy=false}
        }}){Text("Сохранить")}},dismissButton={TextButton({edit=false},enabled=!busy){Text("Отмена")}})
    }
}
@Composable fun MemoryScreen(compact:Boolean,onBack:()->Unit,onSection:(Screen)->Unit){
    val application=app();val context=LocalContext.current
    val status by MemoryService.status.collectAsStateWithLifecycle();val event by MemoryService.events.collectAsStateWithLifecycle()
    val recordings by application.database.notes().observeAudio().collectAsStateWithLifecycle(emptyList())
    var now by remember{mutableLongStateOf(SystemClock.elapsedRealtime())};var warning by remember{mutableStateOf<String?>(null)}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){while(true){now=SystemClock.elapsedRealtime();delay(1000)}}
    val running=status.running && now-status.heartbeat<6000
    fun start(){try{ContextCompat.startForegroundService(context,Intent(context,MemoryService::class.java).setAction(MemoryService.START));warning=null}catch(e:Exception){warning="Запуск недоступен: ${e.localizedMessage}"}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        if(result.values.all{it})start() else warning="Для буфера разрешите микрофон и уведомления в настройках приложения."
    }
    val record=recordings.firstOrNull{it.id==selected}
    BackHandler(selected!=null){selected=null}
    if(record!=null){AudioDetail(record,compact){selected=null};return}
    Column(Modifier.fillMaxSize()){
        PageHeader("Память",compact,onBack) { SectionMenu(onSection) }
        LazyColumn(contentPadding=PaddingValues(if(compact)12.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{SoftCard{
                Text(if(running)"● Буфер работает" else "Буфер остановлен",color=if(running)Accent else Muted,fontWeight=FontWeight.SemiBold)
                Text(if(running)"Последние ${MemoryService.duration(status.availableMs)} доступны" else "Хранит только последние 15 минут звука",style=MaterialTheme.typography.bodyMedium)
                PrimaryButton(if(status.saving)"Сохраняем…" else if(running)"Сохранить последние 15 минут" else "Запустить буфер",{
                    if(running)context.startService(Intent(context,MemoryService::class.java).setAction(MemoryService.SAVE))
                    else {val needed=buildList{add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}
                        if(needed.all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED})start() else permission.launch(needed.toTypedArray())}
                },compact=compact,enabled=!status.saving,icon=if(running)Icons.Outlined.SaveAlt else Icons.Outlined.Mic)
                if(running)TextButton({context.startService(Intent(context,MemoryService::class.java).setAction(MemoryService.STOP))}){Text("Остановить буфер",color=Muted)}
                (warning ?: status.error ?: event)?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Muted)}
                if(!running)Text("Работает с системным уведомлением. После перезагрузки запустите буфер снова.",style=MaterialTheme.typography.bodySmall,color=Muted)
            }}
            item{Text("Сохранённые записи",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))}
            if(recordings.isEmpty())item{Text("Здесь появятся сохранённые фрагменты",color=Muted)}
            items(recordings,key={it.id}){a->Surface({selected=a.id},shape=RoundedCornerShape(20.dp),color=Color.White){
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.PlayCircle,null,tint=Accent);Spacer(Modifier.width(12.dp));Column{
                    Text(a.title.ifBlank{audioDate(a.endedAt)},fontWeight=FontWeight.Medium);Text(MemoryService.duration(a.durationMs),color=Muted,style=MaterialTheme.typography.bodySmall)
                }}
            }}
        }
    }
}
private fun audioDate(time:Long):String {val date=Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault());return "${shortDate(date.toLocalDate().toString())}, ${date.format(DateTimeFormatter.ofPattern("HH:mm"))}"}
@Composable private fun AudioDetail(audio:AudioEntity,compact:Boolean,onBack:()->Unit){
    val application=app();val context=LocalContext.current;val scope=rememberCoroutineScope()
    var rename by rememberSaveable(audio.id){mutableStateOf(false)};var remove by rememberSaveable(audio.id){mutableStateOf(false)}
    var title by rememberSaveable(audio.id){mutableStateOf(audio.title)};var message by remember{mutableStateOf<String?>(null)}
    var ready by remember(audio.id){mutableStateOf(false)};var playing by remember(audio.id){mutableStateOf(false)};var position by remember(audio.id){mutableFloatStateOf(0f)}
    val file=File(application.backups.audioDir,audio.fileName)
    val player=remember(audio.id){MediaPlayer()}
    DisposableEffect(player){
        try{player.setDataSource(file.path);player.setOnPreparedListener{ready=true};player.setOnCompletionListener{playing=false;position=0f};player.setOnErrorListener{_,_,_->message="Не удалось воспроизвести файл";playing=false;true};player.prepareAsync()}
        catch(e:Exception){message="Не удалось открыть аудио: ${e.localizedMessage}"}
        onDispose{player.release()}
    }
    val lifecycleOwner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner,player) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP && playing) { runCatching{player.pause()};playing=false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playing){while(playing){position=player.currentPosition.toFloat();delay(300)}}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")){uri->if(uri!=null)scope.launch{
        try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")!!.use{out->file.inputStream().use{it.copyTo(out)}}};message="Аудиофайл экспортирован"}
        catch(e:Exception){message="Экспорт не завершён: ${e.localizedMessage}"}
    }}
    Column(Modifier.fillMaxSize()){
        PageHeader("Аудиозапись",compact,onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(if(compact)12.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            SoftCard{
                Text(audio.title.ifBlank{audioDate(audio.endedAt)},style=MaterialTheme.typography.titleLarge)
                Text("${audioDate(audio.startedAt)} · ${MemoryService.duration(audio.durationMs)}",color=Muted)
                PrimaryButton(if(playing)"Пауза" else "Воспроизвести",{if(playing)player.pause() else player.start();playing=!playing},compact=compact,enabled=ready,icon=if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow)
                if(ready)Slider(position.coerceIn(0f,maxOf(1,player.duration).toFloat()),{position=it;player.seekTo(it.toInt())},valueRange=0f..maxOf(1,player.duration).toFloat())
            }
            OutlinedButton({rename=true},Modifier.fillMaxWidth()){Text("Переименовать")}
            OutlinedButton({try{val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Поделиться аудио"))
            }catch(e:Exception){message="Не удалось поделиться: ${e.localizedMessage}"}},Modifier.fillMaxWidth()){Text("Поделиться")}
            OutlinedButton({export.launch(file.name)},Modifier.fillMaxWidth()){Text("Экспортировать")}
            TextButton({remove=true},Modifier.fillMaxWidth()){Text("Удалить")};message?.let{Text(it,color=Muted)}
        }
    }
    if(rename)AlertDialog({rename=false},title={Text("Название записи")},text={Input(title,"Название"){title=it}},confirmButton={TextButton({scope.launch{
        try{withContext(Dispatchers.IO){application.backups.gate.withLock{application.database.notes().putAudio(audio.copy(title=title.trim()))};runCatching{application.backups.autoBackup()}};rename=false}
        catch(e:Exception){message="Не удалось переименовать: ${e.localizedMessage}";rename=false}
    }}){Text("Сохранить")}},dismissButton={TextButton({rename=false}){Text("Отмена")}})
    if(remove)AlertDialog({remove=false},title={Text("Удалить аудиозапись?")},text={Text("Сохранённый файл будет удалён с устройства.")},confirmButton={TextButton({scope.launch{
        try{withContext(Dispatchers.IO){application.backups.gate.withLock{application.database.notes().deleteAudio(audio.id);file.delete()};runCatching{application.backups.autoBackup()}};onBack()}
        catch(e:Exception){message="Не удалось удалить: ${e.localizedMessage}";remove=false}
    }}){Text("Удалить")}},dismissButton={TextButton({remove=false}){Text("Оставить")}})
}
@Composable fun MoreScreen(compact:Boolean,model:NotesViewModel){
    val application=app();val context=LocalContext.current;val scope=application.operationScope
    var pending by rememberSaveable{mutableStateOf<String?>(null)};val message by application.operationMessage.collectAsStateWithLifecycle()
    var busy by remember{mutableStateOf(false)};var copies by remember{mutableStateOf(emptyList<File>())}
    val auto by application.backups.automaticStatus.collectAsStateWithLifecycle()
    suspend fun refresh(){copies=withContext(Dispatchers.IO){application.backups.copies.listFiles().orEmpty().filter{it.extension in listOf("zip","json")}.sortedByDescending{it.lastModified()}.take(12)}}
    LaunchedEffect(Unit){refresh()};BackHandler(busy){}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null){busy=true;scope.launch{
        try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt")!!.use{application.backups.export(it)}};application.operationMessage.value="Полная резервная копия создана"}
        catch(e:Exception){application.operationMessage.value="Копия не создана: ${e.localizedMessage}"}finally{busy=false}
    }}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->pending=uri?.toString()}
    Column(Modifier.fillMaxSize()){
        PageHeader("Резервные копии",compact,{if(!busy)model.back()}) { SectionMenu(model::openSection,enabled=!busy) }
        LazyColumn(contentPadding=PaddingValues(if(compact)12.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{SoftCard{
                Text("Резервные копии",style=MaterialTheme.typography.titleLarge)
                Text("Архив с вызовами, составом бригады и аудио. TXT для чтения, JSON для восстановления.",color=Muted)
                PrimaryButton("Создать резервную копию",{export.launch("ZapiskiFeldshera_Backup_${java.time.LocalDate.now()}.zip")},compact=compact,enabled=!busy)
                OutlinedButton({restore.launch(arrayOf("application/zip","application/json","application/octet-stream"))},Modifier.fillMaxWidth(),enabled=!busy){Text("Восстановить")}
                Text(if(busy)"Выполняется операция…" else auto,style=MaterialTheme.typography.bodySmall,color=Muted)
                message?.let{Text(it,color=Accent)}
            }}
            item{Text("Локальные копии",fontWeight=FontWeight.SemiBold);Text("7 последних снимков данных. Аудио хранится отдельно; полный перенос — через ZIP.",style=MaterialTheme.typography.bodySmall,color=Muted)}
            items(copies,key={it.path}){file->TextButton({pending=Uri.fromFile(file).toString()},enabled=!busy){Text("${if(file.extension=="zip")"Перед восстановлением" else "Автокопия"} · ${audioDate(file.lastModified())}")}}
            item{Text("Записки фельдшера · ${BuildConfig.VERSION_NAME}\nЛичный журнал. Все данные на устройстве. Не является МИС или средством диагностики.",color=Muted,style=MaterialTheme.typography.bodySmall)}
        }
    }
    if(pending!=null)AlertDialog({pending=null},title={Text("Восстановить данные?")},text={Text("Сначала будет создан полный архив текущего состояния. Совпадающие записи будут заменены данными из копии. Остальные вызовы останутся. Черновик из копии также будет восстановлен.")},confirmButton={TextButton({
        val uri=pending!!.toUri();pending=null;busy=true;application.maintenance.value=true;scope.launch{
            try{model.awaitPendingWrites();withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.buffered().use{input->
                input.mark(4);val zip=input.read()==80 && input.read()==75;input.reset();application.backups.restore(input,zip)
            }};model.reloadAfterRestore();application.operationMessage.value="Данные восстановлены. Защитная копия сохранена.";refresh()}
            catch(e:Exception){application.operationMessage.value="Восстановление не выполнено: ${e.localizedMessage}"}finally{busy=false;application.maintenance.value=false}
        }
    }){Text("Восстановить")}},dismissButton={TextButton({pending=null}){Text("Отмена")}})
}
