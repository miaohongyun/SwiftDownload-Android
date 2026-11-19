package com.swift.download.bt

/**
 * v2.7 Stage2.2：BT 按需边下边播的 piece 调度计算（纯逻辑，JVM 单测覆盖）。
 * jlibtorrent 只负责把「文件内偏移」换成「全局 piece 下标」，本对象处理区间/预读/首尾索引。
 */
object PieceRange {

    data class Range(val first: Int, val last: Int)

    /**
     * 计算 [offset, offset+length) 覆盖的 piece 下标区间。
     * [pieceLength] 为 piece 字节数（>0），[totalPieces] 用于夹紧上界。
     * [withinFilePiece] 为文件首字节所在的全局 piece 下标。
     */
    fun covering(
        withinFilePiece: Int,
        offsetInFile: Long,
        length: Long,
        pieceLength: Int,
        totalPieces: Int
    ): Range? {
        if (pieceLength <= 0 || totalPieces <= 0 || length <= 0L) return null
        val firstRel = (offsetInFile / pieceLength).toInt()
        val lastRel = ((offsetInFile + length - 1) / pieceLength).toInt()
        val first = (withinFilePiece + firstRel).coerceIn(0, totalPieces - 1)
        val last = (withinFilePiece + lastRel).coerceIn(first, totalPieces - 1)
        return Range(first, last)
    }

    /** 在覆盖区间基础上向后预读若干 piece（播放器拖动后能连续播） */
    fun withPrefetch(range: Range, prefetch: Int, totalPieces: Int): Range =
        range.copy(last = (range.last + prefetch.coerceAtLeast(0)).coerceIn(range.last, totalPieces - 1))

    /**
     * 首尾 piece 列表：moov/mdat 索引常在文件首尾，优先调度以免拖动后长时间黑屏。
     * 去重后返回，避免与当前区间重复调度。
     */
    /** [firstPiece] 为文件首字节的全局 piece，[totalPieces] 为种子全局 piece 总数 */
    fun edgePieces(firstPiece: Int, totalPieces: Int, headCount: Int = 2, tailCount: Int = 2): Set<Int> {
        if (totalPieces <= 0) return emptySet()
        val out = LinkedHashSet<Int>()
        for (i in 0 until headCount.coerceAtMost(totalPieces)) out.add((firstPiece + i).coerceAtMost(totalPieces - 1))
        for (i in 0 until tailCount.coerceAtMost(totalPieces)) {
            val p = totalPieces - 1 - i
            if (p >= firstPiece) out.add(p)
        }
        return out
    }
}
