# gumdrop

<p align="center">
  <img src="web/hero.svg" alt="Gumdrop - Java Multi-Protocol Server" width="800"/>
</p>

<p align="center">
  <em>Multipurpose, asynchronous, non-blocking, event-driven Java multiserver and servlet container</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square" alt="Java 17+"/>
  <img src="https://img.shields.io/badge/License-LGPL%20v3-blue?style=flat-square" alt="LGPL v3"/>
  <img src="https://img.shields.io/badge/Dependencies-Low-brightgreen?style=flat-square" alt="Low Dependencies"/>
</p>

---

This is gumdrop, a multipurpose Java server framework using asynchronous,
non-blocking, event-driven I/O. It supports:
- a generic, extensible server framework that can transparently handle TLS
  connections from clients
    - TCP servers with TLS support
    - UDP servers with DTLS support
    - QUIC support via quiche/BoringSSL (TLS 1.3 always-on)
    - fully transparent SSL support
        - keystore/truststore configuration
        - client certificates
        - SSL protocols (TLS 1.2, 1.3)
        - cipher suite selection
        - named groups / PQC key exchange (X25519MLKEM768)
        - SNI
    - configurable pool of worker threads shared across all servers,
      completely independent of the number of client connections
    - internationalization and localization facilities, current translations
      include:
        - English
        - French
        - Spanish
        - German
    - centralized and secure realm interface for authentication and
      authorization, usable by multiple services
    - CIDR connection filtering, rate limiting, quota features
    - lightweight, simple dependency injection framework
    - client framework for creating clients to communicate with other servers
        - uses same event-driven asynchronous architecture for peers
        - can use I/O worker thread affinity to avoid context switching
- HTTP
    - HTTP/3 over QUIC
        - full HTTP/3 client and server via quiche/BoringSSL
        - QPACK header compression (via quiche)
        - HTTP/3 framing/stream multiplexing (via quiche h3 module)
        - TLS 1.3 always-on with post-quantum key exchange (X25519MLKEM768)
    - HTTP/2
        - all HTTP/2 frame types and stream multiplexing
        - HPACK header compression
    - HTTP/1.0 and 1.1
        - Chunked encoding and persistent connections
    - HTTPS with client certificate authentication, custom SSL parameters,
      cipher suites, and PQC supported groups
    - authentication framework supporting various methods
        - Basic
        - HTTP Digest
        - Bearer
        - OAuth
    - fast async event driven callback API for microservices with examples
    - WebDAV file service
        - supports fast NIO based data transfer
        - PUT and DELETE
        - RFC 2518 distributed authoring
            - PROPFIND, PROPPATCH for property management
            - MKCOL, COPY, MOVE for resource operations
            - LOCK, UNLOCK for write locking
    - complete, conformant Java servlet 4.0 container
        - secure classloader separation
        - separate thread pool configuration for servlet worker threads,
          distinct from I/O worker loops
        - asynchronous processing
        - enterprise DataSource and MailSession handling, JCA connection
          factories, administered objects and all JNDI resources
        - hot deployment
        - WebSocket servlet support with example showing how to use upgrade
        - programmatic registration of web descriptors
        - complete multipart/form-data handling
        - annotation-driven configuration and web fragments
        - server push
        - form-based and client certificate authentication in addition to
          base HTTP authentication methods
        - JSP 2.0 implementation
        - cluster session replication with security features:
            - AES-256-GCM encryption with shared secret
            - replay protection via sequence numbers and timestamps
            - per-node sequence tracking with sliding window
            - protobuf serialization for session attributes
            - deserialization filtering for complex objects
            - cluster node telemetry metrics
- SMTP
    - SMTPS
    - STARTTLS support
    - SMTP AUTH with numerous authentication methods for both standard
      clients and enterprise/military environments (see SASL section below)
    - 8-bit clean message transport
    - memory efficient processing of large messages
    - attack prevention features
    - persistent connections
    - transaction reset
    - connection filtering policy settings for MX mode or message submission
        - rate limiting
        - network block lists
        - max connections per IP
        - require authentication
    - extensible pipeline system for processing messages and performing
      authorisation checks:
        - SPF
        - DKIM
        - DMARC
        - custom parsed message processing
    - simple, extensible asynchronous handler mechanism for implementations
    - CHUNKING/BDAT
    - SMTPUTF8 internationalised email addresses
    - Postfix XCLIENT proxy support
    - message delivery requirements
        - Delivery Status Notifications (DSN)
        - REQUIRETLS
        - MT-PRIORITY
        - FUTURERELEASE
        - DELIVERBY
    - LIMITS support
    - SMTP client implementation for MTA forward message delivery
        - step-by-step asynchronous handler interfaces for event-driven
          client
        - supports TLS connections and STARTTLS
        - will use CHUNKING for efficiency if server supports it
    - example services for local mailbox delivery and relay
- IMAP4rev2
    - complete IMAP4rev2 implementation (RFC 9051)
    - IMAPS (implicit TLS on port 993)
    - STARTTLS support
    - full SASL authentication (see SASL section below)
    - multi-folder mailbox support with hierarchical namespaces
    - supported extensions:
        - IDLE (RFC 2177) - push notifications for mailbox changes
        - NAMESPACE (RFC 2342) - personal/shared namespace support
        - QUOTA (RFC 9208) - storage and message quotas
            - respects quotas defined in configuration
        - MOVE (RFC 6851) - atomic message move operations
        - UIDPLUS - extended UID operations
        - UNSELECT - close without expunge
        - CHILDREN - mailbox hierarchy indicators
        - LIST-EXTENDED, LIST-STATUS - enhanced mailbox listing
    - comprehensive SEARCH command with full RFC 9051 syntax
        - flag, date, size, header, and body searches
        - boolean operators (AND, OR, NOT)
        - sequence sets and UID sets
    - pluggable mailbox backend via standardized API
    - IMAP client with IMAPS and STARTTLS support
- POP3
    - complete POP3 implementation (RFC 1939)
    - POP3S (implicit TLS on port 995)
    - STARTTLS support (RFC 2595)
    - full SASL authentication (see SASL section below)
    - APOP authentication for legacy clients
    - supported extensions (RFC 2449):
        - UIDL - unique message identifiers
        - TOP - retrieve message headers
        - USER/PASS - plaintext authentication
        - CAPA - capability advertisement
        - UTF8 (RFC 6856) - internationalized mailboxes
    - pluggable mailbox backend via standardized API
    - exclusive mailbox locking for session isolation
    - POP3 client with POP3S and STLS support
- mailbox API
    - mbox backend
    - Maildir++ backend
    - extensible for custom backends
    - security features
    - mailbox indexing for fast IMAP search
- FTP
    - FTPS (implicit TLS on port 990)
    - explicit TLS via AUTH TLS/SSL (RFC 4217)
        - control channel encryption
        - PBSZ/PROT commands for data channel protection
        - PROT P for encrypted data transfers
        - FEAT command for capability advertisement
    - full IPv6 support (RFC 2428)
        - EPRT command for extended active mode
        - EPSV command for extended passive mode
        - automatic protocol detection (IPv4/IPv6)
        - EPSV ALL mode for IPv6-only clients
    - quota support
        - SITE QUOTA command
        - SITE SETQUOTA command
    - pluggable realm authentication via standardized mechanism
    - extensible, customizable virtual filesystem
        - local filesystem implementation provided with secure chroot, cross
          platform, configurable read/write permissions
        - extensible for cloud/database resource access
        - uses high performance NIO channels for data transfer
    - simple application handler, abstracted away from protocol details
    - supports binary and ASCII transfer modes
    - passive and active transfer modes
    - resume and append support
    - allows abort to cancel in-progress transfers
    - fully functional FTP file service implementation
- WebSockets
    - server and client built on top of HTTP transports
    - unified socket handler interface
- DNS
    - full DNS server implementation
    - DNS over DTLS for secure queries
    - DoT: DNS over TCP with TLS support
    - DoQ: DNS over QUIC
    - caching with TTL support
    - upstream server proxying
    - custom resolution via subclassing
    - supported record types:
        - A, AAAA (IPv4/IPv6 addresses)
        - CNAME (aliases)
        - MX (mail exchange)
        - NS (name servers)
        - PTR (reverse DNS)
        - SOA (start of authority)
        - TXT (text records)
    - flexible client resolver
- OpenTelemetry
    - native implementation (no OpenTelemetry SDK required)
    - distributed tracing with W3C Trace Context propagation
    - metrics collection (counters, histograms, gauges)
    - OTLP/HTTP export to any OpenTelemetry Collector
    - built-in instrumentation for HTTP, SMTP, IMAP, POP3, FTP
    - endpoint pooling with SelectorLoop affinity
    - configurable aggregation temporality (delta/cumulative)
    - custom instrumentation API for application-level tracing
- SASL authentication
    - centralized, extensible realm interface
        - decouples credentials, authentication, authorization from
          protocols
        - does not expose passwords by default
        - extensible for LDAP, identity providers, databases
    - all major authentication mechanisms supported
        - PLAIN (requires TLS)
        - LOGIN (requires TLS)
        - CRAM-MD5
        - DIGEST-MD5
        - SCRAM-SHA-256 (recommended!)
        - OAUTHBEARER (requires TLS)
        - GSSAPI/Kerberos
        - EXTERNAL for TLS client certificates
- Redis client
    - Redis 6+ ACL auth
    - full message subscription with pattern matching
    - TLS support, fully async
    - pipelining

### TLS Support

The server framework transparently supports TLS for all endpoints. Set
the `secure` server property to `true`, and communication with the client
will be encrypted. Security is configured on the `Listener` (or
via `Service` properties which delegate to it). Protocol handlers
receive plaintext and query `SecurityInfo` for TLS metadata. SSL
encryption and decryption occurs inline on the worker thread, so protocol
handlers need not be aware of the TLS layer. DTLS support applies to
`DatagramEndpoint` via `UDPTransportFactory`. QUIC always uses TLS 1.3
via BoringSSL (bundled with quiche).

The unified `Listener` API allows configuring cipher suites and
key exchange groups (including post-quantum hybrid groups like
X25519MLKEM768) across all transport types: TCP (JSSE), UDP (JSSE DTLS),
and QUIC (BoringSSL with TLS 1.3).

## Why Gumdrop?

- HTTP/3 and QUIC support
    - one of very few Java frameworks with HTTP/3 server support
      (only Netty offers comparable capability; JDK 26's JEP 517 is
      client-only)
    - servlet container runs transparently on top of HTTP/3
- high performance
    - Java NIO non-blocking I/O throughout
    - single-threaded event loops avoid context switching overhead
    - worker thread pool size independent of endpoint count
    - efficient memory usage with ByteBuffers
    - zero-copy file transfers where possible
- scalable architecture
    - handles tens of thousands of concurrent connections per server
    - horizontal scaling via cluster session replication
    - connection count limited only by OS file descriptors
- event-driven design
    - native event-driven architecture, not bolted on
    - callback-based handlers for protocol implementations
    - no blocking operations in I/O path, even async DNS lookups
    - natural fit for distributed, microservices architectures
- small and efficient
    - minimal memory footprint
    - fast startup time
    - single JAR deployment
- simple, extensible interfaces
    - clean separation of protocol handling from business logic
    - implement services without detailed protocol knowledge
    - pluggable authentication via Realm interface
    - pluggable storage via MailboxFactory interface
- low external dependencies
    - gonzalez (XML), jsonparser (JSON), quiche (QUIC/HTTP3), and J2EE APIs
    - self-contained implementations (protobuf, HPACK, ASN.1, OTel, etc.)
    - no dependency injection framework required
- requires Java 17+ (LTS)
    - UNIX domain socket support available natively
    - QUIC support requires native library (optional)
- transparent security
    - TLS/DTLS handled automatically by framework
    - configure once, apply to multiple endpoints
    - optional client certificate authentication built-in
    - rate limiting, quotas, IAM
- production ready
    - comprehensive protocol implementations
    - security hardening (rate limiting, filtering, attack prevention)
    - enterprise observability via OpenTelemetry integration

## Documentation

There is extensive documentation for all Gumdrop features:

- [Example web application](https://cpkb-bluezoo.github.io/gumdrop/web/)
- [Javadoc package and class documentation](https://cpkb-bluezoo.github.io/gumdrop/doc/)

## Configuration

The configuration of gumdrop is primarily contained in the
`gumdroprc` file. The remainder of the configuration is supplied
by standard Java system properties, e.g. the logging subsystem which uses
the `java.util.logging` package. External dependencies are
[Gonzalez](https://github.com/cpkb-bluezoo/gonzalez) (XML parsing),
[jsonparser](https://github.com/cpkb-bluezoo/jsonparser) (JSON parsing),
and [quiche](https://github.com/cloudflare/quiche) (QUIC/HTTP3 support);
otherwise only J2EE APIs are required for the servlet container.

Gumdrop configuration is an extensible dependency injection framework that
allows you to wire together the various components declaratively in a
flexible fashion. The language used for the configuration description is
XML.

## Building

### Java (core framework)

You can currently use `ant` to build the project:

```bash
ant dist
```

### TLS certificates

The HTTPS listener requires a PKCS#12 keystore and the HTTP/3 (QUIC)
listener requires PEM certificate and key files (see
[Security documentation](web/security.html#quic-tls) for details on
why these differ). These are not checked in to the repository; you must
generate them before running the server.

The easiest way for local development is
[mkcert](https://github.com/FiloSottile/mkcert), which creates
certificates trusted by your browser:

```bash
# One-time: install the local CA into your system trust store
mkcert -install

# Generate certificate and key for localhost into etc/
mkcert -cert-file etc/localhost+2.pem -key-file etc/localhost+2-key.pem \
    localhost 127.0.0.1 ::1
```

This produces `etc/localhost+2.pem` and `etc/localhost+2-key.pem`. Then
generate a PKCS#12 keystore from the same certificate for the HTTPS
listener:

```bash
openssl pkcs12 -export -in etc/localhost+2.pem -inkey etc/localhost+2-key.pem \
    -out etc/localhost+2.p12 -name localhost -passout pass:changeit
```

The configuration files in `etc/` reference these by filename (e.g.
`localhost+2.p12`); they are not checked in to the repository.

### Running

Start the server with one of the example configurations in `etc/`:

```bash
./start etc/gumdroprc.servlet
```

You should then be able to point a browser at
[http://localhost:8080/](http://localhost:8080/) or
[https://localhost:8443/](https://localhost:8443/) to see the example web
application included, which includes full documentation of the framework.

Other example configurations are available:

| Configuration | Description |
|---|---|
| `etc/gumdroprc.servlet` | Servlet container (HTTP, HTTPS, HTTP/3) |
| `etc/gumdroprc.webdav` | WebDAV file server |
| `etc/gumdroprc.ftp.file.simple` | Simple FTP file server |
| `etc/gumdroprc.ftp.file.anonymous` | Anonymous FTP file server |
| `etc/gumdroprc.ftp.file.rolebased` | Role-based FTP file server |
| `etc/gumdroprc.imap` | IMAP mailbox access |
| `etc/gumdroprc.pop3` | POP3 mailbox access |
| `etc/gumdroprc.smtp.localdelivery` | SMTP local delivery |
| `etc/gumdroprc.smtp.simplerelay` | SMTP relay (authenticated) |
| `etc/gumdroprc.dns` | DNS caching proxy (UDP, DoT, DoQ) |

You can configure any of these to serve your own application and run it
immediately.

### QUIC support (native library)

QUIC support requires the [quiche](https://github.com/cloudflare/quiche)
library and a JNI glue library that bridges quiche into the JVM. QUIC is
optional; the core framework and all TCP/UDP-based protocols work without it.

#### Why build from source?

Gumdrop's JNI layer calls both quiche functions and BoringSSL functions
directly (for TLS 1.3 configuration, cipher suite selection, and PQC key
exchange groups like X25519MLKEM768). BoringSSL is statically linked into
`libquiche` during the Cargo build, but its headers are not installed
separately. **Building quiche from source is the recommended approach** on
all platforms because it provides:

- the quiche C headers (`quiche.h`)
- the BoringSSL headers (`openssl/ssl.h`, `openssl/err.h`) from the
  vendored copy in the source tree
- a `libquiche` shared library with the BoringSSL symbols the JNI code
  links against

> **Note on system packages:** Debian/Ubuntu ship `libquiche-dev`, but the
> package does not include the BoringSSL headers or guarantee that the
> BoringSSL symbols are exported from the shared library. Since the Gumdrop
> JNI code creates BoringSSL `SSL_CTX` objects and passes them into quiche,
> the headers and symbols must come from the same BoringSSL build. Use the
> source build below.

#### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Rust toolchain (`rustup` + `cargo`) | stable | Install via [rustup.rs](https://rustup.rs/) |
| C compiler | gcc / clang | Xcode Command Line Tools on macOS |
| CMake | 3.x+ | Required by BoringSSL (built by quiche) |
| JDK | 8+ | `JAVA_HOME` must be set |
| Git | any | To clone the quiche repository |

#### Platform matrix

| Platform | Architecture | Status |
|----------|-------------|--------|
| macOS | x86\_64 / aarch64 (Apple Silicon) | Supported |
| Linux | x86\_64 / aarch64 | Supported |
| Windows | x86\_64 | Untested (should work with MSVC) |

#### Step 1: Build quiche from source

```bash
# Clone quiche and pin to a known release
git clone --recursive https://github.com/cloudflare/quiche.git
cd quiche
git checkout 0.25.0

# Build the C FFI shared library (includes HTTP/3 h3 module)
cargo build --release --features ffi
```

Make sure to use `--recursive` to get BoringSSL as a submodule.

This produces:

| Artifact | Path (relative to repo root) |
|----------|------------------------------|
| Shared library (macOS) | `target/release/libquiche.dylib` |
| Shared library (Linux) | `target/release/libquiche.so` |
| quiche C headers | `quiche/include/` |
| BoringSSL headers | `quiche/deps/boringssl/src/include/` |

#### Step 2: Compile the JNI native library

Set `QUICHE_DIR` to the **root of the cloned quiche repository** (the
directory containing `target/`, `quiche/`, etc.) and run `ant dist`:

```bash
export QUICHE_DIR=/path/to/quiche
ant dist
```

The build automatically detects the platform (macOS/Linux), locates the
JDK headers, and compiles the JNI source files in
`src/org/bluezoo/gumdrop/jni/` into `dist/libgumdrop.dylib`
(macOS) or `dist/libgumdrop.so` (Linux). If `QUICHE_DIR` is not set
or the quiche headers are not found, the native build is skipped and only
the Java artifacts are produced.

The build references three paths under `$QUICHE_DIR`:

| Path under `$QUICHE_DIR` | Provides |
|--------------------------|----------|
| `quiche/include` | quiche C headers (`quiche.h`) |
| `quiche/deps/boringssl/src/include` | BoringSSL headers (`openssl/ssl.h`) |
| `target/release` | Built shared library (`-lquiche`) |

<details>
<summary>Manual compilation (alternative to <code>ant dist</code>)</summary>

If you prefer to compile the JNI library manually:

```bash
export QUICHE_DIR=/path/to/quiche
export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || echo $JAVA_HOME)

# macOS (Apple Silicon / x86_64)
cc -shared -fPIC -o libgumdrop.dylib \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  -I"$QUICHE_DIR/quiche/include" \
  -I"$QUICHE_DIR/quiche/deps/boringssl/src/include" \
  -L"$QUICHE_DIR/target/release" \
  -lquiche -lresolv \
  src/org/bluezoo/gumdrop/jni/quiche_jni.c \
  src/org/bluezoo/gumdrop/jni/ssl_ctx_jni.c \
  src/org/bluezoo/gumdrop/jni/h3_jni.c \
  src/org/bluezoo/gumdrop/jni/dns_jni.c

# Linux
cc -shared -fPIC -o libgumdrop.so \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  -I"$QUICHE_DIR/quiche/include" \
  -I"$QUICHE_DIR/quiche/deps/boringssl/src/include" \
  -L"$QUICHE_DIR/target/release" \
  -lquiche -lresolv \
  src/org/bluezoo/gumdrop/jni/quiche_jni.c \
  src/org/bluezoo/gumdrop/jni/ssl_ctx_jni.c \
  src/org/bluezoo/gumdrop/jni/h3_jni.c \
  src/org/bluezoo/gumdrop/jni/dns_jni.c
```

</details>

> **pkg-config alternative:** If you build quiche with
> `cargo build --release --features ffi,pkg-config-meta`, a `quiche.pc`
> file is generated in `target/release/`. You can then replace the
> quiche-specific `-I` and `-L` flags with:
>
> ```bash
> PKG_CONFIG_PATH="$QUICHE_DIR/target/release" pkg-config --cflags --libs quiche
> ```
>
> You still need the separate `-I` flag for the BoringSSL headers, since
> the generated `.pc` file only covers quiche's own include directory.

#### Step 3: Runtime library path

Both `libgumdrop` and `libquiche` must be findable by the dynamic
linker at runtime. There are several options:

**Option A: Set the library path (quick, good for development)**

```bash
# macOS
export DYLD_LIBRARY_PATH="$QUICHE_DIR/target/release:$(pwd)"

# Linux
export LD_LIBRARY_PATH="$QUICHE_DIR/target/release:$(pwd)"
```

**Option B: Pass as a JVM argument**

```bash
java -Djava.library.path="$QUICHE_DIR/target/release:$(pwd)" ...
```

**Option C: Install into a standard location (recommended for deployment)**

Copy the built libraries to a directory already on the linker search path:

```bash
# Copy libquiche
sudo cp "$QUICHE_DIR/target/release/libquiche.dylib" /usr/local/lib/   # macOS
sudo cp "$QUICHE_DIR/target/release/libquiche.so" /usr/local/lib/      # Linux

# Copy the JNI library you built in Step 2
sudo cp libgumdrop.dylib /usr/local/lib/   # macOS
sudo cp libgumdrop.so /usr/local/lib/      # Linux

# Linux only: refresh the linker cache
sudo ldconfig
```

With Option C, no environment variables are needed at runtime.

#### Verifying the build

A quick smoke test that the native library loads:

```bash
java -Djava.library.path="." -cp build/classes \
  -Dorg.bluezoo.gumdrop.quic.test=true \
  org.bluezoo.gumdrop.GumdropNative
```

If it exits without `UnsatisfiedLinkError`, the JNI bindings are
correctly compiled and linked.

## Logo

The gumdrop logo is a gumdrop torus, generated using [POV-Ray](http://www.povray.org/).
A gumdrop torus is a [mathematical construct](http://www.povray.org/documentation/view/3.6.1/448/#s02_07_07_02_i75)
 - the gumdrop logo is such a torus viewed from an angle that makes it resemble
the letter G. All logo images were created using POV-Ray and/or Gimp and are
copyright 2005 Chris Burdess.

## Licensing

Gumdrop is licensed under the GNU Lesser General Public Licence version 3.
See `COPYING` for full terms.


-- Chris Burdess
