package com.spoolpainter.app.data.remote.inventory

import com.google.gson.JsonParser
import com.spoolpainter.app.ui.screens.inventory.InventoryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryPrintProvenanceTest {
    @Test fun `server switch followed by failed refresh rejects stale record print`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<InventoryRepository>()
            val spool = JsonParser.parseString("""{"id":1,"filament":{"id":2}}""").asJsonObject
            val originalUrl = "http://server-a.example"
            val snapshot = InventorySnapshot(originalUrl, "0.26", listOf(spool), emptyList(), emptyList())
            coEvery { repository.snapshot() } returns snapshot
            coEvery { repository.get("spool", 1, originalUrl) } returns spool
            val viewModel = InventoryViewModel(repository)
            viewModel.select("spool", 1)
            assertEquals(spool, viewModel.state.value.selected)
            coEvery { repository.snapshot() } throws InventoryFailure("new server unavailable")
            coEvery { repository.verifyServer(originalUrl) } throws IllegalStateException("服务器地址已改变，请刷新库存后重新操作")
            viewModel.refresh()
            assertEquals(originalUrl, viewModel.state.value.snapshot?.url)
            assertEquals(spool, viewModel.state.value.selected)
            var called = false
            viewModel.preparePrint { _, _ -> called = true }
            assertFalse(called)
            assertTrue(viewModel.state.value.error!!.contains("服务器地址已改变"))
            coVerify(exactly = 1) { repository.get(any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `successful print preflight carries the snapshot server with the record`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<InventoryRepository>()
            val spool = JsonParser.parseString("""{"id":1,"filament":{"id":2}}""").asJsonObject
            val url = "http://server-a.example"
            coEvery { repository.snapshot() } returns InventorySnapshot(url, "0.26", listOf(spool), emptyList(), emptyList())
            coEvery { repository.get("spool", 1, url) } returns spool
            coEvery { repository.verifyServer(url) } returns Unit
            val viewModel = InventoryViewModel(repository)
            viewModel.select("spool", 1)
            var capturedUrl: String? = null
            viewModel.preparePrint { actualRecord, actualUrl -> assertEquals(spool, actualRecord); capturedUrl = actualUrl }
            assertEquals(url, capturedUrl)
        } finally { Dispatchers.resetMain() }
    }
}
