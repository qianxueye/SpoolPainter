package com.spoolpainter.app.hardware.printer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Application-scoped worker. Leaving a screen requests idle cleanup, never cancels a physical job. */
class PrinterController(
    private val port: PrinterPort,
    private val scope: CoroutineScope,
    private val callbackTimeoutMs: Long = 60_000,
    private val holdAwake: (Boolean) -> Unit = {},
) {
    private data class Job(val request: PrintRequest, val tail: PaperTail)
    private sealed interface Completion {
        data object Finished : Completion
        data class Failed(val code: Int, val message: String) : Completion
    }
    private val jobs = Channel<Job>(8)
    private val mutableState = MutableStateFlow<PrintState>(PrintState.Idle)
    val state: StateFlow<PrintState> = mutableState.asStateFlow()
    private val mutablePending = MutableStateFlow(0)
    val pending: StateFlow<Int> = mutablePending.asStateFlow()
    private var clients = 0
    private var open = false
    private var uncertain = false

    init {
        scope.launch {
            for (job in jobs) {
                try { execute(job) }
                catch (e: Exception) { mutableState.value = PrintState.Failed(e.cause?.message ?: e.message ?: "打印失败") }
                finally {
                    runCatching { holdAwake(false) }
                    mutablePending.value -= 1
                    closeIfUnused()
                }
            }
        }
    }

    // UI calls this API on Main, as does the worker; test scopes use one test dispatcher.
    fun attach() { clients++ }
    fun detach() { clients = (clients - 1).coerceAtLeast(0); closeIfUnused() }

    fun enqueue(request: PrintRequest, tail: PaperTail): Boolean {
        if (uncertain) return false
        try { request.qrPayloads() }
        catch (e: IllegalArgumentException) { mutableState.value = PrintState.Failed(e.message ?: "标签无效"); return false }
        catch (e: java.net.URISyntaxException) { mutableState.value = PrintState.Failed("服务器地址格式无效"); return false }
        mutablePending.value += 1
        if (jobs.trySend(Job(request, tail)).isFailure) { mutablePending.value -= 1; return false }
        return true
    }

    private suspend fun execute(job: Job) {
        val id = job.request.label.id
        mutableState.value = PrintState.Working(id, "正在连接打印机…")
        holdAwake(true)
        if (!open || port.state() == 4) {
            // A manager that lost its service must release its receiver before reopening.
            if (open) { port.close(); open = false }
            port.open()
            open = true
        }
        var status = port.state()
        repeat(10) {
            if (status == 4) { delay(200); status = port.state() }
        }
        if (status != 0) {
            mutableState.value = PrintState.Failed(printerStatusMessage(status))
            return
        }
        port.prepare(job.request)
        val done = CompletableDeferred<Completion>()
        val listener = object : PrinterPort.Listener {
            override fun onStart() { scope.launch {
                if (!done.isCompleted && !uncertain) mutableState.value = PrintState.Working(id, "正在打印…")
            } }
            override fun onFinish() { done.complete(Completion.Finished) }
            override fun onError(code: Int, message: String) { done.complete(Completion.Failed(code, message)) }
        }
        try {
            port.begin(listener)
        } catch (e: Exception) {
            // An exception during dispatch does not prove native code accepted no work.
            uncertain = true
            mutableState.value = PrintState.Uncertain(id)
        }
        val onTime = if (uncertain) null else withTimeoutOrNull(callbackTimeoutMs) { done.await() }
        if (onTime == null) {
            uncertain = true
            mutableState.value = PrintState.Uncertain(id)
            // A missing callback cannot prove the paper stopped. Keep the queue blocked and
            // manager alive until a terminal callback, even when the last screen detaches.
        }
        val outcome = onTime ?: done.await()
        uncertain = false
        when (outcome) {
            Completion.Finished -> {
                try {
                    port.feed(job.tail.dots)
                    mutableState.value = PrintState.Finished(id)
                } catch (e: Exception) {
                    mutableState.value = PrintState.Failed("内容已打印，纸尾走纸失败。请勿直接重印：${e.cause?.message ?: e.message}")
                }
            }
            is Completion.Failed -> mutableState.value = PrintState.Failed(
                printerStatusMessage(outcome.code) + outcome.message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            )
        }
    }

    private fun closeIfUnused() {
        if (clients == 0 && mutablePending.value == 0 && open) {
            runCatching { port.close() }
            open = false
        }
    }
}
