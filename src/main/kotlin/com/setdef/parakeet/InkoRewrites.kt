package com.setdef.parakeet

import com.intellij.openapi.util.TextRange

/** A text change: replace [range] with [text]. */
data class InkoEdit(val range: TextRange, val text: String)

/** What an intention would do at an offset: its menu text and edits. */
data class InkoRewrite(val title: String, val edits: List<InkoEdit>)

/**
 * Rewrites worked out from tokens alone, for the intentions on ⌥⏎. Inko's
 * binary operators share one precedence and group left to right, while `and`
 * and `or` bind more loosely, so operands are found by those rules.
 */
object InkoRewrites {
    private val FLIPS = mapOf("==" to "==", "!=" to "!=", "<" to ">", ">" to "<", "<=" to ">=", ">=" to "<=", "and" to "and", "or" to "or")
    private val NEGATIONS = mapOf("==" to "!=", "!=" to "==", "<" to ">=", ">=" to "<", ">" to "<=", "<=" to ">")

    // Flip '==' (and !=, comparisons, and/or).

    fun flip(text: CharSequence, offset: Int): InkoRewrite? {
        val stream = InkoTokenStream(text)
        val operator = operatorAt(stream, offset) ?: return null
        val flipped = FLIPS[operator.text] ?: return null
        val loose = operator.text == "and" || operator.text == "or"
        val leftStart = expressionStart(stream, operator.first - 1, stopAtLoose = !loose)
        val rightEnd = if (loose) looseEnd(stream, operator.last + 1) else operandEnd(stream, operator.last + 1)
        if (leftStart >= operator.first || rightEnd <= operator.last) return null
        val tokens = stream.tokens
        val left = text.substring(tokens[leftStart].start, tokens[operator.first - 1].end)
        val right = text.substring(tokens[operator.last + 1].start, tokens[rightEnd].end)
        // The left side may hold operators of the same level; once it's on the right it needs parentheses.
        val leftHasOperators = hasTopLevelOperator(stream, leftStart, operator.first - 1, looseOnly = loose)
        val newRight = if (leftHasOperators) "($left)" else left
        val title = if (flipped == operator.text) "Flip '${operator.text}'" else "Flip '${operator.text}' to '$flipped'"
        return InkoRewrite(title, listOf(InkoEdit(TextRange(tokens[leftStart].start, tokens[rightEnd].end), "$right $flipped $newRight")))
    }

    /** An operator (possibly several characters, like `<=`) or `and`/`or` at [offset]. */
    private class Operator(val first: Int, val last: Int, val text: String)

    private fun operatorAt(stream: InkoTokenStream, offset: Int): Operator? {
        val tokens = stream.tokens
        val at = tokens.indexOfFirst { (it.isOperator() || it.isKeyword("and", "or")) && offset in it.start..it.end }
        if (at < 0) return null
        if (tokens[at].isKeyword("and", "or")) return Operator(at, at, tokens[at].text)
        var first = at
        var last = at
        while (first > 0 && tokens[first - 1].isOperator() && tokens[first - 1].end == tokens[first].start) first--
        while (last + 1 < tokens.size && tokens[last + 1].isOperator() && tokens[last + 1].start == tokens[last].end) last++
        // A prefix operator (`!x`, `-1`) has no left operand.
        if (first == 0 || !endsOperand(tokens[first - 1])) return null
        return Operator(first, last, (first..last).joinToString("") { tokens[it].text })
    }

    private fun endsOperand(token: InkoToken) = token.closes && token.type != InkoTokens.RBRACE || isOperand(token)

    private fun isOperand(token: InkoToken) = token.type in setOf(
        InkoTokens.IDENTIFIER, InkoTokens.CONSTANT, InkoTokens.FIELD, InkoTokens.STRING, InkoTokens.NUMBER,
    ) || token.isKeyword("self", "true", "false", "nil")

    /**
     * Going back from [index], where the expression it ends starts: over
     * operands, `.` steps, brackets, and binary operators (and, unless
     * [stopAtLoose], `and`/`or`), stopping at `=`, `(`, `,`, keywords, or a new line.
     */
    private fun expressionStart(stream: InkoTokenStream, index: Int, stopAtLoose: Boolean): Int {
        val tokens = stream.tokens
        var k = index
        var start = index + 1
        while (k >= 0) {
            if (k < tokens.size - 1 && start <= index && stream.lineBreakBetween(k, k + 1) && !stream.continues(k + 1)) break
            val token = tokens[k]
            when {
                token.closes && token.type != InkoTokens.RBRACE -> {
                    val open = stream.match(k).takeIf { it >= 0 } ?: break
                    start = open
                    k = open - 1
                }
                isOperand(token) || token.type == InkoTokens.DOT -> { start = k; k-- }
                token.isKeyword("and", "or") -> if (stopAtLoose) break else { start = k; k-- }
                token.isOperator() -> {
                    var m = k
                    while (m > 0 && tokens[m - 1].isOperator() && tokens[m - 1].end == tokens[m].start) m--
                    val group = (m..k).joinToString("") { tokens[it].text }
                    // Assignment (`=`, `+=`, `:=`), annotations (`:`), and arrows end the expression.
                    if (group == ":" || group.endsWith("->") || group.endsWith("=") && group !in setOf("==", "!=", "<=", ">=")) break
                    start = m
                    k = m - 1
                }
                else -> break
            }
        }
        return start
    }

    /** Going forward from [index], the last token of one operand: prefixes, then a chain of names, calls, indexes, and `.` steps. */
    private fun operandEnd(stream: InkoTokenStream, index: Int): Int {
        val tokens = stream.tokens
        var i = index
        while (i < tokens.size && tokens[i].isOperator("!", "-")) i++
        if (i >= tokens.size) return -1
        var end: Int
        while (true) {
            val token = tokens.getOrNull(i) ?: return -1
            end = when {
                token.opens && token.type != InkoTokens.LBRACE -> stream.match(i).takeIf { it > i } ?: return -1
                isOperand(token) -> i
                else -> return -1
            }
            val next = tokens.getOrNull(end + 1)
            when {
                // `name(…)` and `name[…]`: calls and indexes.
                next != null && (next.type == InkoTokens.LPAREN || next.type == InkoTokens.LBRACKET) && next.start == tokens[end].end ->
                    { end = stream.match(end + 1).takeIf { it > end } ?: return -1 }
                else -> {}
            }
            val after = tokens.getOrNull(end + 1)
            // `.member` steps continue the chain, even on the next line.
            if (after?.type == InkoTokens.DOT) { i = end + 2; continue }
            return end
        }
    }

    /** Going forward from [index], the last token before the next `and`/`or` (or the expression's end). */
    private fun looseEnd(stream: InkoTokenStream, index: Int): Int {
        val tokens = stream.tokens
        var i = index
        var end = -1
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.isKeyword("and", "or") || token.type == InkoTokens.COMMA || token.closes || token.type == InkoTokens.LBRACE) break
            if (i > index && stream.lineBreakBetween(i - 1, i) && !stream.continues(i)) break
            if (token.opens) { i = stream.match(i).takeIf { it > i } ?: return -1 }
            if (!(isOperand(tokens[i]) || tokens[i].closes || tokens[i].isOperator() || tokens[i].type == InkoTokens.DOT || tokens[i].isKeyword("as"))) break
            end = i
            i++
        }
        return end
    }

    private fun hasTopLevelOperator(stream: InkoTokenStream, first: Int, last: Int, looseOnly: Boolean): Boolean {
        val tokens = stream.tokens
        var i = first
        while (i <= last) {
            val token = tokens[i]
            if (token.opens) { i = stream.match(i).takeIf { it > i }?.plus(1) ?: return true; continue }
            if (token.isKeyword("and", "or", "as")) return true
            if (!looseOnly && i > first && token.isOperator() && !tokens[i - 1].isOperator()) return true
            i++
        }
        return false
    }

    // Invert 'if'.

    /** An `if` the offset is on (from the keyword to its `{`), with its block and a plain `else` block. */
    private class IfElse(val keyword: Int, val open: Int, val close: Int, val elseOpen: Int, val elseClose: Int)

    private fun ifAt(stream: InkoTokenStream, offset: Int): Pair<Int, Int>? {
        val tokens = stream.tokens
        return tokens.indices.filter { tokens[it].isKeyword("if") }.mapNotNull { keyword ->
            val open = blockOpen(stream, keyword + 1) ?: return@mapNotNull null
            if (offset < tokens[keyword].start || offset > tokens[open].start) null else keyword to open
        }.lastOrNull()
    }

    /** The `{` that starts a construct's block, from [from] on, skipping brackets. */
    private fun blockOpen(stream: InkoTokenStream, from: Int): Int? {
        val tokens = stream.tokens
        var i = from
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type == InkoTokens.LBRACE) return i
            if (token.opens) { i = stream.match(i).takeIf { it > i }?.plus(1) ?: return null; continue }
            if (token.closes) return null
            i++
        }
        return null
    }

    fun invertIf(text: CharSequence, offset: Int): InkoRewrite? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val (keyword, open) = ifAt(stream, offset) ?: return null
        if (open == keyword + 1) return null
        val close = stream.match(open).takeIf { it > open } ?: return null
        if (tokens.getOrNull(close + 1)?.isKeyword("else") != true || tokens.getOrNull(close + 2)?.type != InkoTokens.LBRACE) return null
        val elseOpen = close + 2
        val elseClose = stream.match(elseOpen).takeIf { it > elseOpen } ?: return null
        val condition = negate(stream, keyword + 1, open - 1) ?: return null
        fun inner(o: Int, c: Int) = text.substring(tokens[o].end, tokens[c].start)
        val replacement = condition + text.substring(tokens[open - 1].end, tokens[open].end) + inner(elseOpen, elseClose) +
            text.substring(tokens[close].start, tokens[elseOpen].end) + inner(open, close) + "}"
        return InkoRewrite("Invert 'if' condition", listOf(InkoEdit(TextRange(tokens[keyword + 1].start, tokens[elseClose].end), replacement)))
    }

    /** The negation of the condition in tokens [first]..[last]: `a == b` → `a != b`, `!x` → `x`, else `!(…)`. */
    private fun negate(stream: InkoTokenStream, first: Int, last: Int): String? {
        val tokens = stream.tokens
        val text = stream.text
        val condition = text.substring(tokens[first].start, tokens[last].end)
        val operators = topLevelOperators(stream, first, last) ?: return null
        if (operators.size == 1) {
            val (opFirst, opLast, op) = operators.single()
            NEGATIONS[op]?.let { negated ->
                return text.substring(tokens[first].start, tokens[opFirst].start) + negated + text.substring(tokens[opLast].end, tokens[last].end)
            }
        }
        if (operators.isEmpty() && tokens[first].isOperator("!")) {
            val rest = first + 1
            if (tokens[rest].type == InkoTokens.LPAREN && stream.match(rest) == last) return text.substring(tokens[rest].end, tokens[last].start).trim()
            return text.substring(tokens[rest].start, tokens[last].end)
        }
        return if (operators.isEmpty()) "!$condition" else "!($condition)"
    }

    /** Top-level binary operators (and `and`/`or`/`as`) in [first]..[last], as (first, last, text); null if brackets don't balance. */
    private fun topLevelOperators(stream: InkoTokenStream, first: Int, last: Int): List<Triple<Int, Int, String>>? {
        val tokens = stream.tokens
        val result = mutableListOf<Triple<Int, Int, String>>()
        var i = first
        while (i <= last) {
            val token = tokens[i]
            if (token.opens) { i = stream.match(i).takeIf { it in i..last }?.plus(1) ?: return null; continue }
            if (token.isKeyword("and", "or", "as")) result += Triple(i, i, token.text)
            else if (i > first && token.isOperator() && !tokens[i - 1].isOperator()) {
                var end = i
                while (end + 1 <= last && tokens[end + 1].isOperator() && tokens[end + 1].start == tokens[end].end) end++
                result += Triple(i, end, (i..end).joinToString("") { tokens[it].text })
                i = end + 1
                continue
            }
            i++
        }
        return result
    }

    // 'if' ⇄ 'match'.

    fun ifToMatch(text: CharSequence, offset: Int): InkoRewrite? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val (keyword, _) = ifAt(stream, offset) ?: return null
        if (keyword > 0 && tokens[keyword - 1].isKeyword("else")) return null
        var subject: String? = null
        val arms = mutableListOf<Pair<List<String>, Pair<Int, Int>>>()
        var elseBlock: Pair<Int, Int>? = null
        var at = keyword
        var end: Int
        while (true) {
            val open = blockOpen(stream, at + 1) ?: return null
            val close = stream.match(open).takeIf { it > open } ?: return null
            val (armSubject, literals) = equalities(stream, at + 1, open - 1) ?: return null
            if (subject != null && armSubject != subject) return null
            subject = armSubject
            arms += literals to (open to close)
            end = close
            if (tokens.getOrNull(close + 1)?.isKeyword("else") != true) break
            when {
                tokens.getOrNull(close + 2)?.isKeyword("if") == true -> at = close + 2
                tokens.getOrNull(close + 2)?.type == InkoTokens.LBRACE -> {
                    val elseClose = stream.match(close + 2).takeIf { it > close + 2 } ?: return null
                    elseBlock = (close + 2) to elseClose
                    end = elseClose
                    break
                }
                else -> return null
            }
        }
        val indent = lineIndent(text, tokens[keyword].start)
        val armIndent = "$indent  "
        val literals = arms.flatMap { it.first }.toSet()
        val lines = arms.map { (patterns, block) -> "${armIndent}case ${patterns.joinToString(" or ")} -> ${armBody(text, stream, block, armIndent)}" }.toMutableList()
        when {
            elseBlock != null -> lines += "${armIndent}case _ -> ${armBody(text, stream, elseBlock, armIndent)}"
            // Bool is covered by true and false; anything else needs a catch-all.
            literals != setOf("true", "false") -> lines += "${armIndent}case _ -> {}"
        }
        val replacement = "match $subject {\n${lines.joinToString("\n")}\n$indent}"
        return InkoRewrite("Replace 'if' with 'match'", listOf(InkoEdit(TextRange(tokens[keyword].start, tokens[end].end), replacement)))
    }

    /** `x == 1` or `x == 1 or x == 2`: the subject `x` and literals; null for anything else. */
    private fun equalities(stream: InkoTokenStream, first: Int, last: Int): Pair<String, List<String>>? {
        val tokens = stream.tokens
        val text = stream.text
        val parts = mutableListOf<IntRange>()
        var start = first
        for (i in first..last) {
            if (tokens[i].isKeyword("or")) { parts += start until i; start = i + 1 }
            if (tokens[i].isKeyword("and")) return null
        }
        parts += start..last
        var subject: String? = null
        val literals = mutableListOf<String>()
        for (part in parts) {
            if (part.isEmpty()) return null
            val operators = topLevelOperators(stream, part.first, part.last) ?: return null
            val (opFirst, opLast, op) = operators.singleOrNull() ?: return null
            if (op != "==") return null
            // Matching evaluates the subject once; only names and fields, whose value can't change in between, are safe.
            if ((part.first until opFirst).any { !(isOperand(tokens[it]) || tokens[it].type == InkoTokens.DOT) || tokens[it].type == InkoTokens.STRING || tokens[it].type == InkoTokens.NUMBER }) return null
            val armSubject = text.substring(tokens[part.first].start, tokens[opFirst - 1].end)
            if (subject != null && subject != armSubject) return null
            subject = armSubject
            val literal = literal(stream, opLast + 1, part.last) ?: return null
            literals += literal
        }
        return subject!! to literals
    }

    /** A literal pattern in tokens [first]..[last]: a number (maybe negative), a string without interpolation, or a Bool. */
    private fun literal(stream: InkoTokenStream, first: Int, last: Int): String? {
        val tokens = stream.tokens
        val text = stream.text.substring(tokens[first].start, tokens[last].end)
        return when {
            first == last && tokens[first].type == InkoTokens.NUMBER -> text
            first == last && tokens[first].type == InkoTokens.STRING && !text.contains("\${") -> text
            first == last && tokens[first].isKeyword("true", "false") -> text
            last == first + 1 && tokens[first].isOperator("-") && tokens[last].type == InkoTokens.NUMBER -> text
            else -> null
        }
    }

    /** A block's contents as a match arm's body: inline when it's one line, otherwise a block one level deeper. */
    private fun armBody(text: CharSequence, stream: InkoTokenStream, block: Pair<Int, Int>, armIndent: String): String {
        val tokens = stream.tokens
        val inner = text.substring(tokens[block.first].end, tokens[block.second].start)
        val trimmed = inner.trim()
        if (trimmed.isEmpty()) return "{}"
        if ('\n' !in trimmed) return trimmed
        val lines = inner.split('\n').dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return "{\n" + lines.joinToString("\n") { if (it.isBlank()) "" else "  $it" } + "\n$armIndent}"
    }

    fun matchToIf(text: CharSequence, offset: Int): InkoRewrite? {
        val stream = InkoTokenStream(text)
        val tokens = stream.tokens
        val keyword = tokens.indices.filter { tokens[it].isKeyword("match") }.lastOrNull { k ->
            val open = blockOpen(stream, k + 1) ?: return@lastOrNull false
            offset >= tokens[k].start && offset <= tokens[open].start
        } ?: return null
        val open = blockOpen(stream, keyword + 1) ?: return null
        if (open == keyword + 1) return null
        // The subject is evaluated once per arm afterwards, so only names and fields are safe.
        if ((keyword + 1 until open).any { !(isOperand(tokens[it]) || tokens[it].type == InkoTokens.DOT) || tokens[it].type == InkoTokens.STRING || tokens[it].type == InkoTokens.NUMBER }) return null
        val subject = text.substring(tokens[keyword + 1].start, tokens[open - 1].end)
        val close = stream.match(open).takeIf { it > open } ?: return null

        // The arms: `case` positions directly inside the match's block.
        val cases = mutableListOf<Int>()
        var i = open + 1
        while (i < close) {
            if (tokens[i].isKeyword("case")) cases += i
            i = if (tokens[i].opens) stream.match(i).takeIf { it > i }?.plus(1) ?: return null else i + 1
        }
        if (cases.isEmpty() || tokens[open + 1].let { !it.isKeyword("case") }) return null
        val indent = lineIndent(text, tokens[keyword].start)
        val branches = mutableListOf<Pair<String?, String>>()
        for ((n, case) in cases.withIndex()) {
            val armEnd = (cases.getOrNull(n + 1) ?: close) - 1
            val arrow = (case + 1..armEnd).firstOrNull { tokens[it].isOperator("-") && tokens.getOrNull(it + 1)?.let { t -> t.isOperator(">") && t.start == tokens[it].end } == true } ?: return null
            if ((case + 1 until arrow).any { tokens[it].isKeyword("if") }) return null
            val alternatives = mutableListOf<String>()
            var start = case + 1
            for (j in case + 1..arrow) {
                if (j == arrow || tokens[j].isKeyword("or")) {
                    if (start > j - 1) return null
                    val pattern = stream.text.substring(tokens[start].start, tokens[j - 1].end)
                    if (pattern == "_") {
                        if (n != cases.lastIndex || alternatives.isNotEmpty() || j != arrow) return null
                        alternatives += "_"
                    } else {
                        alternatives += literal(stream, start, j - 1) ?: return null
                    }
                    start = j + 1
                }
            }
            val bodyStart = arrow + 2
            if (bodyStart > armEnd) return null
            val body = if (tokens[bodyStart].type == InkoTokens.LBRACE && stream.match(bodyStart) == armEnd) {
                blockBody(text, stream, bodyStart, armEnd, indent)
            } else {
                "\n$indent  ${text.substring(tokens[bodyStart].start, tokens[armEnd].end).trim()}\n$indent"
            }
            val condition = if (alternatives == listOf("_")) null else alternatives.joinToString(" or ") { "$subject == $it" }
            branches += condition to body
        }
        val rendered = buildString {
            branches.forEachIndexed { n, (condition, body) ->
                when {
                    n == 0 && condition == null -> return null
                    n == 0 -> append("if $condition {$body}")
                    condition == null -> append(" else {$body}")
                    else -> append(" else if $condition {$body}")
                }
            }
        }
        return InkoRewrite("Replace 'match' with 'if'", listOf(InkoEdit(TextRange(tokens[keyword].start, tokens[close].end), rendered)))
    }

    /** A match arm's `{ … }` as an `if` block's contents, one level shallower. */
    private fun blockBody(text: CharSequence, stream: InkoTokenStream, open: Int, close: Int, indent: String): String {
        val tokens = stream.tokens
        val inner = text.substring(tokens[open].end, tokens[close].start)
        if (inner.isBlank()) return ""
        if ('\n' !in inner.trim()) return "\n$indent  ${inner.trim()}\n$indent"
        val lines = inner.split('\n').dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return "\n" + lines.joinToString("\n") { if (it.startsWith("  ")) it.substring(2) else it.trimStart() } + "\n$indent"
    }

    private fun lineIndent(text: CharSequence, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun CharSequence.lastIndexOf(char: Char, from: Int): Int {
        var i = minOf(from, length - 1)
        while (i >= 0) { if (this[i] == char) return i; i-- }
        return -1
    }
}
