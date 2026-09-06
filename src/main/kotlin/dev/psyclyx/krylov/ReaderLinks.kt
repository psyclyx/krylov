package dev.psyclyx.krylov

import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView

class ReaderLink(val url: String, private val open: (String)->Unit) : ClickableSpan() {
    val target: Library.LinkTarget? = target(url)
    var cached=false
    override fun updateDrawState(ds: android.text.TextPaint) { super.updateDrawState(ds); ds.isUnderlineText=target==null }
    override fun onClick(widget: View) = open(url)
    companion object {
        fun target(url: String): Library.LinkTarget? {
            val uri=android.net.Uri.parse(if(url.startsWith("//")) "https:$url" else url)
            if(uri.host!=null && uri.host !in setOf("en.wikipedia.org","en.m.wikipedia.org")) return null
            val path=uri.path.orEmpty()
            val title=when {
                path.startsWith("/wiki/") -> path.removePrefix("/wiki/")
                path.startsWith("./") -> path.removePrefix("./")
                path=="/w/index.php" -> uri.getQueryParameter("title")
                else -> null
            } ?: return null
            return Library.LinkTarget(title.replace('_',' '),if(path=="/w/index.php") uri.getQueryParameter("oldid")?.toLongOrNull() else null)
        }
    }
}

/** Long press only intercepts links; ordinary text retains native selection. */
class ReaderLinks(private val peek: (String)->Unit) : LinkMovementMethod() {
    private val handler=Handler(Looper.getMainLooper())
    private var x=0f; private var y=0f; private var consumed=false
    private var pending: Runnable?=null
    override fun onTouchEvent(widget: TextView, text: Spannable, event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pending?.let { handler.removeCallbacks(it) }; consumed=false; x=event.x; y=event.y
                val layout=widget.layout ?: return super.onTouchEvent(widget,text,event)
                val px=event.x-widget.totalPaddingLeft+widget.scrollX
                val py=event.y-widget.totalPaddingTop+widget.scrollY
                val line=layout.getLineForVertical(py.toInt())
                if(px>=layout.getLineLeft(line) && px<=layout.getLineRight(line)) {
                    val offset=layout.getOffsetForHorizontal(line,px)
                    val link=text.getSpans(offset,offset,ReaderLink::class.java).firstOrNull()
                    if(link!=null) {
                        pending=Runnable { consumed=true; widget.cancelLongPress(); peek(link.url) }
                        handler.postDelayed(pending!!,ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if(kotlin.math.hypot(event.x-x,event.y-y)>ViewConfiguration.get(widget.context).scaledTouchSlop) { pending?.let { handler.removeCallbacks(it) }; pending=null }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_UP -> { pending?.let { handler.removeCallbacks(it) }; pending=null; if(consumed) { consumed=false; return true } }
        }
        return super.onTouchEvent(widget,text,event)
    }
}
