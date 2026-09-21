package ru.ainur.feldshernotes

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.ainur.feldshernotes.data.*
import ru.ainur.feldshernotes.ui.*

class NotesApplication : Application() {
    val operationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
    val maintenance = kotlinx.coroutines.flow.MutableStateFlow(false)
    val operationMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val database by lazy { NotesDatabase.open(this) }
    val backups by lazy { BackupStore(this,database) }
    val profile by lazy { ru.ainur.feldshernotes.profile.ProfileStore(database,backups,operationScope) }
    val repository by lazy { NotesRepository(database.notes()) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { backups.autoBackup() }; Unit } } }
    override fun onCreate() { super.onCreate();java.io.File(noBackupFilesDir,"audio-ring").deleteRecursively();noBackupFilesDir.listFiles()?.filter{it.name.startsWith("document-preview-")}?.forEach{it.delete()} }
}
class MainActivity : ComponentActivity() {
    private val model: NotesViewModel by viewModels { viewModelFactory { initializer {
        NotesViewModel((application as NotesApplication).repository,createSavedStateHandle())
    } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        runCatching { (application as NotesApplication).profile }
        val loaded=runCatching { model }
        if(intent.getBooleanExtra("documents",false))loaded.getOrNull()?.openSection(Screen.PROFILE)
        if(intent.getBooleanExtra("memory",false))loaded.getOrNull()?.openSection(Screen.MEMORY)
        setContent { NotesTheme {
            loaded.getOrNull()?.let { NotesApp(it) } ?: androidx.compose.material3.AlertDialog(
                onDismissRequest={},title={androidx.compose.material3.Text("Журнал не открыт")},
                text={androidx.compose.material3.Text("Не удалось подготовить защитную копию базы. Исходные данные сохранены. Проверьте свободное место и повторите попытку.")},
                confirmButton={androidx.compose.material3.TextButton({recreate()}){androidx.compose.material3.Text("Повторить")}})
        } }
    }
}
