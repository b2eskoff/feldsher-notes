package ru.ainur.feldshernotes

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioServiceOnDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun action(name:String){context.startService(Intent().setClassName(context.packageName,"ru.ainur.feldshernotes.audio.MemoryService").setAction("memory.$name"))}
    private fun count():Int=SQLiteDatabase.openDatabase(context.getDatabasePath("feldsher-notes.db").path,null,SQLiteDatabase.OPEN_READONLY).use{db->db.rawQuery("SELECT COUNT(*) FROM audio",null).use{it.moveToFirst();it.getInt(0)}}
    private fun awaitSaved(expected:Int){repeat(60){if(count()>=expected)return;Thread.sleep(500)};fail("Save did not finish")}
    private fun runCapture(milliseconds:Long){
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.wakeUp()
        context.startActivity(Intent().setClassName(context.packageName,"ru.ainur.feldshernotes.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(2500)
        val before=count()
        context.startForegroundService(Intent().setClassName(context.packageName,"ru.ainur.feldshernotes.audio.MemoryService").setAction("memory.start"))
        try{
            Thread.sleep(5000);device.pressHome();device.sleep()
            Thread.sleep(milliseconds-6000)
            action("save");awaitSaved(before+1);Thread.sleep(5000);action("save");awaitSaved(before+2)
            SQLiteDatabase.openDatabase(context.getDatabasePath("feldsher-notes.db").path,null,SQLiteDatabase.OPEN_READONLY).use{db->
                db.rawQuery("SELECT fileName,durationMs,startedAt,endedAt FROM audio ORDER BY endedAt DESC LIMIT 2",null).use{c->
                    var latestStart=0L
                    while(c.moveToNext()){
                        assertTrue(c.getLong(1) in 1000L..900_100L)
                        if(milliseconds>=900_000L)assertTrue("Full buffer must contain approximately 15 minutes",c.getLong(1)>=899_000L)
                        println("Saved durationMs=${c.getLong(1)}, startedAt=${c.getLong(2)}, endedAt=${c.getLong(3)}")
                        if(latestStart==0L)latestStart=c.getLong(2) else assertTrue(latestStart<c.getLong(3))
                        val file=File(context.filesDir,"audio/${c.getString(0)}");assertTrue(file.length()>0)
                        val extractor=MediaExtractor();try{extractor.setDataSource(file.path);assertEquals(1,extractor.trackCount)
                            val format=extractor.getTrackFormat(0);assertEquals("audio/mp4a-latm",format.getString(MediaFormat.KEY_MIME));assertEquals(48000,format.getInteger(MediaFormat.KEY_SAMPLE_RATE));assertEquals(1,format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                            extractor.selectTrack(0);assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(8192),0)>0)
                        }finally{extractor.release()}
                    }
                }
            }
            assertTrue(File(context.noBackupFilesDir,"audio-ring").walkTopDown().count{it.isFile}<=34)
        }finally{action("stop");device.wakeUp()}
    }
    @Test fun backgroundRotationAndOverlappingM4aSaves()=runCapture(61_000)
    @Test fun thirtyOneMinutesWithScreenOff()=runCapture(31*60_000L)
}
