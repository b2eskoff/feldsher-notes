package ru.ainur.feldshernotes.data

import android.content.Context
import ru.ainur.feldshernotes.profile.ProfileCodec
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.zip.*

class BackupStore(private val context:Context,private val db:NotesDatabase) {
    val gate=Mutex()
    val audioDir=File(context.filesDir,"audio").apply{mkdirs()}
    val copies=File(context.noBackupFilesDir,"backups").apply{mkdirs()}
    val automaticStatus=MutableStateFlow(if(copies.listFiles().orEmpty().any{it.name.startsWith("auto-") && it.extension=="json"})"Локальные копии доступны" else "Автокопия появится после изменений")
    private val dao get()=db.notes()
    private suspend fun snapshot():JSONObject=db.withTransaction {
        JSONObject().put("format","feldsher-notes").put("version",1).put("createdAt",System.currentTimeMillis())
            .put("calls",JSONArray().apply{dao.allCalls().forEach{put(JSONObject(RecordCodec.encode(it.record())))}})
            .put("crew",JSONArray().apply{dao.allCrew().forEach{put(JSONObject().put("date",it.date).put("driver",it.driver).put("first",it.first).put("second",it.second))}})
            .put("audio",JSONArray().apply{dao.allAudio().forEach{put(JSONObject().put("id",it.id).put("fileName",it.fileName).put("title",it.title).put("startedAt",it.startedAt).put("endedAt",it.endedAt).put("durationMs",it.durationMs))}})
            .put("preferences",JSONArray().apply{dao.allPreferences().forEach{put(JSONObject().put("key",it.key).put("value",it.value))}})
            .put("profileData",ProfileCodec.snapshot(dao))
            .put("draft",dao.draft()?.let{JSONObject().put("json",it.json).put("isNew",it.isNew)} ?: JSONObject.NULL)
    }
    suspend fun autoBackup(){try{gate.withLock{
        atomic(File(copies,"auto-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")){it.write(snapshot().toString(2).toByteArray())}
        copies.listFiles().orEmpty().filter{it.name.startsWith("auto-") && it.extension=="json"}.sortedByDescending{it.lastModified()}.drop(7).forEach{it.delete()}
        automaticStatus.value="Автокопия создана"
    }}catch(e:Exception){automaticStatus.value="Автокопия не создана: ${e.message}";throw e}}
    suspend fun export(output:OutputStream)=gate.withLock{writeZip(output)}
    private suspend fun writeZip(output:OutputStream){
        val data=snapshot()
        val protected=object:FilterOutputStream(output){override fun close(){flush()}}
        ZipOutputStream(BufferedOutputStream(protected)).use{z->
            fun text(name:String,value:String){z.putNextEntry(ZipEntry(name));z.write(value.toByteArray());z.closeEntry()}
            text("README.txt","ЗАПИСКИ ФЕЛЬДШЕРА\nTXT — для чтения, backup.json — для восстановления. Архив содержит вызовы, состав бригады, черновик и сохранённое аудио. Временный буфер не включён.\n")
            text("backup.json",data.toString(2))
            objects(data.getJSONArray("calls")).map{RecordCodec.decode(it.toString())}.groupBy{it.date}.forEach{(date,calls)->
                text("calls/$date.txt",calls.joinToString("\n\n────────────────\n\n"){r->buildString{
                    append("Дата: ${r.date}\n");r.time?.let{append("Время: $it\n")}
                    if(r.title.isNotBlank())append("Название:\n${r.title}\n")
                    if(r.description.isNotBlank())append("Заметка:\n${r.description}\n")
                    r.blocks.forEach{b->append("${b.kind.label}:\n");b.values.filterKeys { it != "references" }.forEach{(key,value)->
                        val label=VitalFields.toMap()[key] ?: mapOf("sex" to "Пол","age" to "Возраст","text" to "Текст")[key] ?: key
                        append("$label: $value\n")
                    }}
                    append("ID: ${r.id}\nСоздано: ${r.createdAt}\nИзменено: ${r.updatedAt}")
                }})
            }
            objects(data.getJSONArray("crew")).forEach{text("crew/${it.getString("date")}.txt","Водитель: ${it.getString("driver")}\nФельдшер 1: ${it.getString("first")}\nФельдшер 2: ${it.getString("second")}\n")}
            objects(data.getJSONArray("audio")).forEach{val name=safeName(it.getString("fileName"));val file=File(audioDir,name);check(file.isFile){"Не найден аудиофайл $name"}
                z.putNextEntry(ZipEntry("audio/$name"));file.inputStream().use{it.copyTo(z)};z.closeEntry()
            }
        }
    }
    /** Explicit merge: matching IDs replace; unrelated existing records remain. */
    suspend fun restore(input:InputStream,isZip:Boolean)=gate.withLock{
        val stage=File(context.cacheDir,"restore-${UUID.randomUUID()}").apply{mkdirs()}
        try{
            val json=if(isZip){
                var total=0L
                ZipInputStream(BufferedInputStream(input)).use{z->var entry=z.nextEntry
                    while(entry!=null){val name=entry.name;require(!name.contains("..") && !name.startsWith('/')){"Недопустимый путь в архиве"}
                        if(name=="backup.json" || name.startsWith("audio/")){
                            val target=File(stage,if(name=="backup.json")name else safeName(name.removePrefix("audio/")))
                            require(!target.exists()){"Повторяющийся файл"}
                            target.outputStream().use{out->val b=ByteArray(65536);while(true){val n=z.read(b);if(n<0)break;total+=n
                                require(total<8L*1024*1024*1024 && DiskSpace.available(stage)>32L*1024*1024){"Недостаточно места"};out.write(b,0,n)
                            }}
                        };z.closeEntry();entry=z.nextEntry
                    }
                }
                val file=File(stage,"backup.json");require(file.isFile && file.length()<=32*1024*1024);file.readText()
            }else input.use{limited(it,32*1024*1024).toString(Charsets.UTF_8)}
            val data=JSONObject(json);require(data.getString("format")=="feldsher-notes" && data.getInt("version")==1)
            val calls=objects(data.getJSONArray("calls")).map{RecordCodec.decode(it.toString()).also{r->require(validRecord(r))}}
            require(calls.map{it.id}.distinct().size==calls.size)
            val crews=objects(data.getJSONArray("crew")).map{CrewEntity(it.getString("date").also{date->LocalDate.parse(date)},it.getString("driver"),it.getString("first"),it.getString("second"))}
            val audio=objects(data.getJSONArray("audio")).map{a->
                val name=safeName(a.getString("fileName"));require(File(stage,name).isFile || File(audioDir,name).isFile){"Для восстановления аудио нужен полный ZIP-архив"}
                AudioEntity(a.getString("id"),name,a.getString("title"),a.getLong("startedAt"),a.getLong("endedAt"),a.getLong("durationMs").also{require(it>0)})
            }
            require(audio.map{it.id}.distinct().size==audio.size)
            val draft=data.optJSONObject("draft")?.let{DraftEntity(json=it.getString("json").also{raw->val r=RecordCodec.decode(raw);LocalDate.parse(r.date);r.time?.let{t->LocalTime.parse(t)}},isNew=it.getBoolean("isNew"))}
            val prefs=objects(data.getJSONArray("preferences")).map{p->PreferenceEntity(p.getString("key"),p.getString("value").also{v->if(p.getString("key")=="selectedDate")LocalDate.parse(v)})}
            atomic(File(copies,"before-restore-${System.currentTimeMillis()}.zip")){writeZip(it)}
            val imported=audio.map{a->val source=File(stage,a.fileName);if(source.isFile){
                val name="restored-${UUID.randomUUID()}.m4a";atomic(File(audioDir,name)){out->source.inputStream().use{it.copyTo(out)}};a.copy(fileName=name)
            }else a}
            db.withTransaction{calls.forEach{dao.upsertCall(CallEntity.from(it))};crews.forEach{dao.putCrew(it)};imported.forEach{dao.putAudio(it)};prefs.forEach{dao.putPreference(it)};if(draft!=null)dao.putDraft(draft);ProfileCodec.restore(dao,data.optJSONObject("profileData"))}
        }finally{stage.deleteRecursively()}
    }
    companion object{
        fun objects(a:JSONArray)=(0 until a.length()).map(a::getJSONObject)
        fun safeName(name:String):String{require(name.matches(Regex("[a-zA-Z0-9_.-]+\\.m4a")));return name}
        suspend fun atomic(file:File,write:suspend(OutputStream)->Unit){val part=File(file.path+".part")
            try{FileOutputStream(part).use{write(it);it.fd.sync()};check(part.renameTo(file))}catch(e:Exception){part.delete();throw e}
        }
        private fun limited(input:InputStream,max:Int):ByteArray{val out=ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=max);out.write(b,0,n)};return out.toByteArray()}
    }
}
