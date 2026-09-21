package ru.ainur.feldshernotes.profile

import androidx.room.*
import ru.ainur.feldshernotes.data.CallRecord
import java.time.*
import java.time.temporal.ChronoUnit

@Entity(tableName="work_shifts", indices=[Index(value=["start","end"],unique=true)])
data class WorkShift(@PrimaryKey val id:String, val start:String, val end:String, val night:Boolean, val completed:Boolean, val confirmed:Boolean) {
    val startTime get()=LocalDateTime.parse(start)
    val endTime get()=LocalDateTime.parse(end)
    fun validate() { require(id.isNotBlank());require(endTime>startTime);require(Duration.between(startTime,endTime).toHours()<=48) }
}
@Entity(tableName="call_shift_links")
data class CallShiftLink(@PrimaryKey val callId:String,val shiftId:String)
@Entity(tableName="achievement_unlocks")
data class AchievementUnlock(@PrimaryKey val id:String,val unlockedAt:Long)

data class UserProfile(val name:String="",val surname:String="",val patronymic:String="",val role:String="Фельдшер",val workplace:String="",val education:String="",val started:String="",val avatar:String="")
data class LevelProgress(val level:Int,val total:Long,val intoLevel:Long,val next:Long) {
    val fraction get()=if(level==100)1f else (intoLevel.toFloat()/next).coerceIn(0f,1f)
}
data class Medal(val id:String,val title:String,val category:String,val threshold:Int,val unit:String)
data class Statistics(val calls:Int,val shifts:Int,val nights:Int,val assigned:Int,val maximum:Int?,val xp:Long) {
    val average get()=if(shifts==0)null else assigned.toDouble()/shifts
}
object ProfileMath {
    fun level(xp:Long):LevelProgress {
        val total=xp.coerceAtLeast(0);var remaining=total;var level=1
        while(level<100) { val need=100L+10*((level-1)/2);if(remaining<need)return LevelProgress(level,total,remaining,need);remaining-=need;level++ }
        return LevelProgress(100,total,remaining,0)
    }
    fun completed(shifts:List<WorkShift>,now:LocalDateTime)=shifts.filter{(it.completed || it.confirmed) && it.endTime<=now}.distinctBy{it.start to it.end}.sortedBy{it.start}
    fun candidates(call:CallRecord,shifts:List<WorkShift>):List<WorkShift> {
        val date=runCatching{LocalDate.parse(call.date)}.getOrNull() ?: return emptyList()
        val time=call.time?.let{runCatching{LocalTime.parse(it)}.getOrNull()}
        return shifts.filter { s -> if(time!=null) { val stamp=date.atTime(time);stamp>=s.startTime && stamp<s.endTime }
            else date.atStartOfDay()<s.endTime && date.plusDays(1).atStartOfDay()>s.startTime }
    }
    fun assignments(calls:List<CallRecord>,shifts:List<WorkShift>,links:List<CallShiftLink>):Map<String,String> {
        val manual=links.associate{it.callId to it.shiftId};val ids=shifts.map{it.id}.toSet()
        return calls.mapNotNull { c ->
            if(manual.containsKey(c.id)) manual[c.id]?.takeIf{it in ids}?.let{c.id to it}
            else candidates(c,shifts).singleOrNull()?.let{c.id to it.id}
        }.toMap()
    }
    fun series(calls:List<CallRecord>,shifts:List<WorkShift>,links:List<CallShiftLink>,now:LocalDateTime):Pair<Int,Int> {
        val assigned=assignments(calls,shifts,links).values.toSet();var current=0;var best=0
        completed(shifts,now).forEach { if(it.confirmed || it.id in assigned) { current++;best=maxOf(best,current) } else current=0 }
        return current to best
    }
    fun statistics(calls:List<CallRecord>,shifts:List<WorkShift>,links:List<CallShiftLink>,now:LocalDateTime,from:LocalDate?=null,until:LocalDate?=null):Statistics {
        fun inside(date:String)=runCatching { val d=LocalDate.parse(date);(from==null || d>=from) && (until==null || d<until) }.getOrDefault(false)
        val cs=calls.filter{inside(it.date)};val ss=completed(shifts,now).filter{inside(it.startTime.toLocalDate().toString())}
        val assignments=assignments(calls,shifts,links);val counts=assignments.values.groupingBy{it}.eachCount()
        // Shift metrics include the whole shift, even when it crosses a month boundary.
        val assigned=ss.sumOf{counts[it.id] ?: 0}
        return Statistics(cs.size,ss.size,ss.count{it.night},assigned,ss.maxOfOrNull{counts[it.id] ?: 0},cs.size*2L+ss.size*10L)
    }
    fun firstDate(calls:List<CallRecord>,shifts:List<WorkShift>)=(calls.map{it.date}+shifts.map{it.start.take(10)}).mapNotNull{runCatching{LocalDate.parse(it)}.getOrNull()}.minOrNull()
    val medals:List<Medal> = buildList {
        fun group(key:String,title:String,unit:String,thresholds:List<Int>) { thresholds.forEach{add(Medal("$key:$it",title,key,it,unit))} }
        group("calls","На линии","вызовов",listOf(1,10,50,100,250,500,1000,2500,5000,10000,25000))
        group("shifts","В ритме смен","смен",listOf(1,10,25,50,100,250,500,1000))
        group("nights","Ночная жизнь","ночных смен",listOf(1,10,25,50,100,250,500))
        group("series","Верность записям","смен подряд",listOf(3,10,25,50,100))
        group("months","Вместе с Записками","месяцев",listOf(1,3,6,12,24,36,60,120))
    }
    fun progress(calls:List<CallRecord>,shifts:List<WorkShift>,links:List<CallShiftLink>,now:LocalDateTime,usageSince:LocalDate):Map<String,Int> {
        val s=statistics(calls,shifts,links,now)
        return mapOf("calls" to s.calls,"shifts" to s.shifts,"nights" to s.nights,"series" to series(calls,shifts,links,now).second,"months" to ChronoUnit.MONTHS.between(usageSince,now.toLocalDate()).toInt().coerceAtLeast(0))
    }
    fun rank(category:String,value:Int):String {
        val limits=when(category){"calls"->listOf(50,100,500,1000,5000);"nights"->listOf(10,25,50,100,500);"series"->listOf(3,10,25,50,100);"months"->listOf(1,3,6,12,24);else->listOf(10,25,50,100,500)}
        val i=limits.indexOfLast{value>=it};return if(i<0)"Начало пути" else listOf("Бронза","Серебро","Золото","Платина","Особый ранг")[i]
    }
}
