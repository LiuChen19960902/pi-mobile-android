package com.finecat.pimobile.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedModuleInfoTest {
    @Test
    fun exposesStableBootstrapMetadata() {
        assertEquals("PiMobileShared · schema 1", SharedModuleInfo.summary())
        assertTrue("androidMain" in SharedModuleInfo.sourceSets)
    }
}
