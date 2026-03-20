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
non-blocking, event-driven I/O.

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
    - connection count limited only by OS file descriptors
    - transport-level backpressure provides complete flow control
    - horizontal scaling via cluster session replication
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
    - [gonzalez](https://github.com/cpkb-bluezoo/gonzalez) (XML), [jsonparser](https://github.com/cpkb-bluezoo/jsonparser) (JSON), [quiche](https://github.com/cloudflare/quiche) (QUIC/HTTP3), and J2EE APIs
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
    - comprehensive protocol implementations (HTTP, SMTP, IMAP, POP3, FTP, DNS, MQTT, SOCKS)
    - security hardening (rate limiting, filtering, attack prevention)
    - enterprise observability via OpenTelemetry integration

### Comparison with other frameworks

| Feature | Gumdrop | Netty | Jetty | Tomcat |
|---------|:-------:|:-----:|:-----:|:------:|
| Servlet container | ✓ | ✗ | ✓ | ✓ |
| Low-level async I/O framework | ✓ | ✓ | ✗ | ✗ |
| Standard NIO ByteBuffer | ✓ | ✗ (ByteBuf) | ✓ | ✓ |
| HTTP/3 & QUIC | ✓ | ✓ | ✓ | ✗ |
| SMTP, DNS | ✓ | (clients only, partial) | ✗ | ✗ |
| MQTT broker &amp; client | ✓ | ✗ | ✗ | ✗ |
| IMAP, POP3, FTP, SOCKS | ✓ | ✗ | ✗ | ✗ |
| Transport-level flow control | ✓ | ✓ | ✗ | ✗ |
| Built-in telemetry (no agent) | ✓ | ✗ | ✗ | ✗ |
| Unified auth realm across protocols | ✓ | ✗ | ✗ | ✗ |
| Single JAR, minimal deps | ✓ | ✗ | ✗ | ✗ |
| No DI framework required | ✓ | ✓ | ✗ | ✓ |

Gumdrop uniquely combines a servlet container with a complete low-level networking framework, so you can run J2EE web apps and build highly efficient custom protocol servers from the same codebase. Unlike Netty, it uses standard `ByteBuffer` throughout — no proprietary buffer abstraction to learn. Its HTTP layer is built on the same simple and coherent event-driven I/O framework used for SMTP, IMAP, DNS, MQTT, FTP, and SOCKS, so you can add fully async mail, messaging, file transfer, DNS, or proxy services without bolting on separate stacks.

## Full feature list

- a generic, extensible server framework that can transparently handle TLS
  connections from clients
    - TCP servers with TLS support
    - UDP servers with DTLS support
    - QUIC support via [quiche](https://github.com/cloudflare/quiche)/BoringSSL (TLS 1.3 always-on)
    - fully transparent SSL support for all protocols
        - keystore/truststore configuration
        - client certificates
        - SSL protocols (TLS 1.2, 1.3)
        - cipher suite selection
        - named groups / PQC key exchange (X25519MLKEM768)
        - SNI
    - configurable pool of worker threads shared across all servers,
      completely independent of the number of client connections
    - transport-level flow control with backpressure for large
      transfers over TCP and QUIC connections
    - internationalization and localization facilities, current translations
      include:
        - English
        - French
        - Spanish
        - German
    - centralized and secure realm interface for authentication and
      authorization, usable by multiple services, with mTLS and SASL mechanisms
    - CIDR connection filtering, rate limiting, quota features
    - lightweight, simple dependency injection framework
    - client framework for creating clients to communicate with other servers
        - uses same event-driven asynchronous architecture for peers
        - can use I/O worker thread affinity to avoid context switching
- HTTP
    - HTTP/3 over QUIC
        - full HTTP/3 client and server via [quiche](https://github.com/cloudflare/quiche)/BoringSSL
        - QPACK header compression (via [quiche](https://github.com/cloudflare/quiche))
        - HTTP/3 framing/stream multiplexing (via [quiche](https://github.com/cloudflare/quiche) h3 module)
        - request pseudo-header validation, 1xx informational responses
        - Priority header (RFC 9218), GOAWAY last-stream-ID tracking
        - configurable QUIC transport parameters
        - WebSocket over HTTP/3 (RFC 9220) via Extended CONNECT
    - HTTP/2
        - all HTTP/2 frame types and stream multiplexing
        - HPACK header compression
        - graceful GOAWAY, PING keep-alive, SETTINGS ACK timeout, TLS cipher validation
        - client concurrent-stream limiting, idle timeout
    - HTTP/1.0 and 1.1
        - Chunked encoding and persistent connections
        - idle connection timeout, graceful shutdown, Expect: 100-continue
        - OPTIONS * and configurable TRACE method support
    - HTTP client: Connection: close, obs-fold, Content-Length validation, header size limit, Digest SHA-256
    - authentication framework supporting:
        - Basic
        - HTTP Digest (MD5, SHA-256)
        - Bearer
        - OAuth (token introspection + local JWT validation)
        - mTLS
    - fast async event driven callback API for microservices with examples
    - 103 Early Hints (RFC 8297) for resource preloading across HTTP/1.1, HTTP/2, and HTTP/3
    - unified flow control over HTTP transports
    - WebDAV file service
        - supports fast NIO based data transfer
        - PUT and DELETE (including recursive collection DELETE with Multi-Status)
        - RFC 4918 distributed authoring with full If header conditional evaluation
            - PROPFIND, PROPPATCH for live and dead property management
            - dead property storage with xattr primary and sidecar fallback
            - MKCOL, COPY, MOVE for resource operations (with dead property propagation)
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
        - DKIM (verification and signing, RSA-SHA256 and Ed25519-SHA256)
        - DMARC (policy evaluation, aggregate XML reporting, forensic/failure reporting)
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
    - ETRN command recognition (RFC 1985)
    - SMTP client implementation for MTA forward message delivery
        - step-by-step asynchronous handler interfaces for event-driven
          client
        - supports TLS connections and STARTTLS
        - will use CHUNKING for efficiency if server supports it
        - full EHLO capability parsing (15 extension keywords)
        - MAIL FROM extension parameters (BODY, SMTPUTF8, RET/ENVID, REQUIRETLS, MT-PRIORITY, FUTURERELEASE, DELIVERBY)
        - RCPT TO with DSN parameters (NOTIFY, ORCPT)
        - VRFY and EXPN commands
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
        - LITERAL- (RFC 7888) - non-synchronizing literals
        - ID (RFC 2971) - server identification
        - CONDSTORE (RFC 7162) - per-message modification sequences
            - MODSEQ in FETCH, SEARCH, and STORE responses
            - UNCHANGEDSINCE conditional STORE
            - HIGHESTMODSEQ in SELECT/EXAMINE/STATUS
        - QRESYNC (RFC 7162) - efficient mailbox resynchronization
            - VANISHED (EARLIER) for expunged UIDs on reconnect
            - session-wide VANISHED instead of EXPUNGE
    - async FETCH streaming for large message bodies
    - comprehensive SEARCH command with full RFC 9051 syntax
        - flag, date, size, header, body, and MODSEQ searches
        - boolean operators (AND, OR, NOT)
        - sequence sets and UID sets
    - pluggable mailbox backend via standardized API
    - IMAP client with IMAPS and STARTTLS support
        - QUOTA commands (RFC 9208) - GETQUOTA/GETQUOTAROOT
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
        - RESP-CODES (RFC 2449) - extended error response codes
        - AUTH-RESP-CODE (RFC 3206) - authentication error codes
        - EXPIRE, LOGIN-DELAY (RFC 2449) - policy advertisement
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
        - data connection IP verification (RFC 4217 section 10)
        - FEAT command for capability advertisement
    - SIZE, MDTM, MLST/MLSD machine-readable listings (RFC 3659)
    - UTF-8 pathnames via OPTS (RFC 2640)
    - STAT directory listing over control connection
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
    - extension negotiation framework (RFC 6455 §9) with permessage-deflate
      compression (RFC 7692)
    - WebSocket over HTTP/3 (RFC 9220) via Extended CONNECT with
      `HTTP3WebSocketListener`
    - configurable maximum message size with close code 1009 enforcement
    - close code validation (RFC 6455 §7.4) rejecting reserved wire codes
    - SecureRandom masking keys (RFC 6455 §5.3)
- MQTT
    - full MQTT message broker and client
    - MQTT 3.1.1 and MQTT 5.0 (simultaneous version negotiation)
    - all packet types and QoS levels (0, 1, 2)
    - MQTTS (MQTT over TLS on port 8883)
    - MQTT over WebSocket for browser-based clients
    - SAX-style incremental parser — streams PUBLISH payloads up to 256 MB
      without buffering entire packets in memory
    - pluggable NIO channel-based message store for payload persistence
        - default in-memory store with fast path for small messages
        - override for file-backed or distributed storage
    - horizontal fan-out: payload chunks read once and broadcast to all
      subscribers, minimising I/O for high fan-out topics
    - topic wildcard matching (`+` single-level, `#` multi-level) via trie-based TopicTree
    - retained messages, Last Will and Testament
    - clean session management
    - MQTT 5.0 properties (user properties, content type, message expiry,
      authentication method/data, reason codes)
    - staged handler pattern for async connection, publish, and subscribe
      authorization
    - default service accepts all connections (with optional realm authentication)
    - broker components: SubscriptionManager, RetainedMessageStore, WillManager, QoSManager
    - fully asynchronous MQTT client with SelectorLoop affinity
        - TLS support
        - QoS 0, 1, 2 publish and subscribe
        - Last Will and Testament
        - MQTT 5.0 version negotiation
        - MQTTMessageContent delivery for streaming large received payloads
    - OpenTelemetry instrumentation (connections, publishes, subscribes,
      authentication, session duration, payload size)
    - localized log and error messages (English, French, Spanish, German)
- DNS
    - full DNS server implementation
    - DNS over DTLS for secure queries
    - DoT: DNS over TLS with session resumption, TCP Fast Open, SPKI pinning,
      connection pooling
    - DoQ: DNS over QUIC with error codes, 0-RTT, connection reuse, padding
    - DoH: DNS over HTTPS
    - EDNS0 support with DNS cookies (RFC 7873)
    - DNS message compression (RFC 1035 section 4.1.4)
    - upstream proxying with response ID validation and TCP fallback
    - caching with TTL support
    - custom resolution via subclassing
    - DNSSEC validation (RFC 4033-4035, RFC 5155)
        - EDNS0 DO bit, AD/CD flags
        - RRSIG signature verification (RSA-SHA256/512, ECDSA P-256/P-384,
          Ed25519, Ed448)
        - DS digest verification (SHA-1, SHA-256, SHA-384)
        - chain-of-trust validation with async DNSKEY/DS fetching
        - NSEC and NSEC3 authenticated denial-of-existence
        - configurable trust anchors (IANA root KSK pre-loaded)
        - all crypto CPU-bound, NIO-safe
    - supported record types:
        - A, AAAA (IPv4/IPv6 addresses)
        - CNAME (aliases)
        - MX (mail exchange)
        - NS (name servers)
        - PTR (reverse DNS)
        - SOA (start of authority)
        - SRV (service location)
        - TXT (text records)
        - DS, RRSIG, DNSKEY, NSEC, NSEC3, NSEC3PARAM (DNSSEC)
    - flexible async client resolver
        - UDP, TCP, DoT, DoQ, DoH transports
- SOCKS proxy
    - SOCKS4, SOCKS4a, and SOCKS5 (RFC 1928) protocol support
    - auto-detection of SOCKS version from first byte
    - SOCKS5 authentication methods:
        - no authentication
        - username/password (RFC 1929)
        - GSSAPI/Kerberos (RFC 1961) via existing SASL infrastructure
    - pluggable realm authentication via standardized mechanism
    - async connect authorization handler for custom policies
    - CIDR-based destination allow/block filtering
    - bidirectional TCP relay with transport-level backpressure
    - SelectorLoop affinity — upstream connections share the client's
      event loop thread for lock-free relaying
    - configurable max concurrent relays and idle relay timeout
    - fully async, non-blocking — DNS resolution, upstream connect, and
      TLS handshake all handled asynchronously
    - abstract SOCKSService for custom implementations
    - DefaultSOCKSService for zero-config operation
    - composable SOCKS client handler for tunneling any protocol through
      a SOCKS proxy (HTTP, SMTP, IMAP, MQTT, Redis, LDAP, etc.)
- OpenTelemetry
    - native implementation (no OpenTelemetry SDK required)
    - distributed tracing with W3C Trace Context propagation
    - metrics collection (counters, histograms, gauges)
    - OTLP/HTTP and OTLP/gRPC export to any OpenTelemetry Collector
    - file export for JSONL
    - built-in instrumentation for HTTP, SMTP, IMAP, POP3, FTP, MQTT
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
        - GSSAPI/Kerberos (RFC 4752) — keytab-based, event-loop safe
        - EXTERNAL for TLS client certificates
- LDAP client and LDAPRealm
    - fully asynchronous LDAPv3 client (RFC 4511)
    - simple bind (RFC 4513 §5.1) and SASL bind (RFC 4513 §5.2)
        - PLAIN, CRAM-MD5, DIGEST-MD5, EXTERNAL — all non-blocking
        - GSSAPI/Kerberos — worker-thread offloaded for KDC contact
    - LDAPS (implicit TLS) and STARTTLS
    - search, modify, add, delete, compare, modifyDN, extended operations
    - abandon, controls (request/response), unsolicited notifications
    - intermediate response handling, full search filter support (~=, :=)
    - LDAPRealm for LDAP-backed authentication across all protocols
        - search-then-bind pattern with configurable user filter
        - role/group membership via memberOf attribute
        - certificate-to-user mapping (binary or subject DN mode)
        - Active Directory compatible
- Redis client
    - RESP2 and RESP3 protocol support (HELLO for protocol negotiation)
    - Redis 6+ ACL auth, CLIENT SETNAME/GETNAME/ID, RESET
    - full message subscription with pattern matching (RESP3 Push type)
    - SCAN/HSCAN/SSCAN/ZSCAN cursor-based iteration
    - blocking commands (BLPOP, BRPOP, BLMOVE)
    - Redis Streams (XADD, XREAD, XRANGE, XLEN, XTRIM, XACK, XGROUP, XPENDING)
    - TLS support, fully async, pipelining
- gRPC service and client
    - efficient event based processing of .proto definitions
    - no stubs or external dependencies required
    - operates over HTTP/2 or HTTP/3

## Documentation

There is extensive documentation for all Gumdrop features:

- The [example web application](https://cpkb-bluezoo.github.io/gumdrop/web/) contains detailed documentation for all features
- [Javadoc package and class documentation](https://cpkb-bluezoo.github.io/gumdrop/doc/)
- [RFC compliance matrix](RFC-COMPLIANCE.md) showing extent of support for mandatory and optional RFC features
- [Framework comparison](docs/FRAMEWORK-COMPARISON.md) — deployment size and speed vs Netty, Jetty, Tomcat, Spring Boot

## Configuration

See the [Configuration documentation](https://cpkb-bluezoo.github.io/gumdrop/web/configuration.html) for details on configuration including `gumdroprc`, dependency injection, and component wiring. For TLS certificates (HTTPS, HTTP/3, local development with mkcert), see the [Security documentation](https://cpkb-bluezoo.github.io/gumdrop/web/security.html#tls).

## Building and running

For build, run, and QUIC support instructions, see [BUILDING.md](BUILDING.md).

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
