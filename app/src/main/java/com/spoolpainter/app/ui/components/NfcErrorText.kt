package com.spoolpainter.app.ui.components

import java.util.Locale

/** Localize known transport outcomes without hiding unexpected errors or their diagnostic code. */
fun nfcErrorText(reason: String): String {
    val normalized = reason.lowercase(Locale.ROOT)
    return when {
        normalized.contains("cleanup failed") || normalized.contains("reader state is uncertain") ->
            "读卡器状态尚未确认，请先停止操作并检查设备。详情：$reason"
        normalized.contains("scan timed out") || normalized.contains("no pos nfc tag detected") || normalized.contains("0xf37d") ->
            "未检测到耗材标签，请重新贴近读卡区后再试。"
        normalized == "nfc not available" -> "设备没有可用的标签读卡服务。"
        else -> reason
    }
}
