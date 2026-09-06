package dev.psyclyx.krylov

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.text.Spanned
import android.widget.TextView

/** Underlines convey local availability without changing text metrics or wrapping. */
class ReaderTextView(context: Context) : TextView(context) {
    private val underline=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    private val dash=DashPathEffect(floatArrayOf(3*resources.displayMetrics.density,3*resources.displayMetrics.density),0f)
    fun links(): List<ReaderLink> = (text as? Spanned)?.let { it.getSpans(0,it.length,ReaderLink::class.java).toList() } ?: emptyList()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val text=text as? Spanned ?: return
        val layout=layout ?: return
        canvas.save(); canvas.translate(totalPaddingLeft.toFloat()-scrollX,totalPaddingTop.toFloat()-scrollY)
        underline.strokeWidth=resources.displayMetrics.density*.7f
        for(link in links()) {
            if(link.target==null) continue
            val start=text.getSpanStart(link); val end=text.getSpanEnd(link)
            if(start<0 || end<=start) continue
            underline.color=(currentTextColor and 0x00ffffff) or 0x66000000
            underline.pathEffect=if(link.cached) null else dash
            for(line in layout.getLineForOffset(start)..layout.getLineForOffset(end-1)) {
                val first=maxOf(start,layout.getLineStart(line)); val last=minOf(end,layout.getLineEnd(line))
                val x1=if(first==layout.getLineStart(line)) layout.getLineLeft(line) else layout.getPrimaryHorizontal(first)
                val x2=if(last==layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(last)
                val y=layout.getLineBaseline(line)+resources.displayMetrics.density*2
                canvas.drawLine(minOf(x1,x2),y,maxOf(x1,x2),y,underline)
            }
        }
        canvas.restore()
    }
}
