package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PullBasedChunkTransferManagerTest {

    @Test
    fun rejectsCorruptedChunkAndResumesMissingChunkToAssembleFile() = runBlocking {
        val manager = PullBasedChunkTransferManager()
        val payload = ByteArray(1400) { ((it * 7) % 251).toByte() }
        val desc = MeshFileDescriptor(
            fileId = "f_resume",
            fileName = "photo.webp",
            totalSize = payload.size.toLong(),
            chunkSize = 512,
            totalChunks = 3,
            sha256 = manager.computeSha256(payload),
            senderUid = "alice",
            targetUid = "bob",
        )

        manager.registerDescriptor(desc)
        manager.registerLocalFile("f_resume", payload)

        val c0 = manager.getChunkData("f_resume", 0)!!
        val c1 = manager.getChunkData("f_resume", 1)!!
        val c2 = manager.getChunkData("f_resume", 2)!!

        assertFalse(manager.onChunkReceived(c0))
        // Corrupt chunk 1 hash to verify tamper detection
        val tamperedC1 = c1.copy(sha256 = "0".repeat(64))
        assertFalse(manager.onChunkReceived(tamperedC1))
        assertEquals(1, manager.getReceivedChunkCount("f_resume"))

        // Resumption must request chunk 1 next
        val nextReq = manager.createChunkRequest("f_resume", "bob")
        assertNotNull(nextReq)
        assertEquals(1, nextReq.chunkIndex)

        assertFalse(manager.onChunkReceived(c1))
        assertTrue(manager.onChunkReceived(c2))
        assertNull(manager.getNextMissingChunkIndex("f_resume"))

        val assembled = manager.assembleFile("f_resume")
        assertNotNull(assembled)
        assertTrue(payload.contentEquals(assembled))
    }
}
