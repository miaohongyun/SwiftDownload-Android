package com.swift.download

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.5：存储根目录解析的回归守护。
 *
 * 分区存储（Android 11+）下公共下载目录不可写时必须自动降级到应用专属目录，
 * 否则整个"下载"核心功能不可用。这里用临时目录模拟两种情形。
 */
class StorageRootsTest {

    @Test
    fun `可写目录被判定为可写`() {
        val dir = createTempDir()
        assertTrue("临时目录应可写", StorageRootsProbe.isWritable(dir))
    }

    @Test
    fun `不存在的目录会被创建后可写`() {
        val parent = createTempDir()
        val child = File(parent, "sub/dir")
        assertTrue(StorageRootsProbe.isWritable(child))
        assertTrue("探测应创建目录", child.isDirectory)
    }

    @Test
    fun `不可写路径被判定为不可写`() {
        // 以一个已存在的文件当作"目录"，mkdirs 必然失败 → 不可写
        val f = File(createTempDir(), "not_a_dir")
        f.writeText("x")
        assertTrue("文件路径不应被判为可写", !StorageRootsProbe.isWritable(f))
    }
}

/** 暴露包内私有探测方法供测试（不改变生产代码行为） */
object StorageRootsProbe {
    fun isWritable(dir: File): Boolean = StorageRoots.isWritableForTest(dir)
}
