package ru.ainur.feldshernotes.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/** Called before any Room connection in the app's single process. */
object MigrationSafety {
    @Synchronized fun beforeOpen(context: Context) {
        val source=context.getDatabasePath("feldsher-notes.db")
        if(!source.exists())return
        val db=SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE)
        try {
            if(db.version>=3)return
            check(db.version in 1..2){"Неизвестная версия базы"}
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { check(it.moveToFirst() && it.getInt(0)==0){"База занята"} }
        }finally{db.close()}
        val dir=File(context.noBackupFilesDir,"safety").apply{mkdirs()}
        val part=File(dir,"before-v3-${System.currentTimeMillis()}.sqlite.part")
        try {
            source.inputStream().use { input->FileOutputStream(part).use{out->input.copyTo(out);out.fd.sync()} }
            SQLiteDatabase.openDatabase(part.path,null,SQLiteDatabase.OPEN_READONLY).use { copy->
                check(copy.version in 1..2)
                copy.rawQuery("PRAGMA integrity_check",null).use{check(it.moveToFirst() && it.getString(0)=="ok")}
            }
            check(part.renameTo(File(dir,part.name.removeSuffix(".part"))))
        }catch(e:Exception){part.delete();throw IllegalStateException("Защитная копия не создана. Исходная база сохранена.",e)}
    }
}
