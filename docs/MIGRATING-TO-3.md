# Migrating from Gumdrop 2 to Gumdrop 3

Gumdrop 3 is a breaking release. This guide maps the 2.x types and idioms you
are most likely to have used onto their 3.0 equivalents. The release notes
are in the [CHANGELOG](../CHANGELOG.md); the naming rules are in
[CONTRIBUTING.md](../CONTRIBUTING.md#gumdrop-3-naming-conventions).

Because the rename was done mechanically, most of the migration is a find and
replace using the rules below. The structural changes (no `Service`
subclassing, no XML, no singleton) need code changes.

## Renaming rules

| 2.x | 3.0 | Examples |
|-----|-----|----------|
| Acronyms in upper case | CamelCase acronyms | `HTTPClient` to `HttpClient`, `SMTPListener` to `SmtpListener`, `TCPEndpoint` to `TcpEndpoint`, `SASLMechanism` to `SaslMechanism`, `JSPPage` to `JspPage`, `AMQPFrame` to `AmqpFrame` |
| POP3 | `Pop3` | `POP3Client` to `Pop3Client` |
| Tradenames | Written as the tradename | `WebDAV`, `WebSocket` (not `Webdav`, `Websocket`) |
| `*Service` (application tier) | `*Server` in the protocol's `server` package | `SMTPService` to `smtp.server.SmtpServer` |
| `Server*` prefix on client-side reply handlers | prefix dropped | `ServerEhloReplyHandler` to `EhloReplyHandler`, `ServerOpenHandler` to `OpenHandler` |
| Protocol classes in one flat package | `server` and `client` subpackages | `http.HTTPRequestHandler` to `http.server.HttpRequestHandler` |

Names that only changed case can usually be fixed by a case-sensitive
find and replace on the type name; the package moves need the imports
updated as well.

## Application tier

| 2.x | 3.0 |
|-----|-----|
| `HTTPService` | `org.bluezoo.gumdrop.http.HttpServer`, built with `HttpServer.compose()` |
| `ServletService` | `servlet.server.ServletRequestHandler` installed on an `HttpServer` |
| `WebDAVService` | `webdav.server.WebDAVRequestHandler` installed on an `HttpServer` |
| `WebSocketService` | `websocket.server.WebSocketRequestHandler` installed on an `HttpServer` |
| `HTTPRequestHandlerFactory` | removed: pass an `HttpRequestHandler`, or a small router, to the server |
| `SMTPService` | `smtp.server.SmtpServer` |
| `IMAPService` | `imap.server.ImapServer` |
| `POP3Service` | `pop3.server.Pop3Server` |
| `FTPService` | `ftp.server.FtpServer` |
| `DNSService` | `dns.server.DnsServer` |
| `MQTTService` | `mqtt.server.MqttServer` |
| `SOCKSService` | `socks.server.SocksServer` |
| `MDNSService` | `mdns.server.MdnsServer` |
| `GrpcService` | `grpc.server.GrpcServer` |
| Stock services (`SimpleRelayService`, `DefaultIMAPService`, `AnonymousFTPService`, ...) | session providers in the protocol's `server` package (for example `ftp.server.AnonymousFtpSessionProvider`, `imap.server.DefaultImapSessionProvider`) |
| `Service` interface | `Server` |

Applications no longer subclass a server to customise behaviour. They
implement the protocol's handler interfaces and compose them with the server.
See [web/configuration.html](../web/configuration.html).

## The Gumdrop instance and configuration

| 2.x | 3.0 |
|-----|-----|
| `Gumdrop.getInstance()` singleton | an explicit `Gumdrop` instance from `Gumdrop.boot()` (or `Gumdrop.boot(GumdropConfig)`), passed to `start(gumdrop)` / `connect(gumdrop, handler)` |
| `gumdroprc` XML, `ConfigurationParser`, `ComponentRegistry` | removed: configure in Java. The stock servlet container reads a minimal `server.xml` (see [CONTAINER-DEPLOYMENT.md](CONTAINER-DEPLOYMENT.md)) |
| `Gumdrop.main()` | removed: write your own `main` |
| `health` package (`HealthService`) | removed with no replacement |
| `TlsConfig` and `ClientTlsConfig` | a single material-only `TlsConfig` |
| JSSE `SSLEngine` integration | the in-tree TLS engine; see [web/tls.html](../web/tls.html) |
| `org.bluezoo.gumdrop.telemetry.protobuf` | the jprotobuf library, package `org.bluezoo.protobuf` |

## Configuration methods, realms and clients

| 2.x | 3.0 |
|-----|-----|
| `setXxx` wiring methods on listeners, servers, clients and policy objects | fluent methods named for the setting that return the object: `setRealm(r)` to `realm(r)`, `setEnableIDLE(true)` to `enableIDLE(true)`, `setMaxConnections(n)` to `maxConnections(n)`; the protocol listeners return their own type, so settings chain after `port()` |
| `setPort(int)`, `setWildcard(boolean)` on listeners | `port(int)`, `bindWildcard()` |
| `setListeners(List)` on servers, `RoleBasedQuotaManager.setRoleQuota` | `addListener(...)`, `addRoleQuota(...)` |
| `Realm` methods that return their answer (`passwordMatch`, `isUserInRole`, ...) and `getPassword` | every lookup takes a `RealmCallback` and never blocks; a realm with in-memory answers implements `SynchronousRealm` |
| `AmqpClientRecovery`, `Amqp1ClientRecovery` | `AmqpClient`, `Amqp1Client` |
| `ldap.asn1` package | `asn1` |
| `h2Enabled`, `h2cUpgradeEnabled`, `h3Enabled` on `HttpClient`, `WebSocketClient`, `ConnectIpClient`, `ConnectUdpClient` | `versions(HttpVersion...)`, the set of permitted versions (default HTTP/3, HTTP/2 and HTTP/1.1; the highest is tried first). `h3Enabled(true)` is `versions(HTTP_3)`, `h3Enabled(false)` is `versions(HTTP_2_0, HTTP_1_1)`, `h2Enabled(false)` is leaving out `HTTP_2_0`. `h2WithPriorKnowledge` is unchanged and needs `HTTP_2_0` permitted |
| `setSecure`, `setPinnedSPKIFingerprints`, `setDefaultPort` on `TcpDnsClientTransport`; `setPinnedCertFingerprint`, `setCaFile` on `DoQClientTransport`; `setPath` on `DoHClientTransport`; `tls(...)` on all three | `secure`, `pinnedSpkiFingerprints`, `defaultPort`, `pinnedCertFingerprint`, `caFile`, `path`, all returning the transport, so `TcpDnsClientTransport.createDoT().pinnedSpkiFingerprints(pins).tls(tls)` chains |

## Moved to new packages

| 2.x | 3.0 |
|-----|-----|
| `auth.LDAPRealm` | `auth.ldap.LdapRealm` |
| `auth.OAuthRealm` | `auth.oauth.OAuthRealm` |
| `quic.tls.Hkdf` | `crypto.Hkdf` |
| shared AMQP codec types in `amqp.client` (`AMQPFrame`, `FieldTable`, `BasicProperties`, ...) | `amqp` (`AmqpFrame`, `FieldTable`, `BasicProperties`, ...) |
| `amqp.client.handler.*` | `amqp.client` |

## Finding anything else

A type that is not listed has either only changed case (apply the rules
above) or moved between packages without being renamed. Searching for the
simple class name will find it. The package-level javadoc of each protocol
describes the current layout.
