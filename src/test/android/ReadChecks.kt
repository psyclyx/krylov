package dev.psyclyx.krylov.tests

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import dev.psyclyx.krylov.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ReadChecks {
    fun run(target: Context, report: StringBuilder) {
        val context=object : ContextWrapper(target) {
            override fun getDatabasePath(name: String)=target.getDatabasePath("read_checks_$name").apply { parentFile?.mkdirs() }
            override fun deleteDatabase(name: String)=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
            override fun getSharedPreferences(name: String,mode: Int)=target.getSharedPreferences("read_checks_$name",mode)
            override fun getFilesDir()=File(target.filesDir,"read_checks").apply { mkdirs() }
        }
        context.deleteDatabase("library.db")
        context.getSharedPreferences("reader",0).edit().clear()
            .putLong("cooldownUntil",Library.now()+600_000).putBoolean("prefetch",false).commit()
        val ctor=Repository::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible=true }
        val repo=ctor.newInstance(context)
        val release=CountDownLatch(1)
        try {
            val html=buildString {
                repeat(200) { append("<h2 id='s$it'>Section $it</h2><p>Cached paragraph with <a href='/wiki/Other'>a link</a>.</p><img src='//upload.wikimedia.org/test/$it.jpg' width='200' height='150'>") }
            }
            val article=Article("Read check",123456789,html,emptyList(),Library.now())
            repo.library.save(article,article.title,true,"Cached paragraph")
            val occupied=CountDownLatch(3)
            repeat(2) { repo.foreground.execute { occupied.countDown(); runCatching { release.await(10,TimeUnit.SECONDS) } } }
            val writer=Thread { synchronized(repo.library) {
                val db=repo.library.writableDatabase
                db.beginTransactionNonExclusive()
                try { occupied.countDown(); runCatching { release.await(10,TimeUnit.SECONDS) }; db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            } }.apply { start() }
            check(occupied.await(2,TimeUnit.SECONDS))
            val visit=repo.library.allocateVisit(article,0)
            val historySaved=CountDownLatch(1)
            repo.persist { repo.library.recordVisit(visit) }
            repo.persist { repo.library.position(visit.id,7,-12); historySaved.countDown() }
            val times=mutableListOf<Double>()
            var expected:List<Block>?=null
            repeat(11) {
                val done=CountDownLatch(1)
                var result:Repository.Reading?=null
                var error:Exception?=null
                val start=System.nanoTime()
                repo.read(article.title,success={ result=it; done.countDown() },failure={ error=it; done.countDown() })
                check(done.await(2,TimeUnit.SECONDS)) { "Cached read waited behind network workers or writer monitor" }
                times+=(System.nanoTime()-start)/1e6
                check(error==null) { error.toString() }
                check(result!!.cached && result!!.article.html==html)
                check(expected==null || expected==result!!.blocks)
                expected=result!!.blocks
            }
            check(repo.library.queueCount()==0L) { "Reading queued image writes before display" }
            report.append("PASS cached reads during occupied network workers, locked writer monitor and API cooldown; no image queue writes\n")
            val warm=times.drop(1).sorted()
            report.append("Cached read fixture: ${html.length} chars, first ${times.first()} ms, warm median ${warm[5]} ms, best ${warm.first()} ms (emulator smoke timings)\n")
            release.countDown(); writer.join()
            check(historySaved.await(2,TimeUnit.SECONDS))
            check(repo.library.getVisit(visit.id)?.let { it.scroll==7 && it.offset== -12 }==true)
            report.append("PASS history insertion and position updates retain order across a blocked SQLite writer\n")
        } finally {
            release.countDown()
            repo.foreground.shutdown(); repo.foreground.awaitTermination(2,TimeUnit.SECONDS); repo.imageExecutor.shutdown()
            for(name in listOf("reads","maintenance","worker","historyWrites","availability","persistence","projections")) {
                val field=Repository::class.java.getDeclaredField(name).apply { isAccessible=true }
                (field.get(repo) as java.util.concurrent.ExecutorService).let { it.shutdown(); it.awaitTermination(2,TimeUnit.SECONDS) }
            }
            repo.library.close()
            context.deleteDatabase("library.db")
        }
    }
}
