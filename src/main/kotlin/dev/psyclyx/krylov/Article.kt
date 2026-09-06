package dev.psyclyx.krylov

/** Immutable revision content. Latest is a separate pointer, never an identity. */
data class Article(val title: String, val revision: Long, val html: String, val links: List<String>, val fetched: Long)
data class Block(val kind: String, val html: String, val anchor: String = "", val source: String = "")
data class TableCell(val html: String, val heading: Boolean, val columns: Int, val rows: Int)
data class SearchHit(val title: String, val snippet: String, val cached: Boolean)
data class Visit(val id: Long, val title: String, val revision: Long, val time: Long, val parent: Long, val scroll: Int, val offset: Int = 0)
data class Mark(val id: Long, val title: String, val revision: Long, val block: Int, val start: Int, val end: Int,
                val quote: String, val note: String, val category: String, val color: Int, val time: Long)

/** A small tolerant tokenizer for MediaWiki's rendered HTML, not a wikitext parser.
 * Keeps tables, references and math fallbacks; never executes remote markup. */
object ArticleBlocks {
    private val tokens = Regex("<!--[\\s\\S]*?-->|<[^>]*>|[^<]+")
    private val attrs = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val entityPattern = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt);")
    private val imageTags=Regex("<img\\b[^>]*>",RegexOption.IGNORE_CASE)
    private val linkTags=Regex("<a\\b[^>]*>",RegexOption.IGNORE_CASE)
    private val strippedTags=Regex("<[^>]+>")
    private val rowEnds=Regex("</tr\\s*>",RegexOption.IGNORE_CASE)
    private val cellEnds=Regex("</t[dh]\\s*>",RegexOption.IGNORE_CASE)
    private val voidTags=setOf("img","br","hr","meta","link","input","wbr")
    private val skippedTags=setOf("script","style","noscript")
    private val skippedClasses=setOf("mw-editsection","navbox","metadata","toc","mw-empty-elt")
    private val blockTags=setOf("h1","h2","h3","h4","h5","h6","p","li","blockquote","pre","dt","dd","figcaption")
    private val containerTags=setOf("div","section","ul","ol","dl","figure")
    fun attributes(tag: String): Map<String,String> = attrs.findAll(tag).associate {
        it.groupValues[1].lowercase() to entities(it.groups[2]?.value?.takeIf { v -> v.isNotEmpty() }
            ?: it.groups[3]?.value?.takeIf { v -> v.isNotEmpty() } ?: it.groups[4]?.value.orEmpty())
    }
    private fun entities(value: String): String = if('&' !in value) value else entityPattern.replace(value) {
        when(val entity=it.groupValues[1]) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"
            else -> runCatching { String(Character.toChars(if(entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt())) }.getOrDefault(it.value)
        }
    }
    fun imageUrl(raw: String): String? {
        val decoded=entities(raw)
        val url=if(decoded.startsWith("//")) "https:$decoded" else decoded
        // Wikimedia thumbnail sizes are in the path; parser tracking queries
        // must not create separate cache entries for the same static image.
        return if(url.startsWith("https://upload.wikimedia.org/")) url.substringBefore('?').substringBefore('#') else null
    }
    fun images(html: String): List<Block> = imageTags.findAll(html).mapNotNull {
        val a=attributes(it.value); val src=imageUrl(a["src"].orEmpty())
        if(src!=null) Block("image",a["alt"].orEmpty(),source=src) else null
    }.distinctBy { it.source }.toList()
    fun leadImage(html: String): Block? = imageTags.findAll(html).mapNotNull {
        val a=attributes(it.value); val src=imageUrl(a["src"].orEmpty())
        if(src!=null && (a["width"]?.toIntOrNull() ?: 0)>=120 && (a["height"]?.toIntOrNull() ?: 0)>=80)
            Block("image",a["alt"].orEmpty(),source=src) else null
    }.firstOrNull()
    fun articleLinks(html: String): List<String> = linkTags.findAll(html).mapNotNull {
        val href=attributes(it.value)["href"].orEmpty()
        val path=when {
            href.startsWith("/wiki/") -> href.removePrefix("/wiki/")
            href.startsWith("./") -> href.removePrefix("./")
            else -> return@mapNotNull null
        }.substringBefore('#').substringBefore('?')
        val title=runCatching { java.net.URLDecoder.decode(path.replace("+","%2B"),"UTF-8").replace('_',' ') }.getOrNull()
        title?.takeIf { t -> t.isNotBlank() && ':' !in t }
    }.distinct().toList()
    fun tableText(html: String): String = html.replace(rowEnds,"<br>").replace(cellEnds," · ")
    private fun tagName(token: String, close: Boolean): String {
        val start=if(close) 2 else 1
        var end=start
        while(end<token.length && token[end].isLetterOrDigit()) end++
        return token.substring(start,end).lowercase()
    }
    fun tableRows(html: String): List<List<TableCell>> {
        val rows=mutableListOf<List<TableCell>>(); var row=mutableListOf<TableCell>()
        var depth=0; var cell: StringBuilder?=null; var heading=false; var columns=1; var spans=1
        fun finishCell() { cell?.let { row+=TableCell(it.toString(),heading,columns,spans) }; cell=null }
        for(m in tokens.findAll(html)) {
            val token=m.value; val close=token.startsWith("</")
            val tag=if(token.startsWith('<')) tagName(token,close) else ""
            if(tag=="table") { if(close) depth-- else depth++; if(depth>1) cell?.append(token); continue }
            if(depth==1 && tag=="tr") {
                finishCell(); if(row.isNotEmpty()) { rows+=row; row=mutableListOf() }; continue
            }
            if(depth==1 && (tag=="td" || tag=="th")) {
                finishCell()
                if(!close) { val a=attributes(token); cell=StringBuilder(); heading=tag=="th"; columns=a["colspan"]?.toIntOrNull()?.coerceIn(1,100) ?: 1; spans=a["rowspan"]?.toIntOrNull()?.coerceIn(1,1000) ?: 1 }
            } else cell?.append(token)
        }
        finishCell(); if(row.isNotEmpty()) rows+=row
        return rows
    }
    fun parse(html: String): List<Block> {
        val result = mutableListOf<Block>()
        val buffer = StringBuilder()
        var kind = "p"; var anchor = ""; var pendingAnchor = ""
        val skip = mutableListOf<String>()
        var tableDepth = 0
        val hasMath=html.contains("<math")
        fun flush() {
            val content=buffer.toString()
            if (content.replace(strippedTags, "").isNotBlank() || (kind=="infobox" && buffer.contains("<img")))
                result += Block(kind, content, anchor)
            buffer.setLength(0); kind = "p"; anchor = ""
        }
        for (m in tokens.findAll(MathText.normalize(html))) {
            val token = m.value
            if (token.startsWith("<!--")) continue
            if (!token.startsWith('<')) { if (skip.isEmpty()) buffer.append(token); continue }
            val close = token.startsWith("</")
            val tag = tagName(token,close)
            val void = tag in voidTags || token.endsWith("/>")
            if (skip.isNotEmpty()) {
                if (close && tag == skip.last()) skip.removeAt(skip.lastIndex)
                else if (!close && !void) skip.add(tag)
                continue
            }
            val a=if(close) emptyMap() else attributes(token)
            if (!close && (tag in skippedTags ||
                    a["class"].orEmpty().split(' ').any { it in skippedClasses })) {
                if (!void) skip.add(tag)
                continue
            }
            if (!close && a["id"] != null) pendingAnchor = a.getValue("id")
            if (tag == "table") {
                if (!close) { if (tableDepth == 0) { flush(); kind = if(a["class"].orEmpty().split(' ').contains("infobox")) "infobox" else "table"; anchor = pendingAnchor }; tableDepth++; buffer.append(token) }
                else { buffer.append(token); tableDepth--; if (tableDepth <= 0) { tableDepth = 0; flush() } }
                continue
            }
            if (tableDepth > 0) { buffer.append(token); continue }
            if (tag == "img" && !close) {
                if(a["class"].orEmpty().contains("mwe-math-fallback-image") && hasMath) continue
                val src = imageUrl(a["src"].orEmpty())
                if (src!=null) {
                    val previousKind = kind
                    flush(); result += Block("image", a["alt"].orEmpty(), pendingAnchor, src)
                    kind = if (tableDepth > 0) "table" else previousKind
                } else if (!a["alt"].isNullOrBlank()) buffer.append(a["alt"])
                continue
            }
            if (tag in blockTags) {
                if (close) flush() else {
                    flush(); kind = tag; anchor = a["id"] ?: pendingAnchor
                    if (tag == "li") buffer.append("• ")
                }
            } else if (tag in containerTags) {
                if (close) flush()
            } else buffer.append(token)
        }
        flush()
        return result
    }

}

object QueryTerms {
    fun fts(query: String): String = Regex("[\\p{L}\\p{N}]+").findAll(query).take(16)
        .joinToString(" AND ") { "\"${it.value}\"*" }
}
