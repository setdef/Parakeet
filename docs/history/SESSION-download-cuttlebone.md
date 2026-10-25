# Session: downloading Cuttlebone, and 0.13.0

Parakeet offers to download Cuttlebone's prebuilt build for the user's
Inko, OS and CPU, now that Cuttlebone publishes them
([its docs/releases.md](https://github.com/setdef/Cuttlebone/blob/main/docs/releases.md)).
Version 0.13.0 is prepared on `main`, but not tagged, pushed or published.

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| fdb5451 | Steps 1 and 2: the download, and its tests (one commit: the tests were written beside the code) |
| dc1c048 | Step 3: Prepare 0.13.0 (version, changelog entry, README's setup) |
| 6845af3 | Older releases found through Cuttlebone's release index, which landed during the session, and a fix to a test that raced IntelliJ |
| c2b5552 | This report, and README's link to it |
| b4a034f | Four integration tests reach their states another way, since Cuttlebone 0.11.0 answers around a missing module ([below](#after-cuttlebone-0110)) |
| (this update) | This report's checklist, for 0.11.0 |

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.9.0 (Inko 0.21.1)` in Cargo's `bin` directory, on PATH.
  Another session was changing Cuttlebone throughout. I didn't install,
  rebuild or modify it, and wrote nothing to the Cuttlebone or Inko
  checkouts. I only read Cuttlebone's docs, its staged changes and, once it
  was committed, its release index commit (3e3256f).
- Cuttlebone's published releases: at first only v0.10.0, with four
  builds for Inko 0.21.1 and a `manifest.json`, and no `index.json`. By
  the end, 0.11.0, the first with an `index.json`
  ([below](#after-cuttlebone-0110)). I read the manifest and the releases API
  with `curl` to learn their shape. The test suite never touches the
  network.

## How the download works

All of it is in `CuttleboneDownloads.kt`, and the precheck in
`Cuttlebone.kt` decides when it's needed.

### Which Cuttlebone is used

`Cuttlebone.locate` decides, and is a pure function so the tests can check
the order:

1. **The setting** (*Settings → Languages & Frameworks → Inko → Cuttlebone →
   Executable*): a path, or a name looked up on PATH. When it's set but
   missing, nothing else stands in for it, as before.
2. **`cuttlebone` on PATH**, then Cargo's `bin` directory, as before.
3. **A download** for the project's Inko version: the newest Cuttlebone
   version Parakeet downloaded for it.

The settings page has a new line, "In use:", saying which one: its
`--version` output, where it came from ("set in these settings", "on
PATH", "in Cargo's bin directory", "downloaded by Parakeet") and its path.

The precheck only asks `inko --version` when something needs it: to pick a
download, or to compare with the Inko the chosen Cuttlebone links. (Asking
it every time broke InkoFormattingTest, which counts the fake `inko`'s
calls; the suite caught it before the first commit.)

### When it's offered

The precheck runs when the first Inko file opens. When nothing is set,
nothing is on PATH and nothing is downloaded for the project's Inko, the
existing notification, "Parakeet needs Cuttlebone", is shown once, but only
after a lookup in the background, so it can say what it found:

- **A build exists:** "Parakeet can download Cuttlebone 0.11.0 for Inko
  0.21.1 (macOS on ARM64, 5.5 MB) from its GitHub releases.", with a
  **Download Cuttlebone 0.11.0** action before the existing two.
- **No release has one** for that Inko and platform: it says so, names the
  newest release, and suggests another Inko or building from source. No
  download action.
- **The build needs a newer OS** (the manifest's `minimumOs`: macOS 15):
  it says which version it needs and which this is. No download action.
- **The lookup failed** (offline, GitHub down, a manifest schema this
  Parakeet doesn't read): "Parakeet couldn't look for a Cuttlebone to
  download: …", and the reason.

Looking up fetches JSON (a manifest, perhaps the index or GitHub's
releases list, perhaps older manifests). Nothing is downloaded until the
action is clicked.

### Finding the build

`CuttleboneReleases.find(inko, platform)`:

1. Fetch `releases/latest/download/manifest.json` and take the build whose
   `inko`, `os` and `arch` match. Refuse a manifest whose `schema` isn't 1.
2. Otherwise, fetch `releases/latest/download/index.json` (Cuttlebone's
   release index) and take, newest first, each release whose `inko` list
   has the user's version, fetching its manifest, until one has a build
   for this platform.
3. Without an index (404, as there was before 0.11.0), or with one whose `schema`
   isn't 1, ask GitHub's releases API
   (`api.github.com/repos/setdef/Cuttlebone/releases?per_page=100`). It
   skips drafts and prereleases, keeps releases with an asset named
   `cuttlebone-<version>-inko-<inko>-…`, and fetches only their manifests,
   newest first.

The platform comes from `os.name` and IntelliJ's `CpuArch`: `macos` or
`linux` (and `freebsd`, which has no builds, so it gets the "no release"
message), and `arm64` or `amd64`. An Intel JVM under Rosetta on an Apple
silicon Mac gets the ARM64 build, which runs natively there.

### Downloading and installing

The click starts a backgroundable task, with progress and a cancel button.
`CuttleboneDownloads.install`:

1. Creates a temporary directory, `.download-<random>`, beside the
   installed builds. The dot means a download in progress is never taken
   for a build.
2. Downloads through `HttpRequests`, IntelliJ's HTTP support, so the IDE's
   proxy settings apply. A `Content-Length` other than the manifest's
   `size` is refused before reading.
3. Checks the file's size and SHA-256 against the manifest.
4. Unpacks it with IntelliJ's `Decompressor.Tar`, finds the manifest's
   `binary` (refusing a path outside the archive), and makes it executable.
5. Runs `cuttlebone --version`: it must run, and name the Inko the build
   is for. This step is mine, not docs/releases.md's. It's what keeps a
   mismatched or broken build from ever being installed.
6. Moves the archive's directory (the server and its licenses) into place
   in one atomic rename, as `cuttlebone-<version>-inko-<inko>/cuttlebone`.
   If another IDE installed the same build meanwhile, that one is used.
7. Deletes the temporary directory, whatever happened. Temporary
   directories more than a day old, left by an IDE that was killed
   mid-download, are deleted before the next download.

The builds live in `parakeet/cuttlebone/` under JetBrains' shared data
directory (`PathManager.getCommonDataPath()`: `Library/Application
Support/JetBrains` in your home directory on macOS, `.local/share/JetBrains`
on Linux). That's what I took "the IDE's data directory" to mean. Unlike
each IDE's own config and system directories, it survives IDE updates, and
every JetBrains IDE uses the same downloads.

On success, a notification says it's installed. Every open project with no
Cuttlebone, or using a download, checks again and starts it. On failure,
the notification gives the reason and a **Try Again** action, and nothing
is installed. A cancelled download installs nothing and says nothing.

### When Inko changes

The precheck remembers the project's Inko version. When you come back to
the IDE (`ApplicationActivationListener`, so after running `ivm` in a
terminal), and after ivm installs a version from the settings page, each
project runs `inko --version` again in the background. That's cached
until the executable changes, so it's usually just a `stat`. If the
version changed, the precheck runs again. It uses the download for the new
version if there is one, or offers it the same way.

### Updates

When the Cuttlebone that starts is a download, Parakeet looks once per IDE
session per Inko version for the newest build for that Inko. If it's newer,
a notification says so ("Cuttlebone 0.11.0 is available") with a download
action. The version it told you about is stored (`PropertiesComponent`,
application level), so each newer version is mentioned only once, across
sessions too. Nothing is installed without the click. After an update is
installed, the newest download for that Inko is the one used. Older
downloads are kept, and nothing deletes them.

## Tests

Nothing in the suite reaches the network. `InkoTestCase` replaces the
`CuttleboneLocations` service in every test: its releases fail with "tests
never reach the network", and downloads go to a temporary directory. The
tests that download use `CuttleboneReleaseServer`, a
`com.sun.net.httpserver` server on 127.0.0.1. It serves each release's
manifest and archives (built in the test as `.tar.gz`, in a release's
layout), the latest manifest, the index when asked to, and a releases-API
list.

`CuttleboneDownloadTest` (32 tests):

- **Platform detection:** each OS and CPU, Rosetta, Windows and 32-bit
  CPUs as none, this machine's, and `minimumOs` against macOS and Linux
  kernel versions.
- **The manifest:** reading one, and refusing schema 2.
- **Finding:** the latest's build for each platform (and no other
  requests); an older release for an older Inko, through the releases API
  when there's no index, and through the index when there is one (asking
  the API nothing); the index saying no release has it; the next release
  in the index when one lacks this platform; an index of another schema
  falling back to the API; no build for this Inko; none for FreeBSD; one
  needing a newer macOS; a 404; offline (the server stopped); and the
  tests' own releases being offline.
- **Installing:** a verified build, with its papers, and nothing else left
  in the directory; the newest per Inko; a checksum mismatch; a truncated
  download, both a connection cut mid-body and a complete response
  shorter than the manifest says; a 404; offline; a binary saying another
  Inko; an archive without the binary, or naming a path outside it;
  cancelling; and a download interrupted a day ago cleaned up, while one
  in progress now is left alone. Each failure checks that nothing was
  installed and nothing was left behind.
- **Precedence:** the setting first, and a name in it looked up on PATH; a
  missing setting not replaced by a download; PATH before a download; a
  download only for its own Inko.

`CuttleboneDownloadOfferTest` (10 tests), with the fake Cuttlebone packed as
a release's binary and started through IntelliJ's LSP support:

- the offer, its text and actions, nothing downloaded or started before
  the click, then one archive downloaded, installed, started and in use;
- no build for this Inko: said, and no download action;
- offline: said, and no download action;
- a corrupted download: the reason, a Try Again action, nothing
  installed;
- Inko changing from 0.21.1 to 0.20.0 (no download for it): offered, from
  the older release, then used once clicked; and back to 0.21.1, whose
  download is used without asking;
- an unchanged Inko checks nothing again;
- `cuttlebone` on PATH, and the setting, each used over a download;
- an update offered once, and not again after a restart or in the next
  session, and never installed unasked;
- downloading the update starts it.

`CuttleboneIntegrationTest.testDownloadsABuildAndStartsIt` is the
end-to-end test. It packs the real `cuttlebone` (from check.sh's
`CUTTLEBONE`: here 0.9.0) into an archive as a release's, serves it on
127.0.0.1 with a manifest for this platform, and opens a file with nothing
set or on PATH. It clicks the offer, waits for the downloaded build to run
against the real `inko`, and resolves `x.size(2.0)` to the method the
compiler calls through it.

This is a feature, not a bug fix, so AGENTS.md's fail-first rule doesn't
strictly apply. The tests were written beside the code (step 1's), and the
index's tests before its code. Those didn't compile against the old
constructor, so I didn't watch them fail before writing it. The one
fail-first fix is the cleanup of abandoned downloads: its test ran and
failed, leaving `.download-1` behind, before the fix.

### Results

`scripts/check.sh` passed before each commit. The first run of the typing
replay failed one limit (inlay hints at pauses, 90th percentile 1350 ms
against 1200 ms) at a load average of about 90, from the Cuttlebone
session's builds and mutation tests. Nothing in this work runs while
typing. With the load around 30 to 80, the next run passed.

`scripts/check.sh --full`, on this commit's tree, failed once:
`CuttleboneFeaturesTest.testUsageHighlightingWaitsForAFilesFirstCheck`,
the timing test the last two reports saw fail the same way (the second of
two requests to a fake that waits 1 s hadn't been sent yet). It uses a set
Cuttlebone, which none of this work changes. The rerun, at a load of about
7 to 29, passed in 424 s: 744 tests, the typing replay, the build, and the
Plugin Verifier, "Compatible" against IDEA 2025.3 (1 deprecated and 11
experimental API usages) and 2026.2.3 (27 deprecated, 2 experimental).
None of those usages is in this session's code. The only change since that
run is filling in this paragraph.

## After Cuttlebone 0.11.0

Cuttlebone 0.11.0 was released after this report's first version, with
`index.json`. Its session reported four of Parakeet's integration tests
failing against it. Milestone 11 answers around an import of a module that
doesn't exist, from a repaired copy with the import blank (`fresh`,
`repaired`), so `import app.nope` no longer produced the states those tests
need. Parakeet's behaviour wasn't wrong; the tests' trigger was.

- **The three stale tests** (`testAfterAnEditNoCheckCanAnswer…`) now add a
  module that doesn't parse (`src/app/broken.inko`, saved, not open)
  after the last good check, and import it a line above everything in
  main.inko. Neither the code as it is nor a repaired copy can be checked:
  repairs blank the lines of open documents that changed, and of missing
  imports, and this import is neither. `run` is unchanged and doesn't
  mention `broken`, so its answers are stale, a line down, in 0.9.0 and
  0.11.0 alike. Every assertion is as it was. Two triggers I tried first
  got fresh answers from both versions: a new `fn size -> Missing` in
  main (a repaired copy blanks it), and a method with an error added to
  `B` in util.inko.
- **`testOpenedWithAnImportOfAMissingModuleThereAreNoHints`** adds a
  method using the missing module (`nope.value`), which 0.11.0 still
  can't answer. Before 0.11.0 nothing in the file has an answer, and
  there are no hints. From 0.11.0, the repaired copy's hints are
  Parakeet's everywhere but that method, unmarked, and none are guessed
  there. The test branches on the server's version, as others branch on
  its capabilities.
- **New, `testAfterAnEditImportingAMissingModuleTheRepairedAnswersAreUsedLikeFreshOnes`:**
  with 0.11.0, after adding `import app.nope`, Parakeet uses the repaired
  answers like fresh ones: navigation and Rename's exact resolution both
  resolve `x.size`, and hints and documentation aren't marked outdated.
  Before 0.11.0 it says so and returns.

`CuttleboneIntegrationTest` passed, all 20, against 0.9.0 (on PATH) and
against the released 0.11.0 binary (the Cuttlebone session's copy, named
by `CUTTLEBONE`). The end-to-end download test packed each in turn.
`scripts/check.sh --full` ran against 0.11.0 at the end:

it passed on the first run, in 400 s at a load of about 9 to 21: every
test, the typing replay (against 0.11.0), the build, and the Plugin
Verifier, "Compatible" against both IDEs with the same API usages as
before. The only change since that run is filling in this paragraph. The
pre-commit hook ran `scripts/check.sh` against 0.9.0 for each commit.

README's section on code being edited now says which Cuttlebone has no
answer around an import of a missing module.

## Decisions you may want to revisit

- **Where the builds go**: JetBrains' shared data directory, not one IDE's
  own (above). If you'd rather each IDE kept its own, it's one line,
  `CuttleboneLocations.downloads`.
- **Old builds are never deleted**, after an update or an Inko change.
  Each is 18.5 MB (macOS) to 51 MB (Linux) unpacked. Nothing in the
  settings lists or removes them.
- **The lookup happens without asking**: it fetches JSON from GitHub
  (one request when the latest release fits) when there's no Cuttlebone,
  and once a session to look for updates. Only the archive waits for the
  click.
- **No download button in the settings.** The notification is the only
  way to download, and it's shown once per check (again when the settings
  or the Inko change, or the project reopens).
- **Authenticity:** the SHA-256 comes from the manifest, from the same
  GitHub release as the archive, over HTTPS. That proves the download is
  complete and unchanged since the release, not who made it. Cuttlebone
  doesn't sign its releases.
- **GitHub's API is unauthenticated** (60 requests an hour per IP). It's
  only asked when the latest release has no build for the Inko and there's
  no index, which stops once 0.11.0 publishes one.
- **Updates are only checked for downloads.** A `cuttlebone` on PATH is
  yours to update.
- **The `--version` check after unpacking** is beyond what Cuttlebone's
  docs ask a client to do. It costs one process start, and it's what
  guarantees a mismatched build isn't installed.

## What's left unverified

- **A real download from GitHub.** Every download in the tests is served
  locally. Neither the IDE nor I downloaded a real release archive through
  `HttpRequests`, or followed GitHub's redirect from the release URL to its
  storage. `HttpRequests` follows redirects by default.
- **Proxy settings.** That `HttpRequests` honours the IDE's proxy is
  IntelliJ's documented behaviour; no test goes through a proxy.
- **The real release index.** 0.11.0 published one. Fetched with `curl`,
  it has the documented shape and the fields Parakeet reads (`schema` 1,
  `releases[].cuttlebone`, `inko`, `manifest`). Parakeet itself hasn't
  fetched it.
- **Gatekeeper.** A file written by the IDE's own HTTP code shouldn't be
  quarantined (Cuttlebone's MILESTONE-10.md says so for its own client
  code). The end-to-end test's binary was served over HTTP and ran, but a
  copy of a binary already on this Mac proves little about one downloaded
  from the internet.
- **Linux, Intel Macs, Rosetta, and FreeBSD's message**: only tested as
  data, on this ARM64 Mac.
- **The notifications, progress and settings line as they look**: the
  tests check their text and actions, not how they render.
- **Coming back to the IDE after `ivm default`** in a terminal: the tests
  call the same method the listener calls, but not the listener itself.
- **Restarting a server just after it starts.** When Cuttlebone is
  stopped (to restart it, after the settings or the Inko change) just
  after it initializes, before IntelliJ has sent it the open files,
  IntelliJ's LSP client logs an error ("Server is not in the Running
  state. Ignoring open"). In the tests, a logged error fails the test; in
  an IDE it may show as an IDE error. It isn't new: changing the settings
  had the same window. This session met it when an offer test changed the
  Inko within milliseconds of the start. The tests now wait for the file
  to be sent first. Parakeet doesn't guard against it.
- **Two IDEs downloading the same build at once**: the code handles the
  race (the second rename finds the first's directory), untested.

## Checklist for testing 0.13.0 by hand

Build it with `./gradlew buildPlugin` and install
`build/distributions/parakeet-0.13.0.zip` (*Settings → Plugins → ⚙️ →
Install Plugin from Disk…*), or use `./gradlew runIde`.

### The download

Your `cuttlebone` 0.9.0 in Cargo's `bin` directory comes first, so to see
the offer, move it aside and clear the setting:
`mv "$HOME/.cargo/bin/cuttlebone" "$HOME/.cargo/bin/cuttlebone.aside"`
(move it back at the end), then restart the IDE.

1. **The offer.** Open an Inko project with Inko 0.21.1 and open a file.
   One notification, "Parakeet needs Cuttlebone", should offer
   **Download Cuttlebone 0.11.0** for Inko 0.21.1 (macOS on ARM64,
   5.5 MB). Nothing should be in
   `$HOME/Library/Application Support/JetBrains/parakeet/cuttlebone` yet.
2. **The download.** Click it. A progress bar should appear in the status
   bar, with a cancel button. Then "Cuttlebone 0.11.0 is installed", and
   go to definition on a method call should work. The directory should
   hold `cuttlebone-0.11.0-inko-0.21.1/` with `cuttlebone`, `README.md`,
   `LICENSE`, `LICENSE-MPL-2.0` and `THIRD-PARTY.md`, and nothing else (no
   `.download-…`).
3. **It runs.** `xattr -l` on that `cuttlebone` should show no
   `com.apple.quarantine`, and it should have started without a
   Gatekeeper prompt.
4. **The settings.** *Settings → Languages & Frameworks → Inko* should
   say, under Cuttlebone, "In use: cuttlebone 0.11.0 (Inko 0.21.1),
   downloaded by Parakeet:" and the path, then "Using cuttlebone 0.11.0
   for types and definitions."
5. **Cancelling.** Delete the directory, restart, click Download, and
   cancel at once. Nothing should be installed, and nothing left behind.
   Reopen the project for the offer again.
6. **Offline.** With Wi-Fi off (and the directory deleted), open the
   project: the notification should say it couldn't look for a download,
   and why.
7. **Another Inko.** With ivm, install and switch to an Inko no release
   has a build for (`ivm install 0.20.0; ivm default 0.20.0`), and come
   back to the IDE: a new notification should say no release has a build
   for Inko 0.20.0, with no download action. That answer comes from
   0.11.0's `index.json`: idea.log shouldn't mention `api.github.com`. Switch back to 0.21.1 and
   return: the download should be used again, without a notification.
8. **Overrides.** Move `cuttlebone.aside` back: after a restart, the
   settings should say "in Cargo's bin directory", even with the download
   there. Setting *Executable* to it should say "set in these settings".
9. **A proxy**, if you have one handy: set it in *Settings → Appearance &
   Behavior → System Settings → HTTP Proxy*, and download again.
10. **An update.** Parakeet only downloads the newest build, so put an
    older one in place by hand: quit the IDE, delete
    `cuttlebone-0.11.0-inko-0.21.1/`, and unpack the v0.10.0 release's
    `cuttlebone-0.10.0-inko-0.21.1-aarch64-apple-darwin.tar.gz` (from
    [its release](https://github.com/setdef/Cuttlebone/releases/tag/v0.10.0))
    as `cuttlebone-0.10.0-inko-0.21.1/` in the same directory, with its
    `cuttlebone` inside. Downloading it with `curl` avoids the quarantine.
    Open the project: the settings should say 0.10.0 is in use, and
    "Cuttlebone 0.11.0 is available" should appear, with nothing changing
    until you click it. Click it: 0.11.0 should download, start, and be
    "In use". Then do the same again (0.10.0 alone), but restart without
    clicking: it's told once per version, so it shouldn't appear again.

### Typing (from [Replaying typing against Cuttlebone](SESSION-typing-replay.md))

With a Cuttlebone of milestone 7 or later (0.9.0, or the 0.11.0 download):

1. **Type for a while** in a std-sized module and a small project, both a
   new method and inside an existing one. Hints, errors, usage
   highlighting and completion should keep up, and the IDE should stay
   responsive. With *Help → Diagnostic Tools → Debug Log Settings* set to
   `#com.intellij.platform.lsp`, idea.log shows the requests: a burst of
   `$/cancelRequest` while typing, and few answers after, is the fix
   working.
2. **Completion while typing a member** in code that doesn't parse yet,
   say `values.pu` in a method with an unclosed `{`. The popup should
   offer `push` before you've finished typing it.
3. **Usage highlighting after typing** should appear with the rest of
   highlighting, about 0.3 s after you stop, and at once when only the
   caret moves.
4. **Find Usages or Rename while typing in another file:** the search
   should finish with its results, not lose them to an edit.
5. **Parameter info (⌘P)** inside a call, then type arguments: it should
   follow, and the signature should appear in well under half a second.
6. **CPU** of `cuttlebone` and its `--worker` while typing should drop
   between keystrokes, rather than stay busy with answers nobody wants.
