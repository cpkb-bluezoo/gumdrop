# Contributing to Gumdrop

Thank you for your interest in contributing to Gumdrop. This document covers how to test and submit changes, as well as the coding standards we follow. For build and run instructions, see [BUILDING.md](BUILDING.md).

## Testing

### Test policy

Production changes in `src/` should ship with **unit tests** that exercise the new or changed behaviour. Add **integration tests** when end-to-end behaviour over real I/O is important; they are optional but encouraged for protocol and server work.

**Unit tests** (`test/junit/src`):

- Exercise **program logic only**. They **must not** open network sockets (including loopback), send datagrams, or perform **real file I/O** on disk. Use mocks, stubs, in-memory buffers, and fakes (for example `RecordingStubEndpoint`) to supply the data and callbacks production code would get from I/O.
- Written with **JUnit** (and Hamcrest assertions already in `test/junit/lib/`). The suite is run by **`ant test`** and is what **CI** expects to pass on every change.

**Integration tests** (`test/integration/src`):

- Anything that relies on **real I/O**: live sockets, the network stack, files on disk, subprocesses, or similar. They may use local development infrastructure (certificates, ports, services on your machine).
- **Not guaranteed** to pass on every CI runner or in restricted environments. Run them **locally** when you touch behaviour they cover (`ant integration-test`, or `ant test-all` for unit plus integration).
- May use different reporting styles (console checks, manual verification, ad hoc harnesses). Follow patterns in existing integration tests for the protocol or subsystem you are changing.

**Test dependencies:** do not add new test frameworks or libraries (Mockito, TestNG, AssertJ, and so on) without **prior agreement** in review. Extend the existing JUnit-based suite and in-tree test support under `test/junit/src/org/bluezoo/gumdrop/testsupport/`.

### Running unit tests

```bash
ant test
```

To run one unit test class (do **not** use Maven-style `-Dtest=…`; that is rejected by `build.xml`):

```bash
ant junit-test -Djunit.includes=**/AsyncDiskOffloadBoundaryTest.java
```

Optional unit-test line coverage (not run in CI by default):

```bash
ant junit-coverage
```

This runs the same unit suite as `ant test` with the JaCoCo agent, writes execution
data under `test/junit/coverage/`, and an HTML report under `test/junit/report/`
(open `index.html` in a browser). JUnit plain results still go to `test/junit/results/`.

This target runs only the [unit test](#test-policy) tree under `test/junit/src`.

### Running integration tests

Integration suite only (see [Test policy](#test-policy)):

```bash
ant integration-test
```

Unit plus integration tests (HTTP, SMTP, IMAP, POP3, FTP, servlet, etc.):

```bash
ant test-all
```

Integration tests require TLS certificates. See the [Security documentation](https://cpkb-bluezoo.github.io/gumdrop/web/security.html#tls-certificates) for generating local development certificates with `mkcert`.

Loopback-only integration coverage (no wide-area network) can be run with:

```bash
ant integration-test-loopback
```

### Unit test synchronization

Async unit tests must **not** use `Thread.sleep` or deadline loops that poll mutable state to wait for work to finish. Block on an explicit cross-thread signal instead:

- `CountDownLatch` counted down from a `ProtocolHandler`, executor callback, or test hook
- `RecordingStubEndpoint` for protocol offload tests (`awaitLineStartingWith`, etc.)
- Production test-only observers where no callback exists yet (see existing QUIC/mailbox hooks)

Use `@Test(timeout=…)` only as a hang guard, not as the synchronization mechanism.

`NoThreadSleepGuardTest` enforces this across `test/junit/src` with a small allowlist for tests that intentionally exercise real time (rate limiters, timers, cache expiry, filesystem mtimes). Add allowlist entries only when sleeping is the behaviour under test.

`ContributingStyleGuardTest` enforces the [prohibited language features](#java-version-compatibility) and [timer/callback concurrency](#timers-and-deferred-work) rules across main sources, unit tests, integration tests, and examples. Known debt is listed in `test/junit/resources/contributing-style-allowlist.properties`; remove entries as files are remediated, do not add new ones except for brief migration windows agreed in review.

`FileHeaderGuardTest` enforces the [file header](#file-headers) template on main sources, unit tests, and integration tests (not `examples/`).

`JavadocAuthorGuardTest` enforces `@author` on compilation units under main sources, `test/junit/src`, and `test/integration/src` that declare a top-level type.

`L10nLogGuardTest` flags hardcoded string literals in operator `Logger` calls across all of `src/org/bluezoo/gumdrop`, including multiline `LOGGER.log(...)` forms (see [Localisation](#localisation)); it does not check wire protocol reply text.

## Submitting Changes

1. Fork the repository and create a branch for your changes
2. Make your changes, following the [Coding Standards](#gumdrop-coding-standards) below
3. Add or update [unit tests](#test-policy) for production code changes; ensure `ant test` passes (CI requirement)
4. Run relevant [integration tests](#running-integration-tests) locally when your change depends on real I/O
5. Submit a pull request with a clear description of the change
6. Address any review feedback

---

# Gumdrop Coding Standards

This document defines the coding standards and conventions for the Gumdrop project. All contributions should adhere to these guidelines.

## Java Version Compatibility

**Gumdrop 3 requires Java 25 (LTS) as the minimum baseline** (bumped from 17 so the in-tree TLS engine can use JCA's native ML-KEM/ML-DSA support for post-quantum key exchange).

This is enforced at compile time via the `--release` flag in `build.xml`.

**The following language features are prohibited by project style policy,** even though they are available on this baseline. They apply to **all Java in this repository** (main sources, tests, integration tests, and examples), not only production code. Gumdrop uses a traditional procedural style for clarity and maintainability:
- `var` keyword
- Switch expressions
- Text blocks
- Records
- Pattern matching
- Sealed classes
- Virtual threads
- Lambda expressions
- Method references
- Streams API (`java.util.stream`)
- `Optional<T>`
- `CompletableFuture` and `Future`

### Default Methods in Interfaces

Default methods are permitted for **backward-compatible interface evolution**: adding a new, optional callback or accessor to an existing public interface (`ProtocolHandler`, `Endpoint`, `Mailbox`, `Realm`, and similar) without breaking every class that already implements it. A default method used this way should have a body that is either empty or a sensible, self-contained fallback — not business logic that belongs in a concrete class.

They're also permitted when implementing J2EE APIs that require them.

Default methods are **not** a substitute for an abstract class or a proper base implementation, and should not be used to share non-trivial logic between implementers — use composition or a shared helper class for that instead.

**Good (optional callback, safe no-op default):**
```java
public interface ProtocolHandler {
    void receive(ByteBuffer data);

    // Optional: most handlers don't care about mid-stream flushes.
    default void flushed() {
    }
}
```

**Bad (default method doing real work, not just evolution):**
```java
public interface Mailbox {
    // BAD: this is a real algorithm, not a safe fallback -- put it in a
    // shared helper class instead, so implementers can't accidentally
    // inherit behaviour they didn't ask for.
    default List<Message> search(SearchCriteria criteria) throws IOException {
        List<Message> results = new ArrayList<Message>();
        for (int i = 1; i <= getMessageCount(); i++) {
            if (criteria.matches(getMessage(i))) {
                results.add(getMessage(i));
            }
        }
        return results;
    }
}
```

## File Headers

All source files under main, unit tests, and integration tests must include a proper file header containing:
- Filename
- Copyright owner and date (created/modified year)
- Copyright notice with license reference

Example:
```java
/*
 * ExampleClass.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */
```

`FileHeaderGuardTest` checks main sources, unit tests, and integration tests for this full block (not a one-line “part of gumdrop” stub). To repair abbreviated headers in bulk, run `scripts/expand-lgpl-file-headers.py` from the repository root, then `ant junit-test -Djunit.includes=**/FileHeaderGuardTest.java`.

**Examples** under `examples/` are teaching snippets: keep a **short** file comment (filename and one or two lines of purpose). Do not paste the full LGPL header block into examples; it obscures the code readers are meant to copy. Examples still follow the [prohibited language features](#java-version-compatibility) rules enforced by `ContributingStyleGuardTest`.

## Documentation

- All Java classes must have proper Javadoc with `@author` tag (main sources, unit tests, and integration tests are checked by `JavadocAuthorGuardTest`; run `scripts/add-javadoc-author.py` when adding types)
- Document the intent and purpose, not the obvious mechanics
- Don't write comments that simply restate what the code does

**Good:**
```java
// Ensure session is replicated before response completes
cluster.replicate(context, session);
```

**Bad:**
```java
// Call replicate method on cluster with context and session
cluster.replicate(context, session);
```

## Control Flow

### Conditional Blocks

All conditional blocks must be properly delimited with curly braces and indented, even for single-line blocks. No short-form statements on the same line after `if`.

**Good:**
```java
if (value == null) {
    return;
}

if (count > 0) {
    processItems();
}
```

**Bad:**
```java
if (value == null) return;

if (count > 0) processItems();
```

This prevents a common source of programmer error when modifying code later.

## Imports

- Use proper import statements for all classes
- No fully qualified class names in code unless there is a genuine name clash
- Organize imports logically (java.*, javax.*, then project packages)

**Good:**
```java
import java.util.List;
import java.util.Map;

public void process(List<String> items, Map<String, Object> context) {
```

**Bad:**
```java
public void process(java.util.List<String> items, java.util.Map<String, Object> context) {
```

## Annotations

This section is **guidance**, not an exhaustive allowlist. The tree already uses specialist annotations and tool directives (for example CodeQL `codeql[…]` comments, servlet or injection annotations where a subsystem expects them, and JUnit annotations in tests). That is fine.

**What to avoid** is using metadata to **change behaviour** in ways that are not visible in ordinary control flow: frameworks or reflection that run different code because of an annotation, `SuppressWarnings` that hides a real bug, or broad class-level suppression that obscures review. Prefer narrow `@SuppressWarnings` (method or local scope, not the whole class) and a short comment when the warning is a known false positive or genuinely unavoidable, unless the reason is obvious at the call site (e.g. `@SuppressWarnings("unchecked")` right after an array-based generic cast).

**Routine in main code:** `@Override`, `@Deprecated`, and narrowly scoped `@SuppressWarnings`.

**Also normal elsewhere:** example code that demonstrates annotation-based configuration (e.g. `@WebServlet`), tests (`@Test`, `@Before`, …), and modules whose job is to read or emit annotations.

## Language Features to Avoid

### No Lambdas

Use traditional anonymous classes or explicit method implementations instead.

**Good:**
```java
executor.submit(new Runnable() {
    @Override
    public void run() {
        processTask();
    }
});
```

**Bad:**
```java
executor.submit(() -> processTask());
```

### No Functional Paradigm

Use clear, traditional procedural code. Avoid streams, functional interfaces, and method references.

**Good:**
```java
List<String> result = new ArrayList<>();
for (Item item : items) {
    if (item.isValid()) {
        result.add(item.getName());
    }
}
```

**Bad:**
```java
List<String> result = items.stream()
    .filter(Item::isValid)
    .map(Item::getName)
    .collect(Collectors.toList());
```

### No Method Chaining

Avoid chaining method calls (except for builders, used sparingly). Write each operation as a separate statement for clarity.

**Good:**
```java
StringBuilder sb = new StringBuilder();
sb.append("Hello");
sb.append(" ");
sb.append("World");
String result = sb.toString();
```

**Acceptable (builder pattern):**
```java
DnsMessage response = new DnsMessage.Builder()
    .id(query.getId())
    .flags(FLAG_QR | FLAG_RA)
    .build();
```

**Bad:**
```java
String result = new StringBuilder().append("Hello").append(" ").append("World").toString();
```

### No Inline Function Calls as Parameters

Avoid calling functions inline as parameters. Assign to variables first for clarity.

**Exception:** The `++` operator may be used inline.

**Good:**
```java
String name = user.getName();
String formatted = formatter.format(name);
logger.info(formatted);
```

**Less ideal:**
```java
logger.info(formatter.format(user.getName()));
```

**Acceptable (increment operator):**
```java
array[index++] = value;
```

### No Future/Promise

Avoid `Future`, `CompletableFuture`, `ScheduledFuture`, and similar constructs (including `ExecutorService.submit` when the return value is used to wait on or cancel work). Use traditional callback patterns instead, similar to SAX or JavaScript XMLHttpRequest. Tests must use `CountDownLatch` or handler callbacks for synchronization, not `CompletableFuture` or `Future.get()`.

**Good:**
```java
public interface ResponseCallback {
    void onSuccess(Response response);
    void onError(Exception error);
}

public void sendRequest(Request request, ResponseCallback callback) {
    // Implementation calls callback.onSuccess() or callback.onError()
}
```

**Bad:**
```java
public Future<Response> sendRequest(Request request) {
    return executor.submit(() -> doRequest(request));
}
```

### No Regular Expressions

Avoid `java.util.regex` patterns. Use traditional string parsing methods instead.

**Good:**
```java
int colonIndex = header.indexOf(':');
if (colonIndex > 0) {
    String name = header.substring(0, colonIndex).trim();
    String value = header.substring(colonIndex + 1).trim();
}
```

**Bad:**
```java
Pattern pattern = Pattern.compile("^([^:]+):\\s*(.*)$");
Matcher matcher = pattern.matcher(header);
if (matcher.matches()) {
    String name = matcher.group(1);
    String value = matcher.group(2);
}
```

## Concurrency

### Thread Pools

Use `ExecutorService` and `ScheduledExecutorService` for thread pool management. Create threads using `ThreadFactory` implementations with descriptive names.

**Good:**
```java
private static final ExecutorService EXECUTOR = 
    Executors.newCachedThreadPool(new WorkerThreadFactory());

private static class WorkerThreadFactory implements ThreadFactory {
    private final AtomicInteger count = new AtomicInteger(0);
    
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "worker-" + count.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
}
```

### Callbacks Instead of Futures

Use callback interfaces for asynchronous operations. This is consistent with the prohibition on `Future` and `CompletableFuture`.

**Good:**
```java
public interface CompilationCallback {
    void onSuccess(Class<?> compiledClass);
    void onError(Exception error);
}

public void compileAsync(String source, CompilationCallback callback) {
    executor.execute(new Runnable() {
        @Override
        public void run() {
            try {
                Class<?> result = compile(source);
                callback.onSuccess(result);
            } catch (Exception e) {
                callback.onError(e);
            }
        }
    });
}
```

### Timers and deferred work

Do **not** use `ScheduledFuture`, `scheduleAtFixedRate`, or `scheduleWithFixedDelay` on `ScheduledExecutorService` for Gumdrop code paths. Prefer:

- `ScheduledTimer` for work that must run on a `SelectorLoop` thread (keep-alives, connection timeouts, delayed cleanup)
- One-shot or periodic callbacks scheduled from the selector/worker thread with explicit `Runnable` implementations and a stored cancel handle (timer id, `volatile boolean`, or similar), not a `Future` return value

**Good:**
```java
timer.schedule(loop, delayMillis, new Runnable() {
    @Override
    public void run() {
        connection.closeIdle();
    }
});
```

**Bad:**
```java
ScheduledFuture<?> tick = scheduler.scheduleAtFixedRate(new Runnable() {
    @Override
    public void run() {
        connection.closeIdle();
    }
}, 0, period, TimeUnit.SECONDS);
```

## Localisation

Gumdrop supports internationalisation (i18n) and localisation (l10n). The main audience is **operators** reading server logs (and similar diagnostics), not clients inspecting raw protocol lines on the wire. We maintain translations for English, French, Spanish, and German in each package's `L10N` bundles.

### Policy summary

| Category | Use L10N? | Notes |
|----------|-----------|--------|
| **Operator log messages** (`Logger` info/warning/severe/fine, etc.) | **Yes** | Primary requirement; enforced repo-wide in main source by `L10nLogGuardTest` |
| **Startup / configuration errors** shown to the operator | **Yes** | Missing keystore, bad listener config, and similar |
| **User-facing UI** | **Yes** | HTTP error pages, quota or auth messages meant for a person using an app |
| **Wire protocol text** | **No (English)** | SMTP/IMAP/FTP/HTTP status lines and tokens on the socket; clients rarely display these verbatim |
| **Programming / API misuse** (`NullPointerException`, bad arguments) | **No** | Hardcoded English |
| **Internal parsers, codecs, util** | **No (exceptions)** | Prefer hardcoded English for pure library/parser throws; **operator `Logger` lines still use L10N** when the guard applies |

Existing code may still load some wire replies from `L10N` (historical keys such as `ftp.welcome_banner`). **New work** should not add locale variants for protocol line text unless a feature is explicitly user-facing outside the wire format. Prefer English literals or shared constants for on-the-wire text; put localisation effort into **logs**.

### ResourceBundle Structure

Each package that requires localisation has a ResourceBundle called `L10N`:
- `L10N.properties` - Default (English) messages
- `L10N_en.properties` - English (explicit)
- `L10N_fr.properties` - French
- `L10N_es.properties` - Spanish
- `L10N_de.properties` - German

Access localised strings using:
```java
private static final ResourceBundle L10N =
    ResourceBundle.getBundle(MyClass.class.getPackage().getName() + ".L10N");

String message = L10N.getString("key.name");
String formatted = MessageFormat.format(L10N.getString("key.with.args"), arg1, arg2);
```

### What MUST Be Localised

#### 1. Log messages (required)

All messages written through `java.util.logging` (or equivalent) that operators will see in production logs **must** use `L10N`:

```java
// Good
logger.info(L10N.getString("info.connection_accepted"));
logger.warning(MessageFormat.format(L10N.getString("warn.auth_failed"), user));

// Bad
logger.info("Connection accepted from " + address);
```

#### 2. Configuration and startup errors (required)

Errors reported when the server fails to start or parse configuration, when the operator is the reader:

```java
throw new ConfigurationException(L10N.getString("err.missing_keystore"));
```

#### 3. User-facing UI (required when applicable)

Text shown to end users outside raw protocol traces: servlet error pages, WebDAV or HTTP bodies meant for humans, quota messages in a mailbox UI, and similar:

```java
throw new QuotaExceededException(
    MessageFormat.format(L10N.getString("err.quota_exceeded"), used, limit));
```

#### Wire protocol (not required)

Line-oriented protocol replies (SMTP `220`/`550`, IMAP tagged `OK`/`NO`, FTP `227`, and so on) **may stay in English** on the wire. Do not spend translation effort on telnet-style protocol text unless you are deliberately building a human-facing surface that reuses those strings.

### What Should NOT Be Localised

The following categories should use hardcoded English strings:

#### 1. Programming/API Contract Errors
Exceptions that indicate programmer errors or API misuse. These are for developers, not end users:
```java
// Good - hardcoded, this is a programming error
if (username == null) {
    throw new IllegalArgumentException("Username cannot be null");
}

if (buffer == null) {
    throw new NullPointerException("buffer");
}

// Good - unsupported operation in default interface method
throw new UnsupportedOperationException("Append not supported");
```

#### 2. Internal State Errors
`IllegalStateException` for invalid internal states that indicate bugs:
```java
// Good - hardcoded, indicates internal bug
if (!open) {
    throw new IllegalStateException("Connection is not open");
}

if (appendBuffer != null) {
    throw new IllegalStateException("Append already in progress");
}
```

#### 3. Low-Level Parsing/Protocol Errors
Errors in parsers or codecs that are caught and handled internally, or represent malformed data from external sources:
```java
// Good - hardcoded, internal parsing error
throw new Asn1Exception("Invalid tag: 0x" + Integer.toHexString(tag));

// Good - hardcoded, protocol violation
throw new ProtocolException("Invalid HPACK index: " + index);
```

#### 4. Utility/Library Code
Code in utility packages (`util`, `json`, `hpack`) that may be used outside the server context:
```java
// Good - utility code, hardcoded
throw new JSONParseException("Expected ':' after object key");
```

### Naming Conventions for L10N Keys

Use a hierarchical naming scheme (prefer **`log.*` / `info.*` / `warn.*` / `debug.*`** for new operator log keys):
- `info.*`, `warn.*`, `debug.*` - Log messages by severity
- `err.*` - Configuration, startup, and user-visible errors
- `log.*` - General log lines when severity prefix is awkward
- `{protocol}.*` - Legacy or shared protocol-package keys (including some on-the-wire text in older code)
- `telemetry.*` - Telemetry span names and descriptions (localise sparingly)

### Package Guidelines

| Area | Operator logs (L10N) | Wire / protocol line text |
|------|----------------------|---------------------------|
| All main source (`src/org/bluezoo/gumdrop/**`) | **Yes** (`L10nLogGuardTest`) | English on the wire where applicable; no new locale work for telnet-style lines |
| Subpackages | Use the nearest package `L10N` bundle (same as today) | Same as parent |
| User-facing UI (servlets, WebDAV bodies, quota text) | **Yes** when shown to a person | N/A |

### Adding New Translations

When adding a new **localised log or user-facing** string:

1. Add the key to `L10N.properties` (default English)
2. Add translations to all four language files (`_en`, `_fr`, `_es`, `_de`)
3. Use `MessageFormat` placeholders `{0}`, `{1}` for dynamic values
4. Keep messages concise and avoid culture-specific idioms

`L10nLogGuardTest` enforces **logger** localisation across all main source with no allowlist. The test does **not** require L10N for on-the-wire protocol replies.

Use `python3 scripts/operator-log-l10n.py scan` to list current violations (same rules as the guard). `apply` runs idempotent text replacements registered in that script.

## Telemetry

Gumdrop uses its own telemetry system which is compatible with OpenTelemetry
and uses the same concepts. When adding new features consider if they
require a new span within the current trace. When implementing, if there are
any error conditions ensure that they are logged into the trace.

## Gumdrop 3 naming conventions

Gumdrop 3 renames public types for **role clarity** and **consistent camelCase
acronyms** (hopf precedent). Remaining legacy public types and their targets
are listed in `test/junit/resources/gumdrop3-legacy-type-renames.properties`.

**New public types** in `src/org/bluezoo/gumdrop` must follow these rules:

1. **Acronyms** — only the first letter capitalised per word:
   `HttpServer`, `SmtpClient`, `DnsMessage`, `Pop3Server` (not `HttpServer`,
   `SmtpClient`, …).
2. **Application tier** — listener + handler wiring uses `*Server`, not
   `*Service` (`HttpServer`, `SmtpServer`). Do not add new `*Service` types.
3. **Client reply handlers** — in `{protocol}.client` packages, never prefix
   with `Server` for remote-side replies (`EhloReplyHandler`, not
   `ServerEhloReplyHandler`).
4. **Handlers and facades** — server SPIs use `*RequestHandler` / staged server
   handlers; dial facades use `*Client`.

During migration, legacy names remain in the tree. Any **new** public type that
still uses a legacy pattern must be listed in
`test/junit/resources/gumdrop3-legacy-type-renames.properties` with its target
name; `Gumdrop3NamingConventionTest` enforces this inventory.

## Summary

The goal of these standards is to produce code that is:
- **Clear**: Easy to read and understand at a glance
- **Predictable**: Follows consistent patterns throughout
- **Maintainable**: Easy to modify without introducing bugs
- **Traditional**: Uses well-understood Java idioms
- **Compatible**: Runs on Java 25 and later without modification

When in doubt, prefer clarity over cleverness.

