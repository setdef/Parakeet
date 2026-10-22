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
        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("com.intellij.modules.ultimate")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // Oldest supported (since-build 252) and the version we build against.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
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
