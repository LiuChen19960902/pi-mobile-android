package com.finecat.pimobile.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidErrorPresentationTest {
    @Test
    fun localFailureDoesNotInheritReconnectAction() {
        val holder = AndroidSharedStateHolder()
        holder.showPlatformError("connect-failed")
        assertEquals(AndroidSharedStateHolder.PlatformErrorAction.RECONNECT, holder.platformErrorAction)
        holder.showPlatformError("image-preprocess-failed")
        assertEquals("图片无法读取，请重新选择", holder.platformError)
        assertEquals(AndroidSharedStateHolder.PlatformErrorAction.DISMISS_ONLY, holder.platformErrorAction)
        holder.showPlatformError("私密异常 /secret/path")
        assertEquals("操作失败，请重试", holder.platformError)
        holder.clearPlatformError()
        assertEquals(null, holder.platformError)
        holder.close()
    }
}
