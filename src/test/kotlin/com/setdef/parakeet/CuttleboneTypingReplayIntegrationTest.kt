package com.setdef.parakeet

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.EditorTracker
import com.intellij.codeInsight.daemon.impl.IdentifierHighlighterPassFactory
import com.intellij.codeInsight.editorActions.CompletionAutoPopupHandler
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestModeFlags
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Types real code into real files at a person's speed, against the real `cuttlebone`, with the IDE's own
 * highlighting (whose inspections resolve names), inlay hints, usage highlighting, parameter info and completion
 * running as they do in the IDE, and measures what each feature asks Cuttlebone while it does: requests sent and
 * cancelled, answers that arrive for text that has changed since, checks Cuttlebone runs, and how long after a
 * keystroke each result is up to date. The limits each test asserts are explained in
 * docs/history/SESSION-typing-replay.md.
 *
 * The standard library replay always runs (where Cuttlebone's integration tests run): its module comes from the
 * installed toolchain. The project replay runs with PARAKEET_REPLAY_PROJECT set to a project's directory (with
 * `src`, `inko.pkg` and a synced `dep`; inko-lang/idoc was measured), typing PARAKEET_REPLAY_FILE (default
 * `src/idoc/html.inko`) from the line starting with PARAKEET_REPLAY_FROM (by default html.inko's `fn adjust_headings(`,
 * else the file's first `fn `) to the end of that method.
 */
class CuttleboneTypingReplayIntegrationTest : InkoTestCase(onDisk = true, realInko = true) {
    /** One request, from the trace. */
    private class Request(val id: Long, val method: String, val stamp: Long, val sentAt: Long) {
        @Volatile var endedAt: Long = 0
        @Volatile var status: CuttleboneStatus? = null
        @Volatile var cancelled = false
        @Volatile var failed = false
        /** The document's stamp when the answer arrived. */
        @Volatile var stampAtEnd: Long = 0
        val answered: Boolean get() = endedAt != 0L && !cancelled && !failed
    }

    private val requests = ConcurrentHashMap<Long, Request>()
    /** When each document stamp came about (the edit that made it), in nanoseconds. */
    private val changedAt = ConcurrentHashMap<Long, Long>()
    /** (when, stamp) of each daemon run that finished. */
    private val daemonFinished = CopyOnWriteArrayList<Pair<Long, Long>>()
    private lateinit var document: Document

    private var fixups = 0
    private var accepted = 0
    private var parameterInfos = 0
    private var typos = 0
    private var keystrokes = 0

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }

    override fun setUp() {
        super.setUp()
        val executable = env("CUTTLEBONE") ?: run { InkoProjectSettings.getInstance(project).cuttlebone = ""; Cuttlebone.executable(project)?.toString() }
        assertNotNull("cuttlebone must be installed (or CUTTLEBONE set) to run these tests", executable)
        println("REPLAY against cuttlebone at $executable")
        useCuttlebone(executable!!)
        // A person types the brackets and quotes; the IDE's pairs would need overtyping the replay can't predict.
        val settings = CodeInsightSettings.getInstance()
        val (bracket, quote, brace) = Triple(settings.AUTOINSERT_PAIR_BRACKET, settings.AUTOINSERT_PAIR_QUOTE, settings.INSERT_BRACE_ON_ENTER)
        settings.AUTOINSERT_PAIR_BRACKET = false
        settings.AUTOINSERT_PAIR_QUOTE = false
        settings.INSERT_BRACE_ON_ENTER = false
        com.intellij.openapi.util.Disposer.register(testRootDisposable) {
            settings.AUTOINSERT_PAIR_BRACKET = bracket
            settings.AUTOINSERT_PAIR_QUOTE = quote
            settings.INSERT_BRACE_ON_ENTER = brace
        }
    }

    override fun tearDown() {
        try {
            CuttleboneQueries.getInstance(project).trace = null
        } finally {
            super.tearDown()
        }
    }

    private fun copy(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { path ->
                val target = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            }
        }
    }

    /** A copy of the installed standard library as the project: it comes with the toolchain, so this always runs. */
    private fun std() {
        val std = InkoTestRuns.standardLibrary(InkoCli.getInstance(project))
        assertNotNull("the standard library wasn't found", std)
        copy(std!!, Path.of(myFixture.tempDirPath).resolve("src"))
    }

    /** The project PARAKEET_REPLAY_PROJECT names, if it's set, and the method to type in it. */
    private fun project(): Pair<String, String?>? {
        val dir = env("PARAKEET_REPLAY_PROJECT")?.let(Path::of) ?: return null
        val root = Path.of(myFixture.tempDirPath)
        for (part in listOf("src", "dep")) if (Files.isDirectory(dir.resolve(part))) copy(dir.resolve(part), root.resolve(part))
        Files.copy(dir.resolve(Inko.MANIFEST), root.resolve(Inko.MANIFEST))
        val file = env("PARAKEET_REPLAY_FILE") ?: "src/idoc/html.inko"
        return file to (env("PARAKEET_REPLAY_FROM") ?: "fn adjust_headings(".takeIf { env("PARAKEET_REPLAY_FILE") == null })
    }

    private val skipped = "Skipped: set PARAKEET_REPLAY_PROJECT to a project's directory to replay typing in it"

    // A new method, typed from its first line: until its last `}`, the file doesn't parse.
    fun testTypingANewMethodInAStdModule() {
        std()
        replay("src/std/json.inko", JSON_OBJECT, Scope.METHOD).apply { print("new method, std/json.inko") }.assertWithinLimits()
    }

    // A method's body, typed between its braces (as when the IDE closes the `{` typed before it): the file
    // parses again as each statement is finished.
    fun testTypingAMethodBodyInAStdModule() {
        std()
        replay("src/std/json.inko", JSON_OBJECT, Scope.BODY).apply { print("method body, std/json.inko") }.assertWithinLimits()
    }

    fun testTypingANewMethodInAProject() {
        val (file, from) = project() ?: return println(skipped)
        replay(file, from, Scope.METHOD).apply { print("new method, ${Path.of(env("PARAKEET_REPLAY_PROJECT")!!).fileName}/$file") }.assertWithinLimits()
    }

    fun testTypingAMethodBodyInAProject() {
        val (file, from) = project() ?: return println(skipped)
        replay(file, from, Scope.BODY).apply { print("method body, ${Path.of(env("PARAKEET_REPLAY_PROJECT")!!).fileName}/$file") }.assertWithinLimits()
    }

    /** What the replay deletes and types again: a whole method, or only the lines between its braces. */
    private enum class Scope { METHOD, BODY }

    /**
     * Opens [path], deletes the method that starts at the line [from] (or the file's first method), or its
     * body, lets everything settle, then types it back as a person would, and waits for the results to settle.
     */
    private fun replay(path: String, from: String?, scope: Scope): Report {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(myFixture.tempDirPath))!!.refresh(false, true)
        putTempDirInProjectContent()
        val file = myFixture.findFileInTempDir(path) ?: error("no $path")
        myFixture.configureFromExistingVirtualFile(file)
        document = myFixture.editor.document
        val text = document.text
        val method = if (from != null) text.indexOf(from).also { check(it >= 0) { "no $from in $path" } } else Regex("(?m)^ *fn ").find(text)!!.range.first
        val whole = method(text, method)
        // The body: after the method's first line, up to its closing one.
        val start = if (scope == Scope.METHOD) method else text.indexOf('\n', method) + 1
        val code = if (scope == Scope.METHOD) whole else text.substring(start, method + whole.trimEnd('\n').lastIndexOf('\n') + 1)
        println("REPLAY typing ${code.length} characters of $path:\n$code")

        waitFor("Cuttlebone to start", 60) { CuttleboneService.getInstance(project).server() != null }
        // The definitions index (inko doc), as it's built in the background when a project opens.
        inBackground { InkoDocIndex.getInstance(project).symbols(Path.of(myFixture.tempDirPath)) }

        // The IDE as it is when a person types: the daemon on its timer (with the IDE's own delays), for the editor
        // in front of them.
        println("REPLAY autoReparseDelay ${com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings.getInstance().autoReparseDelay}, PARAMETER_INFO_DELAY ${CodeInsightSettings.getInstance().PARAMETER_INFO_DELAY}")
        val daemon = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl
        val timer = daemon.isUpdateByTimerEnabled
        daemon.setUpdateByTimerEnabled(true)
        EditorTracker.getInstance(project).setActiveEditorsInTests(listOf(myFixture.editor))
        TestModeFlags.set(CompletionAutoPopupHandler.ourTestingAutopopup, true, testRootDisposable)
        project.messageBus.connect(testRootDisposable).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                daemonFinished += System.nanoTime() to document.modificationStamp
            }
        })
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                changedAt[event.document.modificationStamp] = System.nanoTime()
            }
        }, testRootDisposable)

        var report: Report? = null
        try {
            IdentifierHighlighterPassFactory.doWithIdentifierHighlightingEnabled(project) {
                // Where the person starts: the method gone, and everything settled.
                myFixture.editor.caretModel.moveToOffset(start)
                edit { document.deleteString(start, start + code.length) }
                before = text.substring(0, start)
                after = text.substring(start + code.length)
                settle(daemon)
                startTrace()
                val began = System.nanoTime()
                type(start, code, Random(7))
                val typedFor = System.nanoTime() - began
                // The person stops typing, and looks.
                pump(SETTLE_MS)
                settle(daemon)
                report = Report(requests.values.sortedBy { it.id }, typedFor, keystrokes, typos, accepted, parameterInfos, fixups)
                assertEquals("the replay typed the method back as it was", text, document.text)
            }
        } finally {
            // The tests after this one, in the same IDE, expect the daemon as the test framework leaves it.
            CuttleboneQueries.getInstance(project).trace = null
            EditorTracker.getInstance(project).setActiveEditorsInTests(emptyList())
            daemon.setUpdateByTimerEnabled(timer)
            LookupManager.getInstance(project).hideActiveLookup()
            PlatformTestUtil.waitWithEventsDispatching("the daemon to stop", { !daemon.isRunningOrPending }, 30)
        }
        return report!!
    }

    /** The method starting at [start] in [text]: through its closing line, at its own indentation. */
    private fun method(text: String, start: Int): String {
        val indent = text.substring(start).takeWhile { it == ' ' }
        val close = Regex("(?m)^$indent}\\n").find(text, start) ?: error("no end of the method at $start")
        return text.substring(start, close.range.last + 1)
    }

    private fun startTrace() {
        requests.clear()
        CuttleboneService.getInstance(project).sentMessages.clear()
        CuttleboneQueries.getInstance(project).trace = object : CuttleboneQueries.Trace {
            override fun sent(request: Long, method: String, document: Document) {
                requests[request] = Request(request, method, document.modificationStamp, System.nanoTime())
            }

            override fun answered(request: Long, status: CuttleboneStatus?) {
                val r = requests[request] ?: return
                r.status = status
                r.stampAtEnd = document.modificationStamp
                r.endedAt = System.nanoTime()
            }

            override fun failed(request: Long, error: Throwable) {
                val r = requests[request] ?: return
                r.cancelled = error is java.util.concurrent.CancellationException || error is com.intellij.openapi.progress.ProcessCanceledException ||
                    generateSequence(error) { it.cause }.any { it is java.util.concurrent.CancellationException }
                r.failed = !r.cancelled
                r.stampAtEnd = document.modificationStamp
                r.endedAt = System.nanoTime()
            }
        }
    }

    /**
     * Types [code] at [start], a key at a time, at about 7 characters a second: with pauses at the ends of lines
     * and longer ones every few lines, a typo now and then (deleted), parameter info asked for in calls (⌘P),
     * and completion's item accepted when the popup offers the member being typed.
     */
    private fun type(start: Int, code: String, random: Random) {
        var typed = 0
        var lines = 0
        while (typed < code.length) {
            val c = code[typed]
            // After `.`, accept completion's item for the member once two of its letters are typed.
            val begin = memberStartOffset(code, typed)
            val member = if (begin > 0 && code[begin - 1] == '.') memberAt(code, typed) else null
            if (member != null && typed < begin + member.length && acceptCompletion(member)) {
                typed = begin + member.length
                resync(start, code, typed)
                pump(gap(random))
                continue
            }
            if (c.isLetter() && random.nextInt(30) == 0) {
                typos++
                key(('a'..'z').random(random))
                pump(150L + random.nextInt(200))
                myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
                keystrokes++
                pump(gap(random))
            }
            if (c == '\n') {
                pump(500L + random.nextInt(700))
                lines++
                if (lines % 4 == 0) pump(2500)
                key('\n')
                typed++
                // The IDE indents the new line; the person fixes it where it's not what they want.
                typed += code.substring(typed).takeWhile { it == ' ' }.length
                resync(start, code, typed, counts = false)
                continue
            }
            key(c)
            typed++
            resync(start, code, typed)
            if (c == '(' && typed >= 2 && code[typed - 2].isLetterOrDigit()) {
                pump(gap(random))
                parameterInfos++
                myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SHOW_PARAMETER_INFO)
            }
            pump(gap(random) + if (c == ' ') random.nextInt(100) else 0)
        }
    }

    private fun gap(random: Random): Long = 90L + random.nextInt(100)

    /** Where the name that [at] is in (or just after) begins. */
    private fun memberStartOffset(code: String, at: Int): Int {
        var i = at
        while (i > 0 && (code[i - 1].isLetterOrDigit() || code[i - 1] == '_')) i--
        return i
    }

    /** The member name being typed at [at], once two of its letters are typed, if it's long enough to complete. */
    private fun memberAt(code: String, at: Int): String? {
        val begin = memberStartOffset(code, at)
        return Regex("""\G[A-Za-z_][A-Za-z0-9_]*\??""").find(code, begin)?.value?.takeIf { it.length >= 4 && at - begin >= 2 }
    }

    /** Accepts the lookup's item for [member] if it's showing. */
    private fun acceptCompletion(member: String): Boolean {
        val lookup = LookupManager.getActiveLookup(myFixture.editor) as? LookupImpl ?: return false
        val item = lookup.items.firstOrNull { it.lookupString == member } ?: return false
        lookup.currentItem = item
        lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        accepted++
        return true
    }

    private fun key(c: Char) {
        myFixture.type(c)
        keystrokes++
    }

    /** Puts the document back to [code]'s first [typed] characters at [start], where the IDE typed otherwise. */
    private fun resync(start: Int, code: String, typed: Int, counts: Boolean = true) {
        val expected = before + code.substring(0, typed) + after
        if (document.charsSequence.contentEquals(expected) && myFixture.editor.caretModel.offset == start + typed) return
        if (counts) fixups++
        // Only what differs, as the person's own fix would.
        val actual = document.charsSequence
        var prefix = 0
        while (prefix < actual.length && prefix < expected.length && actual[prefix] == expected[prefix]) prefix++
        var suffix = 0
        while (suffix < actual.length - prefix && suffix < expected.length - prefix && actual[actual.length - 1 - suffix] == expected[expected.length - 1 - suffix]) suffix++
        if (prefix + suffix < maxOf(actual.length, expected.length)) {
            edit { document.replaceString(prefix, actual.length - suffix, expected.substring(prefix, expected.length - suffix)) }
        }
        myFixture.editor.caretModel.moveToOffset(start + typed)
    }

    /** The text before and after the method being typed. */
    private var before = ""
    private var after = ""

    private fun edit(action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { action() }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    /** Lets the IDE run (the EDT's events, the daemon's timer, completion) for [ms]. */
    private fun pump(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        }
    }

    /** Waits until the daemon has finished for the document as it is, and no request is waiting for an answer. */
    private fun settle(daemon: DaemonCodeAnalyzerImpl) {
        val deadline = System.nanoTime() + 60_000_000_000
        while (System.nanoTime() < deadline) {
            pump(200)
            val finished = daemonFinished.any { it.second == document.modificationStamp }
            val waiting = requests.values.any { it.endedAt == 0L }
            if (finished && !waiting && !daemon.isRunningOrPending) return
        }
        println("REPLAY warning: didn't settle in 60 s")
    }

    private fun waitFor(what: String, seconds: Int, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching({ "timed out waiting for $what: ${CuttleboneService.getInstance(project).status?.message}" }, condition, seconds)

    private fun <T> inBackground(action: () -> T): T {
        val future = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<T> {
            com.intellij.openapi.application.ReadAction.compute<T, Throwable> { action() }
        }
        PlatformTestUtil.waitWithEventsDispatching("background action", { future.isDone }, 600)
        return future.get()
    }

    /**
     * What the replay measured. Latencies are from the edit that made a version of the text to the answer for
     * it, for versions the person paused at (no edit for [SETTLED_MS]): what they'd see.
     */
    private inner class Report(
        val requests: List<Request>,
        val typedForNs: Long,
        val keystrokes: Int,
        val typos: Int,
        val accepted: Int,
        val parameterInfos: Int,
        val fixups: Int,
    ) {
        val stamps: List<Pair<Long, Long>> = changedAt.entries.map { it.key to it.value }.sortedBy { it.second }

        /** The versions the person paused at, with when each began. */
        val settled: List<Pair<Long, Long>> = stamps.filterIndexed { i, (_, at) ->
            val next = stamps.getOrNull(i + 1)?.second ?: Long.MAX_VALUE
            next - at >= SETTLED_MS * 1_000_000
        }

        val cancelsSent: Long get() = CuttleboneService.getInstance(project).sentMessages["\$/cancelRequest"]?.get() ?: 0

        fun of(method: String) = requests.filter { it.method == method }

        /** Answers that arrived for text that had changed since the request. */
        fun outdated(list: List<Request>) = list.count { it.answered && it.stampAtEnd != it.stamp }

        /**
         * Of the requests whose text changed before they were answered, how many were cancelled: the rest were
         * answered for text nobody had any more, unless something still waited for them (completion, as its items
         * are filtered by what's typed after).
         */
        fun cancelledOfChanged(): Pair<Int, Int> {
            val changed = requests.filter { it.endedAt != 0L && it.stampAtEnd != it.stamp }
            return changed.count { it.cancelled } to changed.size
        }

        /** Answers from a check run for them (not one Cuttlebone kept). */
        fun checks(list: List<Request>) = list.count { it.answered && it.status != null && !it.status!!.cached }

        fun checkMs(list: List<Request>) = list.filter { it.answered && it.status?.cached == false }.sumOf { it.status?.elapsedMs ?: 0.0 }

        /** For each settled version, how long until [method]'s last answer for it, or null without one. */
        fun latencies(method: String): List<Long?> = settled.map { (stamp, at) ->
            requests.filter { it.method == method && it.stamp == stamp && it.answered }.maxOfOrNull { it.endedAt }?.let { (it - at) / 1_000_000 }
        }

        /**
         * For each answer that arrived while its text was still the document's, how long after the edit that made the
         * text: what completion and parameter info, asked right after a keystroke, take to show.
         */
        fun current(method: String): List<Long> = requests.filter { it.method == method && it.answered && it.stampAtEnd == it.stamp }
            .mapNotNull { r -> changedAt[r.stamp]?.let { (r.endedAt - it) / 1_000_000 } }.sorted()

        fun daemonLatencies(): List<Long?> = settled.map { (stamp, at) ->
            daemonFinished.filter { it.second == stamp }.minOfOrNull { it.first }?.let { (it - at) / 1_000_000 }
        }

        /**
         * Fails if typing made Cuttlebone do more, or answer later, than the limits allow: each is a few times what
         * this session measured after its fixes, and well below what it measured before them, where that differed
         * (docs/history/SESSION-typing-replay.md has both).
         */
        fun assertWithinLimits() {
            val keys = keystrokes.toDouble()
            fun perKeystroke(what: String, count: Int, limit: Double) =
                assertTrue("$what per keystroke: $count for $keystrokes keystrokes, over $limit", count / keys <= limit)
            perKeystroke("requests", requests.size, MAX_REQUESTS_PER_KEYSTROKE)
            perKeystroke("checks", checks(requests), MAX_CHECKS_PER_KEYSTROKE)
            perKeystroke("answers for text that had changed", outdated(requests), MAX_OUTDATED_PER_KEYSTROKE)
            val (cancelled, changed) = cancelledOfChanged()
            // A share of a handful says little (idoc's were 2 of 3); the answers per keystroke above cover those.
            if (changed >= 5) {
                assertTrue("of the $changed requests whose text changed before their answer, $cancelled were cancelled", cancelled >= changed * MIN_CANCELLED_SHARE)
            }

            val hints = latencies("textDocument/inlayHint")
            assertTrue("inlay hints answered at ${hints.count { it != null }} of ${hints.size} pauses", hints.count { it != null } * 3 >= hints.size)
            fun within(what: String, sorted: List<Long>, p: Double, limit: Long) {
                val value = percentile(sorted, p) ?: return
                assertTrue("$what: ${(p * 100).toInt()}th percentile $value ms, over $limit ms (all: $sorted)", value <= limit)
            }
            within("inlay hints at pauses", hints.filterNotNull().sorted(), 0.9, MAX_HINTS_AT_PAUSE_MS)
            within("usage highlighting at pauses", latencies("textDocument/documentHighlight").filterNotNull().sorted(), 0.9, MAX_USAGES_AT_PAUSE_MS)
            within("completion, keystroke to answer", current("textDocument/completion"), 0.9, MAX_COMPLETION_MS)
            within("parameter info, keystroke to answer", current("textDocument/signatureHelp"), 1.0, MAX_PARAMETER_INFO_MS)
        }

        /** Every request, and every edit, as tab-separated lines, to PARAKEET_REPLAY_TRACE if it's set. */
        fun write(label: String) {
            val path = env("PARAKEET_REPLAY_TRACE")?.let(Path::of) ?: return
            val origin = stamps.firstOrNull()?.second ?: return
            fun ms(ns: Long) = if (ns == 0L) "" else ((ns - origin) / 1_000_000).toString()
            val lines = stamps.map { (stamp, at) -> "edit\t${ms(at)}\t$stamp" } +
                daemonFinished.map { (at, stamp) -> "daemon\t${ms(at)}\t$stamp" } +
                requests.map { r ->
                    val status = r.status
                    "request\t${ms(r.sentAt)}\t${r.stamp}\t${r.id}\t${r.method}\t${ms(r.endedAt)}\t${r.stampAtEnd}\t" +
                        (if (r.cancelled) "cancelled" else if (r.failed) "failed" else status?.status.orEmpty()) +
                        "\t${status?.reason.orEmpty()}\t${status?.cached ?: ""}\t${status?.elapsedMs ?: ""}"
                }
            Files.write(path.resolve("$label.tsv".replace('/', '_')).also { Files.createDirectories(it.parent) }, lines)
        }

        fun print(label: String) {
            write(label)
            println("REPLAY $label: typed for ${typedForNs / 1_000_000_000.0} s: $keystrokes keystrokes ($typos typos deleted), " +
                "$accepted completions accepted, parameter info $parameterInfos times, ${stamps.size} versions, ${settled.size} paused at, $fixups fixups")
            val (cancelled, changed) = cancelledOfChanged()
            println("REPLAY $label: ${requests.size} requests, $cancelsSent \$/cancelRequest sent, ${outdated(requests)} answers for changed text " +
                "($cancelled of $changed whose text changed cancelled), ${checks(requests)} checks (${"%.0f".format(checkMs(requests))} ms)")
            println("REPLAY | feature | sent | cancelled | answered | for changed text | checks | check ms | latency at pauses: answered, median, p90, max (ms) | keystroke to current answer: median, p90, max (ms) |")
            for ((feature, method) in FEATURES) {
                val list = of(method)
                val latencies = if (method == DAEMON) daemonLatencies() else latencies(method)
                val known = latencies.filterNotNull().sorted()
                fun pct(p: Double) = percentile(known, p)
                val answers = current(method)
                println("REPLAY | $feature | ${list.size} | ${list.count { it.cancelled }} | ${list.count { it.answered }} | ${outdated(list)} | " +
                    "${checks(list)} | ${"%.0f".format(checkMs(list))} | ${known.size}/${latencies.size}, ${pct(0.5)}, ${pct(0.9)}, ${known.lastOrNull()} | " +
                    "${percentile(answers, 0.5)}, ${percentile(answers, 0.9)}, ${answers.lastOrNull()} |")
            }
        }
    }

    private companion object {
        const val JSON_OBJECT = "  fn mut object -> Result[Json, Error[E]] {"

        // The limits (Report.assertWithinLimits). On std's json.inko, both ways of typing, after the fixes / before
        // them, per keystroke: requests 0.36 to 0.42 / 1.45 to 1.50, checks 0.26 to 0.29 / 1.10 to 1.45, answers for
        // text that had changed up to 0.026 / 0.187 or more. Times after the fixes (as before them, but for usage
        // highlighting, which now waits for typing to pause): at the 90th percentile, inlay hints at pauses up to
        // 491 ms (300 of them the IDE's own delay after an edit), usage highlighting at pauses up to 394 ms, and
        // completion up to 184 ms after its keystroke; parameter info, every answer, up to 289 ms.
        const val MAX_REQUESTS_PER_KEYSTROKE = 0.7
        const val MAX_CHECKS_PER_KEYSTROKE = 0.5
        const val MAX_OUTDATED_PER_KEYSTROKE = 0.08
        // Of the requests whose text changed before their answer, 78 to 89% were cancelled after the fixes (the rest
        // had something still waiting for them); before them, and with the fixes but no cancelling, none.
        const val MIN_CANCELLED_SHARE = 0.5
        const val MAX_HINTS_AT_PAUSE_MS = 1200L
        const val MAX_USAGES_AT_PAUSE_MS = 1200L
        const val MAX_COMPLETION_MS = 800L
        const val MAX_PARAMETER_INFO_MS = 1000L

        /** The [p]th of [sorted] (0 to 1), by the nearest rank below. */
        fun percentile(sorted: List<Long>, p: Double): Long? = sorted.getOrNull(((sorted.size - 1) * p).toInt())

        /** How long a version of the text must last to count as one the person paused at. */
        const val SETTLED_MS = 1000L
        /** How long the person looks at the result at the end. */
        const val SETTLE_MS = 3000L
        const val DAEMON = "daemon"
        val FEATURES = listOf(
            "highlighting (whole pass)" to DAEMON,
            "resolve (definition)" to "textDocument/definition",
            "inlay hints" to "textDocument/inlayHint",
            "usage highlighting" to "textDocument/documentHighlight",
            "parameter info" to "textDocument/signatureHelp",
            "completion" to "textDocument/completion",
            "hover" to "textDocument/hover",
            "references" to "textDocument/references",
        )
    }
}
