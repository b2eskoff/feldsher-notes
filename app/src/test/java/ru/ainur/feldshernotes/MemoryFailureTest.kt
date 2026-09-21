package ru.ainur.feldshernotes

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.ainur.feldshernotes.audio.MemoryService
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MemoryFailureTest {
    @Test fun failedBufferDirectoryStopsServiceAndNotification()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val blocker=File(context.noBackupFilesDir,"audio-ring")
        blocker.deleteRecursively();blocker.writeText("Cannot create directory below this file")
        val controller=Robolectric.buildService(MemoryService::class.java).create()
        val service=controller.get()
        try {
            service.onStartCommand(Intent(context,MemoryService::class.java).setAction(MemoryService.START),0,1)
            withTimeout(5000){while(!shadowOf(service).isStoppedBySelf)delay(10)}
            assertFalse(MemoryService.status.value.running)
            assertTrue(MemoryService.status.value.error.orEmpty().contains("временные файлы"))
            assertTrue(shadowOf(service).isForegroundStopped)
        } finally { controller.destroy();blocker.delete() }
    }
}
