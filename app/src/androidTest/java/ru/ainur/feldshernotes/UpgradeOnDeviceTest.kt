package ru.ainur.feldshernotes

import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run seed against the installed 1.0.0, then install -r 1.1.0 and run verify. No app-internal APIs. */
@RunWith(AndroidJUnit4::class)
class UpgradeOnDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun launch(){
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());device.wakeUp();device.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(Intent().setClassName(context.packageName,"ru.ainur.feldshernotes.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));assertTrue("Journal did not become ready",device.wait(Until.hasObject(By.desc("Поиск вызовов")),60_000))
    }
    private fun database()=SQLiteDatabase.openDatabase(context.getDatabasePath("feldsher-notes.db").path,null,SQLiteDatabase.OPEN_READWRITE)
    @Test fun seedOldVersion(){launch();database().use{db->
        assertEquals(1,db.version)
        repeat(3){n->db.insertOrThrow("calls",null,ContentValues().apply{
            put("id","upgrade-$n");put("date",listOf("2026-08-04","2026-08-04","2026-09-10")[n])
            if(n==0)put("time","20:37") else putNull("time")
            put("title",if(n==2)"" else "Вызов $n");put("description","Старая заметка $n — сохранить полностью")
            put("blocksJson","[{\"id\":\"block-$n\",\"kind\":\"VITALS\",\"values\":{\"bp\":\"170/100\",\"spo2\":\"96\"}}]")
            put("createdAt",1000L+n);put("updatedAt",2000L+n)
        })}
    }}
    @Test fun verifyUpdatedVersion(){launch();database().use{db->
        assertEquals(2,db.version)
        repeat(3){n->db.rawQuery("SELECT * FROM calls WHERE id=?",arrayOf("upgrade-$n")).use{c->
            assertTrue(c.moveToFirst());assertEquals("Старая заметка $n — сохранить полностью",c.getString(c.getColumnIndexOrThrow("description")))
            assertEquals(listOf("2026-08-04","2026-08-04","2026-09-10")[n],c.getString(c.getColumnIndexOrThrow("date")))
            if(n==0)assertEquals("20:37",c.getString(c.getColumnIndexOrThrow("time"))) else assertTrue(c.isNull(c.getColumnIndexOrThrow("time")))
            assertEquals("170/100",org.json.JSONArray(c.getString(c.getColumnIndexOrThrow("blocksJson"))).getJSONObject(0).getJSONObject("values").getString("bp"))
            assertEquals(1000L+n,c.getLong(c.getColumnIndexOrThrow("createdAt")));assertEquals(2000L+n,c.getLong(c.getColumnIndexOrThrow("updatedAt")))
        }}
        db.rawQuery("SELECT * FROM crew WHERE date='2026-08-04'",null).use{assertFalse(it.moveToFirst())}
        db.execSQL("INSERT INTO crew VALUES ('2026-08-04','Иванов','Петров','Ахметьянов')")
        db.rawQuery("SELECT driver FROM crew WHERE date='2026-08-04'",null).use{assertTrue(it.moveToFirst());assertEquals("Иванов",it.getString(0))}
        db.rawQuery("SELECT COUNT(*) FROM calls WHERE description LIKE '%Старая заметка%'",null).use{it.moveToFirst();assertEquals(3,it.getInt(0))}
        val safety=File(context.noBackupFilesDir,"safety").listFiles().orEmpty().filter{it.extension=="sqlite"};assertTrue(safety.isNotEmpty())
        SQLiteDatabase.openDatabase(safety.last().path,null,SQLiteDatabase.OPEN_READONLY).use{old->assertEquals(1,old.version);old.rawQuery("SELECT COUNT(*) FROM calls",null).use{it.moveToFirst();assertEquals(3,it.getInt(0))}}
    }
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun node(selector:BySelector)=device.wait(Until.findObject(selector),60_000) ?: error("UI element not found: $selector")
        node(By.desc("Поиск вызовов")).click()
        node(By.clazz("android.widget.EditText")).text="Старая заметка 0"
        node(By.text("Вызов 0")).click();node(By.text("Редактировать")).click()
        node(By.text("Старая заметка 0 — сохранить полностью")).text="Отредактировано после обновления"
        node(By.text("Сохранить вызов")).click()
        node(By.text("Новый вызов")).click()
        val fields=device.wait(Until.findObjects(By.clazz("android.widget.EditText")),60_000)
        check(fields.isNotEmpty());fields.last().text="Новый вызов после обновления"
        node(By.text("Сохранить вызов")).click()
        node(By.text("Новый вызов после обновления"))
        database().use{db->db.rawQuery("SELECT COUNT(*) FROM calls",null).use{it.moveToFirst();assertEquals(4,it.getInt(0))}
            db.rawQuery("SELECT description FROM calls WHERE id='upgrade-0'",null).use{it.moveToFirst();assertEquals("Отредактировано после обновления",it.getString(0))}}
    }
}
