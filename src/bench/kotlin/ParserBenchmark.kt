package dev.psyclyx.krylov.bench

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

/** Usage: baseline.jar candidate.jar article.html [...]; no Android or network. */
fun main(args: Array<String>) {
    require(args.size>=3)
    class Parser(path: String) {
        private val loader=URLClassLoader(arrayOf(File(path).toURI().toURL()),Thread.currentThread().contextClassLoader)
        private val type=loader.loadClass("dev.psyclyx.krylov.ArticleBlocks")
        private val instance=type.getField("INSTANCE").get(null)
        private val parse=type.getMethod("parse",String::class.java)
        fun run(html: String): List<*> = parse.invoke(instance,html) as List<*>
        fun supplemental(html: String): List<String> = listOf("images","leadImage","articleLinks","tableRows","tableText").map {
            type.getMethod(it,String::class.java).invoke(instance,html)?.toString().orEmpty()
        }
        fun digest(blocks: List<*>): String {
            val digest=MessageDigest.getInstance("SHA-256")
            blocks.forEach { block ->
                val b=requireNotNull(block)
                listOf("Kind","Html","Anchor","Source").forEach {
                    val bytes=(b.javaClass.getMethod("get$it").invoke(b) as String).toByteArray()
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    val baseline=Parser(args[0]); val candidate=Parser(args[1])
    args.drop(2).forEach { path ->
        val html=File(path).readText()
        val hashes=(0 until 6).map { baseline.digest(baseline.run(html)) }.toSet()
        check(hashes.size==1) { "Unstable baseline: $path" }
        val expected=hashes.single()
        repeat(6) { check(candidate.digest(candidate.run(html))==expected) { "Output changed: $path" } }
        check(baseline.supplemental(html)==candidate.supplemental(html)) { "Images/links/tables changed: $path" }
        val before=mutableListOf<Double>(); val after=mutableListOf<Double>()
        fun sample(parser: Parser, values: MutableList<Double>) {
            val start=System.nanoTime(); val blocks=parser.run(html)
            values+=(System.nanoTime()-start)/1_000_000.0
            check(parser.digest(blocks)==expected) { "Timed result changed: $path" }
        }
        repeat(12) { if(it%2==0) { sample(baseline,before); sample(candidate,after) } else { sample(candidate,after); sample(baseline,before) } }
        fun median(values: List<Double>)=values.sorted().let { (it[5]+it[6])/2 }
        println("${File(path).name}: chars=${html.length} sha256=$expected n=12 baseline_median_ms=${median(before)} candidate_median_ms=${median(after)} baseline_best_ms=${before.min()} candidate_best_ms=${after.min()}")
    }
}
