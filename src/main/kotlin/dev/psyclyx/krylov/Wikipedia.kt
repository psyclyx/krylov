package dev.psyclyx.krylov

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private fun canonicalTitle(title: String, aliases: Map<String,String>): String {
    var current=title
    val seen=mutableSetOf<String>()
    while(seen.add(current)) current=aliases[current] ?: return current
    throw IOException("Wikipedia returned a redirect cycle.")
}

class Wikipedia(private val preferences: android.content.SharedPreferences) {
    private val gate = RequestGate().apply { cooldown(preferences.getLong("cooldownUntil",0)) }
    val background = ThreadLocal.withInitial { false }
    private fun cooldown(until: Long) {
        gate.cooldown(until)
        preferences.edit().putLong("cooldownUntil",gate.cooldownUntil).apply()
    }
    private fun backoff(): Long {
        val failures=preferences.getInt("serverFailures",0).coerceAtMost(9)
        preferences.edit().putInt("serverFailures",failures+1).apply()
        return Library.now()+(5000L shl failures)+java.util.concurrent.ThreadLocalRandom.current().nextLong(1000)
    }
    fun bytes(url: String, limit: Int = 16 * 1024 * 1024): ByteArray = request(url,limit) { bytes, _ -> bytes }
    private fun retryAfter(value: String?): Long = value?.toLongOrNull()?.let {
        Library.now()+it.coerceIn(0,Long.MAX_VALUE/2000)*1000
    } ?: runCatching {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",java.util.Locale.US).parse(value.orEmpty())?.time
    }.getOrNull() ?: Library.now()
    // Decode API errors before releasing admission, so the next waiter observes maxlag.
    private fun <T> request(url: String, limit: Int, decode: (ByteArray,String?) -> T): T = gate.run(background.get() == true) {
        val uri = URL(url)
        require(uri.protocol == "https" && uri.host in setOf("en.wikipedia.org", "upload.wikimedia.org"))
        val conn = uri.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 25000; conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "Krylov/0.1 (https://github.com/psyclyx/krylov; native Android reader)")
        conn.setRequestProperty("Accept", "application/json,image/*;q=0.9,*/*;q=0.8")
        try {
            if(conn.responseCode != 200) {
                if(conn.responseCode in setOf(429,503)) {
                    cooldown(maxOf(retryAfter(conn.getHeaderField("Retry-After")),backoff()))
                    throw DeferredRequest(gate.cooldownUntil,"Wikipedia returned HTTP ${conn.responseCode}; downloads are cooling down.")
                }
                throw IOException("Wikipedia returned HTTP ${conn.responseCode}. Try again later.")
            }
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while(true) { val n = input.read(buffer); if(n < 0) break
                    if(out.size() + n > limit) throw IOException("This resource exceeds the download limit.")
                    out.write(buffer,0,n)
                }; decode(out.toByteArray(),conn.getHeaderField("Retry-After"))
            }
        } finally { conn.disconnect() }
    }
    fun api(vararg args: Pair<String,String>): JSONObject {
        val query = (args.toList() + listOf("format" to "json", "formatversion" to "2") + if(background.get()==true) listOf("maxlag" to "5") else emptyList()).joinToString("&") { "${it.first}=${URLEncoder.encode(it.second,"UTF-8")}" }
        return request("https://en.wikipedia.org/w/api.php?$query",16*1024*1024) { bytes, retry ->
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if(json.has("error")) {
                val error=json.getJSONObject("error")
                if(error.optString("code") in setOf("maxlag","ratelimited","readonly")) {
                    cooldown(maxOf(retryAfter(retry),backoff()))
                    throw DeferredRequest(gate.cooldownUntil,error.optString("info","Wikipedia is busy."))
                }
                throw IOException(error.optString("info", "Wikipedia is temporarily unavailable."))
            }
            preferences.edit().putInt("serverFailures",0).apply()
            json
        }
    }
    fun article(title: String, revision: Long? = null): Article {
        val p = api("action" to "parse", (if(revision == null) "page" to title else "oldid" to revision.toString()),
            "redirects" to "1", "prop" to "text|revid", "disableeditsection" to "1").getJSONObject("parse")
        val links = p.optJSONArray("links")
        return Article(p.getString("title"), p.getLong("revid"), p.getString("text"),
            if(links == null) emptyList() else buildList { for(i in 0 until links.length()) {
                val l = links.getJSONObject(i); if(l.optInt("ns") == 0 && l.optBoolean("exists")) add(l.getString("title"))
            } }, Library.now())
    }
    data class RevisionBatch(val pages: List<Pair<String,Long>>, val continuation: String?, val aliases: Map<String,String>, val missing: Set<String>) {
        fun canonical(title: String): String = canonicalTitle(title,aliases)
    }
    private fun queryAliases(query: JSONObject?): Map<String,String> {
        val aliases=mutableMapOf<String,String>()
        listOf("normalized","redirects").forEach { field ->
            val values=query?.optJSONArray(field)
            if(values!=null) for(i in 0 until values.length()) {
                val alias=values.getJSONObject(i)
                aliases[alias.getString("from")]=alias.getString("to")
            }
        }
        return aliases
    }
    private fun revisionBatch(args: List<Pair<String,String>>): RevisionBatch {
        val result=api(*(args+listOf("action" to "query","prop" to "revisions","rvprop" to "ids","redirects" to "1")).toTypedArray())
        val continuation=result.optJSONObject("continue")?.toString()
        if(!result.has("batchcomplete") && continuation==null) throw IOException("Wikipedia returned an incomplete revision batch.")
        val query=result.optJSONObject("query")
        val aliases=queryAliases(query)
        val pages=query?.optJSONArray("pages")
        val missing=mutableSetOf<String>()
        val revisions=buildList {
            if(pages!=null) for(i in 0 until pages.length()) {
                val page=pages.getJSONObject(i)
                val title=page.getString("title")
                val revs=page.optJSONArray("revisions")
                if(revs!=null && revs.length()>0) add(title to revs.getJSONObject(0).getLong("revid"))
                else if(page.has("missing") || page.has("invalid")) missing.add(title)
            }
        }
        return RevisionBatch(revisions,continuation,aliases,missing)
    }
    private fun continuationArgs(continuation: String?): List<Pair<String,String>> = buildList {
        if(continuation!=null) { val c=JSONObject(continuation); c.keys().forEach { add(it to c.get(it).toString()) } }
    }
    data class LinkedTitles(val titles: List<String>, val continuation: String?, val aliases: Map<String,String>, val missing: Set<String>) {
        fun canonical(title: String): String = canonicalTitle(title,aliases)
    }
    fun linkedTitles(title: String, continuation: String?): LinkedTitles {
        // Discovery alone must not revision-check already fresh child pages.
        val args=listOf("action" to "query","generator" to "links","titles" to title,
            "gplnamespace" to "0","gpllimit" to "50","redirects" to "1")+continuationArgs(continuation)
        val result=api(*args.toTypedArray())
        val next=result.optJSONObject("continue")?.toString()
        if(!result.has("batchcomplete") && next==null) throw IOException("Wikipedia returned an incomplete link batch.")
        val query=result.optJSONObject("query")
        val pages=query?.optJSONArray("pages")
        val missing=mutableSetOf<String>()
        val titles=buildList {
            if(pages!=null) for(i in 0 until pages.length()) {
                val page=pages.getJSONObject(i)
                val name=page.getString("title")
                if(page.has("missing") || page.has("invalid")) missing.add(name)
                else add(name)
            }
        }
        return LinkedTitles(titles,next,queryAliases(query),missing)
    }
    fun knownRevisions(titles: List<String>): RevisionBatch {
        require(titles.size in 1..50)
        val pages=mutableMapOf<String,Long>()
        val aliases=mutableMapOf<String,String>()
        val missing=mutableSetOf<String>()
        val seen=mutableSetOf<String>()
        var continuation: String?=null
        do {
            val batch=revisionBatch(listOf("titles" to titles.joinToString("|"))+continuationArgs(continuation))
            pages.putAll(batch.pages); aliases.putAll(batch.aliases); missing.addAll(batch.missing)
            continuation=batch.continuation
            if(continuation!=null && !seen.add(continuation)) throw IOException("Wikipedia repeated a revision continuation.")
        } while(continuation!=null)
        return RevisionBatch(pages.toList(),null,aliases,missing)
    }
    fun search(query: String): List<SearchHit> {
        val hits = api("action" to "query", "list" to "search", "srsearch" to query, "srnamespace" to "0", "srlimit" to "30").getJSONObject("query").getJSONArray("search")
        return List(hits.length()) { val h = hits.getJSONObject(it); SearchHit(h.getString("title"), Html.fromHtml(h.optString("snippet"),0).toString(),false) }
    }
    data class Revision(val id: Long, val date: String, val comment: String)
    fun revisions(title: String, before: Long? = null): List<Revision> {
        val args = mutableListOf("action" to "query", "prop" to "revisions", "titles" to title, "rvprop" to "ids|timestamp|comment", "rvlimit" to "30")
        if(before != null) args += "rvstartid" to before.toString()
        val pages = api(*args.toTypedArray()).getJSONObject("query").getJSONArray("pages")
        val revs = pages.getJSONObject(0).optJSONArray("revisions") ?: return emptyList()
        return List(revs.length()) { val r = revs.getJSONObject(it); Revision(r.getLong("revid"), r.getString("timestamp"),r.optString("comment")) }
    }
}

/** Shared by the foreground reader and OS-managed jobs; UI work never waits for the crawl. */
class Repository private constructor(val context: Context) {
    val library = Library(context)
    val api = Wikipedia(library.preferences)
    val foreground = Executors.newFixedThreadPool(FOREGROUND_NETWORK_THREADS)
    // Cached reads never share an executor with network requests or queue writes.
    private val reads = Executors.newSingleThreadExecutor()
    private val availability = Executors.newSingleThreadExecutor()
    private val persistence = Executors.newSingleThreadExecutor()
    private val projections = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() },"KrylovProjections")
    }
    private data class ProjectionWarmup(val article: Article, val context: Long)
    private val pendingProjection=java.util.concurrent.atomic.AtomicReference<ProjectionWarmup?>()
    private val warmingProjection=AtomicBoolean(false)
    private val maintenance = Executors.newSingleThreadExecutor()
    private val historyWrites = Executors.newSingleThreadExecutor()
    fun persist(write: () -> Unit) { historyWrites.execute(write) }
    val imageExecutor = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private class Flight {
        val fetched=java.util.concurrent.CompletableFuture<Article>()
        val saved=java.util.concurrent.CompletableFuture<Unit>()
        val holdsSlot=AtomicBoolean(false)
    }
    private val loads = java.util.concurrent.ConcurrentHashMap<String,Flight>()
    // Bound retained unsaved bodies by the existing foreground + crawl capacity.
    private val fetchSlots=java.util.concurrent.Semaphore(FOREGROUND_NETWORK_THREADS+1,true)
    private val readIds=java.util.concurrent.atomic.AtomicLong()
    val cacheGeneration: Long get() = library.cacheGeneration
    fun cachedLinks(targets: List<Library.LinkTarget>, done: (Set<Library.LinkTarget>)->Unit) {
        availability.execute {
            try { done(library.cachedLinks(targets)) }
            catch(e: Exception) { android.util.Log.w("KrylovCache","link_availability_failed type=${e.javaClass.simpleName}"); done(emptySet()) }
        }
    }
    private val prefetchPriority = ThreadLocal.withInitial { 0L }
    private val prefetchDistance = ThreadLocal.withInitial { 1 }
    private val working = AtomicBoolean(false)
    @Volatile var foregroundActive = false
    @Volatile private var stopRequested = false
    @Volatile var activeRevision = -1L
    @Volatile var queueStatus = "Ready"
    data class Reading(val article: Article, val blocks: List<Block>, val cached: Boolean)
    private data class ProjectionKey(val revision: Long, val format: Int = Library.BLOCK_FORMAT_VERSION)
    private data class PreparedBlocks(val blocks: List<Block>, val stored: Boolean)
    private val blockWork=java.util.concurrent.ConcurrentHashMap<ProjectionKey,java.util.concurrent.CompletableFuture<PreparedBlocks>>()
    private val parsed = object : android.util.LruCache<ProjectionKey,Reading>(16*1024*1024) {
        override fun sizeOf(key: ProjectionKey, value: Reading) =
            ((value.article.html.length+value.blocks.sumOf { it.html.length })*2).coerceAtLeast(1)
    }
    private fun project(article: Article): PreparedBlocks {
        val key=ProjectionKey(article.revision)
        parsed.get(key)?.let { return PreparedBlocks(it.blocks,library.hasBlocks(article.revision)) }
        val promise=java.util.concurrent.CompletableFuture<PreparedBlocks>()
        val existing=blockWork.putIfAbsent(key,promise)
        if(existing!=null) return existing.get()
        try {
            val stored=library.blocks(article.revision)
            val blocks=parsed.get(key)?.blocks ?: stored ?: ArticleBlocks.parse(article.html)
            parsed.put(key,Reading(article,blocks,false))
            return PreparedBlocks(blocks,stored!=null).also { promise.complete(it) }
        } catch(e: Exception) { promise.completeExceptionally(e); throw e }
        finally { blockWork.remove(key,promise) }
    }
    private fun prepare(article: Article, cached: Boolean): Reading {
        val projection=project(article)
        return Reading(article,projection.blocks,cached).also {
            if(!projection.stored) persistence.execute {
                try {
                    library.saveBlocks(article.revision,projection.blocks)
                    maintenance.execute { library.evict(activeRevision) }
                }
                catch(e: Exception) { android.util.Log.w("KrylovCache","projection_save_failed type=${e.javaClass.simpleName}") }
            }
        }
    }
    fun read(title: String, revision: Long? = null, force: Boolean = false,
             success: (Reading)->Unit, failure: (Exception)->Unit) {
        val trace=ReadTrace(readIds.incrementAndGet())
        fun deliver(article: Article, cached: Boolean) {
            val start=android.os.SystemClock.elapsedRealtime()
            val reading=prepare(article,cached)
            trace.prepare=android.os.SystemClock.elapsedRealtime()-start
            trace.report(cached)
            success(reading)
        }
        reads.execute {
            try {
                val start=android.os.SystemClock.elapsedRealtime()
                trace.queue=start-trace.started
                val cached=if(force) null else library.cached(title,revision)
                trace.lookup=android.os.SystemClock.elapsedRealtime()-start
                if(cached!=null) {
                    deliver(cached,true)
                    maintenance.execute { library.touch(cached.revision) }
                } else {
                    val ready=loads[loadKey(title,revision)]?.fetched?.getNow(null)
                    if(ready!=null) deliver(ready,false)
                    else {
                        val queued=android.os.SystemClock.elapsedRealtime()
                        foreground.execute {
                            try {
                                trace.foregroundQueue=android.os.SystemClock.elapsedRealtime()-queued
                                val loaded=loadArticle(title,revision,force,trace)
                                reads.execute { try { deliver(loaded,false) } catch(e: Exception) { trace.failed(e); failure(e) } }
                            } catch(e: Exception) { trace.failed(e); failure(e) }
                        }
                    }
                }
            } catch(e: Exception) { trace.failed(e); failure(e) }
        }
    }
    private class ReadTrace(val id: Long) {
        val started=android.os.SystemClock.elapsedRealtime()
        var queue=0L; var lookup=0L; var foregroundQueue=0L; var prepare=0L; var network=0L; var shared=0L; var savePressure=0L
        fun report(cached: Boolean) {
            android.util.Log.i("KrylovRead","id=$id cached=$cached queue_ms=$queue lookup_ms=$lookup foreground_queue_ms=$foregroundQueue prepare_ms=$prepare network_and_gate_ms=$network shared_flight_ms=$shared save_backpressure_ms=$savePressure total_ms=${android.os.SystemClock.elapsedRealtime()-started}")
        }
        fun failed(error: Exception) {
            android.util.Log.w("KrylovRead","id=$id failed=${error.javaClass.simpleName} total_ms=${android.os.SystemClock.elapsedRealtime()-started}")
        }
    }
    private fun loadKey(title: String, revision: Long?) = "${library.canonical(title)}@${revision ?: "latest"}"
    fun load(title: String, revision: Long? = null, force: Boolean = false): Article = loadArticle(title,revision,force,null)
    private fun loadArticle(title: String, revision: Long?, force: Boolean, trace: ReadTrace?): Article {
        val priority=if(api.background.get()==true) prefetchPriority.get() else Library.now()
        if(!force) library.cached(title,revision)?.let { return it }
        val key=loadKey(title,revision)
        val flight=Flight()
        val existing=loads.putIfAbsent(key,flight)
        if(existing!=null) {
            val start=android.os.SystemClock.elapsedRealtime()
            try {
                val article=existing.fetched.get()
                if(api.background.get()==true) existing.saved.get()
                return article
            } finally { trace?.shared=android.os.SystemClock.elapsedRealtime()-start }
        }
        try {
            val admission=android.os.SystemClock.elapsedRealtime()
            fetchSlots.acquire(); flight.holdsSlot.set(true)
            trace?.savePressure=android.os.SystemClock.elapsedRealtime()-admission
            val start=android.os.SystemClock.elapsedRealtime()
            val article = api.article(title,revision)
            trace?.network=android.os.SystemClock.elapsedRealtime()-start
            val distance=if(api.background.get()==true) prefetchDistance.get()+1 else 1
            // A foreground join needs the body, not gzip/FTS completion. Background
            // workers still await saved before they can complete their queue job.
            flight.fetched.complete(article)
            persistence.execute {
                val saveStart=android.os.SystemClock.elapsedRealtime()
                try {
                    val blocks=project(article).blocks
                    library.save(article,title,revision == null, Html.fromHtml(article.html,0).toString(),blocks)
                    flight.saved.complete(Unit)
                    maintenance.execute { queueImages(article,priority,distance); library.evict(activeRevision) }
                    android.util.Log.i("KrylovCache","persist_ms=${android.os.SystemClock.elapsedRealtime()-saveStart}")
                } catch(e: Exception) {
                    flight.saved.completeExceptionally(e)
                    android.util.Log.e("KrylovCache","persist_failed type=${e.javaClass.simpleName}")
                } finally {
                    loads.remove(key,flight)
                    if(flight.holdsSlot.compareAndSet(true,false)) fetchSlots.release()
                }
            }
            if(api.background.get()==true) flight.saved.get()
            return article
        } catch(e: Exception) {
            flight.fetched.completeExceptionally(e); flight.saved.completeExceptionally(e)
            loads.remove(key,flight)
            if(flight.holdsSlot.compareAndSet(true,false)) fetchSlots.release()
            throw e
        }
    }
    private fun queueImages(article: Article, priority: Long, distance: Int) {
        ArticleBlocks.images(article.html).forEach {
            if(!library.hasImage(it.source)) library.enqueue(it.source,-1,priority,distance)
        }
    }
    @Volatile private var readingContext=0L
    private fun warmProjections() {
        if(!warmingProjection.compareAndSet(false,true)) return
        projections.execute {
            try {
                while(true) {
                    val request=pendingProjection.getAndSet(null) ?: break
                    if(request.context!=readingContext) continue
                    val titles=ArticleBlocks.articleLinks(request.article.html)
                    val revisions=library.missingBlockProjections(titles)
                    var wrote=false
                    for(revision in revisions) {
                        if(request.context!=readingContext) break
                        if(library.hasBlocks(revision)) continue
                        val memory=parsed.get(ProjectionKey(revision))
                        val article=memory?.article ?: library.cached("",revision) ?: continue
                        val blocks=memory?.blocks ?: project(article).blocks
                        if(request.context!=readingContext) break
                        library.saveBlocks(revision,blocks)
                        wrote=true
                    }
                    if(wrote) library.evict(activeRevision)
                }
            } catch(e: Exception) { android.util.Log.w("KrylovCache","projection_warmup_failed type=${e.javaClass.simpleName}") }
            finally {
                warmingProjection.set(false)
                if(pendingProjection.get()!=null) warmProjections()
            }
        }
    }
    fun explore(article: Article) {
        val context=Library.now()
        readingContext=context
        pendingProjection.set(ProjectionWarmup(article,context)); warmProjections()
        maintenance.execute {
            if(readingContext!=context) return@execute
            queueImages(article,context,1)
            if(library.preferences.getBoolean("prefetch",true)) {
                // Promote this neighborhood from saved HTML even if generator
                // discovery is still fresh. Returning to an article needs no API
                // request merely to reprioritize its existing links.
                library.enqueueLinks(ArticleBlocks.articleLinks(article.html),context,1)
                library.expanded(article.title)
            }
            schedule(); pump()
        }
    }
    fun schedule() {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        val network=if(library.preferences.getBoolean("wifiOnly",true)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        if(scheduler.getPendingJob(41)?.networkType==network) return
        val job = JobInfo.Builder(41,ComponentName(context,FetchJob::class.java))
            .setRequiredNetworkType(network)
            .setPersisted(true).setPeriodic(15*60*1000L).setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        scheduler.schedule(job)
    }
    fun networkAllowed(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!library.preferences.getBoolean("wifiOnly",true) || !cm.isActiveNetworkMetered)
    }
    fun pump(done: () -> Unit = {}) {
        if(!working.compareAndSet(false,true)) { done(); return }
        stopRequested=false
        worker.execute {
            api.background.set(true)
            try {
                val deadline = Library.now() + 4*60_000
                while((foregroundActive || Library.now() < deadline) && !stopRequested && library.preferences.getBoolean("prefetch",true) && !library.preferences.getBoolean("paused",false) && networkAllowed()) {
                    val p = library.nextFair() ?: break
                    prefetchPriority.set(p.priority); prefetchDistance.set(p.distance)
                    queueStatus = p.title
                    try {
                        if(p.depth < 0) image(p.title)
                        else if(p.depth == 1) {
                            val cached=library.cached(p.title)
                            if(cached!=null) {
                                library.enqueueLinks(ArticleBlocks.articleLinks(cached.html),p.priority,1)
                                library.expanded(p.title); library.complete(p.title)
                                continue
                            }
                            val batch=api.linkedTitles(p.title,p.cursor)
                            batch.aliases.forEach { (alias,_) -> library.alias(alias,batch.canonical(alias)) }
                            library.enqueueLinks(batch.titles.filter { it!=batch.canonical(p.title) },p.priority,1)
                            if(batch.continuation!=null) {
                                if(batch.continuation==p.cursor) throw IOException("Wikipedia repeated a link continuation.")
                                library.continuation(p.title,batch.continuation); continue
                            }
                            library.expanded(p.title)
                        } else if(!library.fresh(p.title)) {
                            if(p.expected==0L) {
                                val titles=library.uncheckedBatch()
                                if(titles.isEmpty()) { library.completeDownload(p.title); continue }
                                val batch=api.knownRevisions(titles)
                                batch.aliases.forEach { (alias,_) -> library.alias(alias,batch.canonical(alias)) }
                                val revisions=batch.pages.toMap()
                                titles.forEach { requested ->
                                    val title=batch.canonical(requested)
                                    val revision=revisions[title]
                                    when {
                                        title in batch.missing -> library.completeDownload(requested)
                                        revision!=null -> {
                                            if(library.fresh(title) || library.verified(title,revision)) library.completeDownload(title)
                                            else { library.enqueue(title,0,library.queuePriority(requested),library.queueDistance(requested)); library.expected(title,revision) }
                                            if(requested!=title) library.completeDownload(requested)
                                        }
                                        else -> throw IOException("Wikipedia omitted revision metadata for $requested.")
                                    }
                                }
                                // Re-read expected IDs and canonicalized jobs instead of parsing
                                // with the stale pre-batch Pending value.
                                continue
                            }
                            if(!library.fresh(p.title) && !library.verified(p.title,p.expected)) load(p.title,force=true)
                        }
                        if(p.depth==0) library.completeDownload(p.title) else library.complete(p.title)
                    } catch(e: DeferredRequest) {
                        library.defer(p.title,e.until,e.message.orEmpty()); break
                    } catch(e: Exception) { library.failed(p,e.message ?: "Network unavailable") }
                }
            } finally {
                api.background.remove(); prefetchPriority.remove(); prefetchDistance.remove(); queueStatus = "Waiting"; working.set(false); done()
                if(foregroundActive && !stopRequested && library.preferences.getBoolean("prefetch",true) && !library.preferences.getBoolean("paused",false) && networkAllowed()) {
                    library.nextReady()?.let { ready -> worker.schedule({ pump() },(ready-Library.now()).coerceAtLeast(1000),java.util.concurrent.TimeUnit.MILLISECONDS) }
                }
            }
        }
    }
    fun image(source: String): File {
        val url=ArticleBlocks.imageUrl(source) ?: throw IOException("Unsupported image URL")
        val key=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached=File(library.images,key)
        if(cached.isFile) {
            maintenance.execute { library.writableDatabase.execSQL("UPDATE images SET accessed=? WHERE url=?",arrayOf<Any>(Library.now(),url)) }
            return cached
        }
        return downloadImage(url)
    }
    @Synchronized private fun downloadImage(source: String): File {
        val url=ArticleBlocks.imageUrl(source) ?: throw IOException("Unsupported image URL")
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(library.images,key)
        if(!file.exists()) {
            // Even visible images yield admission to article opens and searches.
            val previousBackground=api.background.get()
            val bytes=try { api.background.set(true); api.bytes(url,8*1024*1024) }
                finally { api.background.set(previousBackground) }
            val temp = File(library.images,"$key.part")
            try { temp.writeBytes(bytes); if(!temp.renameTo(file)) throw IOException("Cannot save image") } finally { temp.delete() }
        }
        library.writableDatabase.execSQL("INSERT OR IGNORE INTO images VALUES (?,?,?,?)", arrayOf<Any>(url,key,file.length(),Library.now()))
        library.writableDatabase.execSQL("UPDATE images SET bytes=?,accessed=? WHERE url=?",arrayOf<Any>(file.length(),Library.now(),url))
        library.evict(activeRevision)
        return file
    }
    fun bitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path,bounds)
        val sample = BitmapFactory.Options().apply { inSampleSize = 1; while(bounds.outWidth / inSampleSize > 1400 || bounds.outHeight / inSampleSize > 1800) inSampleSize *= 2 }
        return BitmapFactory.decodeFile(file.path,sample)
    }
    fun stopPump() { stopRequested=true }
    companion object {
        private const val FOREGROUND_NETWORK_THREADS=2
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository = instance ?: synchronized(this) { instance ?: Repository(context.applicationContext).also { instance = it } }
    }
}
class FetchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Repository.get(this).pump { jobFinished(params,false) }; return true
    }
    override fun onStopJob(params: JobParameters): Boolean { Repository.get(this).stopPump(); return true }
}
