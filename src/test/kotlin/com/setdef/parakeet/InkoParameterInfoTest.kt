package com.setdef.parakeet

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import java.awt.Color
import java.lang.reflect.Proxy

/** Parameter info (⌘P), with inko doc output supplied by a fake. */
class InkoParameterInfoTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        val util = "type pub Helper {\n  let pub @count: Int\n  let pub @name: String\n\n  fn pub static new(count: Int, name: String) -> Helper {\n    Helper(count: count, name: name)\n  }\n}\n\nfn pub greet(name: String, times: Array[Int]) {}\n"
        myFixture.addFileToProject("src/app/util.inko", util)
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val file = mirror.resolve("src/app/util.inko")
            listOf(FakeInkoDoc.module(
                "app.util", file,
                methods = listOf(FakeInkoDoc.member("greet", file, 10, 1, signature = "fn pub greet(name: String, times: Array[Int])")),
                types = listOf(FakeInkoDoc.type(
                    "Helper", 1,
                    fields = listOf(
                        FakeInkoDoc.member("count", file, 2, signature = "let pub @count: Int"),
                        FakeInkoDoc.member("name", file, 3, signature = "let pub @name: String"),
                    ),
                    staticMethods = listOf(FakeInkoDoc.member("new", file, 5, signature = "fn pub static new(count: Int, name: String) -> Helper")),
                )),
            ))
        })
    }

    /** A stand-in for the IDE's contexts: getters answer from [values], setters record into it. */
    private inline fun <reified T> context(values: MutableMap<String, Any?>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when {
                method.name.startsWith("set") && args?.size == 1 -> { values[method.name.removePrefix("set").replaceFirstChar(Char::lowercase)] = args[0]; null }
                method.name.startsWith("get") -> values[method.name.removePrefix("get").replaceFirstChar(Char::lowercase)]
                method.name.startsWith("is") -> values[method.name.removePrefix("is").replaceFirstChar(Char::lowercase)] ?: false
                method.name == "setupUIComponentPresentation" -> { values["shown"] = Triple(args!![0], args[1], args[2]); args[0] }
                else -> values[method.name]
            }
        } as T

    /** The parameter info shown at <caret>: each candidate's text, with the current parameter in [brackets]. */
    private fun info(text: String): List<String> {
        val offset = text.indexOf("<caret>")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("src/main.inko", text.replace("<caret>", "")).virtualFile)
        val handler = InkoParameterInfoHandler()
        val created = mutableMapOf<String, Any?>("file" to myFixture.file, "editor" to myFixture.editor, "offset" to offset, "project" to project)
        val owner = handler.findElementForParameterInfo(context<CreateParameterInfoContext>(created)) ?: return emptyList()
        val updated = mutableMapOf<String, Any?>("file" to myFixture.file, "editor" to myFixture.editor, "offset" to offset, "parameterOwner" to owner)
        handler.updateParameterInfo(owner, context<UpdateParameterInfoContext>(updated))
        @Suppress("UNCHECKED_CAST")
        return (created["itemsToShow"] as Array<Any>).map { item ->
            val ui = mutableMapOf<String, Any?>("parameterOwner" to owner, "currentParameterIndex" to updated["currentParameter"], "defaultParameterColor" to Color.WHITE)
            handler.updateUI(item as InkoDefinition, context<ParameterInfoUIContext>(ui))
            val (shown, start, end) = ui["shown"] as Triple<*, *, *>
            val s = shown as String
            if ((start as Int) < 0) s else s.substring(0, start) + "[" + s.substring(start, end as Int) + "]" + s.substring(end)
        }
    }

    fun testHighlightsTheCurrentArgument() {
        assertEquals(listOf("[name: String], times: Array[Int]"), info("import app.util (greet)\n\nfn a {\n  greet(<caret>)\n}\n"))
        assertEquals(listOf("name: String, [times: Array[Int]]"), info("import app.util (greet)\n\nfn a {\n  greet('x', <caret>[1, 2])\n}\n"))
    }

    fun testCommasInNestedCallsDoNotCount() {
        assertEquals(listOf("name: String, [times: Array[Int]]"), info("import app.util (greet)\n\nfn a {\n  greet(f(1, 2), <caret>)\n}\n"))
    }

    fun testInsideAnArrayArgumentShowsTheEnclosingCall() {
        assertEquals(listOf("name: String, [times: Array[Int]]"), info("import app.util (greet)\n\nfn a {\n  greet('x', [1, <caret>])\n}\n"))
    }

    fun testStaticMethods() {
        assertEquals(listOf("count: Int, [name: String]"), info("import app.util (Helper)\n\nfn a {\n  Helper.new(1, <caret>)\n}\n"))
    }

    fun testConstructingATypeShowsItsFields() {
        assertEquals(listOf("[count: Int], name: String"), info("import app.util (Helper)\n\nfn a {\n  Helper(<caret>)\n}\n"))
    }

    fun testNothingOutsideACall() {
        assertEmpty(info("import app.util (greet)\n\nfn a {\n  greet<caret>\n}\n"))
        assertEmpty(info("fn a {\n  [1, <caret>]\n}\n"))
    }
}
