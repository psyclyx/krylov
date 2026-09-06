package dev.psyclyx.krylov

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run with the compiled app classes; these checks do not make network requests. */
fun main() {
    val batch=Wikipedia.RevisionBatch(
        listOf("United States" to 100L), null,
        mapOf("usa" to "Usa", "Usa" to "United States"), setOf("Missing page")
    )
    check(batch.canonical("usa")=="United States")
    check(batch.canonical("Usa")=="United States")
    check(batch.canonical("Missing page") in batch.missing)
    check(batch.canonical("Other page")=="Other page")
    check(runCatching {
        batch.copy(aliases=mapOf("A" to "B", "B" to "A")).canonical("A")
    }.exceptionOrNull() is java.io.IOException)

    val gate=RequestGate()
    val entered=CountDownLatch(1)
    val release=CountDownLatch(1)
    val failure=AtomicReference<Throwable?>()
    val order=java.util.Collections.synchronizedList(mutableListOf<String>())
    fun launch(work: () -> Unit)=Thread {
        try { work() } catch(t: Throwable) { failure.compareAndSet(null,t) }
    }.apply { start() }
    fun awaitWaiting(thread: Thread) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(thread.state !in setOf(Thread.State.WAITING,Thread.State.TIMED_WAITING)) {
            check(thread.isAlive && System.nanoTime()<deadline)
            Thread.sleep(1)
        }
    }
    val active=launch { gate.run(false) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) } }
    check(entered.await(3,TimeUnit.SECONDS))
    val prefetch=launch { gate.run(true) { order.add("prefetch") } }
    awaitWaiting(prefetch)
    val interactive=launch { gate.run(false) { order.add("interactive") } }
    awaitWaiting(interactive)
    release.countDown()
    listOf(active,prefetch,interactive).forEach { it.join(5000); check(!it.isAlive) }
    failure.get()?.let { throw it }
    check(order==listOf("interactive","prefetch"))

    val coolingGate=RequestGate()
    val decoding=CountDownLatch(1)
    val finishDecoding=CountDownLatch(1)
    val response=launch {
        coolingGate.run(true) {
            decoding.countDown(); check(finishDecoding.await(5,TimeUnit.SECONDS))
            coolingGate.cooldown(System.currentTimeMillis()+60_000)
        }
    }
    check(decoding.await(3,TimeUnit.SECONDS))
    val waiting=launch {
        check(runCatching { coolingGate.run(false) { error("Admitted during cooldown") } }.exceptionOrNull() is DeferredRequest)
    }
    awaitWaiting(waiting)
    finishDecoding.countDown()
    listOf(response,waiting).forEach { it.join(5000); check(!it.isAlive) }
    failure.get()?.let { throw it }
    println("PASS: redirect chains, missing pages, interactive priority, cooldown before next admission")
}
