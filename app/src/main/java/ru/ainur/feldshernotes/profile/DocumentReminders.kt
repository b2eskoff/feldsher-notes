package ru.ainur.feldshernotes.profile

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import ru.ainur.feldshernotes.MainActivity
import ru.ainur.feldshernotes.R
import java.time.*

object DocumentReminders {
    private fun preferences(context:Context)=context.getSharedPreferences("document_reminders",Context.MODE_PRIVATE)
    private fun intent(context:Context,key:String)=PendingIntent.getBroadcast(context,0,Intent(context,DocumentReminderReceiver::class.java).setAction("document-reminder").setData(Uri.parse("feldsher-reminder://local/$key")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context:Context,id:String) {
        val prefs=preferences(context);val edit=prefs.edit();val alarms=context.getSystemService(AlarmManager::class.java)
        prefs.all.keys.filter{it.startsWith("$id-")}.forEach{alarms.cancel(intent(context,it));edit.remove(it)};edit.apply()
    }
    fun schedule(context:Context,doc:UserDocument) {
        cancel(context,doc.id)
        if(doc.expires.isBlank() || doc.reminderDays.isBlank())return
        val expiry=LocalDate.parse(doc.expires);val editor=preferences(context).edit()
        doc.reminderDays.split(',').map{it.trim().toInt()}.distinct().forEach{days->
            val time=expiry.minusDays(days.toLong()).atTime(9,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if(time>System.currentTimeMillis()){val key="${doc.id}-$days";editor.putLong(key,time);context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,intent(context,key))}
        };editor.apply()
    }
    fun reboot(context:Context) { preferences(context).all.forEach{(key,value)->val stamp=value as? Long ?: return@forEach
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(stamp,System.currentTimeMillis()+60_000),intent(context,key))} }
    fun consume(context:Context,key:String):Boolean {val p=preferences(context);if(!p.contains(key))return false;p.edit().remove(key).apply();return true}
}
class DocumentReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED){DocumentReminders.reboot(context);return}
        val key=intent.data?.lastPathSegment ?: return;if(!DocumentReminders.consume(context,key))return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("documents","Сроки документов",NotificationManager.IMPORTANCE_DEFAULT).apply{lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).putExtra("documents",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(key.hashCode(),NotificationCompat.Builder(context,"documents").setSmallIcon(R.drawable.ic_cross_monochrome).setContentTitle("Записки фельдшера").setContentText("Проверьте срок действия документа в защищённом разделе").setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build())
    }
}
