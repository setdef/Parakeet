# Session: replaying typing against Cuttlebone

Nobody has used Parakeet with Cuttlebone in a real IDE yet. This session
builds the closest automated stand-in: a replay that types real code into
real files at a person's speed, against the real `cuttlebone`, with
highlighting, inlay hints, usage highlighting, parameter info and
completion running as they do in the IDE. It then fixes what the replay
found, and keeps the replay as a build check with measured limits.

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| 9c90744 | Step 1: the replay (`CuttleboneTypingReplayIntegrationTest`), as its own Gradle task, `typingReplay`, which `scripts/check.sh` runs when there's a cuttlebone; a request trace in `CuttleboneQueries`, Cuttlebone's `cached` in statuses, outgoing messages counted by method |
| 9e9f46a | The replay types a method's body as well as a new method, and runs against the plugin it builds |
| 28d53ff | Step 2: an edit cancels the requests about the text before it; usage highlighting asks once typing pauses, and doesn't prefetch where Cuttlebone can't check the code |
| c214c00 | Step 3: the replay's limits |
| (this report) | Parameter info measured from ⌘P; this report; README; CHANGELOG |

## The `cuttlebone` on PATH

`cuttlebone 0.7.0 (Inko 0.21.1)`, in Cargo's `bin` directory: milestone 7,
as its `--version` and `serverInfo.version` say. I didn't install, rebuild
or modify it, or anything in the Cuttlebone or Inko checkouts. I read
Cuttlebone's docs/lsp.md, its milestone 6 and 7 reports and its source (for
what it logs). The replay copies the standard library from the installed
toolchain (`inko print std`) and, when asked, a project's `src`, `dep` and
`inko.pkg` into the test's own directory; I pointed it at
`inko-lang/idoc`, which it only reads. That checkout had an uncommitted
change in `src/idoc/fs.inko`, so idoc was measured as it is there, not at a
commit.

## Step 1: the replay

`CuttleboneTypingReplayIntegrationTest` opens a file in the test IDE,
deletes a method (or its body), lets everything settle, and types it back:

- **At a person's speed:** a key every 90 to 190 ms (about 7 characters a
  second), half a second to 1.2 s at the end of each line, 2.5 s every
  fourth line, a typo now and then (a wrong letter, a pause, ⌫), from a
  fixed random seed. A version of the text that lasts 1 s or more counts as
  a pause, where a person looks at the results.
- **With the IDE's features as they run there:** the daemon on its timer
  with its own delays (300 ms after an edit), for the editor in front of
  the person (`EditorTracker`); usage highlighting at the caret; inlay
  hints; completion's autopopup after `.`, whose item is accepted once two
  letters of the member are typed and the popup offers it; and parameter
  info (⌘P) after each call's `(`, which then follows the typing.
- **Two ways of typing:** a *new method*, from its first line, so that the
  file doesn't parse until its last `}`; and a *method body* between braces
  that are already there (as when the IDE closes a `{`), so the file parses
  again as each statement is finished. The IDE's own closing of brackets
  and quotes is off, since the replay can't predict when it would overtype
  them; it types every character itself, and checks at the end that the
  file is exactly as it was.
- **Against the real `cuttlebone` and `inko`:** std's `json.inko`
  (`Parser.object`, from the installed toolchain, so it always runs), and
  with `PARAKEET_REPLAY_PROJECT` set, a project's file (idoc's
  `src/idoc/html.inko`, `adjust_headings`, by default).

It measures, from a trace `CuttleboneQueries` keeps of each request and a
listener on the document:

- requests sent, per feature, and the `$/cancelRequest` messages IntelliJ
  wrote to the server (counted in what goes to its stdin);
- answers that arrived for text that had changed since the request;
- checks Cuttlebone ran: answers whose `cuttlebone/status` doesn't say
  `cached`, and the time it reports for them (`elapsedMs`). Cuttlebone's
  log doesn't record every check, so the statuses are the count;
- latency: from the edit that made a version of the text to the last
  answer for it, at each pause (hints, usage highlighting, resolve, and the
  daemon's whole pass); from the keystroke to the answer for completion;
  from ⌘P to the answer for parameter info.

With `PARAKEET_REPLAY_TRACE` set to a directory, it writes every edit,
request and daemon run there, one per line; the tables below come from
those.

**Its own IDE.** In the tests' IDE, a later test's teardown waited
forever for a non-blocking read action: in both full runs with the replay
(in `InkoIntentionsTest`), and in a run of the Cuttlebone tests and
`Inko[A-I]*` (in `InkoInterpolationTest`). It never did with the replay and
either of those tests alone, nor in a full run without the Cuttlebone
tests. I didn't find what the
replay leaves running. So it runs as its own Gradle task, `typingReplay`,
in an IDE of its own (`intellijPlatformTesting.testIde`), with the test
task's classpath (the platform's test framework), the jar it builds, and
its own sandbox. One trap on the way: it first took Parakeet's classes from
the test sandbox's jar, which nothing in the task rebuilds, so it measured
whatever `test` had built last. I caught that when a run of the fixed code
measured like the old one, discarded those runs, and fixed the classpath
(9e9f46a). The discarded runs were of the code before the fixes, in the
replay's own IDE, so they serve as more measurements of it.

## What the replay found

On the code before the fixes, typing a new method into `json.inko` (222
keystrokes, 57 s) sent about 330 requests, and every one but about ten
was a check of its own: 312 to 321 checks, 12.5 to 13 s of Cuttlebone's
time, more than a fifth of the time spent typing.

1. **Nothing was cancelled.** 0 `$/cancelRequest` in every run; 54 to 56
   answers (a sixth) arrived for text that had changed. Since milestone 7,
   Cuttlebone answers every request for the text it was asked about, so
   each was a check for nobody. IntelliJ's LSP client only cancels a
   request when the indicator it was sent under is cancelled
   (`LspRequestExecutorBase.awaitWithCheckCanceled`), and Parakeet sends
   each under an indicator of its own, so that a moved caret doesn't cancel
   it (from the previous session). Its comment said "an edit to the
   document cancels it on the server", which milestone 7 made untrue. A
   first test of cancelling passed without any fix: IntelliJ cancels a
   request after 30 s with no answer, which the test's 30 s wait covered.
   It now waits at most 5 s.
2. **Usage highlighting asked on every keystroke.** It runs each time the
   caret moves, so for the name being typed, and then prefetched that
   name's definition: two requests a keystroke, about 300 of the 330. In
   code that doesn't parse, neither is ever kept (below), so each was a
   whole check, of a name nobody had finished typing.
3. **In code that doesn't parse, nothing is kept.** Every `noHir` answer
   is `cached: false` and costs 40 to 120 ms, even a second question about
   the same text. This is Cuttlebone's, and outside this session (see
   [For Cuttlebone](#for-cuttlebone)). It's what makes the first two
   expensive: with a check kept, they'd have been lookups.

## Step 2: the fixes

- **An edit cancels what it leaves nobody to see.** `CuttleboneQueries`
  listens to the project's Inko documents. On each edit, every request sent
  before it that hasn't been answered and that nothing is waiting for gets
  `$/cancelRequest` (by cancelling its indicator, which IntelliJ turns into
  one). What waits in highlighting or a read action stops waiting when an
  edit cancels it, and then cancels its request if the text has changed. A
  search, completion (whose items are filtered as the person types on) and
  an action keep theirs while they wait. A moved caret still cancels
  nothing. Tests: `CuttleboneClientTest.testAnEditCancelsTheRequestsAboutTheTextBefore`
  and `testAnEditLeavesTheRequestsASearchIsWaitingFor` (the first failed
  before the fix, once it waited less than IntelliJ's 30 s).
- **Usage highlighting asks once typing pauses.** It waits until the text
  has been still for the daemon's own delay (`autoReparseDelay`, 300 ms),
  and has nothing to show if the text changes meanwhile. It doesn't prefetch
  the definition where Cuttlebone just said it can't check the code there,
  since the definition at the same place has no answer either.
  `CuttleboneWhileTypingTest` (all three failed before the fix).

### Which requests to cancel: the one being checked too

Cuttlebone's milestone 6 report warns that cancelling a running query
kills its worker, and the checks it kept. So I measured both ways:
cancelling everything outdated, and sparing the request Cuttlebone is
checking for its first half second, so as not to kill the worker for what's
left of one check. Fixed code, after / with the grace:

| replay | requests | cancels | answers for changed text | checks | Cuttlebone's time (s) | hints at pauses, median (ms) | completion, keystroke to answer, median (ms) | completions accepted | runs |
|---|---|---|---|---|---|---|---|---|---|
| std, new method | 91–97 / 88–91 | 19–30 / 1 | 2–6 / 13–16 | 51–65 / 77–80 | 3.5–3.8 / 3.6–3.7 | 411–452 / 412–418 | 135–167 / 127–129 | 2–3 / 4 | 3 / 2 |
| std, method body | 65–67 / 65–66 | 7–9 / 0–1 | 1–2 / 7–8 | 46–47 / 53 | 2.3–2.6 / 2.4–2.8 | 396–429 / 400–405 | 136–139 / 132–137 | 4 / 4 | 3 / 2 |
| idoc, new method | 54 / 55 | 2–3 / 0 | 1–2 / 4 | 48–49 / 51 | 2.2 / 2.3 | 384–389 / 378 | 77 / 89 | 3 / 3 | 2 / 1 |
| idoc, method body | 43 / 43 | 2 / 0 | 0–1 / 2 | 33–34 / 33 | 1.4–1.5 / 1.2 | 387–394 / 383 | 75 / 75 | 3 / 3 | 2 / 1 |

A check right after a kill took 77 ms (median) instead of 39 ms. The kills
and the checks they saved cost about the same in Cuttlebone's time, so I
took the simpler rule that the session asked for: **every request whose
text has changed is cancelled**, the one being checked included. The cost
is in completion while typing a member into code that doesn't parse (the
std new-method replay): completion restarts as each letter is typed while
its items are still coming, each restart now kills the worker, so the next
answer is slower and the popup was in time for 2 or 3 of the replay's 4
completions rather than 4. That's the trade to revisit if it shows in the
IDE (see [Check by hand](#what-a-human-should-check-by-hand)).

## Measurements, before and after

Ranges are across runs; "before" is the code at 9e9f46a (the replay, none
of the fixes), "after" is the fixed code (28d53ff, and c214c00's, which
changes only the test). Latencies are medians within a run. All on one
Apple Silicon Mac, while another session was working on Cuttlebone.

### std's json.inko, a new method (5 runs before, 3 after)

| | before | after |
|---|---|---|
| keystrokes | 222 | 222–230 |
| requests | 324–333 | 91–97 |
| requests per keystroke | 1.46–1.50 | 0.40–0.42 |
| `$/cancelRequest` sent | 0 | 19–30 |
| answers for text that had changed | 54–56 | 2–6 |
| of the requests whose text changed before their answer, cancelled | 0% | 79–94% |
| checks Cuttlebone ran | 312–321 | 51–65 |
| checks per keystroke | 1.41–1.45 | 0.22–0.29 |
| Cuttlebone's time in those checks (s) | 12.5–13.1 | 3.5–3.8 |
| completions accepted (of 4) | 4 | 2–3 |

| feature | requests | cancelled after | for changed text | checks | latency (ms) |
|---|---|---|---|---|---|
| resolve (definition) | 158–163 → 42–44 | 1–7 | 44–48 → 0–3 | 158–163 → 35–43 | last answer at pauses: 136–146 → 556–623 |
| inlay hints | 16 → 13–15 | 0–2 | 3 → 0 | 4 → 3–5 | at pauses: 408–417 → 411–452 |
| usage highlighting | 137 → 5–6 | 1–2 | 4–6 → 0 | 137 → 4 | at pauses: 91–97 → 385–394 |
| parameter info | 3 → 3 | 0–1 | 0 → 0–1 | 3 → 2–3 | ⌘P to answer: 42–44 → 43–146 |
| completion | 8–14 → 25–31 | 14–21 | 0–5 → 1–3 | 8–14 → 6–12 | keystroke to answer: 105–112 → 135–167 |
| highlighting, the daemon's whole pass | | | | | at pauses: 1119–1148 → 1113–1153 |

### std's json.inko, a method body (2 runs before, 3 after)

| | before | after |
|---|---|---|
| keystrokes | 180–182 | 180 |
| requests per keystroke | 1.45–1.49 | 0.36–0.37 |
| `$/cancelRequest` sent | 0 | 7–9 |
| answers for text that had changed | 34–45 | 1–2 |
| of the requests whose text changed before their answer, cancelled | 0% | 78–89% |
| checks Cuttlebone ran | 200–203 | 46–47 |
| Cuttlebone's time in those checks (s) | 8.9–9.0 | 2.3–2.6 |
| completions accepted (of 4) | 3–4 | 4 |

| feature | requests | for changed text | checks | latency (ms) |
|---|---|---|---|---|
| resolve (definition) | 119–120 → 27 | 23–34 → 0–1 | 66 → 21–22 | last answer at pauses: 102–103 → 140–159 |
| inlay hints | 12 → 11–12 | 0–1 → 0 | 4 → 4–5 | at pauses: 404–412 → 396–429 |
| usage highlighting | 110–111 → 5 | 1 → 0–1 | 107–108 → 4–5 | at pauses: 100 → 349–350 |
| parameter info | 6 → 6 | 2–3 → 0 | 6 → 6 | ⌘P to answer: 48–51 → 50–51 |
| completion | 17–19 → 15–18 | 7 → 0–1 | 17–19 → 10–11 | keystroke to answer: 128–135 → 136–139 |
| highlighting, the daemon's whole pass | | | | at pauses: 1105–1165 → 1137–1207 |

### idoc's html.inko (3 runs and 1 before, 2 and 2 after)

| | new method, before → after | method body, before → after |
|---|---|---|
| keystrokes | 369 | 318 |
| requests | 489–493 → 54 | 406 → 43 |
| `$/cancelRequest` sent | 0 → 2–3 | 0 → 2 |
| answers for text that had changed | 52–57 → 1–2 | 20 → 0–1 |
| checks Cuttlebone ran | 482–486 → 48–49 | 362 → 33–34 |
| Cuttlebone's time in those checks (s) | 14.0–14.4 → 2.2 | 9.4 → 1.4–1.5 |
| usage highlighting requests | 221–222 → 4 | 175 → 4–5 |
| inlay hints at pauses (ms) | 380–385 → 384–389 | 377 → 387–394 |
| usage highlighting at pauses (ms) | 74–83 → 325–327 | 82 → 334–365 |
| completion, keystroke to answer (ms) | 76 → 77 | 76 → 75 |
| parameter info, ⌘P to answer (ms) | 70–71 → 71–72 | 25 → 27–28 |
| the daemon's whole pass at pauses (ms) | 425–433 → 436–440 | 430 → 448–466 |
| completions accepted (of 3) | 3 → 3 | 3 → 3 |

### Reading them

- **Checks fell by 80 to 90%,** and Cuttlebone's time with them, in every
  replay: most of what it checked before was the name being typed, and
  answers for text that had already changed.
- **Hints, parameter info and the daemon's whole pass are as quick as
  before.** Hints arrive about 400 ms after typing stops, 300 of them the
  IDE's own wait after an edit.
- **Usage highlighting shows about 250 ms later after typing stops**
  (about 330 to 390 ms instead of 75 to 100), by design: it waits as long as
  the rest of highlighting does. Moving the caret without typing isn't
  delayed.
- **Resolve's last answer at a pause is later** (in std, 556 to 623 ms
  instead of 136 to 146). Before, the last definitions for a version of the
  text were usage highlighting's prefetches, sent at once; now the daemon's
  inspections ask after its 300 ms wait, and the prefetch waits too. The
  daemon's whole pass, which they're part of, takes as long as before.
- **Completion, typing a member into code that doesn't parse, got
  slower** (see the table above on cancelling).

## Step 3: the limits

The replay asserts these, in both std replays (and the project ones when
they run). Each is a few times what was measured after the fixes. Where
the fixes changed it, it's well below what was measured before them, so
the code before the fixes fails at the first: 1.46 requests a keystroke.

| limit | after the fixes | before them | why it's there |
|---|---|---|---|
| requests per keystroke ≤ 0.7 | 0.36–0.42 | 1.45–1.50 | asking on every keystroke again (usage highlighting, or a new feature) |
| checks per keystroke ≤ 0.5 | 0.22–0.29 | 1.10–1.45 | the same, counted where it costs |
| answers for changed text per keystroke ≤ 0.08 | 0.011–0.026 | 0.187–0.252 | requests nobody cancels |
| of the requests whose text changed before their answer (at least 5 of them), at least half cancelled | 78–94% | 0% | cancelling stopping altogether. With usage highlighting waiting but nothing cancelled, the per-keystroke limits all passed (0.05 answers for changed text a keystroke), so this is the one that catches it |
| inlay hints answered at a third of the pauses or more | 5/8–7/11 | similar | hints that never arrive |
| inlay hints at pauses, 90th percentile ≤ 1200 ms | ≤ 491 | ≤ 465 | results lagging behind typing |
| usage highlighting at pauses, 90th percentile ≤ 1200 ms | ≤ 394 | ≤ 100 | waiting longer than the daemon does |
| completion, keystroke to answer, 90th percentile ≤ 800 ms | ≤ 184 | ≤ 180 | completion stuck behind other questions |
| parameter info, ⌘P to answer, every answer ≤ 600 ms | ≤ 146 | ≤ 90 | the same |

The times are generous because the replay runs on whatever machine runs
the build, busy or not; the counts are less sensitive to that, so they
carry the tightest margins. The share of cancelled requests needs at least
five such requests to mean anything: idoc's replays had three, two of them
cancelled.

`./gradlew typingReplay` runs it (about 2.5 minutes for std's two), and
`scripts/check.sh` does when it finds a cuttlebone; `PARAKEET_REPLAY_PROJECT`
adds a project's two replays.

## Not fixed

- **The daemon's whole pass at a pause** takes about 1.1 s in std's
  `json.inko` (0.44 s in idoc), before and after. Cuttlebone's part of it
  is done within about half a second. `inko check`, which Parakeet runs for
  errors, takes 0.1 to 0.2 s there with its mirror (timed by hand); the
  rest is IntelliJ's scheduling of its passes after its own delays. I left
  it alone.
- **Hints at every pause.** At a third to half of the pauses (before and
  after alike), no hints arrived for that version of the text before the
  next keystroke. At some of them the daemon was still resolving names in
  code that doesn't parse, an uncached check each. At others it hadn't
  run at all. The limit only guards against hints stopping altogether.
- **Completion while a member is typed into code that doesn't parse**
  (above).

## For Cuttlebone

Things the replay saw that are Cuttlebone's to decide, not Parakeet's; I
changed nothing there:

- **`noHir` answers are never `cached`.** In code that doesn't parse, every
  question about the same text costs a check (40 to 120 ms in std's
  `json.inko`, the repaired copies tried again each time), though docs/lsp.md
  says a repaired copy "is checked like the documents as they are, and kept
  like them". Either the repairs that fail aren't remembered, or these
  questions are at places no repair answers. With them kept, the daemon's
  resolving at a pause would be lookups.
- **Killing the worker on a cancel** makes the next check about 40 ms
  slower in std (77 instead of 39 ms, median). Cancelling a check that's
  waiting out the debounce, or keeping the worker and abandoning the check,
  would make cancelling free.

## What a human should check by hand

In a real IDE with this build and the milestone 7 cuttlebone:

1. **Type for a while** in a std-sized module and a small project, both a
   new method and inside an existing one. Hints, errors, usage highlighting
   and completion should keep up, and the IDE should stay responsive. With
   *Help → Diagnostic Tools → Debug Log Settings* set to
   `#com.intellij.platform.lsp`, idea.log shows the requests; a burst of
   `$/cancelRequest` while typing, and few answers after, is the fix
   working.
2. **Completion while typing a member** in code that doesn't parse yet,
   say `values.pu` in a method with an unclosed `{`. The popup should
   offer `push` before you've finished typing it. If it often arrives
   late, that's the cost of cancelling the request being checked, and the
   grace measured above is the alternative.
3. **Usage highlighting after typing** should appear with the rest of
   highlighting, about 0.3 s after you stop, and at once when only the caret
   moves.
4. **Find Usages or Rename while typing in another file:** the search
   should finish with its results, not lose them to an edit.
5. **Parameter info (⌘P)** inside a call, then type arguments: it should
   follow, and the signature appear in well under half a second.
6. **CPU** of `cuttlebone` and its `--worker` while typing should drop
   between keystrokes, rather than stay busy with answers nobody wants.

## Remaining unknowns

- What the replay left running in the tests' IDE that made a later
  teardown wait forever. It runs in an IDE of its own now, so it can't
  affect other tests, but the cause is unknown.
- Whether the test IDE's autopopup and parameter-info controller behave
  exactly as the IDE's: completion's autopopup is turned on by the test
  flag IntelliJ's own tests use, and ⌘P is invoked as an action.
- How this behaves on a slower machine, or with a project larger than std:
  all of it was measured on one Mac.
