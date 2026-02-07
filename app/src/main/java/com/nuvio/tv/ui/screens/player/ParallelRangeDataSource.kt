package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A DataSource that downloads progressive files using multiple parallel HTTP range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (due to CDN per-connection limits
 * or Java/Okio networking overhead). By downloading different byte ranges in parallel across
 * multiple connections, we can multiply the effective throughput (e.g., 3 connections ≈ 300 Mbps).
 *
 * Uses a buffer pool to reuse ByteArrays and avoid GC churn from large object allocations.
 *
 * Only used for progressive downloads (MKV, MP4). HLS/DASH already handle chunked parallel downloads.
 */
@UnstableApi
class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = 3,
    private val chunkSize: Long = 8L * 1024 * 1024 // 8MB per chunk
) : DataSource {

    companion object {
        private const val TAG = "ParallelRangeDS"
    }

    /**
     * A downloaded chunk: a pooled byte array plus the actual number of bytes written.
     * The array may be larger than [size] (it's from the pool).
     */
    private class DownloadedChunk(val data: ByteArray, val size: Int)

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)

    // Chunk download state
    private val chunks = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
    private var executor = Executors.newFixedThreadPool(parallelConnections)

    // Buffer pool: reuse byte arrays to avoid GC churn from LOS allocations
    private val bufferPool = ArrayDeque<ByteArray>()
    private val maxPoolSize = parallelConnections + 2

    // Current chunk being served to ExoPlayer
    private var currentChunk: DownloadedChunk? = null
    private var currentChunkIndex: Long = -1
    private var currentChunkReadOffset: Int = 0

    private val transferListeners = mutableListOf<TransferListener>()

    // Fallback: if parallel mode fails, use a single upstream DataSource
    private var fallbackSource: OkHttpDataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        closed.set(false)
        originalDataSpec = dataSpec
        position = dataSpec.position

        // Recreate executor if it was shut down by a previous close()
        if (executor.isShutdown) {
            executor = Executors.newFixedThreadPool(parallelConnections)
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }

        val openLength: Long
        try {
            openLength = probeSource.open(dataSpec)
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
        } catch (e: Exception) {
            probeSource.close()
            throw e
        }

        // Check if we can do parallel range requests
        val responseHeaders = probeSource.responseHeaders
        val acceptsRanges = responseHeaders["Accept-Ranges"]?.any { it.contains("bytes") } == true ||
                responseHeaders["Content-Range"]?.isNotEmpty() == true
        probeSource.close()

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — fall back to single connection
            Log.w(TAG, "Falling back to single connection (length=${openLength}, acceptsRanges=$acceptsRanges)")
            fallbackSource = upstreamFactory.createDataSource()
            transferListeners.forEach { fallbackSource!!.addTransferListener(it) }
            return fallbackSource!!.open(dataSpec)
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        Log.d(TAG, "Parallel mode: ${parallelConnections} connections, ${chunkSize / 1024 / 1024}MB chunks, " +
                "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}")

        // Schedule initial chunks for download
        scheduleChunks()

        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // Fallback mode: delegate to single upstream
        fallbackSource?.let { return it.read(buffer, offset, length) }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize

        // Load the chunk for the current position
        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            ensureChunkScheduled(chunkIndex)
            val future = chunks[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            try {
                currentChunk = future.get() // Block until chunk is downloaded
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            // Clean up old chunks (returns buffers to pool) and schedule new ones
            cleanupOldChunks(chunkIndex)
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            // Current chunk exhausted, move to next
            currentChunk = null
            return read(buffer, offset, length)
        }

        val readSize = minOf(toRead, available)
        System.arraycopy(chunk.data, currentChunkReadOffset, buffer, offset, readSize)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize

        return readSize
    }

    private fun scheduleChunks() {
        val currentChunkIdx = position / chunkSize
        val maxAhead = parallelConnections + 1

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        chunks.computeIfAbsent(chunkIndex) {
            CompletableFuture.supplyAsync({ downloadChunk(chunkIndex) }, executor)
        }
    }

    private fun downloadChunk(chunkIndex: Long): DownloadedChunk {
        val start = chunkIndex * chunkSize
        val end = if (totalFileLength != C.LENGTH_UNSET.toLong()) {
            minOf(start + chunkSize, totalFileLength)
        } else {
            start + chunkSize
        }
        val rangeLength = end - start

        val ds = upstreamFactory.createDataSource()
        val uri = resolvedUri ?: originalDataSpec?.uri ?: throw IOException("No URI available")
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(start)
            .setLength(rangeLength)
            .build()

        ds.open(spec)

        // Get a buffer from the pool or allocate a new one
        val buffer = acquireBuffer()
        var totalRead = 0
        try {
            while (!closed.get()) {
                val maxRead = minOf(buffer.size - totalRead, 64 * 1024)
                if (maxRead <= 0) break
                val read = ds.read(buffer, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }
        } catch (e: Exception) {
            // Return buffer to pool on failure
            releaseBuffer(buffer)
            ds.close()
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        ds.close()

        if (closed.get()) {
            releaseBuffer(buffer)
            throw IOException("DataSource closed")
        }

        return DownloadedChunk(buffer, totalRead)
    }

    private fun acquireBuffer(): ByteArray {
        synchronized(bufferPool) {
            return bufferPool.removeLastOrNull() ?: ByteArray(chunkSize.toInt())
        }
    }

    private fun releaseBuffer(buffer: ByteArray) {
        synchronized(bufferPool) {
            if (bufferPool.size < maxPoolSize) {
                bufferPool.addLast(buffer)
            }
            // else: let it be GC'd (pool is full)
        }
    }

    private fun cleanupOldChunks(currentChunkIndex: Long) {
        val iter = chunks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < currentChunkIndex) {
                val future = entry.value
                // Return the buffer to the pool if the chunk was downloaded
                if (future.isDone && !future.isCancelled) {
                    try {
                        releaseBuffer(future.get().data)
                    } catch (_: Exception) {}
                }
                future.cancel(true)
                iter.remove()
            }
        }
    }

    override fun close() {
        closed.set(true)
        fallbackSource?.close()
        fallbackSource = null

        // Return current chunk's buffer to pool
        currentChunk?.let { releaseBuffer(it.data) }
        currentChunk = null
        currentChunkIndex = -1

        // Return all in-flight chunk buffers to pool
        chunks.values.forEach { future ->
            if (future.isDone && !future.isCancelled) {
                try { releaseBuffer(future.get().data) } catch (_: Exception) {}
            }
            future.cancel(true)
        }
        chunks.clear()
        executor.shutdownNow()

        // Clear the pool on close to free memory
        synchronized(bufferPool) {
            bufferPool.clear()
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? = resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackSource?.responseHeaders ?: emptyMap()

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = 3,
        private val chunkSize: Long = 8L * 1024 * 1024
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ParallelRangeDataSource(upstreamFactory, parallelConnections, chunkSize)
        }
    }
}
