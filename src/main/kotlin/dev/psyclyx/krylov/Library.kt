package dev.psyclyx.krylov

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class Library(private val context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    private val connection: SQLiteDatabase by lazy(this) { writableDatabase }
    private val cacheChanges=java.util.concurrent.atomic.AtomicLong()
    val cacheGeneration: Long get() = cacheChanges.get()
    val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    val images = File(context.filesDir, "images").apply { mkdirs() }
    val staleMillis get() = preferences.getInt("freshDays", 7) * 86_400_000L
    val limitBytes get() = preferences.getInt("cacheGiB", 16) * 1024L * 1024 * 1024
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
        db.execSQL("CREATE TABLE articles(title TEXT PRIMARY KEY, latest INTEGER, checked INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE revisions(id INTEGER PRIMARY KEY, title TEXT NOT NULL, html BLOB NOT NULL, links TEXT NOT NULL, fetched INTEGER NOT NULL, accessed INTEGER NOT NULL, bytes INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX revisions_lru ON revisions(accessed)")
        db.execSQL("CREATE INDEX revisions_title ON revisions(title, id DESC)")
        createBlockCache(db)
        db.execSQL("CREATE VIRTUAL TABLE search USING fts4(title, body, tokenize=unicode61)")
        db.execSQL("CREATE TABLE aliases(alias TEXT PRIMARY KEY, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE visits(id INTEGER PRIMARY KEY, title TEXT NOT NULL, revision INTEGER NOT NULL, time INTEGER NOT NULL, parent INTEGER, scroll INTEGER NOT NULL DEFAULT 0, offset INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX visits_time ON visits(time DESC, id DESC)")
        db.execSQL("CREATE INDEX visits_title ON visits(title, time DESC)")
        db.execSQL("CREATE TABLE categories(name TEXT PRIMARY KEY, color INTEGER NOT NULL)")
        db.execSQL("INSERT INTO categories VALUES ('Discuss', -4443340), ('Follow up', -12615602), ('Keep', -3631747)")
        db.execSQL("CREATE TABLE marks(id INTEGER PRIMARY KEY, title TEXT NOT NULL, revision INTEGER NOT NULL, block INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, quote TEXT NOT NULL, note TEXT NOT NULL, category TEXT NOT NULL REFERENCES categories(name), time INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX marks_stack ON marks(category, time DESC, id DESC)")
        db.execSQL("CREATE INDEX marks_article ON marks(revision, block)")
        db.execSQL("CREATE TABLE queue(title TEXT PRIMARY KEY, depth INTEGER NOT NULL, priority INTEGER NOT NULL, added INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, ready INTEGER NOT NULL DEFAULT 0, error TEXT, cursor TEXT, expected INTEGER NOT NULL DEFAULT 0, distance INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE expanded(title TEXT PRIMARY KEY, checked INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX queue_next ON queue(ready, priority DESC, added)")
        db.execSQL("CREATE INDEX queue_rank ON queue(priority DESC,distance,depth,added) WHERE attempts<6")
        db.execSQL("CREATE TABLE images(url TEXT PRIMARY KEY, file TEXT NOT NULL, bytes INTEGER NOT NULL, accessed INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX images_lru ON images(accessed)")
        db.execSQL("CREATE TABLE cache_totals(id INTEGER PRIMARY KEY CHECK(id=1), image_bytes INTEGER NOT NULL)")
        db.execSQL("INSERT INTO cache_totals VALUES(1,0)")
        db.execSQL("CREATE TRIGGER image_insert AFTER INSERT ON images BEGIN UPDATE cache_totals SET image_bytes=image_bytes+new.bytes WHERE id=1; END")
        db.execSQL("CREATE TRIGGER image_delete AFTER DELETE ON images BEGIN UPDATE cache_totals SET image_bytes=image_bytes-old.bytes WHERE id=1; END")
        db.execSQL("CREATE TRIGGER image_update AFTER UPDATE OF bytes ON images BEGIN UPDATE cache_totals SET image_bytes=image_bytes+new.bytes-old.bytes WHERE id=1; END")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) {
            db.execSQL("ALTER TABLE queue ADD COLUMN distance INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX queue_rank ON queue(priority DESC,distance,depth,added) WHERE attempts<6")
        }
        if(oldVersion<3) createBlockCache(db)
    }
    private fun createBlockCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE revision_blocks(revision INTEGER PRIMARY KEY REFERENCES revisions(id) ON DELETE CASCADE, formatversion INTEGER NOT NULL, data BLOB NOT NULL)")
    }
    init { setWriteAheadLoggingEnabled(true) }
    fun canonical(title: String): String = connection.rawQuery("SELECT title FROM aliases WHERE alias=?", arrayOf(title)).use { if (it.moveToFirst()) it.getString(0) else title }
    fun alias(alias: String, title: String) {
        connection.execSQL("INSERT OR REPLACE INTO aliases VALUES (?,?)",arrayOf(alias,title))
        cacheChanges.incrementAndGet()
    }
    data class LinkTarget(val title: String, val revision: Long? = null)
    /** Indexed availability only: no HTML decompression or full-library scan. */
    fun cachedLinks(targets: List<LinkTarget>): Set<LinkTarget> {
        val found=mutableSetOf<LinkTarget>()
        // Three bindings per row stay below SQLite's 999-variable baseline.
        targets.distinct().chunked(300).forEach { batch ->
            val args=ArrayList<String>(batch.size*3)
            batch.forEachIndexed { index,target ->
                args+=index.toString(); args+=target.title; args+=(target.revision ?: -1L).toString()
            }
            val values=batch.joinToString(",") { "(?,?,?)" }
            val sql="WITH wanted(n,title,revision) AS (VALUES $values) SELECT w.n FROM wanted w LEFT JOIN aliases x ON x.alias=w.title LEFT JOIN aliases nx ON nx.alias=replace(w.title,'_',' ') LEFT JOIN articles a ON a.title=coalesce(x.title,nx.title,replace(w.title,'_',' ')) JOIN revisions r ON r.id=CASE WHEN CAST(w.revision AS INTEGER)<0 THEN a.latest ELSE CAST(w.revision AS INTEGER) END"
            connection.rawQuery(sql,args.toTypedArray()).use { cursor ->
                while(cursor.moveToNext()) found+=batch[cursor.getInt(0)]
            }
        }
        return found
    }
    fun completeDownload(title: String) { connection.delete("queue","title=? AND depth=0",arrayOf(title)) }
    fun cached(title: String, revision: Long? = null): Article? {
        val canonical = canonical(title)
        val sql = if (revision == null) "SELECT r.* FROM revisions r JOIN articles a ON a.latest=r.id WHERE a.title=?" else "SELECT * FROM revisions WHERE id=?"
        return connection.rawQuery(sql, arrayOf(revision?.toString() ?: canonical)).use { c ->
            if (!c.moveToFirst()) null else {
                val id = c.getLong(0)
                Article(c.getString(1), id, GZIPInputStream(c.getBlob(2).inputStream()).bufferedReader().use { it.readText() },
                    JSONArray(c.getString(3)).let { a -> List(a.length()) { a.getString(it) } }, c.getLong(4))
            }
        }
    }
    fun touch(revision: Long) { connection.execSQL("UPDATE revisions SET accessed=? WHERE id=?",arrayOf<Any>(now(),revision)) }
    fun hasBlocks(revision: Long): Boolean = connection.rawQuery(
        "SELECT 1 FROM revision_blocks WHERE revision=? AND formatversion=?",arrayOf(revision.toString(),BLOCK_FORMAT_VERSION.toString())
    ).use { it.moveToFirst() }
    fun missingBlockProjections(titles: List<String>): List<Long> {
        val revisions=linkedSetOf<Long>()
        titles.distinct().chunked(300).forEach { batch ->
            val values=batch.joinToString(",") { "(?)" }
            val sql="WITH wanted(title) AS (VALUES $values) SELECT r.id FROM wanted w LEFT JOIN aliases x ON x.alias=w.title LEFT JOIN aliases nx ON nx.alias=replace(w.title,'_',' ') JOIN articles a ON a.title=coalesce(x.title,nx.title,replace(w.title,'_',' ')) JOIN revisions r ON r.id=a.latest LEFT JOIN revision_blocks b ON b.revision=r.id AND b.formatversion=? WHERE b.revision IS NULL"
            connection.rawQuery(sql,(batch+BLOCK_FORMAT_VERSION.toString()).toTypedArray()).use {
                while(it.moveToNext()) revisions+=it.getLong(0)
            }
        }
        return revisions.toList()
    }
    /** Format version changes whenever parser block boundaries or anchors change. */
    fun blocks(revision: Long): List<Block>? = connection.rawQuery(
        "SELECT data FROM revision_blocks WHERE revision=? AND formatversion=?",arrayOf(revision.toString(),BLOCK_FORMAT_VERSION.toString())
    ).use { cursor ->
        if(!cursor.moveToFirst()) null else try {
            java.io.DataInputStream(GZIPInputStream(cursor.getBlob(0).inputStream())).use { input ->
                var remaining=MAX_BLOCK_BYTES-4
                val count=input.readInt()
                require(count>=0 && count<=remaining/16)
                fun field(): String {
                    val size=input.readInt(); remaining-=4
                    require(size>=0 && size<=remaining)
                    val bytes=ByteArray(size); input.readFully(bytes); remaining-=size
                    return String(bytes,Charsets.UTF_8)
                }
                List(count) { Block(field(),field(),field(),field()) }.also { require(input.read()==-1) }
            }
        } catch(e: java.io.IOException) { null } catch(e: IllegalArgumentException) { null }
    }
    private fun encodeBlocks(blocks: List<Block>): ByteArray {
        val out=ByteArrayOutputStream()
        java.io.DataOutputStream(GZIPOutputStream(out)).use { output ->
            var remaining=MAX_BLOCK_BYTES-4
            require(blocks.size<=remaining/16)
            output.writeInt(blocks.size)
            fun field(value: String) {
                val bytes=value.toByteArray(Charsets.UTF_8)
                require(bytes.size<=remaining-4)
                remaining-=bytes.size+4
                output.writeInt(bytes.size); output.write(bytes)
            }
            blocks.forEach { field(it.kind); field(it.html); field(it.anchor); field(it.source) }
        }
        return out.toByteArray()
    }
    fun saveBlocks(revision: Long, blocks: List<Block>) {
        val data=encodeBlocks(blocks)
        // Parsing may finish after eviction; never resurrect an evicted revision.
        connection.execSQL("INSERT OR REPLACE INTO revision_blocks(revision,formatversion,data) SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM revisions WHERE id=?)",
            arrayOf<Any>(revision,BLOCK_FORMAT_VERSION,data,revision))
    }
    fun fresh(title: String): Boolean = connection.rawQuery("SELECT checked FROM articles a JOIN revisions r ON r.id=a.latest WHERE a.title=?", arrayOf(canonical(title))).use {
        it.moveToFirst() && now() - it.getLong(0) < staleMillis
    }
    @Synchronized fun save(article: Article, requested: String, latest: Boolean, plain: String, blocks: List<Block>? = null) {
        val db = connection
        val out = ByteArrayOutputStream(); GZIPOutputStream(out).use { it.write(article.html.toByteArray()) }
        val blob = out.toByteArray()
        val projection=blocks?.let { encodeBlocks(it) }
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO revisions VALUES (?,?,?,?,?,?,?)", arrayOf<Any>(article.revision, article.title, blob, JSONArray(article.links).toString(), article.fetched, now(), blob.size))
            db.execSQL("INSERT OR REPLACE INTO aliases VALUES (?,?)", arrayOf<Any>(requested, article.title))
            if (latest) {
                db.execSQL("INSERT OR REPLACE INTO articles VALUES (?,?,?)", arrayOf<Any>(article.title, article.revision, now()))
                db.execSQL("DELETE FROM search WHERE title=?", arrayOf<Any>(article.title))
                db.execSQL("INSERT INTO search(title,body) VALUES (?,?)", arrayOf<Any>(article.title, plain))
            }
            if(projection!=null) db.execSQL("INSERT INTO revision_blocks(revision,formatversion,data) VALUES (?,?,?)",
                arrayOf<Any>(article.revision,BLOCK_FORMAT_VERSION,projection))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        cacheChanges.incrementAndGet()
    }
    fun search(query: String): List<SearchHit> {
        val terms = QueryTerms.fts(query); if (terms.isBlank()) return emptyList()
        return connection.rawQuery("SELECT title,snippet(search,'','',' … ',1,28) FROM search WHERE search MATCH ? ORDER BY CASE WHEN title=? COLLATE NOCASE THEN 0 WHEN title LIKE ? THEN 1 ELSE 2 END LIMIT 40", arrayOf(terms, query, "$query%")).use { c ->
            buildList { while(c.moveToNext()) add(SearchHit(c.getString(0), c.getString(1), true)) }
        }
    }
    private val visitIds by lazy { java.util.concurrent.atomic.AtomicLong(scalar("SELECT coalesce(max(id),0) FROM visits")) }
    fun allocateVisit(article: Article, parent: Long) = Visit(visitIds.incrementAndGet(),article.title,article.revision,now(),parent,0)
    fun recordVisit(visit: Visit) {
        connection.insertOrThrow("visits",null,ContentValues().apply {
            put("id",visit.id); put("title",visit.title); put("revision",visit.revision); put("time",visit.time)
            if(visit.parent>0) put("parent",visit.parent)
            put("scroll",visit.scroll); put("offset",visit.offset)
        })
    }
    fun visit(article: Article, parent: Long): Long = allocateVisit(article,parent).also { recordVisit(it) }.id
    fun position(visit: Long, scroll: Int, offset: Int = 0) { connection.execSQL("UPDATE visits SET scroll=?,offset=? WHERE id=?", arrayOf<Any>(scroll,offset,visit)) }
    fun visits(before: Long = Long.MAX_VALUE, query: String = "", limit: Int = 100): List<Visit> = connection.rawQuery(
        "SELECT id,title,revision,time,coalesce(parent,0),scroll,offset FROM visits WHERE id<? AND title LIKE ? ORDER BY id DESC LIMIT ?", arrayOf(before.toString(), "%$query%", limit.toString())).use { c ->
        buildList { while(c.moveToNext()) add(Visit(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getInt(5),c.getInt(6))) }
    }
    fun getVisit(id: Long): Visit? = connection.rawQuery("SELECT id,title,revision,time,coalesce(parent,0),scroll,offset FROM visits WHERE id=?", arrayOf(id.toString())).use { c ->
        if(c.moveToFirst()) Visit(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getInt(5),c.getInt(6)) else null
    }
    fun categories(): List<Pair<String,Int>> = connection.rawQuery("SELECT name,color FROM categories ORDER BY rowid", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
    fun category(name: String, color: Int) { connection.execSQL("INSERT OR IGNORE INTO categories VALUES (?,?)", arrayOf<Any>(name, color)) }
    @Synchronized fun mark(a: Article, block: Int, start: Int, end: Int, quote: String, note: String, category: String): Long {
        return connection.insertOrThrow("marks",null,ContentValues().apply {
            put("title",a.title); put("revision",a.revision); put("block",block); put("start",start); put("end",end)
            put("quote",quote); put("note",note); put("category",category); put("time",now())
        })
    }
    fun editMark(id: Long, category: String, note: String) { connection.execSQL("UPDATE marks SET category=?,note=? WHERE id=?",arrayOf<Any>(category,note,id)) }
    fun marks(category: String? = null, revision: Long? = null, before: Long = Long.MAX_VALUE): List<Mark> {
        val predicates=mutableListOf("m.id<?"); val args=mutableListOf(before.toString())
        if(category!=null) { predicates+="category=?"; args+=category }
        if(revision!=null) { predicates+="revision=?"; args+=revision.toString() }
        return connection.rawQuery(
        "SELECT m.id,title,revision,block,start,end,quote,note,category,color,time FROM marks m JOIN categories c ON c.name=m.category WHERE ${predicates.joinToString(" AND ")} ORDER BY m.id DESC LIMIT ${if(revision==null) 100 else 10000}",
        args.toTypedArray()).use { c -> buildList {
            while(c.moveToNext()) add(Mark(c.getLong(0),c.getString(1),c.getLong(2),c.getInt(3),c.getInt(4),c.getInt(5),c.getString(6),c.getString(7),c.getString(8),c.getInt(9),c.getLong(10)))
        } }
    }
    fun deleteMark(id: Long) { connection.delete("marks", "id=?", arrayOf(id.toString())) }
    @Synchronized fun enqueue(title: String, depth: Int, priority: Long = 0, distance: Int = 1) {
        enqueueCanonical(canonical(title),depth,priority,distance)
    }
    private fun enqueueCanonical(key: String, depth: Int, priority: Long, distance: Int) {
        require(distance>=0)
        connection.execSQL("INSERT OR IGNORE INTO queue(title,depth,priority,added,distance) VALUES (?,?,?,?,?)", arrayOf<Any>(key,depth,priority,now(),distance))
        connection.execSQL("UPDATE queue SET depth=max(depth,?),distance=CASE WHEN ?>priority THEN ? WHEN ?=priority THEN min(distance,?) ELSE distance END,priority=max(priority,?) WHERE title=?",
            arrayOf<Any>(depth,priority,distance,priority,distance,priority,key))
    }
    @Synchronized fun enqueueLinks(titles: List<String>, priority: Long, distance: Int = 1) {
        require(distance>=0)
        val db=connection
        val cutoff=now()-staleMillis
        db.beginTransaction()
        try {
            val seen=mutableSetOf<String>()
            titles.forEach { title ->
                val key=canonical(title)
                if(seen.add(key)) {
                    val fresh=db.rawQuery("SELECT 1 FROM articles a JOIN revisions r ON r.id=a.latest WHERE a.title=? AND a.checked>?",arrayOf(key,cutoff.toString())).use { it.moveToFirst() }
                    if(!fresh) enqueueCanonical(key,0,priority,distance)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    data class Pending(val title: String, val depth: Int, val attempts: Int, val cursor: String?, val expected: Long, val priority: Long = 0, val distance: Int = 1)
    private val queueKinds = intArrayOf(-1,1,0)
    private var nextKind = 0
    // Reading context and distance outrank kind fairness: old images must not
    // delay the current article's links. Rotate only among equally ranked jobs.
    @Synchronized fun nextFair(): Pending? {
        val rank=connection.rawQuery("SELECT priority,distance FROM queue WHERE ready<=? AND attempts<6 ORDER BY priority DESC,distance LIMIT 1",arrayOf(now().toString())).use {
            if(it.moveToFirst()) it.getLong(0) to it.getInt(1) else null
        } ?: return null
        repeat(queueKinds.size) {
            val kind=queueKinds[nextKind]
            nextKind=(nextKind+1)%queueKinds.size
            nextAt(kind,rank.first,rank.second)?.let { return it }
        }
        return null
    }
    fun next(depth: Int? = null): Pending? = nextAt(depth)
    private fun nextAt(depth: Int?, priority: Long? = null, distance: Int? = null): Pending? {
        val args=mutableListOf(now().toString())
        var filter=if(depth==null) "" else { args+=depth.toString(); " AND depth=?" }
        if(priority!=null) { args+=priority.toString(); filter+=" AND priority=?" }
        if(distance!=null) { args+=distance.toString(); filter+=" AND distance=?" }
        return connection.rawQuery("SELECT title,depth,attempts,cursor,expected,priority,distance FROM queue WHERE ready<=? AND attempts<6$filter ORDER BY priority DESC,distance,added LIMIT 1", args.toTypedArray()).use {
            if(it.moveToFirst()) Pending(it.getString(0),it.getInt(1),it.getInt(2),it.getString(3),it.getLong(4),it.getLong(5),it.getInt(6)) else null
        }
    }
    fun recentlyExpanded(title: String) = connection.rawQuery("SELECT checked FROM expanded WHERE title=?",arrayOf(title)).use { it.moveToFirst() && now()-it.getLong(0)<staleMillis }
    fun expanded(title: String) { connection.execSQL("INSERT OR REPLACE INTO expanded VALUES (?,?)",arrayOf<Any>(title,now())) }
    fun continuation(title: String, cursor: String) { connection.execSQL("UPDATE queue SET cursor=? WHERE title=?",arrayOf(cursor,title)) }
    fun verified(title: String, revision: Long): Boolean {
        val present=connection.rawQuery("SELECT 1 FROM articles a JOIN revisions r ON r.id=a.latest WHERE a.title=? AND a.latest=?",arrayOf(title,revision.toString())).use { it.moveToFirst() }
        if(present) connection.execSQL("UPDATE articles SET checked=? WHERE title=?",arrayOf<Any>(now(),title))
        return present
    }
    fun expected(title: String, revision: Long) { connection.execSQL("UPDATE queue SET expected=? WHERE title=?",arrayOf<Any>(revision,title)) }
    fun queuePriority(title: String): Long = connection.rawQuery("SELECT priority FROM queue WHERE title=?",arrayOf(title)).use {
        if(it.moveToFirst()) it.getLong(0) else 0L
    }
    fun queueDistance(title: String): Int = connection.rawQuery("SELECT distance FROM queue WHERE title=?",arrayOf(title)).use {
        if(it.moveToFirst()) it.getInt(0) else 1
    }
    fun uncheckedBatch(): List<String> = connection.rawQuery(
        "SELECT q.title FROM queue q WHERE depth=0 AND expected=0 AND ready<=? AND attempts<6 AND NOT EXISTS(SELECT 1 FROM articles a JOIN revisions r ON r.id=a.latest WHERE a.title=coalesce((SELECT title FROM aliases WHERE alias=q.title),q.title) AND a.checked>?) ORDER BY priority DESC,distance,added LIMIT 50",
        arrayOf(now().toString(),(now()-staleMillis).toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun hasImage(url: String): Boolean = connection.rawQuery("SELECT file FROM images WHERE url=?",arrayOf(url)).use {
        it.moveToFirst() && File(images,it.getString(0)).isFile
    }
    fun complete(title: String) { connection.delete("queue", "title=?", arrayOf(title)) }
    fun defer(title: String, until: Long, reason: String) { connection.execSQL("UPDATE queue SET ready=?,error=? WHERE title=?",arrayOf<Any>(until,reason,title)) }
    fun failed(p: Pending, message: String) { connection.execSQL("UPDATE queue SET attempts=attempts+1,ready=?,error=? WHERE title=?", arrayOf<Any>(now() + (60_000L shl p.attempts.coerceAtMost(8)), message.take(240), p.title)) }
    fun retryQueue() { connection.execSQL("UPDATE queue SET attempts=0,ready=0,error=NULL") }
    fun scalar(sql: String): Long = connection.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
    fun queueCount() = scalar("SELECT count(*) FROM queue")
    fun cacheBytes(): Long = context.getDatabasePath("library.db").length() +
        File(context.getDatabasePath("library.db").path + "-wal").length() +
        File(context.getDatabasePath("library.db").path + "-shm").length() + scalar("SELECT image_bytes FROM cache_totals WHERE id=1")
    fun nextReady(): Long? = connection.rawQuery("SELECT min(ready) FROM queue WHERE attempts<6",null).use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    @Synchronized fun evict(protectedRevision: Long = -1) {
        // Eviction never deletes reading history, marks, categories or quote anchors.
        var usage = cacheBytes()
        if (usage <= limitBytes) return
        val target = limitBytes * 9 / 10
        while (usage > target) {
            val candidate = connection.rawQuery("SELECT id,title FROM revisions WHERE id<>? ORDER BY accessed LIMIT 1", arrayOf(protectedRevision.toString())).use {
                if(it.moveToFirst()) it.getLong(0) to it.getString(1) else null
            }
            val image = connection.rawQuery("SELECT url,file FROM images ORDER BY accessed LIMIT 1",null).use {
                if(it.moveToFirst()) it.getString(0) to it.getString(1) else null
            }
            if(image != null) { File(images,image.second).delete(); connection.delete("images","url=?",arrayOf(image.first)) }
            else if(candidate != null) {
                connection.beginTransaction()
                try {
                    connection.execSQL("DELETE FROM search WHERE title=? AND EXISTS(SELECT 1 FROM articles WHERE title=? AND latest=?)",arrayOf<Any>(candidate.second,candidate.second,candidate.first))
                    connection.execSQL("DELETE FROM revisions WHERE id=?",arrayOf<Any>(candidate.first))
                    connection.setTransactionSuccessful()
                } finally { connection.endTransaction() }
                cacheChanges.incrementAndGet()
            } else break
            connection.rawQuery("PRAGMA incremental_vacuum(1024)",null).use { while(it.moveToNext()) { /* Step all reclaimed pages. */ } }
            val reclaimed=connection.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { it.moveToFirst() && it.getInt(0)==0 }
            val after=cacheBytes()
            if(!reclaimed) break // Retry later; a reader may be holding the WAL open.
            usage = after
        }
    }
    companion object {
        const val BLOCK_FORMAT_VERSION=1
        private const val MAX_BLOCK_BYTES=64*1024*1024
        fun now() = System.currentTimeMillis()
    }
}
