import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

val TYPING_REPLAY = "CuttleboneTypingReplayIntegrationTest"
val REAL_WORLD = "RealWorld"

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")

    intellijPlatform {
        intellijIdea("2026.2.3")
        bundledPlugin("org.jetbrains.plugins.textmate")
        // Optional at runtime (see parakeet-spellchecker.xml).
        bundledModule("intellij.spellchecker")
        // The IDE's test runner (part of the platform in 2025.2, the bundled Test Runner plugin in 2026.x).
        bundledModule("intellij.platform.testRunner")
        bundledModule("intellij.platform.smRunner")
        testFramework(TestFrameworkType.Platform)
        // Grazie provides the spell-checking inspection in 2026.x.
        testBundledPlugins("com.intellij.modules.ultimate", "tanvd.grazi", "org.intellij.plugins.markdown")
        testBundledModules("intellij.spellchecker")
    }
}

intellijPlatform {
    pluginConfiguration {
        // The Marketplace's "What's New" for this version, from CHANGELOG.md.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            // 253 (2025.3) is the first release where every IntelliJ-based IDE has the LSP API (com.intellij.modules.lsp).
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    // For release.yml; locally these are unset and signing/publishing are skipped.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        // Problems in code that never runs on the IDE reporting them, each explained there.
        ignoredProblemsFile = layout.projectDirectory.file("verification/ignored-problems.txt")
        ides {
            // The oldest release sinceBuild (253) admits, and the version we build against.
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// The custom plugin repository file attached to each GitHub Release. IDEs that add
// https://github.com/<repository>/releases/latest/download/updatePlugins.xml under
// Settings | Plugins | Manage Plugin Repositories can install the plugin and get updates.
tasks.register("updatePluginsXml") {
    group = "intellij platform"
    description = "Writes updatePlugins.xml, describing the signed zip on this version's GitHub Release."
    val pluginXml = tasks.patchPluginXml.flatMap { it.outputFile }
    val signedZip = tasks.signPlugin.flatMap { it.signedArchiveFile }.map { it.asFile.name }
    val repository = providers.environmentVariable("GITHUB_REPOSITORY").orElse("setdef/Parakeet")
    val version = project.version.toString()
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    inputs.file(pluginXml)
    inputs.property("signedZip", signedZip)
    inputs.property("repository", repository)
    inputs.property("version", version)
    outputs.file(output)
    doLast {
        val xml = pluginXml.get().asFile.readText()
        fun element(name: String) = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("plugin.xml has no <$name>")
        fun content(name: String) = element(name).groupValues[1].trim()
        val ideaVersion = Regex("<idea-version[^>]*/>").find(xml)?.value ?: error("plugin.xml has no <idea-version>")
        val url = "https://github.com/${repository.get()}/releases/download/v$version/${signedZip.get()}"
        output.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="${content("id")}" url="$url" version="$version">
            |    $ideaVersion
            |    <name>${content("name")}</name>
            |    ${element("vendor").value}
            |    <description>${content("description")}</description>
            |    <change-notes>${content("change-notes")}</change-notes>
            |  </plugin>
            |</plugins>
            |""".trimMargin(),
        )
    }
}

// `./gradlew test -PunitOnly` skips tests that need a real `inko` installed, and
// `-PnoCuttlebone` the ones that need a real `cuttlebone` (scripts/check.sh decides).
// The real-world projects' tests only run with `-PrealWorld` (scripts/check.sh --full).
tasks.test {
    if (providers.gradleProperty("unitOnly").isPresent) exclude("**/*IntegrationTest*")
    if (providers.gradleProperty("noCuttlebone").isPresent) exclude("**/Cuttlebone*IntegrationTest*")
    if (!providers.gradleProperty("realWorld").isPresent) exclude("**/$REAL_WORLD*")
    providers.environmentVariable("PARAKEET_FIXTURES").orNull?.let { environment("PARAKEET_FIXTURES", it) }
    providers.environmentVariable("PARAKEET_SWEEP_ONLY").orNull?.let { environment("PARAKEET_SWEEP_ONLY", it) }
    providers.environmentVariable("PARAKEET_PROTOCOL").orNull?.let { environment("PARAKEET_PROTOCOL", it) }
    exclude("**/$TYPING_REPLAY*")
    // Where check.sh found cuttlebone; the tests otherwise look on PATH.
    providers.environmentVariable("CUTTLEBONE").orNull?.let { environment("CUTTLEBONE", it) }
}

// The typing replay types for minutes, against the real `cuttlebone`, with the daemon on its timer and
// completion popping up as in the IDE. It runs in an IDE of its own: in the tests' IDE, what it left running
// made a later test's teardown wait forever. scripts/check.sh runs it when there's a cuttlebone.
val typingReplay by intellijPlatformTesting.testIde.registering {
    task {
        description = "Replays typing against the real cuttlebone, and fails when typing responsiveness regresses."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        // The test task's, with the platform's test framework, which this task's own lacks: but with Parakeet as
        // this task builds it, not the test sandbox's copy, which nothing here rebuilds.
        dependsOn(tasks.prepareTestSandbox)
        classpath = tasks.test.get().classpath.filter { "/plugins-test/parakeet/lib/parakeet-" !in it.invariantSeparatorsPath } +
            files(tasks.named("composedJar"))
        // Named rather than found: the scan doesn't see the platform's test case classes on this classpath.
        isScanForTestClasses = false
        include("**/$TYPING_REPLAY.class")
        providers.environmentVariable("CUTTLEBONE").orNull?.let { environment("CUTTLEBONE", it) }
    }
}

// The plugin's version, for error reports (InkoErrorReportSubmitter), without IDE API: 2026.2
// marks every plugin lookup internal, and 2025.3 marks ErrorReportSubmitter.getPluginDescriptor override-only.
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("parakeet/build.properties") { expand("version" to version) }
}

// TextMate bundles must be plain files on disk, so ship them beside the plugin's jars.
tasks.prepareSandbox {
    from(layout.projectDirectory.dir("bundles")) {
        into(intellijPlatform.projectName.map { "$it/bundles" })
    }
}

// Tests load the plugin from the sandbox, so they need the bundles there too (the typing replay's has its own).
for (sandbox in listOf("prepareTestSandbox", "prepareSandbox_typingReplay")) {
    tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>(sandbox) {
        from(layout.projectDirectory.dir("bundles")) {
            into(intellijPlatform.projectName.map { "$it/bundles" })
        }
    }
}

// The test classes are compiled against the main classes, which reach compileTestKotlin only through its
// `libraries`, which Kotlin's classpath snapshots leave out of the task's inputs: so, by its cache key, the main
// classes may change without the test classes being compiled again. Here they're an input, by content.
tasks.named("compileTestKotlin") {
    inputs.files(sourceSets.main.map { it.output.classesDirs }).withPropertyName("mainClasses").withNormalizer(ClasspathNormalizer::class.java)
}

// compileTestKotlin's build cache key must change when the main classes do: otherwise, after a signature change
// in src/main, the cache (or the task being up to date) gave test classes compiled against the old one
// (NoSuchMethodError; SESSION-0.13.1-and-stale-answers.md). scripts/check.sh runs this first.
val checkTestCompileInputs by tasks.registering {
    group = "verification"
    description = "Fails unless compileTestKotlin's fingerprinted inputs include the main classes."
    val inputs = tasks.named("compileTestKotlin").map { task -> task.inputs.files.files.map { it.canonicalFile } }
    val main = sourceSets.main.map { set -> set.output.classesDirs.files.map { it.canonicalFile } }
    doLast {
        val missing = main.get().filter { dir -> inputs.get().none { it == dir || it.startsWith(dir) } }
        check(missing.isEmpty()) { "compileTestKotlin's inputs don't include $missing, so its cache key ignores changes there" }
    }
}
