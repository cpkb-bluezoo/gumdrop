# Changelog

All notable changes to Gumdrop will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **HTTP/3 server and client over QUIC**: Full HTTP/3 support via the quiche
  native library (BoringSSL + quiche JNI bindings). Servers advertise HTTP/3
  availability through `Alt-Svc` headers; clients can connect directly over
  QUIC or discover HTTP/3 transparently via Alt-Svc upgrade.
  - `HTTPClient` supports `--http3` for direct QUIC connections with optional
    client certificates and SNI for alternate-host Alt-Svc targets
  - `HTTPClient` CLI (`main()`) for debugging HTTP connections across all
    protocol versions (HTTP/1.1, HTTP/2, HTTP/3), similar to curl

### Fixed

- `HTTPClientProtocolHandler` now fires `onConnected` and
  `onSecurityEstablished` callbacks on the `HTTPClientHandler`
- TLS client handshake is now initiated after TCP connect completes
- HEAD responses no longer hang waiting for a body that will never arrive

### Changed

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

