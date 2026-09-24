package com.xposed.wetypehook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardStageMetricsTest {

    @Test
    fun sameWindowSurvivesSmallDecorationHeightDrift() {
        assertTrue(isSameWindow(2376, 2376 - 48))
        assertTrue(isSameWindow(2376, 2376))
    }

    @Test
    fun orientationChangeIsNotTheSameWindow() {
        assertFalse(isSameWindow(2376, 1080))
    }
}
