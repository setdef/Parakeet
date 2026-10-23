package com.setdef.parakeet

import com.intellij.psi.PsiManager

class InkoNewFileTest : InkoTestCase() {
    private fun create(name: String, template: String): String {
        val directory = PsiManager.getInstance(project).findDirectory(myFixture.tempDirFixture.findOrCreateDir("test"))!!
        val file = com.intellij.openapi.command.WriteCommandAction.writeCommandAction(project)
            .compute<com.intellij.psi.PsiFile?, RuntimeException> { Action().create(name, template, directory) }!!
        return "${file.name}:${file.text}"
    }

    fun testTestFilesGetTheTestPrefixAndATest() {
        val (name, text) = create("parser", InkoNewFileAction.TEST).split(":", limit = 2)
        assertEquals("test_parser.inko", name)
        assertTrue(text, text.startsWith("import std.test (Tests)\n\nfn pub tests(t: mut Tests) {"))
    }

    fun testProgramsGetAMain() = assertEquals("app.inko:type async Main {\n  fn async main {}\n}\n", create("app", InkoNewFileAction.PROGRAM))

    fun testModulesStartEmpty() = assertEquals("util.inko:", create("util.inko", InkoNewFileAction.MODULE))

    /** The action, with its (protected) file creation reachable. */
    private class Action : InkoNewFileAction() {
        fun create(name: String, template: String, directory: com.intellij.psi.PsiDirectory) = createFile(name, template, directory)
    }
}
