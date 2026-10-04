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
class InventoryQrRoutingTest {
    @Test fun `camera payload fetches fresh spool including archive without writing inventory`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<InventoryRepository>()
            val url = "http://inventory.example:7912"
            val spool = JsonParser.parseString("""{"id":123,"archived":true,"filament":{"id":9}}""").asJsonObject
            coEvery { repository.snapshot() } returns InventorySnapshot(url, "0.26", emptyList(), emptyList(), emptyList())
            coEvery { repository.get("spool", 123, url) } returns spool
            coEvery { repository.verifyServer(url) } returns Unit
            val model = InventoryViewModel(repository)
            model.openQr("WEB+SPOOLMAN:S-123")
            assertEquals(spool, model.state.value.selected)
            assertEquals("spool", model.state.value.selectedEntity)
            model.openQr("$url/spool/show/123")
            model.openQr("$url/?sel=spool:123")
            coVerify(exactly = 3) { repository.get("spool", 123, url) }
            coVerify(exactly = 0) { repository.save(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.bind(any(), any(), any(), any()) }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `foreign QR never fetches its ID and changed server failure remains visible`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<InventoryRepository>()
            val url = "http://inventory.example:7912"
            coEvery { repository.snapshot() } returns InventorySnapshot(url, "0.26", emptyList(), emptyList(), emptyList())
            val model = InventoryViewModel(repository)
            model.openQr("http://other.example:7912/spool/show/123")
            assertNotNull(model.state.value.error)
            assertNull(model.state.value.selected)
            coVerify(exactly = 0) { repository.get(any(), any(), any()) }
            coEvery { repository.get("spool", 123, url) } throws IllegalStateException("服务器地址已改变")
            model.openQr("WEB+SPOOLMAN:S-123")
            assertEquals("服务器地址已改变", model.state.value.error)
            assertNull(model.state.value.selected)
        } finally { Dispatchers.resetMain() }
    }
}
