package ru.ainur.feldshernotes.ui

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ainur.feldshernotes.profile.DocumentReminders
import ru.ainur.feldshernotes.profile.DocumentVault
import ru.ainur.feldshernotes.profile.UserDocument
import ru.ainur.feldshernotes.profile.LegacyDocumentsRequireMigration
import java.io.File
import java.util.UUID

@Composable fun DocumentsScreen() {
    val context = LocalContext.current
    val vault = remember { DocumentVault(context) }
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(emptyList<UserDocument>()) }
    var migrationNeeded by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<UserDocument?>(null) }
    var preview by remember { mutableStateOf<UserDocument?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var archiveMode by remember { mutableStateOf<String?>(null) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingMode by remember { mutableStateOf<String?>(null) }
    var pendingDoc by remember { mutableStateOf<UserDocument?>(null) }

    fun operate(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                migrationNeeded = withContext(Dispatchers.IO) { vault.needsMigration() }
                if (!migrationNeeded) docs = withContext(Dispatchers.IO) { vault.read() }
                if (message?.startsWith("Не удалось") == true) message = null
            } catch (e: Exception) {
                migrationNeeded = e is LegacyDocumentsRequireMigration || vault.needsMigration()
                message = if (e is javax.crypto.AEADBadTagException) "Неверный пароль или повреждённый архив" else e.message ?: "Не удалось обработать документы"
            } finally {
                ready = true
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        operate { vault.initialize() }
    }

    // Authentication is requested only once when the user has documents encrypted with the
    // old user-authenticated AndroidKeyStore key. New documents never ask for verification.
    val credentials = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) operate { vault.migrateLegacy() }
    }
    fun migrateOldDocuments() {
        try {
            val manager = context.getSystemService(KeyguardManager::class.java)
            require(manager.isDeviceSecure) { "На этом устройстве нет настроенной блокировки экрана" }
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricPrompt.Builder(context)
                    .setTitle("Перенос документов")
                    .setSubtitle("Одно подтверждение для старых документов; дальше раздел откроется без пароля")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                    .authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            operate { vault.migrateLegacy() }
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            message = errString.toString()
                        }
                    })
            } else {
                @Suppress("DEPRECATION")
                val intent = manager.createConfirmDeviceCredentialIntent("Перенос документов", "Однократное подтверждение")
                credentials.launch(intent)
            }
        } catch (e: Exception) { message = e.message }
    }

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingMode = "attach"
            message = "Файл выбран. Нажми «Продолжить с выбранным файлом»."
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingMode = "export"
            archiveMode = "export"
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingMode = "restore"
            archiveMode = "restore"
        }
    }

    if (ready && migrationNeeded) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(23.dp), color = Color.White) {
                Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Перенос старых документов", style = MaterialTheme.typography.titleLarge)
                    Text("На телефоне обнаружены документы из прежней версии. Чтобы отключить старую проверку доступа и сохранить файлы, один раз подтвердите доступ. Исходный архив на устройстве останется нетронутым.", color = Muted)
                    Button(onClick = { migrateOldDocuments() }, enabled = !busy) { Text("Перенести документы") }
                    Text("Если не хотите переносить сейчас, вернитесь назад. Без переноса старые файлы будут недоступны в новой версии, но не удалятся.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (message != null) Text(message!!, color = Accent)
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(shape = RoundedCornerShape(23.dp), color = Color.White) {
                Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Документы", style = MaterialTheme.typography.titleLarge)
                    Text("Теперь раздел работает без дополнительной верификации. Скриншоты разрешены. Документы по-прежнему хранятся локально на устройстве и не попадают в обычный бэкап вызовов.", color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { edit = UserDocument(UUID.randomUUID().toString(), "", "Другое") }, enabled = !busy) { Text("Добавить") }
                        OutlinedButton(onClick = { export.launch("documents-${java.time.LocalDate.now()}.fndoc") }, enabled = !busy) { Text("Бэкап") }
                        OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Восстановить") }
                    }
                    Text("Внутри раздела можно хранить данные документа, фото, PDF и сроки действия. Защищённый архив по-прежнему создаётся с отдельным паролем.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (message != null) {
            item { Text(message!!, color = Accent) }
        }
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (pendingUri != null) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (pendingMode == "attach") "Файл готов к прикреплению" else "Файл выбран", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (pendingMode) {
                                "attach" -> "Нажми кнопку ниже, чтобы прикрепить файл к документу."
                                "export" -> "Осталось ввести пароль для защищённой резервной копии."
                                else -> "Осталось ввести пароль архива для восстановления."
                            },
                            color = Muted
                        )
                        Button(onClick = {
                            val uri = pendingUri!!
                            if (pendingMode == "attach") operate {
                                val bytes = context.contentResolver.openInputStream(uri)!!.use { DocumentVault.limited(it) }
                                val type = context.contentResolver.getType(uri).orEmpty()
                                require(type in listOf("application/pdf", "image/jpeg", "image/png", "image/webp")) { "Поддерживаются PDF, JPEG, PNG и WebP" }
                                val doc = pendingDoc ?: error("Повторно выберите документ")
                                scope.launch {
                                    edit = doc.copy(mime = type, attachment = Base64.encodeToString(bytes, Base64.NO_WRAP))
                                    pendingUri = null
                                    pendingDoc = null
                                    pendingMode = null
                                    message = null
                                }
                            } else {
                                archiveMode = pendingMode
                            }
                        }, enabled = !busy) { Text("Продолжить с выбранным файлом") }
                    }
                }
            }
        }
        if (docs.isEmpty() && !busy) {
            item {
                Surface(shape = RoundedCornerShape(23.dp), color = Color.White) {
                    Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
                        Text("Добавь сюда аккредитацию, дипломы, рабочие документы или любые нужные файлы.", color = Muted)
                    }
                }
            }
        }
        items(docs, key = { it.id }) { doc ->
            Surface(shape = RoundedCornerShape(23.dp), color = Color.White) {
                Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(doc.title, style = MaterialTheme.typography.titleMedium)
                            Text(doc.category, color = Muted)
                        }
                        if (doc.attachment.isNotEmpty()) SmallTag("Файл")
                    }
                    val lines = buildList {
                        if (doc.series.isNotBlank() || doc.number.isNotBlank()) add(listOf(doc.series, doc.number).filter { it.isNotBlank() }.joinToString(" · "))
                        if (doc.issued.isNotBlank()) add("Выдан ${profileDate(doc.issued)}")
                        if (doc.expires.isNotBlank()) add("Действует до ${profileDate(doc.expires)}")
                        if (doc.issuer.isNotBlank()) add(doc.issuer)
                    }
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (doc.comment.isNotBlank()) Text(doc.comment, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { edit = doc }, enabled = !busy) { Text("Редактировать") }
                        if (doc.attachment.isNotEmpty()) TextButton(onClick = { preview = doc }, enabled = !busy) { Text("Открыть файл") }
                    }
                }
            }
        }
    }

    if (edit != null) DocumentEditor(
        initial = edit!!,
        dismiss = { edit = null },
        save = { doc -> operate { vault.save(doc); DocumentReminders.schedule(context, doc) }; edit = null },
        attach = { doc -> pendingDoc = doc; attach.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")) },
        delete = { id -> operate { vault.delete(id); DocumentReminders.cancel(context, id) }; edit = null }
    )
    if (preview != null) DocumentPreview(preview!!) { preview = null }

    if (archiveMode != null) AlertDialog(
        onDismissRequest = { archiveMode = null; password = ""; repeatPassword = "" },
        title = { Text(if (archiveMode == "export") "Пароль резервной копии" else "Восстановление документов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Не менее 12 символов. Этот пароль понадобится для защищённого архива документов.")
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation())
                if (archiveMode == "export") OutlinedTextField(repeatPassword, { repeatPassword = it }, label = { Text("Повторите пароль") }, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton({
                val uri = pendingUri ?: return@TextButton
                val mode = archiveMode
                val secret = password.toCharArray()
                password = ""
                repeatPassword = ""
                archiveMode = null
                operate {
                    try {
                        if (mode == "export") {
                            val bytes = vault.export(secret)
                            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes); it.flush() }
                        } else {
                            val bytes = context.contentResolver.openInputStream(uri)!!.use { DocumentVault.limited(it) }
                            vault.restore(bytes, secret)
                            vault.read().forEach { DocumentReminders.schedule(context, it) }
                        }
                        scope.launch {
                            pendingUri = null
                            pendingMode = null
                            message = if (mode == "export") "Защищённая копия создана" else "Документы восстановлены"
                        }
                    } finally {
                        secret.fill('\u0000')
                    }
                }
            }, enabled = password.length >= 12 && (archiveMode != "export" || password == repeatPassword)) { Text("Продолжить") }
        },
        dismissButton = { TextButton({ archiveMode = null; password = ""; repeatPassword = "" }) { Text("Отмена") } }
    )
}

@Composable private fun DocumentEditor(initial: UserDocument, dismiss: () -> Unit, save: (UserDocument) -> Unit, attach: (UserDocument) -> Unit, delete: (String) -> Unit) {
    var doc by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var issuedText by remember(initial) { mutableStateOf(if (initial.issued.isBlank()) "" else profileDate(initial.issued)) }
    var expiresText by remember(initial) { mutableStateOf(if (initial.expires.isBlank()) "" else profileDate(initial.expires)) }
    val categories = listOf("Образование", "Аккредитация", "Удостоверения и обучение", "Рабочие документы", "Другое")
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    AlertDialog(onDismissRequest = dismiss, title = { Text("Документ") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Название", doc.title) { doc = doc.copy(title = it) }
            categories.forEach { c ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(doc.category == c, { doc = doc.copy(category = c) })
                    Text(c)
                }
            }
            Field("Серия (необязательно)", doc.series) { doc = doc.copy(series = it) }
            Field("Номер (необязательно)", doc.number) { doc = doc.copy(number = it) }
            Field("Дата выдачи · ДД.ММ.ГГГГ", issuedText) { issuedText = it }
            Field("Действует до · ДД.ММ.ГГГГ", expiresText) { expiresText = it }
            Field("Кем выдан", doc.issuer) { doc = doc.copy(issuer = it) }
            Field("Комментарий", doc.comment) { doc = doc.copy(comment = it) }
            Text("Напоминания необязательны: укажи дни через запятую, например 180,90,30.", color = Muted)
            Field("За сколько дней напомнить", doc.reminderDays) { doc = doc.copy(reminderDays = it) }
            TextButton({
                runCatching {
                    doc.copy(
                        issued = if (issuedText.isBlank()) "" else parseProfileDate(issuedText),
                        expires = if (expiresText.isBlank()) "" else parseProfileDate(expiresText)
                    )
                }.onSuccess(attach).onFailure { error = "Проверьте даты в формате ДД.ММ.ГГГГ" }
            }) { Text(if (doc.attachment.isEmpty()) "Прикрепить PDF / изображение" else "Заменить файл") }
            if (initial.title.isNotBlank()) TextButton({ confirm = true }) { Text("Удалить документ") }
            if (error != null) Text(error!!, color = Accent)
        }
    }, confirmButton = {
        TextButton({
            runCatching {
                doc.copy(
                    issued = if (issuedText.isBlank()) "" else parseProfileDate(issuedText),
                    expires = if (expiresText.isBlank()) "" else parseProfileDate(expiresText)
                ).also { it.validate() }
            }.onSuccess {
                if (it.reminderDays.isNotBlank() && Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS)
                save(it)
            }.onFailure { error = "Проверьте поля и даты в формате ДД.ММ.ГГГГ" }
        }) { Text("Сохранить") }
    }, dismissButton = { TextButton(dismiss) { Text("Отмена") } })

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Удалить документ и файл?") },
        confirmButton = { TextButton({ delete(doc.id) }) { Text("Удалить") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } }
    )
}

@Composable private fun DocumentPreview(doc: UserDocument, dismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.id, page) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val bytes = Base64.decode(doc.attachment, Base64.NO_WRAP)
                if (doc.mime == "application/pdf") {
                    val file = File(context.noBackupFilesDir, "document-preview-${UUID.randomUUID()}")
                    try {
                        file.writeBytes(bytes)
                        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        file.delete()
                        PdfRenderer(fd).use { renderer ->
                            count = renderer.pageCount
                            renderer.openPage(page).use { p ->
                                val width = 1000
                                val height = (p.height.toDouble() / p.width * width).toInt().coerceIn(1, 6000)
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    } finally {
                        file.delete()
                        bytes.fill(0)
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        } catch (e: Exception) {
            error = "Не удалось открыть файл"
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(doc.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (error != null) Text(error!!)
                bitmap?.let { Image(it.asImageBitmap(), "Документ", Modifier.fillMaxWidth()) }
                if (count > 1) Row {
                    TextButton({ page-- }, enabled = page > 0) { Text("Назад") }
                    Text("${page + 1}/$count")
                    TextButton({ page++ }, enabled = page < count - 1) { Text("Далее") }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Закрыть") } }
    )
}
