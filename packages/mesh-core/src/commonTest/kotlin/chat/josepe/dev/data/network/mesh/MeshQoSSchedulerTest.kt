package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshQoSSchedulerTest {

    private fun dummyEnvelope(id: String) = MeshEnvelope(
        messageId = id,
        chatId = "chat_1",
        chatType = "DIRECT",
        senderId = "alice",
        targetId = "bob",
        type = "text",
        ciphertext = "cipher",
        iv = "iv",
    )

    @Test
    fun highPriorityPreemptsLowPriorityChunks() = runBlocking {
        val scheduler = MeshQoSScheduler()
        assertFalse(scheduler.hasPendingHighPriority())

        scheduler.enqueue(
            PrioritizedMeshPacket(
                id = "chunk_1",
                priority = MeshPacketPriority.LOW,
                envelope = dummyEnvelope("chunk_1"),
                isTextOrControl = false,
            )
        )
        scheduler.enqueue(
            PrioritizedMeshPacket(
                id = "text_1",
                priority = MeshPacketPriority.HIGH,
                envelope = dummyEnvelope("text_1"),
                isTextOrControl = true,
            )
        )
        scheduler.enqueue(
            PrioritizedMeshPacket(
                id = "chunk_2",
                priority = MeshPacketPriority.LOW,
                envelope = dummyEnvelope("chunk_2"),
                isTextOrControl = false,
            )
        )

        assertTrue(scheduler.hasPendingHighPriority())
        assertEquals("text_1", scheduler.dequeueNext()?.id)
        assertFalse(scheduler.hasPendingHighPriority())
        assertEquals("chunk_1", scheduler.dequeueNext()?.id)
        assertEquals("chunk_2", scheduler.dequeueNext()?.id)
        assertNull(scheduler.dequeueNext())
    }
}
