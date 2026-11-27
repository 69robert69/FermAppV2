package com.fermapp.api.common.util

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Replacements for the Promise helpers of the TypeScript server, on virtual threads:
 * `mapLimit` (bounded parallel work) and `Promise.all` for independent loads.
 */
object Concurrency {

    /** Runs the task for every item with at most [limit] running at the same time; waits for all. */
    @JvmStatic
    fun <T> mapLimit(items: List<T>, limit: Int, task: Consumer<T>) {
        val permits = Semaphore(Math.max(1, limit))
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            for (item in items) {
                permits.acquireUninterruptibly()
                // submit, not execute: a failing task ends in its Future, as before, and is not logged.
                executor.submit(
                    Runnable {
                        try {
                            task.accept(item)
                        } finally {
                            permits.release()
                        }
                    },
                )
            }
        }
    }

    /** Runs two independent loads in parallel and returns both results. */
    @JvmStatic
    fun <A, B> both(first: Supplier<A>, second: Supplier<B>): Pair<A, B> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val a = executor.submit(Callable { first.get() })
            val b = executor.submit(Callable { second.get() })
            Pair(get(a), get(b))
        }

    @JvmRecord
    data class Pair<A, B>(val first: A, val second: B)

    private fun <T> get(future: Future<T>): T {
        try {
            return future.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException(error)
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is RuntimeException) {
                throw cause
            }
            throw IllegalStateException(cause)
        }
    }
}
