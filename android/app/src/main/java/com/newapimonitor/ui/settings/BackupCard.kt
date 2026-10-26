package com.newapimonitor.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newapimonitor.data.backup.*
import com.newapimonitor.data.monitor.MonitorStore
import com.newapimonitor.data.monitor.MonitorPreferencesStore
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.data.store.Appearance
import com.newapimonitor.domain.AlertSettings
import com.newapimonitor.ui.components.*
import com.newapimonitor.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Document picking and password entry live outside the settings screen's main form. */
@Composable
fun BackupCard(repo: NewApiRepository, appearance: Appearance, alerts: AlertSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var include by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }
    var pendingPayload by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingPayload; pendingPayload = null
        if (uri != null && payload != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) } ?: error("No output") }
                status = "備份已儲存"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "儲存失敗，請重新匯出"
            } finally { busy = false }
        }
    }
    fun applyImport(plain: String) {
        val parsed = BackupManager.parse(plain)
        if (parsed == null) { status = "備份格式不正確或版本不支援"; return }
        vm.importBackup(parsed) { count -> status = "已匯入 $count 個實例" }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        val bytes = readBackupBytes(it)
                        require(bytes.size <= 16 * 1024 * 1024)
                        bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
                    } ?: error("No input")
                }
                if (BackupCipher.isEncrypted(text)) { password = ""; importText = text } else applyImport(text)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "讀取失敗或檔案超過 16 MiB"
            } finally { busy = false }
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(tr("備份與還原"), color = TxtColor)
        Text(tr("預設只匯出設定。包含金鑰時必須使用密碼加密；加密及解密全部喺手機完成。"), color = MutedColor)
        SwitchRow(label = tr("備份包含令牌"), sub = tr("包含 Access Token／API Key，需要至少 10 字元密碼"), checked = include,
            onCheckedChange = { if (!busy) include = it })
        OutlinedTextField(password, { password = it }, label = { Text(tr("備份密碼（無金鑰時可留空）")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (password.isNotEmpty() || include) OutlinedTextField(confirmation, { confirmation = it }, label = { Text(tr("再輸入密碼")) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(tr("導出備份"), {
                val includeAtClick = include
                val instancesAtClick = repo.store.instances.value.toList()
                if ((include || password.isNotEmpty()) && (password.length < 10 || password != confirmation)) {
                    status = "密碼至少 10 字元，兩次輸入必須相同"
                } else scope.launch {
                    busy = true
                    val secret = password.toCharArray(); password = ""; confirmation = ""
                    try {
                        val instances = instancesAtClick
                        val budgets = MonitorStore(repo.store.appContext).budgets.first()
                        val homeStore = MonitorPreferencesStore(repo.store.appContext)
                        val homeOptions = homeStore.preferences.first()
                        val alertRules = homeStore.rules.first()
                        val payload = withContext(Dispatchers.Default) {
                            val plain = BackupManager.toJson(instances, appearance, alerts, includeCredentials = includeAtClick,
                                budgetsById = budgets, homeOptions = homeOptions, alertRules = alertRules)
                            if (secret.isNotEmpty()) BackupCipher.encrypt(plain, secret) else plain
                        }
                        pendingPayload = payload
                        export.launch("newapi-monitor-${java.time.LocalDate.now()}.json")
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { status = "無法建立備份"
                    } finally { secret.fill('\u0000'); busy = false }
                }
            }, small = true, enabled = !busy && importText == null, kind = AppButtonKind.PRIMARY)
            AppButton(tr("匯入備份"), { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, small = true, enabled = !busy && importText == null)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, color = Txt2Color)
        Text(tr("無金鑰備份匯入同一站點及同名實例時，保留手機已有嘅金鑰。密碼唔會儲存，遺失後無法還原加密備份。"), color = MutedColor)
    }
    if (importText != null) AlertDialog(
        onDismissRequest = { if (!busy) { importText = null; password = "" } },
        title = { Text(tr("解密備份")) },
        text = { Column { OutlinedTextField(password, { password = it }, label = { Text(tr("密碼")) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            if (status.isNotEmpty()) Text(status, color = MutedColor) } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val encrypted = importText ?: return@TextButton
            val secret = password.toCharArray(); password = ""
            scope.launch {
                busy = true
                try { val plain = withContext(Dispatchers.Default) { BackupCipher.decrypt(encrypted, secret) }; applyImport(plain); importText = null
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { status = "密碼不正確或備份已損壞，未匯入任何資料"
                } finally { secret.fill('\u0000'); busy = false }
            }
        }) { Text(tr("解密並匯入")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { importText = null; password = "" }) { Text(tr("取消")) } },
    )
}

internal fun readBackupBytes(input: java.io.InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        require(output.size() + size <= 16 * 1024 * 1024) { "Backup too large" }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
