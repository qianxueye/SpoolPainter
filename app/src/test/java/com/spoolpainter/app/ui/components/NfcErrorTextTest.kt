package com.spoolpainter.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class NfcErrorTextTest {
    @Test fun noCardTimeoutUsesPositionNeutralChinese() {
        val text = nfcErrorText("POS NFC: POS NFC scan timed out; present the tag again")
        assertTrue(text.contains("未检测到"))
        assertFalse(text.contains("侧面"))
        assertFalse(text.contains("硬件损坏"))
    }
    @Test fun cleanupFailureKeepsActualDiagnosticAndUnknownErrorsAreNotMasked() {
        val cleanup = "POS PICC cleanup failed (0xf3e4)"
        assertTrue(nfcErrorText(cleanup).contains(cleanup))
        assertTrue(nfcErrorText("POS cleanup failed (0xf37d)").contains("状态尚未确认"))
        val unknown = "Unexpected reader response (0xabcd)"
        assertEquals(unknown, nfcErrorText(unknown))
    }
}
