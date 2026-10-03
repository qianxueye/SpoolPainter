package com.spoolpainter.app.data.remote.inventory

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.spoolpainter.app.ui.screens.inventory.InventoryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryMutationProvenanceTest {
    @Test fun `in flight A mutation cannot pair its result with B snapshot or reuse B identity`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repository = mockk<InventoryRepository>()
            val sourceA = "http://server-a.example"
            val sourceB = "http://server-b.example"
            val recordA = JsonParser.parseString("""{"id":1,"filament":{"id":2,"name":"A stock"},"location":"old"}""").asJsonObject
            val savedA = recordA.deepCopy().apply { addProperty("location", "new") }
            val recordB = JsonParser.parseString("""{"id":1,"filament":{"id":9,"name":"B stock"},"location":"B"}""").asJsonObject
            val snapshotA = InventorySnapshot(sourceA, "0.26", listOf(recordA), emptyList(), emptyList())
            val snapshotB = InventorySnapshot(sourceB, "0.26", listOf(recordB), emptyList(), emptyList())
            val completion = CompletableDeferred<JsonObject>()
            coEvery { repository.snapshot() } returns snapshotA
            coEvery { repository.get("spool", 1, sourceA) } returns recordA
            coEvery { repository.save("spool", recordA, any(), sourceA) } coAnswers { completion.await() }
            val viewModel = InventoryViewModel(repository)
            viewModel.select("spool", 1)
            viewModel.save(JsonObject().apply { addProperty("location", "new") })
            assertTrue(viewModel.state.value.busy)
            assertEquals(sourceA, viewModel.state.value.snapshot?.url)

            // Settings change while A's request is dispatched. The subsequent
            // current-settings refresh returns B with the same numeric spool ID.
            coEvery { repository.snapshot() } returns snapshotB
            completion.complete(savedA)
            assertFalse(viewModel.state.value.busy)
            assertNull(viewModel.state.value.snapshot)
            assertNull(viewModel.state.value.selected)
            assertTrue(viewModel.state.value.uncertain)
            assertTrue(viewModel.state.value.error!!.contains(sourceA))

            viewModel.save(JsonObject().apply { addProperty("location", "again") })
            viewModel.weight(100.0, false)
            viewModel.delete()
            viewModel.archive()
            viewModel.uid("04A1B2C3D4E5F6", false)
            var printed = false
            viewModel.preparePrint { _, _ -> printed = true }
            assertFalse(printed)
            coVerify(exactly = 1) { repository.save(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.weight(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.delete(any(), any(), any()) }
            coVerify(exactly = 0) { repository.bind(any(), any(), any(), any()) }

            // An explicit refresh may show B, but must require a new selection.
            viewModel.refresh()
            assertEquals(sourceB, viewModel.state.value.snapshot?.url)
            assertNull(viewModel.state.value.selected)
            assertFalse(viewModel.state.value.uncertain)
            viewModel.preparePrint { _, _ -> printed = true }
            assertFalse(printed)
            coEvery { repository.get("spool", 1, sourceB) } returns recordB
            coEvery { repository.verifyServer(sourceB) } returns Unit
            viewModel.select("spool", 1)
            viewModel.preparePrint { actual, url ->
                assertEquals(recordB, actual)
                assertEquals(sourceB, url)
                printed = true
            }
            assertTrue(printed)
        } finally { Dispatchers.resetMain() }
    }
}
