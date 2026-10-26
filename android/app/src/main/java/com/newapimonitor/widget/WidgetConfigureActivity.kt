package com.newapimonitor.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.newapimonitor.NewApiMonitorApp
import com.newapimonitor.ui.theme.NewApiMonitorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val app = application as NewApiMonitorApp
        lifecycleScope.launch {
            app.store.ready.first { it }
            val choice = WidgetOptionsStore(app).read(id)
            setContent { NewApiMonitorTheme {
                val instances by app.store.instances.collectAsState()
                var source by remember { mutableStateOf(choice.source) }
                var hide by remember { mutableStateOf(choice.hideMoney) }
                var saving by remember { mutableStateOf(false) }
                var failure by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("設定桌面小工具", style = MaterialTheme.typography.headlineSmall)
                        Text("每個小工具獨立選擇，亦可長按小工具重新設定。")
                        (listOf("all" to "全部實例總覽", "active" to "跟隨目前實例") + instances.map { it.id to it.name }).forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(source == value, { source = value }); Text(label) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hide, { hide = it }); Text("隱藏小工具金額") }
                        if (failure) Text("儲存失敗，請重試", color = MaterialTheme.colorScheme.error)
                        Button(enabled = !saving, onClick = { saving = true; lifecycleScope.launch {
                            try {
                                WidgetOptionsStore(app).save(id, WidgetChoice(source, hide))
                                // A placeholder is committed immediately; the provider then performs its bounded update.
                                AppWidgetManager.getInstance(app).updateAppWidget(id, WidgetSync.buildViews(app, WidgetData.setup(), id))
                                BalanceWidgetProvider().onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(id))
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e
                            } catch (_: Exception) { failure = true; saving = false }
                        } }, modifier = Modifier.fillMaxWidth()) { Text("儲存小工具") }
                        TextButton(onClick = { finish() }) { Text("取消") }
                    }
                }
            } }
        }
    }
}
