package com.spoolpainter.app.hardware.printer

import com.spoolpainter.app.hardware.paper.MotionUnknown
import com.spoolpainter.app.hardware.paper.PaperMotion
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrinterControllerTest {
    private fun request(id: Int = 42) = PrintRequest(SpoolLabel(id, "品牌", "PLA", "白色", 420.5, "A1"), "https://example.org/stock/")
    private class FakePort : PrinterPort {
        val events = mutableListOf<String>()
        var status = 0
        var opens = 0
        var closes = 0
        val requests = mutableListOf<PrintRequest>()
        val callbacks = mutableListOf<PrinterPort.Listener>()
        val feeds = mutableListOf<Int>()
        var prepareFails = false
        var dispatchFails = false
        override fun open() { events += "open"; opens++ }
        override fun state() = status
        override fun prepare(request: PrintRequest) { events += "prepare"; if (prepareFails) error("render failed"); requests += request }
        override fun begin(listener: PrinterPort.Listener) { events += "begin"; callbacks += listener; if (dispatchFails) error("dispatch failed") }
        override fun feed(dots: Int) { feeds += dots }
        override fun close() { closes++ }
    }

    @Test fun compensationRunsAfterSuccessfulPreparationAndBeforePrinting() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope, paperMotion = PaperMotion { port.events += "retract" })
        controller.enqueue(request().copy(retractBeforePrint = true), PaperTail.ONE_AND_HALF_CM)
        runCurrent()
        assertEquals(listOf("open", "prepare", "retract", "begin"), port.events)
        port.callbacks.single().onFinish()
        runCurrent()
        assertEquals(listOf(120), port.feeds)
    }

    @Test fun preparationFailureNeverRetractsOrPrints() = runTest {
        val port = FakePort().also { it.prepareFails = true }
        var motions = 0
        val controller = PrinterController(port, backgroundScope, paperMotion = PaperMotion { motions++ })
        controller.enqueue(request().copy(retractBeforePrint = true), PaperTail.ONE_CM)
        runCurrent()
        assertEquals(0, motions)
        assertTrue(port.callbacks.isEmpty())
        assertTrue(controller.state.value is PrintState.Failed)
    }

    @Test fun knownUnsentFailureNeverBeginsPrintAndAllowsExplicitRetry() = runTest {
        val port = FakePort()
        var fail = true
        var motions = 0
        val controller = PrinterController(port, backgroundScope, paperMotion = PaperMotion { motions++; if (fail) error("日志检查失败，未移动") })
        controller.enqueue(request().copy(retractBeforePrint = true), PaperTail.ONE_CM)
        runCurrent()
        assertEquals(1, motions)
        assertTrue(port.callbacks.isEmpty())
        assertTrue(controller.state.value is PrintState.Failed)
        fail = false
        controller.enqueue(request().copy(retractBeforePrint = true), PaperTail.ONE_CM)
        runCurrent()
        assertEquals(2, motions)
        assertEquals(1, port.callbacks.size)
        port.callbacks.single().onFinish()
        runCurrent()
    }

    @Test fun unknownMotionQuarantinesAllLaterJobsWithoutBeginningRaster() = runTest {
        val port = FakePort()
        val wake = mutableListOf<Boolean>()
        val controller = PrinterController(port, backgroundScope, holdAwake = { wake += it }, paperMotion = PaperMotion { throw MotionUnknown("回抽结果未知") })
        controller.enqueue(request(1).copy(retractBeforePrint = true), PaperTail.ONE_CM)
        controller.enqueue(request(2), PaperTail.ONE_CM)
        runCurrent()
        assertEquals(PrintState.Uncertain(1, "回抽结果未知"), controller.state.value)
        assertEquals(listOf(true, false), wake)
        assertTrue(port.callbacks.isEmpty())
        assertEquals(listOf(1), port.requests.map { it.label.id })
        assertFalse(controller.enqueue(request(3), PaperTail.ONE_CM))
        assertEquals(0, port.closes)
    }

    @Test fun unresolvedPersistedMotionBlocksEvenANormalReceiptBeforeOpen() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope, paperMotion = object : PaperMotion {
            override suspend fun retractBeforePrint() = Unit
            override fun checkNoPending() { throw MotionUnknown("存在未确认回抽") }
        })
        controller.enqueue(request(), PaperTail.ONE_CM)
        runCurrent()
        assertEquals(0, port.opens)
        assertTrue(controller.state.value is PrintState.Uncertain)
    }

    @Test fun queuedRequestsKeepTheirOwnCompensationSetting() = runTest {
        val port = FakePort()
        var motions = 0
        val controller = PrinterController(port, backgroundScope, paperMotion = PaperMotion { motions++ })
        controller.enqueue(request(1).copy(retractBeforePrint = true), PaperTail.ONE_CM)
        controller.enqueue(request(2), PaperTail.ONE_CM)
        runCurrent()
        port.callbacks[0].onFinish()
        runCurrent()
        assertEquals(1, motions)
        assertEquals(listOf(true, false), port.requests.map { it.retractBeforePrint })
        port.callbacks[1].onFinish()
        runCurrent()
    }

    @Test fun fixedLabelsCannotRequestCompensationAndDefaultIsOff() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        assertFalse(request().retractBeforePrint)
        assertTrue(runCatching { request().copy(paper = PaperTemplate(), retractBeforePrint = true) }.exceptionOrNull() is IllegalArgumentException)
        runCurrent()
        assertEquals(0, port.opens)
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

    @Test fun fixedLabelFeedsOnlyGapAndSnapshotsSelectedFields() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        val fields = mutableSetOf(LabelField.ID, LabelField.NAME)
        val value = request().copy(paper = PaperTemplate(gapMm = 2.0, selectedFields = fields))
        assertTrue(controller.enqueue(value, PaperTail.TWO_AND_HALF_CM))
        fields.clear()
        runCurrent()
        assertEquals(setOf(LabelField.ID, LabelField.NAME), port.requests.single().paper!!.selectedFields)
        port.callbacks.single().onFinish()
        runCurrent()
        assertEquals(listOf(16), port.feeds)
    }

    @Test fun zeroFixedGapDoesNotSendFeedCommand() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        controller.enqueue(request().copy(paper = PaperTemplate(gapMm = 0.0)), PaperTail.ONE_AND_HALF_CM)
        runCurrent()
        port.callbacks.single().onFinish()
        runCurrent()
        assertTrue(port.feeds.isEmpty())
    }

    @Test fun invalidPaperIsRejectedBeforeOpeningPrinter() = runTest {
        val port = FakePort()
        val controller = PrinterController(port, backgroundScope)
        assertFalse(controller.enqueue(request().copy(paper = PaperTemplate(widthMm = 49.0)), PaperTail.ONE_CM))
        runCurrent()
        assertEquals(0, port.opens)
        assertTrue(port.callbacks.isEmpty())
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
