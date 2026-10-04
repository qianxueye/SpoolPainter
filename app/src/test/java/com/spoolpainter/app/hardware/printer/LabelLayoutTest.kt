package com.spoolpainter.app.hardware.printer

import org.junit.Assert.*
import org.junit.Test

class LabelLayoutTest {
    private val metrics = object : LabelTextMetrics {
        override val lineHeightDots = 24
        override val baselineOffsetDots = 20f
        override fun measure(text: String) = text.codePointCount(0, text.length) * 10f
    }
    private fun request(paper: PaperTemplate = PaperTemplate()) = PrintRequest(
        SpoolLabel(123, "Vendor", "PLA", "FFFFFF", 250.0, "Shelf", "Full product name"),
        "https://example.org/stock", paper = paper,
    )

    @Test fun defaultPlanHasExactCanvasAndContainedContent() {
        val plan = LabelLayout.plan(request(), metrics)
        assertEquals(384, plan.widthDots)
        assertEquals(480, plan.heightDots)
        assertEquals(0, plan.paperLeftDots)
        assertEquals(384, plan.paperWidthDots)
        assertEquals(5, plan.elements.filterIsInstance<LabelElement.Text>().size)
        val qr = plan.elements.filterIsInstance<LabelElement.Qr>().single()
        assertEquals(192, qr.boxDots)
        assertTrue(qr.moduleScale >= 2)
        assertTrue(qr.y + qr.boxDots <= 464)
        assertTrue(qr.symbol.size * qr.moduleScale <= qr.boxDots)
    }

    @Test fun narrowerStockIsCenteredAndOffsetsMoveOnlyForwardAndRight() {
        val plan = LabelLayout.plan(request(PaperTemplate(widthMm = 40.0, offsetXmm = 2.0, offsetYmm = 1.0)), metrics)
        assertEquals(32, plan.paperLeftDots)
        val first = plan.elements.first() as LabelElement.Text
        assertEquals(64, first.x)
        assertEquals(44f, first.baselineY)
    }

    @Test fun wrapsFullUnicodeNameWithoutLosingCharacters() {
        val name = "厂商 Ultra Transparent 🧵 ".repeat(8)
        val value = request(PaperTemplate(heightMm = 200.0, selectedFields = setOf(LabelField.NAME)))
            .copy(label = request().label.copy(name = name))
        val lines = LabelLayout.plan(value, metrics).elements.filterIsInstance<LabelElement.Text>()
        assertTrue(lines.size > 1)
        assertEquals("名称：$name", lines.joinToString("") { it.text })
        assertTrue(lines.all { metrics.measure(it.text) <= 352 })
    }

    @Test fun longNameOverflowIsRejectedRatherThanTruncated() {
        val value = request(PaperTemplate(heightMm = 20.0, selectedFields = setOf(LabelField.NAME)))
            .copy(label = request().label.copy(name = "超长名称".repeat(100)))
        val failure = runCatching { LabelLayout.plan(value, metrics) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("完整名称不会截断"))
    }

    @Test fun qrTooWideOrTooDenseIsRejected() {
        val narrow = request(PaperTemplate(widthMm = 20.0, qrMm = 24.0, selectedFields = setOf(LabelField.QR)))
        assertTrue(runCatching { LabelLayout.plan(narrow, metrics) }.isFailure)
        val dense = request(PaperTemplate(qrMm = 10.0, selectedFields = setOf(LabelField.QR)))
        assertTrue(runCatching { LabelLayout.plan(dense, metrics) { QrSymbol(45, BooleanArray(45 * 45)) } }.isFailure)
    }

    @Test fun encodedQrHasFourModuleQuietZoneAndIntegerScale() {
        val qr = LabelLayout.plan(request(), metrics).elements.filterIsInstance<LabelElement.Qr>().single()
        val symbol = qr.symbol
        for (i in 0 until symbol.size) for (border in 0..3) {
            assertFalse(symbol.black(i, border))
            assertFalse(symbol.black(border, i))
            assertFalse(symbol.black(i, symbol.size - border - 1))
            assertFalse(symbol.black(symbol.size - border - 1, i))
        }
        assertEquals(qr.boxDots / symbol.size, qr.moduleScale)
    }

    @Test fun receiptTailAndFixedGapAreDistinct() {
        val receipt = request().copy(paper = null)
        assertEquals(120, receipt.feedDots(PaperTail.ONE_AND_HALF_CM))
        assertEquals(16, request().feedDots(PaperTail.TWO_AND_HALF_CM))
        assertEquals(0, request(PaperTemplate(gapMm = 0.0)).feedDots(PaperTail.ONE_AND_HALF_CM))
    }

    @Test fun invalidGeometryIsRejected() {
        val invalid = listOf(
            PaperTemplate(widthMm = 48.1), PaperTemplate(heightMm = Double.NaN),
            PaperTemplate(gapMm = -1.0), PaperTemplate(offsetXmm = -0.1),
            PaperTemplate(offsetYmm = -0.1), PaperTemplate(fontDots = 0),
            PaperTemplate(widthMm = 20.0, marginMm = 10.0), PaperTemplate(selectedFields = emptySet()),
        )
        invalid.forEach { assertTrue("Must reject $it", runCatching { it.validate() }.isFailure) }
    }

    @Test fun fieldSelectionPrintsOnlyRequestedFieldsAndDoesNotRemoveLetters() {
        val value = request(PaperTemplate(selectedFields = setOf(LabelField.NAME))).copy(label = request().label.copy(name = "Transparent\ntrnt"))
        val lines = LabelLayout.plan(value, metrics).elements.filterIsInstance<LabelElement.Text>()
        assertEquals("名称：Transparent trnt", lines.joinToString("") { it.text })
    }
}
