package com.newapimonitor.ui.monitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newapimonitor.data.monitor.*
import com.newapimonitor.domain.Instance
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HomeOptionsSheet(options: MonitorPreferences, instances: List<Instance>, store: MonitorPreferencesStore, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("自訂總覽", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("緊湊實例卡", Modifier.weight(1f))
                Switch(options.compact, { scope.launch { store.update { p -> p.copy(compact = it) } } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("隱藏首頁金額", Modifier.weight(1f))
                Switch(options.hideMoney, { scope.launch { store.update { p -> p.copy(hideMoney = it) } } })
            }
            Text("釘選項目排最前；排除只影響總餘額，消費統計仍然包括此實例。", style = MaterialTheme.typography.bodySmall)
            val ordered = options.sorted(instances) { it.id }
            ordered.forEachIndexed { index, source ->
                HorizontalDivider()
                Text(source.name, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(source.id in options.pinned, { checked -> scope.launch { store.update {
                        it.copy(pinned = if (checked) it.pinned + source.id else it.pinned - source.id)
                    } } })
                    Text("釘選", Modifier.weight(1f))
                    TextButton(enabled = index > 0 && ((source.id in options.pinned) == (ordered[index - 1].id in options.pinned)), onClick = { scope.launch { store.update { prefs ->
                        val ids = ordered.map { it.id }.toMutableList()
                        val prev = ids[index - 1]; ids[index - 1] = source.id; ids[index] = prev
                        prefs.copy(order = ids)
                    } } }) { Text("上移") }
                    TextButton(enabled = index < ordered.lastIndex && ((source.id in options.pinned) == (ordered[index + 1].id in options.pinned)), onClick = { scope.launch { store.update { prefs ->
                        val ids = ordered.map { it.id }.toMutableList()
                        val next = ids[index + 1]; ids[index + 1] = source.id; ids[index] = next
                        prefs.copy(order = ids)
                    } } }) { Text("下移") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(source.id in options.excluded, { checked -> scope.launch { store.update {
                        it.copy(excluded = if (checked) it.excluded + source.id else it.excluded - source.id)
                    } } })
                    Text("不計入總餘額")
                }
            }
            Button(dismiss, Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
