package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class FileDownloadProgress(
    val fileId: String,
    val fileName: String,
    val totalChunks: Int,
    val receivedChunks: Set<Int>,
    val totalBytes: Long,
    val isComplete: Boolean = false,
)

/**
 * Manages Pull-based chunked file transfers over mesh ad-hoc radios.
 * Replaces blind flooding (Push) with on-demand (Pull) chunk retrieval (32 KB - 64 KB),
 * per-block SHA-256 verification, and seamless resumption across connection drops.
 */
@OptIn(ExperimentalEncodingApi::class)
class PullBasedChunkTransferManager {

    private val mutex = Mutex()
    private val activeDescriptors = mutableMapOf<String, MeshFileDescriptor>()
    private val chunkBuffers = mutableMapOf<String, MutableMap<Int, MeshChunkData>>()
    private val localFiles = mutableMapOf<String, ByteArray>()

    private val _downloadProgress = MutableSharedFlow<FileDownloadProgress>(extraBufferCapacity = 64)
    val downloadProgress: SharedFlow<FileDownloadProgress> = _downloadProgress.asSharedFlow()

    /** Registers a lightweight FileDescriptor received via the control plane (Gossip). */
    suspend fun registerDescriptor(descriptor: MeshFileDescriptor) = mutex.withLock {
        activeDescriptors[descriptor.fileId] = descriptor
        if (!chunkBuffers.containsKey(descriptor.fileId)) {
            chunkBuffers[descriptor.fileId] = mutableMapOf()
        }
    }

    fun getDescriptor(fileId: String): MeshFileDescriptor? {
        return activeDescriptors[fileId]
    }

    suspend fun registerLocalFile(fileId: String, data: ByteArray) = mutex.withLock {
        localFiles[fileId] = data
    }

    fun computeSha256(bytes: ByteArray): String = MeshSha256.sha256Hex(bytes)

    suspend fun getChunkData(fileId: String, chunkIndex: Int): MeshChunkData? = mutex.withLock {
        val data = localFiles[fileId] ?: return@withLock null
        val desc = activeDescriptors[fileId]
        val chunkSize = desc?.chunkSize ?: 65536
        val start = chunkIndex * chunkSize
        if (start >= data.size) return@withLock null
        val end = minOf(start + chunkSize, data.size)
        val slice = data.copyOfRange(start, end)
        MeshChunkData(
            fileId = fileId,
            chunkIndex = chunkIndex,
            dataBase64 = Base64.Default.encode(slice),
            sha256 = MeshSha256.sha256Hex(slice)
        )
    }

    /**
     * Determines the next missing chunk index to request, enabling
     * effortless resumption if transfer was interrupted.
     */
    suspend fun getNextMissingChunkIndex(fileId: String): Int? = mutex.withLock {
        val desc = activeDescriptors[fileId] ?: return@withLock null
        val received = chunkBuffers[fileId]?.keys ?: emptySet()
        for (i in 0 until desc.totalChunks) {
            if (!received.contains(i)) {
                return@withLock i
            }
        }
        return@withLock null
    }

    suspend fun getReceivedChunkCount(fileId: String): Int = mutex.withLock {
        chunkBuffers[fileId]?.size ?: 0
    }

    /** Creates a CHUNK_REQUEST packet for the designated chunk index. */
    suspend fun createChunkRequest(fileId: String, requesterUid: String): MeshChunkRequest? {
        val nextIndex = getNextMissingChunkIndex(fileId) ?: return null
        return MeshChunkRequest(fileId = fileId, chunkIndex = nextIndex, requesterUid = requesterUid)
    }

    /**
     * Ingests a received CHUNK_DATA payload, verifies integrity, stores in memory buffer,
     * and notifies listeners of updated progress.
     */
    suspend fun onChunkReceived(chunk: MeshChunkData): Boolean = mutex.withLock {
        val desc = activeDescriptors[chunk.fileId] ?: return@withLock false
        if (chunk.chunkIndex !in 0 until desc.totalChunks) return@withLock false

        val decoded = runCatching { Base64.Default.decode(chunk.dataBase64) }.getOrNull() ?: return@withLock false
        if (chunk.sha256.isNotBlank()) {
            val actualHash = MeshSha256.sha256Hex(decoded)
            if (!actualHash.equals(chunk.sha256, ignoreCase = true)) {
                chunkBuffers[chunk.fileId]?.remove(chunk.chunkIndex)
                return@withLock false
            }
        }

        val buffer = chunkBuffers.getOrPut(chunk.fileId) { mutableMapOf() }

        // Store verified chunk
        buffer[chunk.chunkIndex] = chunk

        val isComplete = buffer.size >= desc.totalChunks
        val progress = FileDownloadProgress(
            fileId = desc.fileId,
            fileName = desc.fileName,
            totalChunks = desc.totalChunks,
            receivedChunks = buffer.keys.toSet(),
            totalBytes = desc.totalSize,
            isComplete = isComplete
        )
        _downloadProgress.tryEmit(progress)

        return@withLock isComplete
    }

    suspend fun assembleFile(fileId: String): ByteArray? = mutex.withLock {
        val desc = activeDescriptors[fileId] ?: return@withLock null
        val buffer = chunkBuffers[fileId] ?: return@withLock null
        if (buffer.size < desc.totalChunks) return@withLock null

        val parts = ArrayList<ByteArray>(desc.totalChunks)
        var actualTotal = 0
        for (i in 0 until desc.totalChunks) {
            val chunk = buffer[i] ?: return@withLock null
            val bytes = runCatching { Base64.Default.decode(chunk.dataBase64) }.getOrNull() ?: return@withLock null
            if (chunk.sha256.isNotBlank() && !MeshSha256.sha256Hex(bytes).equals(chunk.sha256, ignoreCase = true)) {
                buffer.remove(i)
                return@withLock null
            }
            parts.add(bytes)
            actualTotal += bytes.size
        }

        val result = ByteArray(actualTotal)
        var offset = 0
        for (bytes in parts) {
            bytes.copyInto(result, offset)
            offset += bytes.size
        }

        if (desc.sha256.isNotBlank() && !MeshSha256.sha256Hex(result).equals(desc.sha256, ignoreCase = true)) {
            chunkBuffers.remove(fileId)
            return@withLock null
        }

        chunkBuffers.remove(fileId)
        result
    }

    /** Clears temporary memory chunks for a given file once persistence/reassembly is complete. */
    suspend fun clearFileBuffer(fileId: String) = mutex.withLock {
        chunkBuffers.remove(fileId)
        localFiles.remove(fileId)
    }
}
