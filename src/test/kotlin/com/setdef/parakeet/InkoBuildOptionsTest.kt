package com.setdef.parakeet

import java.nio.file.Files

/** --release, --target, and --static in run configurations. */
class InkoBuildOptionsTest : InkoTestCase() {
    private fun configuration(command: InkoCommand, release: Boolean = false, target: String? = null, static: Boolean = false): InkoRunConfiguration {
        val configuration = InkoRunConfigurationType.getInstance().configurationFactories[0].createTemplateConfiguration(project) as InkoRunConfiguration
        configuration.options.command = command.cli
        configuration.options.release = release
        configuration.options.compileTarget = target
        configuration.options.staticLinking = static
        return configuration
    }

    fun testEachCommandGetsOnlyTheOptionsItAccepts() {
        assertEquals(listOf("--release", "--static"), configuration(InkoCommand.RUN, true, "amd64-linux-gnu", true).buildFlags())
        assertEquals(listOf("--release", "--target", "amd64-linux-gnu", "--static"), configuration(InkoCommand.BUILD, true, "amd64-linux-gnu", true).buildFlags())
        assertEquals(listOf("--release", "--target", "amd64-linux-gnu"), configuration(InkoCommand.TEST, true, "amd64-linux-gnu", true).buildFlags())
        assertEquals(listOf("--target", "amd64-linux-gnu"), configuration(InkoCommand.CHECK, true, "amd64-linux-gnu", true).buildFlags())
        assertEmpty(configuration(InkoCommand.BUILD, target = " ").buildFlags())
    }

    fun testTheTestProgramIsWhereTheCompilerPutsIt() {
        val fake = FakeInkoCli { call -> if (call.args == listOf("print", "target")) FakeInkoCli.ok(stdout = "arm64-mac-native\n") else FakeInkoCli.ok() }
        assertEquals("build/debug/tests", InkoTestRuns.program(configuration(InkoCommand.TEST), fake))
        assertEquals("build/release/tests", InkoTestRuns.program(configuration(InkoCommand.TEST, release = true), fake))
        // The host's own target builds where no target would; others under their name.
        assertEquals("build/release/tests", InkoTestRuns.program(configuration(InkoCommand.TEST, true, "arm64-mac-native"), fake))
        assertEquals("build/amd64-linux-musl/debug/tests", InkoTestRuns.program(configuration(InkoCommand.TEST, target = "amd64-linux-musl"), fake))
    }

    fun testListsTargetsWithoutTheNativeMarker() {
        val fake = FakeInkoCli { FakeInkoCli.ok(stdout = "amd64-linux-gnu\narm64-mac-native (native)\n") }
        assertEquals(listOf("amd64-linux-gnu", "arm64-mac-native"), InkoTargets.list(fake))
    }

    fun testTheEditorSavesTheOptions() {
        val editor = InkoRunConfigurationEditor(project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, editor)
        editor.component
        val original = configuration(InkoCommand.BUILD, true, "amd64-linux-gnu", true)
        editor.resetFrom(original)
        val copy = configuration(InkoCommand.RUN)
        editor.applyTo(copy)
        assertEquals(listOf("--release", "--target", "amd64-linux-gnu", "--static"), copy.buildFlags())
    }

    fun testRerunsBuildWithTheOptions() {
        val dir = Files.createTempDirectory("rerun")
        val root = Files.createDirectories(dir.resolve("project"))
        val script = InkoTestReruns.script(listOf(InkoFailedTest("adds", "$root/test/test_a.inko")), root, listOf("--release"), "build/release/tests")
        assertTrue(script, script.contains("'build' '--release' '--include' 'test' '.parakeet/tests.inko'"))
        assertTrue(script, script.contains("\"\$mirror/build/release/tests\" 'adds'"))
    }
}
