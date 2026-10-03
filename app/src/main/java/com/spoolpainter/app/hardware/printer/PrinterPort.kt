package com.spoolpainter.app.hardware.printer

/** All calls are serialized on the controller dispatcher (Main for the SDK's Android views). */
interface PrinterPort {
    fun open()
    fun state(): Int
    fun prepare(request: PrintRequest)
    fun begin(listener: Listener)
    fun feed(dots: Int)
    fun close()

    interface Listener {
        fun onStart()
        fun onFinish()
        fun onError(code: Int, message: String)
    }
}

fun printerStatusMessage(code: Int): String = when (code) {
    0 -> "打印机已就绪"
    1 -> "打印机正在工作，请稍后重试"
    2, -3 -> "打印头过热，请冷却后重试"
    3, -4 -> "打印机缺纸，请装纸后重试"
    4, -1 -> "打印机尚未连接，请检查硬件后重试"
    else -> "打印机错误（$code）"
}

sealed interface PrintState {
    data object Idle : PrintState
    data class Working(val spoolId: Int, val message: String) : PrintState
    data class Finished(val spoolId: Int) : PrintState
    data class Failed(val message: String) : PrintState
    /** No automatic retry: a job may still be printing after the callback deadline. */
    data class Uncertain(val spoolId: Int) : PrintState
}
