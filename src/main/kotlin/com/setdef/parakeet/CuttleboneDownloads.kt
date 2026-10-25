package com.setdef.parakeet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import com.intellij.util.system.CpuArch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * An OS and CPU as Cuttlebone's release manifest names them (docs/releases.md in Cuttlebone):
 * `macos`, `linux` or `freebsd`, and `arm64` or `amd64`. [osVersion] is checked against a build's `minimumOs`.
 */
data class CuttlebonePlatform(val os: String, val arch: String, val osVersion: String) {
    val description: String
        get() = "${OS_NAMES[os] ?: os} on ${if (arch == "arm64") "ARM64" else "x86-64"}"

    companion object {
        private val OS_NAMES = mapOf("macos" to "macOS", "linux" to "Linux", "freebsd" to "FreeBSD")

        /**
         * The platform for Java's `os.name` and `os.version`, and its CPU, or null where Inko doesn't run.
         * An Intel JVM on an Apple silicon Mac runs under Rosetta ([emulated]), but the ARM64 build runs natively there.
         */
        fun of(osName: String, osVersion: String, cpu: CpuArch, emulated: Boolean): CuttlebonePlatform? {
            val os = when {
                osName.startsWith("Mac") -> "macos"
                osName.startsWith("Linux") -> "linux"
                osName.startsWith("FreeBSD") -> "freebsd"
                else -> return null
            }
            val arch = when (cpu) {
                CpuArch.ARM64 -> "arm64"
                CpuArch.X86_64 -> if (os == "macos" && emulated) "arm64" else "amd64"
                else -> return null
            }
            return CuttlebonePlatform(os, arch, osVersion)
        }

        val current: CuttlebonePlatform?
            get() = of(System.getProperty("os.name").orEmpty(), System.getProperty("os.version").orEmpty(), CpuArch.CURRENT, CpuArch.isEmulated())

        /** Whether [version] (`15.1`, or a Linux kernel's `6.8.0-45-generic`) is [minimum] or newer. */
        fun meets(version: String, minimum: String): Boolean {
            val numeric = Regex("""^\d+(\.\d+)*""").find(version)?.value ?: return true
            return Ivm.isAtLeast(numeric, minimum)
        }
    }
}

/** One build in a release's manifest.json. */
data class CuttleboneBuild(
    val cuttlebone: String,
    val inko: String,
    val target: String,
    val os: String,
    val arch: String,
    val minimumOs: String?,
    val url: String,
    val size: Long,
    val sha256: String,
    val binary: String,
) {
    /** Where Parakeet installs it: one directory per Cuttlebone and Inko version. */
    val directoryName: String get() = CuttleboneDownloads.directoryName(cuttlebone, inko)
}

object CuttleboneManifest {
    const val SCHEMA = 1

    /** The builds a manifest lists. Refuses a schema other than [SCHEMA], as docs/releases.md asks. */
    fun parse(json: String): List<CuttleboneBuild> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IOException("the manifest isn't JSON (${e.message})")
        }
        val schema = root.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt
        if (schema != SCHEMA) throw IOException("the manifest's schema is $schema, which this Parakeet doesn't know (it reads schema $SCHEMA): a newer Parakeet may")
        val version = root.string("cuttlebone") ?: throw IOException("the manifest has no cuttlebone version")
        return root.getAsJsonArray("builds")?.mapNotNull { element ->
            val build = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            CuttleboneBuild(
                cuttlebone = version,
                inko = build.string("inko") ?: return@mapNotNull null,
                target = build.string("target").orEmpty(),
                os = build.string("os") ?: return@mapNotNull null,
                arch = build.string("arch") ?: return@mapNotNull null,
                minimumOs = build.string("minimumOs"),
                url = build.string("url") ?: return@mapNotNull null,
                size = build.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null,
                sha256 = build.string("sha256")?.lowercase() ?: return@mapNotNull null,
                binary = build.string("binary") ?: return@mapNotNull null,
            )
        }.orEmpty()
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

/** What looking for a build for an Inko version and platform found. */
sealed class CuttleboneLookup {
    data class Found(val build: CuttleboneBuild) : CuttleboneLookup()

    /** No release has a build for this Inko on this platform; [latest] is the newest release's version. */
    data class NoBuild(val inko: String, val platform: CuttlebonePlatform, val latest: String?) : CuttleboneLookup()

    /** The newest build for this Inko and platform needs a newer OS. */
    data class NeedsNewerOs(val build: CuttleboneBuild, val platform: CuttlebonePlatform) : CuttleboneLookup()

    /** Couldn't ask (offline, say): [reason] says why. */
    data class Failed(val reason: String) : CuttleboneLookup()

    val message: String
        get() = when (this) {
            is Found -> "Parakeet can download Cuttlebone ${build.cuttlebone} for Inko ${build.inko} " +
                "(${CuttlebonePlatform(build.os, build.arch, "").description}, ${megabytes(build.size)}) from its GitHub releases."
            is NoBuild -> "No release of Cuttlebone has a build for Inko $inko on ${platform.description}" +
                (latest?.let { " (the newest, $it, has builds for other Inko versions)" } ?: "") +
                ", so Parakeet can't download one. Use an Inko version a release has a build for, or build Cuttlebone from source."
            is NeedsNewerOs -> "Cuttlebone ${build.cuttlebone}'s build for Inko ${build.inko} on ${platform.description} needs version " +
                "${build.minimumOs} or newer, and this is ${platform.osVersion}, so Parakeet can't download it. Build Cuttlebone from source instead."
            is Failed -> "Parakeet couldn't look for a Cuttlebone to download: $reason"
        }

    private fun megabytes(bytes: Long) = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
}

/**
 * Where Cuttlebone's releases are listed: the latest release's manifest, its index of every release's Inko
 * versions (from Cuttlebone 0.11.0), and GitHub's releases API, for releases before there was an index: of
 * those, only the ones with an archive for the Inko version have their manifest fetched.
 */
open class CuttleboneReleases(private val latestManifest: String, private val latestIndex: String, private val releasesApi: String) {
    /** The newest build for [inko] on [platform]: the latest release's, else the newest older release's that has one. */
    open fun find(inko: String, platform: CuttlebonePlatform, indicator: ProgressIndicator?): CuttleboneLookup {
        val latest = try {
            CuttleboneManifest.parse(fetch(latestManifest, indicator))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IOException) {
            return CuttleboneLookup.Failed(describe(latestManifest, e))
        }
        val latestVersion = latest.firstOrNull()?.cuttlebone
        pick(latest, inko, platform)?.let { return it }
        val older = try {
            indexedManifests(inko, latestVersion, indicator) ?: olderManifests(inko, latestVersion, indicator)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: IOException) {
            return CuttleboneLookup.Failed(describe(releasesApi, e))
        }
        for (manifest in older) {
            val builds = try {
                CuttleboneManifest.parse(fetch(manifest, indicator))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: IOException) {
                LOG.info("skipping ${describe(manifest, e)}")
                continue
            }
            pick(builds, inko, platform)?.let { return it }
        }
        return CuttleboneLookup.NoBuild(inko, platform, latestVersion)
    }

    /**
     * The manifests of releases other than [latest] that the release index says have builds for [inko], newest
     * first; null when there's no index (before Cuttlebone 0.11.0), or it's in a schema this Parakeet doesn't read.
     */
    private fun indexedManifests(inko: String, latest: String?, indicator: ProgressIndicator?): List<String>? {
        val index = try {
            JsonParser.parseString(fetch(latestIndex, indicator)).asJsonObject
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.info("no release index: ${if (e is IOException) describe(latestIndex, e) else e.toString()}")
            return null
        }
        val schema = index.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt
        if (schema != 1) {
            LOG.info("the release index's schema is $schema, which this Parakeet doesn't read")
            return null
        }
        val releases = index.get("releases")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return releases.mapNotNull { element ->
            val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val version = release.get("cuttlebone")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val inkos = release.get("inko")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
            if (version == latest || inko !in inkos) return@mapNotNull null
            release.get("manifest")?.takeIf { it.isJsonPrimitive }?.asString?.let { version to it }
        }.sortedWith(compareBy(VERSIONS) { it.first }).map { it.second }
    }

    private fun pick(builds: List<CuttleboneBuild>, inko: String, platform: CuttlebonePlatform): CuttleboneLookup? {
        val build = builds.firstOrNull { it.inko == inko && it.os == platform.os && it.arch == platform.arch } ?: return null
        val minimum = build.minimumOs
        if (minimum != null && !CuttlebonePlatform.meets(platform.osVersion, minimum)) return CuttleboneLookup.NeedsNewerOs(build, platform)
        return CuttleboneLookup.Found(build)
    }

    /** The manifests of releases other than [latest] with an archive for [inko], newest first. */
    private fun olderManifests(inko: String, latest: String?, indicator: ProgressIndicator?): List<String> {
        val releases = try {
            JsonParser.parseString(fetch(releasesApi, indicator, "application/vnd.github+json")).asJsonArray
        } catch (e: IllegalStateException) {
            throw IOException("the releases list isn't a list")
        } catch (e: com.google.gson.JsonParseException) {
            throw IOException("the releases list isn't JSON")
        }
        return releases.mapNotNull { element ->
            val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (release.get("draft")?.asBoolean == true || release.get("prerelease")?.asBoolean == true) return@mapNotNull null
            val version = release.get("tag_name")?.asString?.removePrefix("v") ?: return@mapNotNull null
            if (version == latest) return@mapNotNull null
            val assets = release.getAsJsonArray("assets")?.mapNotNull { it.asJsonObject } ?: return@mapNotNull null
            val names = assets.mapNotNull { it.get("name")?.asString }
            if (names.none { it.startsWith("cuttlebone-$version-inko-$inko-") }) return@mapNotNull null
            val manifest = assets.firstOrNull { it.get("name")?.asString == "manifest.json" }?.get("browser_download_url")?.asString
            manifest?.let { version to it }
        }.sortedWith(compareBy(VERSIONS) { it.first }).map { it.second }
    }

    protected open fun fetch(url: String, indicator: ProgressIndicator?, accept: String = "application/json"): String =
        HttpRequests.request(url).productNameAsUserAgent().accept(accept)
            .connectTimeout(TIMEOUT_MS).readTimeout(TIMEOUT_MS)
            .readString(indicator)

    companion object {
        private val LOG = logger<CuttleboneReleases>()
        const val TIMEOUT_MS = 15_000
        private val VERSIONS = Comparator<String> { a, b -> if (a == b) 0 else if (Ivm.isAtLeast(a, b)) -1 else 1 }

        val GITHUB = CuttleboneReleases(
            "https://github.com/setdef/Cuttlebone/releases/latest/download/manifest.json",
            "https://github.com/setdef/Cuttlebone/releases/latest/download/index.json",
            "https://api.github.com/repos/setdef/Cuttlebone/releases?per_page=100",
        )

        fun describe(url: String, e: IOException): String {
            val reason = (e as? HttpRequests.HttpStatusException)?.let { "HTTP ${it.statusCode}" } ?: e.message ?: e.javaClass.simpleName
            return "couldn't get $url ($reason)."
        }
    }
}

/**
 * Where Parakeet finds and keeps Cuttlebone, as a service so tests can substitute their own: `cuttlebone` on PATH
 * (or where `cargo install` puts it), the releases to download from, and the directory downloads go in.
 */
open class CuttleboneLocations {
    /** `cuttlebone` on PATH, else in Cargo's bin directory. */
    open fun installed(): Cuttlebone.Located? {
        PathEnvironmentVariableUtil.findInPath("cuttlebone")?.toPath()?.let { return Cuttlebone.Located(it, Cuttlebone.Source.PATH) }
        val cargo = System.getProperty("user.home")?.let { Path.of(it, ".cargo", "bin", "cuttlebone") }?.takeIf(Files::isExecutable)
        return cargo?.let { Cuttlebone.Located(it, Cuttlebone.Source.CARGO) }
    }

    /** Shared by the user's JetBrains IDEs, and kept across their updates, unlike each IDE's own directories. */
    open val downloads: Path get() = PathManager.getCommonDataPath().resolve("parakeet").resolve("cuttlebone")

    open val releases: CuttleboneReleases get() = CuttleboneReleases.GITHUB

    open val platform: CuttlebonePlatform? get() = CuttlebonePlatform.current

    companion object {
        fun getInstance(): CuttleboneLocations = service()
    }
}

/** A Cuttlebone Parakeet downloaded. */
data class CuttleboneInstall(val cuttlebone: String, val inko: String, val executable: Path)

/**
 * Downloading Cuttlebone: only when the user clicks to, from a notification offering the build for their
 * Inko and platform (and once, a newer one). Each build is checked against its manifest (size and SHA-256),
 * unpacked, and run (`--version` must name its Inko) in a temporary directory beside the installed ones, then
 * moved into place in one step, so a failed or partial download is never found.
 */
object CuttleboneDownloads {
    private val LOG = logger<CuttleboneDownloads>()
    private val DIRECTORY = Regex("""cuttlebone-(.+?)-inko-(.+)""")

    fun directoryName(cuttlebone: String, inko: String) = "cuttlebone-$cuttlebone-inko-$inko"

    /** The newest Cuttlebone downloaded for [inko]. */
    fun installed(inko: String, root: Path = CuttleboneLocations.getInstance().downloads): CuttleboneInstall? =
        all(root).filter { it.inko == inko }.maxWithOrNull { a, b -> if (a.cuttlebone == b.cuttlebone) 0 else if (Ivm.isAtLeast(a.cuttlebone, b.cuttlebone)) 1 else -1 }

    /** Every downloaded Cuttlebone. Temporary directories start with a dot, so a download in progress isn't one. */
    fun all(root: Path = CuttleboneLocations.getInstance().downloads): List<CuttleboneInstall> {
        if (!root.isDirectory()) return emptyList()
        return try {
            root.listDirectoryEntries().mapNotNull { dir ->
                val match = DIRECTORY.matchEntire(dir.name) ?: return@mapNotNull null
                val executable = dir.resolve("cuttlebone").takeIf(Files::isExecutable) ?: return@mapNotNull null
                CuttleboneInstall(match.groupValues[1], match.groupValues[2], executable)
            }
        } catch (e: IOException) {
            LOG.info("couldn't list $root", e)
            emptyList()
        }
    }

    /**
     * Downloads [build] through IntelliJ's HTTP support (so the IDE's proxy settings apply), checks it, and installs
     * it under [root]. Throws [IOException] saying what went wrong, having installed nothing.
     */
    fun install(build: CuttleboneBuild, indicator: ProgressIndicator, root: Path = CuttleboneLocations.getInstance().downloads): CuttleboneInstall {
        Files.createDirectories(root)
        removeAbandoned(root)
        val target = root.resolve(build.directoryName)
        val work = Files.createTempDirectory(root, ".download-")
        try {
            val archive = work.resolve("archive.tar.gz")
            indicator.text = "Downloading Cuttlebone ${build.cuttlebone} for Inko ${build.inko}"
            try {
                HttpRequests.request(build.url).productNameAsUserAgent()
                    .connectTimeout(CuttleboneReleases.TIMEOUT_MS).readTimeout(CuttleboneReleases.TIMEOUT_MS)
                    .connect { request ->
                        val length = request.connection.contentLengthLong
                        if (length >= 0 && length != build.size) throw IOException("the server is sending $length bytes, but the manifest says ${build.size}.")
                        request.saveToFile(archive, indicator)
                    }
            } catch (e: HttpRequests.HttpStatusException) {
                throw IOException("couldn't download ${build.url} (HTTP ${e.statusCode}).")
            }
            indicator.checkCanceled()
            indicator.text = "Checking Cuttlebone ${build.cuttlebone}"
            val size = Files.size(archive)
            if (size != build.size) throw IOException("the download was $size bytes, but the manifest says ${build.size}: it was cut short or changed.")
            val sha256 = sha256(archive)
            if (sha256 != build.sha256) throw IOException("the download's SHA-256 is $sha256, but the manifest says ${build.sha256}: it was corrupted or changed.")
            val unpacked = work.resolve("unpacked")
            try {
                Decompressor.Tar(archive).extract(unpacked)
            } catch (e: IOException) {
                throw IOException("couldn't unpack the download (${e.message}).")
            }
            val binary = unpacked.resolve(build.binary).normalize()
            if (!binary.startsWith(unpacked) || !Files.isRegularFile(binary)) throw IOException("the download has no ${build.binary}.")
            if (!binary.toFile().setExecutable(true)) throw IOException("couldn't make ${binary.fileName} executable.")
            indicator.checkCanceled()
            verify(binary, build)
            // The archive's directory holds the server and its licenses: that's what's installed.
            val contents = binary.parent
            val staged = work.resolve(build.directoryName)
            Files.move(contents, staged)
            if (staged.resolve("cuttlebone") != staged.resolve(binary.fileName)) Files.move(staged.resolve(binary.fileName), staged.resolve("cuttlebone"))
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // Installed meanwhile (by another IDE, say): that one is as good.
                if (!Files.isExecutable(target.resolve("cuttlebone"))) throw IOException("couldn't install it in $target (${e.message}).")
            }
            LOG.info("installed Cuttlebone ${build.cuttlebone} for Inko ${build.inko} in $target")
            return CuttleboneInstall(build.cuttlebone, build.inko, target.resolve("cuttlebone"))
        } finally {
            try {
                NioFiles.deleteRecursively(work)
            } catch (e: IOException) {
                LOG.info("couldn't delete $work", e)
            }
        }
    }

    /**
     * Deletes what downloads left behind when the IDE stopped mid-way (a download cleans up after itself otherwise):
     * those a day old, since a newer one may be another IDE's, downloading now.
     */
    private fun removeAbandoned(root: Path) {
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        try {
            for (dir in root.listDirectoryEntries(".download-*")) {
                if (Files.getLastModifiedTime(dir).toMillis() < dayAgo) NioFiles.deleteRecursively(dir)
            }
        } catch (e: IOException) {
            LOG.info("couldn't remove abandoned downloads in $root", e)
        }
    }

    /** The build must run, and say it links the Inko it's for. */
    private fun verify(binary: Path, build: CuttleboneBuild) {
        val output = try {
            CapturingProcessHandler(GeneralCommandLine(binary.toString(), "--version")).runProcess(10_000)
        } catch (e: Exception) {
            throw IOException("the downloaded cuttlebone doesn't run (${e.message}).")
        }
        if (output.exitCode != 0 || output.isTimeout) throw IOException("the downloaded cuttlebone doesn't run (${output.stderr.trim().ifEmpty { "exit code ${output.exitCode}" }}).")
        val linked = Cuttlebone.linkedInko(output.stdout)
        if (linked != build.inko) throw IOException("the downloaded cuttlebone says it's \"${output.stdout.trim()}\", not a build for Inko ${build.inko}.")
    }

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Looks up the build for [inko] in the background, then calls [then] with what it found. */
    fun lookUp(inko: String, then: (CuttleboneLookup) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val locations = CuttleboneLocations.getInstance()
            val platform = locations.platform
            val lookup = if (platform == null) {
                CuttleboneLookup.Failed("Cuttlebone has builds for macOS and Linux only, on ARM64 and x86-64 (${SystemInfo.OS_NAME}, ${CpuArch.CURRENT}).")
            } else {
                try {
                    locations.releases.find(inko, platform, null)
                } catch (e: Exception) {
                    if (e is ProcessCanceledException) return@executeOnPooledThread
                    LOG.info("looking for a Cuttlebone to download failed", e)
                    CuttleboneLookup.Failed(e.message ?: e.toString())
                }
            }
            then(lookup)
        }
    }

    /**
     * Says once that a newer Cuttlebone than [installed] is available for its Inko (checked once per Inko version
     * each session, and told once per new version), offering to download it.
     */
    fun offerUpdate(project: Project, installed: CuttleboneInstall) {
        if (checkedForUpdates.putIfAbsent(installed.inko, true) != null) return
        lookUp(installed.inko) { lookup ->
            val build = (lookup as? CuttleboneLookup.Found)?.build ?: return@lookUp
            if (build.cuttlebone == installed.cuttlebone || !Ivm.isAtLeast(build.cuttlebone, installed.cuttlebone)) return@lookUp
            val told = "${build.cuttlebone} for Inko ${build.inko}"
            val properties = PropertiesComponent.getInstance()
            if (properties.getValue(TOLD_UPDATE) == told || project.isDisposed) return@lookUp
            properties.setValue(TOLD_UPDATE, told)
            val notification = group().createNotification(
                "Cuttlebone ${build.cuttlebone} is available",
                "A newer Cuttlebone for Inko ${build.inko} is available: ${build.cuttlebone}, where Parakeet uses ${installed.cuttlebone}. " +
                    "Parakeet doesn't install it unless you ask.",
                NotificationType.INFORMATION,
            )
            notification.addAction(downloadAction(project, build, "Download Cuttlebone ${build.cuttlebone}"))
            notification.notify(project)
        }
    }

    /** The Inko versions checked for updates this session: once each. */
    private val checkedForUpdates = ConcurrentHashMap<String, Boolean>()

    private const val TOLD_UPDATE = "parakeet.cuttlebone.toldUpdate"

    /** Forgets which updates were checked for and told, for tests. */
    @org.jetbrains.annotations.TestOnly
    internal fun forgetUpdates() {
        forgetUpdatesThisSession()
        PropertiesComponent.getInstance().unsetValue(TOLD_UPDATE)
    }

    /** Forgets which updates were checked for, as a new session would, for tests. */
    @org.jetbrains.annotations.TestOnly
    internal fun forgetUpdatesThisSession() = checkedForUpdates.clear()

    fun downloadAction(project: Project, build: CuttleboneBuild, text: String = "Download Cuttlebone ${build.cuttlebone}"): NotificationAction =
        NotificationAction.create(text) { _, notification ->
            notification.expire()
            download(project, build)
        }

    /** Downloads and installs [build] in the background, with progress and cancellation, then starts it where it's wanted. */
    fun download(project: Project, build: CuttleboneBuild, onFinished: (Result<CuttleboneInstall>?) -> Unit = {}) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Cuttlebone ${build.cuttlebone}", true) {
            @Volatile private var result: Result<CuttleboneInstall>? = null

            override fun run(indicator: ProgressIndicator) {
                result = try {
                    Result.success(install(build, indicator))
                } catch (e: IOException) {
                    LOG.info("downloading Cuttlebone ${build.cuttlebone} failed", e)
                    Result.failure(e)
                }
            }

            override fun onSuccess() {
                val result = result
                val installed = result?.getOrNull()
                if (installed != null) {
                    group().createNotification(
                        "Cuttlebone ${installed.cuttlebone} is installed",
                        "Parakeet downloaded Cuttlebone ${installed.cuttlebone} for Inko ${installed.inko}, and uses it unless the settings name another, or there's one on PATH.",
                        NotificationType.INFORMATION,
                    ).notify(project)
                    started()
                } else if (result != null) {
                    val notification = group().createNotification(
                        "Couldn't download Cuttlebone",
                        "Cuttlebone ${build.cuttlebone} wasn't installed: ${result.exceptionOrNull()?.message}",
                        NotificationType.ERROR,
                    )
                    notification.addAction(downloadAction(project, build, "Try Again"))
                    notification.notify(project)
                }
                onFinished(result)
            }

            override fun onCancel() = onFinished(null)
        })
    }

    /** A Cuttlebone was installed: each project that has none, or uses a download, checks again. */
    private fun started() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val service = CuttleboneService.getInstance(project)
            if (service.status is Cuttlebone.Status.NotFound || service.located?.source == Cuttlebone.Source.DOWNLOADED) service.settingsChanged()
        }
    }

    fun group() = NotificationGroupManager.getInstance().getNotificationGroup(Inko.NOTIFICATION_GROUP)
}

/** Coming back to the IDE (after running `ivm` in a terminal, say), each project checks whether its Inko changed. */
class CuttleboneInkoWatcher : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) CuttleboneService.getInstance(project).inkoMayHaveChanged()
        }
    }
}
