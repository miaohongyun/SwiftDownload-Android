package com.swift.download

import com.swift.download.files.FilesPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7 Stage0：六项跨模块边界核查中发现缺陷的回归测试。
 * 只覆盖纯逻辑部分；Android 平台调用（ContentResolver/HttpURLConnection）由源码核查给证据。
 */
class Stage0RegressionTest {

    // --- 项1：FilesPage 重命名命名校验（v2.5.2 路径分隔符 + v2.6.0 隐藏文件）---

    @Test
    fun renameRejectsPathTraversal() {
        assertTrue(FilesPage.renameError("../escape") != null)
        assertTrue(FilesPage.renameError("a/b") != null)
        assertTrue(FilesPage.renameError("a\\b") != null)
        assertTrue(FilesPage.renameError(".") != null)
        assertTrue(FilesPage.renameError("..") != null)
    }

    @Test
    fun renameRejectsHiddenFile() {
        assertTrue(FilesPage.renameError(".hidden") != null)
    }

    @Test
    fun renameAcceptsNormalNames() {
        assertNull(FilesPage.renameError("电影.mp4"))
        assertNull(FilesPage.renameError("a.b.c.txt"))
        assertNull(FilesPage.renameError("名义-最终 (1).mkv"))
    }

    // --- 项1：MediaStore content URI 判定 ---

    @Test
    fun contentUriDetection() {
        assertTrue(FilesPage.isContentUri("content://media/external/downloads/42"))
        assertTrue(FilesPage.isContentUri("CONTENT://COM.ANDROID.PROVIDERS.DOWNLOADS/document/5"))
        assertFalse(FilesPage.isContentUri("/storage/emulated/0/Download/x.mp4"))
        assertFalse(FilesPage.isContentUri(""))
    }
}
