package com.setdef.parakeet

import junit.framework.TestCase
import java.nio.file.Path
import com.setdef.parakeet.InkoDefinitionKind as Kind

class InkoDocParserTest : TestCase() {
    private val json = """
        {"name": "app.util", "file": "/mirror/src/app/util.inko", "documentation": "",
         "constants": [{"name": "LIMIT", "location": {"lines": {"start": 1, "end": 1}, "columns": {"start": 1, "end": 9}}}],
         "methods": [{"name": "helper", "file": "/mirror/src/app/util.inko", "location": {"lines": {"start": 3, "end": 5}, "columns": {"start": 1, "end": 1}}}],
         "types": [{"name": "Helper", "location": {"lines": {"start": 7, "end": 9}, "columns": {"start": 1, "end": 1}},
                    "constructors": [], "fields": [{"name": "count", "location": {"lines": {"start": 8, "end": 8}, "columns": {"start": 3, "end": 9}}}],
                    "static_methods": [], "implemented_traits": [],
                    "instance_methods": [{"name": "to_string", "file": "/std/string.inko", "location": {"lines": {"start": 40, "end": 42}, "columns": {"start": 3, "end": 3}}}]}],
         "traits": [{"name": "Show", "location": {"lines": {"start": 11, "end": 13}, "columns": {"start": 1, "end": 1}},
                     "required_methods": [{"name": "show", "file": "/mirror/src/app/util.inko", "location": {"lines": {"start": 12, "end": 12}, "columns": {"start": 3, "end": 9}}}],
                     "default_methods": [], "implementations": []}]}
    """.trimIndent()

    private val definitions = InkoDocParser.parse(json) { Path.of(it.toString().replace("/mirror", "/project")) }

    private fun find(kind: Kind, name: String) = definitions.single { it.kind == kind && it.name == name }

    fun testReadsEveryKindOfDefinition() {
        assertEquals(
            listOf(Kind.MODULE, Kind.CONSTANT, Kind.MODULE_METHOD, Kind.TYPE, Kind.FIELD, Kind.INSTANCE_METHOD, Kind.TRAIT, Kind.INSTANCE_METHOD),
            definitions.map { it.kind },
        )
        assertEquals("Helper", find(Kind.FIELD, "count").owner)
        assertEquals("Show", find(Kind.INSTANCE_METHOD, "show").owner)
        assertEquals(8 to 3, find(Kind.FIELD, "count").let { it.line to it.column })
    }

    fun testMapsPathsOutOfTheMirror() {
        assertEquals(Path.of("/project/src/app/util.inko"), find(Kind.MODULE, "app.util").file)
    }

    fun testMethodsKeepTheirOwnFile() {
        assertEquals(Path.of("/std/string.inko"), find(Kind.INSTANCE_METHOD, "to_string").file)
    }

    fun testIgnoresNonModuleJson() {
        assertTrue(InkoDocParser.parse("""{"readme": "x"}""").isEmpty())
    }
}
