package dev.psyclyx.krylov

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DeferredRequest(val until: Long, message: String) : IOException(message)

/** One in flight, no token accumulation/bursts, foreground-first admission. */
class RequestGate(private val now: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var active = false
    private var foregroundWaiting = 0
    private var next = 0L
    var cooldownUntil = 0L
        private set
    fun cooldown(until: Long) = lock.withLock { cooldownUntil=maxOf(cooldownUntil,until); changed.signalAll() }
    fun <T> run(background: Boolean, block: () -> T): T {
        lock.withLock {
            if(!background) foregroundWaiting++
            try {
                while(true) {
                    val time=now()
                    if(time<cooldownUntil) throw DeferredRequest(cooldownUntil,"Wikipedia asked us to slow down. Saved articles are still available.")
                    val delay=next-time
                    if(!active && (!background || foregroundWaiting==0) && delay<=0) { active=true; next=time+500; break }
                    changed.await(if(delay>0) delay.coerceAtMost(1000) else 1000,TimeUnit.MILLISECONDS)
                }
            } finally { if(!background) foregroundWaiting-- }
        }
        try { return block() } finally { lock.withLock { active=false; changed.signalAll() } }
    }
}
