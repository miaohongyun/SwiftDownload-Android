package com.swift.download.remote

import com.swift.download.DownloadTask

/**
 * v2.6.0 局域网远程投递：纯协议逻辑（无网络/界面依赖，JVM 单测覆盖）。
 * 负责链接类型识别、任务列表 JSON、内嵌控制台 HTML 的生成。
 */
object LanRemoteProtocol {

    enum class LinkKind { HTTP, MAGNET, INVALID }

    fun classify(raw: String): LinkKind = when {
        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> LinkKind.HTTP
        raw.startsWith("magnet:?xt=urn:btih:", true) -> LinkKind.MAGNET
        else -> LinkKind.INVALID
    }

    /** JSON 字符串转义（含控制字符），避免任务名里的引号/换行破坏前端解析 */
    fun json(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** 任务列表 → JSON 数组。字段保持精简，控制台只展示必要信息 */
    fun tasksJson(tasks: List<DownloadTask>): String = tasks.joinToString(",", "[", "]") { t ->
        val pct = if (t.totalBytes > 0) (t.downloadedBytes * 100 / t.totalBytes).toInt() else 0
        val status = when (t.status) {
            DownloadTask.Status.COMPLETED -> "已完成"
            DownloadTask.Status.FAILED -> "失败"
            DownloadTask.Status.PAUSED -> "暂停"
            DownloadTask.Status.QUEUED -> "等待"
            DownloadTask.Status.PROBING -> "准备中"
            DownloadTask.Status.RUNNING -> "下载中"
        }
        "{\"id\":${t.id},\"name\":${json(t.fileName.ifBlank { t.hash })}," +
            "\"status\":${json(status)},\"pct\":$pct,\"error\":${json(t.error)}}"
    }

    /** 极简单页控制台：链接投递 + .torrent 上传 + 任务列表轮询 */
    fun consoleHtml(): String = """
<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>极速下载 · 远程投递</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;background:#f5f6f8;color:#222}
header{background:#2b6cf5;color:#fff;padding:16px;font-size:18px;font-weight:bold}
main{max-width:640px;margin:0 auto;padding:16px}
.card{background:#fff;border-radius:12px;padding:16px;margin-bottom:14px;box-shadow:0 1px 3px #0001}
input,button{font-size:15px;padding:9px;border-radius:8px;border:1px solid #ccd}
input{width:calc(100% - 90px)}
button{background:#2b6cf5;color:#fff;border:0;cursor:pointer}
.row{display:flex;gap:8px;margin-top:10px}
#msg{color:#c22;min-height:20px;margin-top:8px}
.task{border-bottom:1px solid #eee;padding:8px 0}
.bar{height:6px;background:#e8e8e8;border-radius:3px;margin-top:5px}
.bar>i{display:block;height:100%;background:#2b6cf5;border-radius:3px}
small{color:#888}
</style></head><body>
<header>极速下载 · 远程投递</header><main>
<div class="card"><b>推送链接</b>
<div class="row"><input id="url" placeholder="http(s):// 直链 或 magnet: 磁力">
<button onclick="push()">添加</button></div>
<div class="row"><input type="file" id="torrent" accept=".torrent"></div>
<div class="row"><button onclick="upload()">上传 .torrent</button></div>
<div id="msg"></div></div>
<div class="card"><b>下载任务</b> <small>每 2 秒自动刷新</small><div id="list"></div></div>
</main><script>
function msg(t){document.getElementById('msg').textContent=t}
function push(){var u=document.getElementById('url').value.trim();if(!u){msg('请输入链接');return}
fetch('/api/add?url='+encodeURIComponent(u)).then(r=>r.json()).then(j=>{msg(j.ok?'已添加任务':'添加失败：'+(j.msg||''));document.getElementById('url').value='';load()})}
function upload(){var f=document.getElementById('torrent').files[0];if(!f){msg('请选择 .torrent 文件');return}
var fd=new FormData();fd.append('torrent',f);fetch('/api/torrent',{method:'POST',body:fd}).then(r=>r.json()).then(j=>{msg(j.ok?'种子任务已添加':'上传失败：'+(j.msg||''));load()})}
function load(){fetch('/api/tasks').then(r=>r.json()).then(a=>{var h='';for(var i=0;i<a.length;i++){var t=a[i];h+='<div class="task"><b>'+t.name+'</b> <small>'+t.status+'</small><div class="bar"><i style="width:'+t.pct+'%"></i></div></div>'}document.getElementById('list').innerHTML=h||'<small>暂无任务</small>'})}
load();setInterval(load,2000);
</script></body></html>
""".trimIndent()

    /** 简单结果 JSON，供前端 fetch 解析 */
    fun resultJson(ok: Boolean, msg: String = ""): String =
        "{\"ok\":$ok,\"msg\":${json(msg)}}"
}
