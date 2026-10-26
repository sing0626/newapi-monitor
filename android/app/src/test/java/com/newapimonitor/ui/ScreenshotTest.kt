package com.newapimonitor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.ui.theme.NewApiMonitorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Robolectric NATIVE graphics 截圖測試：JVM 上真渲染 Compose（無實機／模擬器），
 * 以演示模式啟動 App，切三個 tab 各壓一張 PNG 到工作區 android-shots/。
 *
 * 出圖方式：decorView.draw(Canvas) — NATIVE graphics 下係真像素渲染（Roborazzi 同款原理），
 * 唔行 captureToImage（App 有倒數環 withFrameNanos 循環＋shimmer 等永續動畫，
 * compose idle sync 會永遠 busy、forceRedraw 喺 manual clock 下冇 frame，兩條路都卡死）。
 *
 * 已知渲染器限制（唔算 fail）：Modifier.blur／RenderEffect 玻璃模糊可能渲染唔出；系統狀態列唔會出現。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val shotDir = File("D:/vibeproject/NewAPI Monitor/android-shots")

    @Before
    fun setUp() {
        // App 有無限動畫，autoAdvance=true 時 Compose 永遠唔會 idle；
        // 關閉自動推進，用手動 pump frames 推動 UI（Google 官方「無限動畫測試」配方）。
        composeRule.mainClock.autoAdvance = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun decorView(): View =
        (composeRule as AndroidComposeTestRule<*, *>).activity.window.decorView

    private fun hasText(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes().isNotEmpty()

    /** 手動推進 frame clock 直到條件成立；同時 pump main looper（VM coroutines 同 view layout 要靠佢跑） */
    private fun pumpUntil(maxFrames: Int = 600, stepMs: Long = 16, predicate: () -> Boolean) {
        repeat(maxFrames) {
            composeRule.mainClock.advanceTimeBy(stepMs)
            composeRule.waitForIdle()
            if (predicate()) return
        }
        throw AssertionError("pumpUntil 超時：等咗 ${(maxFrames * stepMs)}ms 都未見條件成立")
    }

    /**
     * 推進 clock 越過 M3 NavigationBarItem label 等 component 嘅顏色過渡動畫（~200ms），
     * 令手動 clock 下被凍結喺中途嘅過渡全部落地，截出最終穩定狀態。
     */
    private fun settleTransitions() {
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val view = decorView()
        pumpUntil(maxFrames = 100) { view.width > 0 && view.height > 0 }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap)) // NATIVE graphics：真渲染像素
        shotDir.mkdirs()
        val out = File(shotDir, name)
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCREENSHOT_WRITTEN: ${out.absolutePath} bytes=${out.length()} size=${view.width}x${view.height}")
    }

    @Test
    fun captureFourTabsInDemoMode() {
        val app = ApplicationProvider.getApplicationContext<NewApiMonitorApp>()

        // 等 Application.onCreate 嘅 DataStore load 完成（避免異步 load 覆寫 demo 設定），再開演示模式
        runBlocking {
            withTimeout(30_000) { app.store.ready.first { it } }
            app.store.setDemo(true)
        }

        composeRule.setContent {
            NewApiMonitorTheme {
                AppRoot(repo = app.repo)
            }
        }
        composeRule.waitForIdle()

        // 1) 儀表板：等 demo 快照真渲染好（模型卡出現 = ok && !keyOnly；剩餘額度大字出現）
        pumpUntil { hasText("總餘額") && hasText("演示實例") }
        // 推進 count-up／入場動畫至完成，截最終狀態
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        capture("dashboard.png")

        // 2) 日誌：等 demo 分頁數據載入（摘要列出現「共約 N 筆」）
        composeRule.onNodeWithText("日誌").performClick()
        pumpUntil { hasText("全部模型") && hasText("共約", substring = true) }
        settleTransitions()
        capture("logs.png")

        // 3) 令牌：等 demo 令牌列表載入（摘要卡「令牌管理」＋「N 張」出現）
        composeRule.onNodeWithText("令牌").performClick()
        pumpUntil { hasText("令牌管理") && hasText("張", substring = true) }
        settleTransitions()
        capture("tokens.png")

        // 4) 設定：等一般設定卡出現；內容即時渲染，必須先 settle 嘅係 M3 label 顏色過渡，
        //    否則標籤活躍色會凍結喺切換前狀態（icon 無動畫已切 → 導覽活躍態錯亂）
        composeRule.onNodeWithText("設定").performClick()
        pumpUntil { hasText("一般設定") && hasText("安全說明") }
        settleTransitions()
        capture("settings.png")

        // Padding belongs inside the scroll container: its final action can clear the overlay.
        composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        settleTransitions()
        val finalAction = composeRule.onNodeWithText("清除本機所有數據").fetchSemanticsNode().boundsInRoot
        val overlay = composeRule.onNodeWithTag("glass-bar").fetchSemanticsNode().boundsInRoot
        assertTrue("final settings action must scroll entirely above the glass bar", finalAction.bottom <= overlay.top)

        listOf("dashboard.png", "logs.png", "tokens.png", "settings.png").forEach { name ->
            val f = File(shotDir, name)
            if (!f.exists() || f.length() <= 0) throw AssertionError("$name 未寫出或係空檔")
            println("SCREENSHOT_OK: ${f.absolutePath} bytes=${f.length()}")
        }
    }
}
