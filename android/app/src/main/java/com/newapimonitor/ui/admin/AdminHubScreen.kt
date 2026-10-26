package com.newapimonitor.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.AdminUserInfo
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.Badge
import com.newapimonitor.ui.components.BadgeTone
import com.newapimonitor.ui.components.EmptyStateCard
import com.newapimonitor.ui.components.FigureKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.LogRowItem
import com.newapimonitor.ui.components.SkeletonLogRow
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.BgColor
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.WarnAmber
import com.newapimonitor.ui.theme.isLightMode
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.ui.theme.tr
import com.newapimonitor.util.Fmt

/** H .chip2 pill（同 TokensScreen 同款幾何） */
private val HChipShape = RoundedCornerShape(50)

/**
 * 管理員 Hub（唯讀第一階段，方案 H 實心卡）：
 *  - seg「使用者 | 全站日誌」：使用者列表（GET /api/user/，伺服器搜尋＋分頁）與全站日誌（GET /api/log/）
 *  - 401 = PAT 無效、403 = 冇管理員權限、404 = 站點唔支援，各自有明確狀態；唔會扮成功或出空列表當冇人
 *  - 完全唯讀：唔代管理員改任何使用者／日誌
 */
@Composable
fun AdminHubScreen(repo: NewApiRepository, onNavigateBack: () -> Unit, bottomContentPadding: Dp = 0.dp) {
    val vm: AdminViewModel = viewModel(initializer = { AdminViewModel(repo) })
    val state by vm.state.collectAsState()
    DisposableLifecycleEffect(onStart = vm::resume, onStop = vm::pause)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("管理員專區"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                AppButton(tr("返回"), onNavigateBack, kind = AppButtonKind.GHOST, small = true)
            }
            Text(tr("唯讀監察全站使用者與日誌；呢個 App 唔會代你改任何使用者。"),
                color = MutedColor, fontSize = 11.5.sp, modifier = Modifier.padding(top = 7.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                HFilterChip(tr("使用者"), active = state.seg == AdminState.Seg.USERS) { vm.setSeg(AdminState.Seg.USERS) }
                HFilterChip(tr("全站日誌"), active = state.seg == AdminState.Seg.LOGS) { vm.setSeg(AdminState.Seg.LOGS) }
                Spacer(Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(color = Accent1, strokeWidth = 2.dp, modifier = Modifier.width(16.dp).height(16.dp))
                }
            }
        }
        when {
            state.source == "none" -> MessageCard(tr("未設定實例"))
            state.needsAccessToken -> EmptyStateCard(
                kind = FigureKind.KEY,
                title = tr("需要 Access Token"),
                sub = tr("管理員功能要管理員權限嘅 Access Token 先查到。\n實例設定入面填好再返嚟。"),
            )
            state.seg == AdminState.Seg.USERS -> UsersSection(vm, state)
            else -> LogsSection(state)
        }
    }
}

/* ---------------- 使用者列表 ---------------- */

@Composable
private fun UsersSection(vm: AdminViewModel, state: AdminState) {
    state.usersFailure?.let { failure ->
        GlassCard {
            Text(permissionLabel(failure), color = Txt2Color, fontSize = 12.sp)
            AppButton(tr("重新整理"), vm::refresh, kind = AppButtonKind.GHOST, small = true,
                modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    OutlinedTextField(
        value = state.userKeyword,
        onValueChange = vm::setUserSearch,
        placeholder = { Text(tr("搜尋用戶名…"), fontSize = 12.sp, color = MutedColor) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent1,
            unfocusedBorderColor = CardStrokeColor,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            cursorColor = Accent1,
            focusedTextColor = TxtColor,
            unfocusedTextColor = TxtColor,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.loading && state.users.isEmpty()) {
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(4) { SkeletonLogRow() }
        }
        return
    }
    if (state.users.isEmpty()) {
        GlassCard { Text(tr("站點冇回傳任何使用者"), color = MutedColor, fontSize = 12.sp) }
        return
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("全站使用者"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Badge("${state.users.size} " + tr("人"), tone = BadgeTone.ACTIVE)
        }
        if (state.usersTruncated) {
            Text(tr("已達載入上限（1000 人）；請用搜尋收窄範圍。"),
                fontSize = 11.sp, color = WarnAmber, modifier = Modifier.padding(top = 4.dp))
        }
        Column(Modifier.padding(top = 4.dp)) {
            state.users.forEachIndexed { i, u ->
                UserRow(u, state.qpu)
                if (i != state.users.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(HairlineColor))
                }
            }
        }
        if (state.demo) Text(tr("演示模擬數據"), color = DimColor, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun UserRow(u: AdminUserInfo, qpu: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(modelBrandColor(u.username)),
            contentAlignment = Alignment.Center,
        ) {
            Text(u.username.trim().take(1).uppercase().ifEmpty { "?" },
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                listOf(u.displayName, u.username).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = TxtColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(u.roleLabel)
                    if (u.group.isNotBlank()) append(" · ").append(u.group)
                    append(" · ").append(Fmt.num(u.requestCount.toDouble())).append(" ").append(tr("次"))
                },
                fontSize = 10.5.sp, color = MutedColor, style = TabularStyle,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                Fmt.usd(u.quota / qpu, 2),
                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor, style = TabularStyle,
            )
            Text(
                tr("已用 ") + Fmt.usd(u.usedQuota / qpu, 2),
                fontSize = 10.sp, color = MutedColor, style = TabularStyle,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (u.status == 2) Badge(tr("已停用"), tone = BadgeTone.NEUTRAL)
    }
}

/* ---------------- 全站日誌 ---------------- */

@Composable
private fun LogsSection(state: AdminState) {
    state.logsFailure?.let { failure ->
        GlassCard { Text(permissionLabel(failure), color = Txt2Color, fontSize = 12.sp) }
        return
    }
    if (state.loading && state.logs.isEmpty()) {
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(4) { SkeletonLogRow() }
        }
        return
    }
    if (state.logs.isEmpty()) {
        GlassCard { Text(tr("站點冇回傳任何日誌"), color = MutedColor, fontSize = 12.sp) }
        return
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("全站日誌"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Badge("${state.logs.size} " + tr("行"), tone = BadgeTone.ACTIVE)
        }
        if (state.logsTruncated) {
            Text(tr("僅載入最近 1000 行；統計屬約數。"),
                fontSize = 11.sp, color = WarnAmber, modifier = Modifier.padding(top = 4.dp))
        }
        Column(Modifier.padding(top = 4.dp)) {
            state.logs.take(100).forEachIndexed { i, row ->
                LogRowItem(entry = row, qpu = state.qpu, isLast = i == minOf(state.logs.size, 100) - 1)
            }
        }
        if (state.demo) Text(tr("演示模擬數據"), color = DimColor, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/* ---------------- 共用 ---------------- */

@Composable
private fun permissionLabel(failure: AdminFailure): String = when (failure) {
    AdminFailure.AUTH -> tr("認證失敗（401）：檢查 Access Token")
    AdminFailure.FORBIDDEN -> tr("需要管理員權限（403）：呢個 Access Token 唔係管理員。")
    AdminFailure.MISSING -> tr("站點不支援此端點（404）")
    AdminFailure.NETWORK -> tr("無法取得站點資料，請檢查連線")
    AdminFailure.INVALID -> tr("站點回應格式不正確")
}

@Composable
private fun MessageCard(message: String) { GlassCard { Text(message, color = Txt2Color, fontSize = 12.sp) } }

@Composable
private fun HFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (active) TxtColor else InnerFillColor
    val fg = if (active) (if (isLightMode) CardFillColor else BgColor) else Txt2Color
    Box(
        Modifier
            .clip(HChipShape)
            .background(bg)
            .border(1.dp, if (active) bg else StrokeSoft, HChipShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg, style = TabularStyle, maxLines = 1)
    }
}
