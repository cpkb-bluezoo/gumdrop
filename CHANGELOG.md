# Changelog

All notable changes to Gumdrop will be documented in this file.

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-08-09

### Added

- **AMQP 0-9-1 client** (`org.bluezoo.gumdrop.amqp.client`, issue #154, #155):
  non-blocking client for publishing and consuming messages against brokers
  such as RabbitMQ, with exchange/queue declaration and binding, publish and
  consume, publisher confirms, classic transactions
  (`tx.select`/`commit`/`rollback`), and automatic connection recovery with
  configurable exponential backoff that transparently replays exchanges,
  queues, bindings, and consumers after a reconnect.
- **AMQPLAIN, EXTERNAL, and GSSAPI SASL mechanisms for the AMQP client**
  (issue #188, #189): the client previously only supported SASL PLAIN. It now
  also supports RabbitMQ's `AMQPLAIN` mechanism, `EXTERNAL` (TLS client
  certificate), and `GSSAPI`/Kerberos (worker-thread offloaded for KDC
  contact), reusing gumdrop's shared `SASLUtils` infrastructure where
  possible. `AMQPClientProtocolHandler` now also drives
  `connection.secure`/`secure-ok` round trips so multi-step mechanisms work,
  not just single-shot ones.
- **FTP client** (`org.bluezoo.gumdrop.ftp.client`, #113): async FTP client
  mirroring the existing POP3/SMTP/IMAP client packages, including PASV/EPSV
  data-connection handling and AUTH TLS/PROT P support.
- **Background mailbox search-index indexer** (issue #163, #179, #180):
  `MailboxIndexer`/`MailboxWatcher` build and maintain per-mailbox search
  indexes on a priority-queued background worker instead of blocking on
  first access, for both mbox and Maildir stores.
- **Streaming protocol parser infrastructure** (#103): introduced
  `ByteStreamLexer` and token-based error recovery as a replacement for the
  older line-buffering `LineParser` model, enabling true zero-copy, no
  full-line-buffering parsing for line-oriented protocols.
- **DMARC aggregate and forensic report generation updated to RFC
  9990/9991** (#112).
- **`module-info.java`** JPMS module descriptor added for the gumdrop core
  module (part of #187).
- Maven Central publishing workflow and `SECURITY.md` vulnerability
  disclosure policy added.
- Dependabot configuration added for automated dependency update PRs.
- **DTLS support for UDP listeners** (issue #190): `UDPEndpoint` now
  maintains one DTLS session per peer address, so a single bound socket
  serves many concurrent DTLS clients (DNS-over-DTLS, RFC 8094, in
  particular). Includes RFC 6347 §4.2.4 handshake flight retransmission
  with exponential backoff, and `securityEstablished(SecurityInfo)` now
  actually fires for DTLS, backed by the same `JSSESecurityInfo` used for
  TCP/TLS.

### Changed

- **Worker loop selection is now least-loaded, not pure round-robin**
  (issue #139, #186): new connections are assigned to the `SelectorLoop`
  with the fewest active connections rather than cycling blindly, improving
  load distribution under uneven per-connection cost.
- **Removed per-context monitors from servlet hot paths** (issue #140, #185):
  eliminates unnecessary lock contention on frequently-hit servlet request
  paths.
- **Servlet request/response bodies are now streamed instead of fully
  buffered** (issue #120, #148), and **HTTP/2 stream writes now apply
  backpressure instead of buffering unboundedly pending data** (issue #123,
  #150).
- **HPACK Huffman decoding is now table-driven** (issue #138, #172),
  replacing a slower bit-by-bit implementation.
- **DNS response cache eviction is no longer quadratic** (issue #129, #158).
- **HTTP header lookups and repeated-parameter growth are no longer O(n) /
  O(k²)** (issues #141/#142, #182).
- **MQTT `TopicTree` retained messages are now indexed and pruned on
  unsubscribe** (issues #143/#144, #183), instead of scanning all
  subscriptions.
- **`ContextClassLoader` no longer races on concurrent first-time class
  loads** (issue #164, #181).
- **FTP LIST/NLST/MLSD listings are now streamed** rather than buffered in
  full (issues #131/#132, #160), and **mbox/Maildir mailbox access no longer
  performs unnecessary linear passes or holds locks longer than needed**
  (issues #124, #133, #134/#135, #153, #161, #162).
- **IMAP command processing no longer blocks on an unbounded storage latch
  wait** (issue #130, #159); blocking file I/O across mailbox storage was
  migrated onto a dedicated `StorageExecutor` so it no longer risks
  occupying `SelectorLoop` worker threads.
- **MQTT packet ID allocation is now bounded** to avoid livelock under
  sustained load (issue #125, #151).
- **FTP passive-mode (PASV) listener is now closed once a data connection is
  accepted**, and its port range is now configurable (issue #145, #184).
- **AMQP client documentation** and general documentation pass across
  changed areas.
- Large **coding-standards compliance pass** (#187): removed lambdas,
  method references, and Streams API usage from main source in favor of the
  project's traditional procedural style; added missing file headers,
  `@author` tags, and brace-delimited conditional blocks; converted
  hardcoded log/response strings across the codebase to the L10N resource
  bundle mechanism with English, French, Spanish, and German translations.
- Routine dependency version bumps (Maven compiler, javadoc, antrun,
  source, and jar plugins) via Dependabot (#166–#171).
- **EL expression parsing is now cached** (issue #191): `ELEvaluator` used
  to re-scan an expression string for operator positions on every single
  evaluation, with no caching at any level — costly inside JSP iteration
  tags, which re-evaluate the same expression once per row of every
  request. Parsing is now cached in a bounded, shared structure keyed by
  the expression string; the actual bean/property lookups and operator
  application still run fresh on every evaluation, so caching cannot
  return stale data.
- **HTTP Digest nonce/cnonce tracking is now bounded, and no longer
  serialises unrelated requests through one lock** (issue #192): both
  maps previously grew without bound under sustained Digest-authenticated
  traffic; nonces now expire after five minutes (matching common server
  defaults) with opportunistic eviction. The client-nonce replay check
  moved off a single shared `synchronized` block onto a
  `ConcurrentHashMap`, so it no longer contends across every connection
  an auth provider instance serves.
- **`UDPEndpoint`'s receive buffer is now a pooled direct buffer**
  (issue #193), matching `TCPEndpoint`'s read/write path, instead of a
  plain heap allocation that forced the JVM's internal direct-buffer
  bounce-copy on every datagram.
- **`Container.getContextByPath` is now an indexed lookup** (issue #194)
  instead of an unindexed linear scan with `String.startsWith` over every
  deployed context on every request.
- **LDAP `BERDecoder` no longer allocates a decoder and pooled buffer per
  nesting level** (issue #195): constructed (nested) BER values are now
  parsed in place via plain recursive descent over the already-received
  bytes, rather than spinning up a whole new `BERDecoder` per level of
  nesting — relevant to deeply nested LDAP search filters.

### Fixed

- **`HTTP3Listener` NullPointerException** on certain startup configurations
  (#108).
- **HTTP/2 `Content-Length` validation ordering** corrected so a mismatched
  length is rejected before the affected body is processed (#67).
- **Session-expiry throttle check was inverted**, causing sessions to expire
  either far too eagerly or not at all depending on configuration (#146).
- **FTP AUTH TLS / PROT P downgrade** no longer possible after a client
  negotiates protection level P and then attempts to send data in the
  clear.
- **Malformed or conflicting `Content-Length` headers are now rejected**
  instead of silently picking one, closing an HTTP request-smuggling vector
  (#116; see also the SEC-034 entry under Security).
- **HTTP authentication enforcement gaps across HTTP/1.1, HTTP/2, and
  HTTP/3** closed — auth constraints evaluated on one protocol version were
  not consistently reaching requests served over another (#117).
- **`JSPServlet` exception messages no longer leak internal detail** to
  clients on compilation/runtime errors (#174, #177).
- **Zip Slip vulnerability in `Context.getResourcePaths()`** fixed —
  crafted WAR entries could previously write outside the deployment
  directory during resource enumeration (#173, #176).
- **Debug header leaking the raw, unnormalized request path** removed
  (CodeQL alert, issues #39/#40, #180).
- **`DefaultServlet` no longer echoes the client-supplied `Host` header into
  a redirect `Location`** (CodeQL alert, issue #175, #178).

### Security

- **Servlet role authorization bypass (High)**: In
  `ContextRequestDispatcher.authorize()`, an authenticated user who lacked a
  required role was re-authenticated (which succeeds for any valid
  credentials) instead of being denied, so `<auth-constraint>` role checks
  were never enforced. Any authenticated user could reach role-protected
  resources, including the `manager` admin application. Authorization now:
  - denies with `403 Forbidden` when the user is authenticated but not in a
    permitted role (it no longer re-invokes authentication);
  - honors an empty `<auth-constraint/>` as deny-all (the deployment
    descriptor parser now marks it with `EmptyRoleSemantic.DENY`);
  - treats the special role names `*` and `**` as "any authenticated user"
    per Servlet 3.1.

- **Servlet resource path traversal outside webapp root**:
  `Context` resource-path resolution allowed `../`-style traversal outside
  the webapp root, permitting access to files outside the deployed
  application. Fixed with strict path confinement and regression tests.

- **IMAP/POP3 SCRAM-SHA-256 proof verification broken**:
  Server-side SCRAM proof verification in `SASLUtils`, `IMAPProtocolHandler`,
  and `POP3ProtocolHandler` did not properly verify the client proof,
  potentially allowing authentication bypass.

- **`WEB-INF`/`META-INF` bypass in `DefaultServlet` path checks**:
  path checks could be bypassed (e.g. via encoding/case tricks)
  to serve files from `WEB-INF`/`META-INF`, which must always be protected
  from direct access.

- **IMAP/POP3/SMTP DIGEST-MD5 response verification broken**:
  the same class of flaw as the SCRAM issue above, affecting DIGEST-MD5
  response verification in `SASLUtils`, `IMAPProtocolHandler`,
  `POP3ProtocolHandler`, and `SMTPProtocolHandler`.

- **Strict allowlist for replicated session deserialization**: 
  `SessionSerializer` now validates deserialized cluster-session
  class names against a strict allowlist in `Container`/`ServletService`
  instead of deserializing arbitrary classes, closing an insecure
  deserialization vector in cluster session replication.

- **Conflicting `Content-Length` headers not rejected on HTTP/1**: 
  `HTTPProtocolHandler`, `Stream`, and
  `HTTPVersion` now reject requests carrying multiple/conflicting
  `Content-Length` headers instead of picking one.

- **`Transfer-Encoding` header with multiple codings not rejected**: 
  `HTTPUtils` now rejects a
  `Transfer-Encoding` header listing multiple codings.

- **SOCKS NO-AUTH/SOCKS4 accepted despite configured realm**: 
  `SOCKSProtocolHandler` now rejects unauthenticated
  NO-AUTH and SOCKS4 negotiation when a realm requiring authentication is
  configured.

- **SMTP auth state derived from XCLIENT LOGIN assertion**: 
  `SMTPProtocolHandler` no longer trusts an
  XCLIENT-asserted `LOGIN` value as proof of authenticated state.

- **JWT validation failure fell back to an insecure path**: 
  `OAuthRealm` now fails closed when JWT validation fails,
  instead of falling back to a less-secure authentication path.

- **Dangerous reflective method calls reachable from the EL evaluator**: 
  `ELEvaluator` now blocks a denylist of dangerous
  reflective method invocations reachable from EL expressions.

- **FTP home-directory confinement bypassable via `..` segments**: 
  `RoleAwareFTPFileSystem` now collapses `..`
  segments before applying the home-directory confinement check (see also
  the pre-existing `BasicFTPFileSystem` symlink-containment fix below).

- **SCRAM salt is now random**: `BasicRealm.getScramCredentials()`
  previously derived the salt deterministically from the username
  (`new SecureRandom(username.getBytes(...))`). It now uses a fresh,
  unpredictable random salt for each call.

- **PBKDF2 password hashing added**: `BasicRealm` now supports a
  salted, iterated `{PBKDF2}` (`PBKDF2WithHmacSHA256`) password format via
  `BasicRealm.createPbkdf2Hash()`, and this is the recommended storage
  format. The legacy `{SHA}`, `{SSHA}`, `{SHA256}` and `{SSHA256}` formats
  are still accepted for backward compatibility but are documented as weak.

- **SCRAM iteration count raised**: `BasicRealm` raises
  the SCRAM iteration count to 210,000 (the current OWASP-recommended
  minimum) and caches derived credentials to offset the added cost.

- **Digest authentication nonce hardened**: HTTP Digest nonce
  generation in `HTTPAuthenticationProvider` no longer uses
  `Math.random()`; it now mixes the current time with `SecureRandom` bytes
  so nonces are unpredictable.

- **HTTP Digest replay guard and request binding weak**:
  `HTTPAuthenticationProvider`'s Digest authentication lacked adequate
  replay protection and binding to the specific request; fixed with
  nonce/request-binding checks.

- **IMAP/SMTP OAUTHBEARER authentication not bound to token subject**: 
  `IMAPProtocolHandler` and
  `SMTPProtocolHandler` accepted an OAUTHBEARER token without binding the
  authenticated identity to the token's subject claim.

- **JWT validation missing required `exp` claim check; array `aud` claims
  unsupported**: `OAuthRealm` now requires an `exp` claim
  and correctly validates array-valued `aud` claims.

- **No session ID rotation on authentication**: `
  Request` now rotates the session ID on successful
  authentication.

- **WebSocket frames with oversized declared payloads not rejected before
  allocation**: `WebSocketFrame`/`WebSocketConnection`
  now reject an oversized declared frame length before allocating a buffer
  for it, and raise a new `WebSocketMessageTooBigException`.

- **No configurable maximum HTTP request body size**:
  `HTTPListener`, `HTTPProtocolHandler`, and `Stream` now support an
  enforceable maximum body size.

- **gRPC bodies buffered fully in memory instead of streamed**: 
  the gRPC server and client now stream frames through
  push parsers (`GrpcFrameParser`) instead of buffering entire message
  bodies.

- **DNS listener ACLs and RFC 7873 cookies not enforced**:
  `DNSListener`/`DNSService` now enforce access-control lists and DNS
  cookie validation to mitigate spoofing/amplification abuse.

- **Active-mode FTP data address not verified against the control client**: 
  `FTPDataConnectionCoordinator`,
  `FTPListener`, and `FTPProtocolHandler` now verify that an active-mode
  data connection actually originates from the control connection's peer
  address.

- **HTTP/2 concurrency slot released before the response actually
  completed**: `HTTPProtocolHandler`/`Stream` now hold
  the HTTP/2 concurrency slot until the response completes, closing a
  concurrency-limit-bypass window.

- **Client-sent `PUSH_PROMISE` accepted instead of rejected**: 
  `HTTPProtocolHandler` now rejects a
  client-sent `PUSH_PROMISE` frame with `PROTOCOL_ERROR` and `GOAWAY`
  instead of accepting it — servers are never a valid recipient of this
  frame per RFC 9113 §6.6.

- **Indexed HPACK header fields not counted against
  `MAX_HEADER_LIST_SIZE`**: `hpack.Decoder` now
  counts indexed, not just literal, header fields against the configured
  limit.

- **HPACK integer decoding unbounded**:
  `hpack.Decoder` now bounds integer decoding to prevent
  overflow-driven memory/logic issues.

- **IMAP command literal size not enforced on every literal-accepting
  command**: `IMAPProtocolHandler` now enforces
  `maxLiteralSize` consistently.

- **Resolved SOCKS destination addresses not validated against the
  destination filter**: `SOCKSProtocolHandler`/
  `SOCKSUDPRelay` now validate every DNS-resolved address, not just the
  literal target, against the configured destination policy.

- **SOCKS BIND lacked destination-policy and bind-interface restriction**: 
  `SOCKSBindRelay`/`SOCKSProtocolHandler` extend
  destination-policy enforcement to the BIND command and restrict which
  interfaces BIND may listen on.

- **DNS response validation gaps: QR bit, source address, question section**: 
  `DNSService` now validates the QR
  bit, response source address, and echoed question section on incoming
  DNS responses.

- **Cluster session replay/timestamp validation skipped for some claim
  shapes**: `session.Cluster` now applies
  replay/timestamp validation unconditionally.

- **Timing-unsafe MAC/digest comparisons**: 
  `HTTPAuthenticationProvider`, `IMAPProtocolHandler`,
  `POP3ProtocolHandler`, and `DKIMValidator` now use constant-time
  comparison for credential/digest checks.

- **No HTTP/1.1 header-count limit**:
  `HTTPProtocolHandler` now enforces a maximum header count per request.

- **No maximum line length enforced in `LineParser`**: 
  `LineParser` (and the FTP/HTTP/IMAP/POP3/SMTP handlers built on
  it) now enforce a maximum line length, preventing unbounded-line memory
  exhaustion.

- **Non-PRI input accepted after an h2c 101 Switching Protocols upgrade**: 
  `HTTPProtocolHandler` now
  rejects non-conforming input following an h2c upgrade.

- **FTP unique-name generation not re-validated against path policy**: 
  `BasicFTPFileSystem.generateUniqueName()`
  now sanitizes the suggested name and re-validates the resulting path.

- **Multipart filename directory components not stripped**: 
  `MimePart` now strips directory components
  from client-supplied multipart filenames and canonicalizes the result.

- **Unbounded SOCKS5 GSSAPI token length**:
  `SOCKSProtocolHandler`/`SOCKSConstants` now cap GSSAPI token length at
  16 KiB.

- **`rsa-sha1` DKIM signatures accepted**: 
  `DKIMValidator` no longer accepts `rsa-sha1` signatures.

- **TLS peer-verification disable flag not applied to TCP/TLS**: 
  `HTTPClient.setVerifyPeer(false)` previously only affected
  QUIC connections; it now applies to TCP/TLS connections too.

- **No SSRF protection option on `HTTPClient`**: 
  `HTTPClient` gains an opt-in SSRF protection mode that
  blocks requests/redirects to internal address ranges.

- **WebDAV XML parsing hardened against XXE/DoS**:
  `WebDAVRequestParser` and `DeadPropertyParser` now explicitly install a
  deny-external-entities resolver (`XMLParseUtils.DENY_EXTERNAL_ENTITIES`)
  rather than relying on the parser default, and WebDAV request bodies are
  capped in size (rejecting oversized bodies with `413`) to bound XML
  parser work and mitigate entity-expansion ("billion laughs") attacks.
  Further hardened (SEC-028) by additionally capping dead-property sidecar
  file size and rejecting `DOCTYPE` declarations in
  `DeadPropertyParser`/`DeadPropertyStore`.

- **FTP symlink containment**:
  `BasicFTPFileSystem.resolveSecurePath()` now performs a `toRealPath()`
  containment check (as the WebDAV file handler does) so symbolic links
  inside the FTP root cannot be used to escape the root directory.

- **Maildir symlink containment**: `MaildirMailboxStore`
  adds the same `toRealPath()` containment check to Maildir path
  resolution as the existing FTP/WebDAV fix above.

- **Host header syntax not validated; malformed port crashed the request**: 
  `HTTPProtocolHandler`, `HTTPUtils`, and `Request`
  now validate `Host` header syntax and guard `getServerPort()` against a
  crash on malformed input.

- **DMARC `pct=` sampling used a non-cryptographic RNG**:
  `DMARCValidator` now uses `SecureRandom` for percentage-based sampling
  decisions.

- **Invalid HTTP response headers handled gracefully**:
  `Response.addHeader()` now catches invalid header names/values (e.g. those
  containing CR/LF) and drops the header with a warning instead of
  propagating an exception, preventing HTTP response splitting from becoming
  a server error. The `Header` constructor already rejects CR/LF via
  `HTTPUtils.isValidHeaderValue`; a regression test was added.

- **Clarified `sendRedirect` open-redirect documentation**:
  `Response.sendRedirect()` javadoc now accurately states that the method does
  not restrict the redirect target and that same-origin/allowlist validation
  is the application's responsibility.

- **Clarified HTTP client `-k` flag**: The command-line `HTTPClient`
  `-k` (skip TLS certificate verification) flag is documented as insecure and
  debugging-only; certificate verification remains enabled by default.

- **Open-by-default trust model on SOCKS/MQTT/SMTP relay undocumented**: 
  clarified that SOCKS, MQTT, and
  SMTP relay are open-by-default and must be explicitly restricted by the
  operator; no code behavior change.

- **Hardcoded DMARC TLD set replaced with a real Public Suffix List**: 
  `DMARCValidator` replaces a hardcoded TLD set with a
  proper `PublicSuffixList` implementation for organizational-domain
  determination.

## [2.0] - 2026-03-22

### Added

- **HTTP/3 server and client over QUIC**: Full HTTP/3 support via the quiche
  native library (BoringSSL + quiche JNI bindings). Servers advertise HTTP/3
  availability through `Alt-Svc` headers; clients can connect directly over
  QUIC or discover HTTP/3 transparently via Alt-Svc upgrade.
  - `HTTPClient` supports `--http3` for direct QUIC connections with optional
    client certificates and SNI for alternate-host Alt-Svc targets
  - `HTTPClient` CLI (`main()`) for debugging HTTP connections across all
    protocol versions (HTTP/1.1, HTTP/2, HTTP/3), similar to curl

- **MQTT broker and client**: MQTT 3.1.1 and MQTT 5.0 over TCP and WebSocket,
  including `MQTTListener` / `DefaultMQTTService` (broker), subscription and
  retained-message handling, and the `MQTTClient` API.

- **SOCKS proxy**: SOCKS protocol server and client implementation.

### Fixed

- `HTTPClientProtocolHandler` now fires `onConnected` and
  `onSecurityEstablished` callbacks on the `HTTPClientHandler`
- TLS client handshake is now initiated after TCP connect completes
- HEAD responses no longer hang waiting for a body that will never arrive

### Changed

- **Pluggable DI via `GumdropConfigurator` SPI**: Configuration parsing and
  dependency injection have been extracted into a new `org.bluezoo.gumdrop.config`
  package and are now behind a `GumdropConfigurator` service provider interface.
  The default implementation is discovered via `java.util.ServiceLoader`. External
  DI frameworks (Guice, Spring, CDI) can be plugged in by providing an alternative
  `META-INF/services/org.bluezoo.gumdrop.GumdropConfigurator` on the classpath.
  Gumdrop instances can also be configured entirely programmatically without any
  DI framework.

- **Removed `<container>` element from gumdroprc** (breaking): The standalone
  `<container>` configuration element has been removed. Container properties
  (`hot-deploy`, `realms`, `resources`, cluster settings) are now set directly
  on the `<service>` element for `ServletService`. The `<context>` element is
  now a direct child of `<service>`, following the same pattern as `<listener>`.
  `ServletService` creates its `Container` internally.

  Before:
  ```xml
  <container id="mainContainer">
      <property name="hot-deploy" value="true"/>
      <context path="" root="../web"/>
  </container>
  <service id="http" class="org.bluezoo.gumdrop.servlet.ServletService">
      <property name="container" ref="#mainContainer"/>
      <listener class="org.bluezoo.gumdrop.http.HTTPListener">
          <property name="port" value="8080"/>
      </listener>
  </service>
  ```

  After:
  ```xml
  <service id="http" class="org.bluezoo.gumdrop.servlet.ServletService">
      <property name="hot-deploy" value="true"/>
      <context path="" root="../web"/>
      <listener class="org.bluezoo.gumdrop.http.HTTPListener">
          <property name="port" value="8080"/>
      </listener>
  </service>
  ```

- **Config/DI classes moved to `org.bluezoo.gumdrop.config`** (breaking):
  `ComponentRegistry`, `ConfigurationParser`, `ParseResult`,
  `ComponentDefinition`, `PropertyDefinition`, `ComponentReference`,
  `ListValue`, and `MapValue` have been moved from `org.bluezoo.gumdrop`
  to `org.bluezoo.gumdrop.config`. Code that imports these classes directly
  will need to update its import statements.

- **Minimum Java version raised to 17 (LTS)**: Gumdrop v2 requires Java 17 or
  later. This enables native UNIX domain socket support (JEP 380) without
  JNI or third-party libraries. The build now uses `--release 17` exclusively;
  the legacy `source`/`target` fallback properties have been removed.

- **UNIX domain socket support**: Any TCP-based listener can now bind to a UNIX
  domain socket by specifying a `path` attribute instead of `port`. Once
  accepted, connections use the same `SocketChannel`/`TCPEndpoint`/`ProtocolHandler`
  infrastructure as TCP. Stale socket files are cleaned up on bind and shutdown.

- **Renamed `<listen>` to `<listener>` in gumdroprc**: The configuration element
  for declaring listeners within a `<service>` has been renamed from `<listen>` to
  `<listener>` for consistency with the `Listener` class name.

- **Externalized parsing libraries**: The
  [Gonzalez](https://github.com/cpkb-bluezoo/gonzalez) XML parser and
  [jsonparser](https://github.com/cpkb-bluezoo/jsonparser) JSON parser are now
  external dependencies instead of bundled source. They are downloaded
  automatically by `ant resolve-deps` (called as part of the default build).
  The JPMS module-info now uses `requires transitive` for both modules.

## [1.1] - 2026-01-10

### Added

- **WebDAV (RFC 2518) support for file server**: The `WebDAVService` (formerly
  `FileHTTPServer`) supports distributed authoring via WebDAV when enabled with
  the `webdavEnabled` property.
  Full implementation includes:
  - PROPFIND - query resource properties with Depth 0, 1, or infinity
  - PROPPATCH - set/remove dead properties
  - MKCOL - create collections (directories)
  - COPY - copy resources with Depth and Overwrite support
  - MOVE - move resources with lock token validation
  - LOCK - exclusive and shared write locks with configurable timeout
  - UNLOCK - release locks by token
  
  Live properties (creationdate, displayname, getcontentlength, getcontenttype,
  getetag, getlastmodified, lockdiscovery, resourcetype, supportedlock) are
  computed from the filesystem. Lock management is thread-safe and in-memory.
  XML request parsing uses the Gonzalez streaming parser; XML response generation
  uses Gonzalez's XMLWriter for efficient NIO-based output.

- **JPMS module support**: Gumdrop is now a proper Java module (`org.bluezoo.gumdrop`)
  with `module-info.java`. The jar is compiled with `-release 8` for Java 8 runtime
  compatibility while including `module-info.class` for Java 9+ module system support.

- **Integrated XML and JSON parsing**: The Gonzalez XML parser (`org.bluezoo.gonzalez`)
  and JSON parser (`org.bluezoo.json`) are now part of the gumdrop API and available
  to users of the library. (These were later externalized as separate dependencies;
  see the [Unreleased] section above.)

- **`XMLParseUtils` utility class**: New utility class (`org.bluezoo.gumdrop.util.XMLParseUtils`)
  provides convenient methods for parsing XML using Gonzalez with NIO. Supports:
  - `parseFile()` - parses local files using NIO FileChannel
  - `parseURL()` - parses XML from URLs
  - `parseStream()` - parses from InputStream via ReadableByteChannel
  - `parseStreamWithDigest()` - parses while computing an MD5 digest
  
  Parser instances are cached per-thread and reused via `reset()` to minimize
  allocation overhead for repeated parsing operations. All methods support
  `publicId` parameter for catalog-based entity resolution in documents with
  external DTD references.

### Changed

- **Release artifact naming**: The release artifacts have been renamed for clarity:
  - `gumdrop-1.1.jar` - Core library for building on top of the Gumdrop framework
  - `gumdrop-container-1.1.jar` - Self-contained servlet container (executable fat jar)
  - `gumdrop-manager-1.1.war` - Manager web application
  
  The previous `server.jar` is now `gumdrop.jar`. The previous `gumdrop.jar` (fat jar)
  is now `gumdrop-container.jar`. This naming better reflects the intended use:
  - Use `gumdrop.jar` as a library dependency when extending Gumdrop's servers
  - Use `gumdrop-container.jar` for immediate deployment as a servlet container

- **All XML parsing now uses Gonzalez**: Replaced blocking SAX parser with the Gonzalez
  streaming XML parser throughout the codebase for consistent non-blocking behavior:
  - `ConfigurationParser` - gumdroprc configuration files
  - `DeploymentDescriptorParser` - web.xml and web-fragment.xml
  - `TldParser` - Tag Library Descriptor files
  - `XMLJSPParser` - XML-format JSP pages (JSPX)
  - `BasicRealm` - realm configuration XML

- Build system updated to use two-phase compilation:
  - Main sources compiled with `-release 8` for Java 8 compatibility
  - `module-info.java` compiled with `-release 9` for JPMS support

- **Java 8 API compliance enforced**: Fixed several Java 9+ APIs that had crept into
  the codebase. The `-release 8` flag now properly validates API usage at compile time:
  - Replaced `ObjectInputFilter` with `resolveClass()` override in `SessionSerializer`
  - Replaced `Set.of()` with `Collections.emptySet()` and static initializer blocks
  - Replaced `ProcessHandle.current().pid()` with `ManagementFactory.getRuntimeMXBean()`
  - Replaced `URLDecoder.decode(String, Charset)` with `URLDecoder.decode(String, String)`
  - Replaced `SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN` with default case handling

### Removed

- External `gonzalez-1.0.jar` and `jsonparser-1.2.jar` dependencies from `lib/`
  (sources now integrated directly into gumdrop)

## [1.0] - 2025-12-01

### Added

- Initial stable release of Gumdrop multipurpose Java server
- Event-driven, non-blocking architecture based on Java NIO
- Protocol implementations:
  - HTTP/1.1 and HTTP/2 with server push support
  - WebSocket (RFC 6455)
  - SMTP with STARTTLS and authentication
  - POP3 with APOP and SASL authentication
  - IMAP4rev1 with IDLE support
  - FTP with passive mode and TLS
  - DNS server and resolver
- Servlet 4.0 container with JSP 2.3 support
- DTLS support for secure UDP protocols
- Redis client with pub/sub support
- LDAP client for authentication
- OpenTelemetry integration for observability
- Cluster session replication
- Rate limiting and quota management
- Custom classloader for dependency isolation

