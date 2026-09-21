package ru.ainur.feldshernotes.profile

import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.ainur.feldshernotes.data.*
import org.json.*
import java.time.*
import java.util.UUID

object ProfileCodec {
    fun encode(p:UserProfile)=JSONObject().put("name",p.name).put("surname",p.surname).put("patronymic",p.patronymic).put("role",p.role).put("workplace",p.workplace).put("education",p.education).put("started",p.started).put("avatar",p.avatar).toString()
    fun decode(raw:String?)=if(raw==null)UserProfile() else JSONObject(raw).let{UserProfile(it.optString("name"),it.optString("surname"),it.optString("patronymic"),it.optString("role","Фельдшер"),it.optString("workplace"),it.optString("education"),it.optString("started"),it.optString("avatar"))}
    suspend fun snapshot(dao:NotesDao)=JSONObject()
        .put("shifts",JSONArray().apply{dao.allShifts().forEach{put(JSONObject().put("id",it.id).put("start",it.start).put("end",it.end).put("night",it.night).put("completed",it.completed).put("confirmed",it.confirmed))}})
        .put("links",JSONArray().apply{dao.allLinks().forEach{put(JSONObject().put("callId",it.callId).put("shiftId",it.shiftId))}})
        .put("unlocks",JSONArray().apply{dao.allUnlocks().forEach{put(JSONObject().put("id",it.id).put("unlockedAt",it.unlockedAt))}})
    suspend fun restore(dao:NotesDao,data:JSONObject?) {
        if(data==null)return
        val existing=dao.allShifts().associateBy{it.start to it.end}.toMutableMap();val remap=mutableMapOf<String,String>()
        BackupStore.objects(data.getJSONArray("shifts")).forEach {
            val s=WorkShift(it.getString("id"),it.getString("start"),it.getString("end"),it.getBoolean("night"),it.getBoolean("completed"),it.getBoolean("confirmed"));s.validate()
            val old=existing[s.start to s.end];val merged=s.copy(id=old?.id ?: s.id);remap[s.id]=merged.id;dao.putShift(merged);existing[s.start to s.end]=merged
        }
        val ids=dao.allShifts().map{it.id}.toSet()
        BackupStore.objects(data.getJSONArray("links")).forEach{val call=it.getString("callId");val shift=it.getString("shiftId");val id=remap[shift] ?: shift
            if(dao.getCall(call)!=null && (id in ids || id.isEmpty()))dao.putLink(CallShiftLink(call,id))}
        BackupStore.objects(data.getJSONArray("unlocks")).forEach{val stamp=it.getLong("unlockedAt");require(stamp>0);dao.unlock(AchievementUnlock(it.getString("id"),stamp))}
    }
}
data class ProfileData(val calls:List<CallRecord> = emptyList(),val shifts:List<WorkShift> = emptyList(),val links:List<CallShiftLink> = emptyList(),val unlocks:List<AchievementUnlock> = emptyList(),val profile:UserProfile=UserProfile(),val ready:Boolean=false)
class ProfileStore(private val db:NotesDatabase,private val backups:BackupStore,scope:CoroutineScope) {
    private val dao=db.notes()
    val data=combine(dao.observeCalls(),dao.observeShifts(),dao.observeLinks(),dao.observeUnlocks(),dao.observePreference("userProfile")){calls,shifts,links,unlocks,profile->
        ProfileData(calls.map{it.record()},shifts,links,unlocks,ProfileCodec.decode(profile),true)
    }.stateIn(scope,SharingStarted.Eagerly,ProfileData())
    val error=MutableStateFlow<String?>(null)
    init { scope.launch(Dispatchers.IO) {
        try {
            val firstUsage=dao.preference("profileUsageSince") ?: dao.allCalls().minOfOrNull{it.createdAt}?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()} ?: LocalDate.now().toString()
            dao.putPreference(PreferenceEntity("profileUsageSince",firstUsage))
            data.filter{it.ready}.collect { d ->
                val progress=ProfileMath.progress(d.calls,d.shifts,d.links,LocalDateTime.now(),LocalDate.parse(dao.preference("profileUsageSince")!!))
                val ids=d.unlocks.map{it.id}.toSet();var changed=false
                db.withTransaction { ProfileMath.medals.filter{it.id !in ids && progress.getValue(it.category)>=it.threshold}.forEach { dao.unlock(AchievementUnlock(it.id,System.currentTimeMillis()));changed=true } }
                if(changed)backups.autoBackup()
            }
        }catch(e:Exception){error.value="Не удалось обновить достижения: ${e.message}"}
    } }
    suspend fun saveProfile(p:UserProfile) { if(p.started.isNotBlank())require(LocalDate.parse(p.started)<=LocalDate.now());dao.putPreference(PreferenceEntity("userProfile",ProfileCodec.encode(p)));backups.autoBackup() }
    suspend fun saveShift(s:WorkShift) { s.validate();require(!s.completed || s.endTime<=LocalDateTime.now()){"Нельзя завершить будущую смену"}
        db.withTransaction{val duplicate=dao.allShifts().firstOrNull{it.start==s.start && it.end==s.end};dao.putShift(s.copy(id=duplicate?.id ?: s.id))};backups.autoBackup() }
    suspend fun deleteShift(id:String) { dao.deleteShift(id);backups.autoBackup() }
    suspend fun link(callId:String,shiftId:String) { require(dao.getCall(callId)!=null);require(shiftId.isEmpty() || dao.allShifts().any{it.id==shiftId});dao.putLink(CallShiftLink(callId,shiftId));backups.autoBackup() }
}
