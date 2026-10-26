package com.newapimonitor.ui.monitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.data.monitor.MonitorPreferences
import com.newapimonitor.domain.Instance
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.theme.*
import kotlinx.coroutines.launch

@Composable internal fun ModelPicker(vm: MonitorViewModel, state: MonitorUiState, selected: Set<String>, demo: Boolean, input: String, change: (String) -> Unit) {
    val options by vm.preferenceStore.preferences.collectAsState(MonitorPreferences())
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var suiteName by remember { mutableStateOf("") }
    var namingSuite by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    LaunchedEffect(selected, demo) { vm.loadModels(selected) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { browse = !browse }) { Text(if (browse) "收起模型清單" else "搜尋及收藏模型") }
        if (browse) TextButton(enabled = !state.catalogLoading && !state.probing, onClick = { vm.loadModels(selected) }) { Text("重新載入") }
    }
    if (browse) {
        OutlinedTextField(search, { search = it }, label = { Text("搜尋模型名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(favoritesOnly, { favoritesOnly = it }); Text("只顯示收藏", color = Txt2Color, fontSize = 12.sp) }
        if (state.catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.catalogFailures.forEach { (id, failure) -> Text("${id.take(6)}：$failure", color = WarnAmber, fontSize = 11.sp) }
        val names = (state.modelCatalog.values.flatten() + options.favorites).distinct()
            .filter { it.contains(search, true) && (!favoritesOnly || it in options.favorites) }
            .sortedWith(compareBy<String> { it !in options.favorites }.thenBy { it })
        if (names.isEmpty()) Text("未有符合模型。先選實例，或手動輸入模型名稱。", color = MutedColor, fontSize = 12.sp)
        val chosen = input.lines().map(String::trim).filter(String::isNotEmpty).toSet()
        names.take(40).forEach { model ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(model in chosen, enabled = !state.probing && (model in chosen || chosen.size < 3), onCheckedChange = {
                    change((if (it) chosen + model else chosen - model).joinToString("\n"))
                })
                Column(Modifier.weight(1f)) {
                    Text(model, color = TxtColor, fontSize = 12.sp)
                    Text("${state.modelCatalog.values.count { model in it }}/${if (demo) 1 else selected.size} 個實例清單提供", color = MutedColor, fontSize = 10.sp)
                }
                TextButton(onClick = { scope.launch { vm.preferenceStore.update { it.copy(favorites = if (model in it.favorites) it.favorites - model else it.favorites + model) } } }) {
                    Text(if (model in options.favorites) "★" else "☆")
                }
            }
        }
        if (names.size > 40) Text("顯示首 40 個結果，請輸入名稱縮窄搜尋。", color = MutedColor, fontSize = 11.sp)
    }
    TextButton(onClick = { namingSuite = !namingSuite; if (namingSuite && suiteName.isBlank()) suiteName = "常用組合" }) { Text("儲存測試組合") }
    if (namingSuite) {
        OutlinedTextField(suiteName, { suiteName = it }, label = { Text("組合名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = suiteName.isNotBlank(), onClick = { vm.saveSuite(suiteName, input); namingSuite = false }) { Text("儲存組合") }
    }
    options.suites.forEach { suite -> Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !state.probing, onClick = { change(suite.models.joinToString("\n")) }, modifier = Modifier.weight(1f)) { Text("套用：${suite.name}") }
        TextButton(onClick = { scope.launch { vm.preferenceStore.update { it.copy(suites = it.suites.filterNot { s -> s.name == suite.name }) } } }) { Text("移除") }
    } }
}

@Composable internal fun ProbeComparison(state: MonitorUiState, instances: List<Instance>) {
    if (state.results.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth()) {
        Text("本輪測速比較", color = TxtColor, style = MaterialTheme.typography.titleMedium)
        state.results.groupBy { it.sourceId }.forEach { (id, rows) ->
            Text("${instances.firstOrNull { it.id == id }?.name ?: "演示實例"}：成功 ${rows.count { it.ok }}/${rows.size}（${rows.count { it.ok } * 100 / rows.size}%）", color = MutedColor, fontSize = 12.sp)
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { listOf("實例 / 模型", "首字 ms", "tokens/s", "結果").forEach { Text(it, Modifier.width(132.dp).padding(vertical = 12.dp), color = MutedColor, fontSize = 12.sp) } }
            state.results.forEach { result -> Row {
                listOf((instances.firstOrNull { it.id == result.sourceId }?.name ?: "演示實例") + " / " + result.model,
                    result.firstTokenMs?.toString() ?: "—", result.tokensPerSecond?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—",
                    if (result.ok) "成功" else "失敗").forEach { Text(it, Modifier.width(132.dp).padding(end = 8.dp, bottom = 12.dp), color = Txt2Color, fontSize = 12.sp) }
            } }
        }
    }
}
