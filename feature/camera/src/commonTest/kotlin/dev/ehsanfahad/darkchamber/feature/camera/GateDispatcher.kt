package dev.ehsanfahad.darkchamber.feature.camera

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Runs what it is handed only when the test says so — an encrypt held at its doorstep, or a
 * resumption held between two steps, so a lock can be landed exactly there.
 */
internal class GateDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    val pending: Int get() = tasks.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runOne() {
        tasks.removeFirst().run()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) runOne()
    }
}
