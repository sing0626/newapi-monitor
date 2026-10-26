package com.newapimonitor.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newapimonitor.data.repo.NewApiRepository
import com.newapimonitor.data.backup.BackupManager
import com.newapimonitor.domain.Instance
import com.newapimonitor.ui.components.AppButton
import com.newapimonitor.ui.components.AppButtonKind
import com.newapimonitor.ui.components.Badge
import com.newapimonitor.ui.components.BadgeTone
import com.newapimonitor.ui.components.GlassCard
import com.newapimonitor.ui.components.SegOpt
import com.newapimonitor.ui.components.Segmented
import com.newapimonitor.ui.components.StatusDot
import com.newapimonitor.ui.components.SwitchRow
import com.newapimonitor.ui.theme.Accent1
import com.newapimonitor.ui.theme.CardFillColor
import com.newapimonitor.ui.theme.CardStrokeColor
import com.newapimonitor.ui.theme.ErrRed
import com.newapimonitor.ui.theme.HairlineColor
import com.newapimonitor.ui.theme.InnerFillColor
import com.newapimonitor.ui.theme.modelBrandColor
import com.newapimonitor.ui.theme.MutedColor
import com.newapimonitor.ui.theme.OkGreen
import com.newapimonitor.ui.theme.StrokeSoft
import com.newapimonitor.ui.theme.Txt2Color
import com.newapimonitor.ui.theme.TxtColor
import com.newapimonitor.ui.theme.tr
import com.newapimonitor.data.store.Appearance
import com.newapimonitor.data.store.AppearanceStore
import com.newapimonitor.data.store.DashboardLayoutStore
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定：一般（刷新間隔＋演示模式）、警報通知（門檻＋倍數＋冷卻）、生物辨識鎖定、
 * 實例 CRUD／切換、實例表單＋測試連接、備份與還原（SAF 導出／匯入 JSON）、安全說明＋清除數據。
 */
@Composable
fun SettingsScreen(
    repo: NewApiRepository,
    onSetDemo: (Boolean) -> Unit,
    onNavigateSpeedTest: () -> Unit = {},
    onNavigatePricing: () -> Unit = {},
    onNavigateBilling: () -> Unit = {},
    onNavigateAdmin: () -> Unit = {},
    onNavigateMonitor: () -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val vm: SettingsViewModel = viewModel(initializer = { SettingsViewModel(repo) })
    val instances by vm.instances.collectAsState()
    val settings by vm.settings.collectAsState()
    val alerts by vm.alerts.collectAsState()
    LaunchedEffect(Unit) { vm.refreshAlertSettings() }
    val appLock by vm.appLock.collectAsState()
    val hiddenCards by vm.hiddenCards.collectAsState()
    val test by vm.test.collectAsState()

    val context = LocalContext.current
    val appearanceStore = remember(context) { AppearanceStore(context.applicationContext) }
    val appearance by appearanceStore.appearance.collectAsState(initial = Appearance())
    val appearanceScope = rememberCoroutineScope()
    var notifGranted by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notifGranted = isGranted || NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    var formOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var fName by remember { mutableStateOf("") }
    var fUrl by remember { mutableStateOf("") }
    var fToken by remember { mutableStateOf("") }
    var fKey by remember { mutableStateOf("") }
    var fQpu by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }
    var confirmSaveNoTokens by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Instance?>(null) }
    var confirmWipe1 by remember { mutableStateOf(false) }
    var confirmWipe2 by remember { mutableStateOf(false) }

    fun openForm(inst: Instance?) {
        editingId = inst?.id
        fName = inst?.name ?: ""
        fUrl = inst?.baseUrl ?: ""
        fToken = inst?.accessToken ?: ""
        fKey = inst?.apiKey ?: ""
        fQpu = inst?.quotaPerUnit?.toString() ?: ""
        urlError = false
        formOpen = true
    }

    fun closeForm() {
        formOpen = false
        editingId = null
    }

    fun doSave() {
        vm.save(
            InstanceForm(fName, fUrl, fToken, fKey, fQpu),
            editingId,
        ) { closeForm() }
    }

    fun save() {
        urlError = fUrl.trim().isEmpty()
        if (urlError) return
        if (fToken.isBlank() && fKey.isBlank()) {
            confirmSaveNoTokens = true
            return
        }
        doSave()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /* ---------- 一般設定 ---------- */
        GlassCard {
            Text(tr("一般設定"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Column(Modifier.padding(top = 12.dp)) {
                Text(tr("刷新間隔"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Txt2Color)
                Segmented(
                    options = com.newapimonitor.domain.AppSettings.VALID_INTERVALS.map { SegOpt(it.toString(), "${it}s") },
                    selected = settings.intervalSec.toString(),
                    onSelect = { vm.setIntervalSec(it.toInt()) },
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "儀表板每次輪詢嘅間隔；App 轉背景（ON_STOP）時自動暫停。",
                    fontSize = 11.sp,
                    color = MutedColor,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HDivider(Modifier.padding(vertical = 6.dp))
            SwitchRow(
                label = tr("演示模式"),
                sub = tr("完全離線嘅仿真數據，載入即有，唔會請求任何站點。"),
                checked = settings.demo,
                onCheckedChange = onSetDemo,
            )
        }

        GlassCard {
            Text(tr("外觀與語言"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Text(tr("語言"), fontSize = 12.sp, color = Txt2Color, modifier = Modifier.padding(top = 12.dp))
            Segmented(
                options = listOf(SegOpt("yue", "粵語"), SegOpt("zh-TW", "繁體"), SegOpt("zh-CN", "简体"), SegOpt("en", "English")),
                selected = appearance.language,
                onSelect = { appearanceScope.launch { appearanceStore.setLanguage(it) } },
                modifier = Modifier.padding(top = 6.dp),
            )
            HDivider(Modifier.padding(top = 4.dp))
            Text(tr("主題"), fontSize = 12.sp, color = Txt2Color, modifier = Modifier.padding(top = 12.dp))
            Segmented(
                options = listOf(SegOpt("dark", tr("深色")), SegOpt("oled", "OLED"), SegOpt("light", tr("淺色"))),
                selected = appearance.mode,
                onSelect = { appearanceScope.launch { appearanceStore.setMode(it) } },
                modifier = Modifier.padding(top = 6.dp),
            )
            HDivider(Modifier.padding(top = 4.dp))
            Text(tr("強調色"), fontSize = 12.sp, color = Txt2Color, modifier = Modifier.padding(top = 12.dp))
            Segmented(
                options = listOf(SegOpt("purple", tr("紫色")), SegOpt("blue", tr("藍色")), SegOpt("green", tr("綠色")), SegOpt("orange", tr("橙色"))),
                selected = appearance.accent,
                onSelect = { appearanceScope.launch { appearanceStore.setAccent(it) } },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        /* ---------- 儀表板卡片 ---------- */
        GlassCard {
            Text(tr("儀表板卡片"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Text(
                tr("揀選儀表板要顯示嘅卡片；收埋咗隨時可以開返。"),
                fontSize = 11.5.sp,
                color = MutedColor,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Column(Modifier.padding(top = 4.dp)) {
                DashboardLayoutStore.HIDEABLE_IDS.forEach { id ->
                    SwitchRow(
                        label = tr(dashboardCardLabels[id] ?: id),
                        sub = dashboardCardSubs[id]?.let { tr(it) } ?: "",
                        checked = id !in hiddenCards,
                        onCheckedChange = { visible -> vm.setCardHidden(id, !visible) },
                    )
                }
            }
        }

        GlassCard {
            Text(tr("消費報告與比較"), color = TxtColor, fontWeight = FontWeight.Bold)
            Text(tr("按日期或歷史月份查詢消費，追查模型及令牌成本。多實例、預算、測速及通知可由總覽開啟。"), color = MutedColor)
            AppButton(tr("開啟消費報告") + " →", onNavigateMonitor, small = true, modifier = Modifier.padding(top = 10.dp))
        }

        /* ---------- 模型測速探針 ---------- */
        GlassCard {
            Text(tr("模型探針"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Text(
                tr("手動向選定模型發送最短 chat completion；可能收費，唔會自動測速。"),
                fontSize = 11.5.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 6.dp),
            )
            AppButton(
                text = tr("開啟模型探針") + " →",
                onClick = onNavigateSpeedTest,
                kind = AppButtonKind.GHOST,
                small = true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        /* ---------- 官方價格與分組（設定內次頁，不佔底部導覽 tab） ---------- */
        GlassCard {
            Text(tr("價格與分組"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Text(
                tr("查看站點公布的官方模型價格與可用分組；不同於已發生的消費。"),
                fontSize = 11.5.sp,
                color = MutedColor,
                modifier = Modifier.padding(top = 6.dp),
            )
            AppButton(
                text = tr("開啟價格與分組") + " →",
                onClick = onNavigatePricing,
                kind = AppButtonKind.GHOST,
                small = true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        /* ---------- 帳戶與服務（訂閱／充值方式／充值記錄二級頁） ---------- */
        GlassCard {
            Text(tr("帳戶與服務"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Text(
                tr("查看自身訂閱、站點充值方式與充值記錄；充值一律跳到站點官方網頁處理。"),
                fontSize = 11.5.sp,
                color = MutedColor,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            AppButton(
                text = tr("開啟帳戶與服務") + " →",
                onClick = onNavigateBilling,
                kind = AppButtonKind.GHOST,
                small = true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        /* ---------- 管理員專區（唯讀；權限按伺服器實際回應漸進開放） ---------- */
        GlassCard {
            Text(tr("管理員專區"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            Text(
                tr("唯讀監察全站使用者與日誌；要管理員權限嘅 Access Token，冇權限會清楚提示。"),
                fontSize = 11.5.sp,
                color = MutedColor,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            AppButton(
                text = tr("開啟管理員專區") + " →",
                onClick = onNavigateAdmin,
                kind = AppButtonKind.GHOST,
                small = true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        /* ---------- 警報通知 ---------- */
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("警報通知"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor, modifier = Modifier.weight(1f))
                Badge(
                    text = if (notifGranted) tr("系統通知已授權") else tr("未獲通知權限"),
                    tone = if (notifGranted) BadgeTone.OK else BadgeTone.KEY,
                )
            }
            SwitchRow(
                label = tr("啟用背景檢查"),
                sub = tr("每 15 分鐘 WorkManager 在背景檢查餘額與消費異常（演示模式下跳過）。"),
                checked = alerts.enabled,
                onCheckedChange = { en ->
                    vm.setAlertEnabled(en)
                    if (en && !notifGranted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                },
            )

            if (alerts.enabled) {
                HDivider(Modifier.padding(top = 4.dp))
                Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var balStr by remember(alerts.balanceBelowUsd) {
                        mutableStateOf(alerts.balanceBelowUsd?.toString() ?: "")
                    }
                    Field(
                        label = "餘額低於門檻（USD · 留空關閉）",
                        value = balStr,
                        onValueChange = { s ->
                            balStr = s
                            val v = s.toDoubleOrNull()?.takeIf { it >= 0 }
                            vm.setAlertBalanceBelow(v)
                        },
                        placeholder = "例：5.0",
                        hint = "當前餘額跌穿此值且有武裝觸發時通知。",
                        keyboardType = KeyboardType.Decimal,
                    )

                    var spendStr by remember(alerts.dailySpendAboveUsd) {
                        mutableStateOf(alerts.dailySpendAboveUsd?.toString() ?: "")
                    }
                    Field(
                        label = "今日消費上限（USD · 留空關閉）",
                        value = spendStr,
                        onValueChange = { s ->
                            spendStr = s
                            val v = s.toDoubleOrNull()?.takeIf { it > 0 }
                            vm.setAlertDailySpendAbove(v)
                        },
                        placeholder = "例：10.0",
                        hint = "今日累計消費超出此值時觸發警報。",
                        keyboardType = KeyboardType.Decimal,
                    )

                    var monthStr by remember(alerts.monthlyBudgetUsd) {
                        mutableStateOf(alerts.monthlyBudgetUsd?.toString() ?: "")
                    }
                    Field(
                        label = tr("月度預算（USD · 留空關閉）"),
                        value = monthStr,
                        onValueChange = { text ->
                            monthStr = text
                            vm.setAlertMonthlyBudget(text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 })
                        },
                        placeholder = "100.0",
                        hint = tr("本月消費達預算 90% 及 100% 時分別通知，每月各一次。"),
                        keyboardType = KeyboardType.Decimal,
                    )
                    SwitchRow(
                        label = tr("錯誤率通知"),
                        sub = tr("今日完整日誌中至少 3 次錯誤、錯誤率達 10% 時通知。"),
                        checked = alerts.notifyErrorRate,
                        onCheckedChange = vm::setAlertErrorRate,
                    )
                    SwitchRow(
                        label = tr("令牌到期通知"),
                        sub = tr("令牌到期前不足 3 日、不足 1 日及過期時通知。"),
                        checked = alerts.notifyTokenExpiry,
                        onCheckedChange = vm::setAlertTokenExpiry,
                    )

                    Column {
                        Text("消費飆升倍數（SPIKE）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Txt2Color)
                        val spikeOpts = listOf(
                            SegOpt("0.0", tr("關閉")),
                            SegOpt("2.0", "2x"),
                            SegOpt("3.0", "3x"),
                            SegOpt("5.0", "5x"),
                        )
                        val currentSpike = if (alerts.spikeMultiplier <= 0) "0.0" else "${alerts.spikeMultiplier}"
                        Segmented(
                            options = spikeOpts,
                            selected = if (spikeOpts.any { it.value == currentSpike }) currentSpike else "3.0",
                            onSelect = { vm.setAlertSpikeMultiplier(it.toDouble()) },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            "今日推算消費速度高於過去 7 日均值倍數時報警。",
                            fontSize = 11.sp,
                            color = MutedColor,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Column {
                        Text("冷卻間隔", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Txt2Color)
                        val coolOpts = listOf(
                            SegOpt("15", "15分"),
                            SegOpt("30", "30分"),
                            SegOpt("60", "1小時"),
                            SegOpt("120", "2小時"),
                        )
                        Segmented(
                            options = coolOpts,
                            selected = alerts.cooldownMinutes.toString(),
                            onSelect = { vm.setAlertCooldownMinutes(it.toInt()) },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            "同類警報在冷卻期間不會重複轟炸。",
                            fontSize = 11.sp,
                            color = MutedColor,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    SwitchRow(
                        label = tr("常駐通知"),
                        sub = tr("喺通知欄常駐顯示餘額同今日消費，鎖屏都見到；可隨時關閉。"),
                        checked = alerts.persistentStatus,
                        onCheckedChange = { enabled ->
                            vm.setAlertPersistent(enabled)
                            if (enabled && Build.VERSION.SDK_INT >= 33 && !notifGranted) {
                                permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    SwitchRow(
                        label = "令牌封禁通知",
                        sub = "偵測到上游令牌被自動封禁（status 3）時發出警告通知。",
                        checked = alerts.notifyTokenBanned,
                        onCheckedChange = { vm.setAlertNotifyTokenBanned(it) },
                    )
                }
            }
        }

        /* ---------- 應用鎖定（生物辨識） ---------- */
        GlassCard {
            Text(tr("安全鎖定"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor)
            SwitchRow(
                label = tr("生物辨識鎖定"),
                sub = tr("開啟後每次返回 App（Resume）均須透過指紋、人臉或裝置密碼驗證解鎖。"),
                checked = appLock,
                onCheckedChange = { vm.setAppLock(it) },
            )
        }

        /* ---------- 實例列表 ---------- */
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("實例"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = TxtColor, modifier = Modifier.weight(1f))
                AppButton(tr("＋ 新增"), { openForm(null) }, kind = AppButtonKind.PRIMARY, small = true)
            }
            if (instances.isEmpty()) {
                Text(
                    "仲未有任何實例，點「＋ 新增」開始。",
                    fontSize = 12.sp,
                    color = MutedColor,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    instances.forEach { inst ->
                        val active = settings.activeId == inst.id
                        InstanceCard(
                            inst = inst,
                            active = active,
                            onUse = { vm.setActive(inst.id) },
                            onEdit = { openForm(inst) },
                            onDelete = { confirmDelete = inst },
                        )
                    }
                }
            }
        }

        /* ---------- 實例表單 ---------- */
        if (formOpen) {
            GlassCard {
                Text(
                    if (editingId != null) "編輯實例" else "新增實例",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TxtColor,
                )
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        label = tr("名稱"),
                        value = fName,
                        onValueChange = { fName = it },
                        placeholder = "例：My NewAPI",
                        hint = null,
                        ime = ImeAction.Next,
                    )
                    Field(
                        label = "Base URL",
                        value = fUrl,
                        onValueChange = { fUrl = it; urlError = false },
                        placeholder = "https://your-newapi.example.com",
                        hint = "站點根路徑，唔好帶 /v1 或 /api 後綴。留空會自動補 https://。",
                        ime = ImeAction.Next,
                        keyboardType = KeyboardType.Uri,
                        isError = urlError,
                        errorText = if (urlError) "請填 Base URL" else null,
                    )
                    Field(
                        label = "Access Token（管理 API · 選填）",
                        value = fToken,
                        onValueChange = { fToken = it },
                        placeholder = "系統訪問令牌",
                        hint = "NewAPI 控制台 → 個人設置 → 生成系統訪問令牌。用嚟查 /api/user/self、/api/log/self、模型列表。",
                        password = true,
                        ime = ImeAction.Next,
                    )
                    Field(
                        label = "API Key（計費 API · 選填）",
                        value = fKey,
                        onValueChange = { fKey = it },
                        placeholder = "sk-…",
                        hint = "sk- 開頭嘅令牌 key，用嚟查 /v1/dashboard/billing/* 總額。",
                        password = true,
                        ime = ImeAction.Next,
                    )
                    Field(
                        label = "QuotaPerUnit（進階 · 選填）",
                        value = fQpu,
                        onValueChange = { fQpu = it },
                        placeholder = "預設 500000（1 美元 = 500,000 quota）",
                        hint = "只有站點改過 QuotaPerUnit 先要填；留空會自動讀 /api/status。",
                        keyboardType = KeyboardType.Decimal,
                        ime = ImeAction.Done,
                    )
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(tr("儲存"), { save() }, kind = AppButtonKind.PRIMARY)
                    AppButton(tr("測試連接"), { vm.testConnection(InstanceForm(fName, fUrl, fToken, fKey, fQpu)) })
                    AppButton(tr("取消"), { closeForm() })
                }
                TestOutput(test)
            }
        }

        BackupCard(repo, appearance, alerts, vm)

        /* ---------- 安全說明 ---------- */
        GlassCard {
            Text(tr("安全說明"), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, color = ErrRed)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("• 令牌只存本機 DataStore，唔會傳去任何第三方伺服器；API 請求直接由 App 發去你填嘅 Base URL。", style = noteStyle())
                Text("• 建議喺 NewAPI 建立專用低額度令牌做監察，唔好用管理員主 key。", style = noteStyle())
                Text("• 原生 App 冇瀏覽器 CORS 限制，/api/* 管理端點都可以直接查詢；站點若設咗 WAF／防火牆，請放行本 App。", style = noteStyle())
            }
            AppButton(
                "清除本機所有數據",
                { confirmWipe1 = true },
                kind = AppButtonKind.DANGER,
                small = true,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    /* ---------- 對話框 ---------- */
    if (confirmSaveNoTokens) {
        ConfirmDialog(
            title = "兩種令牌都未填",
            text = "儲存後儀表板無數據可顯示。照樣儲存？",
            confirmText = "照樣儲存",
            onConfirm = {
                confirmSaveNoTokens = false
                doSave()
            },
            onDismiss = { confirmSaveNoTokens = false },
        )
    }
    confirmDelete?.let { inst ->
        ConfirmDialog(
            title = "刪除實例",
            text = "刪除實例「${inst.name}」？此操作唔可復原。",
            confirmText = tr("刪除"),
            danger = true,
            onConfirm = {
                vm.deleteInstance(inst.id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
    if (confirmWipe1) {
        ConfirmDialog(
            title = tr("清除本機所有數據"),
            text = "確定清除本機所有實例、令牌同設定？",
            confirmText = "下一步",
            danger = true,
            onConfirm = {
                confirmWipe1 = false
                confirmWipe2 = true
            },
            onDismiss = { confirmWipe1 = false },
        )
    }
    if (confirmWipe2) {
        ConfirmDialog(
            title = "最後確認",
            text = "此操作唔可復原，所有實例同令牌會被永久刪除。",
            confirmText = "全部清除",
            danger = true,
            onConfirm = {
                confirmWipe2 = false
                vm.wipeAll()
                closeForm()
            },
            onDismiss = { confirmWipe2 = false },
        )
    }
}

/** 儀表板卡 id → 設定頁標籤（tr key；未收錄嘅 key tr 會 fallback 原文） */
private val dashboardCardLabels = mapOf(
    "status" to "狀態列",
    "instances" to "全部實例",
    "budget" to "月度預算",
    "report" to "月度報告",
    "week" to "7 日消費",
    "history" to "餘額歷史",
    "models" to "模型調用",
    "cost" to "模型性價比",
    "heatmap" to "時段熱力圖",
    "stability" to "穩定性",
    "feed" to "即時流水",
)

/** 各卡一句補充說明（同檔其餘 hardcoded yue 文案一致） */
private val dashboardCardSubs = mapOf(
    "status" to "實例連線狀態同輪詢倒數。",
    "instances" to "多實例餘額總覽，撳一下可以切換實例。",
    "budget" to "本月消費預算進度環。",
    "report" to "月度報告入口：消費日曆＋月度統計。",
    "week" to "過去 7 日每日消費柱狀圖。",
    "history" to "餘額趨勢（即時／24 小時／7 日／30 日）。",
    "models" to "今日模型調用分布與 Top 5。",
    "cost" to "本月每百萬 token 平均成本排行。",
    "heatmap" to "今月各時段消費熱力分佈。",
    "stability" to "今日請求錯誤統計。",
    "feed" to "最近調用即時流水。",
)

private fun noteStyle(): TextStyle = TextStyle(fontSize = 12.sp, color = MutedColor, lineHeight = 19.sp)

/** 髮絲級分隔線（H .mrow border-bottom 同款，design-mockups/h-fintech.html） */
@Composable
private fun HDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HairlineColor)
    )
}

/** 備份狀態行（tr 喺渲染時先翻譯，callback 入面唔可以 call @Composable tr） */
private sealed interface BackupStatus {
    object ExportOk : BackupStatus
    object ExportFail : BackupStatus
    data class ImportOk(val count: Int) : BackupStatus
    object ImportFail : BackupStatus
    object ImportBadFormat : BackupStatus
}

/**
 * H .mrow 實例行（design-mockups/h-fintech.html）：
 * 品牌色圓角方塊字母 icon + 名 w600 13sp + baseUrl mono muted sub；
 * 右側使用中狀態（綠點 pulse + accent pill）；憑證徽章與 設為使用中／編輯／刪除 handlers 完全不變。
 */
@Composable
private fun InstanceCard(
    inst: Instance,
    active: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InstanceIcon(inst.name)
            Column(Modifier.weight(1f)) {
                Text(
                    inst.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TxtColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    inst.baseUrl.ifEmpty { "（未填 Base URL）" },
                    fontSize = 10.5.sp,
                    color = MutedColor,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (active) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(OkGreen, pulse = true)
                    Badge(tr("使用中"), tone = BadgeTone.ACTIVE)
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (inst.accessToken.isNotBlank()) Badge("Access Token ✓", tone = BadgeTone.OK)
            else Badge("無 Access Token")
            if (inst.apiKey.isNotBlank()) Badge("API Key ✓", tone = BadgeTone.KEY)
            else Badge("無 API Key")
            inst.quotaPerUnit?.let { Badge("QPU " + com.newapimonitor.util.Fmt.num(it)) }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!active) AppButton(tr("設為使用中"), onUse, small = true)
            AppButton(tr("編輯"), onEdit, small = true)
            AppButton(tr("刪除"), onDelete, kind = AppButtonKind.DANGER, small = true)
        }
    }
}

/** 實例圓角方塊字母 icon（H mockup .mic：26dp 方角 8，modelBrandColor 品牌色） */
@Composable
private fun InstanceIcon(name: String) {
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

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    hint: String?,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Done,
    isError: Boolean = false,
    errorText: String? = null,
) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Txt2Color)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            placeholder = { Text(placeholder, fontSize = 13.5.sp, color = com.newapimonitor.ui.theme.DimColor, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            isError = isError,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ime),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent1.copy(alpha = 0.55f),
                unfocusedBorderColor = StrokeSoft,
                focusedContainerColor = InnerFillColor,
                unfocusedContainerColor = InnerFillColor,
                cursorColor = Accent1,
                focusedTextColor = TxtColor,
                unfocusedTextColor = TxtColor,
            ),
        )
        if (errorText != null) {
            Text(errorText, fontSize = 11.sp, color = ErrRed, modifier = Modifier.padding(top = 3.dp))
        } else if (hint != null) {
            Text(hint, fontSize = 11.sp, color = MutedColor, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 測試連接輸出（.test-out：mono、逐行 ✓/✗/·、ok/fail 左緣色條） */
@Composable
private fun TestOutput(test: TestUi) {
    if (test.running) {
        Text(
            "測試中…",
            fontSize = 11.sp,
            color = MutedColor,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    if (test.lines.isEmpty()) return
    val ok = test.ok == true
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(InnerFillColor)
            .border(1.dp, CardStrokeColor, shape)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Column {
            test.lines.forEach { line ->
                val prefix = when (line.t) {
                    "ok" -> "✓ "
                    "err" -> "✗ "
                    else -> "· "
                }
                val color = when (line.t) {
                    "ok" -> OkGreen.copy(alpha = 0.85f)
                    "err" -> ErrRed.copy(alpha = 0.8f)
                    else -> MutedColor
                }
                Text(
                    prefix + line.s,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                )
            }
            // ok/fail 狀態邊色提示
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ok) OkGreen.copy(alpha = 0.4f) else ErrRed.copy(alpha = 0.4f))
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(text, fontSize = 13.5.sp, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) ErrRed else Accent1, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消"), color = Txt2Color) }
        },
        containerColor = CardFillColor,
        shape = RoundedCornerShape(18.dp),
    )
}
