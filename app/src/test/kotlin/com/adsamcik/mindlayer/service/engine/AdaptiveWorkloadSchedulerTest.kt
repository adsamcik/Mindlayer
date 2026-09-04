package com.adsamcik.mindlayer.service.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveWorkloadSchedulerTest {

    @Test
    fun `loaded affinity wins before a cheaper cold request`() = runBlocking {
        var loaded: String? = "warm"
        val scheduler = AdaptiveWorkloadScheduler(loadedAffinity = { loaded })
        val blocker = CompletableDeferred<Unit>()
        val active = async {
            scheduler.run(request("warm", cost = 50)) { blocker.await() }
        }
        awaitQueued(scheduler, 0, activeAffinity = "warm")

        val order = mutableListOf<String>()
        val cold = async { scheduler.run(request("cold", cost = 1)) { order += "cold" } }
        val warm = async { scheduler.run(request("warm", cost = 100)) { order += "warm" } }
        awaitQueued(scheduler, 2)

        blocker.complete(Unit)
        active.await()
        warm.await()
        cold.await()

        assertEquals(listOf("warm", "cold"), order)
        loaded = null
    }

    @Test
    fun `equal priority work runs cheapest first without affinity`() = runBlocking {
        val scheduler = AdaptiveWorkloadScheduler(loadedAffinity = { null })
        val blocker = CompletableDeferred<Unit>()
        val active = async { scheduler.run(request("active", cost = 1)) { blocker.await() } }
        awaitQueued(scheduler, 0, activeAffinity = "active")

        val order = mutableListOf<String>()
        val long = async { scheduler.run(request("long", cost = 1_000)) { order += "long" } }
        val cheap = async { scheduler.run(request("cheap", cost = 10)) { order += "cheap" } }
        awaitQueued(scheduler, 2)

        blocker.complete(Unit)
        active.await()
        cheap.await()
        long.await()

        assertEquals(listOf("cheap", "long"), order)
    }

    @Test
    fun `explicit priority wins before cost inside an affinity band`() = runBlocking {
        val scheduler = AdaptiveWorkloadScheduler(loadedAffinity = { null })
        val blocker = CompletableDeferred<Unit>()
        val active = async { scheduler.run(request("active", cost = 1)) { blocker.await() } }
        awaitQueued(scheduler, 0, activeAffinity = "active")

        val order = mutableListOf<String>()
        val cheap = async {
            scheduler.run(request("cheap", priority = 0, cost = 1)) { order += "cheap" }
        }
        val important = async {
            scheduler.run(request("important", priority = 5, cost = 5_000)) { order += "important" }
        }
        awaitQueued(scheduler, 2)

        blocker.complete(Unit)
        active.await()
        important.await()
        cheap.await()

        assertEquals(listOf("important", "cheap"), order)
    }

    @Test
    fun `starved work overrides newly queued loaded affinity`() = runBlocking {
        var now = 0L
        val scheduler = AdaptiveWorkloadScheduler(
            loadedAffinity = { "warm" },
            clockMs = { now },
            starvationMs = 30_000L,
        )
        val blocker = CompletableDeferred<Unit>()
        val active = async { scheduler.run(request("warm", cost = 1)) { blocker.await() } }
        awaitQueued(scheduler, 0, activeAffinity = "warm")

        val order = mutableListOf<String>()
        val oldCold = async {
            scheduler.run(request("cold", cost = 100)) { order += "cold" }
        }
        awaitQueued(scheduler, 1)
        now = 30_001L
        val newWarm = async {
            scheduler.run(request("warm", cost = 1)) { order += "warm" }
        }
        awaitQueued(scheduler, 2)

        blocker.complete(Unit)
        active.await()
        oldCold.await()
        newWarm.await()

        assertEquals(listOf("cold", "warm"), order)
    }

    @Test
    fun `affinity burst yields to another waiting session`() = runBlocking {
        val scheduler = AdaptiveWorkloadScheduler(
            loadedAffinity = { "warm" },
            maxAffinityBurst = 4,
        )
        val blocker = CompletableDeferred<Unit>()
        val active = async { scheduler.run(request("warm", cost = 1)) { blocker.await() } }
        awaitQueued(scheduler, 0, activeAffinity = "warm")

        val order = mutableListOf<String>()
        val warmJobs = (1..4).map { index ->
            async {
                scheduler.run(request("warm", priority = 10, cost = index)) {
                    order += "warm-$index"
                }
            }
        }
        val cold = async {
            scheduler.run(request("cold", priority = -10, cost = 1)) { order += "cold" }
        }
        awaitQueued(scheduler, 5)

        blocker.complete(Unit)
        active.await()
        warmJobs.forEach { it.await() }
        cold.await()

        assertEquals(listOf("warm-1", "warm-2", "warm-3", "cold", "warm-4"), order)
    }

    @Test
    fun `cancelling a queued request removes it without blocking successors`() = runBlocking {
        val scheduler = AdaptiveWorkloadScheduler(loadedAffinity = { null })
        val blocker = CompletableDeferred<Unit>()
        val active = async { scheduler.run(request("active", cost = 1)) { blocker.await() } }
        awaitQueued(scheduler, 0, activeAffinity = "active")

        val cancelled = async { scheduler.run(request("cancel", cost = 1)) { error("must not run") } }
        val survivor = async { scheduler.run(request("survivor", cost = 2)) { "done" } }
        awaitQueued(scheduler, 2)
        cancelled.cancelAndJoin()
        awaitQueued(scheduler, 1)

        blocker.complete(Unit)
        active.await()
        assertEquals("done", survivor.await())
        val final = scheduler.snapshot()
        assertNull(final.activeAffinity)
        assertEquals(0, final.queuedCount)
    }

    private fun request(
        affinity: String,
        priority: Int = 0,
        cost: Int,
    ) = AdaptiveWorkloadScheduler.Request(
        affinityKey = affinity,
        priority = priority,
        estimatedCost = cost,
    )

    private suspend fun awaitQueued(
        scheduler: AdaptiveWorkloadScheduler,
        queued: Int,
        activeAffinity: String? = null,
    ) {
        withTimeout(2_000L) {
            while (true) {
                val snapshot = scheduler.snapshot()
                if (snapshot.queuedCount == queued &&
                    (activeAffinity == null || snapshot.activeAffinity == activeAffinity)
                ) {
                    return@withTimeout
                }
                yield()
            }
        }
    }
}
