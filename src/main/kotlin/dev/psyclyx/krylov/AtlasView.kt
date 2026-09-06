package dev.psyclyx.krylov

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/** Bounded time windows keep layout and interaction independent of library size. */
class AtlasView(context: Context, private val visits: List<Visit>, private val open: (Visit)->Unit) : View(context) {
    private val nodes = visits.distinctBy { it.title }.reversed()
    private val index = nodes.mapIndexed { i,v -> v.title to i }.toMap()
    private val byId = visits.associateBy { it.id }
    private val edges = visits.mapNotNull { v -> byId[v.parent]?.let { p -> index[p.title]!! to index[v.title]!! } }.distinct()
    private val points = nodes.mapIndexed { i,_ -> val angle=i*2.39996323; val r=42*sqrt(i.toDouble()); PointF((cos(angle)*r).toFloat(),(sin(angle)*r).toFloat()) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoom = 1f
    private var dx = 0f; private var dy = 0f
    private val density = resources.displayMetrics.density
    init {
        contentDescription="Reading atlas, ${nodes.size} articles. Pan and zoom to explore. An accessible article list is below the graph."
        isFocusable=true
        // Deterministic spring relaxation; at most 200 nodes, done once per window.
        repeat(50) {
            val forces = Array(nodes.size) { PointF() }
            for(i in points.indices) for(j in 0 until i) {
                val x=points[i].x-points[j].x; val y=points[i].y-points[j].y; val squared=(x*x+y*y).coerceAtLeast(16f)
                val force=(700f/squared).coerceAtMost(3f); val length=sqrt(squared)
                forces[i].offset(x/length*force,y/length*force); forces[j].offset(-x/length*force,-y/length*force)
            }
            edges.forEach { (i,j) ->
                val x=points[j].x-points[i].x; val y=points[j].y-points[i].y; val length=sqrt(x*x+y*y).coerceAtLeast(1f)
                val f=(length-95)*.016f; forces[i].offset(x/length*f,y/length*f); forces[j].offset(-x/length*f,-y/length*f)
            }
            points.forEachIndexed { i,p -> p.offset(forces[i].x,forces[i].y) }
        }
    }
    private val gestures = GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent)=true
        override fun onScroll(e1: MotionEvent?,e2: MotionEvent,distanceX:Float,distanceY:Float):Boolean { dx-=distanceX; dy-=distanceY; invalidate(); return true }
        override fun onSingleTapUp(e:MotionEvent):Boolean {
            val x=(e.x-width/2f-dx)/(zoom*density); val y=(e.y-height/2f-dy)/(zoom*density)
            val closest=points.indices.minByOrNull { hypot(points[it].x-x,points[it].y-y) }
            if(closest!=null && hypot(points[closest].x-x,points[closest].y-y)<32/zoom) { performClick(); open(nodes[closest]) }; return true
        }
        override fun onDoubleTap(e:MotionEvent):Boolean { zoom=1f; dx=0f; dy=0f; invalidate(); return true }
    })
    private val scales=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector:ScaleGestureDetector):Boolean { zoom=(zoom*detector.scaleFactor).coerceIn(.25f,3f); invalidate(); return true }
    })
    override fun onTouchEvent(event:MotionEvent):Boolean { parent.requestDisallowInterceptTouchEvent(true); scales.onTouchEvent(event); gestures.onTouchEvent(event); return true }
    override fun performClick():Boolean { super.performClick(); return true }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(width/2f+dx,height/2f+dy); canvas.scale(zoom*density,zoom*density)
        paint.color=Color.rgb(187,204,188); paint.strokeWidth=1.2f
        edges.forEach { (i,j) ->
            val a=points[i]; val b=points[j]; canvas.drawLine(a.x,a.y,b.x,b.y,paint)
            val angle=atan2(b.y-a.y,b.x-a.x); val x=b.x-cos(angle)*11; val y=b.y-sin(angle)*11
            canvas.drawLine(x,y,x-cos(angle-.5f)*7,y-sin(angle-.5f)*7,paint); canvas.drawLine(x,y,x-cos(angle+.5f)*7,y-sin(angle+.5f)*7,paint)
        }
        nodes.forEachIndexed { i,v ->
            val p=points[i]; val current=v.title==visits.firstOrNull()?.title
            paint.color=if(current) Color.rgb(41,107,84) else Color.rgb(116,144,115)
            canvas.drawCircle(p.x,p.y,if(current) 8f else 5f,paint)
            if(zoom>.6f || current || nodes.size<35) {
                paint.textSize=11f; paint.color=Color.rgb(43,56,46); paint.textAlign=Paint.Align.CENTER
                val title=if(v.title.length>26) v.title.take(24)+"…" else v.title
                canvas.drawText(title,p.x,p.y+22,paint)
            }
        }
        canvas.restore()
    }
}
