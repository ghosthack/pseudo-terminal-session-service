# jmux

A small session daemon that does for Harness Manager what tmux does on macOS and Linux:
hosts named terminal sessions in a server that outlives the UI, keeps a server-side
terminal emulator per session so a fresh client can attach later and see the current
screen, lets several clients attach at once, and exposes a scriptable control surface
(`capture`, `send-keys`, resize). It exists so Harness Manager can run on Windows, where
tmux does not; on macOS and Linux tmux stays the default substrate.

## Maven dependency

```xml
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>jmux</artifactId>
  <version>0.1.0</version>
</dependency>
```

The `cli` classifier is the standalone executable JAR. It bundles its runtime
dependencies, including pty4j's native resources. The main library JAR declares
its dependencies normally; both are platform-independent Java artifacts.

## Building

Requires JDK 25 or newer, Maven 3.9 and a POSIX shell. The source build uses
only public downloads; it needs no GitHub token or preinstalled sibling project.

```sh
./scripts/build.sh
# target/jmux-0.1.0.jar      Java library
# target/jmux-0.1.0-cli.jar  standalone CLI
```

The build resolves the published JediTerm core fork from Maven Central and runs
the complete jmux test suite. All dependencies are public downloads. Maven uses
an isolated repository under `target/`; delete it to repeat the build from an
empty cache. No system Maven settings or credentials are used.

For embedding builds, install into the same isolated repository:

```sh
mvn -s target/empty-settings.xml -gs target/empty-settings.xml \
  -Dmaven.repo.local="$PWD/target/oss-repository" install
```

## Running

```sh
java -jar target/jmux-0.1.0-cli.jar <command>
```

| command | what it does |
|---|---|
| `serve [--daemon] [--keep-alive] [--home DIR] [--history-lines N] [--client-buffer BYTES]` | run a server; `--daemon` detaches stdio to `server.log` and ignores SIGHUP/SIGINT |
| `list [--json]` | live sessions (name, workdir, size, pid, clients, backend; `--json` adds labels and outputBytes) |
| `has NAME` | prints `true`/`false` |
| `new NAME [--dir DIR] [--size CxR]` | start a session running the login shell; **the only command that starts a server** |
| `kill NAME` | SIGHUP the shell (Unix) / terminate it (Windows), then force-kill survivors |
| `send-keys NAME TEXT` | type TEXT, pause 400 ms, press Enter (tmux parity) |
| `input NAME [--text T] [--paste] [KEY...]` | type T (or paste it, bracketed if the program asked), then press named keys: `Enter`, `Esc`, `Up`, `C-c`, `M-b`, `F5`, a single character… (see PROTOCOL.md) |
| `label NAME [KEY=VALUE...]` | merge labels (`KEY=` removes one) and print the result |
| `wait NAME [--quiet MS] [--timeout MS] [--since BYTES]` | block until the output has been quiet for MS (default 300); prints `{quiet, outputBytes, alive}` |
| `watch [NAME...] [--quiet MS]` | print one JSON line per event (`output`, `quiet`, `title`, `bell`, `exit`) of the named sessions, or of all of them including new ones; if the server drops the stream (the watcher fell behind) it prints the error and exits 1 |
| `capture NAME [--format text\|annotated\|ansi] [--json]` | the visible screen plus foreground command, shell pid, title, size; `annotated` marks selected rows with `▶ ` and the cursor with `▮` |
| `path NAME` | the session's current working directory |
| `attach NAME` | interactive attach from a Unix terminal; Ctrl-] detaches (not available on Windows yet) |
| `shutdown` | kill every session and stop the server |
| `version` | protocol and artifact version |

Every session's process gets `JMUX_SESSION=<name>` in its environment, so a program
(or an agent) inside a session knows which one it is. Library callers can add variables
per session with the `env` argument of `SessionServerClient.newSession` (`TERM` and
`JMUX_SESSION` cannot be overridden; see PROTOCOL.md "Environment").

One server per home directory, `~/.jmux/` by default
(`JMUX_HOME` or `-Djmux.home` override), holding
`server.sock`, `server.lock` and `server.log`. Like tmux, the server exits a couple of
seconds after its last session ends unless started with `--keep-alive`, and only `new`
starts one; every other command on a dead server reports "no server running" (empty
list, `false`, "no such session") and never spawns.

Harness Manager starts the daemon through the same resolution the CLI uses: an explicit
`-Djmux.launcher=` / `JMUX_LAUNCHER` path, then a
sibling jpackage launcher next to the running executable, then
`java -cp <its own classpath> …Main serve --daemon`.

## What the daemon guarantees

- **Attach is exact.** One pump thread per session reads the PTY, tokenizes the byte
  stream into complete escape sequences, applies them to the headless jediterm emulator
  and fans the same bytes out to attached clients under one lock. A client attaching
  mid-stream gets a serialized snapshot of the screen (history, cells, styles, cursor,
  scroll region, modes, title) taken at the exact position the live stream continues from.
- **No client can stall a session.** Each attached client has its own bounded outbound
  queue and writer; a client that falls behind is re-synchronised with a fresh snapshot,
  and one that stays stuck is dropped.
- **Terminal queries are answered once.** The daemon answers DA, DSR and cursor-position
  queries itself and strips them from what clients see, so a harness that probes its
  terminal right after `new` + `send-keys` gets a reply even with nobody attached, and two
  attached clients never both answer. The light/dark query (`CSI ?996n`) is passed
  through, because only a client knows its theme.
- **Sizes follow the latest client.** A resize from any client (or an attach at a
  different size) resizes the session and announces `CSI 8 ; rows ; cols t` to the other
  clients, tmux's `window-size latest`.

## Contracts a client must honour

- Send raw terminal input as DATA_IN bytes; never assume the daemon echoes.
- Treat the first bytes after `attach` as a full redraw: they clear the screen.
- Report `ambiguousCharsAreDoubleWidth() == false` in the client emulator (the server's
  emulator does), or wrap points will differ.
- The wire protocol is versioned; see PROTOCOL.md. A mismatch is reported, never
  worked around: finish the old server's sessions or run `shutdown`.

## Windows

The code is platform-neutral by construction — pty4j's ConPTY backend (opted in with
`setUseWinConPty(true)`; a winpty fallback is reported as `backend: winpty` and is a
degraded state), JDK Unix-domain sockets and `ProcessHandle`. A Windows x64 smoke
test verified session creation with ConPTY, input, screen capture and shutdown.
Known gaps:

- `attach` from a console is not implemented on Windows (Harness Manager attaches over the
  socket, so the app is unaffected).
- The foreground-command probe is a heuristic there (newest child chain, skipping `cmd`
  and `conhost`), not a process-group fact as on Unix.
- A Windows package must pass `-Dpty4j.preferred.native.folder=<image>/lib/pty4j-native`
  (or `-Dpty4j.tmpdir`) to both launchers so pty4j does not re-extract `conpty.dll` and
  `OpenConsole.exe` on every cold start, and the `jmux` launcher needs
  `win-console=true` so `list --json` and `capture` have a stdout.

## Layout

```
protocol/   frame types and codec, JSON helpers, operation and field names
vt/         tokenizer, SGR normaliser, query filter, mode tracker, headless terminal, screen serializer
session/    PTY session (pump, fan-out, attach handshake, kill), session manager, shell command, probes
server/     Unix-domain socket server, connection handling, lock/socket startup order, daemon mode
client/     SessionServerClient (what Harness Manager uses), AttachedSession, ServerLauncher
cli/        the command-line front end; Main is the entry point
```

## License and source cut

jmux’s original code is MIT licensed. Bundled dependencies retain their upstream
licenses; see [THIRD-PARTY.md](THIRD-PARTY.md) and `licenses/`.

This repository is generated from an allowlisted source snapshot. Its first
commit contains the public cut only; private development history and internal
workflow files are not copied. Later exports append ordinary public commits.

## Releases

See [PUBLISHING.md](PUBLISHING.md) for local release validation and the GitHub
Actions publishing workflow. Source exports and releases never copy private Git
history.
