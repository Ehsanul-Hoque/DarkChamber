package dev.ehsanfahad.darkchamber.feature.vault.presentation

/**
 * The heights of a lazy column's [count] items as they are laid out, and from them the column's height
 * estimated: an item not laid out yet counts as the average of those that have been. The fast-scroll
 * thumb stands by these pixels, as a PDF's does by its known page heights — never by how many items
 * happen to be on screen, which changes as items of different heights come and go and would move the
 * thumb with it.
 *
 * Sums run on Fenwick trees, so recording a height and asking how far down an item starts take time
 * in the logarithm of [count] — a text file can be a million rows.
 */
internal class RowHeights(val count: Int) {
    /** Each item's height, or -1 while it has not been laid out. */
    private val heights = IntArray(count) { -1 }

    /** Fenwick trees over the known heights and over which items are known (1-based). */
    private val sums = LongArray(count + 1)
    private val known = IntArray(count + 1)
    private var knownSum = 0L
    private var knownCount = 0

    /** Record that item [index] was laid out [height] pixels tall. A later height replaces an earlier one. */
    fun record(index: Int, height: Int) {
        if (index !in 0 until count || height < 0) return
        val before = heights[index]
        if (before == height) return
        if (before < 0) {
            add(known, index, 1)
            knownCount++
            add(sums, index, height.toLong())
            knownSum += height
        } else {
            add(sums, index, (height - before).toLong())
            knownSum += height - before
        }
        heights[index] = height
    }

    /** The average height of the items laid out so far; 0 before any. */
    fun average(): Float = if (knownCount == 0) 0f else (knownSum.toDouble() / knownCount).toFloat()

    /** How far down the column item [index] starts, estimated; [count] gives the column's height. */
    fun top(index: Int): Float = topOf(index.coerceIn(0, count)).toFloat()

    /** The column's height, estimated. */
    fun total(): Float = top(count)

    /**
     * The item at [px] down the column and how far into it, estimated — the inverse of [top]. Past the
     * end, the last item; before the start, the first.
     */
    fun at(px: Float): Spot {
        // Nothing laid out, every top is 0 and nothing can be found by height: the start.
        if (count == 0 || knownCount == 0 || px <= 0f) return Spot(0, 0)
        // The last item starting at or above px: tops only grow, so a binary search finds it.
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (topOf(mid) <= px) low = mid else high = mid - 1
        }
        val height = heightOf(low)
        val into = (px - topOf(low)).toInt()
        return Spot(low, into.coerceIn(0, (height - 1).coerceAtLeast(0)))
    }

    private fun heightOf(index: Int): Int = heights[index].takeIf { it >= 0 } ?: average().toInt()

    /** The items before [index]: their known heights, and the rest at the average. */
    private fun topOf(index: Int): Double {
        val knownBefore = prefix(known, index)
        val unknownBefore = index - knownBefore
        val average = if (knownCount == 0) 0.0 else knownSum.toDouble() / knownCount
        return prefix(sums, index).toDouble() + unknownBefore * average
    }

    private fun add(tree: LongArray, index: Int, delta: Long) {
        var i = index + 1
        while (i <= count) {
            tree[i] += delta
            i += i and -i
        }
    }

    private fun add(tree: IntArray, index: Int, delta: Int) {
        var i = index + 1
        while (i <= count) {
            tree[i] += delta
            i += i and -i
        }
    }

    /** The sum over items 0 until [index]. */
    private fun prefix(tree: LongArray, index: Int): Long {
        var sum = 0L
        var i = index
        while (i > 0) {
            sum += tree[i]
            i -= i and -i
        }
        return sum
    }

    private fun prefix(tree: IntArray, index: Int): Int {
        var sum = 0
        var i = index
        while (i > 0) {
            sum += tree[i]
            i -= i and -i
        }
        return sum
    }

    data class Spot(val index: Int, val offsetPx: Int)
}
