package com.newapimonitor.data.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test fun `unknown or enabled policy requires authentication`() {
        assertTrue(lockRequired(null))
        assertTrue(lockRequired(true))
        assertFalse(lockRequired(false))
    }
}
