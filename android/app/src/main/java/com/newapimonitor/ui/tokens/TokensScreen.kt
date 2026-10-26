package com.newapimonitor.ui.tokens

import com.newapimonitor.ui.theme.tr
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.AppError
import com.newapimonitor.domain.ErrorKind
import com.newapimonitor.domain.TokenInfo
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.Badge
import com.newapimonitor.ui.components.BadgeTone
import com.newapimonitor.ui.components.EmptyStateCard
import com.newapimonitor.ui.components.ErrorCard
import com.newapimonitor.ui.components.FigureKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.SectionHeader
import com.newapimonitor.ui.components.SegOpt
import com.newapimonitor.ui.components.Segmented
import com.newapimonitor.ui.components.SkeletonLogRow
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.BarTrackColor
import com.newapimonitor.ui.theme.BgColor
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.CyanLight
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.isLightMode
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.OkGreen
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.WarnAmber
import com.newapimonitor.util.Fmt
import java.time.Instant
import java.time.ZoneId

/**
 * 令牌管理：列表卡（名稱／狀態徽章／已用剩餘 USD／有效期／建立時間），
 * 撳卡彈 dialog 顯示完整 key（sk-…）＋複製＋啟用/停用切換。
 * 需要 Access Token（管理 API）；keyOnly 實例顯示引導空狀態。
 */
@Composable
fun TokensScreen(
    repo: NewApiRepository,
    onNavigateSettings: () -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val vm: TokensViewModel = viewModel(initializer = { TokensViewModel(repo) })
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<TokenInfo?>(null) }
    LaunchedEffect(state.seg, repo.store.settings.value.demo, repo.store.activeInstance.value?.id) {
        selected = null
        vm.clearReveal()
    }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TokenInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<TokenInfo?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 多選模式（令牌 seg）先出吸底操作列；列表底部預留返佢嘅企位
    val batchBarVisible = state.seg == TokensViewModel.Seg.TOKENS && state.selectionMode
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp + bottomContentPadding + (if (batchBarVisible) 84.dp else 0.dp),
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 摘要列：標題「令牌管理」＋ seg（令牌 | 渠道）＋ 張數 ＋ 右上「＋ 新增」
            GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("令牌管理"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
                    Spacer(Modifier.width(10.dp))
                    // H .chip2 藥丸 chips：選中實色反轉（mockup .chip2.on）
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HFilterChip(tr("令牌"), active = state.seg == TokensViewModel.Seg.TOKENS) {
                            vm.setSeg(TokensViewModel.Seg.TOKENS)
                        }
                        HFilterChip(tr("渠道"), active = state.seg == TokensViewModel.Seg.CHANNELS) {
                            vm.setSeg(TokensViewModel.Seg.CHANNELS)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.seg == TokensViewModel.Seg.TOKENS) {
                        if (state.tokens.isNotEmpty()) {
                            Badge("${state.tokens.size} ${tr("張")}", tone = BadgeTone.ACTIVE)
                            Spacer(Modifier.width(8.dp))
                        }
                        AppButton(tr("＋ 新增"), { showCreate = true }, small = true)
                    }
                }
            }

            // 切換失敗錯誤條（snackbar 位置，可收起）
            state.actionError?.let { msg ->
                ActionErrorBar(tr(msg), onDismiss = vm::clearActionError)
            }

            // 批量結果摘要（成功 N · 失敗 M；約 4 秒自動收起；有失敗轉琥珀色）
            state.batchResult?.let { (ok, fail) ->
                BatchResultBar(ok = ok, fail = fail, onDismiss = vm::clearBatchResult)
            }

            // 本月燒錢榜：seg=令牌 先出，喺令牌列表上面（載入中／失敗會靜默收起）
            if (state.seg == TokensViewModel.Seg.TOKENS) {
                SpendBoardCard(state)
            }

            when (state.seg) {
                TokensViewModel.Seg.CHANNELS -> ChannelsSection(
                    state = state,
                    onRetry = vm::retryChannels,
                    onToggle = vm::toggleChannelStatus,
                    onTest = vm::testChannel,
                    onDelete = vm::deleteChannel,
                )
                else -> TokensSection(
                    state = state,
                    vm = vm,
                    onNavigateSettings = onNavigateSettings,
                    onSelect = { selected = it },
                    onEdit = { editTarget = it },
                    onDelete = { deleteTarget = it },
                )
            }
        }

        // 批量管理吸底操作列（多選模式先出；批量跑緊時照住顯示進度）
        if (batchBarVisible) {
            BatchActionBar(
                state = state,
                onSelectAll = vm::selectAll,
                onEnable = { vm.batchSetStatus(true) },
                onDisable = { vm.batchSetStatus(false) },
                onDelete = { showBatchDeleteConfirm = true },
                onCancel = vm::exitSelectionMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp + bottomContentPadding + 8.dp),
            )
        }
    }

    selected?.let { token ->
        TokenDetailDialog(
            token = token,
            qpu = state.qpu,
            busy = state.togglingId == token.id,
            revealedKey = state.revealedKey,
            revealing = state.revealingId == token.id,
            revealError = state.revealError,
            onReveal = { vm.reveal(token) },
            onToggle = {
                vm.toggle(token)
                selected = null
            },
            onEdit = {
                selected = null
                vm.clearReveal()
                editTarget = token
            },
            onDelete = {
                selected = null
                vm.clearReveal()
                deleteTarget = token
            },
            onDismiss = { selected = null; vm.clearReveal() },
        )
    }
    if (showCreate) {
        TokenFormDialog(
            editing = null,
            qpu = state.qpu,
            busy = state.togglingId != null,
            onSave = { name, remain, unlimited, days, group, limitsEnabled, limits, ips ->
                vm.createToken(name, remain, unlimited, days, group, limitsEnabled, limits, ips)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    editTarget?.let { token ->
        TokenFormDialog(
            editing = token,
            qpu = state.qpu,
            busy = state.togglingId == token.id,
            onSave = { name, remain, unlimited, days, group, limitsEnabled, limits, ips ->
                vm.saveEdits(token, name, remain, unlimited, days, group, limitsEnabled, limits, ips)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
    deleteTarget?.let { token ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = CardFillColor,
            shape = RoundedCornerShape(18.dp),
            title = { Text(tr("刪除令牌"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    tr("確定刪除令牌「") + token.name + tr("」？此操作唔可復原；用緊呢張 key 嘅應用會即刻 401。"),
                    fontSize = 13.sp,
                    color = Txt2Color,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(token)
                    deleteTarget = null
                }) { Text(tr("刪除"), color = ErrRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(tr("取消"), color = Txt2Color) }
            },
        )
    }
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            containerColor = CardFillColor,
            shape = RoundedCornerShape(18.dp),
            title = { Text(tr("批量刪除"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    tr("確認刪除已選令牌？此操作唔可復原。"),
                    fontSize = 13.sp,
                    color = Txt2Color,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteConfirm = false
                    vm.batchDelete()
                }) { Text(tr("刪除"), color = ErrRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text(tr("取消"), color = Txt2Color) }
            },
        )
    }
}

/* ---------------- 令牌列表區 ---------------- */

@Composable
private fun TokensSection(
    state: TokensViewModel.UiState,
    vm: TokensViewModel,
    onNavigateSettings: () -> Unit,
    onSelect: (TokenInfo) -> Unit,
    onEdit: (TokenInfo) -> Unit,
    onDelete: (TokenInfo) -> Unit,
) {
    when {
        state.loading -> GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(4) { SkeletonLogRow() }
        }
        state.error != null -> {
            val err = state.error!!
            ErrorCard(
                title = errorTitle(err),
                message = tr(err.message),
                hint = errorHint(err),
                onRetry = vm::retry,
            )
        }
        state.noAccessToken -> EmptyStateCard(
            kind = FigureKind.KEY,
            title = tr("令牌管理需要 Access Token"),
            sub = tr("令牌列表（/api/token/）係管理 API，要 Access Token 先查到。\n實例設定入面填好 Access Token 再返嚟。"),
        ) {
            AppButton(tr("去設定"), onNavigateSettings, small = true)
        }
        state.tokens.isEmpty() && state.keyword.isNotBlank() -> EmptyStateCard(
            kind = FigureKind.LIST,
            title = tr("搵唔到符合嘅令牌"),
            sub = tr("換個關鍵字再試，或者清空搜尋睇返全部。"),
        ) {
            AppButton(tr("清除搜尋"), { vm.setTokenSearch("") }, small = true)
        }
        state.tokens.isEmpty() -> EmptyStateCard(
            kind = FigureKind.LIST,
            title = tr("仲未有任何令牌"),
            sub = tr("㩒右上「＋ 新增」直接開一張，或者去 NewAPI 控制台添加。"),
        ) {
            AppButton(tr("重新整理"), vm::retry, small = true)
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 伺服器端搜尋（350ms 防抖；新版站點 keyword=，demo 本地過濾）
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::setTokenSearch,
                placeholder = { Text(tr("搜尋令牌名稱…"), fontSize = 12.sp, color = MutedColor) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent1,
                    unfocusedBorderColor = CardStrokeColor,
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = Accent1,
                    focusedTextColor = TxtColor,
                    unfocusedTextColor = TxtColor,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = TxtColor),
                modifier = Modifier.fillMaxWidth(),
            )
            // 排序選擇工具列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("排序："),
                    fontSize = 11.5.sp,
                    color = MutedColor,
                    fontWeight = FontWeight.SemiBold,
                )
                // H .chip2 排序 chips（窄屏可橫向滑動）；值不變：default / used_desc / remain_asc / exp_asc
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    HFilterChip(tr("預設"), active = state.tokenSort == "default") { vm.setTokenSort("default") }
                    HFilterChip(tr("已用最多"), active = state.tokenSort == "used_desc") { vm.setTokenSort("used_desc") }
                    HFilterChip(tr("剩餘最少"), active = state.tokenSort == "remain_asc") { vm.setTokenSort("remain_asc") }
                    HFilterChip(tr("即將過期"), active = state.tokenSort == "exp_asc") { vm.setTokenSort("exp_asc") }
                    // 批量管理 pill chip：撳入多選模式（再撳退出）；批量跑緊時收起
                    if (!state.batchRunning) {
                        HFilterChip(tr("批量管理"), active = state.selectionMode) {
                            if (state.selectionMode) vm.exitSelectionMode() else vm.enterSelectionMode()
                        }
                    }
                }
            }

            if (state.truncated) {
                Text(
                    tr("已達載入上限（1000 張）；請用搜尋收窄範圍。"),
                    fontSize = 11.sp,
                    color = WarnAmber,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            vm.sortedTokens().forEach { token ->
                TokenCard(
                    token = token,
                    qpu = state.qpu,
                    toggling = state.togglingId == token.id,
                    selectionMode = state.selectionMode,
                    selected = token.id in state.selectedIds,
                    onClick = {
                        // 多選模式：撳卡 = 切換選取；平時：開詳情
                        if (state.selectionMode) vm.toggleSelect(token.id) else onSelect(token)
                    },
                    onLongClick = if (state.selectionMode) null else {
                        {
                            // 長撳直接入多選並選中呢張
                            vm.enterSelectionMode()
                            vm.toggleSelect(token.id)
                        }
                    },
                )
            }
        }
    }
}

/* ---------------- 批量管理：吸底操作列 ---------------- */

/**
 * 多選模式吸底操作卡：「已選 N」＋ 全選／批量啟用／批量停用／批量刪除／取消；
 * 批量跑緊時換「處理中… M / N」（tabular），全部按鈕停用；窄屏按鈕排唔落可橫向滑。
 */
@Composable
private fun BatchActionBar(
    state: TokensViewModel.UiState,
    onSelectAll: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = state.selectedIds.size
    val running = state.batchRunning
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                val prog = state.batchProgress
                Text(
                    tr("處理中…"),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${prog?.first ?: 0} / ${prog?.second ?: 0}",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                    style = TabularStyle,
                )
            } else {
                Text(
                    tr("已選 ") + n,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                    style = TabularStyle,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                AppButton(tr("全選"), onSelectAll, kind = AppButtonKind.GHOST, small = true, enabled = !running)
                AppButton(tr("批量啟用"), onEnable, kind = AppButtonKind.PRIMARY, small = true, enabled = !running && n > 0)
                AppButton(tr("批量停用"), onDisable, kind = AppButtonKind.GHOST, small = true, enabled = !running && n > 0)
                AppButton(tr("批量刪除"), onDelete, kind = AppButtonKind.DANGER, small = true, enabled = !running && n > 0)
                AppButton(tr("取消"), onCancel, kind = AppButtonKind.GHOST, small = true, enabled = !running)
            }
        }
    }
}

/* ---------------- 渠道列表區（admin 操作與監察） ---------------- */

@Composable
private fun ChannelsSection(
    state: TokensViewModel.UiState,
    onRetry: () -> Unit,
    onToggle: (com.newapimonitor.domain.ChannelInfo) -> Unit,
    onTest: (com.newapimonitor.domain.ChannelInfo) -> Unit,
    onDelete: (com.newapimonitor.domain.ChannelInfo) -> Unit,
) {
    when {
        state.channelsLoading -> GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            repeat(3) { SkeletonLogRow() }
        }
        state.channelsError != null -> {
            val err = state.channelsError!!
            ErrorCard(
                title = errorTitle(err),
                message = tr(err.message),
                hint = if (err.status == 401 || err.status == 403) tr("渠道列表要管理員權限嘅 Access Token 先查到；一般用戶 token 查唔到。")
                else errorHint(err),
                onRetry = onRetry,
            )
        }
        state.channels.isEmpty() -> EmptyStateCard(
            kind = FigureKind.INBOX,
            title = tr("冇渠道數據"),
            sub = tr("要管理員權限嘅 Access Token；一般用戶 token 查唔到渠道。"),
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                tr("渠道管理需要管理員權限；普通用戶 Token 只可檢視或無法操作。"),
                fontSize = 11.sp,
                color = MutedColor,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // H .mrow 列表：單一實心卡 + 髮絲線分隔（design-mockups/h-fintech.html .mrow）
            GlassCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                state.channels.forEachIndexed { i, ch ->
                    ChannelRow(
                        channel = ch,
                        operating = state.operatingChannelId == ch.id,
                        onToggle = onToggle,
                        onTest = onTest,
                        onDelete = onDelete,
                    )
                    if (i < state.channels.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
                    }
                }
            }
        }
    }
}

/* ---------------- H 本地元件（方案 H · 彩色金融卡風，design-mockups/h-fintech.html） ---------------- */

private val HChipShape = RoundedCornerShape(50)

/** mockup .chip2 藥丸 chip：選中實色反轉（light 深底白字／dark 淺底深字，.chip2.on），未選 InnerFillColor */
@Composable
private fun HFilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
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
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            style = TabularStyle,
            maxLines = 1,
        )
    }
}

/** 令牌品牌色圓角方塊字母 icon（H mockup .mic：26dp 方角 8，同儀表板 ModelIcon 一款） */
@Composable
private fun TokenBrandIcon(name: String) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(modelBrandColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
        )
    }
}

/** 渠道圓角方塊字母 icon：InnerFillColor 底 + Accent1 字母（H .mic 語言） */
@Composable
private fun ChannelTypeIcon(name: String) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(InnerFillColor)
            .border(1.dp, StrokeSoft, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Accent1,
        )
    }
}

/**
 * H .mrow 渠道行：類型字母 icon + 名稱 w600 13sp + 類型／回應 muted sub + 右側狀態 chip pill；
 * 測速／啟停用／刪除按鈕同 handlers 跟原本完全一致。
 */
@Composable
private fun ChannelRow(
    channel: com.newapimonitor.domain.ChannelInfo,
    operating: Boolean,
    onToggle: (com.newapimonitor.domain.ChannelInfo) -> Unit,
    onTest: (com.newapimonitor.domain.ChannelInfo) -> Unit,
    onDelete: (com.newapimonitor.domain.ChannelInfo) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelTypeIcon(channel.name)
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TxtColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(tr("類型 ")).append(channel.type)
                        channel.responseTime?.takeIf { it > 0 }?.let {
                            append(tr(" · 回應 ")).append(Fmt.num(it.toDouble())).append(" ms")
                        }
                    },
                    fontSize = 10.5.sp,
                    color = MutedColor,
                    style = TabularStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TokenStatusBadge(channel.status)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                text = if (operating) tr("測試中…") else tr("測試速度"),
                onClick = { onTest(channel) },
                kind = AppButtonKind.GHOST,
                small = true,
                enabled = !operating,
            )
            Spacer(Modifier.width(8.dp))
            AppButton(
                text = if (operating) tr("處理中…") else if (channel.status == 1) tr("停用") else tr("啟用"),
                onClick = { onToggle(channel) },
                kind = if (channel.status == 1) AppButtonKind.DANGER else AppButtonKind.PRIMARY,
                small = true,
                enabled = !operating,
            )
            Spacer(Modifier.width(8.dp))
            AppButton(
                text = tr("刪除"),
                onClick = { onDelete(channel) },
                kind = AppButtonKind.DANGER,
                small = true,
                enabled = !operating,
            )
        }
    }
}

/* ---------------- 本月燒錢榜（seg=令牌，/api/log/self 本月窗口聚合） ---------------- */

/**
 * 本月燒錢榜卡：每個令牌本月消費 Top 5（usd 降序 + 比例 mini bar）。
 *  - 首次載入中唔出卡（列表 skeleton 已有 loading 感）；失敗成張卡靜默收起
 *  - 已載入但 rows 空 → 顯示「本月未有令牌消費紀錄」
 *  - 抓取窗口撞分頁封頂（spendTruncated）→ 標題旁「（約）」chip
 */
@Composable
private fun SpendBoardCard(state: TokensViewModel.UiState) {
    if (!state.spendLoaded) return
    val rows = state.spendRows
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("本月燒錢榜"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MutedColor,
            )
            if (state.spendTruncated) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(InnerFillColor)
                        .border(1.dp, StrokeSoft, RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        tr("（約）"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WarnAmber,
                        style = TabularStyle,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            tr("根據本月日誌統計；超過 300 行會標明（約）。"),
            fontSize = 10.5.sp,
            color = MutedColor,
            modifier = Modifier.padding(top = 3.dp),
        )
        if (rows.isEmpty()) {
            Text(
                tr("本月未有令牌消費紀錄"),
                fontSize = 12.sp,
                color = DimColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            // rows 已按 usd 降序；第一名做滿格基準
            val maxUsd = maxOf(rows[0].usd, 1e-9)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEachIndexed { i, s ->
                    SpendBoardRow(
                        rank = i + 1,
                        name = s.name,
                        usd = s.usd,
                        calls = s.calls,
                        frac = (s.usd / maxUsd).toFloat(),
                    )
                }
            }
        }
    }
}

/** 一行：排名 + 令牌名 + 右側粗體 USD 同「N 次」；下面 Accent1 比例 mini bar */
@Composable
private fun SpendBoardRow(rank: Int, name: String, usd: Double, calls: Int, frac: Float) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rank.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) Accent1 else MutedColor,
                style = TabularStyle,
                modifier = Modifier.width(18.dp),
            )
            TokenBrandIcon(name)
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TxtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Fmt.usd(usd, 2),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TxtColor,
                    style = TabularStyle,
                    maxLines = 1,
                )
                Text(
                    "$calls ${tr("次")}",
                    fontSize = 10.sp,
                    color = MutedColor,
                    style = TabularStyle,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .padding(top = 5.dp)
                .height(5.dp)
                .fillMaxWidth()
                .background(BarTrackColor, RoundedCornerShape(50))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac.coerceIn(0.04f, 1f))
                    .height(5.dp)
                    .background(Accent1, RoundedCornerShape(50))
            )
        }
    }
}

/* ---------------- 令牌卡 ---------------- */

/**
 * H .mrow 令牌行（design-mockups/h-fintech.html）：
 * 品牌色字母 icon + 名 w600 13sp + 有效期 muted sub；右側 剩餘（粗體 tabular）／已用（muted）＋ 狀態 chip pill。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TokenCard(
    token: TokenInfo,
    qpu: Double,
    toggling: Boolean,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // GlassCard 唔支援 onLongClick → 外層 Box 統一接 tap／long-press；press scale（0.982）自己複刻返
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.982f else 1f, tween(120), label = "tokenCardPress")
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selectionMode) {
                    SelectionCheckbox(selected = selected)
                }
                TokenBrandIcon(token.name)
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            token.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TxtColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ExpiryCountdownBadge(token.expiredTime)
                    }
                    Text(
                        buildString {
                            append(tr("有效期 ")).append(expiryLabel(token.expiredTime))
                            if (token.createdTime > 0) append(tr(" · 建立於 ")).append(dateLabel(token.createdTime))
                        },
                        fontSize = 10.5.sp,
                        color = MutedColor,
                        style = TabularStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        token.remainUsd(qpu)?.let { Fmt.usd(it, 4) } ?: tr("無限制"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyanLight,
                        style = TabularStyle,
                        maxLines = 1,
                    )
                    Text(
                        tr("已用 ") + Fmt.usd(token.usedUsd(qpu), 4),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MutedColor,
                        style = TabularStyle,
                        maxLines = 1,
                    )
                    TokenStatusBadge(token.status)
                }
            }
        }
    }
}

/** 多選圓形 checkbox：選中 Accent1 實心＋白 ✓；未選 InnerFillColor＋CardStrokeColor 描邊 */
@Composable
private fun SelectionCheckbox(selected: Boolean) {
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent1 else InnerFillColor)
            .border(1.dp, if (selected) Accent1 else CardStrokeColor, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

/** 到期倒數警示徽章：<3天警告、<1天嚴重、已過期 */
@Composable
private fun ExpiryCountdownBadge(expiredTime: Long) {
    if (expiredTime <= 0) return
    val nowSec = System.currentTimeMillis() / 1000
    val diffSec = expiredTime - nowSec
    when {
        diffSec <= 0 -> {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ErrRed.copy(alpha = 0.08f))
                    .border(1.dp, ErrRed.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(tr("已過期"), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = ErrRed)
            }
        }
        diffSec < 86400 -> {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ErrRed.copy(alpha = 0.08f))
                    .border(1.dp, ErrRed.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(tr("即將過期（剩餘 1 日內）"), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = ErrRed)
            }
        }
        diffSec < 3 * 86400 -> {
            val daysLeft = kotlin.math.ceil(diffSec / 86400.0).toInt()
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(WarnAmber.copy(alpha = 0.08f))
                    .border(1.dp, WarnAmber.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(if (daysLeft == 1) tr("即將過期（剩餘 1 日內）") else "${tr("即將過期（剩餘 ")}$daysLeft${tr(" 日）")}", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = WarnAmber)
            }
        }
    }
}

/** 狀態 chip pill（上游 status 數字為準）：1=已啟用 綠 tint／2=手動停用 灰／3=自動封禁 紅 tint */
@Composable
private fun TokenStatusBadge(status: Int) {
    val (text, fg, bg, border) = when (status) {
        1 -> Quadruple(tr("已啟用"), OkGreen, OkGreen.copy(alpha = 0.08f), OkGreen.copy(alpha = 0.35f))
        2 -> Quadruple(tr("手動停用"), MutedColor, InnerFillColor, CardStrokeColor)
        3 -> Quadruple(tr("自動封禁"), ErrRed, ErrRed.copy(alpha = 0.08f), ErrRed.copy(alpha = 0.35f))
        else -> Quadruple(tr("狀態 ") + status, WarnAmber, WarnAmber.copy(alpha = 0.08f), WarnAmber.copy(alpha = 0.35f))
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/* ---------------- key 詳情 dialog ---------------- */

@Composable
private fun TokenDetailDialog(
    token: TokenInfo,
    qpu: Double,
    busy: Boolean,
    revealedKey: String?,
    revealing: Boolean,
    revealError: String?,
    onReveal: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }
    // No key inferred from a list row. Only an explicit reveal response is displayable/copyable.
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardFillColor,
        shape = RoundedCornerShape(18.dp),
        title = {
            Column {
                Text(token.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TokenStatusBadge(token.status)
                    Text(
                        tr("已用 ") + Fmt.usd(token.usedUsd(qpu), 4) + tr(" · 剩餘 ") +
                            (token.remainUsd(qpu)?.let { Fmt.usd(it, 4) } ?: tr("無限制")),
                        fontSize = 11.sp,
                        color = MutedColor,
                        style = TabularStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    tr("令牌 Key"),
                    fontSize = 11.sp,
                    color = MutedColor,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    revealedKey ?: tr("Key 尚未顯示"),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Txt2Color,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(InnerFillColor)
                        .border(1.dp, CardStrokeColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                revealError?.let { Text(tr(it), color = ErrRed, fontSize = 11.sp) }
                Text(
                    buildString {
                        append(tr("有效期 ")).append(expiryLabel(token.expiredTime))
                        if (token.createdTime > 0) append(tr(" · 建立於 ")).append(dateLabel(token.createdTime))
                    },
                    fontSize = 11.sp,
                    color = MutedColor,
                    style = TabularStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (copied) tr("已複製 ✓") else "",
                    fontSize = 11.sp,
                    color = OkGreen,
                    style = TabularStyle,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onReveal, enabled = !revealing && !busy) {
                        Text(if (revealing) tr("處理中…") else tr("顯示 Key"), color = Accent1, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = {
                        revealedKey?.let { clipboard.setText(AnnotatedString(it)); copied = true }
                    }, enabled = revealedKey != null) { Text(tr("複製"), color = Accent1, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onToggle, enabled = !busy) {
                        Text(
                            if (busy) tr("處理中…") else if (token.status == 1) tr("停用") else tr("啟用"),
                            color = if (token.status == 1) ErrRed else OkGreen,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TextButton(onClick = onEdit) { Text(tr("編輯"), color = Accent1, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onDelete) { Text(tr("刪除"), color = ErrRed, fontWeight = FontWeight.Bold) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("關閉"), color = Txt2Color) }
        },
    )
}

/* ---------------- 新增／編輯令牌 dialog ---------------- */

/**
 * 新增／編輯令牌：名稱＋額度（無限／有限額 USD）＋有效期（永不／7/30/90 日）。
 * 編輯模式唔改有效期（上游 PUT 以現值保留），改名稱同額度。
 */
@Composable
private fun TokenFormDialog(
    editing: TokenInfo?,
    qpu: Double,
    busy: Boolean,
    onSave: (name: String, remainUsd: Double?, unlimited: Boolean, expiryDays: Long,
        group: String, modelLimitsEnabled: Boolean, modelLimits: String, allowIps: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var unlimited by remember { mutableStateOf(editing?.unlimitedQuota ?: true) }
    var remainTxt by remember {
        mutableStateOf(
            editing?.takeIf { !it.unlimitedQuota }?.remainUsd(qpu)?.let { String.format("%.2f", it) } ?: ""
        )
    }
    var expiryDays by remember { mutableStateOf(-1L) }
    var group by remember { mutableStateOf(editing?.group ?: "") }
    var limitsEnabled by remember { mutableStateOf(editing?.modelLimitsEnabled ?: false) }
    var limits by remember { mutableStateOf(editing?.modelLimits ?: "") }
    var ips by remember { mutableStateOf(editing?.allowIps ?: "") }
    val remainUsd = remainTxt.toDoubleOrNull()
    val nameOk = name.isNotBlank()
    val remainOk = unlimited || (remainUsd != null && remainUsd >= 0.0)
    val defaultTokenName = tr("新增令牌")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardFillColor,
        shape = RoundedCornerShape(18.dp),
        title = { Text(if (editing == null) tr("新增令牌") else tr("編輯令牌"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("名稱（例：my-app）"), fontSize = 13.sp, color = com.newapimonitor.ui.theme.DimColor) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent1.copy(alpha = 0.55f),
                        unfocusedBorderColor = CardStrokeColor,
                        focusedContainerColor = InnerFillColor,
                        unfocusedContainerColor = InnerFillColor,
                        cursorColor = Accent1,
                        focusedTextColor = TxtColor,
                        unfocusedTextColor = TxtColor,
                    ),
                )
                Segmented(
                    options = listOf(SegOpt("unlimited", tr("無限")), SegOpt("limited", tr("有限額"))),
                    selected = if (unlimited) "unlimited" else "limited",
                    onSelect = { unlimited = it == "unlimited" },
                )
                if (!unlimited) {
                    OutlinedTextField(
                        value = remainTxt,
                        onValueChange = { remainTxt = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(tr("額度（美元）"), fontSize = 13.sp, color = com.newapimonitor.ui.theme.DimColor) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent1.copy(alpha = 0.55f),
                            unfocusedBorderColor = CardStrokeColor,
                            focusedContainerColor = InnerFillColor,
                            unfocusedContainerColor = InnerFillColor,
                            cursorColor = Accent1,
                            focusedTextColor = TxtColor,
                            unfocusedTextColor = TxtColor,
                        ),
                    )
                }
                if (editing == null) {
                    Segmented(
                        options = listOf(
                            SegOpt("-1", tr("永不")),
                            SegOpt("7", tr("7 日")),
                            SegOpt("30", tr("30 日")),
                            SegOpt("90", tr("90 日")),
                        ),
                        selected = expiryDays.toString(),
                        onSelect = { expiryDays = it.toLongOrNull() ?: -1L },
                    )
                }
                OutlinedTextField(value = group, onValueChange = { group = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text(tr("群組")) }, singleLine = true)
                Segmented(options = listOf(SegOpt("off", tr("所有模型")), SegOpt("on", tr("限制模型"))),
                    selected = if (limitsEnabled) "on" else "off", onSelect = { limitsEnabled = it == "on" })
                if (limitsEnabled) OutlinedTextField(value = limits, onValueChange = { limits = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text(tr("模型列表（逗號分隔）")) })
                OutlinedTextField(value = ips, onValueChange = { ips = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text(tr("允許 IP（逗號分隔）")) })
                if (editing == null) {
                    Text(
                        tr("提示：新令牌會即刻出現喺你嘅 NewAPI 帳號度。"),
                        fontSize = 11.sp,
                        color = MutedColor,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim().ifEmpty { defaultTokenName },
                        if (unlimited) null else remainUsd,
                        unlimited,
                        expiryDays,
                        group.trim(), limitsEnabled, limits.trim(), ips.trim(),
                    )
                },
                enabled = !busy && nameOk && remainOk,
            ) {
                Text(if (busy) tr("處理中…") else tr("儲存"), color = Accent1, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消"), color = Txt2Color) }
        },
    )
}

/* ---------------- 切換失敗錯誤條 ---------------- */

@Composable
private fun ActionErrorBar(message: String, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrRed.copy(alpha = 0.08f))
            .border(1.dp, ErrRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⚠ " + message,
                fontSize = 12.sp,
                color = ErrRed.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                tr("知道了"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Txt2Color,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/* ---------------- 批量結果摘要條 ---------------- */

/**
 * 批量操作結果：「成功 N · 失敗 M」；全成功 OkGreen tint，有失敗轉 WarnAmber；
 * 樣式跟 ActionErrorBar 同款，約 4 秒自動收起（撳一下都可以即刻收）。
 */
@Composable
private fun BatchResultBar(ok: Int, fail: Int, onDismiss: () -> Unit) {
    LaunchedEffect(ok, fail) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }
    val tint = if (fail > 0) WarnAmber else OkGreen
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("成功") + " $ok · " + tr("失敗") + " $fail",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint,
                style = TabularStyle,
                modifier = Modifier.weight(1f),
            )
            Text(
                tr("知道了"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Txt2Color,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun errorTitle(error: AppError): String = when (error.kind) {
    ErrorKind.AUTH -> tr("認證失敗")
    ErrorKind.NETWORK -> tr("網絡錯誤")
    ErrorKind.HTTP -> tr("伺服器錯誤") + " (HTTP ${error.status ?: "?"})"
    ErrorKind.PARSE -> tr("回應解析失敗")
    ErrorKind.API -> tr("API 返回錯誤")
}

@Composable
private fun errorHint(error: AppError): String = when (error.kind) {
    ErrorKind.AUTH -> tr("請檢查 Access Token 同管理員權限。")
    ErrorKind.NETWORK -> tr("請檢查網絡連線同實例網址。")
    ErrorKind.HTTP -> tr("請檢查實例網址同伺服器狀態。")
    ErrorKind.PARSE -> tr("請確認實例網址指向 NewAPI。")
    ErrorKind.API -> tr("請檢查實例設定同權限。")
}

/* ---------------- 時間工具 ---------------- */

@Composable
private fun expiryLabel(expiredTime: Long): String = when {
    expiredTime <= 0 -> tr("永不過期")
    expiredTime <= System.currentTimeMillis() / 1000 -> tr("已於 ") + dateLabel(expiredTime) + tr(" 過期")
    else -> tr("至 ") + dateLabel(expiredTime)
}

private fun dateLabel(tsSec: Long): String =
    Instant.ofEpochSecond(tsSec).atZone(ZoneId.systemDefault()).toLocalDate().toString()
