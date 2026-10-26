package com.newapimonitor.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newapimonitor.data.api.ApiException
import com.newapimonitor.data.api.NewApiClient
import com.newapimonitor.data.api.ResponseParsing
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.demo.DemoEngine
import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed class SearchResultItem {
    data class Token(val name: String) : SearchResultItem()
    data class Model(val name: String) : SearchResultItem()
    data class ErrorLog(val model: String, val createdAt: Long) : SearchResultItem()
    data class Setting(val label: String) : SearchResultItem()
}

/** Only public names are searchable: never match or render token keys or raw error content. */
internal fun searchResults(
    query: String,
    tokens: List<TokenInfo>,
    models: List<String>,
    errors: List<LogEntry>,
    settings: List<String>,
    errorLabel: String = "錯誤日誌",
): List<SearchResultItem> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return buildList {
        tokens.filter { it.name.contains(term, ignoreCase = true) }.take(5)
            .forEach { add(SearchResultItem.Token(it.name)) }
        models.filter { it.contains(term, ignoreCase = true) }.take(5)
            .forEach { add(SearchResultItem.Model(it)) }
        errors.filter {
            it.type == 5 && (errorLabel.contains(term, ignoreCase = true) ||
                it.modelName?.contains(term, ignoreCase = true) == true ||
                it.tokenName?.contains(term, ignoreCase = true) == true)
        }.take(5).forEach { add(SearchResultItem.ErrorLog(it.modelName.orEmpty(), it.createdAt)) }
        settings.filter { it.contains(term, ignoreCase = true) }.take(5)
            .forEach { add(SearchResultItem.Setting(it)) }
    }
}

@Composable
fun GlobalSearchDialog(
    repo: NewApiRepository,
    onDismiss: () -> Unit,
    onNavigateTab: (String) -> Unit,
) {
    val settings by repo.store.settings.collectAsState()
    val inst by repo.store.activeInstance.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val settingLabels = listOf(tr("設定"), tr("一般設定"), tr("實例"), tr("安全鎖定"), tr("警報通知"), tr("外觀與語言"))
    val errorLabel = tr("錯誤日誌")

    // Source and query are effect keys: a new input/instance cancels the old request and clears its rows.
    LaunchedEffect(query, settings.demo, inst?.id, inst?.accessToken, settingLabels, errorLabel) {
        results = emptyList()
        permissionDenied = false
        loading = false
        val term = query.trim()
        if (term.isEmpty()) return@LaunchedEffect
        results = searchResults(term, emptyList(), emptyList(), emptyList(), settingLabels, errorLabel)
        if (!settings.demo && (inst == null || inst?.accessToken.isNullOrBlank())) return@LaunchedEffect
        delay(250)
        loading = true
        val tokens = mutableListOf<TokenInfo>()
        val models = mutableListOf<String>()
        val errors = mutableListOf<LogEntry>()
        try {
            if (settings.demo) {
                DemoEngine.start()
                tokens.addAll(DemoEngine.tokens())
                models.addAll(DemoEngine.distinctModels())
                errors.addAll(DemoEngine.todayLogs().filter { it.type == 5 }.take(100))
            } else {
                val source = inst ?: return@LaunchedEffect
                // Only the user's own token and log endpoints. Never request /api/channel/ here.
                try {
                    tokens.addAll(repo.fetchTokensPage(source).tokens)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 401 || e.status == 403) permissionDenied = true
                } catch (_: Exception) { /* Keep local settings and other available results. */ }
                try {
                    models.addAll(repo.modelsForFilter(source))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { /* Models are optional. */ }
                try {
                    val client = NewApiClient(source.baseUrl, source.accessToken, source.apiKey,
                        source.authStyle, source.authStyleKey, source.quotaPerUnit)
                    repo.snapshotForSource(demo = false, inst = source)?.user?.id?.let(client::setUserId)
                    val end = System.currentTimeMillis() / 1000
                    val data = client.fetchLogs(p = 1, pageSize = 100, startTs = end - 7 * 86400L,
                        endTs = end, type = 5)
                    errors.addAll(ResponseParsing.pickLogItems(data).map(ResponseParsing::parseLogEntry)
                        .filter { it.type == 5 }.sortedByDescending { it.createdAt })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 401 || e.status == 403) permissionDenied = true
                } catch (_: Exception) { /* Keep other available results. */ }
            }
            results = searchResults(term, tokens, models, errors, settingLabels, errorLabel)
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardFillColor,
        shape = RoundedCornerShape(18.dp),
        titleContentColor = TxtColor,
        textContentColor = Txt2Color,
        title = { Text(tr("全域即時搜尋"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("搜尋令牌、模型、錯誤日誌或設定…"), fontSize = 12.5.sp, color = MutedColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent1,
                        unfocusedBorderColor = CardStrokeColor,
                        focusedTextColor = TxtColor,
                        unfocusedTextColor = TxtColor,
                        cursorColor = Accent1,
                        focusedContainerColor = InnerFillColor,
                        unfocusedContainerColor = InnerFillColor,
                    ),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                if (permissionDenied) Text(tr("搜尋部分資料需要權限（401/403）；請檢查 Access Token 權限。"),
                    fontSize = 12.sp, color = MutedColor, modifier = Modifier.padding(vertical = 8.dp))
                if (query.isNotBlank() && results.isEmpty()) {
                    Text(if (loading) tr("載入中…") else tr("冇搵到符合嘅項目"),
                        fontSize = 12.sp, color = MutedColor, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        itemsIndexed(results) { idx, item ->
                            SearchResultRow(item) {
                                onDismiss()
                                onNavigateTab(when (item) {
                                    is SearchResultItem.Token -> "tokens"
                                    is SearchResultItem.Model, is SearchResultItem.ErrorLog -> "logs"
                                    is SearchResultItem.Setting -> "settings"
                                })
                            }
                            if (idx < results.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("關閉"), color = Accent1) } },
    )
}

@Composable
private fun SearchResultRow(item: SearchResultItem, onClick: () -> Unit) {
    val (typeLabel, title, sub) = when (item) {
        is SearchResultItem.Token -> Triple(tr("令牌"), item.name, "")
        is SearchResultItem.Model -> Triple(tr("模型"), item.name, tr("日誌"))
        is SearchResultItem.ErrorLog -> Triple(tr("錯誤日誌"), item.model.ifBlank { tr("錯誤日誌") },
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(item.createdAt * 1000)))
        is SearchResultItem.Setting -> Triple(tr("設定"), item.label, "")
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 2.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(InnerFillColor)
            .border(1.dp, StrokeSoft, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(typeLabel, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, color = Accent1, maxLines = 1)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TxtColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 10.5.sp, color = MutedColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
