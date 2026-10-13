package com.finecat.pimobile.android

import com.finecat.pimobile.shared.SharedModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedModuleSmokeTest {
    @Test
    fun androidApplicationCanCallSharedModule() {
        assertEquals("PiMobileShared · schema 1", SharedModuleInfo.summary())
    }
}
