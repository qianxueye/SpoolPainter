package com.spoolpainter.app.hardware.printer

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrinterControllerTest {
    private fun request(id: Int = 42) = PrintRequest(SpoolLabel(id, "品牌", "PLA", "白色", 420.5, "A1"), "https://example.org/stock/")
    private class FakePort : PrinterPort {
        var status = 0
        var opens = 0
        var closes = 0
        val requests = mutableListOf<PrintRequest>()
        val callbacks = mutableListOf<PrinterPort.Listener>()
        val feeds = mutableListOf<Int>()
        var prepareFails = false
        var dispatchFails = false
        override fun open() { opens++ }
        override fun state() = status
        override fun prepare(request: PrintRequest) { if (prepareFails) error("render failed"); requests += request }
        override fun begin(listener: PrinterPort.Listener) { callbacks += listener; if (dispatchFails) error("dispatch failed") }
        override fun feed(dots: Int) { feeds += dots }
        override fun close() { closes++ }
    }

    @Test fun queueWaitsForTerminalCallbackAndFeedsOnce() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        controller.attach()
        assertTrue(controller.enqueue(request(1), PaperTail.ONE_AND_HALF_CM))
        assertTrue(controller.enqueue(request(2), PaperTail.TWO_CM))
        runCurrent()
        assertEquals(listOf(1), port.requests.map { it.label.id })
        port.callbacks[0].onStart()
        runCurrent()
        assertTrue(controller.state.value is PrintState.Working)
        port.callbacks[0].onFinish()
        port.callbacks[0].onFinish()
        port.callbacks[0].onError(-4, "late error")
        runCurrent()
        assertEquals(listOf(120), port.feeds)
        assertEquals(listOf(1, 2), port.requests.map { it.label.id })
        port.callbacks[1].onFinish()
        runCurrent()
        assertEquals(listOf(120, 160), port.feeds)
        assertEquals(0, controller.pending.value)
        assertEquals(PrintState.Finished(2), controller.state.value)
        assertEquals(0, port.closes)
        controller.detach()
        assertEquals(1, port.closes)
    }

    @Test fun leavingScreenDoesNotCloseInFlightJob() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        controller.attach()
        controller.enqueue(request(), PaperTail.ONE_CM)
        runCurrent()
        controller.detach()
        assertEquals(0, port.closes)
        port.callbacks.single().onFinish()
        runCurrent()
        assertEquals(listOf(80), port.feeds)
        assertEquals(1, port.closes)
    }

    @Test fun timeoutQuarantinesQueueUntilLateTerminalCallback() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope, callbackTimeoutMs = 100)
        controller.attach()
        controller.enqueue(request(1), PaperTail.ONE_AND_HALF_CM)
        controller.enqueue(request(2), PaperTail.TWO_CM)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(PrintState.Uncertain(1), controller.state.value)
        assertFalse(controller.enqueue(request(3), PaperTail.ONE_CM))
        controller.detach()
        assertEquals(0, port.closes)
        assertEquals(1, port.requests.size)
        port.callbacks[0].onFinish()
        runCurrent()
        assertEquals(listOf(120), port.feeds)
        assertEquals(2, port.requests.size)
        port.callbacks[1].onFinish()
        runCurrent()
        assertEquals(1, port.closes)
    }

    @Test fun noPaperAndOverheatDoNotBeginOrFeed() = runTest {
        for (code in listOf(2, 3)) {
            val port = FakePort().also { it.status = code }
            val controller = PrinterController(port, backgroundScope)
            controller.enqueue(request(), PaperTail.ONE_CM)
            runCurrent()
            assertEquals(PrintState.Failed(printerStatusMessage(code)), controller.state.value)
            assertTrue(port.callbacks.isEmpty())
            assertTrue(port.feeds.isEmpty())
        }
    }

    @Test fun terminalErrorAllowsExplicitRetryWithoutFeeding() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        controller.enqueue(request(), PaperTail.ONE_CM)
        runCurrent()
        port.callbacks[0].onError(-4, "paper out")
        runCurrent()
        assertTrue((controller.state.value as PrintState.Failed).message.contains("缺纸"))
        assertTrue(port.feeds.isEmpty())
        assertTrue(controller.enqueue(request(), PaperTail.TWO_AND_HALF_CM))
        runCurrent()
        port.callbacks[1].onFinish()
        runCurrent()
        assertEquals(listOf(200), port.feeds)
    }

    @Test fun dispatchExceptionCannotAllowAnOverlappingJob() = runTest {
        val port = FakePort().also { it.dispatchFails = true }
        val controller = PrinterController(port, backgroundScope)
        controller.enqueue(request(), PaperTail.ONE_CM)
        runCurrent()
        assertTrue(controller.state.value is PrintState.Uncertain)
        assertFalse(controller.enqueue(request(2), PaperTail.ONE_CM))
        assertEquals(0, port.closes)
        port.callbacks.single().onError(-2, "native dispatch rejected")
        runCurrent()
        assertTrue(controller.state.value is PrintState.Failed)
        assertEquals(1, port.closes)
    }

    @Test fun renderingFailureDoesNotKillWorker() = runTest {
        val port = FakePort().also { it.prepareFails = true }
        val controller = PrinterController(port, backgroundScope)
        controller.enqueue(request(), PaperTail.ONE_CM)
        runCurrent()
        assertTrue(controller.state.value is PrintState.Failed)
        port.prepareFails = false
        controller.enqueue(request(), PaperTail.ONE_CM)
        runCurrent()
        port.callbacks.single().onFinish()
        runCurrent()
        assertTrue(controller.state.value is PrintState.Finished)
    }
}
