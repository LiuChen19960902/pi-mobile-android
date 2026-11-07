package com.finecat.pimobile.android.ui.finecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 目录浏览失败后的逃生上一级是纯字符串推导：不依赖服务端 crumbs，也不能把根目录再往上翻。 */
class FineCatDirectorySheetParentPathTest {
    @Test
    fun nestedPath_returnsParent() {
        assertEquals("/tmp", directoryErrorParentPath("/tmp/aaa-pi-acceptance"))
        assertEquals("/home/user", directoryErrorParentPath("/home/user/old-proj"))
    }

    @Test
    fun topLevelDir_returnsRoot() {
        assertEquals("/", directoryErrorParentPath("/tmp"))
    }

    @Test
    fun trailingSlash_isNormalized() {
        assertEquals("/tmp", directoryErrorParentPath("/tmp/aaa-pi-acceptance/"))
        assertEquals("/", directoryErrorParentPath("/tmp/"))
    }

    @Test
    fun rootAndNonAbsolute_haveNoParent() {
        assertNull(directoryErrorParentPath("/"))
        assertNull(directoryErrorParentPath(""))
        assertNull(directoryErrorParentPath("relative/path"))
    }
}
