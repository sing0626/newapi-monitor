package com.newapimonitor.data.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newapimonitor.ui.theme.translated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceStoreTest {
    @Test fun roundTripAndRejectInvalidValues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = AppearanceStore(context)
        store.setLanguage("en")
        store.setMode("oled")
        store.setAccent("orange")
        assertEquals(Appearance("en", "oled", "orange"), AppearanceStore(context).appearance.first())
        store.setLanguage("invalid")
        store.setMode("invalid")
        store.setAccent("invalid")
        assertEquals(Appearance("en", "oled", "orange"), store.appearance.first())
        store.setLanguage("yue")
        store.setMode("dark")
        store.setAccent("purple")
    }

    @Test fun translationsCoverFourChoices() {
        assertEquals("Dashboard", translated("儀表板", "en"))
        assertEquals("仪表盘", translated("儀表板", "zh-CN"))
        assertEquals("儀表板", translated("儀表板", "zh-TW"))
        assertEquals("儀表板", translated("儀表板", "yue"))
    }
}
