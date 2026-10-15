# Wire protocol v1

Transport: a Unix-domain socket at `<home>/server.sock`. Every message is a frame:

```
u32 length   big-endian; counts the type byte and the payload
u8  type
[]  payload  length - 1 bytes
```

Payloads are capped at 16 MiB (`Frame.MAX_PAYLOAD`); a larger length is a protocol error
and the connection is closed.

| type | name | direction | payload |
|-----:|------|-----------|---------|
| 1  | REQUEST        | client → server | JSON object: `{"op": …, "protocol": 1, …}` |
| 2  | RESPONSE       | server → client | JSON: `{"ok": true, …}` or `{"ok": false, "error": "…"}` |
| 16 | DATA_OUT       | server → client | raw session output (attach mode) |
| 17 | DATA_IN        | client → server | raw bytes for the session's PTY (attach mode) |
| 18 | RESIZE         | client → server | JSON `{"columns": n, "rows": n}` (attach mode) |
| 19 | DETACH         | client → server | empty; the server closes the connection |
| 20 | SESSION_EXITED | server → client | JSON `{"name": …, "exitCode": n}`; the server then closes |

## Connections

A **control** connection carries any number of REQUEST/RESPONSE pairs, in order. After a
successful `watch` the connection is in **watch mode** (see "Watch stream" below). After a
successful `attach` the connection is in **attach mode**: the server sends DATA_OUT frames
(the first ones are the redraw snapshot) and, eventually, SESSION_EXITED; the client sends
DATA_IN, RESIZE and finally DETACH. A RESPONSE with `ok: false` may still arrive in attach
mode (for example when the client is dropped for being too slow); it is followed by the
server closing the connection.

Size changes made by other clients are announced **in-band** on DATA_OUT as
`CSI 8 ; rows ; columns t`, ordered with the output they precede.

## Version handshake

Every REQUEST carries `"protocol": 1`. A server whose protocol differs answers

```json
{"ok": false, "error": "protocol mismatch", "protocol": <server's>, "version": "<artifact version>", "pid": <server pid>}
```

and the client reports it to the user without spawning, killing or replacing the running
server (it still hosts live sessions). The number is bumped on any change to frame types,
framing, or the meaning or removal of an existing field or operation; fields and
operations are added without a bump, and clients ignore unknown fields.

A daemon of the same protocol can therefore be older than its client. It answers an
operation it does not know with `unknown op: <op>` (the Java client raises that as
`SessionServerException.isUnsupported()`, message prefix `unsupported by this jmux daemon:`),
ignores request fields it does not know (`env` on `new`, `format` on `capture`), and omits
response fields it does not have. A client must not read meaning into an absence: the
Java client decodes a missing `outputBytes` as `-1` (unknown, the daemon predates the
counter), and refuses a non-`text` `capture` whose reply has no `format` echo rather than
pass plain text off as the requested format. For the same reason `new` acknowledges a
non-empty `env` with `"env": true` in its reply: a daemon that predates `env` creates the
session without it and omits the ack, which the Java client reports as
`NewSessionResult.envApplied() == false` (the session exists either way; the caller
decides whether to keep it). `requireBracketedPaste` on `input` has no ack: it was added
in the same change as `input` itself, so a daemon that answers `input` at all is expected
to honour it (a development build from before that point would paste raw instead).

## Operations

| op | request fields | response |
|----|----------------|----------|
| `ping` | — | `protocol`, `version`, `pid` |
| `list` | — | `sessions: [{name, workdir, columns, rows, pid, createdAt, clients, alive, backend, labels, outputBytes}]` |
| `has` | `name` | `present` |
| `new` | `name`, `workdir`, `columns`, `rows`, `command?` (string array; omitted = login shell), `labels?` (object of strings), `env?` (object of strings; see "Environment") | `pid`, `backend` (`unix` / `conpty` / `winpty`), `env: true` when the request carried a non-empty `env` |
| `kill` | `name` | — (an absent session is not an error) |
| `send-keys` | `name`, `text`, `enter?` (default true) | — (text, 400 ms pause, CR; the pause is server-side) |
| `capture` | `name`, `format?` (`text` default / `annotated` / `ansi`) | `text`, `alive`, `foregroundCommand`, `processId`, `title`, `columns`, `rows`, `format`, `outputBytes`, `cursorVisible` |
| `label` | `name`, `labels` (object; a `null` value removes the key) | `labels` (the merged result) |
| `input` | `name`, `text?`, `paste?` (default false), `requireBracketedPaste?` (default false), `keys?` (array of key names) | — (text first, then keys; at least one is required; `unknown key: X` fails the request before anything is written) |
| `wait` | `name`, `sinceBytes?` (default -1), `quietMs?` (default 300), `timeoutMs?` (default 5000); both durations clamped to 0..60000 | `quiet`, `outputBytes`, `alive` |
| `watch` | `names?` (array; absent or empty = every session, including ones created later), `quietMs?` (default 500) | — ; then watch mode |
| `current-path` | `name` | `path` |
| `attach` | `name`, `columns`, `rows` | — ; then attach mode |
| `shutdown` | — | — ; the server kills every session and exits |

Errors are `{"ok": false, "error": "<message for the user>"}`; `no such session: <name>`
is the message for a missing session on every session-addressed operation.

**Labels** are free-form string pairs a client attaches to a session (`new`, `label`) and
reads back from `list`; the server gives them no meaning.

**`outputBytes`** counts the raw bytes the session's PTY has produced (before the server
strips the queries it answers); it only grows. A client compares two values to tell
whether anything was drawn in between.

**`capture` formats.** `text` is the visible screen, one row per line, trailing
whitespace trimmed. `annotated` is the same rows plus two markers: `▶ ` before a row
that looks selected (a non-blank cell in reverse video, or on a background other than the
screen's dominant one; nothing is marked when more than a third of the non-blank rows
qualify, which is a colour theme rather than a selection) and `▮` inserted at the cursor
when it is visible. `ansi` is the redraw stream (see "Redraw snapshot") without history,
as a string.

**`input`** takes `text` (typed as raw UTF-8, or with `paste: true` sent as a paste,
wrapped in `CSI 200~ … CSI 201~` when the program enabled bracketed paste; inside the
bracket every ESC (0x1b) and C1 CSI (U+009B) character is removed from the text, so no
nesting of end markers can close the paste early; with `requireBracketedPaste: true` a
paste the program has not enabled bracketed paste for (DEC 2004) is refused with
`bracketed paste is not enabled in the session` and nothing, keys included, is written,
for a caller that must not have multi-line text typed as raw keystrokes) and then
`keys`, named case-insensitively: a single character; `Enter`, `Tab`, `BTab`/`S-Tab`,
`Esc`/`Escape`, `Space`, `Backspace`/`BSpace`, `Delete`/`DC`, `Insert`/`IC`, `Up`, `Down`,
`Left`, `Right`, `Home`, `End` (SS3 forms when the program set application cursor keys),
`PageUp`/`PgUp`/`PPage`, `PageDown`/`PgDn`/`NPage`, `F1`..`F12`; `C-x` for the control
character of a letter or one of `@[\]^_?` (`C-Space` is NUL); `M-key` for ESC followed by
`key`.

**`wait`** blocks the connection. With `sinceBytes >= 0` it first waits for
`outputBytes` to exceed it (if that never happens the screen counts as settled), then
for `quietMs` without output; `quiet: false` means `timeoutMs` (shared by both phases)
ran out. An exited session answers at once with `quiet: true, alive: false`.

## Watch stream

After `{"ok": true}` the server sends one RESPONSE frame per event, in order:

```json
{"ok": true, "event": "<type>", "name": "<session>", "outputBytes": n, "title": "…", "exitCode": n}
```

| event | when | extra |
|-------|------|-------|
| `output` | the first output after a quiet period, or after the watch started | — |
| `quiet` | `quietMs` passed without output after `output` | — |
| `title` | the OSC 0/2 title changed | `title` |
| `bell` | the program rang the bell (at most one per read batch) | — |
| `exit` | the session's process ended (also sent at once for a session that had already exited when it was registered) | `exitCode` |

The client only ever sends DETACH, which ends the stream; EOF ends it too. A watcher that
falls thousands of events behind gets `{"ok": false, "error": "…"}` and the server
closes the connection (a peer that stopped reading may not get the error frame: the
socket is closed after a short grace either way). An exited session's events end with `exit`; the stream itself stays
open (in all-sessions mode it keeps picking up new sessions).

## Environment

Every session's process starts with `JMUX_SESSION=<session name>` in its environment
(plus `TERM=xterm-256color`, `COLORTERM=truecolor`, and `LANG` defaulted to
`en_US.UTF-8`), so a program can tell which jmux session it runs in.

`new` may carry `env`, an object of string → string variables merged into that
environment after these defaults: it can add variables or replace inherited ones,
`COLORTERM` and `LANG`, but `TERM` and `JMUX_SESSION` always keep the values above. Names
must be non-empty and contain neither `=` nor NUL; values must not contain NUL.

## Redraw snapshot

The first DATA_OUT bytes after `attach` are a complete redraw at the client's requested
size: a reset prefix (`CSI ?1049 l`, `CSI 0 m`, `CSI ?7 h`, `CSI r`, `CSI ?25 l`,
`CSI H`, `CSI 2 J`, `CSI 3 J`), up to N history lines and the screen as styled runs, then
the scroll region, autowrap, cursor shape, bracketed paste, cursor-key and keypad modes,
mouse tracking, focus reporting, the title, the current SGR pen, the cursor position, and
cursor visibility last. A client emulator of the same size ends up in the same state as
the server's. If a string sequence (OSC/DCS) is in progress on the session at attach time,
its bytes so far follow the snapshot so the client sees it whole.

## Query handling

The server's emulator answers primary/secondary Device Attributes (`CSI c`, `CSI > c`),
DSR 5 (`CSI 5 n`) and the cursor-position report (`CSI 6 n`) and strips those requests
from DATA_OUT; it strips and ignores XTWINOPS 8 (`CSI 8 ; … t`) from the program. It
passes through `CSI ? 996 n` (light/dark preference), OSC 10/11 colour queries, DECRQM,
XTVERSION and everything else, so clients answer those exactly as they would on a direct
PTY. SGR sequences with colon sub-parameters are rewritten to semicolon form before both
the server emulator and clients see them.
