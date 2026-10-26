package com.newapimonitor.ui.monitor

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.ui.theme.NewApiMonitorTheme
import com.newapimonitor.ui.AppRoot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.newapimonitor.domain.Instance
import com.newapimonitor.data.monitor.MonitorStore
import com.newapimonitor.data.monitor.MonitorSnapshot
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h891dp-420dpi")
class MonitorScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test(timeout = 120000) fun `home options and demo cross report appear in native navigation`() {
        val app=ApplicationProvider.getApplicationContext<NewApiMonitorApp>()
        runBlocking { app.store.ready.first { it }; app.store.setDemo(true) }
        compose.mainClock.autoAdvance=false
        compose.setContent { NewApiMonitorTheme { AppRoot(app.repo) } }
        pump()
        compose.onNodeWithText("自訂").activate();pump()
        compose.onNodeWithText("自訂總覽").assertExists()
        compose.onNodeWithText("緊湊實例卡").assertExists()
        compose.onNodeWithText("隱藏首頁金額").assertExists()
        capture("monitor-home-options")
        compose.onNodeWithText("完成").activate();pump()
        compose.onNodeWithText("消費報告").activate();pump()
        compose.onNodeWithText("跨實例比較").assertExists()
        compose.onNodeWithText("匯出報告 CSV").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).onFirst().activate();pump()
        compose.onNodeWithText("查詢").activate();pump()
        compose.onNodeWithText("跨實例消費").assertExists()
        compose.onNodeWithText("匯出報告 CSV").assertExists()
        capture("monitor-cross-report")
    }
    @Test(timeout = 120000) fun `demo tools support date reports cost drilldown stream probes and notification history`() {
        val app=ApplicationProvider.getApplicationContext<NewApiMonitorApp>()
        runBlocking { app.store.ready.first { it }; app.store.setDemo(true) }
        compose.mainClock.autoAdvance=false
        compose.setContent { NewApiMonitorTheme { AppRoot(app.repo) } }
        pump()
        compose.onNodeWithText("總餘額").assertExists()
        compose.onNodeWithTag("glass-bar").assertExists()
        capture("monitor-overview")
        compose.onNodeWithText("演示實例").activate();pump()
        compose.onNodeWithText("剩餘額度").assertExists()
        compose.onNodeWithTag("glass-bar").assertDoesNotExist()
        capture("monitor-instance")
        compose.onNodeWithContentDescription("返回上一頁").activate();pump()
        compose.onNodeWithText("消費報告").activate();pump()
        compose.onNodeWithTag("glass-bar").assertDoesNotExist()
        compose.onNodeWithText("查詢").activate();pump()
        compose.onNodeWithText("模型成本").assertExists()
        capture("monitor-report")
        compose.onAllNodesWithText("demo-fast",substring=true).onFirst().activate();pump()
        compose.onNodeWithText("返回報告").assertExists()
        capture("monitor-report-detail")
        compose.onNodeWithContentDescription("返回上一頁").activate();pump()
        compose.onNodeWithText("自訂日期報告").assertExists()
        compose.onNodeWithContentDescription("返回上一頁").activate();pump()
        compose.onNodeWithText("模型測速").activate();pump()
        compose.onNodeWithTag("glass-bar").assertDoesNotExist()
        compose.onNodeWithText("每行一個模型，最多 3 個").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("demo-fast")) }
        compose.onNodeWithText("開始測試").activate();pump()
        compose.onAllNodesWithText("首字 180 ms",substring=true).onFirst().assertExists()
        capture("monitor-stream")
        compose.onNodeWithContentDescription("返回上一頁").activate();pump()
        compose.onNodeWithContentDescription("開啟通知").activate();pump()
        compose.onNodeWithText("本機通知紀錄",substring=true).assertExists()
        capture("monitor-alerts")
    }
    @Test(timeout = 120000) fun `stale balance is excluded from totals and budget editing validates before saving`() {
        val app=ApplicationProvider.getApplicationContext<NewApiMonitorApp>()
        val cache=MonitorStore(app)
        val source=runBlocking {
            app.store.ready.first { it }; app.store.setDemo(false)
            // An unreachable loopback fixture never sends credentials to an external host.
            val instance=app.store.addInstance(Instance(name="預算測試",baseUrl="http://127.0.0.1:1",accessToken="fixture"))!!
            val old=System.currentTimeMillis()-120_000
            cache.save(MonitorSnapshot(instance.id,1200.25,balanceAt=old,attemptedAt=old,sourceStamp=MonitorSnapshot.stamp(instance)))
            cache.setBudget(instance.id,100.0)
            instance
        }
        compose.mainClock.autoAdvance=false
        compose.setContent { NewApiMonitorTheme { AppRoot(app.repo) } }
        pump()
        repeat(100) {
            if (compose.onAllNodesWithText("$1.2K").fetchSemanticsNodes().isEmpty()) pump()
        }
        compose.onAllNodesWithText("$1.2K").assertCountEquals(1)
        compose.onNodeWithText("$1.2K").activate();pump()
        compose.onNodeWithText("$1,200.25000 USD").assertExists()
        compose.onNodeWithText("關閉").activate();pump()
        compose.onNodeWithText("編輯預算").activate();pump()
        capture("monitor-budget")
        val input=compose.onNodeWithText("預算 USD（留空關閉）")
        input.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("-1")) }
        compose.onNodeWithText("儲存預算").activate();pump()
        compose.onNodeWithText("請輸入大於零的有效金額").assertExists()
        input.performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("250")) }
        compose.onNodeWithText("儲存預算").activate();pump()
        runBlocking { withTimeout(5_000) { cache.budgets.first { it[source.id] == 250.0 } } }
        pump()
        compose.onNodeWithText("預算 USD（留空關閉）").assertDoesNotExist()
        compose.onNodeWithText("月度預算 $250.00").assertExists()
    }
    private fun SemanticsNodeInteraction.activate() = performSemanticsAction(SemanticsActions.OnClick) { it() }
    private fun pump() { repeat(30) { compose.mainClock.advanceTimeBy(16);compose.waitForIdle() } }
    private fun capture(name:String) {
        val view=(compose as AndroidComposeTestRule<*,*>).activity.window.decorView
        if(view.width<=0 || view.height<=0)return
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory=File("../../.codex-work/monitor-shots").apply { mkdirs() }
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
