# Streaming token→event parsing (issue #85)

Design and implementation plan for replacing the buffered-line
(`LineParser.lineReceived(ByteBuffer)`) model in all line-based protocol
handlers with a two-stage **lexer → parser** pipeline, matching the streaming
style already used by `RESPDecoder`, `H2Parser`, `GrpcFrameParser`, and
`JSONParser`.

Status: **in progress — Phase 0 (foundation), Phase 1 (POP3, server +
client), Phase 2 (FTP), Phase 3 (SMTP server + client), and Phase 4.1
(IMAP server) complete. Phase 4.2 (IMAP client) next.**
This document is the working checklist — update the task tables as work lands
and record issues encountered inline. See §8 for the current phase-by-phase
status and issues found so far.

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
Parser (a state machine starting in `EXPECT_COMMAND`) matches the `KEYWORD`
token directly against known verbs — no `String` buffered — resolving to an
enum (e.g. `SMTPCommand.EHLO`) immediately; `ATOM` is retained as the domain
argument (it must be, it's a value the command needs); on `CRLF` it calls
`handleEHLO("mail.sender.com")` directly via a `switch` on that enum — see
§3.3 for why this, not a buffered-string `dispatchCommand(String, String)`
re-parsed at `CRLF`, is the target shape.

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
parse state machine. The **command-execution logic itself is the seam and stays
untouched** — every existing `handleXXX(args)` method (business logic: `handleQUIT`,
`handleUSER`, `handleAuthPLAIN`, …) keeps its current signature and body. What
changes, and must change, is everything **upstream** of that: how the handler
decides *which* `handleXXX` to call.

**Target shape: resolve identity to an enum at the token that determines it;
dispatch by switching on that enum. Never buffer a command/mechanism/verb as a
`String` to re-parse or string-`switch` on later** — that is just the old
whole-line buffering pushed one step downstream, and gives up the efficiency
and clarity the token model is for. Concretely, established by the POP3 server
conversion (§8 Phase 1) and the model for every later phase:

- The **first token that identifies a command/verb/mechanism** is matched
  directly against known values — as raw bytes against known byte sequences
  where it comes straight off the lexer (e.g. `POP3ServerLexer.Token.KEYWORD`
  → `POP3Command`, via `matchCommand(ByteBuffer)`; no `String` allocated, no
  decode, for every *recognised* command), or via a canonical existing
  `fromName`-style lookup where it's a sub-value inside already-materialised
  text (e.g. the SASL mechanism name inside POP3's `AUTH` args →
  `SASLMechanism.fromName(...)`, reusing existing infrastructure rather than a
  hand-rolled `switch` on string literals). Either way, the result is an enum,
  resolved once, immediately — not a `String` field revisited later.
- A `String` is decoded **only when the value doesn't match anything known**
  and the raw text is genuinely needed (an error message quoting what the
  client sent), or when the token content must be preserved verbatim by
  contract (SASL continuation data, free-form args) — decoding is the
  exception path, not the default one.
- Dispatch, at the token/production that completes the command (typically
  `CRLF`), is a **direct `switch` on the resolved enum** calling the
  `handleXXX` method — not a re-parse, not a generic `dispatchCommand(String,
  String)` layer that re-derives what was already known. An enum `switch`
  compiles to a `tableswitch` on ordinal; a `String` `switch` is a
  hashCode-then-`equals` chain. Preferring the enum form is a real efficiency
  gain on top of the architectural one.
- Where a resolved-but-invalid-for-context value still needs its text for an
  error reply (e.g. `USER` sent while in `TRANSACTION` state — a *known*
  command, just wrong here), the enum constant's own `name()` **is** the exact
  canonical text already, at zero decode cost; only genuinely-unrecognised
  input needs the separately-decoded fallback string. See
  `POP3ProtocolHandler.unknownCommandText()` for the pattern.

This keeps the blast radius inside the parsing front end (`handleXXX` bodies
are untouched) while eliminating the deferred-string-dispatch shape that a
naive "just swap `LineParser` for `ByteStreamLexer`" pass tends to produce —
watch for it explicitly in every later phase (SMTP verbs, IMAP commands, HTTP
methods, …): if a phase's first draft has a `dispatchCommand(String, ...)` or
a `switch (someBufferedString)`, that draft was too shallow and should be
reworked to this shape before being called done.

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

**Server tokens:** `KEYWORD` (verb, ≤4 letters), `SP`, `TEXT`, `CRLF`.
**Server grammar:** `KEYWORD [SP TEXT] CRLF`. `KEYWORD` is matched directly
against known verbs at the token itself, resolving to a `POP3Command` enum
with no `String` buffered for the common case (§3.3) → direct `switch`-on-enum
dispatch to `handleXXX(args)` at `CRLF`. **Implemented; see §8 Phase 1 for the
as-built shape** (`POP3ServerLexer`, `POP3Command`, `matchCommand()`,
`dispatchCommand()`). AUTH sub-dialog: after `AUTH`, lexer stays in line mode
but parser routes the next line's `KEYWORD` (checked against `authState` at
the token, before any verb-matching is attempted) to `handleAuthContinuation`
as raw text — mirrors current `authState`.
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
**Server grammar:** `KEYWORD [SP REST_OF_LINE] CRLF`. As with POP3 (§5.1),
`KEYWORD` is matched directly against known SMTP verbs at the token, resolving
to an `SMTPCommand`-style enum with no `String` buffered for recognised verbs,
dispatched via `switch`-on-enum at `CRLF` — not a buffered-string
`dispatchCommand(String, String)` (§3.3). The
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
- **`handleXXX(args)` command-execution bodies are the seam — do not touch
  them.** Everything *upstream* of them is fair game and, per §3.3, is
  expected to change shape: replace deferred `String`-buffer-then-dispatch
  with resolve-to-enum-at-the-identifying-token, direct `switch`-on-enum
  dispatch. `reply`, `processDataBuffer`, `receiveLiteralData`, … (the
  non-command-verb dispatch points) follow the same principle where they
  have an equivalent identifying token (e.g. an IMAP response type, an SMTP
  reply code range).
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

### Phase 0 — foundation ✅ done

- [x] D1 resolved: zero-copy windowed `token(type, buf)`, no copies in the lexer.
- [x] D2 resolved: `boolean token()` return latches text mode until `CRLF`; text
      mode emits chunked `TEXT` windows (no lexer buffering) then `CRLF`; parser
      buffers/streams/discards as it chooses; shared *discard-until-CRLF* recovery.
- [x] Implemented `ByteStreamLexer<T extends Enum<T>>`
      ([src/org/bluezoo/gumdrop/ByteStreamLexer.java](../src/org/bluezoo/gumdrop/ByteStreamLexer.java))
      to the §3.4 API: `feed(ByteBuffer)` drive loop, windowed `boolean
      token(T, ByteBuffer)` / `rawBytes(ByteBuffer)` / `tokenTooLong()` on the
      nested `Handler<T>` interface, `consume(byte)` abstract scan step,
      `emit(T, int, int)`, `enterRaw(long)` / `enterRawUntil(byte[])`,
      `currentPosition()`, and `regionStart()` (see below). **Grew one more
      primitive during Phase 1:** `requestStop()` / `MODE_STOPPED`, for
      handing control to something that reads raw bytes directly outside
      this lexer's token/text/raw modes entirely (POP3 client's
      `DotUnstuffer` handoff) — see Phase 1's client write-up below for why
      the first attempt at this was subclass-local and silently broken.
- [x] `checkTokenCap(int maxTokenLength, int maxNetInSize)` static helper added
      per §3.5, to be called by each per-protocol lexer once its transport's
      `maxNetInSize` is known.
- [x] Reusable parser base providing *discard-until-CRLF* recovery: implemented
      as a small composed helper, `TokenErrorRecovery<T extends Enum<T>>`
      ([src/org/bluezoo/gumdrop/TokenErrorRecovery.java](../src/org/bluezoo/gumdrop/TokenErrorRecovery.java)),
      not a base class — protocol handlers already extend other things, so a
      parser holds an instance as a field and delegates to it from `token()`.
- [x] Scaffold unit tests: `ByteStreamLexerTest` (25 tests) and
      `TokenErrorRecoveryTest` (7 tests), both in
      `test/junit/src/org/bluezoo/gumdrop/`, using a minimal toy protocol
      independent of any real handler. Cover token boundary splitting, window
      validity, chunked TEXT across `feed()` slices (incl. CRLF split across
      calls), cap enforcement (incl. exempting text mode), `RAW(n)` /
      `RAW_UNTIL(delim)` entry + in-`feed()` resume + split-across-calls +
      false-match recovery, underflow rewind fuzzed at every chunk size from 1
      byte up, the `consume()` abort escape hatch, and constructor validation.
      `ant test` full suite green.

**Issues found and fixed during implementation** (kept here for the next
phase's implementer):

1. **`emit()` stomped a nested raw-mode request.** For the CRLF token type,
   `emit()` unconditionally reset `mode = MODE_TOKEN` after calling
   `handler.token()` — which clobbered `mode` if the handler had synchronously
   called `enterRaw()`/`enterRawUntil()` from *within* that same callback (the
   exact pattern used to accept SMTP DATA / open an IMAP literal). Fixed by
   only applying `emit()`'s own mode transition when the callback left `mode`
   unchanged; if the callback changed it, that change is preserved. All raw
   escapes are entered this way — nested inside the token dispatch, not as a
   separate call after `feed()` returns.
2. **Missing `mode = MODE_TOKEN` reset caused a genuine infinite loop.**
   `continueRawFixed()` and `continueRawUntil()` didn't reset `mode` back to
   `MODE_TOKEN` on successful completion (unlike text mode, which goes through
   `emit()` for its own reset). Once a fixed-length raw payload was fully
   consumed, `continueRawFixed()` became a no-op that kept returning `true`
   without advancing the buffer — `feed()`'s outer loop spun forever whenever
   there was trailing structured data after the raw payload. **Caught by two
   real hung JVMs during test development, not by inspection** — a strong
   argument for the fuzz/fuzz-like fixed tests in §6, and a reminder to watch
   for this same "did I reset `mode` on the non-`emit()` completion path"
   pattern in any future raw-mode-like addition.
3. **`enterRawUntil()`'s delimiter fully excludes itself from delivered
   content, including bytes it shares with what looks like content.** For
   `RAW_UNTIL("\r\n.\r\n")` (SMTP DATA / POP3 dot-termination), the CRLF
   immediately before the terminating `.` is consumed as part of the 5-byte
   delimiter match, **not delivered as the content's own trailing line
   terminator**. This is required for correctness — anchoring the delimiter to
   include the leading CRLF is what prevents a false match on ordinary content
   like `"Hello.\r\n"` appearing mid-body (a bare `".\r\n"` search would wrongly
   terminate on that). But it means content delivered via `rawBytes()` for a
   non-empty DATA/multiline body will be missing its final CRLF relative to
   what was actually sent. **Phase 3 (SMTP) and Phase 1 (POP3 client) must
   account for this explicitly** when applying `DotUnstuffer` / reconstructing
   message content — do not assume the delivered raw bytes end where the
   original content's last line did.

### Phase 1 — POP3 ✅ done (server + client)

- [x] POP3 server lexer + parser; route AUTH sub-dialog.
      `POP3ServerLexer` ([src/org/bluezoo/gumdrop/pop3/POP3ServerLexer.java](../src/org/bluezoo/gumdrop/pop3/POP3ServerLexer.java))
      implements the `KEYWORD [SP TEXT] CRLF` grammar exactly as planned in
      §5.1. `POP3ProtocolHandler` now implements
      `ByteStreamLexer.Handler<POP3ServerLexer.Token>` instead of
      `LineParser.Callback`; `receive()` delegates to `lexer.feed(data)`.
      `handleAuthContinuation` and every per-command `handleXXX(args)`
      method (the actual command logic) are untouched, confirming the
      "seam" design in §6 held — but see the dispatch refactor below,
      which goes further than the initial pass and replaces the
      *dispatch* layer itself (not just how it's fed).
- **Command dispatch refactored to resolve the verb once, at the KEYWORD
  token, as an enum — not deferred as a string.** The initial pass had
  kept the old shape: buffer a `String` keyword, uppercase it, and
  `switch (String)` on it at CRLF via `handleCommand`/
  `handleAuthorizationCommand`/`handleTransactionCommand`. That's just the
  same string-dispatch work moved later, and defeats a chunk of the point
  of tokenising. Reworked so the command is known as soon as its token
  arrives:
  - Added `POP3Command` enum (`QUIT, CAPA, NOOP, USER, PASS, APOP, AUTH,
    STLS, UTF8, STAT, LIST, RETR, DELE, RSET, TOP, UIDL, UNKNOWN`).
  - `matchCommand(ByteBuffer window)` matches the KEYWORD token's raw
    bytes directly against known verbs, case-insensitively, **with no
    String allocation and no decode** for the recognised-command case.
    Refined from an initial linear chain of up to 14 sequential 3/4-byte
    comparisons to: each byte is read exactly once and case-folded while
    packing all of them into a single `int` (`pack3`/`pack4`), then
    dispatched with **one `switch` on that packed int** — a
    lookupswitch/tableswitch instead of a comparison chain. Length-3
    (`TOP`) and length-4 verbs are packed and switched *separately*, not
    padded into a shared 4-byte int, specifically to avoid a contrived but
    real edge case: padding a 3-byte verb with a zero byte to compare
    against 4-byte packed values would let a literal NUL byte sent as a
    4th input byte falsely match the padded verb. A String is decoded only
    for the unrecognised-verb case, where the exact text is needed for the
    `-ERR unknown command` reply.
  - The resolved `POP3Command` (a field, `pendingCommand`) replaces the
    old `pendingKeyword` String entirely for the command path. SASL
    continuation lines are detected at the *same* KEYWORD token (checking
    `state`/`authState`, which cannot change mid-line) and take a
    completely separate branch — they were never really "commands" and
    are never matched against verbs at all, just decoded (case preserved)
    into `pendingContinuationText`.
  - `dispatchLine()` now calls `dispatchCommand(POP3Command, ...)`, a
    direct `switch` on the enum (replacing the three deleted
    string-switching methods entirely) — an enum switch compiles to a
    `tableswitch` on ordinal, versus a `String` switch's hashCode+equals
    chain, so this is a genuine efficiency gain in addition to the
    architectural one.
  - One correctness subtlety: the "unknown command" error message needs
    the exact command text in **two** distinct cases — a verb that
    matched nothing (`pendingUnknownText`, decoded once) and a verb that
    matched something valid but in the *wrong state* (e.g. `USER` while
    in `TRANSACTION`) — for the latter, decoding is unnecessary since
    `command.name()` **is** the exact uppercased text already, at zero
    cost. `unknownCommandText(command, unknownText)` picks the right one.
  - Old `handleCommand`/`handleAuthorizationCommand`/
    `handleTransactionCommand` deleted entirely (verified no external
    references first).
- **Same "resolve to an enum once, don't string-switch later" pattern
  applied to the SASL mechanism dispatch in `handleAUTH`.** This one
  turned out to need no new code at all: `SASLMechanism.fromName(String)`
  (case-insensitive) already existed in `org.bluezoo.gumdrop.auth` and was
  going unused — `handleAUTH` was manually `.toUpperCase()`-ing the
  mechanism substring and `switch`-ing on 8 string literals, duplicating
  what the enum's own lookup already does. Replaced with
  `SASLMechanism.fromName(mechanismText)` (also drops the now-unneeded
  `toUpperCase` allocation) followed by a `switch` on the enum. Note this
  one is *not* resolved at the lexer/token boundary like the command verb
  was — the mechanism name is a substring of the already-opaque
  `TEXT`-mode `args`, not a separate structured token, so there was no
  buffering to avoid here; the win is purely "enum switch, not string
  switch" plus reusing existing canonical parsing instead of duplicating
  it. Verified against `ant integration-test-pop3`'s `testAuthPlain` /
  `testAuthLogin`, which exercise this exact path end-to-end.
- [x] POP3 client lexer + parser.
      `POP3ClientLexer` ([src/org/bluezoo/gumdrop/pop3/client/POP3ClientLexer.java](../src/org/bluezoo/gumdrop/pop3/client/POP3ClientLexer.java))
      implements `WORD [SP TEXT] CRLF` — the same grammar as the server side,
      grammatically. `POP3ClientProtocolHandler` now implements
      `ByteStreamLexer.Handler<POP3ClientLexer.Token>` instead of
      `LineParser.Callback`. Two design decisions diverge from §5.1's
      original wording, both recorded here because they matter for later
      client phases (SMTP, IMAP):
  - **RETR/TOP message content deliberately does *not* go through
    `enterRawUntil()`.** §5.1 originally said "RAW_UNTIL(...) with
    dot-unstuffing (reuse DotUnstuffer)" — but `DotUnstuffer` already does
    *both* boundary detection *and* the unstuffing transform in one pass,
    correctly, in constant memory, across arbitrary chunk boundaries; it
    already meets this whole effort's bar. Routing it through
    `enterRawUntil()` would only find the `\r\n.\r\n` boundary — the
    delivered bytes would still be dot-*stuffed*, requiring a *second*,
    separate unstuffing pass over them anyway, for no benefit and real
    risk (reimplementing tested, subtle state-machine logic). Instead
    `POP3ClientProtocolHandler.receive()` keeps its pre-existing structure
    unchanged: branch on `dotUnstufferActive`, drive `DotUnstuffer`
    directly off raw bytes when active, the lexer otherwise. This lexer
    only needed to learn when to *get out of the way* — which is `requestStop()`,
    below, and turned out to require a real fix to the shared scaffold.
  - **`ByteStreamLexer.requestStop()` — new shared primitive, added here,
    born from a bug.** The first implementation gave `POP3ClientLexer` its
    own local `stopRequested` flag, checked only inside its own
    `consume()`, set by the handler (via a package-visible wrapper) from
    within `dispatchRetrReply()`/`dispatchTopReply()` — the same
    "nested call during token dispatch" pattern already used for
    `enterRaw`/`enterRawUntil`. **It silently didn't work for the common
    case.** Any response line with a space in it (`"+OK 13 octets"`, not
    just bare `"+OK"`) latches text mode, and text mode's terminating
    CRLF is emitted by the *base class's own* `continueText()` — which
    never calls the subclass's `consume()` at all. The subclass-local
    flag was checked in the one place that, for exactly the case that
    mattered, was never reached. Caught by
    `testRetrOkAndContentInSameReadDoNotDesyncLexer` (assertion failure,
    not a hang this time — a quieter, easier-to-miss failure mode than
    Phase 0's infinite loop, and arguably more dangerous for that reason).
    Fixed properly by promoting this to the base class: a new
    `MODE_STOPPED` mode and `protected final void requestStop()`, hooking
    into the *existing* `mode == modeBeforeCallback` check inside `emit()`
    that already makes `enterRaw`/`enterRawUntil` work correctly when
    called from a nested callback — no matter which internal path (`consume()`
    or `continueText()`) is what actually emitted the triggering token,
    `emit()` is the one chokepoint both go through, so this now works
    uniformly. `feed()` normalises `MODE_STOPPED` back to `MODE_TOKEN`
    both mid-loop and at loop exit (a token completing on the very last
    byte of a buffer needed the latter case explicitly). **Any future
    phase that needs "hand control to something reading raw bytes
    directly, outside token/text/raw modes entirely" should use
    `requestStop()` — do not reinvent a subclass-local flag; it will have
    this exact gap.**
  - Status markers (`+OK`, `-ERR`, the bare `+` SASL continuation prefix)
    are matched directly off the `WORD` token's bytes via `matchStatus()`,
    resolving to the *existing* `POP3Response.Status` enum (no new enum
    needed) — same "resolve once, at the token" principle as the server's
    `matchCommand()`, but case-*sensitive* (unlike command verbs), matching
    the pre-streaming `POP3Response.parse()`'s `String.startsWith` exactly.
    `POP3Response.parse(String)` itself and its dedicated test
    (`POP3ResponseTest`, 18 tests) are untouched — the handler simply stopped
    calling it, constructing `POP3Response` directly from the resolved
    status + accumulated text instead.
  - No overall line-length cap for the client (matches the pre-streaming
    `LineParser.parse(data, this)` two-arg call, which had none either —
    the client trusts the server it connected to; `maxTokenLength =
    Integer.MAX_VALUE`, and `checkTokenCap()` is correctly *not* called,
    since it would always throw for an intentionally-unbounded cap).
  - Decoding is lenient (`new String(bytes, US_ASCII)`, replacing invalid
    bytes) rather than strict, matching the pre-streaming client's own
    behaviour exactly — this is a deliberate *difference* from the
    server's strict `CharacterCodingException`-throwing decode, because
    the **old code differed the same way** between client and server; not
    an inconsistency introduced by this conversion.
- [x] Golden-transcript + sliced-boundary tests for **both** server and
      client. Server: extended `POP3ProtocolHandlerTest` (6 new tests:
      byte-at-a-time, every-chunk-size fuzz up to 12, pipelined commands in
      one buffer, lexer-cap and parser-tracked-length "line too long" +
      resync, empty line) and added `POP3ServerLexerTest` (8 tests) directly
      verifying token content, including the embedded-double-space
      verbatim-preservation property the TEXT-mode reconstruction depends
      on. Client: extended `POP3ClientProtocolHandlerTest` (5 new tests:
      greeting byte-at-a-time, greeting every-chunk-size fuzz, CAPA
      multi-line every-chunk-size fuzz, and the `requestStop()`-proving
      same-buffer RETR test above). Full `ant test` green throughout (POP3:
      106 + 8 server, 52 + 18 client = 184 POP3-specific tests; plus the full
      pre-existing suite unaffected), **and** `ant integration-test-pop3`
      green after both the server and the shared-scaffold `ByteStreamLexer`
      change (real `TCPEndpoint` sockets) — 22/22 (this target only exercises
      the server; there is no dedicated client integration target — the
      existing `POP3ClientHelper` integration test helper is an independent
      reference client, not `POP3ClientProtocolHandler`, so client coverage
      relies on the unit tests above, which do model the real
      persistent-buffer + `compact()` transport contract).
- [x] Remove `LineParser.Callback` — done for both server and client (no
      remaining reference in `POP3ProtocolHandler.java` or
      `POP3ClientProtocolHandler.java`; `LineParser` itself is not removed
      yet, per §6's "delete only after the last handler stops implementing
      it" — POP3 is the first of five protocols).

**Design decisions made while implementing the server:**

- **Args/SASL-continuation via TEXT mode, reconstructed by the parser.**
  `SP` latches text mode for everything after the first space (matching the
  pre-streaming split-on-first-space semantics exactly, including preserving
  embedded/repeated spaces verbatim — verified directly by
  `POP3ServerLexerTest.testArgsWithEmbeddedDoubleSpacePreservedVerbatim`).
  For SASL continuation lines (`authState != NONE`), the parser reconstructs
  the original raw line as `keyword + " " + args` when an `SP` was seen, or
  just `keyword` otherwise — exact for a single separating space, which is
  the only kind `SP` ever represents.
- **Overall line-length cap reproduced at the parser, not the lexer.** The
  lexer's own `maxTokenLength` cap only bounds the `KEYWORD` token (per
  §3.2/§3.5, text mode is exempt). To reproduce the old `LineParser`'s
  512-byte **whole-line** cap, `POP3ProtocolHandler` tracks a running
  `lineByteCount` (keyword + SP + args-so-far) and flags `lineErrorMessage`
  once it would exceed `MAX_LINE_LENGTH`, deferring the `-ERR` reply to CRLF
  time. This path does **not** need `TokenErrorRecovery` — text mode has no
  cap, so the lexer keeps scanning normally all the way to the real CRLF
  with no desync.
- **`tokenTooLong()` *does* need `TokenErrorRecovery`.** Unlike the
  parser-tracked overflow above, a lexer-level cap violation (an
  over-long `KEYWORD`, e.g. no spaces at all) leaves the lexer mid-line in
  a genuinely desynced position. `POP3ProtocolHandler.tokenTooLong()` calls
  `lexerRecovery.beginDiscard()`; every subsequent token — including ones
  from an unrelated, later command that happens to be re-lexed once the
  wire catches up — is discarded until the next CRLF, at which point normal
  dispatch resumes. This is the case Phase 0's `TokenErrorRecovery` helper
  was built for; POP3's otherwise-simple grammar doesn't need it anywhere
  else.
- **`tokenTooLong()` returns from `feed()` immediately, matching old
  behaviour exactly — not a regression, but worth flagging for every later
  phase.** `ByteStreamLexer.scanTokens()`'s cap-violation branch calls
  `handler.tokenTooLong()` and returns `false` **without** continuing to
  scan any remaining bytes already sitting in the same buffer — verified to
  be byte-for-byte the same shape as `LineParser.parse()`'s own
  cap-exceeded branch (`callback.lineTooLong(); return;`, no further
  scanning). Concretely: if a too-long line is immediately followed by a
  well-formed pipelined command **in the same network read**, that
  follow-up command will **not** be dispatched until a **separate**
  `receive()` call delivers more bytes (or re-delivers, via the transport's
  `compact()`, whatever was already sitting unconsumed) — it is not
  processed within the same `feed()` call just because the bytes are
  physically present. This matches the pre-existing `LineParser` model
  exactly, so it is not a regression, but it was surprising enough to trip
  up an initial version of the server's own test (see git history / test
  comments in `testLongKeywordTriggersLineTooLongAndResyncs`) and is worth
  restating for whoever implements Phase 5 (HTTP), where pipelined
  requests are common and this same property will apply identically.

### Phase 2 — FTP ✅ done

- [x] FTP server lexer + parser.
      `FTPServerLexer` ([src/org/bluezoo/gumdrop/ftp/FTPServerLexer.java](../src/org/bluezoo/gumdrop/ftp/FTPServerLexer.java))
      implements `KEYWORD [SP TEXT] CRLF` — the same grammar as POP3's server
      side. `FTPProtocolHandler` now implements
      `ByteStreamLexer.Handler<FTPServerLexer.Token>` instead of
      `LineParser.Callback`. Command dispatch was built enum-first from the
      start this time (§3.3's target shape, not the string-buffer-then-switch
      version POP3 initially had and had to be corrected afterwards): a new
      `FTPCommand` enum (45 verbs) is resolved directly from the `KEYWORD`
      token's raw bytes via `matchCommand()` — packed-int `switch`, no
      allocation for recognised verbs, same technique as POP3's
      (already-optimised) `matchCommand()` — and `dispatchLine()` calls
      `dispatchCommand(FTPCommand, String, String)`, a direct `switch` on the
      enum, replacing the old 45-branch `if/else` chain in `lineRead(String)`
      entirely.
  - **No continuation-mode branch needed, unlike POP3.** FTP's `AUTH
    TLS`/`AUTH SSL` (RFC 2228/4217) completes in a single command/reply
    exchange — there is no SASL-style multi-line challenge-response on the
    control channel — so every `KEYWORD` token is always a command verb,
    never raw continuation data. This made the FTP conversion structurally
    simpler than POP3's.
  - **No state-gated command sets, unlike POP3.** POP3's `AUTHORIZATION`/
    `TRANSACTION` states restrict which verbs are valid, requiring a nested
    `switch (state) { switch (command) }` and a "matched-but-wrong-state"
    text fallback (`command.name()`). FTP has no equivalent — every `doXxx`
    method checks `authenticated` itself — so `dispatchCommand()` is a
    single flat `switch`, and the `default:` (unmatched) branch can only
    ever be reached with `command == UNKNOWN`, making a POP3-style
    `unknownCommandText()` helper genuinely dead code here; written, then
    deleted once that was noticed.
  - **§5.2's original "IAC/ABOR" gotcha does not apply.** The plan's
    per-protocol breakdown flagged preserving TELNET IAC sequences and an
    ABOR out-of-band path as a risk. Checked the actual code: there is no
    IAC/Telnet handling anywhere in `FTPProtocolHandler`, and `doAbor()` is
    an ordinary command dispatched through the normal line-based path like
    any other verb — no special out-of-band/urgent-data mechanism exists in
    this codebase. Nothing needed preserving beyond ordinary byte
    pass-through, which `TEXT`-mode chunking already does.
  - **Whole-line cap reproduced the same two-part way as POP3** (lexer caps
    `KEYWORD` at `MAX_LINE_LENGTH` = 1024; parser tracks `lineByteCount`
    across `KEYWORD`+`SP`+`TEXT` for the combined budget; `tokenTooLong()`
    uses `TokenErrorRecovery` to discard-until-CRLF and resync, matching
    POP3's `tokenTooLong()`-needs-recovery /
    parser-tracked-overflow-doesn't split exactly).
  - **A pre-existing, unrelated bug was left alone, deliberately.**
    `L10N.properties` entries like `ftp.err.line_too_long` already have the
    reply code baked into the string (`"500 Command line too long"`), but
    every call site does `reply(500, L10N.getString(...))` — and `reply()`
    itself prepends `"%d %s"` — so the wire output is actually `"500 500
    Command line too long"`. This bug predates this conversion; fixing it
    is out of scope for a parsing-model change and the exact same buggy
    call pattern was reproduced verbatim rather than silently "fixed" as a
    drive-by.
- [x] Tests; suite green. **No pre-existing unit or integration coverage
      existed for `FTPProtocolHandler`'s control channel at all** — unlike
      POP3, which had 100 existing tests to extend, this was a from-scratch
      gap (confirmed: `ant integration-test-ftp`'s target pattern,
      `**/ftp/*IntegrationTest.java`, matches no files, and previously
      succeeded trivially as a no-op). Added `FTPServerLexerTest` (7 tests,
      direct token-content verification, mirroring `POP3ServerLexerTest`)
      and `FTPProtocolHandlerTest` (15 tests: greeting, basic dispatch,
      case-insensitive verbs, unknown command, sliced-boundary
      byte-at-a-time and every-chunk-size fuzz for both bare and
      argument-bearing commands, pathname-with-spaces fuzz, pipelined
      commands in one buffer, lexer-cap and parser-tracked "line too long" +
      resync, empty line) using `handler == null` (business logic —
      authentication, filesystem access — is unrelated to this conversion;
      only command recognition/dispatch is under test, and unauthenticated
      replies like `530` still prove correct routing). Full `ant test`
      green throughout.
- [x] Remove `LineParser.Callback` — done (no remaining reference in
      `FTPProtocolHandler.java`).

### Phase 3.1 — SMTP server ✅ done
- [x] `SMTPServerLexer.java` (new) — `KEYWORD [SP TEXT] CRLF`, structurally
      identical to the POP3/FTP server lexers.
- [x] **DATA/BDAT content deliberately bypasses the lexer entirely** —
      unlike POP3/FTP, SMTP has two genuine binary/raw-content escapes
      (RFC 5321 §4.5.2 dot-stuffed DATA, RFC 3030 fixed-length BDAT). Both
      pre-existing state machines (`processDataBuffer`/`DataState` for
      DATA, `handleBdatContent`/`bdatBytesRemaining` for BDAT) were kept
      **completely unchanged** and are still driven directly from
      `receive()`'s own state check — exactly as before this conversion —
      rather than routed through the lexer's `enterRaw`/`enterRawUntil`
      primitives. `processDataBuffer` already correctly combines
      boundary-detection and dot-unstuffing in one pass in constant memory
      across arbitrary chunk boundaries (including the async-delivery
      retained-input replay path); reimplementing that as a lexer escape
      would only duplicate already-correct, subtle logic. Same principle
      as POP3 client's `DotUnstuffer` handoff (Phase 1).
    - `receive()` and `handlePipelinedCommands()` needed only the minimal
      `LineParser.parse(...)` → `lexer.feed(...)` swap; all surrounding
      state-check structure (`if BDAT ... else if DATA ... else { <parse>;
      if hasRemaining() { recheck state } }`) is untouched.
- [x] **`requestStop()` timing — checked fresh after `dispatchCommand()`
      returns, not eagerly inside `data()`/`bdat()`.** `dispatchLine()`
      calls `dispatchCommand(...)`, then separately checks `state ==
      SMTPState.DATA || state == SMTPState.BDAT` and only then calls
      `lexer.enterContentMode()` (a package-private wrapper around the
      base class's `protected final requestStop()`, same pattern as POP3
      client's `stopForHandoff()`). This matters because `bdat()` with
      `chunkSize == 0` synchronously completes via
      `handleBdatChunkComplete()` and may revert `state` back to
      `SMTPState.RCPT` **within the same call** (when `!bdatLast`) — the
      pre-conversion `LineParser`-based code's `continueLineProcessing()`
      was re-checked fresh by `LineParser` after every line and correctly
      did *not* stop in that case, letting a pipelined next command on the
      same line be processed normally. Checking state after dispatch
      (rather than inside `bdat()`/`data()`) reproduces this exactly.
      Verified with `testZeroChunkBdatDoesNotStopLexerAndPipelinedCommandRuns`.
- [x] `SMTPCommand` enum (16 verbs: HELO, EHLO, MAIL, RCPT, DATA, BDAT,
      RSET, QUIT, NOOP, HELP, VRFY, EXPN, STARTTLS, AUTH, XCLIENT, ETRN) +
      packed-int `matchCommand()`, built correctly from the start this
      time (no self-caught string-redecode mistake, unlike Phase 2). 14 of
      the 16 verbs are exactly 4 bytes and share one packed-int switch;
      STARTTLS (8 bytes) and XCLIENT (7 bytes) don't fit a 32-bit pack and
      are the only verbs at their respective lengths, so they use a plain
      byte-by-byte `matchesLiteral()` comparison instead — there is
      nothing to *dispatch among* at those lengths, so a packed-int switch
      would add complexity with no benefit.
- [x] SMTPUTF8 (RFC 6531) charset-mode handling reproduced token-by-token
      rather than via the old whole-line re-scan:
    - `mightBeMailCommand`'s speculative UTF-8-vs-ASCII decoder selection
      is now `isMailKeyword()`, a pure byte comparison against the KEYWORD
      token (a 4-byte token that literally spells "MAIL" can, by
      definition, never itself contain non-ASCII bytes, so no decode is
      needed to test this) — computed once at the KEYWORD token as
      `pendingUseUtf8`, then reused for every subsequent TEXT chunk's
      decoder choice on that line, including for AUTH continuation data
      (mirroring the pre-conversion code, which computed the decoder once
      per line regardless of `authState`).
    - The retroactive rejection ("MAIL was UTF-8-flavoured but SMTPUTF8
      wasn't declared") no longer re-scans a buffered whole line: a
      `pendingSawNonAscii` flag is set if any TEXT chunk's raw bytes have
      the high bit set (checking raw bytes directly, rather than decoded
      `char > 127`, is equivalent for detecting "was there any non-ASCII
      content" and avoids a redundant decode), then checked once in
      `dispatchLine()` after `dispatchCommand()` returns.
    - One provably-redundant check from the old code was *not*
      replicated: `requireAscii && containsNonAscii(charBuffer)` after a
      strict US-ASCII decode. A strict `CharsetDecoder` (the JDK default
      REPORT action) already fails on any byte > 127 during the decode
      itself, so that whole-line re-scan could never actually fire — it
      was defensive-but-dead code, not a behavior difference.
- [x] AUTH continuation routing confirmed **simpler** than POP3's: no
      `SMTPState` gating at all (POP3 required `state == AUTHORIZATION &&
      authState != NONE`; SMTP only checks `authState != AuthState.NONE`),
      since AUTH can legitimately be issued from `SMTPState.READY`.
    - `charBuffer` (the old `CharBuffer` accumulator) was removed
      entirely — confirmed by grep it was used nowhere outside the
      replaced `lineReceived()`.
- [x] Tests: `SMTPServerLexerTest.java` (6 tests, direct token-level,
      mirroring `POP3ServerLexerTest`/`FTPServerLexerTest`) +
      `SMTPProtocolHandlerTest.java` (17 tests, new — no pre-existing
      handler-level coverage existed; `SMTPServerAuthTest.java` only
      exercised `SASLUtils` directly). Handler tests cover: basic
      dispatch, sliced-boundary fuzzing (byte-at-a-time and every chunk
      size), pipelined commands in one `receive()` call, line-too-long +
      resync (both KEYWORD-capped and parser-tracked TEXT overflow), DATA
      with dot-unstuffed content (including a pipelined DATA+content+
      terminator in one buffer), a pipelined command immediately after
      DATA completes, BDAT with content, and — the key regression
      target — the zero-chunk BDAT case where the lexer must *not* stop
      and a pipelined next command on the same line runs normally. All
      existing SMTP tests (`SMTPServerAuthTest` 30 tests,
      `SMTPClientProtocolHandlerTest` 22 tests) and integration tests
      (`SMTPServerIntegrationTest` 27, `LocalDeliveryIntegrationTest` 5,
      `SMTPClientIntegrationTest` 7 — including DATA, dot-stuffing, RCPT,
      multi-transaction, STARTTLS, and full local-delivery scenarios)
      pass unchanged. Full `ant test` and `ant integration-test-smtp`
      green.
- [x] `LineParser.Callback` removed from `SMTPProtocolHandler` (the
      client still implements it; removed once Phase 3.2 converts it).

### Phase 3.2 — SMTP client ✅ done
- [x] `SMTPClientLexer.java` (new) — reply grammar `CODE [SEP TEXT] CRLF`
      (RFC 5321 §4.2), structurally different from every server-side lexer
      so far: `CODE` is a **fixed 3-digit width**, not a variable-length
      run up to the first space, so the lexer tracks a small amount of
      per-line non-positional state (`sawCode`) instead of scanning for a
      delimiter. `SEP` is a single byte — `'-'` (`DASH`, continuation) or,
      leniently, any other byte (`SP`, final line) — consumed but not
      itself part of the message text, matching the pre-conversion code's
      own lenient `charAt(3) == '-'` check (any non-`'-'` separator byte
      was silently accepted and dropped, not just space).
    - `Integer.MAX_VALUE` `maxTokenLength` (no cap): this client trusts the
      remote server, same principle as `POP3ClientLexer` — structured
      tokens here (`CODE`, `DASH`, `SP`) are fixed at 1–3 bytes each
      anyway, so the cap is moot for the common case, but the constant
      documents the "trust the peer" client-side stance explicitly rather
      than leaving a magic small number to be questioned later.
    - No `requestStop()`/raw-escape machinery needed at all — the SMTP
      client only *sends* DATA/BDAT content (via the pre-existing,
      untouched `DotUnstuffer`), it never *receives* raw content, so
      `rawBytes()`/`tokenTooLong()` are structurally unreachable.
- [x] **Bug found and fixed during testing** (self-caught, not user-
      reported): `sawCode` was being reset to `false` only inside {@code
      consume(byte)}'s own CR/LF branch — but once `SEP` latches text mode
      (true for nearly every real SMTP reply, since almost all carry a
      status message), `consume()` is **not** re-entered for the rest of
      that line; the base class's `continueText()` emits the terminating
      CRLF directly. So `sawCode` stayed `true` into the next line, and
      the next line's first CODE digit was misinterpreted as the
      *separator byte left over from the previous line*, corrupting
      parsing of every line after the first in any multi-line response.
      This is the same class of bug as POP3 client's `requestStop()` saga
      in Phase 1 (subclass-local state that a generic base-class text-mode
      path silently bypasses) but caught by the new unit tests before
      reaching integration tests. Unlike `lastWasCR` in the command
      lexers — which self-heals because every non-`'\r'` byte
      unconditionally clears it, and a fresh line's first byte is never
      `'\r'` — `sawCode` has no such self-correcting property, since its
      *whole job* is to persist meaningfully across the CODE→separator
      boundary. Fixed by adding `SMTPClientLexer.resetForNextLine()`
      (package-private) and having the parser call it from its own
      `Token.CRLF` handling, which reliably fires exactly once per line
      regardless of which internal path produced the CRLF — the same
      "let the single chokepoint the parser always sees do the reset"
      principle used elsewhere in this conversion. Covered by
      `testMultilineEhloReplySequence` in the new lexer test, which fails
      without the fix.
- [x] Reply code resolved directly from the `CODE` token's raw bytes
      (`parseCode()`, a 3-digit unrolled loop) with **no `String`
      allocation** for the common (valid, 3-digit) case — a decode only
      happens on the rare malformed-code error path, mirroring the
      "decode only for the rare/error case" pattern used throughout this
      conversion (e.g. POP3/FTP/SMTP-server's unknown-command text).
- [x] `handleReplyLine(String)` removed entirely; its logic (421 special-
      case, continuation accumulation into `multiLineResponse`, dispatch)
      moved into `dispatchLine()`, operating on already-resolved
      `pendingCode`/`pendingContinuation`/`replyTextBuilder` state instead
      of re-parsing a buffered line string. A bare CRLF with no CODE token
      at all (blank line) is silently ignored, matching the pre-conversion
      `line.remaining() < 2` check.
    - `lineBuilder` (an unused leftover `StringBuilder` field never
      actually read anywhere in the pre-conversion code) removed.
- [x] Tests: `SMTPClientLexerTest.java` (7 tests, new, direct token-level)
      + all 22 pre-existing `SMTPClientProtocolHandlerTest` tests pass
      unchanged (EHLO capability parsing, MAIL FROM/RCPT TO extension
      params, VRFY/EXPN — this suite already gave strong regression
      coverage for the conversion once the `sawCode` bug above was fixed).
      `SMTPClientIntegrationTest` (7 tests, real client against real
      server: STARTTLS upgrade, SMTPS implicit TLS, multiple messages per
      session, multiple recipients) passes unchanged. Full `ant test` and
      `ant integration-test-smtp` green.
- [x] `LineParser.Callback` removed from `SMTPClientProtocolHandler`
      (`LineParser` import removed; no remaining reference).

### Phase 4.1 — IMAP server ✅ done
- [x] `IMAPServerLexer.java` (new) — `KEYWORD [SP TEXT] CRLF`, structurally
      identical to the POP3/FTP/SMTP server lexers. Crucially, **this
      lexer knows nothing about IMAP literals at all**: the `"{" number
      ["+"] "}" CRLF` production (RFC 9051 §4.3, RFC 7888) only ever
      appears immediately before a CRLF, so all literal detection lives in
      the parser, inspecting accumulated text when the `CRLF` token
      arrives, and calling `enterRaw(long)` from within that callback (the
      "nested call during token dispatch" pattern, same as every other
      raw escape in this conversion). This confirms the plan's original
      framing of IMAP as "hardest because of literals" — the existing
      pre-conversion code turned out to already be a hand-rolled version
      of exactly what `enterRaw` was designed for (its own class Javadoc
      cites IMAP `{nnn}` literals as the motivating example), so the
      actual work was mapping onto that primitive faithfully, not
      inventing new mechanism.
- [x] **Scope boundary, deliberately**: only the outer "get bytes off the
      wire into a fully-assembled logical command line" layer — the exact
      seam `LineParser` occupied — was converted. `dispatchCommand(tag,
      command, args)` and every per-command argument parser beneath it
      (quoted strings, parenthesized lists, `StringTokenizer` flag
      parsing, ~40 commands across several state-gated `switch (String)`
      blocks) is **completely unchanged**. Unlike POP3/FTP/SMTP, this
      dispatch was *not* converted to enum resolution: `switch (String)`
      already compiles to hashcode+equals dispatch in Java, not the
      sequential-if-else pattern the original enum-resolution instruction
      was correcting for in POP3 — so there was no efficiency or
      clarity win available, only risk, across a dispatch this large.
- [x] **Two literal kinds, two different fates, exactly preserved**:
    - **APPEND's own message-body literal** never touches the lexer's
      general-literal path at all. The pre-conversion `isAppendCommand`
      sniff (now `isAppendCommandWord()`, checking the first word
      assembled so far in `argsBuilder`) makes the generic CRLF handler
      leave APPEND's `{n}` marker untouched in the dispatched arguments
      text, exactly as before — APPEND's own business logic
      (`handleAppend`/`executeAppendDirect`/`AppendStateImpl`) parses it
      itself, completely unchanged. `receiveLiteralData` became
      `handleAppendLiteralBytes(ByteBuffer slice)`, invoked from
      `rawBytes()` — identical routing logic (discard-on-failure /
      buffer-while-mailbox-opening / stream-to-`appendDataHandler` /
      stream-to-mailbox, plus the `wantsPause()`/`endpoint.pauseRead()`
      backpressure check), just minus the "how much do I take from this
      buffer" arithmetic, since `enterRaw` already bounds `slice` to at
      most what's still needed.
    - **General-purpose literals** (RFC 7888 — a literal mailbox name,
      SASL/LOGIN credential, search key, anywhere else in a command)
      buffer into a small `ByteArrayOutputStream`, UTF-8 decode, and
      splice back into `argsBuilder` once complete — `receiveCommandLiteralData`
      became `handleGeneralLiteralBytes`, same simplification. A chained
      second literal on the continuation segment is detected the exact
      same way, at the next `CRLF`, with no special-casing — this is what
      `enterRaw`'s automatic resume-within-the-same-`feed()`-call
      behavior buys for free.
- [x] **Discovered during design, before writing code**: `enterRaw` is
      *always* triggered from within a token callback in every other use
      in this conversion, but APPEND's `appendLiteralRemaining` is not
      reliably set synchronously with `dispatchCommand()` returning — an
      application-provided `SelectedHandler`/`AuthenticatedHandler.append()`
      can defer calling `AppendStateImpl.readyForData()` asynchronously
      (e.g. behind a permission check), well after `dispatchCommand()` has
      already returned. A SMTP-style "check state after dispatchCommand()
      returns" post-dispatch hook (as used for DATA/BDAT) would silently
      miss this case. Fixed by calling `lexer.enterLiteral(literalSize)`
      directly at both of the two sites that actually set
      `appendLiteralRemaining` (`executeAppendDirect`'s synchronous path,
      and `AppendStateImpl.readyForData()`'s app-callback path) — the
      *only* place in this whole phase where business logic needed a
      one-line touch, and it's the same timing/race characteristics the
      pre-conversion code already had (the app callback could always defer
      past when bytes start arriving for a non-sync literal; this isn't a
      regression, just faithfully relocating the existing trigger point).
- [x] **A second, purely-lexical wrinkle, resolved without changing the
      lexer**: after a raw literal escape completes, `ByteStreamLexer`
      always resumes in structured token-scanning mode, not latched text
      mode (necessarily — that's what makes chained-literal detection
      possible at all). So resumed bytes arrive as an ordinary
      `KEYWORD`/`SP` pair, which — for content that continues *without* a
      leading space (e.g. a literal immediately followed by `)` closing a
      list, with no separating space) — would otherwise be misidentified
      as a fresh command's tag. Resolved entirely in the parser via a
      `freshCommand` flag: once false (past the real tag), a `KEYWORD`
      token's content is appended into `argsBuilder` verbatim instead of
      being captured as a new tag, and an `SP` token appends a literal
      space and re-latches text mode, which then handles the rest of the
      resumed segment normally via the base class's own `continueText()`.
      Covered by `IMAPServerLexerTest.testResumeAfterEnterRawWithoutLeadingSpace`.
- [x] The per-segment length budget (`segmentByteCount`, checked
      incrementally as `TEXT` chunks arrive) replicates the pre-conversion
      `lineLength > maxLineLength + 2` whole-physical-line check, reset
      once per segment (not cumulatively across a multi-literal logical
      command) — matching the original's own per-`lineReceived()`-call
      scope exactly.
- [x] **One deliberate, documented behavioral improvement, not a
      faithfulness gap**: the pre-conversion `lineTooLong()` did not reset
      `pendingCommand` when an oversized *continuation* segment (post-
      literal) was rejected, which looks like a latent bug — a subsequent
      line would be wrongly treated as continuing the abandoned command.
      `tokenTooLong()` now unconditionally calls `resetCommandState()`,
      abandoning the whole in-progress command (tag, args, literal-
      continuation state) rather than trying to preserve any of it. Noted
      here rather than silently diverging.
- [x] `receive()` simplified from ~42 lines of manual
      `appendLiteralRemaining`/`commandLiteralRemaining` pre/post checks
      and re-entrant `LineParser.parse`/`receive(buffer)` calls down to a
      single `lexer.feed(buffer)` — `enterRaw`'s automatic resume made all
      of that bookkeeping unnecessary, not just relocated it.
- [x] `charBuffer` (whole-line `CharBuffer` decode buffer) and
      `pendingCommand`/`commandLiteralRemaining`/`commandLiteralBuffer`
      removed; `LineParser.Callback` removed from `IMAPProtocolHandler`.
- [x] Tests: `IMAPServerLexerTest.java` (8 tests, direct token-level,
      including the post-raw-escape-resume regression test above) +
      `IMAPProtocolHandlerTest.java` (19 tests, new — no pre-existing
      handler-level coverage existed at all). Handler tests cover: basic
      dispatch, missing/invalid tag (including the "leading space" edge
      case), sliced-boundary fuzzing, line-too-long for both the tag and
      the args (confirming the reply correctly falls back to `currentTag`/
      `"*"` when the cap fires before any tag is known — matching, not
      fixing, the pre-conversion timing), and — the core new-ground
      coverage — RFC 7888 general-purpose literals via LOGIN: a
      synchronizing literal, a non-synchronizing (LITERAL+) pipelined
      literal, two **chained** literals in one command (proving the
      resume-and-detect-another-literal path), literal-too-large
      rejection with resync, and literal content sliced at every chunk
      size. APPEND's own literal (the specialised streaming path) is
      deliberately left to `IMAPServerIntegrationTest`'s
      `testAppendSynchronizingLiteral`/`testAppendNonSynchronizingLiteral`
      (26 tests total, unchanged, still green) — driving it meaningfully
      needs a real `MailboxStore`/`Mailbox`, which those tests already
      provide end-to-end, including the async mailbox-open buffering path
      that the non-sync test's own comment calls out. Full `ant test` and
      `ant integration-test-imap` green.

### Phase 4.2 — IMAP client
- [ ] IMAP client lexer + parser (`*` / tag / `+`, literals in responses).
- [ ] Tests; suite green; remove `LineParser.Callback` from the client.

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
