package dev.psyclyx.krylov

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Path-dependent zoom: each detent captures a range around the current target.
 * Reanchoring preserves selection when narrowing or widening. All targets are
 * reachable at level zero, including with an off-center thumb origin. */
class ThumbRange(val count: Int, private val minimum: Float, private val maximum: Float,
                 start: Int, cross: Float) {
    data class Window(val first: Int, val last: Int)
    private val windows=mutableListOf(Window(0,(count-1).coerceAtLeast(0)))
    var selected=start.coerceIn(0,(count-1).coerceAtLeast(0)); private set
    private var anchor=cross.coerceIn(minimum+1,maximum-1)
    private var anchorIndex=selected
    val level get()=windows.lastIndex
    val window get()=windows.last()
    fun move(cross: Float): Int {
        val w=window
        selected=if(cross<=anchor) {
            anchorIndex-((anchor-cross)/(anchor-minimum)*(anchorIndex-w.first)).roundToInt()
        } else anchorIndex+((cross-anchor)/(maximum-anchor)*(w.last-anchorIndex)).roundToInt()
        selected=selected.coerceIn(w.first,w.last)
        return selected
    }
    fun narrow(cross: Float): Boolean {
        val w=window; val size=w.last-w.first+1
        if(size<=5) return false
        val next=maxOf(5,sqrt(size.toDouble()).roundToInt())
        val first=(selected-next/2).coerceIn(w.first,w.last-next+1)
        windows+=Window(first,first+next-1)
        reanchor(cross); return true
    }
    fun widen(cross: Float): Boolean {
        if(level==0) return false
        windows.removeAt(windows.lastIndex); reanchor(cross); return true
    }
    private fun reanchor(cross: Float) {
        anchor=cross.coerceIn(minimum+1,maximum-1); anchorIndex=selected
    }
}
