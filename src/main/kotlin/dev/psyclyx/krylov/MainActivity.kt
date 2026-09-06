package dev.psyclyx.krylov

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.Editable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.BackgroundColorSpan
import android.text.style.URLSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class MainActivity : Activity() {
    private val ink = Color.rgb(36,46,42)
    private val muted = Color.rgb(109,117,109)
    private val paper = Color.rgb(248,247,241)
    private val accent = Color.rgb(37,101,82)
    private val line = Color.rgb(224,227,216)
    private lateinit var repo: Repository
    private val db get() = repo.library
    private lateinit var root: LinearLayout
    private lateinit var thumbControl: View
    private lateinit var body: FrameLayout
    private var tab = "Read"
    private var article: Article? = null
    private var blocks = emptyList<Block>()
    private var articleMarks = emptyList<Mark>()
    private var visitId = 0L
    private var currentVisit: Visit? = null
    private var reader: ListView? = null
    private var position = 0
    private var generation = 0
    @Volatile private var searchGeneration = 0
    @Volatile private var viewGeneration = 0
    private data class ReadingLocation(val visit: Visit, val pinned: Boolean)
    private val back = java.util.ArrayDeque<ReadingLocation>()
    private var stack = emptyList<Mark>()
    private var stackIndex = -1
    private var trailQuery = ""
    private var trailBefore = Long.MAX_VALUE
    private val handler = Handler(Looper.getMainLooper())
    private var statusLabel: TextView? = null
    private val dateFormat = SimpleDateFormat("d MMM yyyy · HH:mm",Locale.getDefault())
    private var exactRevision = false
    private var findQuery = ""
    private var findMatches = emptyList<Int>()
    private var findIndex = 0
    private var displayingReader = false
    private var positionOffset = 0
    private lateinit var feedback: LinearLayout
    private var feedbackId = 0L
    private val expandedBoxes = mutableSetOf<Int>()
    private val tableLimits = mutableMapOf<Int,Int>()
    private var stackCategory: String? = null
    private var cancelThumbGesture: (() -> Unit)? = null
    private var linkRefreshPending=false
    private var linkEpoch= -1L
    private fun refreshLinkStyles() {
        if(linkRefreshPending || !::repo.isInitialized) return
        linkRefreshPending=true
        handler.postDelayed({
            linkRefreshPending=false
            if(!displayingReader || isDestroyed) return@postDelayed
            val views=mutableListOf<ReaderTextView>()
            fun collect(v: View) { if(v is ReaderTextView) views+=v else if(v is ViewGroup) for(i in 0 until v.childCount) collect(v.getChildAt(i)) }
            reader?.let { collect(it) }
            val links=views.flatMap { it.links() }
            val targets=links.mapNotNull { it.target }.distinct()
            val screen=viewGeneration
            val epoch=repo.cacheGeneration
            repo.cachedLinks(targets) { saved ->
                runOnUiThread {
                    if(screen==viewGeneration && !isDestroyed) {
                        links.forEach { it.cached=it.target in saved }; views.forEach { it.invalidate() }
                        linkEpoch=epoch
                    }
                }
            }
        },32)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository.get(this)
        window.statusBarColor = paper; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(paper)
            setOnApplyWindowInsetsListener { v, insets -> v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom); insets.consumeSystemWindowInsets() }
        }
        val stage=FrameLayout(this)
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        body=FrameLayout(this); stage.addView(body,FrameLayout.LayoutParams(-1,-1))
        thumbControl=object : View(this) {
            private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                paint.color=if(isPressed) paper else 0xddeeeee7.toInt()
                canvas.drawCircle(width/2f,height/2f,dp(18).toFloat(),paint)
                paint.color=if(isPressed) accent else 0x99727d74.toInt()
                canvas.drawCircle(width/2f,height/2f,dp(3).toFloat(),paint)
            }
            override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
        }.apply {
            isClickable=true; isFocusable=true
            contentDescription="Reader control. Tap for contents, hold for tools. Up then sideways: history. Left then vertical: sections and marks. Down then sideways: place a mark. Right: search."
            setOnClickListener { if(displayingReader) contents() else search() }
            setOnLongClickListener { menu(); true }
            scrub(this)
        }
        stage.addView(thumbControl,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.BOTTOM).apply { bottomMargin=dp(160); rightMargin=dp(40) })
        feedback=row().apply { visibility=View.GONE; setPadding(dp(12),0,dp(4),0); setBackgroundColor(line) }
        stage.addView(feedback,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        if(android.os.Build.VERSION.SDK_INT>=33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        val savedVisit = savedInstanceState?.getLong("visit") ?: db.preferences.getLong("lastVisit",0)
        visitId = savedVisit
        val previous = if(savedVisit > 0) db.getVisit(savedVisit) else null
        currentVisit=previous
        home()
        if(previous != null) open(previous.title,if(db.preferences.getBoolean("exactRevision",false)) previous.revision else null,restore=previous,remember=false)
        repo.schedule(); repo.pump()
        handler.post(object : Runnable {
            override fun run() {
                if(isDestroyed) return
                if(displayingReader && repo.cacheGeneration!=linkEpoch) refreshLinkStyles()
                handler.postDelayed(this,1000)
            }
        })
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: CharSequence, size: Float = 16f, color: Int = ink) = ReaderTextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(),1.05f)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 13f; setTextColor(accent); minWidth = 0; minimumWidth = 0
        setPadding(dp(12),0,dp(12),0); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() }
    }
    private fun rounded(color: Int = Color.WHITE) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat(); setStroke(dp(1),line) }
    private fun padded(c: LinearLayout, amount: Int = 22): LinearLayout = c.apply { setPadding(dp(amount),dp(12),dp(amount),dp(16)) }
    private fun title(c: LinearLayout, heading: String) {
        c.addView(label(heading,23f).apply { typeface = Typeface.create("serif",Typeface.NORMAL); setPadding(0,dp(8),0,dp(16)) })
    }
    private fun show(view: View) { displayingReader=false; viewGeneration++; body.removeAllViews(); body.addView(view,FrameLayout.LayoutParams(-1,-1)); thumbControl.invalidate() }
    private fun scroll(content: View) = ScrollView(this).apply { isFillViewport = true; addView(content) }
    private fun savePosition() {
        if(displayingReader && reader != null && article != null) {
            position = reader!!.firstVisiblePosition; positionOffset=(reader!!.getChildAt(0)?.top ?: 0)-reader!!.paddingTop
            val id=visitId; val block=position; val offset=positionOffset
            currentVisit=currentVisit?.copy(scroll=block,offset=offset)
            repo.persist { db.position(id,block,offset) }
        }
    }
    private fun switch(name: String) {
        savePosition(); generation++; tab = name; reader = null
        when(name) { "Read" -> if(article == null) home() else render(); "Trail" -> trail(); "Atlas" -> atlas(); "Marks" -> marks() }
    }
    private fun home() {
        val c = padded(column())
        val recent = db.visits(limit=20).distinctBy { it.title }
        if(recent.isEmpty()) {
            c.addView(button("Search Wikipedia") { search() }.apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL })
            c.addView(label("Tap the floating button for contents; hold for tools. Drag up for history, left for sections, down to place a mark, or right to search. Then scrub across. Pull farther for precision; return to the button to cancel.",14f,muted).apply { setPadding(0,dp(24),0,0) })
        }
        else { c.addView(label("Recent",14f,muted)); recent.forEach { v -> c.addView(card(v.title,dateFormat.format(Date(v.time))) { open(v.title,restore=v) }) } }
        show(scroll(c))
    }
    private fun card(heading: String, detail: String, action: () -> Unit): View = column().apply {
        setPadding(0,dp(12),0,dp(12))
        addView(label(heading,19f).apply { typeface = Typeface.create("serif",Typeface.NORMAL) })
        if(detail.isNotBlank()) addView(label(detail,13f,muted).apply { setPadding(0,dp(7),0,0) })
        isFocusable = true; setOnClickListener { action() }
        addView(View(this@MainActivity).apply { setBackgroundColor(line) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(12) })
        layoutParams = LinearLayout.LayoutParams(-1,-2)
    }
    private fun message(value: String) { Toast.makeText(this,value,Toast.LENGTH_LONG).show() }
    private data class ScrubItem(val title: String, val detail: String = "", val block: Int = -1,
                                 val start: Int = 0, val end: Int = 0, val screenY: Float = 0f,
                                 val action: () -> Unit)
    private fun navigationTargets(): List<ScrubItem> {
        val items=mutableListOf(ScrubItem("Beginning",block=-1) { reader?.setSelectionFromTop(0,0) })
        blocks.forEachIndexed { i,b -> if(b.kind in setOf("h2","h3","h4")) {
            items+=ScrubItem(Html.fromHtml(b.html,0).toString(),if(b.kind=="h2") "Section" else "Subsection",i) { reader?.setSelectionFromTop(i+1,0) }
        } }
        articleMarks.forEach { m ->
            items+=ScrubItem("● "+m.category,m.quote.ifBlank { m.note }.take(100),m.block,m.start,m.end) { jumpMark(m) }
        }
        return items.sortedWith(compareBy<ScrubItem> { it.block }.thenBy { it.start })
    }
    private fun textViews(view: View): List<TextView> = when(view) {
        is TextView -> if(view.tag is Int) listOf(view) else emptyList()
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun markTargets(level: Int): List<ScrubItem> {
        val list=reader ?: return emptyList()
        val listLocation=IntArray(2); list.getLocationOnScreen(listLocation)
        val top=listLocation[1].toFloat(); val bottom=top+list.height
        val visible=textViews(list).mapNotNull { tv ->
            val at=IntArray(2); tv.getLocationOnScreen(at)
            val lo=maxOf(top,at[1].toFloat()); val hi=minOf(bottom,(at[1]+tv.height).toFloat())
            if(hi-lo<dp(8)) null else Triple(tv,at[1].toFloat(),(lo+hi)/2)
        }
        if(level==0) return visible.map { (tv,_,y) ->
            val block=tv.tag as Int
            val section=(block downTo 0).firstOrNull { blocks[it].kind in setOf("h2","h3","h4") } ?: -1
            val name=if(section<0) "Introduction" else Html.fromHtml(blocks[section].html,0).toString()
            ScrubItem(name,"Section",section,screenY=y) { quickMark(section,0,0,name) }
        }.groupBy { it.block }.values.map { group -> group.first().copy(screenY=(group.minOf { it.screenY }+group.maxOf { it.screenY })/2) }
        if(level==1) return visible.map { (tv,_,y) ->
            val block=tv.tag as Int; val text=tv.text.toString()
            ScrubItem(text.take(120),"Paragraph",block,0,text.length,y) { quickMark(block,0,text.length,text) }
        }
        return visible.flatMap { (tv,viewY,_) ->
            val text=tv.text.toString(); val layout=tv.layout ?: return@flatMap emptyList()
            val words=java.text.BreakIterator.getWordInstance(Locale.getDefault()).apply { setText(text) }
            val items=mutableListOf<ScrubItem>()
            var start=words.first(); var end=words.next()
            while(end!=java.text.BreakIterator.DONE) {
                val from=start; val to=end; val word=text.substring(from,to)
                if(word.any { it.isLetterOrDigit() }) {
                    val line=layout.getLineForOffset(from)
                    val y=viewY+tv.totalPaddingTop+(layout.getLineTop(line)+layout.getLineBottom(line))/2f
                    if(y in top..bottom) {
                        val block=tv.tag as Int
                        items+=ScrubItem(word,"Word · "+text.substring(maxOf(0,from-25),minOf(text.length,to+35)),block,from,to,y) {
                            quickMark(block,from,to,word)
                        }
                    }
                }
                start=end; end=words.next()
            }
            items
        }
    }
    private fun jumpMark(mark: Mark) {
        reader?.setSelectionFromTop((mark.block+1).coerceAtLeast(0),dp(48))
        reader?.post {
            val tv=reader?.let { textViews(it).firstOrNull { view -> view.tag==mark.block } }
            val layout=tv?.layout
            if(layout!=null && mark.start in 0 until tv.text.length) {
                reader?.setSelectionFromTop(mark.block+1,dp(48)-layout.getLineTop(layout.getLineForOffset(mark.start)))
            }
        }
    }
    private fun scrub(control: View) {
        var originX=0f; var originY=0f
        var mode=""; var held=false; var active=false; var cancelled=false; var gestureGeneration=0
        var selection=0; var semantic=0; var depth=0
        var choices=emptyList<ScrubItem>(); var range: ThumbRange?=null
        var overlay: FrameLayout?=null
        var heading: TextView?=null; var detail: TextView?=null; var nearby: TextView?=null
        var pointer: View?=null; var longPress: Runnable?=null
        var crossMinimum=0f; var crossMaximum=0f
        fun dismiss() { overlay?.let { body.removeView(it) }; overlay=null; control.isPressed=false }
        fun primary(x: Float,y: Float) = when(mode) { "History" -> originY-y; "Sections" -> originX-x; "Mark" -> y-originY; else -> x-originX }
        fun cross(x: Float,y: Float)=if(mode=="Sections") y else x
        fun setupRange(cross: Float, selected: Int) {
            range=ThumbRange(choices.size,crossMinimum,crossMaximum,selected,cross)
            selection=range!!.selected
        }
        cancelThumbGesture={ longPress?.let { handler.removeCallbacks(it) }; held=true; active=false; dismiss() }
        control.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureGeneration=viewGeneration; originX=event.rawX; originY=event.rawY; mode=""; active=false; held=false; cancelled=false; depth=0; semantic=0
                    control.isPressed=true
                    longPress=Runnable { if(!active) { held=true; control.isPressed=false; control.performLongClick() } }
                    handler.postDelayed(longPress!!,android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if(viewGeneration!=gestureGeneration) { held=true; active=false; dismiss(); longPress?.let { handler.removeCallbacks(it) } }
                    if(held) return@setOnTouchListener true
                    val dx=event.rawX-originX; val dy=event.rawY-originY
                    if(!active && kotlin.math.hypot(dx,dy)>=dp(30)) {
                        handler.removeCallbacks(longPress!!)
                        mode=if(kotlin.math.abs(dx)>kotlin.math.abs(dy)) { if(dx<0) "Sections" else "Search" } else if(dy<0) "History" else "Mark"
                        if(!displayingReader && mode in setOf("Sections","Mark")) mode="Search"
                        val location=IntArray(2); body.getLocationOnScreen(location)
                        crossMinimum=(if(mode=="Sections") location[1] else location[0])+dp(24).toFloat()
                        crossMaximum=(if(mode=="Sections") location[1]+body.height else location[0]+body.width)-dp(24).toFloat()
                        choices=when(mode) {
                            "Sections" -> navigationTargets()
                            "History" -> listOf(ScrubItem("All history","Browse by date") { switch("Trail") })+db.visits(limit=100).filter { it.id!=visitId }.asReversed().map { v -> ScrubItem(v.title,dateFormat.format(Date(v.time))) { open(v.title,restore=v) } }
                            "Mark" -> markTargets(0)
                            else -> listOf(ScrubItem("Search Wikipedia") { search() })
                        }
                        val initial=if(mode=="Sections") choices.indexOfLast { it.block<=(reader?.firstVisiblePosition ?: 0)-1 }.coerceAtLeast(0) else if(mode=="Mark") choices.size/2 else if(mode=="History") choices.lastIndex else 0
                        setupRange(cross(event.rawX,event.rawY),initial)
                        overlay=FrameLayout(this).apply { isClickable=false; clipChildren=false }
                        val panel=padded(column(),16).apply { setBackgroundColor(paper); elevation=dp(6).toFloat() }
                        heading=label("",22f); detail=label("",12f,muted); nearby=label("",14f,muted)
                        panel.addView(detail); panel.addView(heading); panel.addView(nearby)
                        overlay!!.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { leftMargin=dp(16); rightMargin=dp(16); topMargin=dp(12) })
                        pointer=View(this).apply { setBackgroundColor(accent) }
                        overlay!!.addView(pointer,FrameLayout.LayoutParams(dp(10),dp(3),Gravity.START))
                        body.addView(overlay,FrameLayout.LayoutParams(-1,-1))
                        active=true; control.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                    }
                    if(active) {
                        cancelled=kotlin.math.hypot(dx,dy)<dp(22)
                        val axis=cross(event.rawX,event.rawY)
                        val prior=selection
                        if(!cancelled && mode!="Search") {
                            selection=range!!.move(axis)
                            val p=primary(event.rawX,event.rawY)
                            if(p>=dp(30+(depth+1)*34+6)) {
                                val old=choices.getOrNull(selection)
                                if(mode=="Mark" && semantic<2) {
                                    semantic++; choices=markTargets(semantic)
                                    val closest=choices.indices.minByOrNull { kotlin.math.abs(choices[it].screenY-(old?.screenY ?: originY)) } ?: 0
                                    setupRange(axis,closest); depth++
                                } else if(range!!.narrow(axis)) { selection=range!!.selected; depth++ }
                            } else if(depth>0 && p<dp(30+depth*34-6)) {
                                if(range!!.level>0) { range!!.widen(axis); selection=range!!.selected; depth-- }
                                else if(mode=="Mark" && semantic>0) {
                                    val oldY=choices.getOrNull(selection)?.screenY ?: originY
                                    semantic--; choices=markTargets(semantic)
                                    setupRange(axis,choices.indices.minByOrNull { kotlin.math.abs(choices[it].screenY-oldY) } ?: 0); depth--
                                }
                            }
                        }
                        if(selection!=prior) control.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val item=choices.getOrNull(selection)
                        heading?.text=if(cancelled) "Cancel" else item?.title ?: "No targets here"
                        detail?.text=if(cancelled) "Release without changing anything" else mode+" · "+(if(mode=="Mark") listOf("section","paragraph","word")[semantic] else "release to open")+(if(depth>0) " · focused" else "")
                        nearby?.text=if(cancelled) "" else if(mode=="Search") "Release to search · return to the button to cancel" else {
                            val w=range!!.window
                            item?.detail.orEmpty()+"\n"+(if(choices.isEmpty()) 0 else selection+1)+" of "+choices.size+
                                (if(depth>0) " · "+(w.last-w.first+1)+" in focus" else "")+
                                "\n"+choices.getOrNull(selection+1)?.let { "Next · "+it.title.take(60) }.orEmpty()
                        }
                        pointer?.visibility=if(mode=="Mark" && item!=null && !cancelled) View.VISIBLE else View.GONE
                        val loc=IntArray(2); body.getLocationOnScreen(loc)
                        pointer?.translationY=(item?.screenY ?: 0f)-loc[1]
                    }
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    longPress?.let { handler.removeCallbacks(it) }
                    cancelled=cancelled || kotlin.math.hypot(event.rawX-originX,event.rawY-originY)<dp(22)
                    val apply=active && !cancelled && viewGeneration==gestureGeneration && event.actionMasked==MotionEvent.ACTION_UP
                    dismiss()
                    if(apply) choices.getOrNull(selection)?.action?.invoke()
                    else if(!active && !held && event.actionMasked==MotionEvent.ACTION_UP) control.performClick()
                    active=false
                }
            }
            true
        }
    }
    private fun bottom(dialog: AlertDialog): AlertDialog {
        dialog.show(); dialog.window?.apply {
            setGravity(Gravity.BOTTOM); setLayout(-1,ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.post { val maximum=(resources.displayMetrics.heightPixels*.72).toInt(); if(decorView.height>maximum) setLayout(-1,maximum) }
        }; return dialog
    }
    private fun menu() {
        val items=mutableListOf<Pair<String,()->Unit>>()
        items+="Search" to { search() }
        if(article!=null) {
            if(!displayingReader) items+="Return to article" to { switch("Read") }
            items+="Contents" to { if(!displayingReader) switch("Read"); contents() }
            items+="Find in article" to { findInArticle() }
            items+="Mark with category or note" to { if(!displayingReader) switch("Read"); currentPassage()?.let { (b,q) -> addMark(b,0,q.length,q) } }
            items+="Mark whole article" to { quickMark(-1,0,0,"") }
            items+="Revisions" to { revisions() }
        }
        items+="History" to { switch("Trail") }; items+="Reading paths" to { switch("Atlas") }
        items+="Marks" to { switch("Marks") }; items+="Settings" to { settings() }
        items.reverse() // Search and common reading actions stay nearest the thumb.
        val dialog=bottom(AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _,i -> items[i].second() }.setNegativeButton("Close",null).create())
        dialog.listView.postDelayed({ dialog.listView.setSelectionFromTop(items.lastIndex,0) },120)
    }
    private fun currentPassage(): Pair<Int,String>? {
        if(!displayingReader || blocks.isEmpty()) { message("Open an article to mark it"); return null }
        val start=(textViews(reader ?: return null).minByOrNull { tv -> val at=IntArray(2); tv.getLocationOnScreen(at); kotlin.math.abs(at[1]+tv.height/2-resources.displayMetrics.heightPixels/2) }?.tag as? Int ?: 0).coerceIn(0,blocks.lastIndex)
        val index=(start..blocks.lastIndex).firstOrNull { blocks[it].kind !in setOf("image","infobox") } ?: start
        return index to Html.fromHtml(blocks[index].html,Html.FROM_HTML_MODE_COMPACT).trim().toString().take(5000)
    }
    private fun quickMark() { currentPassage()?.let { (b,q) -> quickMark(b,0,q.length,q) } }
    private fun quickMark(block: Int, start: Int, end: Int, quote: String) {
        val a=article ?: return
        val category=db.preferences.getString("lastCategory","Keep") ?: "Keep"
        val color=db.categories().firstOrNull { it.first==category }?.second ?: accent
        db.category(category,color)
        val id=db.mark(a,block,start,end,quote,"",category)
        articleMarks=db.marks(revision=a.revision)
        (reader?.adapter as? HeaderViewListAdapter)?.wrappedAdapter?.let { (it as? BaseAdapter)?.notifyDataSetChanged() }
        root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val mark=Mark(id,a.title,a.revision,block,start,end,quote,"",category,color,Library.now())
        feedbackId=id; feedback.removeAllViews(); feedback.visibility=View.VISIBLE
        feedback.addView(label("Marked · $category",13f),LinearLayout.LayoutParams(0,dp(44),1f))
        feedback.addView(button("Edit") { feedback.visibility=View.GONE; addMark(block,start,end,quote,mark) })
        feedback.addView(button("Undo") { db.deleteMark(id); feedback.visibility=View.GONE; articleMarks=db.marks(revision=a.revision); (reader?.adapter as? HeaderViewListAdapter)?.wrappedAdapter?.let { (it as? BaseAdapter)?.notifyDataSetChanged() } })
        handler.postDelayed({ if(feedbackId==id) feedback.visibility=View.GONE },5000)
    }
    private fun findInArticle() {
        if(article==null) return
        val field=EditText(this).apply { hint="Find text"; setSingleLine(); setText(findQuery) }
        val dialog=AlertDialog.Builder(this).setTitle("Find in article").setView(field).setPositiveButton("Find") { _,_ ->
            findQuery=field.text.toString().trim(); if(findQuery.isEmpty()) return@setPositiveButton
            findMatches=blocks.indices.filter { Html.fromHtml(blocks[it].html,0).toString().contains(findQuery,true) }; findIndex=0
            if(findMatches.isEmpty()) message("No matches") else { switch("Read"); findMove(0) }
        }.setNegativeButton("Cancel",null).create(); bottom(dialog)
    }
    private fun findMove(delta: Int) {
        if(findMatches.isEmpty()) return
        findIndex=(findIndex+delta+findMatches.size)%findMatches.size
        reader?.setSelectionFromTop(findMatches[findIndex]+1,0)
        feedbackId++; feedback.removeAllViews(); feedback.visibility=View.VISIBLE
        feedback.addView(label("${findIndex+1}/${findMatches.size} passages",12f),LinearLayout.LayoutParams(0,dp(44),1f))
        feedback.addView(button("Previous") { findMove(-1) }); feedback.addView(button("Next") { findMove(1) }); feedback.addView(button("×") { findQuery=""; feedback.visibility=View.GONE; savePosition(); render() })
    }
    private fun <T> async(work: () -> T, success: (T) -> Unit, failure: (Exception) -> Unit = { message(it.message ?: "Something went wrong") }) {
        repo.foreground.execute { try { val result = work(); runOnUiThread { if(!isDestroyed) success(result) } } catch(e: Exception) { runOnUiThread { if(!isDestroyed) failure(e) } } }
    }
    private fun readArticle(title: String, revision: Long?, force: Boolean,
                            success: (Repository.Reading)->Unit, failure: (Exception)->Unit) {
        repo.read(title,revision,force,
            { result -> runOnUiThread { if(!isDestroyed) success(result) } },
            { error -> runOnUiThread { if(!isDestroyed) failure(error) } })
    }
    private fun open(title: String, revision: Long? = null, force: Boolean = false, restore: Visit? = null, remember: Boolean = true, mark: Mark? = null, fragment: String? = null, pinned: Boolean = revision!=null) {
        cancelThumbGesture?.invoke()
        savePosition()
        val old = currentVisit ?: if(visitId > 0) db.getVisit(visitId) else null
        val ticket = ++generation
        val openingStarted=System.nanoTime()
        var completed=false
        tab = "Read"
        handler.postDelayed({
            if(!completed && ticket==generation && article?.let { it.title==title && (revision==null || revision==it.revision) }!=true) {
                feedback.removeAllViews(); feedback.visibility=View.VISIBLE
                feedback.addView(label("Loading $title",12f,muted),LinearLayout.LayoutParams(0,dp(44),1f))
                feedback.addView(button("Cancel") { generation++; feedback.visibility=View.GONE })
            }
        },250)
        readArticle(title,revision,force, { (loaded,parsed,cached) ->
            val readyAt=System.nanoTime()
            if(ticket != generation) return@readArticle
            completed=true; feedback.visibility=View.GONE
            if(remember && old != null) back.addLast(ReadingLocation(old,exactRevision))
            article = loaded; exactRevision = pinned
            expandedBoxes.clear(); tableLimits.clear(); findQuery=""; findMatches=emptyList()
            blocks = parsed
            val keepVisit=restore != null && !remember && old?.id==restore.id
            val visit=if(keepVisit) restore!! else db.allocateVisit(loaded,old?.id ?: 0)
            currentVisit=visit; visitId=visit.id
            val exact=exactRevision
            repo.persist {
                if(!keepVisit) db.recordVisit(visit)
                db.preferences.edit().putLong("lastVisit",visit.id).putBoolean("exactRevision",exact).apply()
            }
            position = when { mark != null -> mark.block + 1; fragment != null -> (blocks.indexOfFirst { it.anchor == fragment } + 1).coerceAtLeast(0); restore != null -> restore.scroll; else -> 0 }
            positionOffset=if(mark==null && fragment==null) restore?.offset ?: 0 else 0
            repo.activeRevision = loaded.revision
            if(tab == "Read") render()
            if(!cached) statusLabel?.text="Latest · "+loaded.revision
            reader?.let { list ->
                list.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if(list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(this)
                        android.util.Log.d("KrylovRead","open="+ticket+" cached="+cached+" ready_to_draw_ms="+((System.nanoTime()-readyAt)/1e6)+" request_to_draw_ms="+((System.nanoTime()-openingStarted)/1e6))
                        return true
                    }
                })
            }
            if(mark!=null) reader?.post { jumpMark(mark) }
            repo.explore(loaded)
            if(cached && !pinned && !db.fresh(loaded.title)) {
                async({ repo.api.background.set(true); try { repo.load(loaded.title,force=true) } finally { repo.api.background.remove() } }, { updated ->
                    if(article?.revision == loaded.revision && tab == "Read") statusLabel?.text = if(updated.revision != loaded.revision) "A newer revision is ready · tap Revisions" else "Up to date · revision ${loaded.revision}"
                }, { if(article?.revision == loaded.revision) statusLabel?.text = "Saved copy · refresh unavailable · revision ${loaded.revision}" })
            }
        }, { error ->
            if(ticket != generation) return@readArticle
            completed=true; feedback.visibility=View.GONE
            val c = padded(column()); title(c,title); c.addView(label(error.message ?: "No saved copy and no connection.",15f,muted))
            c.addView(button("Try again") { open(title,revision,force,restore,false,mark,fragment) })
            c.addView(button("Back to reading") { if(article == null) home() else render() }); show(c)
        })
    }
    private fun render() {
        val a = article ?: return home()
        articleMarks = db.marks(revision=a.revision)
        val c = column()
        if(stackIndex in stack.indices) {
            c.addView(row().apply {
                setPadding(dp(12),0,dp(12),0); setBackgroundColor(line)
                addView(button("← Newer") { stackMove(-1) })
                addView(label("${stack[stackIndex].category} · ${stackIndex+1}/${stack.size}",12f),LinearLayout.LayoutParams(0,-2,1f))
                addView(button("Older →") { stackMove(1) }); addView(button("×") { stackIndex=-1; render() })
            })
        }
        val list = ListView(this).apply { divider = null; cacheColorHint = paper; setPadding(dp(8),0,dp(22),0); isVerticalScrollBarEnabled=false; clipToPadding = false }
        val h = column()
        h.addView(label(a.title,30f).apply { typeface = Typeface.create("serif",Typeface.NORMAL); setPadding(0,dp(12),0,dp(8)) })
        statusLabel = label("${if(exactRevision) "Historical revision" else if(db.fresh(a.title)) "Saved · fresh" else "Saved · checking for updates"} · ${a.revision}",11f,muted)
        h.addView(statusLabel!!.apply { setPadding(0,0,0,dp(20)); setOnClickListener { revisions() }; contentDescription="Revision ${a.revision}. Tap for revision history." })
        list.addHeaderView(markedBlock(h,-1),null,false)
        val footer = column().apply {
            setPadding(0,dp(24),0,dp(32))
            addView(label("From Wikipedia, the free encyclopedia. Text available under CC BY-SA; images have individual licenses.",12f,muted))
            addView(button("Source, contributors & image credits ↗") { external("https://en.wikipedia.org/w/index.php?oldid=${a.revision}") })
        }
        list.addFooterView(footer,null,false)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = blocks.size
            override fun getItem(p: Int) = blocks[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, recycled: View?, parent: ViewGroup): View = markedBlock(blockView(blocks[p],p,a),p)
            override fun isEnabled(position: Int) = false
        }
        c.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) { refreshLinkStyles() }
        })
        reader = list; show(c); displayingReader=true; thumbControl.invalidate(); list.setSelectionFromTop(position,positionOffset)
    }
    private fun markedBlock(content: View, index: Int): View {
        val local=articleMarks.filter { it.block==index }
        return object : FrameLayout(this) {
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            init {
                setPadding(dp(14),0,0,0); setWillNotDraw(false)
                addView(content,FrameLayout.LayoutParams(-1,-2))
            }
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                super.dispatchDraw(canvas)
                val tv=textViews(content).firstOrNull()
                local.forEachIndexed { n,m ->
                    var y=dp(12).toFloat()
                    if(tv?.layout!=null) {
                        val rect=android.graphics.Rect(); tv.getDrawingRect(rect); offsetDescendantRectToMyCoords(tv,rect)
                        val line=tv.layout.getLineForOffset(m.start.coerceIn(0,tv.text.length))
                        y=rect.top+tv.totalPaddingTop+tv.layout.getLineBaseline(line)-dp(4).toFloat()
                    }
                    paint.color=m.color
                    canvas.drawCircle(dp(3+(n%3)*4).toFloat(),y,dp(2).toFloat(),paint)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if(event.x<dp(14) && local.isNotEmpty()) {
                    if(event.actionMasked==MotionEvent.ACTION_UP) {
                        if(local.size==1) markDetail(local.first())
                        else bottom(AlertDialog.Builder(this@MainActivity).setItems(local.map { it.category+" · "+it.quote.take(50) }.toTypedArray()) { _,i -> markDetail(local[i]) }.create())
                    }
                    return true
                }
                return super.onTouchEvent(event)
            }
        }
    }
    private fun blockView(b: Block, index: Int, a: Article): View {
        if(b.kind=="infobox") {
            val c=column()
            val lead=ArticleBlocks.leadImage(b.html)
            lead?.let { c.addView(blockView(it,index,a)) }
            c.addView(button(if(index in expandedBoxes) "Details ▴" else "Details ▾") {
                savePosition(); if(!expandedBoxes.add(index)) expandedBoxes.remove(index)
                (reader?.adapter as? HeaderViewListAdapter)?.wrappedAdapter?.let { (it as? BaseAdapter)?.notifyDataSetChanged() }
            }.apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL; contentDescription="${if(index in expandedBoxes) "Collapse" else "Expand"} article infobox" })
            if(index in expandedBoxes) {
                ArticleBlocks.images(b.html).filter { it.source!=lead?.source }.forEach { c.addView(blockView(it,index,a)) }
                c.addView(blockView(Block("table",b.html),index,a))
            }; return c
        }
        if(b.kind=="table") {
            val rows=ArticleBlocks.tableRows(b.html)
            val c=column(); val grid=GridLayout(this)
            val occupied=mutableSetOf<Pair<Int,Int>>()
            val count=tableLimits[index] ?: 30
            rows.take(count).forEachIndexed { r,cells ->
                var col=0
                cells.forEach { cell ->
                    while((col until col+cell.columns).any { (r to it) in occupied }) col++
                    for(y in r until r+cell.rows) for(x in col until col+cell.columns) occupied+=y to x
                    val t=label(nativeText(cell.html.replace(Regex("<img\\b[^>]*>"),"")),15f).apply {
                        typeface=if(cell.heading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setPadding(dp(10),dp(8),dp(10),dp(8)); maxWidth=dp(250); setTextIsSelectable(true)
                        setBackgroundColor(if(cell.heading) line else paper)
                        movementMethod=ReaderLinks { peek(it) }; setLinkTextColor(accent)
                    }
                    val params=GridLayout.LayoutParams(GridLayout.spec(r,cell.rows),GridLayout.spec(col,cell.columns)).apply { width=dp(if(cell.columns>1) (cell.columns*150).coerceAtMost(500) else 180); height=-2; setMargins(0,0,dp(1),dp(1)) }
                    grid.addView(t,params); col+=cell.columns
                }
            }
            c.addView(HorizontalScrollView(this).apply { addView(grid) })
            if(rows.size>count) c.addView(button("More rows (${rows.size-count} remaining)") { tableLimits[index]=count+30; savePosition(); render() })
            return c
        }
        if(b.kind == "image") {
            val c = column().apply { setPadding(0,dp(8),0,dp(16)) }
            val image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = Html.fromHtml(b.html,0).toString(); minimumHeight = dp(100); maxHeight = dp(420); setBackgroundColor(line) }
            c.addView(image,LinearLayout.LayoutParams(-1,dp(220)))
            val caption = label(Html.fromHtml(b.html,0),12f,muted); c.addView(caption)
            repo.imageExecutor.execute {
                try {
                    val bitmap=repo.bitmap(repo.image(b.source))
                    runOnUiThread { if(!isDestroyed) { if(bitmap!=null) image.setImageBitmap(bitmap) else caption.text="${b.html} · image format unavailable" } }
                } catch(e: Exception) { runOnUiThread { if(!isDestroyed) caption.text="${Html.fromHtml(b.html,0)} · image unavailable offline" } }
            }
            image.setOnClickListener { external(b.source) }
            return c
        }
        val text = nativeText(b.html)
        val localMarks = articleMarks.filter { it.block == index }
        if(findQuery.isNotEmpty()) {
            var from=0
            while(from<text.length) { val at=text.toString().indexOf(findQuery,from,true); if(at<0) break; text.setSpan(BackgroundColorSpan(0x55d6b548),at,at+findQuery.length,0); from=at+findQuery.length }
        }
        val heading = b.kind.startsWith("h") && b.kind.length == 2
        val tv = label(text,if(heading) (if(b.kind == "h2") 27f else 22f) else db.preferences.getInt("fontSize",19).toFloat()).apply {
            tag=index
            typeface = Typeface.create(if(b.kind == "pre") "monospace" else "serif",if(heading) Typeface.BOLD else Typeface.NORMAL)
            setPadding(0,dp(if(heading) 24 else 2),0,dp(if(heading) 12 else 16))
            setLineSpacing(dp(5).toFloat(),1.12f)
            setTextIsSelectable(true); movementMethod = ReaderLinks { peek(it) }; setLinkTextColor(accent)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode,menu: Menu): Boolean { menu.add(0,101,0,"Mark"); return true }
                override fun onPrepareActionMode(mode: ActionMode,menu: Menu) = false
                override fun onActionItemClicked(mode: ActionMode,item: MenuItem): Boolean {
                    if(item.itemId != 101) return false
                    val start = selectionStart.coerceAtLeast(0); val end = selectionEnd.coerceAtLeast(start)
                    quickMark(index,start,end,text.subSequence(start,end).toString()); mode.finish(); return true
                }
                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
        }
        val c = column()
        // Marks live in the margin without changing article typography.
        if(heading) c.addView(tv.apply { setOnLongClickListener { quickMark(index,0,0,text.toString()); true } })
        else if(b.kind == "table") c.addView(HorizontalScrollView(this).apply { background = rounded(Color.rgb(239,240,232)); addView(tv.apply { setPadding(dp(12),dp(12),dp(12),dp(12)); minWidth = dp(460) }) })
        else c.addView(tv)
        return c
    }
    private fun nativeText(html: String): SpannableStringBuilder {
        val text=SpannableStringBuilder(Html.fromHtml(html,Html.FROM_HTML_MODE_COMPACT).trim())
        for(span in text.getSpans(0,text.length,URLSpan::class.java)) {
            val start=text.getSpanStart(span); val end=text.getSpanEnd(span)
            text.removeSpan(span); text.setSpan(ReaderLink(span.url) { link(it) },start,end,0)
        }; return text
    }
    private fun peek(url: String) {
        val uri=Uri.parse(if(url.startsWith("//")) "https:$url" else url)
        val path=uri.path.orEmpty()
        if((uri.host!=null && uri.host !in setOf("en.wikipedia.org","en.m.wikipedia.org")) || !path.startsWith("/wiki/")) {
            bottom(AlertDialog.Builder(this).setMessage(url).setPositiveButton("Open") { _,_ -> link(url) }.setNegativeButton("Close",null).create()); return
        }
        val title=path.removePrefix("/wiki/").replace('_',' ')
        val preview=label("Loading…",17f).apply { setPadding(dp(20),dp(12),dp(20),dp(12)); typeface=Typeface.create("serif",Typeface.NORMAL) }
        val dialog=bottom(AlertDialog.Builder(this).setTitle(title).setView(scroll(preview)).setPositiveButton("Read") { _,_ -> open(title,fragment=uri.fragment) }.setNegativeButton("Keep reading",null).create())
        async({ val a=repo.load(title); ArticleBlocks.parse(a.html).filter { it.kind=="p" }.take(3).joinToString("\n\n") { Html.fromHtml(it.html,0).toString() }.take(1800) }, { if(dialog.isShowing) preview.text=it }, { if(dialog.isShowing) preview.text=it.message ?: "Preview unavailable" })
    }
    private fun link(url: String) {
        val normalized = if(url.startsWith("//")) "https:$url" else url
        val uri = Uri.parse(normalized)
        if(normalized.startsWith("#")) { jumpAnchor(Uri.decode(normalized.drop(1))); return }
        val internal = uri.host == null || uri.host == "en.wikipedia.org" || uri.host == "en.m.wikipedia.org"
        val path = uri.path.orEmpty()
        if(internal && (path.startsWith("/wiki/") || path.startsWith("./"))) {
            val title = (if(path.startsWith("/wiki/")) path.removePrefix("/wiki/") else path.removePrefix("./")).replace('_',' ')
            if(title == article?.title && uri.fragment != null) jumpAnchor(uri.fragment!!) else open(title,fragment=uri.fragment)
        } else if(internal && path == "/w/index.php" && uri.getQueryParameter("title") != null) {
            open(uri.getQueryParameter("title")!!.replace('_',' '),uri.getQueryParameter("oldid")?.toLongOrNull())
        } else external(if(uri.host == null) "https://en.wikipedia.org$normalized" else normalized)
    }
    private fun jumpAnchor(anchor: String) {
        val index = blocks.indexOfFirst { it.anchor == anchor }
        if(index >= 0) reader?.setSelection(index+1) else message("This reference is not available in the native view. Open the source for its full layout.")
    }
    private fun external(url: String) {
        if(Uri.parse(url).scheme !in setOf("https","http")) return
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(e: Exception) { message("No browser is installed.") }
    }
    private fun contents() {
        if(article==null) { menu(); return }
        if(!displayingReader) switch("Read")
        val targets=navigationTargets()
        bottom(AlertDialog.Builder(this).setTitle("Contents").setItems(targets.map { it.title }.toTypedArray()) { _,i ->
            targets[i].action()
        }.setNegativeButton("Close",null).create())
    }
    private fun revisions(before: Long? = null) {
        val a = article ?: return
        val c = padded(column(),16)
        c.addView(label("Reading revision ${a.revision}",14f,muted))
        c.addView(button("Check & open latest") { open(a.title,force=true,remember=false) })
        val loading = label("Loading revision history…",14f,muted); c.addView(loading)
        val dialog = AlertDialog.Builder(this).setTitle("Revision history").setView(scroll(c)).setNegativeButton("Close",null).show()
        async({ repo.api.revisions(a.title,before) }, { revisions ->
            c.removeView(loading)
            revisions.forEach { r -> c.addView(card(r.date.replace('T',' ').removeSuffix("Z"),"${r.id} · ${r.comment}") { dialog.dismiss(); open(a.title,r.id) }) }
            if(revisions.size == 30) c.addView(button("Older revisions →") { dialog.dismiss(); revisions(revisions.last().id-1) })
        }, { loading.text = "Revision history unavailable. Saved revision ${a.revision} is still readable." })
    }
    private fun search() {
        savePosition(); generation++; searchGeneration++
        val c = padded(column())
        val field = EditText(this).apply { hint = "A subject, a phrase, a question"; setSingleLine(); textSize = 18f; setTextColor(ink); setHintTextColor(muted) }
        c.addView(field)
        val results = column(); c.addView(scroll(results),LinearLayout.LayoutParams(-1,0,1f)); show(c)
        val screen = viewGeneration
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) = Unit
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                val q = s.toString().trim(); val request = ++searchGeneration
                results.removeAllViews(); if(q.isBlank()) return
                handler.postDelayed({
                    if(request != searchGeneration || screen != viewGeneration) return@postDelayed
                    async({ db.search(q) }, { local ->
                        if(request != searchGeneration || screen != viewGeneration) return@async
                        results.addView(label("YOUR LIBRARY · ${local.size}",11f,accent).apply { setPadding(0,dp(18),0,dp(12)) })
                        local.forEach { hit -> results.addView(card(hit.title,hit.snippet) { hideKeyboard(field); open(hit.title) }) }
                        val status = label("Searching Wikipedia…",12f,muted); results.addView(status)
                        async({ if(request==searchGeneration && screen==viewGeneration) repo.api.search(q) else emptyList() }, { remote ->
                            if(request != searchGeneration || screen != viewGeneration) return@async
                            status.text = "WIKIPEDIA"
                            remote.filter { r -> local.none { it.title == r.title } }.forEach { hit -> results.addView(card(hit.title,hit.snippet) { hideKeyboard(field); open(hit.title) }) }
                            if(local.isEmpty() && remote.isEmpty()) status.text = "No articles found. Try another phrase."
                        }, { if(request == searchGeneration && screen == viewGeneration) status.text = "Wikipedia unavailable · cached results above remain usable" })
                    })
                },300)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        results.addView(label("Search saved article text and Wikipedia together. Your library works offline.",15f,muted).apply { setPadding(0,dp(20),0,0) })
        field.requestFocus()
        field.postDelayed({ if(screen == viewGeneration) getSystemService(InputMethodManager::class.java).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) },100)
    }
    private fun hideKeyboard(view: View) { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0) }
    private fun trail() {
        val c = padded(column())
        title(c,"History")
        val tools = row(); tools.addView(button("Jump to date") { chooseDate { trail() } }); tools.addView(button("All time") { trailBefore=Long.MAX_VALUE; trailQuery=""; trail() }); c.addView(tools)
        val field = EditText(this).apply { hint="Filter article titles"; setSingleLine(); setTextColor(ink); setHintTextColor(muted); setText(trailQuery) }; c.addView(field)
        c.addView(button("Filter") { trailQuery=field.text.toString(); hideKeyboard(field); trail() })
        val entries = column(); c.addView(entries)
        val scroller = scroll(c); show(scroller)
        val screen = viewGeneration
        fun append(before: Long) {
            async({ db.visits(before,trailQuery) }, { visits ->
                if(screen != viewGeneration) return@async
                if(visits.isEmpty()) entries.addView(label("No visits here yet. Your next reading session starts in Search.",15f,muted))
                var lastDay = ""
                visits.forEach { v ->
                    val day = SimpleDateFormat("EEEE, d MMMM yyyy",Locale.getDefault()).format(Date(v.time))
                    if(day != lastDay) { entries.addView(label(day,12f,accent).apply { setPadding(0,dp(22),0,dp(12)) }); lastDay=day }
                    val parent = if(v.parent > 0) db.getVisit(v.parent)?.title else null
                    entries.addView(card(v.title,"${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(v.time))}${parent?.let { " · from $it" } ?: " · started a thread"}") {
                        AlertDialog.Builder(this).setTitle(v.title).setItems(arrayOf("Resume · recent enough revision","Read exactly what I read · ${v.revision}","Show this date in Atlas")) { _,i ->
                            when(i) { 0 -> open(v.title,restore=v); 1 -> open(v.title,v.revision,restore=v); else -> { trailBefore=v.id+1; switch("Atlas") } }
                        }.show()
                    })
                }
                if(visits.size == 100) {
                    val more = button("Earlier visits ↓") { }; more.setOnClickListener { entries.removeView(more); append(visits.last().id) }; entries.addView(more)
                }
            })
        }
        append(trailBefore)
    }
    private fun chooseDate(after: () -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this,{ _,year,month,day ->
            calendar.set(year,month,day,23,59,59)
            val until = calendar.timeInMillis
            async({ db.readableDatabase.rawQuery("SELECT coalesce(max(id),0)+1 FROM visits WHERE time<=?",arrayOf(until.toString())).use { it.moveToFirst(); it.getLong(0) } }, { trailBefore=it; after() })
        },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun atlas() {
        val c = padded(column(),16)
        title(c,"Reading paths")
        val controls = row(); controls.addView(button("Jump to date") { chooseDate { atlas() } }); controls.addView(button("Latest") { trailBefore=Long.MAX_VALUE; atlas() }); controls.addView(button("Trail ↗") { switch("Trail") }); c.addView(controls)
        val status = label("Loading paths…",12f,muted); c.addView(status)
        show(c); val screen = viewGeneration
        async({ db.visits(trailBefore,trailQuery,200) }, { visits ->
            if(screen != viewGeneration) return@async
            status.text = "${visits.map { it.title }.distinct().size} articles · ${visits.size} visits in this window${if(trailQuery.isNotBlank()) " · $trailQuery" else ""}"
            if(visits.isEmpty()) { c.addView(label("Follow a link while reading to begin your atlas.",16f,muted)); return@async }
            val graph = AtlasView(this,visits) { v -> open(v.title,restore=v) }
            c.addView(graph,LinearLayout.LayoutParams(-1,0,1f))
            val actions = row(); actions.addView(button("Article list") { AlertDialog.Builder(this).setTitle("Articles in this atlas").setItems(visits.distinctBy { it.title }.map { it.title }.toTypedArray()) { _,i -> val v=visits.distinctBy { it.title }[i]; open(v.title,restore=v) }.show() })
            if(visits.size == 200) actions.addView(button("Earlier paths →") { trailBefore=visits.last().id; atlas() })
            c.addView(actions)
        })
    }
    private fun addMark(block: Int, start: Int, end: Int, quote: String, existing: Mark? = null) {
        val a = article ?: return
        val c = padded(column(),16)
        c.addView(label(if(block < 0) a.title else quote.take(240),16f))
        val cats = db.categories()
        val choices = Spinner(this); choices.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,cats.map { it.first } + "Create category…"); c.addView(choices)
        choices.setSelection(cats.indexOfFirst { it.first==(existing?.category ?: db.preferences.getString("lastCategory","Keep")) }.coerceAtLeast(0))
        val name = EditText(this).apply { hint="New category name"; setSingleLine(); visibility=View.GONE }; c.addView(name)
        val colors = listOf(Color.rgb(188,71,65),Color.rgb(45,113,94),Color.rgb(183,138,48),Color.rgb(100,93,167),Color.rgb(47,117,161))
        var selectedColor = colors[0]
        val swatches = row().apply { visibility=View.GONE }
        colors.forEachIndexed { i,color -> swatches.addView(button(if(i==0) "●" else "○") { selectedColor=color; for(j in 0 until swatches.childCount) (swatches.getChildAt(j) as Button).text=if(j==i) "●" else "○" }.apply { setTextColor(color); contentDescription=listOf("Red","Green","Gold","Violet","Blue")[i] },LinearLayout.LayoutParams(0,dp(48),1f)) }; c.addView(swatches)
        choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,p: Int,id: Long) { name.visibility=if(p==cats.size) View.VISIBLE else View.GONE; swatches.visibility=name.visibility }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val note = EditText(this).apply { hint="Note"; minLines=2; maxLines=5; setText(existing?.note.orEmpty()) }; c.addView(note)
        c.addView(label("Saved with revision ${a.revision} and the time you marked it.",12f,muted))
        val dialog = AlertDialog.Builder(this).setTitle(if(block<0) "Mark article" else if(end>start) "Mark passage" else "Mark section").setView(scroll(c)).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val category = if(choices.selectedItemPosition==cats.size) name.text.toString().trim() else cats[choices.selectedItemPosition].first
            if(category.isBlank() || category.length>48) { name.error="Use 1–48 characters"; return@setOnClickListener }
            db.category(category,selectedColor)
            if(existing==null) db.mark(a,block,start,end,quote,note.text.toString(),category) else db.editMark(existing.id,category,note.text.toString())
            db.preferences.edit().putString("lastCategory",category).apply()
            savePosition(); dialog.dismiss(); render(); message("Marked · $category")
        } }; bottom(dialog)
    }
    private fun marks(category: String? = null) {
        val c = padded(column()); title(c,"Marks")
        val filter = Spinner(this); val cats=listOf("All marks") + db.categories().map { it.first }
        filter.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,cats); filter.setSelection(if(category==null) 0 else cats.indexOf(category).coerceAtLeast(0)); c.addView(filter)
        var initial=true
        filter.onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,v: View?,p: Int,id: Long) { if(initial) { initial=false; return }; marks(if(p==0) null else cats[p]) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val entries=column(); c.addView(entries); show(scroll(c)); val screen=viewGeneration
        fun append(before: Long) {
            async({ db.marks(category,before=before) }, { found ->
                if(screen!=viewGeneration) return@async
                if(before==Long.MAX_VALUE && found.isNotEmpty()) entries.addView(button("Retrace →") { stackCategory=category; stack=found; stackIndex=0; openMark(found[0]) })
                if(found.isEmpty() && before==Long.MAX_VALUE) entries.addView(label("Drag down from the reader control, then scrub sideways to place a mark. Pull farther for a paragraph or word. Selected text and section headings can be marked too.",16f,muted))
                found.forEach { m -> entries.addView(column().apply {
                    setPadding(0,dp(14),0,dp(10)); layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }
                    addView(label("● ${m.category} · ${dateFormat.format(Date(m.time))}",11f,m.color))
                    addView(label(m.title,21f).apply { typeface=Typeface.create("serif",Typeface.NORMAL); setPadding(0,dp(8),0,dp(8)) })
                    if(m.quote.isNotEmpty()) addView(label("“${m.quote.take(300)}”",16f))
                    if(m.note.isNotEmpty()) addView(label(m.note,14f,muted).apply { setPadding(0,dp(8),0,0) })
                    addView(button("Open mark · revision ${m.revision}") { openMark(m) }); setOnLongClickListener { markDetail(m); true }
                }) }
                if(found.size==100) { val more=button("Earlier marks ↓") {}; more.setOnClickListener { entries.removeView(more); append(found.last().id) }; entries.addView(more) }
            })
        }; append(Long.MAX_VALUE)
    }
    private fun openMark(m: Mark) { open(m.title,m.revision,mark=m) }
    private fun stackMove(delta: Int) {
        val target=stackIndex+delta
        if(target in stack.indices) { stackIndex=target; openMark(stack[target]); return }
        if(delta>0 && stack.isNotEmpty()) async({ db.marks(stackCategory,before=stack.last().id) }, { more ->
            if(more.isEmpty()) message("Beginning of this stack") else { stack=stack+more; stackIndex=target; openMark(stack[target]) }
        }) else message("Newest mark in this stack")
    }
    private fun markDetail(m: Mark) { AlertDialog.Builder(this).setTitle(m.category).setMessage("${m.quote}\n\n${m.note}\n\n${dateFormat.format(Date(m.time))} · revision ${m.revision}").setPositiveButton("Open") { _,_ -> openMark(m) }.setNeutralButton("Delete") { _,_ ->
        db.deleteMark(m.id); if(tab=="Read") { savePosition(); render() } else marks()
    }.setNegativeButton("Close",null).show() }
    private fun settings() {
        savePosition(); generation++
        val c=padded(column()); title(c,"Settings")
        val summary=label("Calculating cache…",14f,muted); c.addView(summary)
        async({ "${db.scalar("SELECT count(*) FROM revisions")} saved revisions · ${"%.2f".format(db.cacheBytes()/1073741824.0)} GiB\n${db.queueCount()} queued · ${repo.queueStatus}" }, { summary.text=it })
        fun setting(name: String,values: List<String>,selected: Int,change: (Int)->Unit) {
            c.addView(label(name,15f).apply { setPadding(0,dp(22),0,dp(6)) }); val spinner=Spinner(this); spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values); spinner.setSelection(selected)
            var initial=true; spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long) { if(initial) initial=false else change(i) }; override fun onNothingSelected(p:AdapterView<*>?)=Unit
            }; c.addView(spinner)
        }
        val sizes=listOf(1,2,4,8,16,32); setting("Cache budget",sizes.map { "$it GiB" },sizes.indexOf(db.preferences.getInt("cacheGiB",16))) { db.preferences.edit().putInt("cacheGiB",sizes[it]).apply(); async({ db.evict(repo.activeRevision) },{ message("Cache budget updated") }) }
        val days=listOf(1,3,7,14,30); setting("Accept saved articles for",days.map { "$it days" },days.indexOf(db.preferences.getInt("freshDays",7))) { db.preferences.edit().putInt("freshDays",days[it]).apply() }
        val fonts=listOf(17,19,21,24); setting("Reading text size",fonts.map { "$it sp" },fonts.indexOf(db.preferences.getInt("fontSize",19))) { db.preferences.edit().putInt("fontSize",fonts[it]).apply() }
        fun toggle(text:String,key:String,default:Boolean) { c.addView(Switch(this).apply { this.text=text; textSize=15f; setTextColor(ink); setPadding(0,dp(18),0,dp(12)); isChecked=db.preferences.getBoolean(key,default); setOnCheckedChangeListener { _,checked -> db.preferences.edit().putBoolean(key,checked).apply(); repo.schedule(); repo.pump() } }) }
        toggle("Prefetch one link ahead","prefetch",true); toggle("Prefetch on unmetered networks only","wifiOnly",true); toggle("Pause fetch queue","paused",false)
        c.addView(button("Retry failed downloads") { db.retryQueue(); repo.pump(); message("Downloads ready to retry") })
        c.addView(button("Inspect queue") { queue() })
        c.addView(label("Prefetch saves direct links only, on Android’s schedule. It batches revision checks and skips unchanged content. All traffic shares a 2 requests/second ceiling with one request in flight. Background requests yield when Wikipedia is busy; server cooldowns take precedence. Opening an article explicitly can use mobile data.",13f,muted).apply { setPadding(0,dp(24),0,0) })
        c.addView(button("Back to $tab") { switch(tab) }); show(scroll(c))
    }
    private fun queue() {
        async({ db.readableDatabase.rawQuery("SELECT title,depth,attempts,error FROM queue ORDER BY priority DESC,distance,added LIMIT 100",null).use { cursor -> buildList { while(cursor.moveToNext()) add("${cursor.getString(0)}\n${if(cursor.getInt(1)<0) "Image" else "${cursor.getInt(1)} more link levels"} · ${cursor.getInt(2)} attempts${cursor.getString(3)?.let { "\n$it" }.orEmpty()}") } } }, { items ->
            AlertDialog.Builder(this).setTitle("Fetch queue · first 100").setItems(items.ifEmpty { listOf("Queue is empty") }.toTypedArray(),null).setPositiveButton("Done",null).show()
        })
    }
    private fun goBack() {
        cancelThumbGesture?.invoke()
        generation++
        if(!displayingReader && article!=null) { switch("Read"); return }
        if(back.isNotEmpty()) { val location=back.removeLast(); val v=location.visit; open(v.title,v.revision,restore=v,remember=false,pinned=location.pinned) }
        else if(tab!="Read") switch("Read") else if(article!=null) { savePosition(); article=null; reader=null; home() } else finish()
    }
    @Deprecated("Platform back dispatch") override fun onBackPressed() { goBack() }
    override fun onKeyDown(code: Int,event: KeyEvent): Boolean {
        if(event.isCtrlPressed) when(code) { KeyEvent.KEYCODE_K -> { search(); return true }; KeyEvent.KEYCODE_F -> { findInArticle(); return true }; KeyEvent.KEYCODE_M -> { quickMark(); return true }; KeyEvent.KEYCODE_H -> { switch("Trail"); return true } }
        return super.onKeyDown(code,event)
    }
    override fun onResume() { super.onResume(); if(::repo.isInitialized) { repo.foregroundActive=true; repo.pump() } }
    override fun onPause() { cancelThumbGesture?.invoke(); savePosition(); repo.foregroundActive=false; super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { savePosition(); out.putLong("visit",visitId); super.onSaveInstanceState(out) }
    override fun onDestroy() { generation++; searchGeneration++; handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
