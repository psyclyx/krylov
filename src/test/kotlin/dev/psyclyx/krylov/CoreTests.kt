package dev.psyclyx.krylov

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    val blocks=ArticleBlocks.parse("""<div class="mw-parser-output"><p>Lead <a href="/wiki/Reading">link</a>.</p><div class="mw-heading"><h2 id="History">History<span class="mw-editsection">edit</span></h2></div><p>Some <b>bold</b> text.</p><table><tr><th>Name</th><td>Value</td></tr></table><figure><img src="//upload.wikimedia.org/wikipedia/a/a1/Test.png" alt="A test"><figcaption>Caption</figcaption></figure><script>bad()</script><div class="navbox"><div>Skip this</div></div><h3 id="End">End</h3><p>Last paragraph</p></div>""")
    check(blocks.first().html.contains("href="))
    check(blocks.any { it.anchor=="History" && it.kind=="h2" && !it.html.contains("edit") })
    check(blocks.any { it.kind=="table" && it.html.contains("Name") && it.html.contains("Value") })
    check(blocks.single { it.kind=="image" }.source.startsWith("https://upload.wikimedia.org/"))
    check(blocks.none { it.html.contains("bad()") || it.html.contains("Skip this") })
    check(blocks.last().html=="Last paragraph")
    val infobox=ArticleBlocks.parse("<table class=\"infobox\"><tr><th rowspan=\"2\">Key</th><td>One</td></tr><tr><td>Two</td></tr></table><p>Lead stays separate.</p>")
    check(infobox.first().kind=="infobox" && infobox.last().kind=="p")
    val rows=ArticleBlocks.tableRows(infobox.first().html)
    check(rows.size==2 && rows[0][0].heading && rows[0][0].rows==2 && rows[1][0].html=="Two")
    check(ArticleBlocks.images("<img src='//upload.wikimedia.org/x.png'><img src='https://evil.invalid/x.png'>").size==1)
    val photo="<img src='//upload.wikimedia.org/icon.svg' width='20' height='20'><img src='//upload.wikimedia.org/photo.jpg?width=300&amp;tracking=1' width='300' height='200' alt='A &amp; B'>"
    check(ArticleBlocks.leadImage(photo)?.source=="https://upload.wikimedia.org/photo.jpg")
    check(ArticleBlocks.leadImage(photo)?.html=="A & B")
    check(ArticleBlocks.images(photo+photo).size==2)
    check(ArticleBlocks.imageUrl("https://upload.wikimedia.org.evil.invalid/photo.jpg")==null)
    check(ArticleBlocks.articleLinks("<a href='/wiki/A_B#section'>x</a><a href='./C%2B%2B'>C++</a><a href='/wiki/A_B'>same</a><a href='/wiki/File:Bad'>file</a><a href='https://evil.invalid/wiki/Bad'>bad</a>")==listOf("A B","C++"))
    val equation=MathText.normalize("<math><semantics><msup><mi>A</mi><mn>0</mn></msup><annotation encoding='application/x-tex'>{raw latex}</annotation></semantics></math>")
    check(equation=="<i>A</i><sup>0</sup>")
    val mathBlock=ArticleBlocks.parse("<p><math><mi>x</mi></math><img class='mwe-math-fallback-image-inline' src='https://wikimedia.org/math.svg' alt='{raw latex}'></p>")
    check(mathBlock.single().html=="<i>x</i>")
    check(QueryTerms.fts("alpha OR \" beta*")=="\"alpha\"* AND \"OR\"* AND \"beta\"*")
    check(QueryTerms.fts("' - % ").isEmpty())
    check(QueryTerms.fts("日本語 café")=="\"日本語\"* AND \"café\"*")
    val gate=RequestGate()
    val inFlight=AtomicInteger(); val maximum=AtomicInteger(); val finished=CountDownLatch(3)
    val starts=java.util.Collections.synchronizedList(mutableListOf<Long>())
    repeat(3) { Thread {
        gate.run(false) { val n=inFlight.incrementAndGet(); maximum.updateAndGet { maxOf(it,n) }; starts+=System.currentTimeMillis(); Thread.sleep(30); inFlight.decrementAndGet() }
        finished.countDown()
    }.start() }
    check(finished.await(6,TimeUnit.SECONDS))
    check(maximum.get()==1)
    check(starts.zipWithNext().all { (a,b) -> b-a>=490 })
    gate.cooldown(System.currentTimeMillis()+60_000)
    check(runCatching { gate.run(false) { error("Must not run during cooldown") } }.exceptionOrNull() is DeferredRequest)
    println("PASS: native block parsing, safe FTS terms, serialized request spacing, cooldown admission")
}
