package com.setdef.parakeet

import com.google.gson.GsonBuilder
import com.intellij.openapi.progress.ProgressIndicator
import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

/**
 * Cuttlebone's GitHub releases, served on 127.0.0.1: each release's manifest.json and archives, the latest
 * release's manifest, and the releases API's list. Tests change what's served ([respond]) to break it.
 */
class CuttleboneReleaseServer : AutoCloseable {
    /** A response: [status], [body], and how many bytes of it to send before cutting the connection ([cutAt]). */
    data class Response(val status: Int, val body: ByteArray, val contentType: String = "application/octet-stream", val cutAt: Int? = null)

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val routes = mutableMapOf<String, Response>()
    val requests: MutableList<String> = CopyOnWriteArrayList()
    private val releases = mutableListOf<Release>()

    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val latestManifest: String get() = "$base/releases/latest/download/manifest.json"
    val releasesApi: String get() = "$base/api/releases"
    val releasesSource: CuttleboneReleases get() = CuttleboneReleases(latestManifest, releasesApi)

    class Release(val version: String, val builds: List<Pair<CuttleboneBuild, ByteArray>>)

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += path
            val response = synchronized(routes) { routes[path] } ?: Response(404, "Not Found".toByteArray(), "text/plain")
            exchange.responseHeaders.add("Content-Type", response.contentType)
            try {
                exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                exchange.responseBody.write(response.body, 0, response.cutAt ?: response.body.size)
                exchange.responseBody.flush()
            } catch (e: IOException) {
                // The client went away.
            }
            // Short of its length (cutAt), closing drops the connection.
            try {
                exchange.close()
            } catch (e: IOException) {
                // As it should.
            }
        }
        server.start()
    }

    fun respond(path: String, response: Response) = synchronized(routes) { routes[path] = response }

    /**
     * Publishes a release of [version] with a build for each of [inkos] on [platforms], each an archive holding
     * [binary] (as `cuttlebone`) in the layout Cuttlebone's releases use. The newest published is the latest.
     */
    fun publish(
        version: String,
        inkos: List<String>,
        binary: ByteArray,
        platforms: List<CuttlebonePlatform> = listOf(MAC_ARM),
        /** The oldest OS each platform's build runs on, as Cuttlebone's releases have it. */
        minimumOs: (CuttlebonePlatform) -> String = { if (it.os == "macos") "15.0" else "4.11" },
    ): Release {
        val builds = inkos.flatMap { inko ->
            platforms.map { platform ->
                val target = when (platform.os to platform.arch) {
                    "macos" to "arm64" -> "aarch64-apple-darwin"
                    "macos" to "amd64" -> "x86_64-apple-darwin"
                    "linux" to "arm64" -> "aarch64-unknown-linux-musl"
                    else -> "x86_64-unknown-linux-musl"
                }
                val name = "cuttlebone-$version-inko-$inko-$target"
                val archive = archive(name, binary)
                val build = CuttleboneBuild(
                    version, inko, target, platform.os, platform.arch, minimumOs(platform),
                    "$base/releases/download/v$version/$name.tar.gz", archive.size.toLong(), sha256(archive), "$name/cuttlebone",
                )
                respond("/releases/download/v$version/$name.tar.gz", Response(200, archive))
                build to archive
            }
        }
        val release = Release(version, builds)
        releases += release
        val manifest = manifest(version, builds.map { it.first })
        respond("/releases/download/v$version/manifest.json", manifest)
        respond("/releases/latest/download/manifest.json", manifest)
        respondReleases()
        return release
    }

    private fun respondReleases() {
        val list = releases.reversed().map { release ->
            mapOf(
                "tag_name" to "v${release.version}",
                "draft" to false,
                "prerelease" to false,
                "assets" to release.builds.map { (build, _) ->
                    mapOf("name" to build.url.substringAfterLast('/'), "browser_download_url" to build.url)
                } + mapOf("name" to "manifest.json", "browser_download_url" to "$base/releases/download/v${release.version}/manifest.json"),
            )
        }
        respond("/api/releases", Response(200, GSON.toJson(list).toByteArray(), "application/json"))
    }

    override fun close() = server.stop(0)

    companion object {
        private val GSON = GsonBuilder().serializeNulls().create()
        val MAC_ARM = CuttlebonePlatform("macos", "arm64", "15.1")
        val LINUX_AMD = CuttlebonePlatform("linux", "amd64", "6.8.0-45-generic")

        fun manifest(version: String, builds: List<CuttleboneBuild>, schema: Int = 1): Response = Response(
            200,
            GSON.toJson(mapOf(
                "schema" to schema,
                "cuttlebone" to version,
                "builds" to builds.map {
                    mapOf(
                        "inko" to it.inko, "target" to it.target, "os" to it.os, "arch" to it.arch, "minimumOs" to it.minimumOs,
                        "url" to it.url, "size" to it.size, "sha256" to it.sha256, "binary" to it.binary,
                    )
                },
            )).toByteArray(),
            "application/json",
        )

        /** A `.tar.gz` like a release's: one directory, [name], with the server (not executable, as a careless archive's might be) and its papers. */
        fun archive(name: String, binary: ByteArray): ByteArray {
            val bytes = ByteArrayOutputStream()
            TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("$name/"))
                tar.closeArchiveEntry()
                for ((file, content) in listOf("cuttlebone" to binary, "README.md" to "Cuttlebone\n".toByteArray(), "LICENSE-MPL-2.0" to "MPL\n".toByteArray())) {
                    val entry = TarArchiveEntry("$name/$file")
                    entry.size = content.size.toLong()
                    entry.mode = "644".toInt(8)
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
            return bytes.toByteArray()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A `cuttlebone` that only says its version, for tests that don't start it. */
        fun versionOnly(version: String, inko: String): ByteArray =
            "#!/bin/sh\necho 'cuttlebone $version (Inko $inko)'\n".toByteArray()
    }
}

/** Where tests find Cuttlebone: never the network, and downloads in a directory of the test's own. */
class TestCuttleboneLocations(
    override val downloads: Path,
    @Volatile var releasesSource: CuttleboneReleases = OFFLINE,
    @Volatile var platformOverride: CuttlebonePlatform? = CuttleboneReleaseServer.MAC_ARM,
    /** What PATH has; by default the real PATH, which Cuttlebone's integration tests use. */
    @Volatile var onPath: (() -> Cuttlebone.Located?)? = null,
) : CuttleboneLocations() {
    override fun installed(): Cuttlebone.Located? = onPath.let { if (it == null) super.installed() else it() }
    override val releases: CuttleboneReleases get() = releasesSource
    override val platform: CuttlebonePlatform? get() = platformOverride

    companion object {
        /** Releases that are never there: nothing in the tests reaches the network. */
        val OFFLINE = object : CuttleboneReleases("http://offline.invalid/manifest.json", "http://offline.invalid/releases") {
            override fun fetch(url: String, indicator: ProgressIndicator?, accept: String): String =
                throw IOException("tests never reach the network")
        }
    }
}
