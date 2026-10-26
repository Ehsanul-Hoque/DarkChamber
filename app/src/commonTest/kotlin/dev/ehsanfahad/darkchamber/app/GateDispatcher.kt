package dev.ehsanfahad.darkchamber.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Runs what it is handed only when the test says so — an encrypt held at its doorstep, so a lock can
 * land exactly there. (The feature's own copy lives in its test source set, which this module cannot
 * see.)
 */
class GateDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    val pending: Int get() = tasks.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
