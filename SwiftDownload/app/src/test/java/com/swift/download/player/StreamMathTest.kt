package com.swift.download.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2：边下边播核心数学（Range 解析 / 已下载区段）单测。
 */
class StreamMathTest {

    private val total = 1000L

    @Test fun `无 Range 头返回整文件`() {
        assertArrayEquals(longArrayOf(0, 999), StreamMath.parseRange(null, total))
        assertArrayEquals(longArrayOf(0, 999), StreamMath.parseRange("", total))
    }

    @Test fun `开区间 Range`() {
        assertArrayEquals(longArrayOf(100, 999), StreamMath.parseRange("bytes=100-", total))
    }

    @Test fun `闭区间 Range 超出截剪`() {
        assertArrayEquals(longArrayOf(100, 999), StreamMath.parseRange("bytes=100-5000", total))
        assertArrayEquals(longArrayOf(100, 200), StreamMath.parseRange("bytes=100-200", total))
    }

    @Test fun `后缀 Range`() {
        assertArrayEquals(longArrayOf(500, 999), StreamMath.parseRange("bytes=-500", total))
        assertArrayEquals(longArrayOf(0, 999), StreamMath.parseRange("bytes=-99999", total))
    }

    @Test fun `非法输入回退整文件`() {
        assertArrayEquals(longArrayOf(0, 999), StreamMath.parseRange("bytes=abc", total))
        assertArrayEquals(longArrayOf(0, 999), StreamMath.parseRange("garbage", total))
    }

    @Test fun `越界返回空数组`() {
        assertTrue(StreamMath.parseRange("bytes=1000-", total).isEmpty())
        assertTrue(StreamMath.parseRange("bytes=2000-3000", total).isEmpty())
    }

    @Test fun `多段取第一段`() {
        assertArrayEquals(longArrayOf(0, 99), StreamMath.parseRange("bytes=0-99,200-299", total))
    }

    @Test fun `end小于start归一`() {
        assertArrayEquals(longArrayOf(100, 100), StreamMath.parseRange("bytes=100-50", total))
    }

    /* ---------------- onDisk / diskRunEnd ---------------- */

    @Test fun `单线程覆盖前缀`() {
        val segs = mapOf(0L to 400L)
        assertTrue(StreamMath.onDisk(0, segs))
        assertTrue(StreamMath.onDisk(399, segs))
        assertFalse(StreamMath.onDisk(400, segs))
        assertEquals(400L, StreamMath.diskRunEnd(0, segs, total))
        assertEquals(400L, StreamMath.diskRunEnd(350, segs, total))
    }

    @Test fun `四段各自前缀不连续`() {
        // 4 线程均分 1000：段界 0/250/500/750
        val segs = mapOf(0L to 200L, 250L to 100L, 500L to 250L, 750L to 40L)
        assertTrue(StreamMath.onDisk(560, segs))
        assertTrue(StreamMath.onDisk(700, segs))   // 段 [500,750) 已下满，覆盖 700
        assertFalse(StreamMath.onDisk(790, segs))   // 段 [750,…) 前缀只到 790
        assertFalse(StreamMath.onDisk(210, segs))   // 段 [0,…) 前缀只到 200
        // 从 0 起连续区：段0 [0,200) 未满其段界 250 → 停在 200
        assertEquals(200L, StreamMath.diskRunEnd(0, segs, total))
        // 从 500（段起点）连续 250 → 750；段 750 有 40 → 续到 790；该段未满(段界1000) → 停 790
        assertEquals(790L, StreamMath.diskRunEnd(500, segs, total))
    }

    @Test fun `全部下满连续整文件`() {
        val segs = mapOf(0L to 250L, 250L to 250L, 500L to 250L, 750L to 250L)
        assertEquals(1000L, StreamMath.diskRunEnd(0, segs, total))
        assertTrue(StreamMath.onDisk(999, segs))
    }

    @Test fun `起点不在盘上返回自身`() {
        val segs = mapOf(0L to 100L)
        assertEquals(500L, StreamMath.diskRunEnd(500, segs, total))
        assertFalse(StreamMath.onDisk(-1, segs))
    }
}
