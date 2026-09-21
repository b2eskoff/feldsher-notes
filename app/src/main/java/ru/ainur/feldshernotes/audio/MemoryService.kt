package ru.ainur.feldshernotes.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import ru.ainur.feldshernotes.*
import ru.ainur.feldshernotes.data.AudioEntity
import ru.ainur.feldshernotes.data.DiskSpace
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class MemoryStatus(val running:Boolean=false,val availableMs:Long=0,val saving:Boolean=false,val heartbeat:Long=0,val error:String?=null)
class MemoryService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val alive=AtomicBoolean(false)
    private val saving=AtomicBoolean(false)
    @Volatile private var recorder:AudioRecord?=null
    @Volatile private var ring:PacketRing?=null
    @Volatile private var format:MediaFormat?=null
    private var captureJob:Job?=null
    private var startedAt=0L
    private var session:File?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action){
            SAVE->savePast()
            STOP->{alive.set(false);recorder?.let{runCatching{it.stop()}};if(captureJob?.isActive!=true && !saving.get())stopSelf()}
            START->if(captureJob?.isActive!=true){
                if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY}
                try{
                    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Аудиобуфер",NotificationManager.IMPORTANCE_LOW))
                    ServiceCompat.startForeground(this,71,notification("Запуск буфера…"),if(Build.VERSION.SDK_INT>=30)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                    alive.set(true);status.value=MemoryStatus();captureJob=scope.launch{capture()}
                }catch(e:Exception){status.value=MemoryStatus(error="Не удалось запустить микрофон: ${e.localizedMessage}");stopSelf()}
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).putExtra("memory",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name:String,code:Int)=PendingIntent.getService(this,code,Intent(this,MemoryService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_cross_monochrome).setContentTitle("Память · Записки фельдшера")
            .setContentText(text).setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0,"Сохранить 15 мин",action(SAVE,1)).addAction(0,"Остановить",action(STOP,2)).build()
    }
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun capture(){
        var codec:MediaCodec?=null;var wake:PowerManager.WakeLock?=null
        val folder=File(noBackupFilesDir,"audio-ring/${UUID.randomUUID()}");session=folder
        var cleanupRing:PacketRing?=null;format=null
        var failure:String?=null
        try{
            val packets=PacketRing(folder);cleanupRing=packets;ring=packets
            check(DiskSpace.available(folder)>64L*1024*1024){"Недостаточно свободного места для буфера"}
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"feldsher:memory").apply{acquire(24*60*60*1000L)}
            val audio=AudioRecord(MediaRecorder.AudioSource.MIC,48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                maxOf(19200,AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)))
            recorder=audio;check(audio.state==AudioRecord.STATE_INITIALIZED){"Микрофон недоступен"}
            val encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);codec=encoder
            encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,48000,1).apply{
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);setInteger(MediaFormat.KEY_BIT_RATE,128000);setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            audio.startRecording();check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            startedAt=System.currentTimeMillis();var frames=0L;var tick=0L
            val pcm=ByteArray(2048);val info=MediaCodec.BufferInfo()
            while(alive.get()){
                val count=audio.read(pcm,0,pcm.size,AudioRecord.READ_BLOCKING);if(!alive.get())break
                check(count>0){"Запись микрофона прервана"}
                val index=encoder.dequeueInputBuffer(100_000);check(index>=0){"Аудиокодек перестал принимать звук"}
                encoder.getInputBuffer(index)!!.apply{clear();put(pcm,0,count)}
                encoder.queueInputBuffer(index,0,count,frames*1_000_000/48000,0);frames+=count/2
                while(true){val out=encoder.dequeueOutputBuffer(info,0)
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){format=encoder.outputFormat;continue}
                    if(out<0)break
                    try{if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){
                        val b=encoder.getOutputBuffer(out)!!;b.position(info.offset);b.limit(info.offset+info.size)
                        val bytes=ByteArray(info.size);b.get(bytes);packets.append(info.presentationTimeUs,bytes)
                    }}finally{encoder.releaseOutputBuffer(out,false)}
                }
                val now=SystemClock.elapsedRealtime()
                if(now-tick>=1000){tick=now
                    check(DiskSpace.available(folder)>48L*1024*1024){"Буфер остановлен: мало свободного места"}
                    if(Build.VERSION.SDK_INT>=29)check(audio.activeRecordingConfiguration?.isClientSilenced!=true){"Android приостановил доступ к микрофону"}
                    status.value=MemoryStatus(true,packets.durationMs(),saving.get(),now)
                    getSystemService(NotificationManager::class.java).notify(71,notification("Доступно ${duration(packets.durationMs())}"))
                }
            }
        }catch(e:Exception){failure=e.localizedMessage ?: "Запись прервана"}
        finally{
            alive.set(false);recorder?.let{runCatching{it.stop()};runCatching{it.release()}};recorder=null
            codec?.let{runCatching{it.stop()};runCatching{it.release()}}
            runCatching{cleanupRing?.close()}
            runCatching{wake?.let{if(it.isHeld)it.release()}}
            status.value=MemoryStatus(saving=saving.get(),error=failure)
            if(!saving.get())folder.deleteRecursively()
            if(!saving.get()){ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);stopSelf()}
            else getSystemService(NotificationManager::class.java).notify(71,notification("Сохраняем фрагмент…"))
        }
    }
    private fun savePast(){
        if(!alive.get() || !saving.compareAndSet(false,true))return
        val source=ring;val mediaFormat=format;val origin=startedAt;val folder=session
        val snapshot=try{check(source!=null && mediaFormat!=null);source.snapshot()}catch(_:Exception){saving.set(false);events.value="Буфер ещё наполняется";return}
        status.value=status.value.copy(saving=true)
        scope.launch{
            val app=application as NotesApplication;val id=UUID.randomUUID().toString()
            val target=File(app.backups.audioDir,"${DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault()).format(Instant.now())}-$id.m4a")
            val part=File(target.path+".part");var muxer:MediaMuxer?=null
            try{
                check(DiskSpace.available(part.parentFile!!)>32L*1024*1024){"Недостаточно места для сохранения"}
                val writer=MediaMuxer(part.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
                val track=writer.addTrack(mediaFormat!!);writer.start();var first=-1L;var last=0L
                source!!.read(snapshot){pts,bytes->if(first<0)first=pts;last=pts
                    writer.writeSampleData(track,ByteBuffer.wrap(bytes),MediaCodec.BufferInfo().apply{set(0,bytes.size,pts-first,0)})
                }
                check(first>=0);writer.stop();writer.release();muxer=null
                java.io.RandomAccessFile(part,"rw").use{it.fd.sync()};check(part.renameTo(target))
                val start=origin+first/1000;val end=origin+last/1000+21
                app.backups.gate.withLock{app.database.notes().putAudio(AudioEntity(id,target.name,"",start,end,end-start))}
                runCatching{app.backups.autoBackup()}
                val clock=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                events.value="Сохранено ${clock.format(Instant.ofEpochMilli(start))}–${clock.format(Instant.ofEpochMilli(end))}"
            }catch(e:Exception){events.value="Не удалось сохранить: ${e.localizedMessage}";part.delete()}
            finally{muxer?.let{runCatching{it.release()}};source!!.release(snapshot);saving.set(false);status.value=status.value.copy(saving=false)
                if(!alive.get()){folder?.deleteRecursively();ServiceCompat.stopForeground(this@MemoryService,ServiceCompat.STOP_FOREGROUND_REMOVE);stopSelf()}
            }
        }
    }
    override fun onDestroy(){alive.set(false);recorder?.let{runCatching{it.stop()}};status.value=status.value.copy(running=false);super.onDestroy()}
    companion object{
        const val START="memory.start";const val SAVE="memory.save";const val STOP="memory.stop";private const val CHANNEL="memory"
        val status=MutableStateFlow(MemoryStatus());val events=MutableStateFlow<String?>(null)
        fun duration(ms:Long)="%02d:%02d".format(ms/60000,(ms/1000)%60)
    }
}
