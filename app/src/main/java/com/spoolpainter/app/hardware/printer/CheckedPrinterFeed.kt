package com.spoolpainter.app.hardware.printer

/**
 * Reuse POIPrinterManager's existing native lease. Its lineWrapPixels wrapper discards the
 * return code; the verified one-argument native method feeds dots with black-mark flags zero.
 * No fallback is attempted because a rejected/unknown command must never be issued twice.
 */
internal fun checkedForwardFeed(manager: Any, dots: Int) {
    require(dots >= 0) { "走纸距离不能为负数" }
    if (dots == 0) return
    val field = manager.javaClass.getDeclaredField("printer").apply { isAccessible = true }
    val nativePrinter = checkNotNull(field.get(manager)) { "打印连接已关闭，未发送走纸指令" }
    val result = nativePrinter.javaClass.getMethod("setPrintCtrlFeed", Int::class.javaPrimitiveType).invoke(nativePrinter, dots) as Int
    check(result == 0) { "走纸指令未确认成功（$result），请检查纸张位置；不要直接重印" }
}
