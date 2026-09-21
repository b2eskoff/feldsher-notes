package ru.ainur.feldshernotes.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.*
import java.io.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*
import java.time.LocalDate

/** Document metadata and bytes are encrypted together; ordinary notes backups never read this store. */
data class UserDocument(val id:String,val title:String,val category:String,val series:String="",val number:String="",val issued:String="",val expires:String="",val issuer:String="",val comment:String="",val mime:String="",val attachment:String="",val reminderDays:String="") {
    fun validate(){ require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")));require(title.isNotBlank());listOf(issued,expires).filter{it.isNotBlank()}.forEach{LocalDate.parse(it)}
        if(reminderDays.isNotBlank()){require(expires.isNotBlank());reminderDays.split(',').forEach{require(it.trim().toInt() in 0..3650)}}
        require(mime in listOf("","application/pdf","image/jpeg","image/png","image/webp"));require(attachment.length<=24*1024*1024)
    }
    fun json()=JSONObject().put("id",id).put("title",title).put("category",category).put("series",series).put("number",number).put("issued",issued).put("expires",expires).put("issuer",issuer).put("comment",comment).put("mime",mime).put("attachment",attachment).put("reminderDays",reminderDays)
    companion object { fun decode(j:JSONObject)=UserDocument(j.getString("id"),j.getString("title"),j.getString("category"),j.optString("series"),j.optString("number"),j.optString("issued"),j.optString("expires"),j.optString("issuer"),j.optString("comment"),j.optString("mime"),j.optString("attachment"),j.optString("reminderDays")).also{it.validate()} }
}
object DocumentArchive {
    private val magic="FNDOCS01".toByteArray()
    private fun key(password:CharArray,salt:ByteArray):SecretKeySpec {
        val spec=PBEKeySpec(password,salt,600_000,256)
        return try{SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")}finally{spec.clearPassword()}
    }
    fun seal(bytes:ByteArray,password:CharArray):ByteArray {
        require(password.size>=12){"Пароль должен содержать минимум 12 символов"};val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(password,salt));cipher.updateAAD(magic+salt)
        return magic+salt+cipher.iv+cipher.doFinal(bytes)
    }
    fun open(bytes:ByteArray,password:CharArray):ByteArray {
        require(bytes.size in 52..MAX_SIZE && bytes.take(8).toByteArray().contentEquals(magic)){"Неверный формат архива"}
        val salt=bytes.copyOfRange(8,24);val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,salt),GCMParameterSpec(128,bytes.copyOfRange(24,36)));cipher.updateAAD(magic+salt)
        return cipher.doFinal(bytes,36,bytes.size-36)
    }
    const val MAX_SIZE=64*1024*1024
}
/**
 * v1 remains readable after a single device-authenticated migration. Never overwrite the
 * original encrypted file: a failed migration must not destroy existing documents.
 */
class LegacyDocumentsRequireMigration:IllegalStateException("Для переноса старых документов один раз подтвердите доступ. До переноса старое хранилище останется нетронутым.")

class DocumentVault(context:Context) {
    private val legacyFile=AtomicFile(File(context.noBackupFilesDir,"documents/vault.enc").also{it.parentFile!!.mkdirs()})
    private val file=AtomicFile(File(context.noBackupFilesDir,"documents/vault.v2.enc").also{it.parentFile!!.mkdirs()})
    fun needsMigration():Boolean=legacyFile.baseFile.exists() && !file.baseFile.exists()
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey("feldsher-document-v2",null) as? SecretKey)?.let{return it}
        check(!file.baseFile.exists()){"Ключ документов недоступен. Старую резервную копию не удаляйте."}
        val builder=KeyGenParameterSpec.Builder("feldsher-document-v2",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(builder.build())}.generateKey()
    }
    private fun decrypt(input:AtomicFile,k:SecretKey):List<UserDocument> {
        val bytes=input.openRead().use{limited(it)}
        require(bytes.size>=28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,k,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return try{decode(cipher.doFinal(bytes,12,bytes.size-12))}finally{bytes.fill(0)}
    }
    @Synchronized fun migrateLegacy():List<UserDocument> {
        if(!needsMigration())return read()
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val original=(ks.getKey("feldsher-document-v1",null) as? SecretKey)
            ?: error("Старый ключ недоступен. Восстановите документы из резервной копии.")
        val restored=decrypt(legacyFile,original)
        // Commit v2 atomically, verify it, and leave the original v1 file intact.
        try {
            writeV2(restored)
            val verified=read()
            check(verified==restored){"Проверка переноса документов не прошла"}
            return verified
        } catch(e:Exception) {
            // Only the new file is discarded: the original v1 encrypted vault remains intact.
            file.baseFile.delete()
            throw e
        }
    }
    @Synchronized fun initialize(){if(!needsMigration())key()}
    @Synchronized fun read():List<UserDocument> {
        if(needsMigration())throw LegacyDocumentsRequireMigration()
        if(!file.baseFile.exists())return emptyList()
        return decrypt(file,key())
    }
    @Synchronized fun write(documents:List<UserDocument>) {
        if(needsMigration())throw LegacyDocumentsRequireMigration()
        writeV2(documents)
    }
    private fun writeV2(documents:List<UserDocument>) {
        require(documents.map{it.id}.distinct().size==documents.size);documents.forEach{it.validate()}
        val bytes=encode(documents);require(bytes.size<DocumentArchive.MAX_SIZE-1024){"Хранилище превышает 64 МБ"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val encrypted=cipher.iv+cipher.doFinal(bytes);bytes.fill(0)
        val out=file.startWrite();try{out.write(encrypted);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    @Synchronized fun save(doc:UserDocument){write(read().filterNot{it.id==doc.id}+doc)}
    @Synchronized fun delete(id:String){write(read().filterNot{it.id==id})}
    fun export(password:CharArray):ByteArray { val clear=encode(read());return try{DocumentArchive.seal(clear,password)}finally{clear.fill(0);password.fill('\u0000')} }
    @Synchronized fun restore(bytes:ByteArray,password:CharArray) {
        val clear=try{DocumentArchive.open(bytes,password)}finally{password.fill('\u0000')}
        try{val restored=decode(clear);val ids=restored.map{it.id}.toSet();write(read().filterNot{it.id in ids}+restored)}finally{clear.fill(0)}
    }
    companion object {
        fun encode(documents:List<UserDocument>)=JSONObject().put("format","feldsher-documents").put("version",1).put("documents",JSONArray().apply{documents.forEach{put(it.json())}}).toString().toByteArray()
        fun decode(bytes:ByteArray):List<UserDocument> { val j=JSONObject(bytes.toString(Charsets.UTF_8));require(j.getString("format")=="feldsher-documents" && j.getInt("version")==1);val a=j.getJSONArray("documents");require(a.length()<=200)
            return (0 until a.length()).map{UserDocument.decode(a.getJSONObject(it))}.also{require(it.map{d->d.id}.distinct().size==it.size)} }
        fun limited(input:InputStream):ByteArray {val out=ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=DocumentArchive.MAX_SIZE);out.write(b,0,n)};return out.toByteArray()}
    }
}
