package com.livetranslate.app.audio

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mixes two 16 kHz mono PCM16 LE streams (media + mic) by averaging samples.
 * Queues whole chunks (no per-byte boxing). Drops oldest samples when one side lags.
 */
class PcmMixer(
    private val onMixed: (ByteArray) -> Unit,
) {
    private val lock = Any()
    private val mediaQ = ArrayDeque<ByteArray>()
    private val micQ = ArrayDeque<ByteArray>()
    private var mediaOff = 0
    private var micOff = 0
    private var mediaBytes = 0
    private var micBytes = 0
    private val closed = AtomicBoolean(false)

    fun offerMedia(chunk: ByteArray) = offer(chunk, media = true)

    fun offerMic(chunk: ByteArray) = offer(chunk, media = false)

    fun close() {
        closed.set(true)
        synchronized(lock) {
            mediaQ.clear()
            micQ.clear()
            mediaOff = 0
            micOff = 0
            mediaBytes = 0
            micBytes = 0
        }
    }

    private fun offer(chunk: ByteArray, media: Boolean) {
        if (closed.get() || chunk.isEmpty()) return
        synchronized(lock) {
            if (closed.get()) return
            if (media) {
                mediaQ.addLast(chunk)
                mediaBytes += chunk.size
                trim(media = true)
            } else {
                micQ.addLast(chunk)
                micBytes += chunk.size
                trim(media = false)
            }
            drain()
        }
    }

    private fun trim(media: Boolean) {
        var bytes = if (media) mediaBytes else micBytes
        var off = if (media) mediaOff else micOff
        val q = if (media) mediaQ else micQ
        while (bytes > MAX_QUEUE_BYTES && q.isNotEmpty()) {
            val head = q.first()
            val remain = head.size - off
            if (bytes - remain >= MAX_QUEUE_BYTES) {
                q.removeFirst()
                bytes -= remain
                off = 0
            } else {
                var drop = bytes - MAX_QUEUE_BYTES
                if (drop % 2 != 0) drop++
                drop = drop.coerceAtMost(remain)
                off += drop
                bytes -= drop
                if (off >= head.size) {
                    q.removeFirst()
                    off = 0
                }
                break
            }
        }
        if (media) {
            mediaBytes = bytes
            mediaOff = off
        } else {
            micBytes = bytes
            micOff = off
        }
    }

    private fun drain() {
        val frames = minOf(mediaBytes, micBytes) / 2
        if (frames <= 0) return
        val out = ByteArray(frames * 2)
        var oi = 0
        repeat(frames) {
            val mixed = ((readSample(media = true) + readSample(media = false)) / 2)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[oi++] = (mixed and 0xFF).toByte()
            out[oi++] = ((mixed shr 8) and 0xFF).toByte()
        }
        if (oi > 0 && !closed.get()) onMixed(out)
    }

    private fun readSample(media: Boolean): Int {
        val lo = readByte(media).toInt() and 0xFF
        val hi = readByte(media).toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun readByte(media: Boolean): Byte {
        val q = if (media) mediaQ else micQ
        var off = if (media) mediaOff else micOff
        while (q.isNotEmpty()) {
            val head = q.first()
            if (off < head.size) {
                val b = head[off]
                off++
                if (media) {
                    mediaOff = off
                    mediaBytes--
                } else {
                    micOff = off
                    micBytes--
                }
                if (off >= head.size) {
                    q.removeFirst()
                    if (media) mediaOff = 0 else micOff = 0
                }
                return b
            }
            q.removeFirst()
            off = 0
            if (media) mediaOff = 0 else micOff = 0
        }
        return 0
    }

    companion object {
        private const val MAX_QUEUE_BYTES = 48_000
    }
}
