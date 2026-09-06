package dev.psyclyx.krylov
fun main() {
    for(n in listOf(1,2,5,6,40,500,10000)) {
        val range=ThumbRange(n,0f,600f,n/2,480f)
        check(range.move(0f)==0)
        check(range.move(600f)==n-1)
        range.move(250f)
        val selected=range.selected
        if(range.narrow(250f)) {
            check(range.move(250f)==selected)
            check(range.window.last-range.window.first+1<n)
            range.move(350f)
            val next=range.selected
            check(range.widen(350f))
            check(range.move(350f)==next)
            check(range.move(0f)==0 && range.move(600f)==n-1)
        }
    }
    val a=ThumbRange(500,0f,600f,250,300f)
    val b=ThumbRange(500,0f,600f,250,300f)
    a.move(100f); a.narrow(100f)
    b.move(500f); b.narrow(500f)
    check(a.window!=b.window) // Same zoom depth, different paths.
    println("PASS full-range reachability, anchored adaptive narrowing, reversible widening, path dependence")
}
