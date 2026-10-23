package com.setdef.parakeet

import java.nio.file.Path

class InkoStdDocsTest : junit.framework.TestCase() {
    private fun definition(kind: InkoDefinitionKind, name: String, module: String, owner: String? = null) =
        InkoDefinition(kind, name, module, owner, Path.of("/std.inko"), 1, 1)

    fun testLinksStandardLibraryDefinitionsLikeIdoc() {
        val base = "https://docs.inko-lang.org/std/v0.21.1/module/std/net/socket/"
        assertEquals(base, InkoStdDocs.url(definition(InkoDefinitionKind.MODULE, "std.net.socket", "std.net.socket"), "0.21.1"))
        assertEquals("${base}Socket/", InkoStdDocs.url(definition(InkoDefinitionKind.TYPE, "Socket", "std.net.socket"), "0.21.1"))
        assertEquals("${base}Socket/#method.no_delay=", InkoStdDocs.url(definition(InkoDefinitionKind.INSTANCE_METHOD, "no_delay=", "std.net.socket", "Socket"), "0.21.1"))
        assertEquals("${base}Socket/#field.inner", InkoStdDocs.url(definition(InkoDefinitionKind.FIELD, "inner", "std.net.socket", "Socket"), "0.21.1"))
        assertEquals("https://docs.inko-lang.org/std/latest/module/std/base64/encode/", InkoStdDocs.url(definition(InkoDefinitionKind.MODULE_METHOD, "encode", "std.base64"), null))
    }

    fun testOnlyTheStandardLibraryIsPublished() = assertNull(InkoStdDocs.url(definition(InkoDefinitionKind.TYPE, "Helper", "app.util"), "0.21.1"))
}
