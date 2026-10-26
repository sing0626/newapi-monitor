package com.newapimonitor.ui.billing

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.domain.TopUpRecord
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.Badge
import com.newapimonitor.ui.components.BadgeTone
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.theme.DimColor
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.TabularStyle
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.tr
import com.newapimonitor.util.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 帳戶與服務（設定內二級頁）：
 *  - 訂閱／套餐（GET /api/subscription/self）：方案名、狀態、到期日；站點不支援（404）出明確狀態
 *  - 充值方式（GET /api/user/topup/info）：最低充值、線上／兌換碼開關、付款方法 chips
 *  - 充值記錄（GET /api/user/topup/self）：時間／金額（站點幣種）／狀態
 *  - 「前往站點充值」只開使用者設定站點嘅官方網頁；App 內不做任何付款動作
 */
@Composable
fun BillingScreen(repo: NewApiRepository, onNavigateBack: () -> Unit, bottomContentPadding: Dp = 0.dp) {
    val vm: BillingViewModel = viewModel(initializer = { BillingViewModel(repo) })
    val state by vm.state.collectAsState()
    DisposableLifecycleEffect(onStart = vm::resume, onStop = vm::pause)
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("帳戶與服務"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                AppButton(tr("返回"), onNavigateBack, kind = AppButtonKind.GHOST, small = true)
            }
            Text(tr("自身訂閱、充值方式與充值記錄；金額單位由站點決定，唔會換算成美元。"),
                color = MutedColor, fontSize = 11.5.sp, modifier = Modifier.padding(top = 7.dp))
            Text(tr("App 內不做任何付款；充值一律跳到你設定嘅站點官方網頁處理。"),
                color = MutedColor, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
            if (!state.demo && state.source != "none" && !state.needsAccessToken) {
                AppButton(tr("重新整理"), vm::refresh, enabled = !state.loading,
                    kind = AppButtonKind.GHOST, small = true, modifier = Modifier.padding(top = 10.dp))
            }
        }
        when {
            state.source == "none" -> MessageCard(tr("未設定實例"))
            state.needsAccessToken -> MessageCard(tr("需要 Access Token"))
            else -> {
                SubscriptionCard(state)
                TopUpInfoCard(state,
                    onGoSite = {
                        state.siteUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    })
                TopUpRecordsCard(state)
            }
        }
    }
}

/* ---------------- 訂閱／套餐 ---------------- */

@Composable
private fun SubscriptionCard(state: BillingState) {
    GlassCard {
        Text(tr("訂閱與套餐"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        state.subscriptionFailure?.let {
            Text(failureLabel(it), color = Txt2Color, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        val sub = state.subscription
        if (!state.loading && sub == null) {
            Text(tr("站點未回傳訂閱資料"), color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        if (sub == null) return@GlassCard
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sub.planName.ifBlank { tr("免費／無套餐") },
                    color = TxtColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (sub.expireTime > 0) tr("到期") + " " + fmtDate(sub.expireTime) else tr("無固定到期日"),
                    color = MutedColor, fontSize = 11.sp, style = TabularStyle,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            StatusBadge(sub.status)
        }
        if (state.demo) {
            Text(tr("演示模擬數據"), color = DimColor, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/* ---------------- 充值方式 ---------------- */

@Composable
private fun TopUpInfoCard(state: BillingState, onGoSite: () -> Unit) {
    GlassCard {
        Text(tr("充值方式"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        state.topUpInfoFailure?.let {
            Text(failureLabel(it), color = Txt2Color, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        val info = state.topUpInfo
        if (!state.loading && info == null) {
            Text(tr("站點未回傳充值方式"), color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        if (info == null) return@GlassCard
        Column(Modifier.padding(top = 6.dp)) {
            InfoRow(tr("線上充值"), if (info.enableOnlineTopUp) tr("站點已開啟") else tr("站點未開啟"))
            InfoRow(tr("兌換碼充值"), if (info.enableExchangeTopUp) tr("站點已開啟") else tr("站點未開啟"))
            info.minTopUp?.takeIf { it > 0.0 }?.let {
                InfoRow(tr("最低充值"), decimal(it) + "（" + tr("站點幣種") + "）")
            }
            if (info.payMethods.isNotEmpty()) {
                Text(
                    tr("付款方法") + "：" + info.payMethods.joinToString("、"),
                    color = Txt2Color, fontSize = 11.5.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (info.enableOnlineTopUp && state.siteUrl != null) {
                Text(
                    tr("App 內不代付款；線上充值會打開站點官方網頁，完成後返嚟撳「重新整理」核對。"),
                    color = MutedColor, fontSize = 10.5.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                AppButton(
                    text = tr("前往站點充值") + " →",
                    onClick = onGoSite,
                    kind = AppButtonKind.GHOST,
                    small = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/* ---------------- 充值記錄 ---------------- */

@Composable
private fun TopUpRecordsCard(state: BillingState) {
    GlassCard {
        Text(tr("充值記錄"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        state.topUpsFailure?.let {
            Text(failureLabel(it), color = Txt2Color, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        val records = state.topUps
        if (!state.loading && records == null) {
            Text(tr("站點未回傳充值記錄"), color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        if (records.isNullOrEmpty()) {
            Text(tr("暫無充值記錄"), color = MutedColor, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            return@GlassCard
        }
        Column(Modifier.padding(top = 4.dp)) {
            records.forEachIndexed { i, r ->
                RecordRow(r)
                if (i != records.lastIndex) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp)
                            .background(HairlineColor)
                    )
                }
            }
        }
        if (state.demo) {
            Text(tr("演示模擬數據"), color = DimColor, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RecordRow(r: TopUpRecord) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                decimal(r.amount) + "（" + tr("站點幣種") + "）",
                color = TxtColor, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, style = TabularStyle,
            )
            Text(
                listOfNotNull(
                    r.createTime.takeIf { it > 0 }?.let { fmtDate(it) },
                    r.payType.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                color = MutedColor, fontSize = 10.5.sp, style = TabularStyle,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        StatusBadge(r.status)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, tone) = when (status.lowercase(Locale.US)) {
        "success", "paid", "completed", "active" -> tr("已完成") to BadgeTone.OK
        "pending", "processing" -> tr("處理中") to BadgeTone.KEY
        "" -> tr("未知") to BadgeTone.NEUTRAL
        else -> tr("未完成") to BadgeTone.NEUTRAL
    }
    Badge(label, tone = tone)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MutedColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            value, color = TxtColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, style = TabularStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageCard(message: String) { GlassCard { Text(message, color = Txt2Color, fontSize = 12.sp) } }

@Composable
private fun failureLabel(failure: BillingFailure): String = when (failure) {
    BillingFailure.AUTH -> tr("認證失敗（401）：檢查 Access Token")
    BillingFailure.FORBIDDEN -> tr("站點拒絕存取（403）：沒有此資料的權限")
    BillingFailure.MISSING -> tr("站點不支援此端點（404）")
    BillingFailure.NETWORK -> tr("無法取得站點資料，請檢查連線")
    BillingFailure.INVALID -> tr("站點回應格式不正確")
}

private fun decimal(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private fun fmtDate(tsSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(tsSec * 1000L))
