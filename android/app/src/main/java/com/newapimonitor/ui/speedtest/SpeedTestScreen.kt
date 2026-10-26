package com.newapimonitor.ui.speedtest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.PingResult
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.OkGreen
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.WarnAmber
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedTestScreen(
    repo: NewApiRepository,
    onNavigateBack: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val vm: SpeedTestViewModel = viewModel(initializer = { SpeedTestViewModel(repo) })
    val state by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 頂欄導覽卡
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("模型測速探針"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                )
                Spacer(Modifier.weight(1f))
                AppButton(
                    text = tr("返回"),
                    onClick = onNavigateBack,
                    kind = AppButtonKind.GHOST,
                    small = true,
                )
            }
        }

        // 提示說明卡
        GlassCard {
            Text(
                tr("測試說明"),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TxtColor,
            )
            Text(
                tr("測速請求說明"),
                fontSize = 11.5.sp,
                color = MutedColor,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // 錯誤條提示
        state.error?.let { err ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x14F87171))
                    .border(1.dp, Color(0x59F87171), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚠ ${tr(err)}",
                        fontSize = 12.sp,
                        color = ErrRed,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        text = tr("關閉"),
                        onClick = vm::clearError,
                        kind = AppButtonKind.GHOST,
                        small = true,
                    )
                }
            }
        }

        // 測速控制卡
        GlassCard {
            Text(
                tr("測試模型清單（每行一個，最多 5 個）"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Txt2Color,
            )

            OutlinedTextField(
                value = state.modelsInput,
                onValueChange = vm::setModelsInput,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                enabled = !state.isRunning,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent1,
                    unfocusedBorderColor = CardStrokeColor,
                    disabledBorderColor = CardStrokeColor,
                    focusedTextColor = TxtColor,
                    unfocusedTextColor = TxtColor,
                    disabledTextColor = TxtColor,
                    cursorColor = Accent1,
                    focusedContainerColor = InnerFillColor,
                    unfocusedContainerColor = InnerFillColor,
                    disabledContainerColor = InnerFillColor,
                ),
                shape = RoundedCornerShape(12.dp),
                minLines = 3,
                maxLines = 6,
            )

            if (state.availableModels.isNotEmpty()) {
                Text(
                    tr("可用模型點擊快速加入："),
                    fontSize = 11.sp,
                    color = MutedColor,
                    modifier = Modifier.padding(top = 10.dp),
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.availableModels.take(8).forEach { model ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(InnerFillColor)
                                .border(1.dp, StrokeSoft, RoundedCornerShape(50))
                                .clickable(enabled = !state.isRunning) { vm.addModelToInput(model) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(model, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Txt2Color)
                        }
                    }
                }
            }

            if (state.availableModels.isNotEmpty()) {
                val count = state.availableModels.distinct().count { it.isNotBlank() }
                AppButton(
                    text = tr("測試全部可用模型") + " ($count)",
                    onClick = vm::startAllAvailableModels,
                    kind = AppButtonKind.GHOST,
                    enabled = !state.isRunning && count <= 5,
                )
                if (count > 5) {
                    Text(
                        tr("可用模型超過 5 個，請手動選擇最多 5 個"),
                        fontSize = 11.sp,
                        color = MutedColor,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = if (state.isRunning) tr("測試中…") else tr("開始測試"),
                    onClick = vm::startSpeedTest,
                    kind = AppButtonKind.PRIMARY,
                    enabled = !state.isRunning,
                )
                if (state.isRunning) {
                    AppButton(
                        text = tr("停止"),
                        onClick = vm::stopSpeedTest,
                        kind = AppButtonKind.DANGER,
                    )
                }
                state.currentTestingModel?.let { cur ->
                    Text(
                        "${tr("正在測試：")}$cur",
                        fontSize = 11.sp,
                        color = WarnAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 即時延遲排行榜
        if (state.latestResults.isNotEmpty()) {
            GlassCard {
                Text(
                    tr("本次測速排行榜"),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                )
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    val sortedList = state.latestResults.values.sortedWith(
                        compareBy<PingResult> { if (it.ok) 0 else 1 }
                            .thenBy { it.latencyMs }
                    )
                    // mini bar 以反比延遲做長度：最快嗰行滿格
                    val best = sortedList.filter { it.ok }.minOfOrNull { it.latencyMs }
                    sortedList.forEachIndexed { idx, res ->
                        RankItemRow(rank = idx + 1, result = res, bestLatencyMs = best)
                        if (idx < sortedList.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
                        }
                    }
                }
            }
        }

        // 歷史測速紀錄卡
        GlassCard {
            Text(
                tr("本機歷史紀錄（最近 20 筆）"),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = TxtColor,
            )
            if (state.history.isEmpty()) {
                Text(
                    tr("未有任何測速紀錄。點「開始測試」進行測試。"),
                    fontSize = 11.5.sp,
                    color = MutedColor,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    state.history.forEachIndexed { idx, hist ->
                        HistoryItemRow(item = hist)
                        if (idx < state.history.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
                        }
                    }
                }
            }
        }
    }
}

/** 排行榜行（H .mrow）：品牌色圓角方塊 rank icon + 名 + 右側粗體延遲 + 反比延遲 mini bar */
@Composable
private fun RankItemRow(rank: Int, result: PingResult, bestLatencyMs: Long?) {
    val statusColor = when {
        !result.ok -> ErrRed
        result.latencyMs < 500 -> OkGreen
        result.latencyMs < 1500 -> WarnAmber
        else -> ErrRed
    }
    val brand = modelBrandColor(result.model)
    val fraction = if (!result.ok || result.latencyMs <= 0L) 0f
    else bestLatencyMs?.let { (it.toFloat() / result.latencyMs).coerceIn(0.08f, 1f) } ?: 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brand),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rank.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.model,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TxtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(BarTrackColor)
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(brand)
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (result.ok) "${result.latencyMs} ms" else tr("失敗"),
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = statusColor,
            style = TabularStyle,
        )
    }
}

@Composable
private fun HistoryItemRow(item: PingHistoryItem) {
    val timeStr = remember(item.timestamp) {
        val dt = Instant.ofEpochMilli(item.timestamp).atZone(ZoneId.systemDefault())
        dt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.model,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = TxtColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            timeStr,
            fontSize = 10.5.sp,
            color = MutedColor,
            style = TabularStyle,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            if (item.ok) "${item.latencyMs} ms" else "ERR",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (item.ok) OkGreen else ErrRed,
            style = TabularStyle,
        )
    }
}
