package com.spoolpainter.app.domain.primitives

import java.net.URI

/** QR scanning resolves a record in the current inventory; it never navigates to an arbitrary URL. */
object SpoolQrPayload {
    private val scheme = Regex("WEB\\+SPOOLMAN:S-([1-9][0-9]*)", RegexOption.IGNORE_CASE)

    fun spoolId(payload: String, serverUrl: String): Int {
        val text = payload.trim()
        require(text.length <= 4096) { "二维码内容过长" }
        scheme.matchEntire(text)?.let { return positiveId(it.groupValues[1]) }
        val base = uri(serverUrl.trim().trimEnd('/'))
        val scanned = uri(text)
        require(base.scheme?.lowercase() in setOf("http", "https") && base.host != null && base.rawQuery == null && base.rawFragment == null) { "请先设置有效的服务器地址" }
        require(scanned.scheme.equals(base.scheme, true) && scanned.host.equals(base.host, true) && port(scanned) == port(base) && scanned.rawUserInfo == null) { "这不是当前服务器的库存二维码" }
        require(scanned.rawFragment == null) { "不支持此库存二维码链接格式" }
        val basePath = base.rawPath.orEmpty().trimEnd('/')
        val path = scanned.rawPath.orEmpty()
        if (scanned.rawQuery != null) {
            require(path == basePath || path == "$basePath/") { "不支持此库存二维码链接格式" }
            val selection = Regex("sel=spool(?::|%3[Aa])([1-9][0-9]*)").matchEntire(scanned.rawQuery)
            require(selection != null) { "请扫描当前服务器的库存选择链接" }
            return positiveId(selection.groupValues[1])
        }
        val prefix = basePath + "/spool/show/"
        require(path.startsWith(prefix)) { "请扫描库存二维码（耗材卷），不支持耗材类型或其他链接" }
        val id = path.removePrefix(prefix).removeSuffix("/")
        require(id.matches(Regex("[1-9][0-9]*"))) { "库存二维码编号无效" }
        return positiveId(id)
    }

    private fun positiveId(raw: String): Int = raw.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("库存二维码编号无效")
    private fun uri(raw: String): URI = try { URI(raw) } catch (_: Exception) {
        throw IllegalArgumentException("请扫描 Spoolman 库存二维码")
    }
    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
}
