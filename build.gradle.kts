import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
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
        testFramework(TestFrameworkType.Platform)
        // Grazie provides the spell-checking inspection in 2026.x.
        testBundledPlugins("com.intellij.modules.ultimate", "tanvd.grazi")
        testBundledModules("intellij.spellchecker")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
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
        ides {
            // Oldest supported (since-build 252) and the version we build against.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// The custom plugin repository file attached to each GitHub Release. IDEs that add
// https://github.com/<repository>/releases/latest/download/updatePlugins.xml under
// Settings | Plugins | Manage Plugin Repositories can install the plugin and get updates.
val updatePluginsXml by tasks.registering {
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

// `./gradlew test -PunitOnly` skips tests that need a real `inko` installed.
tasks.test {
    if (providers.gradleProperty("unitOnly").isPresent) exclude("**/*IntegrationTest*")
}

// TextMate bundles must be plain files on disk, so ship them beside the plugin's jars.
tasks.prepareSandbox {
    from(layout.projectDirectory.dir("bundles")) {
        into(intellijPlatform.projectName.map { "$it/bundles" })
    }
}

// Tests load the plugin from the sandbox, so they need the bundles there too.
tasks.prepareTestSandbox {
    from(layout.projectDirectory.dir("bundles")) {
        into(intellijPlatform.projectName.map { "$it/bundles" })
    }
}
