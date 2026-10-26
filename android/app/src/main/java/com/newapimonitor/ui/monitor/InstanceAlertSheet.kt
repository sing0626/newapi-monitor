package com.newapimonitor.ui.monitor

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newapimonitor.alert.AlertScheduler
import com.newapimonitor.data.monitor.*
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.data.store.AlertSettingsStore
import com.newapimonitor.domain.Instance
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun InstanceAlertSheet(repo: NewApiRepository, source: Instance, dismiss: () -> Unit) {
    val store = remember { MonitorPreferencesStore(repo.store.appContext) }
    val rules by store.rules.collectAsState(emptyMap())
    val rule = rules[source.id] ?: InstanceAlertRule()
    var enabled by remember(rule) { mutableStateOf(rule.enabled) }
    var balance by remember(rule) { mutableStateOf(rule.balanceBelow?.toString().orEmpty()) }
    var daily by remember(rule) { mutableStateOf(rule.dailyAbove?.toString().orEmpty()) }
    var start by remember(rule) { mutableStateOf(rule.quietStart) }
    var end by remember(rule) { mutableStateOf(rule.quietEnd) }
    var cooldown by remember(rule) { mutableStateOf(rule.cooldownMinutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("實例警報", style = MaterialTheme.typography.titleLarge)
            Text(source.name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("啟用此實例警報", Modifier.weight(1f)); Switch(enabled, { enabled = it })
            }
            Text("啟用時會一併開啟警報總開關；背景檢查約每 15 分鐘，系統省電可能延後。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(balance, { balance = it }, label = { Text("餘額低於 USD（留空停用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(daily, { daily = it }, label = { Text("每日消費高於 USD（留空停用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(start, { start = it }, label = { Text("靜音開始 HH:mm（留空關閉）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(end, { end = it }, label = { Text("靜音結束 HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cooldown, { cooldown = it }, label = { Text("相同警報冷卻分鐘（1–1440）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !saving, onClick = {
                val next = runCatching {
                    InstanceAlertRule(enabled, balance.takeIf(String::isNotBlank)?.toDouble(), daily.takeIf(String::isNotBlank)?.toDouble(),
                        start.trim(), end.trim(), cooldown.toInt()).validate()
                }.getOrNull()
                if (next == null) error = "請檢查金額、靜音時間及冷卻分鐘；靜音開始／結束需一同填寫且不能相同。"
                else scope.launch {
                    saving = true
                    try {
                        store.saveRule(source.id, next)
                        if (next.enabled) {
                            AlertSettingsStore(repo.store.appContext).update { it.copy(enabled = true) }
                            AlertScheduler.ensure(repo.store.appContext, true)
                            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        dismiss()
                    } catch (_: Exception) { error = "未能儲存，請重試"; saving = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("儲存警報") }
        }
    }
}
