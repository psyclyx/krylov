package dev.psyclyx.krylov.tests

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.psyclyx.krylov.Article
import dev.psyclyx.krylov.Block
import dev.psyclyx.krylov.Library
import java.io.File

class LibraryInstrumentation : Instrumentation() {
    private var seed=false
    private var gestures=false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        gestures=arguments?.getString("gestures")=="true"
        seed=arguments?.getString("seed")=="true" || gestures
        start()
    }
    override fun onStart() {
        val report=StringBuilder()
        try {
            libraryTests(report)
            ReadChecks.run(targetContext,report)
            if(seed) seedReader(report)
            if(gestures) GestureChecks.run(this,report)
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream",report.toString()) })
        } catch(t: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply {
                putString("stream",report.toString()+"FAIL: "+android.util.Log.getStackTraceString(t))
            })
        }
    }
    private fun article(title: String, revision: Long, text: String) = Article(
        title,revision,"<p>$text</p>",listOf("Other article"),Library.now()
    )
    private fun libraryTests(report: StringBuilder) {
        // Instrumentation runs under the target UID; isolate names within its
        // writable storage instead of attempting to write another package's UID.
        val context=object : android.content.ContextWrapper(targetContext) {
            override fun getPackageName()="dev.psyclyx.krylov.tests"
            override fun getDatabasePath(name: String)=targetContext.getDatabasePath("instrumentation_$name").apply { parentFile?.mkdirs() }
            override fun deleteDatabase(name: String)=targetContext.deleteDatabase("instrumentation_$name")
            override fun getSharedPreferences(name: String,mode: Int)=targetContext.getSharedPreferences("instrumentation_$name",mode)
            override fun getFilesDir()=File(targetContext.filesDir,"instrumentation").apply { mkdirs() }
            override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) =
                openOrCreateDatabase(name,mode,factory,null)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,handler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase {
                val flags=android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY or
                    (if(mode and android.content.Context.MODE_ENABLE_WRITE_AHEAD_LOGGING!=0) android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING else 0)
                return android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath(name).path,factory,flags,handler)
            }
        }
        check(context.packageName=="dev.psyclyx.krylov.tests")
        context.deleteDatabase("library.db")
        context.getSharedPreferences("reader",0).edit().clear().commit()
        migrationTest(context,report)
        val library=Library(context)
        try {
            val latest=article("Test article",102,"Contemporary quantum mechanics")
            val historic=article("Test article",101,"Historic obsolete wording")
            library.save(latest,"test_article",true,"Contemporary quantum mechanics")
            library.save(historic,"Test article",false,"Historic obsolete wording")
            check(library.cached("test_article")?.revision==102L)
            check(library.cached("Test article",101)?.html==historic.html)
            check(library.cached("Test article",101)?.links==historic.links)
            check(library.cached("Test article")?.revision==102L)
            check(library.fresh("test_article"))
            check(library.search("quantum").single().title=="Test article")
            check(library.search("obsolete").isEmpty())
            library.writableDatabase.execSQL("UPDATE articles SET checked=0")
            check(!library.fresh("Test article"))
            check(!library.verified("Test article",101))
            check(library.verified("Test article",102))
            check(library.fresh("Test article"))
            report.append("PASS exact/latest isolation, aliases, FTS, freshness verification\n")
            val projection=listOf(
                Block("p","<p>"+"α🌍".repeat(40_000)+"</p>","anchor-α","https://upload.wikimedia.org/fixture.png"),
                Block("h2","Section","stable section anchor","")
            )
            check(library.blocks(latest.revision)==null)
            check(library.missingBlockProjections(listOf("test_article","Missing","Test article"))==listOf(latest.revision))
            library.saveBlocks(latest.revision,projection)
            check(library.blocks(latest.revision)==projection) { "Block fields over 64 KiB did not round-trip" }
            Library(context).use { reopened -> check(reopened.blocks(latest.revision)==projection) }
            check(library.missingBlockProjections(listOf("test_article","Missing")).isEmpty())
            library.writableDatabase.execSQL("UPDATE revision_blocks SET formatversion=-1 WHERE revision=?",arrayOf<Any>(latest.revision))
            check(library.blocks(latest.revision)==null) { "Outdated block format was reused" }
            check(library.missingBlockProjections(listOf("test_article"))==listOf(latest.revision))
            library.saveBlocks(latest.revision,projection)
            library.writableDatabase.execSQL("UPDATE revision_blocks SET data=x'00' WHERE revision=?",arrayOf<Any>(latest.revision))
            check(library.blocks(latest.revision)==null) { "Corrupt projection must fall back to parsing" }
            library.saveBlocks(latest.revision,projection)
            library.saveBlocks(999999,listOf(Block("p","Evicted")))
            check(library.blocks(999999)==null)
            library.save(historic,historic.title,false,"Historic obsolete wording",listOf(Block("p","Historic obsolete wording")))
            check(library.blocks(historic.revision)==listOf(Block("p","Historic obsolete wording")))
            report.append("PASS durable projection roundtrip, large UTF-8 fields, format/corruption fallback, no resurrection\n")
            val linkTargets=listOf(Library.LinkTarget("test_article"),Library.LinkTarget("Missing"),
                Library.LinkTarget("Test article",101),Library.LinkTarget("Other",101),Library.LinkTarget("Test article",999))
            check(library.cachedLinks(linkTargets)==setOf(linkTargets[0],linkTargets[2],linkTargets[3]))
            report.append("PASS cached-link availability, normalized aliases, exact revisions and absent bodies\n")


            library.category("Custom",0xff123456.toInt())
            val mark=library.mark(historic,0,9,17,"obsolete","Discuss wording","Discuss")
            val second=library.mark(latest,-1,0,0,"","Whole article","Keep")
            check(library.marks().map { it.id }==listOf(second,mark))
            check(library.marks(revision=101).single().id==mark)
            check(library.marks(category="Discuss").single().id==mark)
            check(library.marks(category="Discuss",revision=102).isEmpty())
            check(library.marks(before=second).single().id==mark)
            val anchor=library.marks(revision=101).single()
            library.editMark(mark,"Custom","Revised note")
            val edited=library.marks(category="Custom").single()
            check(edited.note=="Revised note" && edited.color==0xff123456.toInt())
            check(edited.revision==anchor.revision && edited.block==anchor.block && edited.start==anchor.start && edited.end==anchor.end && edited.quote==anchor.quote && edited.time==anchor.time)
            library.deleteMark(second)
            check(library.marks().single().id==mark)
            report.append("PASS nullable mark filters, edit/delete, immutable revision anchors\n")

            val first=library.visit(latest,0)
            val next=library.visit(historic,first)
            val repeated=library.visit(latest,next)
            library.position(first,3,-27)
            check(library.visits().map { it.id }==listOf(repeated,next,first))
            check(library.getVisit(first)?.scroll==3 && library.getVisit(first)?.offset == -27)
            check(library.getVisit(next)?.parent==first && library.getVisit(repeated)?.parent==next)
            check(library.visits(before=repeated,limit=1).single().id==next)
            report.append("PASS repeated visits, parent paths, pagination, reading offsets\n")

            library.enqueue("test_article",0)
            library.expected("Test article",102)
            check(library.uncheckedBatch().isEmpty())
            library.enqueue("Test article",1,1)
            library.enqueue("Test article",0)
            library.continuation("Test article","{\"gplcontinue\":\"fixture\"}")
            library.completeDownload("Test article")
            check(library.next()?.depth==1 && library.next()?.expected==102L)
            check(library.next()?.cursor?.contains("fixture")==true)
            library.enqueue("Pending download",0)
            check(library.uncheckedBatch()==listOf("Pending download"))
            library.completeDownload("Pending download")
            check(library.queueCount()==1L)
            library.complete("Test article")
            check(library.next()==null)
            report.append("PASS queue deduplication, upgrade, verified IDs, exploration preservation\n")

            val currentContext=10_000_000_001L
            repeat(64) { library.enqueue("Old article $it",0) }
            library.enqueue("Old image",-1,-1)
            library.enqueue("Old exploration",1,1)
            library.enqueue("Current article",0,currentContext)
            library.enqueue("Current image",-1,currentContext)
            library.enqueue("Current exploration",1,currentContext)
            check(library.uncheckedBatch().first()=="Current article")
            repeat(4) {
                check(library.nextFair()?.title=="Current image")
                check(library.nextFair()?.title=="Current exploration")
                check(library.nextFair()?.title=="Current article")
            }
            check(library.next(-1)?.priority==currentContext)
            library.defer("Current image",Library.now()+60_000,"fixture backoff")
            check(library.nextFair()?.title=="Current exploration")
            library.enqueue("Current nearby image",-1,currentContext,0)
            check(library.nextFair()?.title=="Current nearby image")
            library.enqueue("Newest article",0,currentContext+1,1)
            check(library.nextFair()?.title=="Newest article")
            library.defer("Newest article",Library.now()+60_000,"fixture backoff")
            check(library.nextFair()?.title=="Current nearby image")
            library.enqueue("Current nearby image",-1,currentContext-1,0)
            check(library.next(-1)?.priority==currentContext && library.next(-1)?.distance==0)
            library.enqueue("Current nearby image",-1,currentContext+2,1)
            check(library.nextFair()?.distance==1)
            library.enqueue("Current nearby image",-1,currentContext+2,0)
            library.enqueue("Current nearby image",-1,currentContext+2,1)
            check(library.nextFair()?.distance==0)
            library.writableDatabase.execSQL("DELETE FROM queue WHERE depth<>0")
            check(library.nextFair()?.title=="Current article")
            library.writableDatabase.execSQL("DELETE FROM queue")
            check(library.nextFair()==null)
            report.append("PASS global context/distance precedence, fair equal-rank interleave, backoff and empty-class fallback\n")

            library.enqueue("Already queued",1,5,0)
            library.expected("Already queued",321)
            library.continuation("Already queued","fixture cursor")
            library.alias("Queued alias","Already queued")
            library.enqueueLinks(listOf("test_article","Already queued","Queued alias","New linked page","New linked page"),currentContext)
            check(library.queueCount()==2L) { "Fresh cached links or duplicate aliases were queued" }
            check(library.next(1)?.let { it.title=="Already queued" && it.expected==321L && it.cursor=="fixture cursor" && it.distance==1 && it.priority==currentContext }==true)
            check(library.next(0)?.title=="New linked page")
            library.writableDatabase.execSQL("DELETE FROM queue")
            report.append("PASS transactional local-link enqueue, fresh-cache skips, alias deduplication, queue-state preservation\n")

            val staleArticle=article("Stale cached article",301,"Stale text")
            library.save(staleArticle,staleArticle.title,true,"Stale text")
            library.writableDatabase.execSQL("UPDATE articles SET checked=0 WHERE title='Stale cached article'")
            library.enqueue("test_article",0,currentContext)
            library.enqueue(staleArticle.title,0,currentContext)
            library.enqueue("Uncached article",0,currentContext)
            check(library.uncheckedBatch().toSet()==setOf("Stale cached article","Uncached article")) { "Fresh articles must not be metadata-checked" }
            library.alias("Late alias","Test article")
            library.writableDatabase.execSQL("INSERT INTO queue(title,depth,priority,added) VALUES('Late alias',0,0,0)")
            check("Late alias" !in library.uncheckedBatch())
            library.writableDatabase.execSQL("DELETE FROM queue")
            report.append("PASS recently checked cached pages and aliases excluded from all metadata batches\n")

            val db=library.writableDatabase
            check(library.scalar("PRAGMA auto_vacuum")==2L) { "Incremental vacuum not enabled" }
            db.execSQL("INSERT INTO images VALUES('fixture:a','a',100,1)")
            db.execSQL("INSERT INTO images VALUES('fixture:b','b',250,2)")
            check(library.scalar("SELECT image_bytes FROM cache_totals")==350L)
            db.execSQL("UPDATE images SET bytes=150 WHERE url='fixture:a'")
            check(library.scalar("SELECT image_bytes FROM cache_totals")==400L)
            db.execSQL("DELETE FROM images WHERE url='fixture:b'")
            check(library.scalar("SELECT image_bytes FROM cache_totals")==150L)
            check(!library.hasImage("fixture:a"))
            File(library.images,"a").writeBytes(ByteArray(150))
            check(library.hasImage("fixture:a"))
            check(!library.hasImage("fixture:missing"))
            val database=context.getDatabasePath("library.db")
            val physical=database.length()+File(database.path+"-wal").length()+File(database.path+"-shm").length()
            check(library.cacheBytes()==physical+150L)
            report.append("PASS image insert/update/delete accounting and physical byte accounting\n")

            db.rawQuery("PRAGMA wal_autocheckpoint=0",null).use { check(it.moveToFirst()) }
            val protected=article("Protected article",201,"Protected cache entry")
            library.save(protected,protected.title,true,"Protected cache entry")
            File(library.images,"a").writeBytes(ByteArray(150))
            check(File(database.path+"-wal").length()>0L)
            library.preferences.edit().putInt("cacheGiB",0).commit()
            library.evict(protected.revision)
            check(File(database.path+"-wal").length()==0L) { "Eviction did not truncate the WAL" }
            check(!File(library.images,"a").exists())
            check(library.scalar("SELECT image_bytes FROM cache_totals")==0L)
            check(library.cached("Test article")==null && library.cached("Test article",101)==null)
            check(library.blocks(latest.revision)==null)
            check(library.scalar("SELECT count(*) FROM revision_blocks")==0L)
            check(library.cached(protected.title)?.revision==protected.revision)
            check(library.search("quantum").isEmpty())
            check(library.marks().single().quote=="obsolete")
            check(library.visits().size==3)
            report.append("PASS eviction, checkpoint truncation, active revision protection, history/mark retention\n")
        } finally {
            library.close()
            context.deleteDatabase("library.db")
            context.getSharedPreferences("reader",0).edit().clear().commit()
            File(context.filesDir,"images").deleteRecursively()
        }
    }
    private fun migrationTest(context: android.content.Context, report: StringBuilder) {
        for(version in 1..2) {
        val old=context.openOrCreateDatabase("library.db",0,null)
        try {
            old.execSQL("CREATE TABLE queue(title TEXT PRIMARY KEY, depth INTEGER NOT NULL, priority INTEGER NOT NULL, added INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, ready INTEGER NOT NULL DEFAULT 0, error TEXT, cursor TEXT, expected INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE INDEX queue_next ON queue(ready,priority DESC,added)")
            old.execSQL("INSERT INTO queue VALUES('Retained job',1,12345678901,42,2,0,'old error','old cursor',991)")
            if(version==2) {
                old.execSQL("ALTER TABLE queue ADD COLUMN distance INTEGER NOT NULL DEFAULT 1")
                old.execSQL("CREATE INDEX queue_rank ON queue(priority DESC,distance,depth,added) WHERE attempts<6")
            }
            old.execSQL("CREATE TABLE revisions(id INTEGER PRIMARY KEY)")
            old.execSQL("INSERT INTO revisions VALUES(7)")
            old.execSQL("CREATE TABLE retained(value TEXT)")
            old.execSQL("INSERT INTO retained VALUES('reading data')")
            old.version=version
        } finally { old.close() }
        val upgraded=Library(context)
        try {
            val p=upgraded.nextFair()
            check(upgraded.readableDatabase.version==3)
            check(p?.let { it.title=="Retained job" && it.priority==12345678901L && it.distance==1 && it.depth==1 && it.attempts==2 && it.expected==991L && it.cursor=="old cursor" }==true)
            check(upgraded.scalar("SELECT count(*) FROM retained WHERE value='reading data'")==1L)
            check(upgraded.scalar("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='queue_rank'")==1L)
            check(upgraded.scalar("SELECT added FROM queue")==42L)
            check(upgraded.scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='revision_blocks'")==1L)
            check(upgraded.scalar("SELECT count(*) FROM revisions WHERE id=7")==1L)
            upgraded.saveBlocks(7,listOf(Block("p","migrated")))
            check(upgraded.blocks(7)==listOf(Block("p","migrated")))
            report.append("PASS v$version-to-v3 migration retains queue state and existing data\n")
        } finally {
            upgraded.close()
            context.deleteDatabase("library.db")
        }
        }
    }
    private fun seedReader(report: StringBuilder) {
        val library=Library(targetContext)
        try {
            val text="""<table class="infobox"><tr><th>Type</th><td>Offline test fixture</td></tr><tr><th>Purpose</th><td>Native reader checks</td></tr></table><p>A reader follows an idea, then returns to the passage that started it. This offline fixture exercises native article rendering without making a network request. An <a href="/wiki/Krylov_missing_fixture">uncached link</a> stays dashed.</p><h2 id="Navigation">Navigation</h2><p>Follow a <a href="/wiki/Krylov_test_companion">related article</a>, mark a passage, and retrace the path in reading history.</p><h2 id="Memory">Memory</h2><p>Bookmarks preserve the revision and exact passage. A reading trail preserves each visit.</p>"""
            val a=Article("Krylov test article",9_000_000_001L,text,listOf("Krylov test companion"),Library.now())
            val b=article("Krylov test companion",9_000_000_002L,"A related article for testing navigation and return paths.")
            library.save(a,a.title,true,"A reader follows an idea. Navigation. Memory. Bookmarks.")
            library.save(b,b.title,true,"A related article for testing navigation and return paths.")
            val visit=library.visit(a,0)
            library.preferences.edit().putLong("lastVisit",visit).putBoolean("prefetch",false).commit()
            report.append("SEEDED target reader with offline fixture; prefetch disabled\n")
        } finally { library.close() }
    }
}
