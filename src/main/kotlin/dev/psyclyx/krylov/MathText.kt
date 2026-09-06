package dev.psyclyx.krylov

/** Translate presentation MathML into Android-supported inline text markup.
 * Annotation source is deliberately excluded; it is not a second equation. */
object MathText {
    private data class Node(val tag: String, val children: MutableList<Node> = mutableListOf(), val value: String = "")
    private val math=Regex("<math\\b[^>]*>[\\s\\S]*?</math>",RegexOption.IGNORE_CASE)
    private val tokens=Regex("<[^>]*>|[^<]+")
    fun normalize(html: String): String = math.replace(html) { expression ->
        val root=Node("root"); val stack=java.util.ArrayDeque<Node>(); stack.add(root)
        tokens.findAll(expression.value).forEach { token ->
            val s=token.value
            // MediaWiki emits balanced MathML, but tolerate unmatched closings.
            if(s.startsWith("</")) { if(stack.size>1) stack.removeLast() }
            else if(s.startsWith('<')) {
                val tag=s.drop(1).takeWhile { it.isLetterOrDigit() || it==':' || it=='-' }.substringAfter(':').lowercase()
                val node=Node(tag); stack.last.children.add(node); if(!s.endsWith("/>")) stack.add(node)
            } else stack.last.children.add(Node("text",value=s))
        }
        render(root)
    }
    private fun render(node: Node): String {
        if(node.tag=="annotation" || node.tag=="annotation-xml") return ""
        if(node.tag=="text") return node.value
        val children=node.children.filterNot { it.tag=="text" && it.value.isBlank() }.map { render(it) }
        fun child(i: Int)=children.getOrElse(i) { "" }
        return when(node.tag) {
            "msup" -> "${child(0)}<sup>${child(1)}</sup>"
            "msub" -> "${child(0)}<sub>${child(1)}</sub>"
            "msubsup" -> "${child(0)}<sub>${child(1)}</sub><sup>${child(2)}</sup>"
            "mfrac" -> "(${child(0)})/(${child(1)})"
            "msqrt" -> "√(${children.joinToString("")})"
            "mroot" -> "<sup>${child(1)}</sup>√(${child(0)})"
            "mover" -> "${child(0)}<sup>${child(1)}</sup>"
            "munder" -> "${child(0)}<sub>${child(1)}</sub>"
            "munderover" -> "${child(0)}<sub>${child(1)}</sub><sup>${child(2)}</sup>"
            "mtable" -> "[${children.joinToString("; ")}]"
            "mtr" -> children.joinToString(", ")
            "mi" -> "<i>${children.joinToString("")}</i>"
            "mspace" -> " "
            else -> children.joinToString("")
        }
    }
}
