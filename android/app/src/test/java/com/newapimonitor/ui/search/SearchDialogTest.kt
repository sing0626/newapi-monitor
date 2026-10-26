package com.newapimonitor.ui.search

import com.newapimonitor.domain.LogEntry
import com.newapimonitor.domain.TokenInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDialogTest {
    @Test fun neverSearchesTokenKeys() {
        val token = TokenInfo(name = "Work", key = "secret-match-value")
        assertTrue(searchResults("secret-match", listOf(token), emptyList(), emptyList(), emptyList()).isEmpty())
        assertEquals(listOf(SearchResultItem.Token("Work")),
            searchResults("work", listOf(token), emptyList(), emptyList(), emptyList()))
    }

    @Test fun searchesOnlyErrorLogMetadataNotErrorBody() {
        val error = LogEntry(type = 5, modelName = "model-x", tokenName = "work", createdAt = 42,
            content = "secret-match-value")
        val consume = error.copy(type = 2)
        assertTrue(searchResults("secret-match", emptyList(), emptyList(), listOf(error), emptyList()).isEmpty())
        assertEquals(listOf(SearchResultItem.ErrorLog("model-x", 42)),
            searchResults("MODEL-X", emptyList(), emptyList(), listOf(consume, error), emptyList()))
        assertEquals(listOf(SearchResultItem.ErrorLog("model-x", 42)),
            searchResults("error logs", emptyList(), emptyList(), listOf(error), emptyList(), "Error logs"))
    }

    @Test fun settingsMatchImmediatelyWithoutRemoteData() {
        assertEquals(listOf(SearchResultItem.Setting("Settings")),
            searchResults("set", emptyList(), emptyList(), emptyList(), listOf("Settings", "Logs")))
        assertTrue(searchResults("  ", emptyList(), listOf("set"), emptyList(), listOf("Settings")).isEmpty())
    }
}
