package com.slipstream.wheel.pad

import com.slipstream.wheel.protocol.TapNibbles

/**
 * Held bits and 4-bit tap counters of the 18 canonical buttons, packed so that each button's
 * held bit and its counter live in the same 64-bit word. One compare-and-set then updates
 * both, and a snapshot can never see a counter that went up without the held bit that goes
 * with it (PROTOCOL.md 12.3: the counter increments in the same snapshot that first sets the
 * held bit). Without that, the hub would replay a press the phone is still holding.
 *
 * Word 0 holds buttons 0..8, word 1 holds buttons 9..17. Inside a word, button index i
 * (0..8) has its counter at bits 4i..4i+3 and its held bit at bit 36 + i.
 */
object TapCounters {
    const val PER_WORD = 9
    private const val HELD_SHIFT = 36
    private const val TAPS_MASK = (1L shl HELD_SHIFT) - 1
    private const val HELD_MASK = ((1L shl PER_WORD) - 1) shl HELD_SHIFT

    fun wordOf(bit: Int): Int = bit / PER_WORD
    fun indexOf(bit: Int): Int = bit % PER_WORD

    fun held(word: Long, i: Int): Boolean = (word ushr (HELD_SHIFT + i)) and 1L != 0L

    fun tap(word: Long, i: Int): Int = ((word ushr (4 * i)) and 0xF).toInt()

    /**
     * Press-down: sets the held bit and increments the counter (wrapping 15 to 0) in one
     * step. A button that is already held is returned unchanged, so a second finger on the
     * same button, or a key repeat, is not a new press.
     */
    fun press(word: Long, i: Int): Long {
        if (held(word, i)) return word
        val shift = 4 * i
        val next = ((word ushr shift) + 1) and 0xF
        val withTap = (word and (0xFL shl shift).inv()) or (next shl shift)
        return withTap or (1L shl (HELD_SHIFT + i))
    }

    /** Release: clears the held bit. The counter is never touched. */
    fun release(word: Long, i: Int): Long = word and (1L shl (HELD_SHIFT + i)).inv()

    /** Every held bit cleared, counters kept. */
    fun releaseAll(word: Long): Long = word and TAPS_MASK

    fun heldAny(word: Long): Boolean = word and HELD_MASK != 0L

    /** The canonical 18-bit held mask from both words. */
    fun heldBits(w0: Long, w1: Long): Int {
        val lo = ((w0 ushr HELD_SHIFT) and 0x1FF).toInt()
        val hi = ((w1 ushr HELD_SHIFT) and 0x1FF).toInt()
        return lo or (hi shl PER_WORD)
    }

    /** Counters of all 18 buttons into [out] in canonical order. */
    fun unpackTaps(w0: Long, w1: Long, out: ByteArray) {
        for (i in 0 until PER_WORD) {
            out[i] = tap(w0, i).toByte()
            out[PER_WORD + i] = tap(w1, i).toByte()
        }
    }

    /** The hub's rule for one counter (PROTOCOL.md 12.4 rule 3). */
    fun delta(new: Int, old: Int): Int = TapNibbles.delta(new, old)

    /** Both words together cover exactly the canonical buttons. */
    const val BUTTONS = 2 * PER_WORD
}
