package com.spoolpainter.app.hardware.printer

import org.junit.Assert.*
import org.junit.Test

class CheckedPrinterFeedTest {
    class NativePrinter(var result: Int = 0) {
        val calls = mutableListOf<Int>()
        fun setPrintCtrlFeed(dots: Int): Int { calls += dots; return result }
    }
    class Manager(private val printer: NativePrinter?)

    @Test fun feedsExistingLeaseExactlyOnceInDots() {
        val native = NativePrinter()
        checkedForwardFeed(Manager(native), 16)
        assertEquals(listOf(16), native.calls)
    }
    @Test fun rejectedFeedThrowsWithoutFallbackOrRetry() {
        val native = NativePrinter(-1)
        assertTrue(runCatching { checkedForwardFeed(Manager(native), 16) }.isFailure)
        assertEquals(listOf(16), native.calls)
    }
    @Test fun zeroDoesNothingAndNegativeIsRejected() {
        val native = NativePrinter()
        checkedForwardFeed(Manager(native), 0)
        assertTrue(runCatching { checkedForwardFeed(Manager(native), -1) }.isFailure)
        assertTrue(native.calls.isEmpty())
    }
    @Test fun missingLeaseFailsClosed() {
        assertTrue(runCatching { checkedForwardFeed(Manager(null), 16) }.isFailure)
        assertTrue(runCatching { checkedForwardFeed(Any(), 16) }.isFailure)
    }
}
