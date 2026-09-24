package com.slipstream.wheel.protocol

/**
 * TCP framing for the USB link (PROTOCOL.md section 8): u16 little-endian length, then
 * exactly that many bytes of one INPUT or STATUS packet.
 */
object Framing {
    const val HEADER_LEN = 2

    /** Writes the frame of packet[0, len) into out at outOff. Returns the frame length. */
    fun write(packet: ByteArray, len: Int, out: ByteArray, outOff: Int): Int {
        Le.putU16(out, outOff, len)
        System.arraycopy(packet, 0, out, outOff + HEADER_LEN, len)
        return len + HEADER_LEN
    }
}

fun interface FrameSink {
    /** [frame] holds one complete packet of [len] bytes. Valid only during the call. */
    fun onFrame(frame: ByteArray, len: Int)
}

/**
 * Reassembles frames from an arbitrary split of the TCP byte stream. Every frame must have
 * the length [expectedLen] (44 on the phone, 52 on the hub); anything else is a protocol
 * error and the caller closes the connection.
 */
class FrameAssembler(private val expectedLen: Int) {
    private val frame = ByteArray(expectedLen)
    private var headerFill = 0
    private var lenLow = 0
    private var bodyFill = 0

    fun reset() {
        headerFill = 0
        bodyFill = 0
    }

    /** Feeds src[off, off + len). Returns false on a bad frame length. */
    fun feed(src: ByteArray, off: Int, len: Int, sink: FrameSink): Boolean {
        var i = off
        val end = off + len
        while (i < end) {
            if (headerFill < Framing.HEADER_LEN) {
                val b = src[i++].toInt() and 0xFF
                if (headerFill == 0) {
                    lenLow = b
                    headerFill = 1
                } else {
                    val frameLen = lenLow or (b shl 8)
                    if (frameLen != expectedLen) {
                        reset()
                        return false
                    }
                    headerFill = 2
                    bodyFill = 0
                }
                continue
            }
            val n = minOf(expectedLen - bodyFill, end - i)
            System.arraycopy(src, i, frame, bodyFill, n)
            bodyFill += n
            i += n
            if (bodyFill == expectedLen) {
                headerFill = 0
                bodyFill = 0
                sink.onFrame(frame, expectedLen)
            }
        }
        return true
    }
}
