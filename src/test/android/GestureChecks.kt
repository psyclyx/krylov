package dev.psyclyx.krylov.tests

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.psyclyx.krylov.Library
import dev.psyclyx.krylov.MainActivity

/** Explicitly opted-in emulator fixture checks, never part of a normal library run. */
object GestureChecks {
    fun run(inst: Instrumentation, report: StringBuilder) {
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(v: View): List<View> = listOf(v)+(if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
        val deadline=SystemClock.uptimeMillis()+5000
        var ready=false
        while(!ready && SystemClock.uptimeMillis()<deadline) {
            inst.runOnMainSync { ready=descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.tag is Int } }
            if(!ready) SystemClock.sleep(20)
        }
        check(ready) { "Reader fixture did not render" }
        lateinit var control: View
        var x=0f; var y=0f
        val density=activity.resources.displayMetrics.density
        inst.runOnMainSync {
            control=descendants(activity.window.decorView).first { it.contentDescription?.startsWith("Reader control.")==true }
            val at=IntArray(2); control.getLocationOnScreen(at)
            x=at[0]+control.width/2f; y=at[1]+control.height/2f
        }
        var cachedLink=false
        val linksDeadline=SystemClock.uptimeMillis()+3000
        while(!cachedLink && SystemClock.uptimeMillis()<linksDeadline) {
            inst.runOnMainSync {
                val links=descendants(activity.window.decorView).filterIsInstance<dev.psyclyx.krylov.ReaderTextView>().flatMap { it.links() }
                cachedLink=links.any { it.target?.title=="Krylov test companion" && it.cached }
                check(links.none { it.target?.title=="Krylov missing fixture" && it.cached })
            }
            if(!cachedLink) SystemClock.sleep(20)
        }
        check(cachedLink) { "Cached link never received its solid-underline state" }
        report.append("PASS visible cached-link availability updates without a network request\n")
        val db=Library(inst.targetContext)
        try {
            val before=db.marks().size
            fun gesture(commit: Boolean) {
                val down=SystemClock.uptimeMillis()
                fun event(action: Int,dx: Float,dy: Float) {
                    inst.sendPointerSync(MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x+dx*density,y+dy*density,0))
                    inst.waitForIdleSync()
                }
                event(MotionEvent.ACTION_DOWN,0f,0f)
                event(MotionEvent.ACTION_MOVE,0f,40f)
                event(MotionEvent.ACTION_MOVE,0f,78f)
                event(MotionEvent.ACTION_MOVE,0f,112f)
                event(MotionEvent.ACTION_MOVE,-70f,112f)
                if(!commit) event(MotionEvent.ACTION_MOVE,0f,0f)
                event(MotionEvent.ACTION_UP,if(commit) -70f else 0f,if(commit) 112f else 0f)
            }
            gesture(false)
            check(db.marks().size==before) { "Returning to the button created a mark" }
            gesture(true)
            val mark=db.marks().first()
            check(db.marks().size==before+1 && mark.end>mark.start && mark.quote.isNotBlank())
            check(mark.quote.none { it.isWhitespace() }) { "Word detent marked a paragraph" }
            inst.runOnMainSync {
                val text=descendants(activity.window.decorView).filterIsInstance<TextView>().first { it.tag==mark.block }.text
                check(text.subSequence(mark.start,mark.end).toString()==mark.quote)
                check((text as Spanned).getSpans(0,text.length,BackgroundColorSpan::class.java).isEmpty()) { "Stored mark highlighted article text" }
            }
            report.append("PASS real thumb gesture: return-to-origin cancellation, exact word anchor, no stored text highlight\n")
        } finally { db.close(); inst.runOnMainSync { activity.finish() } }
    }
}
