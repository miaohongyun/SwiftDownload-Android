package com.swift.download

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.7 真机崩溃复盘：类成员初始化顺序守卫。
 *
 * 背景（2026-11-19 小米14pro 启动闪退）：
 * TransfersPage 把 `lastTasks = emptyList()` 写在 init 块之后，而 init 块构建筛选胶囊时
 * 就调用了 labelOf() -> TaskFilters.counts(lastTasks)，此时 lastTasks 仍为 null，
 * 传给非空参数触发 Kotlin `checkNotNullParameter` -> NPE -> Activity 启动崩溃。
 *
 * 这类问题编译期发现不了，JVM 单测也测不到（不实例化 View），只有真机才暴露。
 * 因此用"源码结构检查"把它变成一条会自动执行的铁律：
 *   凡是含 init 块的类，所有类成员属性(val/var)必须声明在 init 块结束之前。
 *
 * 该检查不依赖 Robolectric 等重型库，纯 JVM 文本扫描，零成本防复发。
 */
class SourceLayoutGuardTest {

    /** 定位源码根目录（Gradle 单测工作目录可能是项目根或 app 模块根） */
    private fun findSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/swift/download"),
            File("app/src/main/java/com/swift/download"),
            File(System.getProperty("user.dir"), "src/main/java/com/swift/download"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/swift/download")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到源码目录，已尝试：${candidates.map { it.path }}")
    }

    /** 找出某个 init 块结束行（按大括号配对） */
    private fun initEndLine(lines: List<String>, start: Int): Int {
        var depth = 0
        for (i in start until lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (i > start && depth <= 0) return i
        }
        return lines.size - 1
    }

    /**
     * 类成员属性声明：4 空格缩进的 (private|protected|internal)? val/var。
     * 方法内局部变量缩进 >= 8 空格，companion object 内成员也会被跳过，避免误报。
     */
    private fun isFieldDeclaration(line: String): Boolean =
        Regex("^    (private |protected |internal )?(val|var) [A-Za-z_]").containsMatchIn(line)

    @Test
    fun `所有类成员属性必须声明在 init 块之前`() {
        val root = findSourceRoot()
        val violations = ArrayList<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            val initStarts = lines.indices.filter { Regex("^\\s*init\\s*\\{").containsMatchIn(lines[it]) }
            if (initStarts.isEmpty()) return@forEach
            val end = initEndLine(lines, initStarts.last())

            var inCompanion = false
            for (i in end + 1 until lines.size) {
                val line = lines[i]
                if (Regex("^\\s*companion object").containsMatchIn(line)) inCompanion = true
                if (inCompanion) continue
                if (isFieldDeclaration(line)) {
                    violations.add("${file.name}:${i + 1}: ${line.trim()}")
                }
            }
        }
        assertTrue(
            "发现 init 块之后声明的类成员属性（Kotlin 初始化顺序陷阱，会导致真机启动崩溃）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
