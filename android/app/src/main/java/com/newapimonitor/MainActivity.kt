package com.newapimonitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import com.newapimonitor.data.store.Appearance
import com.newapimonitor.ui.TAB_ALERTS
import com.newapimonitor.ui.TAB_REPORT
import com.newapimonitor.ui.TAB_STREAM
import com.newapimonitor.ui.TAB_DASHBOARD
import com.newapimonitor.ui.TAB_SPEEDTEST
import com.newapimonitor.ui.TAB_TOKENS
import com.newapimonitor.ui.theme.LocalLanguage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.newapimonitor.ui.AppRoot
import com.newapimonitor.ui.theme.NewApiMonitorTheme
import kotlinx.coroutines.launch

/**
 * NewAPI Monitor — 原生 Android 版（Kotlin + Jetpack Compose + Material 3）。
 * 繼承 FragmentActivity 以支援 androidx.biometric.BiometricPrompt 應用安全鎖定。
 * edge-to-edge + 深色 system bars；內容由 Compose 全權接管。
 */
class MainActivity : FragmentActivity() {

    companion object {
        /** 動態捷徑／磁貼帶嚟嘅 tab 目標 extra（值係 TAB_DASHBOARD／TAB_TOKENS／TAB_SPEEDTEST） */
        const val EXTRA_TAB = "com.newapimonitor.extra.TAB"

        /**
         * 待跳轉 tab：onCreate／onNewIntent 從 intent 讀入，AppRoot 首次組合消費一次即清。
         * mutableStateOf 等已組合緊嘅 AppRoot 可以用 snapshotFlow 即場跟跳（熱啟動深入連結）。
         */
        var pendingTab by mutableStateOf<String?>(null)
    }

    // null means the persisted policy has not been read yet: never compose AppRoot in that state.
    private var isLockedState by mutableStateOf<Boolean?>(null)
    private var promptRunning = false
    private var lockCheckGeneration = 0
    private var activePrompt: BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        pendingTab = validTab(intent)
        val app = application as NewApiMonitorApp
        setContent {
            val appearance by app.appearanceStore.appearance.collectAsState(initial = Appearance())
            SideEffect {
                val light = appearance.mode == "light"
                window.decorView.systemUiVisibility = if (light) {
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else 0
            }
            CompositionLocalProvider(LocalLanguage provides appearance.language) {
                NewApiMonitorTheme(appearance) {
                    // Never instantiate sensitive screens or their ViewModels until the policy is known.
                    isLockedState?.let { locked ->
                        AppRoot(repo = app.repo, isLocked = locked, onRequestUnlock = { triggerBiometricUnlock() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 熱啟動深入連結（捷徑／磁貼）：pendingTab 更新後，已組合緊嘅 AppRoot 會即刻跟跳
        pendingTab = validTab(intent)
    }

    /** 只接受 App 支援嘅 tab 值，其他（髒數據）一律丟棄 */
    private fun validTab(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_TAB)?.takeIf { it == TAB_DASHBOARD || it == TAB_TOKENS || it == TAB_SPEEDTEST || it == TAB_ALERTS || it == TAB_REPORT || it == TAB_STREAM || it == "monitor" }

    override fun onResume() {
        super.onResume()
        val generation = ++lockCheckGeneration
        val app = application as NewApiMonitorApp
        // Hide any previous session before suspending for DataStore; stale checks cannot reopen it.
        isLockedState = null
        activePrompt?.cancelAuthentication()
        activePrompt = null
        promptRunning = false
        lifecycleScope.launch {
            val enabled = try {
                app.appLockStore.isEnabledOnce()
            } catch (_: Exception) {
                true // Unknown policy is locked, never interpreted as disabled.
            }
            if (generation != lockCheckGeneration || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
            isLockedState = com.newapimonitor.data.store.lockRequired(enabled)
            if (enabled) triggerBiometricUnlock()
        }
    }

    override fun onPause() {
        // Hide content before another activity can expose the last rendered frame.
        isLockedState = null
        ++lockCheckGeneration
        activePrompt?.cancelAuthentication()
        activePrompt = null
        promptRunning = false
        super.onPause()
    }

    private fun triggerBiometricUnlock() {
        if (isLockedState != true || promptRunning || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val executor = ContextCompat.getMainExecutor(this)
        val generation = lockCheckGeneration
        try {
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (generation != lockCheckGeneration) return
                    promptRunning = false
                    activePrompt = null
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && isLockedState == true) {
                        isLockedState = false
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (generation != lockCheckGeneration) return
                    promptRunning = false
                    activePrompt = null
                    // 鎖定狀態保持 true，用戶可手動點擊「解鎖」按鈕重試
                }

                override fun onAuthenticationFailed() {
                    // 單次指紋不匹配，系統會允許再試，不需要重設 promptRunning
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解鎖 NewAPI Monitor")
            .setSubtitle("請透過指紋、人臉或裝置密碼驗證身份")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        promptRunning = true
        activePrompt = prompt
        prompt.authenticate(promptInfo)
        } catch (_: Exception) {
            promptRunning = false
            activePrompt = null
            // Prompt failure must never grant access; the locked screen allows a retry.
            isLockedState = true
        }
    }
}
