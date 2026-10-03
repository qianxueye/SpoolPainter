package com.spoolpainter.app.data.remote.inventory

import com.spoolpainter.app.data.remote.spoolman.SpoolmanRepositoryHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InventoryConnectionReadOnlyTest {
    @get:Rule val tempFolder = TemporaryFolder()
    @Test fun `connecting an empty server never provisions or creates inventory`() = runTest {
        val harness = SpoolmanRepositoryHarness(tempFolder, initialUrl = "")
        harness.settingsRepository.setUrl("http://test.local/")
        assertTrue(harness.fakeApi.callLog.isNotEmpty())
        assertTrue(harness.fakeApi.callLog.all { it.startsWith("get") || it.startsWith("list") })
        assertTrue(harness.fakeApi.spoolExtraFields.isEmpty())
        assertTrue(harness.fakeApi.filamentExtraFields.isEmpty())
    }
}
