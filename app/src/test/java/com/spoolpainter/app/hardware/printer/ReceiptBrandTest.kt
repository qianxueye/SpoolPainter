package com.spoolpainter.app.hardware.printer

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ReceiptBrandTest {
    @Test fun exactVendorIdentityIsCaseInsensitiveAndTrimsOuterWhitespace() {
        assertEquals(ReceiptBrand.INSLOGIC, ReceiptBrand.resolve("  inslogic  "))
        assertEquals(ReceiptBrand.POLYMAKER, ReceiptBrand.resolve("PolyMaker"))
        assertEquals(ReceiptBrand.SNAPMAKER, ReceiptBrand.resolve("snapmaker"))
        assertEquals(ReceiptBrand.BAMBU_LAB, ReceiptBrand.resolve(" bambu lab "))
        assertEquals(ReceiptBrand.BAMBU_LAB, ReceiptBrand.resolve("BambuLab"))
        assertEquals(ReceiptBrand.BAMBU_LAB, ReceiptBrand.resolve("拓竹"))
        assertEquals(ReceiptBrand.KEXCELLED, ReceiptBrand.resolve("Kexcelled"))
    }

    @Test fun unknownOrSimilarNamesNeverBorrowAnotherVendorsLogo() {
        listOf("", "Generic", "Polymaker reseller", "Snapmaker-compatible", "INSLOGIC3D", "INS LOGIC", "Bambu", "Bambu Lab reseller", "Kexcelled-compatible").forEach {
            assertNull("Must retain text without a guessed logo: $it", ReceiptBrand.resolve(it))
        }
    }

    @Test fun vendorMatchingDoesNotDependOnDeviceLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(ReceiptBrand.INSLOGIC, ReceiptBrand.resolve("inslogic"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun bundledWordmarksFitAndLeaveAnEightDotGapBeforeReceiptText() {
        val inslogic = ReceiptLogoLayout.fit(1199, 353)
        assertEquals(320, inslogic.widthDots)
        assertEquals(94, inslogic.artworkHeightDots)
        assertEquals(32, inslogic.leftDots)
        assertEquals(102, inslogic.contentTopDots)
        val polymaker = ReceiptLogoLayout.fit(600, 170)
        assertEquals(320, polymaker.widthDots)
        assertEquals(90, polymaker.artworkHeightDots)
        val snapmaker = ReceiptLogoLayout.fit(125, 24)
        assertEquals(320, snapmaker.widthDots)
        assertEquals(61, snapmaker.artworkHeightDots)
        val bambu = ReceiptLogoLayout.fit(1240, 360)
        assertEquals(320, bambu.widthDots)
        assertEquals(92, bambu.artworkHeightDots)
        val kexcelled = ReceiptLogoLayout.fit(1582, 378)
        assertEquals(320, kexcelled.widthDots)
        assertEquals(76, kexcelled.artworkHeightDots)
    }

    @Test fun allNamedStockBrandsHaveArtworkWhileGenericRetainsText() {
        val stockedBrands = listOf("Polymaker", "INSLOGIC", "Snapmaker", "BambuLab", "Kexcelled")
        assertEquals(ReceiptBrand.entries.toSet(), stockedBrands.map { ReceiptBrand.resolve(it) }.toSet())
        assertNull(ReceiptBrand.resolve("Generic"))
    }

    @Test fun tallArtworkUsesHeightLimitAndInvalidOrUnrenderableGeometryFails() {
        val tall = ReceiptLogoLayout.fit(100, 200)
        assertEquals(48, tall.widthDots)
        assertEquals(96, tall.artworkHeightDots)
        assertEquals(168, tall.leftDots)
        assertEquals(104, tall.contentTopDots)
        listOf(0 to 100, 100 to 0, -1 to 100, 1 to Int.MAX_VALUE).forEach { (width, height) ->
            assertTrue(runCatching { ReceiptLogoLayout.fit(width, height) }.isFailure)
        }
    }
}
