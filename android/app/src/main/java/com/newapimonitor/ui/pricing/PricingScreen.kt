package com.newapimonitor.ui.pricing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.api.OfficialModelPrice
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.ui.common.DisposableLifecycleEffect
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.tr
import java.util.Locale

@Composable
fun PricingScreen(repo: NewApiRepository, onNavigateBack: () -> Unit, bottomContentPadding: Dp = 0.dp) {
    val vm: PricingViewModel = viewModel(initializer = { PricingViewModel(repo) })
    val state by vm.state.collectAsState()
    DisposableLifecycleEffect(onStart = vm::resume, onStop = vm::pause)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("價格與分組"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                AppButton(tr("返回"), onNavigateBack, kind = AppButtonKind.GHOST, small = true)
            }
            Text(tr("站點公布的官方定價；並非日誌中觀察到的實際成本。"),
                color = MutedColor, fontSize = 11.5.sp, modifier = Modifier.padding(top = 7.dp))
            Text(tr("幣種由站點決定，API 未提供幣種資訊；不換算為美元。"),
                color = MutedColor, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
            if (!state.demo && state.source != "none" && !state.needsAccessToken) {
                AppButton(tr("重新整理"), vm::refresh, enabled = !state.loading,
                    kind = AppButtonKind.GHOST, small = true, modifier = Modifier.padding(top = 10.dp))
            }
        }
        when {
            state.demo -> MessageCard(tr("演示模式不提供官方價格；請切換至實際站點。"))
            state.source == "none" -> MessageCard(tr("未設定實例"))
            state.needsAccessToken -> MessageCard(tr("需要 Access Token"))
            else -> {
                if (state.loading) CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                GlassCard {
                    Text(tr("官方模型價格"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    state.catalogFailure?.let { Text(failureLabel(it), color = Txt2Color, fontSize = 12.sp) }
                    if (!state.loading && state.catalogFailure == null && state.catalog?.models.isNullOrEmpty()) {
                        Text(tr("站點沒有提供模型定價資料"), color = MutedColor, fontSize = 12.sp)
                    }
                    state.catalog?.models?.sortedBy { it.name }?.forEach { model -> ModelRow(model) }
                }
                GlassCard {
                    Text(tr("可用分組"), color = TxtColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    state.groupsFailure?.let { Text(failureLabel(it), color = Txt2Color, fontSize = 12.sp) }
                    if (!state.loading && state.groupsFailure == null && state.userGroups.isEmpty()) {
                        Text(tr("站點沒有提供可用分組"), color = MutedColor, fontSize = 12.sp)
                    }
                    state.userGroups.forEach { group ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(group.label, color = TxtColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            group.ratio?.let { Text("×${decimal(it)}", color = Txt2Color, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: OfficialModelPrice) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Text(model.name, color = TxtColor, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        val parts = listOfNotNull(
            model.modelRatio?.let { tr("模型倍率") + " ×${decimal(it)}" },
            model.completionRatio?.let { tr("輸出倍率") + " ×${decimal(it)}" },
            model.fixedPrice?.let { tr("固定價格（站點幣種）") + " ${decimal(it)}" },
        )
        Text(if (parts.isEmpty()) tr("未公布價格") else parts.joinToString(" · "),
            color = MutedColor, fontSize = 11.sp)
    }
}

@Composable
private fun MessageCard(message: String) { GlassCard { Text(message, color = Txt2Color, fontSize = 12.sp) } }

@Composable
private fun failureLabel(failure: PricingFailure): String = when (failure) {
    PricingFailure.AUTH -> tr("認證失敗（401）：檢查 Access Token")
    PricingFailure.FORBIDDEN -> tr("站點拒絕存取（403）：沒有此資料的權限")
    PricingFailure.MISSING -> tr("站點不支援此端點（404）")
    PricingFailure.NETWORK -> tr("無法取得站點資料，請檢查連線")
    PricingFailure.INVALID -> tr("站點回應格式不正確")
}

private fun decimal(value: Double): String = String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
