package com.newapimonitor.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 訂閱 lifecycle ON_START / ON_STOP + 離開 composition 時 onStop：
 *  - Activity 轉背景（ON_STOP）→ 輪詢暫停 + in-flight 請求 cancel
 *  - 返嚟（ON_START）→ 即刻刷新 + 重啟輪詢
 *  - 切走 tab（onDispose）→ 同樣暫停
 */
@Composable
fun DisposableLifecycleEffect(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val currentOnStart = rememberUpdatedState(onStart)
    val currentOnStop = rememberUpdatedState(onStop)
    val observer = remember {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> currentOnStart.value()
                Lifecycle.Event.ON_STOP -> currentOnStop.value()
                else -> Unit
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(observer)
        currentOnStart.value()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            currentOnStop.value()
        }
    }
}
