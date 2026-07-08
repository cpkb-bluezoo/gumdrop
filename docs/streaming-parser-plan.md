# Streaming token→event parsing (issue #85)

Design and implementation plan for replacing the buffered-line
(`LineParser.lineReceived(ByteBuffer)`) model in all line-based protocol
handlers with a two-stage **lexer → parser** pipeline, matching the streaming
style already used by `RESPDecoder`, `H2Parser`, `GrpcFrameParser`, and
`JSONParser`.

Status: **planning**. Nothing implemented yet. This document is the working
checklist — update the task tables as work lands and record issues encountered
inline.

---

## 1. The model

Today each handler does:

```
receive(ByteBuffer) → LineParser.parse() → lineReceived(ByteBuffer wholeLine)
                                              ↑ entire CRLF-terminated line
                                                buffered before dispatch
```

`lineReceived` then re-decodes the whole line, splits a verb from arguments, and
dispatches. Binary phases (SMTP `DATA`/`BDAT`, IMAP `{nnn}` literals, HTTP
bodies) escape line parsing via `continueLineProcessing() == false` and consume
raw bytes directly.

The target is a **byte → token → event** pipeline per protocol, driven by a
state machine, consuming as many whole tokens as the current buffer contains and
leaving a partial trailing token unconsumed for the next `receive()`:

```
receive(ByteBuffer) → Lexer.feed(buf)        // bytes → tokens (state machine)
                        → Parser token events  // tokens → semantic events
                          → handler method     // e.g. ehlo(domain), reply(code,text,last)
```

### Worked example — SMTP server, receiving `EHLO mail.sender.com\r\n`

Lexer emits: `KEYWORD("EHLO")`, `SP`, `ATOM("mail.sender.com")`, `CRLF`.
Parser (a state machine starting in `EXPECT_COMMAND`) consumes those tokens and,
on `CRLF`, emits the semantic event `ehlo("mail.sender.com")` — which is exactly
today's `dispatchCommand("EHLO", "mail.sender.com")` call, minus the whole-line
buffer.

### Worked example — SMTP client, receiving a multiline reply

```
250-smtp.example.com Hello\r\n
250 STARTTLS\r\n
```

Lexer emits: `NUMBER("250")`, `DASH`, `TEXT("smtp.example.com Hello")`, `CRLF`,
then `NUMBER("250")`, `SP`, `TEXT("STARTTLS")`, `CRLF`. Parser emits
`replyLine(250, "smtp.example.com Hello", last=false)` then
`replyLine(250, "STARTTLS", last=true)`.

Client and server have **different token vocabularies and grammars** because
they parse different languages (commands vs replies).

---

## 2. What this does and does not buy us

Be honest about the payoff so scope stays calibrated:

- **Real wins (primary justification):**
  - **Hard per-byte caps.** The cap is enforced as bytes arrive, closing the
    partial-line accumulation window flagged in SEC-032, instead of checking
    length only after a full line is assembled.
  - **Explicit state machines.** Protocol transitions (pipelining, `STARTTLS`
    handoff, literal-length extraction, AUTH sub-dialogs) become explicit lexer
    states rather than special-cased branches inside `lineReceived`.
  - **Architectural uniformity.** All wire parsing follows one streaming idiom.
  - **Constant memory for content transfers** already exists via the binary
    escape; the new model makes that escape a first-class lexer state rather
    than a `continueLineProcessing()` side channel.
- **Not a goal / modest at best:**
  - Dramatic memory reduction on **command lines**. Tokenisation itself is
    zero-copy (windows over the existing receive buffer), but the connection
    still needs a receive buffer sized to hold one maximal token — the same
    order as today's maximal line. What the parser retains shrinks to only the
    small values it deliberately copies out (a mailbox name, a domain). We are
    doing this for correctness/security/clarity, not headline memory numbers.

If the memory story were the only motivation this would not be worth the churn.
The state-machine clarity and hard caps are.

---

## 3. Architecture

Three layers. Only the bottom is shared; lexers and parsers are per-protocol
because the grammars differ.

### 3.1 Shared scaffold — `ByteStreamLexer` (new, `org.bluezoo.gumdrop`)

An abstract base that owns the mechanical concerns every lexer shares. It
**allocates nothing per token and copies no bytes** — exactly the `JSONParser` /
Gonzalez model:

- `feed(ByteBuffer)` — the drive loop over the connection's existing receive
  buffer. On recognising a complete token it emits **`boolean token(TokenType
  type, ByteBuffer buf)`**, where `buf` is *the same underlying buffer* with its
  position and limit temporarily windowed to the token's byte span. No `Token`
  object is materialised; no payload is copied. After the callback returns the
  loop restores the buffer's real limit and advances position past the token.
- **The `token()` return value is the parser→lexer feedback channel.** Returning
  `true` tells the lexer "everything from the next byte until `CRLF` is opaque
  text": it latches text mode, emits chunked `TEXT` windows (§3.2), and unlatches
  at `CRLF`. Returning `false` continues normal structured tokenisation. This
  replaces any separate `expectRestOfLine()` call — the switch rides on the
  return of the token that ended the structured prefix (a reply-code separator, a
  header-name colon, …). While text mode is latched the return value is ignored
  until `CRLF`.
- **Token lifetime contract (SAX `characters()` semantics):** the windowed
  buffer is valid **only for the duration of the `token()` call**. A callee that
  needs to retain the bytes (a mailbox name to store, a domain to resolve later)
  copies them out itself; a callee that only inspects them (verb dispatch,
  numeric parse) reads directly from the window with zero allocation.
- **Cross-`receive()` tokens need no side accumulator.** If a token has not
  terminated by the end of the current buffer, the lexer leaves its bytes
  unconsumed (rewinds position to the token start) and returns; the caller
  compacts the receive buffer and reads more into it, so the token's bytes
  eventually sit contiguously in that one buffer and can be windowed in a single
  callback. The only "accumulator" is the connection's own receive buffer, which
  already exists. **Constraint:** that buffer must be able to hold one maximal
  token, i.e. its capacity ≥ the per-token cap — the same constraint `LineParser`
  already imposed for a maximal line.
- **Cap enforcement (the SEC-032 hard cap):** a token that has not terminated
  within `maxTokenLength` bytes triggers `tokenTooLong()` immediately and stops
  the loop — enforced as bytes arrive, without ever assembling the over-long run.
- **Binary escape:** the subclass can enter `RAW(n)` or `RAW_UNTIL(delimiter)`,
  where `feed()` hands raw bytes straight to a `rawBytes(ByteBuffer slice)`
  callback without tokenising — for DATA, literals, chunk bodies — and returns to
  token mode when the count/delimiter is satisfied. Replaces
  `continueLineProcessing()`. These slices are windows too; no copy.

Subclasses implement the scan as a `protected abstract boolean consume(byte b)`
(or an equivalent transition table) that returns whether to continue.

> **D1 — resolved.** Zero-copy windowed `token(type, buf)`, no materialised token
> type, no buffer copies during tokenisation; the callee copies only if it needs
> to retain. Cross-read tokens handled by underflow-rewind on the receive buffer,
> not a side accumulator.

### 3.2 Per-protocol lexer

A subclass of `ByteStreamLexer` with a protocol-tuned token set and a small
state machine. Emits tokens to a `Handler` interface (implemented by the parser).
Kept deliberately thin: a token is a maximal run of one lexical class. The lexer
does **not** try to distinguish "domain" from "text" — that is context the
parser supplies. It classifies into runs like ATOM, NUMBER, SP, CRLF, and the
handful of separator bytes that protocol cares about (`-`, `<`, `>`, `:`, `=`,
`(`, `)`, `{`, `}`, `[`, `]`), plus a parser-requestable **text mode** for
free-form trailing text (reply text, header values, resp-text).

**D2 — resolved. The lexer never buffers freeform text.** Free-form trailing
text cannot be lexed context-free, so the parser still switches the lexer into
text mode after it has seen the structured prefix (e.g. after a reply code +
separator, or a header name + colon) — otherwise the lexer would split the value
into `ATOM`/`SP`/`ATOM`… and force the parser to reconstruct exact spacing. The
switch is signalled **by returning `true` from the `token()` callback** on the
token that ends the structured prefix (§3.1) — no separate method call. In text
mode the lexer **emits chunks, it does not accumulate**: it emits a `TEXT` token
windowing whatever contiguous text run is in the current `receive()` buffer,
flushes at the buffer boundary, and emits `CRLF` at line end. A value spanning
several network reads therefore arrives as **multiple `TEXT` tokens followed by
one `CRLF`**, each a zero-copy window. Text mode unlatches at `CRLF`.

Consequences:

- **Buffering is the parser's choice, per token.** A parser that must retain the
  whole value (e.g. an SMTP reply line it will match against) concatenates the
  `TEXT` chunks into its own small accumulator; one that can stream (write to
  disk, forward) consumes each chunk and keeps nothing; one recovering from an
  error discards them. The lexer is identical in all three cases.
- **No maximal-token receive-buffer constraint for text.** The
  "buffer capacity ≥ per-token cap" rule (§3.1) applies only to *structured*
  tokens (verbs, atoms, numbers, literal headers), which are short and capped.
  Freeform text is emitted incrementally, so an arbitrarily long value never has
  to fit in one buffer. Any overall length limit on the value is enforced by the
  parser as it counts chunk bytes.
- **Protocol-error recovery is a standard parser state:** on a malformed token
  sequence the parser enters *discard-until-CRLF*, drops every token until it
  sees `CRLF`, then emits the error reply and returns to its start state. This
  keeps the wire in sync without the lexer knowing anything about errors. Every
  protocol parser gets this recovery state as a shared pattern.

### 3.3 Per-protocol parser = the protocol handler

The existing handler implements the lexer's `Handler` interface and holds the
parse state machine. On each token it advances state; on `CRLF` (or on a
completed production) it invokes the **same semantic dispatch methods that exist
today** (`dispatchCommand`, `reply`, `handleAuthData`, …). The semantic events
are unchanged — only their production changes from "parse a buffered line" to
"reduce a token stream". This keeps the blast radius inside the parsing front
end and leaves command-execution logic untouched.

### 3.4 Concrete API shape (build to this, do not reinvent)

The scaffold `Handler` interface is exactly:

```java
public interface Handler {
    /** A complete structured token, windowed zero-copy over the receive buffer.
     *  Valid only for this call. Return true to latch text mode until CRLF. */
    boolean token(TokenType type, ByteBuffer window);

    /** A run of raw bytes during a RAW(n)/RAW_UNTIL escape, windowed zero-copy. */
    void rawBytes(ByteBuffer slice);

    /** The in-progress token exceeded the per-token cap; parsing has stopped. */
    void tokenTooLong();
}
```

`TokenType` is a **per-protocol enum** (server and client each have their own).
The scaffold base exposes to subclasses:

```java
protected abstract boolean consume(byte b);  // the subclass scan step
protected void enterRaw(long n);              // RAW(n): stream n raw bytes then resume tokens
protected void enterRawUntil(byte[] delim);   // RAW_UNTIL: stream until delimiter then resume
// emit helpers the subclass calls when it recognises a boundary:
protected boolean emit(TokenType type, int startPos, int endPos);
```

`feed(ByteBuffer)` is provided by the base: it runs `consume` / RAW handling over
the buffer, manages windowing and position, and returns when the buffer is
drained or a token underflows. **RAW→token resume happens within a single
`feed()` call** (needed for IMAP literals mid-command).

### 3.5 Transport buffer contract (verified against `TCPEndpoint`)

This is load-bearing; get it wrong and connections drop intermittently.

- The transport owns a persistent `netIn`. It calls `handler.receive(netIn)` then
  `netIn.compact()` (see `TCPEndpoint.processInbound`). So **whatever the lexer
  leaves before `netIn.position()` is preserved and re-presented on the next
  read** — this is the underflow mechanism; the lexer must leave `position` at the
  start of the first not-yet-complete structured token.
- `netIn` **grows by doubling up to `maxNetInSize`** when the handler
  underconsumes; if a single structured token cannot fit within `maxNetInSize`,
  the transport throws `IOException` and closes the connection.
- **Therefore every structured-token cap MUST be ≤ `maxNetInSize`.** Set
  `maxTokenLength` from the protocol's existing line-length constant
  (`MAX_LINE_LENGTH`, `MAX_COMMAND_LINE_LENGTH`, …) and assert it does not exceed
  `maxNetInSize`, so `tokenTooLong()` always fires *before* the transport's
  buffer-full close. Freeform `TEXT` and `RAW(n)` are exempt — they are consumed
  incrementally and never need to fit whole in `netIn`.
- **Line terminator is CRLF only**, matching `LineParser` (`LF` is a terminator
  only when the preceding byte was `CR`). A bare `LF` is an ordinary token byte.
  Do not "helpfully" accept bare `LF` — that would change existing behaviour.
- **Charset decoding moves into the parser, per token.** Each parser decodes the
  windowed bytes it cares about (ASCII/ISO-8859-1/UTF-8) when it needs a `String`;
  most tokens (verbs, codes) are compared as bytes with no decode. This *resolves*
  the SMTPUTF8 concern: the parser sees the verb token first and selects the
  decoder for the remaining tokens, so no whole-line UTF-8 peek is needed.

---

## 4. Build order

All work happens on the local branch **`streaming-protocol-parser`**. Shared
scaffold first, then protocols from simplest grammar to most entangled, proving
the pattern on a small handler before the big ones. HTTP/1 is in scope (both
client and server) and comes last because it is the most entangled.

Recommended sequence:

1. `ByteStreamLexer` scaffold + unit tests (no protocol).
2. **POP3** server + client — smallest grammar, clean multiline (`.`-terminated).
3. **FTP** server — verb/arg only, no binary interleave on the control channel.
4. **SMTP** server + client — adds DATA/BDAT binary escape and multiline replies.
5. **IMAP** server + client — hardest line protocol: `{nnn}` literals mid-line,
   tags, `+` continuation, LITERAL- (RFC 7888).
6. **HTTP/1** server + client — request/status line + headers; entangled with
   h2c preface, chunked, Content-Length. **HTTP/1 and the upgrade handshake
   only** — once the connection switches to HTTP/2, `H2Parser` frame parsing
   already owns the bytes and is out of scope. The client
   (`HTTPClientProtocolHandler`) parses with a bespoke `findCRLF` + `parseBuffer`
   state machine rather than `LineParser.Callback`, so its conversion is
   analogous but edits different code.

**Validate-and-pause cadence.** After *each* protocol (each numbered step) the
work stops for review: run the full `ant clean test` plus that protocol's
integration suite, confirm the lexer/parser shape reads well, and only then
delete that handler's line-buffering path (`LineParser.Callback` impl, or the
client's `findCRLF`/`parseBuffer` path). No protocol starts before the previous
one is validated and signed off.

---

## 5. Per-protocol breakdown

For each: token vocabulary, grammar → events (events already exist as dispatch
methods), and binary-mode escapes.

### 5.1 POP3

**Server tokens:** `KEYWORD` (verb, ≤4 letters), `SP`, `ARG` (atom), `CRLF`.
**Server grammar:** `KEYWORD [SP ARG [SP ARG]] CRLF` → `dispatchCommand(verb,
args)`. AUTH sub-dialog: after `AUTH`, lexer stays in line mode but parser routes
the next line to `handleAuthData` (base64 atom) — mirrors current `authState`.
**Binary escape:** none inbound. Outbound multiline (`RETR`/`TOP`/`LIST`/`UIDL`)
is response-side, unaffected.

**Client tokens:** `STATUS` (`+OK` / `-ERR`), `SP`, `TEXT` (rest-of-line),
`CRLF`; plus multiline data lines terminated by a lone `.`.
**Client grammar:** `STATUS [SP TEXT] CRLF` → `statusReply(ok, text)`. Multiline
commands enter a `RAW_UNTIL("\r\n.\r\n")` escape with dot-unstuffing (reuse
`DotUnstuffer`).

### 5.2 FTP (server, control channel)

**Tokens:** `KEYWORD` (3–4 letter verb), `SP`, `REST_OF_LINE` (arg — paths may
contain spaces, so arg is everything after the first SP), `CRLF`.
**Grammar:** `KEYWORD [SP REST_OF_LINE] CRLF` → existing command dispatch.
**Binary escape:** none on the control channel (data transfers are a separate
connection/handler). Simplest server conversion — good second target.
Gotcha: `TELNET` IAC sequences and the `ABOR` out-of-band case — preserve current
handling; the lexer should pass IAC bytes through unchanged in REST_OF_LINE.

### 5.3 SMTP

**Server tokens:** `KEYWORD` (verb), `SP`, `ATOM`, separators `<` `>` `:`,
`REST_OF_LINE`, `CRLF`. Enough to see `MAIL FROM:<path>` structure without
buffering the whole line.
**Server grammar:** `KEYWORD [SP REST_OF_LINE] CRLF` → `dispatchCommand`. The
UTF-8-vs-ASCII decision currently made in `lineReceived` (SMTPUTF8, the
`mightBeMailCommand` peek) moves to a lexer charset mode set when the verb token
resolves to `MAIL`/`RCPT`. AUTH sub-dialog routes to `handleAuthData` as today.
**Binary escapes:**
- `DATA`: `RAW_UNTIL("\r\n.\r\n")` with dot-unstuffing → `processDataBuffer`.
- `BDAT`: `RAW(n)` for the chunk size → existing BDAT path.
Both replace the `continueLineProcessing() == false` (`state == DATA || BDAT`)
branch.

**Client tokens:** `NUMBER` (3-digit code), `DASH`, `SP`, `TEXT`
(rest-of-line), `CRLF`.
**Client grammar:** `NUMBER (DASH|SP) TEXT CRLF`; `DASH` ⇒ continuation,
`SP`/end ⇒ last line → `replyLine(code, text, last)`. Reuses the existing
multiline accumulation (`line.charAt(3) == '-'`) but as a state transition. EHLO
capability parsing consumes the accumulated last-line set as today.

### 5.4 IMAP — hardest

**Server tokens:** `TAG` (atom before first SP), `SP`, `ATOM`/`KEYWORD`,
`QUOTED` (`"..."` with escapes), `LITERAL_HEADER` (`{nnn}` or `{nnn+}`),
parens/brackets `(` `)` `[` `]`, `CRLF`.
**Server grammar:** `TAG SP command …` → existing command dispatch. The critical
case: when the lexer emits `LITERAL_HEADER(n)`, the parser (per RFC 3501 sync
literals) sends the `+ OK` continuation, then the lexer enters `RAW(n)` to stream
the literal bytes to the current sink (APPEND message data → `receiveLiteralData`;
general command literal → `receiveCommandLiteralData`), then **resumes token mode
mid-line** for the rest of the command. This is the key advantage of the new
model: literal length is extracted from `{nnn}` and the escape is entered without
ever buffering the line. Non-synchronising literals `{nnn+}` (RFC 7888 LITERAL-)
skip the continuation. Maps directly onto the existing
`appendLiteralRemaining` / `commandLiteralRemaining` state.
**Binary escape:** `RAW(n)` per literal, possibly several per command, each
resuming token mode after.

**Client tokens:** `UNTAGGED` (`*`), `CONTINUATION` (`+`), `TAG` (atom),
`SP`, `ATOM`, `QUOTED`, `LITERAL_HEADER`, parens/brackets, `CRLF`.
**Client grammar:** `* …` untagged response, `tag OK|NO|BAD …` tagged result,
`+ …` continuation request → existing response handlers. Literals in server
responses use the same `RAW(n)` escape as the server side.

### 5.5 HTTP/1 (server + client)

**D3 — resolved. In scope: HTTP/1 message parsing and the upgrade handshake
only.** Once a connection switches to HTTP/2, `H2Parser` frame parsing already
owns the byte stream and is untouched by this work. Request/status line + header
field lines are line-based but HTTP is the most entangled case: h2c preface
handoff (recently reworked — `State.H2C_PREFACE`), chunked transfer coding,
Content-Length bodies, `:method`/`:path`/`:authority` pseudo-header synthesis,
header-count caps, and the existing `State` enum driving all of it. Highest
regression risk, so it goes last, after the pattern is proven on five protocols.

**Server** (`HTTPProtocolHandler`, a `LineParser.Callback` today):
- **Tokens (request line):** `METHOD`, `SP`, `TARGET`, `SP`, `VERSION`, `CRLF`.
- **Tokens (header):** `FIELD_NAME`, `:`, then text mode for the value
  (`token()` returns `true` after the colon) → chunked `TEXT` + `CRLF`; empty
  line ⇒ end of headers.
- **Binary escapes:** Content-Length `RAW(n)`; chunked = nested state
  (`chunk-size` line → `RAW(size)` → `CRLF` → repeat → trailer).
- Preserve `State.H2C_PREFACE`, header-count caps, and pseudo-header synthesis.

**Client** (`HTTPClientProtocolHandler`): parses HTTP/1 responses with a bespoke
`findCRLF` + `parseBuffer` (8192) state machine (`STATUS_LINE` → `HEADERS` →
`parseBody`), *not* `LineParser.Callback`. Conversion is analogous — status line
tokens `VERSION SP CODE SP REASON(text) CRLF`, header tokens as above — but edits
the client's own parse path rather than the shared `LineParser`.

---

## 6. Migration & safety strategy

- **Parallel, not big-bang.** Keep `LineParser` in place until every protocol is
  migrated; convert one handler at a time on its own branch, each behind a green
  `ant clean test` + integration run. Delete `LineParser` only after the last
  handler stops implementing `LineParser.Callback`.
- **Semantic dispatch methods are the seam.** Do not touch command-execution
  logic. The conversion is purely "how tokens/lines reach `dispatchCommand`,
  `reply`, `handleAuthData`, `processDataBuffer`, `receiveLiteralData`, …".
- **Shared parser recovery state.** Every protocol parser implements
  *discard-until-CRLF* (§3.2): on a malformed token sequence, drop tokens until
  `CRLF`, emit the protocol error reply, resync. Factor this into a small reusable
  base or mixin so all parsers get identical, tested recovery.
- **Golden-transcript tests.** For each protocol, capture representative wire
  transcripts (including split-across-`receive()` boundaries mid-token and
  mid-literal) and assert identical semantic dispatch under old and new paths.
  Tests live in `test/junit/src/org/bluezoo/gumdrop/<proto>/`; extend the existing
  per-handler test classes (e.g. `SMTPProtocolHandlerTest`,
  `IMAPClientProtocolHandlerTest`) rather than starting new suites, so the old
  behaviour is the oracle.
- **Fuzz the boundary handling.** Feed the same byte stream sliced at every
  possible offset (1 byte at a time, then every 2, …) via repeated `feed()` calls
  and assert the emitted token/event sequence is invariant — this is the property
  most likely to break and the one buffered lines hid.

---

## 7. Risks & implementation cautions (no open questions)

- **D1 — token payload model** (§3.1): **resolved** — zero-copy windowed
  `token(type, buf)`, no materialised token object, no lexer-side copies;
  cross-read tokens handled by underflow-rewind on the receive buffer.
- **D2 — lexer/parser feedback** for free-form trailing text (§3.2): **resolved**
  — `token()` returns `boolean`; a `true` return latches lexer text mode until
  `CRLF`. In text mode the lexer emits chunked `TEXT` windows (never buffers) then
  `CRLF`; the parser decides whether to concatenate, stream, or discard. Protocol
  errors handled by a shared parser *discard-until-CRLF* recovery state.
- **D3 — HTTP scope** (§5.5): **resolved** — HTTP/1 server + client are in scope
  (upgrade handshake included; HTTP/2 framing stays with `H2Parser`). Sequenced
  last as the highest-risk conversion.
All architectural decisions (D1–D3) are resolved. Remaining items are
implementation cautions, not open questions:

- IMAP mid-line literal resume is the single trickiest mechanic; do the Phase 4
  spike (§8) before committing the IMAP conversion.
- SMTP charset (SMTPUTF8) mode-switching: resolved by §3.5 (parser selects the
  decoder after seeing the verb token) — verify the `mightBeMailCommand`
  whole-line peek is fully replaced and covered by tests.
- FTP TELNET IAC / `ABOR` out-of-band handling must survive text-mode treatment;
  pass IAC bytes through unchanged in the argument `TEXT` token and keep the
  existing `ABOR` path.
- Client and server share a protocol package but not a grammar; do not try to
  unify their lexers — each gets its own `TokenType` enum and state machine.
- Per-token cap ≤ `maxNetInSize` (§3.5) — assert this at construction for every
  lexer.

---

## 8. Task checklist

Update as work lands. Record blockers inline under each item.

### Phase 0 — foundation
- [x] D1 resolved: zero-copy windowed `token(type, buf)`, no copies in the lexer.
- [x] D2 resolved: `boolean token()` return latches text mode until `CRLF`; text
      mode emits chunked `TEXT` windows (no lexer buffering) then `CRLF`; parser
      buffers/streams/discards as it chooses; shared *discard-until-CRLF* recovery.
- [ ] Implement `ByteStreamLexer` scaffold to the §3.4 API (feed loop emitting
      windowed `boolean token(type, buf)`, `true`→latch text mode until `CRLF`,
      underflow-rewind for structured cross-read tokens, chunked text mode, cap →
      `tokenTooLong()`, `RAW(n)` / `RAW_UNTIL(delim)` with in-`feed()` resume).
- [ ] Assert per-token cap ≤ `maxNetInSize` at construction (§3.5).
- [ ] Reusable parser base providing *discard-until-CRLF* recovery.
- [ ] Scaffold unit tests: token boundary splitting, window validity within the
      callback, chunked TEXT across `feed()` slices, cap enforcement, escape
      entry/resume, underflow rewind across `feed()` calls sliced mid-token,
      error recovery resync.

### Phase 1 — POP3
- [ ] POP3 server lexer + parser; route AUTH sub-dialog; keep `dispatchCommand`.
- [ ] POP3 client lexer + parser; multiline `RAW_UNTIL` + dot-unstuffing.
- [ ] Golden-transcript + sliced-boundary tests; full suite green.
- [ ] Remove `LineParser.Callback` from both POP3 handlers.

### Phase 2 — FTP
- [ ] FTP server lexer + parser (verb + REST_OF_LINE; IAC/ABOR preserved).
- [ ] Tests; suite green; remove `LineParser.Callback`.

### Phase 3 — SMTP
- [ ] SMTP server lexer + parser; DATA `RAW_UNTIL` + BDAT `RAW(n)`; SMTPUTF8
      charset mode; AUTH routing.
- [ ] SMTP client lexer + parser; multiline reply state machine; EHLO caps.
- [ ] Tests; suite green; remove `LineParser.Callback` from both.

### Phase 4 — IMAP
- [ ] Spike: mid-line `{nnn}` literal extraction → `+ OK` → `RAW(n)` → resume.
- [ ] IMAP server lexer + parser (tags, quoted, literals, LITERAL-).
- [ ] IMAP client lexer + parser (`*` / tag / `+`, literals in responses).
- [ ] Tests incl. literal split across `receive()`; suite green; remove
      `LineParser.Callback` from both.

### Phase 5 — HTTP/1
- [ ] Server (`HTTPProtocolHandler`): request-line + header lexer/parser;
      Content-Length + chunked escapes; preserve `State.H2C_PREFACE`, header
      caps, pseudo-header synthesis; HTTP/2 handoff to `H2Parser` untouched.
- [ ] Client (`HTTPClientProtocolHandler`): status-line + header lexer/parser;
      replace the bespoke `findCRLF`/`parseBuffer` path.
- [ ] Tests; suite green; remove old line-buffering paths.

### Phase 6 — teardown
- [ ] Confirm no remaining `LineParser.Callback` implementors.
- [ ] Remove `LineParser` once unused; update `SEC-032` notes (cap now per-byte).
