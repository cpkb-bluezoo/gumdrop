# RFC Compliance Matrix

## DNS

### Applicable RFCs

| RFC | Title | Status |
|-----|-------|--------|
| RFC 1035 | Domain Names: Implementation and Specification | Core |
| RFC 1034 | Domain Names: Concepts and Facilities | Referenced |
| RFC 3596 | DNS Extensions to Support IPv6 | Implemented (AAAA) |
| RFC 7858 | DNS over Transport Layer Security (DoT) | Implemented |
| RFC 9250 | DNS over Dedicated QUIC Connections (DoQ) | Implemented |
| RFC 8484 | DNS Queries over HTTPS (DoH) | Implemented |
| RFC 2308 | Negative Caching of DNS Queries | Implemented |
| RFC 6891 | Extension Mechanisms for DNS (EDNS0) | Implemented |
| RFC 8305 | Happy Eyeballs v2 | Concept (parallel A/AAAA) |
| RFC 5452 | Measures for Making DNS More Resilient against Forged Answers | Implemented |
| RFC 2782 | A DNS RR for specifying the location of services (SRV) | Implemented |
| RFC 7873 | Domain Name System (DNS) Cookies | Implemented |
| RFC 7766 | DNS Transport over TCP | Implemented |
| RFC 4033 | DNS Security Introduction and Requirements | Implemented |
| RFC 4034 | Resource Records for the DNS Security Extensions | Implemented |
| RFC 4035 | Protocol Modifications for the DNS Security Extensions | Implemented |
| RFC 5155 | DNS Security (DNSSEC) Hashed Authenticated Denial of Existence | Implemented |
| RFC 8624 | Algorithm Implementation Requirements and Usage Guidance for DNSSEC | Referenced |

---

### RFC 1035 — Domain Names: Implementation and Specification

#### Section 3 — Domain Name Space and RR Definitions

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| TYPE values (A, NS, CNAME, SOA, PTR, MX, TXT) | 3.2.2 | Compliant | All standard types implemented in `DNSType` |
| QTYPE values (AXFR, MAILB, MAILA, ANY) | 3.2.3 | Partial | Only ANY(255) implemented; AXFR/MAILB/MAILA omitted (zone transfer not supported) |
| CLASS values (IN, CH, HS) | 3.2.4 | Compliant | CS(2) omitted as explicitly obsolete |
| QCLASS ANY | 3.2.5 | Compliant | ANY(255) implemented |
| RR format (NAME, TYPE, CLASS, TTL, RDLENGTH, RDATA) | 3.2.1 | Compliant | `DNSResourceRecord` stores all fields |
| A RDATA (4-octet IPv4) | 3.4.1 | Compliant | Factory method and accessor |
| CNAME RDATA (domain name) | 3.3.1 | Compliant | |
| NS RDATA (domain name) | 3.3.11 | Compliant | |
| PTR RDATA (domain name) | 3.3.12 | Compliant | |
| MX RDATA (preference + exchange) | 3.3.9 | Compliant | |
| TXT RDATA (character-strings) | 3.3.14 | Compliant | Proper 255-byte chunking |
| SOA RDATA (MNAME, RNAME, SERIAL, REFRESH, RETRY, EXPIRE, MINIMUM) | 3.3.13 | Compliant | |
| Label length limit (63 octets) | 2.3.4 | Compliant | Validated in `encodeName()` |
| Total name length limit (255 octets) | 2.3.4 | Compliant | Validated in `encodeName()` and `decodeName()` |
| Case-insensitive name comparison | 2.3.3 | Compliant | `DNSQuestion.equals()`, `DNSResourceRecord.equals()`, `CacheKey` |

#### Section 4 — Messages

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Header format (12 octets: ID, FLAGS, counts) | 4.1.1 | Compliant | All fields parsed/serialized correctly |
| QR flag (bit 15) | 4.1.1 | Compliant | `FLAG_QR = 0x8000` |
| OPCODE (bits 14-11) | 4.1.1 | Compliant | Extracted correctly via shift |
| AA, TC, RD, RA flags | 4.1.1 | Compliant | All defined and tested |
| Z bits MUST be zero | 4.1.1 | Compliant | Masked on parse (lenient), cleared on serialization |
| RCODE (bits 3-0) | 4.1.1 | Compliant | All standard codes defined |
| Question section format (QNAME, QTYPE, QCLASS) | 4.1.2 | Compliant | |
| Resource record format | 4.1.3 | Compliant | |
| Name compression (decoding) | 4.1.4 | Compliant | Pointer following with loop limit |
| Name compression (encoding) | 4.1.4 | Compliant | `serialize()` uses `writeNameCompressed()` with suffix-based compression table |
| UDP transport on port 53 | 4.2.1 | Compliant | `DNSListener`, `UDPDNSClientTransport` |
| UDP message size limit (512 octets) | 4.2.1 | Compliant | Server proxy uses 512-byte buffer |
| TCP 2-byte length prefix | 4.2.2 | Compliant | `DoTProtocolHandler`, `TCPDNSClientTransport` |
| TCP max message size (65535) | 4.2.2 | Compliant | |
| Unknown type handling | 4.1.3 | Compliant | RFC 3597: raw type/class values preserved in `DNSResourceRecord` |
| Unknown class handling | 4.1.3 | Compliant | RFC 3597: raw type/class values preserved in `DNSResourceRecord` |

#### Section 6 — Name Server Implementation

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Standard query processing | 6.2 | Compliant | `DNSService.processQuery()` handles standard queries |
| OPCODE validation | 6.2 | Compliant | Non-query opcodes return NOTIMP |
| RCODE responses | 6.2 | Compliant | FORMERR, SERVFAIL, NXDOMAIN used appropriately |

#### Section 7 — Resolver Implementation

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Transform user request into query | 7.1 | Compliant | `DNSResolver.query()` |
| Sending queries to multiple servers | 7.2 | Compliant | Timeout/retry across server list |
| Processing responses (ID matching) | 7.3 | Compliant | `handleResponse()` matches by ID |
| Using the cache (TTL-based) | 7.4 | Compliant | `DNSCache` with TTL expiry |
| RD flag set in queries | 4.1.1 | Compliant | Stub resolver sets `FLAG_RD` |
| Truncation → TCP retry (resolver) | 4.2.1 | Compliant | `retryOverTcpAsync()` |
| Truncation → TCP retry (server proxy) | 4.2.1 | Compliant | `DNSService.retryOverTcp()` retries upstream query over TCP when TC bit is set |

---

### RFC 5452 — Measures for Making DNS More Resilient against Forged Answers

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Verify response Message ID matches query | 3 | Compliant | `proxyToUpstream()` validates ID before accepting upstream response |

---

### RFC 2782 — A DNS RR for specifying the location of services (SRV)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| SRV record type (33) | All | Compliant | `DNSType.SRV`, `DNSResourceRecord.srv()` factory, accessors for priority/weight/port/target |
| SRV query support | All | Compliant | `DNSResolver.querySRV()` |

---

### RFC 7766 — DNS Transport over TCP

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Connection reuse for multiple queries | 6.2.1 | Compliant | `TCPDNSConnectionPool` maintains persistent connections per server |
| Idle connection timeout | 6.2.3 | Compliant | `setIdleTimeoutMs()` (default 30s) |
| Maximum connection lifetime | 7 | Compliant | `setMaxLifetimeMs()` (default 5 min) |

---

### RFC 7873 — Domain Name System (DNS) Cookies

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Client cookie (8 octets) in EDNS0 option 10 | 4, 5.1 | Compliant | `DNSCookie.getClientCookie()` — 8-byte random value included in queries |
| Server cookie generation via HMAC | 5.2 | Compliant | `DNSCookie.generateServerCookie()` — HMAC-SHA256 of client IP + client cookie |
| Server cookie validation | 5.2 | Compliant | `DNSCookie.validateServerCookie()` |
| Client caches server cookie per server | 5.1 | Compliant | `DNSCookie.processResponseCookie()` per-server cache |
| EDNS0 option parsing | 4 | Compliant | `DNSCookie.findEdnsOption()` |

---

### RFC 7858 — DNS over TLS (DoT)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Server MUST listen on port 853 | 3.1 | Compliant | `DoTListener` default port 853 |
| TLS MUST be the first data exchange | 3.1 | Compliant | `DoTListener` sets `secure = true` |
| Port 853 MUST NOT carry cleartext | 3.1 | Compliant | TLS is mandatory |
| MUST use 2-octet length field | 3.3 | Compliant | Length-prefixed framing in handler |
| SHOULD pipeline queries | 3.3 | Compliant | Multiple messages per connection |
| MUST match responses by Message ID | 3.3 | Compliant | Client resolver matches by ID |
| SHOULD reuse connections | 3.4 | Compliant | Persistent TCP connections |
| SHOULD NOT close immediately after response | 3.4 | Compliant | |
| MUST be robust to idle termination | 3.4 | Compliant | Disconnect handler present |
| SHOULD enable TLS session resumption | 3.4 | Compliant | `TCPTransportFactory.configureTlsSessionCache()` sets cache size and timeout on both server and client session contexts |
| SHOULD use TCP Fast Open for re-establishment | 3.4 | Compliant | `TCPTransportFactory.setTcpFastOpen()` enabled for DoT in `TCPDNSClientTransport` |
| Clients SHOULD use Strict usage profile | 4.2 | Compliant | `SPKIPinnedCertTrustManager` verifies SPKI SHA-256 hash; `TCPDNSClientTransport.setPinnedSPKIFingerprints()` |

---

### RFC 9250 — DNS over Dedicated QUIC Connections (DoQ)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| ALPN token "doq" | 4.1 | Compliant | Set in `QuicTransportFactory` |
| MUST listen on port 853 | 4.1.1 | Compliant | Default port 853 |
| MUST NOT use port 53 | 4.1.1 | Compliant | |
| One stream per query | 4.2 | Compliant | `DoQStreamHandler` per stream |
| MUST use 2-octet length prefix | 4.2 | Compliant | Length-prefixed framing in `DoQStreamHandler` and `DoQClientTransport` |
| Client MUST indicate STREAM FIN | 4.2 | Compliant | `stream.close()` after send |
| Server MUST indicate STREAM FIN | 4.2 | Compliant | `endpoint.close()` after response |
| Message ID MUST be 0 | 4.2.1 | Compliant | `DoQClientTransport.send()` rewrites ID to 0 |
| DoQ error codes | 4.3 | Compliant | `DoQStreamHandler` defines DOQ_NO_ERROR through DOQ_EXCESSIVE_LOAD; `resetWithError()` sends RESET_STREAM with appropriate code |
| SHOULD negotiate idle timeout | 4.4 | Partial | Relies on QUIC engine defaults |
| 0-RTT early data for QUERY/NOTIFY | 4.5 | Compliant | `QuicTransportFactory.setEarlyDataEnabled(true)` enables `quiche_config_enable_early_data`; `DoQClientTransport` caches session tickets per server |
| MUST use padding | 5.4 | Compliant | EDNS(0) padding (RFC 7830) with 128-byte block alignment |
| Client SHOULD reuse connections | 5.5.1 | Compliant | `DoQConnectionPool` maintains per-server persistent QUIC connections; queries use new streams on shared connections |
| SHOULD process queries in parallel | 5.6 | Compliant | Separate streams |

---

### RFC 8484 — DNS Queries over HTTPS (DoH)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| HTTP POST with `application/dns-message` | 4.1 | Compliant | `DoHClientTransport.send()` sends POST to configured path with correct Content-Type and Accept headers |
| Accept: `application/dns-message` | 4.1 | Compliant | Set on every request |
| URI template path | 4.1 | Compliant | Default `/dns-query`, configurable via `setPath()` |
| DNS wire format in request/response body | 4.1 | Compliant | Raw DNS message bytes in POST body; response accumulated and delivered via `onReceive()` |
| HTTPS (port 443) | 5.1 | Compliant | Default port 443 with TLS enabled |

---

### RFC 2308 — Negative Caching of DNS Queries

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Cache NXDOMAIN responses | 3 | Compliant | `DNSCache.cacheNegative()` |
| Negative TTL from SOA MINIMUM | 5 | Compliant | `min(SOA.TTL, SOA.MINIMUM)` with configurable fallback |
| Discard expired negative entries | 5 | Compliant | TTL-based expiry |

---

### RFC 6891 — Extension Mechanisms for DNS (EDNS0)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| OPT pseudo-RR type (41) | 6.1.1 | Compliant | `DNSResourceRecord.opt()` factory, `getUdpPayloadSize()` accessor |
| EDNS0 in queries | 6.1.1 | Compliant | `DNSResolver.query()` and `DNSService.proxyToUpstream()` include OPT record |
| EDNS0 UDP payload size | 6.2.3 | Compliant | `DNSMessage.DEFAULT_EDNS_UDP_SIZE` (4096), upstream buffer sized accordingly |
| DO bit (DNSSEC OK) | 6.1.3 | Compliant | `DNSResourceRecord.EDNS_FLAG_DO`, `opt(size, flags, data)` factory, `getEDNSFlags()` |

---

### RFC 4033 — DNS Security Introduction and Requirements

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Trust anchor management | 5 | Compliant | `DNSSECTrustAnchor` with IANA root KSK DS records and custom anchors |
| Validation states (secure/insecure/bogus/indeterminate) | 5 | Compliant | `DNSSECStatus` enum |

---

### RFC 4034 — Resource Records for the DNS Security Extensions

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| DNSKEY record type (48) | 2 | Compliant | `DNSType.DNSKEY`, RDATA accessors for flags/protocol/algorithm/public key |
| RRSIG record type (46) | 3 | Compliant | `DNSType.RRSIG`, full RDATA accessors for all fields |
| NSEC record type (47) | 4 | Compliant | `DNSType.NSEC`, next domain name and type bit map parsing |
| DS record type (43) | 5 | Compliant | `DNSType.DS`, key tag/algorithm/digest type/digest accessors |
| Key tag computation | Appendix B | Compliant | `DNSResourceRecord.computeKeyTag()` running sum algorithm |
| Canonical DNS name ordering | 6.1 | Compliant | `DNSSECValidator.compareCanonical()` label-by-label from root |
| Canonical RRset form | 6.3 | Compliant | `DNSSECValidator.buildCanonicalRRset()` — lowercase, original TTL, sorted |
| RRSIG verification | 3.1.8.1 | Compliant | `DNSSECValidator.verifyRRSIG()` — signed data = RRSIG header + canonical RRset |
| DS verification | 5.2 | Compliant | `DNSSECValidator.verifyDS()` — hash(owner name + DNSKEY RDATA) |
| Type bit map encoding | 4.1.2 | Compliant | `DNSResourceRecord.parseTypeBitMaps()` — window block + bitmap |

---

### RFC 4035 — Protocol Modifications for the DNS Security Extensions

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| DO bit in EDNS0 | 3.2.1 | Compliant | Set in resolver and server upstream queries when DNSSEC enabled |
| AD flag (Authenticated Data) | 3.2.3 | Compliant | `DNSMessage.FLAG_AD`, `isAuthenticatedData()` |
| CD flag (Checking Disabled) | 3.2.2 | Compliant | `DNSMessage.FLAG_CD`, `isCheckingDisabled()` |
| RRSIG signature validation | 5.3 | Compliant | RSA-SHA256/512, ECDSA P-256/P-384, Ed25519, Ed448 via JCA |
| Chain of trust validation | 5.3.1 | Compliant | `DNSSECChainValidator` — async DNSKEY/DS fetching to trust anchor |
| NSEC denial-of-existence | 5.4 | Compliant | `DNSSECValidator.verifyNSEC()` — name-between and type absence |
| Strip DNSSEC records when DO not set | 3.2.1 | Compliant | `DNSService.stripDNSSECRecords()` |

---

### RFC 5155 — DNSSEC Hashed Authenticated Denial of Existence

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| NSEC3 record type (50) | 3 | Compliant | `DNSType.NSEC3`, full RDATA accessors |
| NSEC3PARAM record type (51) | 4 | Compliant | `DNSType.NSEC3PARAM`, RDATA accessors |
| NSEC3 hash computation | 5 | Compliant | `DNSSECValidator.nsec3Hash()` — iterated SHA-1 with salt |
| NSEC3 denial-of-existence | 8 | Compliant | `DNSSECValidator.verifyNSEC3()` — hash comparison |
| Base32hex encoding | 3.3 | Compliant | `DNSSECValidator.base32HexEncode()` (RFC 4648 section 7) |

---

## FTP

### Applicable RFCs

| RFC | Title | Status |
|-----|-------|--------|
| RFC 959 | File Transfer Protocol | Core |
| RFC 4217 | Securing FTP with TLS | Implemented |
| RFC 2428 | FTP Extensions for IPv6 and NATs | Implemented |
| RFC 2389 | Feature Negotiation for the FTP | Implemented (FEAT) |
| RFC 3659 | Extensions to FTP | Implemented |
| RFC 2640 | Internationalization of FTP | Implemented (UTF-8) |

---

### RFC 959 — File Transfer Protocol

#### Section 3 — Data Types, Structures, and Modes

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| TYPE A (ASCII, default) MUST be accepted | 3.1.1.1 | Compliant | Default type in `FTPConnectionMetadata` |
| TYPE I (Image/Binary) SHOULD be accepted | 3.1.1.3 | Compliant | Mapped to `BINARY` enum |
| TYPE E (EBCDIC) | 3.1.1.2 | Accepted | Type set in metadata but no actual EBCDIC conversion |
| TYPE L requires mandatory byte-size parameter | 3.1.1.4 | Compliant | `doType()` parses byte-size, stores in `FTPConnectionMetadata.localByteSize`; 501 if missing |
| Format control parameter (N/T/C) for ASCII/EBCDIC | 3.1.1.5 | Partial | Not parsed; NON-PRINT assumed — acceptable as it is the default |
| STRU F (File, default) MUST be accepted | 3.1.2.1 | Compliant | Default in `doStru()` |
| STRU R (Record) MUST be accepted for text types | 3.1.2.2 | Compliant | Accepted (200) when transfer type is ASCII or EBCDIC; 504 for IMAGE/LOCAL |
| STRU P (Page) — optional | 3.1.2.3 | Not implemented | 504 correct for optional feature |
| MODE S (Stream, default) MUST be accepted | 3.4.1 | Compliant | Default in `FTPConnectionMetadata` |
| MODE B (Block) — optional | 3.4.2 | Not implemented | 504 correct |
| MODE C (Compressed) — optional | 3.4.3 | Not implemented | 504 correct |
| ASCII transfer CRLF line endings | 3.1.1.1 | Compliant | `convertToNetworkFormat()` in `FTPDataConnectionCoordinator` |
| Stream mode EOF by closing connection | 3.4.1 | Compliant | Data connection closed after transfer |

#### Section 4.1.1 — Access Control Commands

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| USER command | 4.1.1 | Compliant | `doUser()` delegates to `FTPConnectionHandler` |
| PASS command | 4.1.1 | Compliant | `doPass()` requires prior USER |
| ACCT command | 4.1.1 | Compliant | `doAcct()` supports 3-stage authentication |
| CWD command | 4.1.1 | Compliant | `doCwd()` with authorization check |
| CDUP command | 4.1.1 | Compliant | `doCdup()` delegates to CWD ".." |
| SMNT command (optional) | 4.1.1 | Not implemented | 502 correct for optional command |
| REIN command | 4.1.1 | Compliant | Resets all session state and replies 220 |
| QUIT command | 4.1.1 | Compliant | Sends 221 and closes |
| Commands case-insensitive | 5.4 | Compliant | `lineRead()` uppercases before dispatch |

#### Section 4.1.2 — Transfer Parameter Commands

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| PORT command (h1,h2,h3,h4,p1,p2) | 4.1.2 | Compliant | Parses 6-field address/port |
| PASV command | 4.1.2 | Compliant | Opens server socket, returns 227 (h1,h2,h3,h4,p1,p2) |
| TYPE command | 4.1.2 | Compliant | A, I, E, L all handled; TYPE L byte-size parsed |
| STRU command | 4.1.2 | Compliant | F always accepted; R accepted for text types (ASCII/EBCDIC) |
| MODE command | 4.1.2 | Compliant | Stream only; Block/Compressed correctly return 504 |

#### Section 4.1.3 — FTP Service Commands

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| RETR command | 4.1.3 | Compliant | Async download with restart offset support |
| STOR command | 4.1.3 | Compliant | Async upload with quota checking |
| STOU command | 4.1.3 | Compliant | Generates unique filename via `FTPFileSystem` |
| APPE command | 4.1.3 | Compliant | Append mode supported |
| ALLO command | 4.1.3 | Compliant | Delegates to `FTPFileSystem.allocateSpace()` |
| REST command | 4.1.3 | Compliant | Sets restart offset for next RETR |
| RNFR/RNTO sequence | 4.1.3 | Compliant | Validates source exists before accepting rename |
| ABOR command | 4.1.3 | Compliant | Sends 426 then 226 when transfer in progress; 226 only otherwise |
| DELE command | 4.1.3 | Compliant | |
| RMD command | 4.1.3 | Compliant | |
| MKD command | 4.1.3 | Compliant | Replies 257 with pathname |
| PWD command | 4.1.3 | Compliant | Replies 257 with quoted pathname |
| LIST command | 4.1.3 | Compliant | Unix ls -l format via `FTPFileInfo.formatAsListingLine()` |
| NLST command | 4.1.3 | Compliant | Returns file names only (one per line) via `TransferType.NAME_LIST` |
| SITE command | 4.1.3 | Compliant | QUOTA and SETQUOTA sub-commands implemented |
| SYST command | 4.1.3 | Compliant | Returns system type |
| STAT command (no args) | 4.1.3 | Compliant | Returns server status |
| STAT command (with directory) | 4.1.3 | Compliant | Directory listing over control connection (no data connection) |
| STAT command (with file) | 4.1.3 | Compliant | Returns file status |
| HELP command | 4.1.3 | Compliant | |
| NOOP command | 4.1.3 | Compliant | |

#### Section 4.2 — FTP Replies

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Reply format "code SP text CRLF" | 4.2 | Compliant | `reply()` method |
| Multi-line format "code-text" | 4.2 | Compliant | `replyMultiLine()` method |
| All reply codes per spec | 4.2 | Compliant | 150, 200, 220, 221, 226, 227, 230, 250, 257, 331, 350, 421, 425, 500–553 |

#### Section 3.2 — Data Connection Management

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Active mode (server connects to client) | 3.2 | Compliant | `FTPDataConnectionCoordinator.establishActiveConnection()` |
| Passive mode (server listens) | 3.2 | Compliant | `FTPDataConnectionCoordinator.setupPassiveMode()` |
| Connection opened per transfer | 3.3 | Compliant | `cleanup()` called after each transfer |
| Default port 21 | 5.2 | Compliant | `FTPListener.FTP_DEFAULT_PORT` |

---

### RFC 4217 — Securing FTP with TLS

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| AUTH TLS command | 4 | Compliant | `doAuth()` initiates TLS handshake |
| AUTH re-negotiation | 4 | Compliant | No re-AUTH guard; `isSecure()` check prevents double-TLS |
| AUTH resets data protection state | 4 | Compliant | Clears `pbszSet` and `dataProtection` |
| PBSZ 0 for TLS | 8 | Compliant | Always responds PBSZ=0 |
| PROT C (Clear) | 9 | Compliant | Disables data channel TLS |
| PROT P (Private) | 9 | Compliant | Enables data channel TLS |
| PROT S/E not required | 9 | Compliant | Returns 536 |
| CCC — server MAY refuse | 6 | Compliant | Returns 533 |
| PBSZ required before PROT | 9 | Compliant | `doProt()` checks `pbszSet` flag |
| AUTH requires TLS availability | 4 | Compliant | Checks `server.isSTARTTLSAvailable()` |
| Certificate-based authentication | 10 | Compliant | `securityEstablished()` uses `SASLUtils.authenticateExternal()` |
| Data connection IP verification | 10 | Compliant | `acceptDataConnection()` rejects connections whose source IP differs from the control connection |
| Implicit FTPS on port 990 | — | Compliant | `FTPListener.getPort()` returns 990 when `secure=true` and no explicit port set |

---

### RFC 2428 — FTP Extensions for IPv6 and NATs

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| EPRT command format | 2 | Compliant | Parses delimiter, net-prt, address, port |
| EPRT IPv4 (net-prt=1) | 2 | Compliant | Validates `Inet4Address` |
| EPRT IPv6 (net-prt=2) | 2 | Compliant | Validates `Inet6Address` |
| EPRT protocol mismatch check | 2 | Compliant | Returns 522 if address family doesn't match |
| EPSV command | 3 | Compliant | Response format `229 (|||port|)` |
| EPSV with net-prt argument | 3 | Compliant | Validates net-prt 1 or 2 |
| EPSV ALL | 4 | Compliant | Sets `epsvAllMode`, rejects PORT/PASV/EPRT |

---

### RFC 2389 — Feature Negotiation

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| FEAT response format (multi-line 211) | 3.2 | Compliant | Proper `211-`/`211 ` format |
| MUST NOT advertise unsupported features | 3 | Compliant | Only advertises implemented features |
| Feature lines space-prefixed | 3.2 | Compliant | Each feature line starts with space |

---

### RFC 3659 — Extensions to FTP

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| REST STREAM | 5 | Compliant | `doRest()` stores byte offset for next RETR |
| TVFS (Trivial Virtual File Store) | 6 | Compliant | `BasicFTPFileSystem` uses "/" convention |
| SIZE command | 4 | Compliant | `doSize()` returns 213 with file size; rejects directories |
| MDTM command | 3 | Compliant | `doMdtm()` returns 213 with `YYYYMMDDhhmmss` UTC timestamp |
| MLST command | 7 | Compliant | Single entry over control connection; facts: size, modify, type, perm |
| MLSD command | 7 | Compliant | Directory listing over data connection via `TransferType.MACHINE_LISTING` |
| MLST fact advertisement in FEAT | 7.8 | Compliant | `MLST size*;modify*;type*;perm*;` advertised in FEAT response |
| Time values in UTC | 2.3 | Compliant | `DateTimeFormatter` with `ZoneOffset.UTC` |

### RFC 2640 — Internationalization of FTP

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| UTF-8 support via OPTS | 4 | Compliant | `OPTS UTF8 ON` switches control connection encoding to UTF-8 |
| UTF8 advertised in FEAT | 3 | Compliant | `UTF8` listed in FEAT response |

---

## HTTP/1.1

### Applicable RFCs

| RFC | Title | Status |
|-----|-------|--------|
| RFC 9112 | HTTP/1.1 (Message Syntax and Routing) | Core |
| RFC 9110 | HTTP Semantics | Core |
| RFC 7617 | HTTP Basic Authentication | Implemented |
| RFC 7616 | HTTP Digest Authentication | Implemented |
| RFC 6750 | OAuth 2.0 Bearer Token Usage | Implemented |
| RFC 6455 | The WebSocket Protocol | Implemented (upgrade) |

---

### RFC 9112 — HTTP/1.1 Message Syntax and Routing

#### Section 2-3 — Message Format and Request Line

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Request-line format: method SP request-target SP HTTP-version | 3 | Compliant | `processRequestLine()` in `HTTPProtocolHandler` |
| US-ASCII decoding of request-line | 2.1 | Compliant | `US_ASCII_DECODER` used |
| 414 URI Too Long for oversized request-target | 3 | Compliant | `MAX_LINE_LENGTH` check |
| 400 Bad Request for malformed request-line | 3 | Compliant | Multiple validation checks |
| 501 Not Implemented for unrecognised method | 3 | Compliant | `isMethodSupported()` |
| 505 HTTP Version Not Supported | 3 | Compliant | `HTTPVersion.UNKNOWN` case |
| HTTP-version parsing | 2.3 | Compliant | `HTTPVersion.fromString()` |

#### Section 5 — Field Syntax

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| field-line = field-name ":" OWS field-value OWS | 5.1 | Compliant | `processHeaderLine()` |
| No whitespace between field-name and colon | 5.1 | Compliant | `Header` constructor validates via `HTTPUtils.isValidHeaderName()` |
| obs-fold handling: reject or replace with SP | 5.2 | Compliant | Replaces obs-fold with SP (replaces leading HTAB with SP) |
| ISO-8859-1 decoding for field values | 5.5 | Compliant | `ISO_8859_1_DECODER` used |
| 431 Request Header Fields Too Large | 5 | Compliant | `MAX_LINE_LENGTH` check |
| Host header MUST be present in HTTP/1.1 | 3.2 | Compliant | `endHeaders()` validates |
| Duplicate Host header MUST be rejected (400) | 3.2 | Compliant | `endHeaders()` checks `hostCount != 1` |
| Case-insensitive header name lookup | 5.1 | Compliant | `Headers.getValue()` uses `equalsIgnoreCase()` |
| Token character validation | 5.6.2 | Compliant | `HTTPUtils.TOKEN_CHARS` lookup table (updated to RFC 9110) |

#### Section 6-7 — Message Body and Transfer Coding

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Body length precedence: Transfer-Encoding > Content-Length > close | 6.3 | Compliant | `endHeaders()` follows correct precedence |
| Chunked encoding format | 7.1 | Compliant | `processChunkSizeLine()`, `receiveChunkedData()` |
| Chunk extensions (semicolon) | 7.1.1 | Compliant | Parsed and ignored after semicolon |
| Last chunk (0 CRLF) + trailer section | 7.1, 7.1.2 | Compliant | `processTrailerLine()` handles |
| Content-Length delimited body | 6.2 | Compliant | `receiveBody()` |
| Read-until-close for HTTP/1.0 | 6.3 | Compliant | `receiveBodyUntilClose()` |
| 411 Length Required | 6.3 | Compliant | Sent when body present but no framing in HTTP/1.1 |
| Reject requests with both Transfer-Encoding and Content-Length | 6.3 | Compliant | `streamEndHeaders()` detects both TE and CL present and sends 400 Bad Request |

#### Section 4 — Status Line

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| status-line = HTTP-version SP status-code SP reason-phrase CRLF | 4 | Compliant | `writeStatusLineAndHeaders()` |
| Reason phrases per RFC 9110 | 4 | Compliant | `HTTPConstants.messages` (updated to RFC 9110 section 15) |

#### Section 9 — Connection Management

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Persistent connections default for HTTP/1.1 | 9.3 | Compliant | Default behaviour |
| Connection: close ends persistence | 9.6 | Compliant | `closeConnection` flag in `Stream` |
| HTTP/1.0 defaults to close | 9.3 | Compliant | Set in `processRequestLine()` |
| Pipelining: respond in order | 9.3.2 | Compliant | Sequential state machine ensures order |
| Connection: close echoed in response | 9.6 | Compliant | Added in `sendResponseHeaders()` |
| Graceful shutdown via Connection: close | 9.6 | Compliant | `maxRequestsPerConnection` triggers `closeConnection` after configured limit |
| Idle connection timeout | 9.8 | Compliant | Configurable `idleTimeoutMs` in `HTTPListener`; timer resets on each `receive()` |

---

### RFC 9110 — HTTP Semantics

#### Section 5 — Fields

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| field-name is case-insensitive | 5.1 | Compliant | `Headers` class uses `equalsIgnoreCase()` |
| token = 1*tchar | 5.6.2 | Compliant | `HTTPUtils.TOKEN_CHARS` (updated reference from RFC 7230) |
| field-value validation | 5.5 | Compliant | `HTTPUtils.HEADER_VALUE_CHARS` |

#### Section 6.6 — Date and Server

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Date header SHOULD be sent in responses | 6.6.1 | Compliant | Added in `Stream.sendResponseHeaders()` |
| Server header | 10.2.4 | Compliant | "gumdrop/VERSION" added in responses |
| IMF-fixdate format for Date header | 5.6.7 | Compliant | `HTTPDateFormat` outputs "GMT" (fixed from numeric offset) |
| Parse all three date formats | 5.6.7 | Compliant | IMF-fixdate, RFC 850, asctime all parsed |

#### Section 9 — Methods

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Method = token | 9.1 | Compliant | `HTTPUtils.isValidMethod()` |
| GET (safe, idempotent) | 9.3.1 | Compliant | |
| HEAD: response MUST NOT contain a body | 9.3.2 | Compliant | `sendResponseBody()` suppresses body data when method is HEAD |
| POST | 9.3.3 | Compliant | |
| PUT (idempotent) | 9.3.4 | Compliant | |
| DELETE (idempotent) | 9.3.5 | Compliant | |
| CONNECT (tunnel) | 9.3.6 | Compliant | Method supported |
| OPTIONS * targets server itself | 9.3.7 | Compliant | `handleOptionsAsterisk()` returns 200 with `Allow` header listing supported methods |
| OPTIONS for resources | 9.3.7 | Compliant | Delegated to handler |
| TRACE: client MUST NOT send content | 9.3.8 | Compliant | Added to `isNoBodyMethod()` |
| TRACE: echo request as message/http | 9.3.8 | Compliant | `handleTrace()` echoes request with Content-Type `message/http`; disabled by default for security |
| 501 for unrecognised methods | 15.6.2 | Compliant | |

#### Section 10 — Request Header Fields

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Expect: 100-continue | 10.1.1 | Compliant | `streamEndHeaders()` sends `100 Continue` interim response when Expect header present |

#### Section 8.6 — Content-Length

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Content-Length SHOULD be sent when known | 8.6 | Compliant | `sendResponseHeaders()` logs FINE warning when response lacks both Content-Length and Transfer-Encoding |

#### Section 11 — Authentication

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| 401 MUST include WWW-Authenticate | 11.6.1 | Compliant | `HTTPAuthenticationProvider.generateChallenge()` produces challenge |
| Basic authentication | 11.7.1 | Compliant | RFC 7617 |
| Digest authentication | 11.7.1 | Compliant | RFC 7616 |
| Bearer authentication | 11.7.1 | Compliant | RFC 6750 |

#### Section 7.8 — Upgrade

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Upgrade header field | 7.8 | Compliant | Parsed in `Stream.streamEndHeaders()` |
| 101 Switching Protocols | 15.2.2 | Compliant | Sent for h2c and WebSocket upgrades |
| h2c upgrade | 7.8 | Compliant | `completeH2cUpgrade()` in `HTTPProtocolHandler` |
| WebSocket upgrade (RFC 6455) | 7.8 | Compliant | `Stream.upgradeToWebSocket()` |

#### Section 15 — Status Codes

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| All standard status codes defined | 15 | Compliant | `HTTPStatus` enum covers all RFC 9110 codes |
| Reason phrases match RFC 9110 | 15 | Compliant | `HTTPConstants` updated (413, 414, 416, 422 reason phrases corrected) |
| 103 Early Hints (RFC 8297) | 15.2 | Compliant | `HTTPResponseState.sendInformational()` sends 1xx responses before the final response; implemented for HTTP/1.1, HTTP/2, and HTTP/3; 1xx headers skip Server/Date/Connection per RFC 9110 section 15.2; HTTP/1.0 silently no-ops |
| 418 I'm a Teapot | 15.5.19 | Compliant | |

---

## HTTP/2 — RFC 9113 (obsoletes RFC 7540)

### HPACK — RFC 7541

#### Section 3 — Starting HTTP/2

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| ALPN negotiation with "h2" | 3.2 | Compliant | `HTTPListener.configureTransportFactory()` sets ALPN |
| h2c cleartext upgrade | 3.1 | Compliant | `completeH2cUpgrade()` — deprecated by RFC 9113, intentionally retained |
| Prior knowledge (cleartext) | 3.3 | Compliant | PRI preface parsed in `processRequestLine()` |
| Client connection preface (24-octet magic) | 3.4 | Compliant | Validated in `receivePri()` and `receiveFrameData()` |
| Server connection preface (SETTINGS) | 3.4 | Compliant | Sent in `securityEstablished()` and `receivePri()` |

#### Section 4 — Frame Format

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| 9-octet frame header: Length(24) Type(8) Flags(8) R(1) StreamId(31) | 4.1 | Compliant | `H2Parser.receive()`, `H2Writer.writeFrameHeader()` |
| Frame size limits (SETTINGS_MAX_FRAME_SIZE) | 4.2 | Compliant | Validated in `H2Parser`; default 16,384; range 2^14 to 2^24-1 |
| Unknown frame types MUST be ignored | 4.1 | Compliant | Default case in `H2Parser.dispatchFrame()` |
| Field block fragmentation (HEADERS + CONTINUATION) | 4.3 | Compliant | `sendResponseHeaders()` fragments; parser tracks `continuationExpectedStream` |

#### Section 5 — Streams and Multiplexing

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Stream states: idle, open, half-closed, closed, reserved | 5.1 | Compliant | `Stream.State` enum |
| Client-initiated streams use odd IDs | 5.1.1 | Compliant | Validated in `headersFrameReceived()` |
| Server-initiated streams use even IDs | 5.1.1 | Compliant | `getNextServerStreamId()` increments by 2 |
| Stream IDs monotonically increasing | 5.1.1 | Compliant | `lastClientStreamId` check in `headersFrameReceived()` |
| SETTINGS_MAX_CONCURRENT_STREAMS | 5.1.2 | Compliant | Enforced; excess streams get RST_STREAM REFUSED_STREAM |
| Flow control: connection and stream windows | 5.2 | Compliant | `H2FlowControl` tracks both levels |
| Default initial window size 65,535 | 5.2.1 | Compliant | `H2FlowControl.DEFAULT_INITIAL_WINDOW_SIZE` |
| WINDOW_UPDATE increments | 5.2.2 | Compliant | `H2FlowControl.onDataReceived()` / `onWindowUpdate()` |
| Priority signaling deprecated | 5.3 | Compliant | `StreamPriorityTree` and `StreamPriorityScheduler` annotated `@Deprecated`; not wired into handler |

#### Section 5.4 and 7 — Error Handling

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Connection errors → GOAWAY | 5.4.1 | Compliant | `sendGoaway()` sends GOAWAY and closes |
| Graceful two-phase GOAWAY shutdown | 5.4.1 | Compliant | `sendGracefulGoaway()` sends GOAWAY with MAX_VALUE last-stream-ID, then final GOAWAY after 1s delay |
| Stream errors → RST_STREAM | 5.4.2 | Compliant | `sendRstStream()` |
| All 14 error codes (0x0–0xd) defined | 7 | Compliant | `H2FrameHandler` constants |

#### Section 6 — Frame Definitions

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| DATA frame parsing and writing | 6.1 | Compliant | `H2Parser.parseDataFrame()`, `H2Writer.writeData()` |
| HEADERS frame parsing and writing | 6.2 | Compliant | Includes PADDED and PRIORITY flags |
| PRIORITY frame parsing | 6.3 | Compliant | Parsed for wire compat; semantics deprecated |
| RST_STREAM frame parsing and writing | 6.4 | Compliant | 4-octet error code |
| SETTINGS frame parsing, validation, ACK | 6.5 | Compliant | All 6 parameters handled; ACK/empty payload validated |
| SETTINGS_ENABLE_PUSH validated (0 or 1) | 6.5.2 | Compliant | `H2Parser.parseSettingsFrame()` |
| SETTINGS_MAX_FRAME_SIZE validated (2^14 to 2^24-1) | 6.5.2 | Compliant | Range check in parser |
| SETTINGS_INITIAL_WINDOW_SIZE validated (≤ 2^31-1) | 6.5.2 | Compliant | Overflow check in parser |
| SETTINGS ACK timeout enforcement | 6.5.3 | Compliant | 5s timer via `startSettingsTimeout()`; fires GOAWAY SETTINGS_TIMEOUT |
| PUSH_PROMISE frame parsing and writing | 6.6 | Compliant | |
| PING frame parsing and ACK | 6.7 | Compliant | 8-octet opaque data; ACK response |
| PING keep-alive (server-initiated) | 6.7 | Compliant | Configurable `pingIntervalMs`; periodic non-ACK PINGs sent on idle HTTP/2 connections |
| GOAWAY frame parsing and writing | 6.8 | Compliant | Last-stream-ID + error code; `sendGoaway(int, String)` overload includes UTF-8 debug data |
| WINDOW_UPDATE frame parsing (increment ≠ 0) | 6.9 | Compliant | Zero increment → PROTOCOL_ERROR |
| WINDOW_UPDATE overflow → FLOW_CONTROL_ERROR | 6.9.1 | Compliant | `H2FlowControl.onWindowUpdate()` |
| SETTINGS_INITIAL_WINDOW_SIZE adjusts all streams | 6.9.2 | Compliant | `H2FlowControl.onSettingsInitialWindowSize()` |
| CONTINUATION frame parsing | 6.10 | Compliant | Locked to same stream; `continuationExpectedStream` |

#### RFC 7541 — HPACK

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Static table (61 entries) | Appendix A | Compliant | `HPACKConstants.STATIC_TABLE` |
| Dynamic table management | 4 | Compliant | Encoder/Decoder maintain per-connection dynamic tables |
| Entry size = name_len + value_len + 32 | 4.1 | Compliant | `HPACKConstants.headerSize()` |
| Dynamic table size update | 6.3 | Compliant | Decoder handles 0x20 opcode |
| Integer representation | 5.1 | Compliant | `encodeInteger()` / `decodeInteger()` |
| String literal / Huffman encoding | 5.2 | Compliant | `Huffman.encode()` / `Huffman.decode()` |
| Indexed header field (0x80) | 6.1 | Compliant | |
| Literal with incremental indexing (0x40) | 6.2.1 | Compliant | |
| Literal without indexing (0x00) | 6.2.2 | Compliant | |
| Literal never indexed (0x10) | 6.2.3 | Compliant | |

#### Section 8 — HTTP Message Semantics

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Pseudo-headers before regular headers | 8.3 | Compliant | `validateH2Headers()` |
| Each pseudo-header at most once | 8.3 | Compliant | `seenPseudo` set check |
| :method, :scheme, :path required (non-CONNECT) | 8.3.1 | Compliant | Validated in `validateH2Headers()` |
| CONNECT needs only :method | 8.3.1 | Compliant | Special case in `validateH2Headers()` |
| :status response pseudo-header | 8.3.2 | Compliant | Added in `sendResponseHeaders()` |
| Connection-specific headers MUST NOT appear | 8.2.2 | Compliant | Stripped in `sendResponseHeaders()`; rejected in `validateH2Headers()` |
| Transfer-Encoding MUST NOT appear | 8.2.2 | Compliant | Rejected in `validateH2Headers()` |
| TE only with "trailers" value | 8.2.2 | Compliant | Validated in `validateH2Headers()` |
| Server push via PUSH_PROMISE | 8.4 | Compliant | `Stream.pushPromise()` + `sendPushPromise()` both gated by `isEnablePush()` |

#### Section 9 — Connection Management and TLS

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| TLS 1.2+ required for h2 | 9.2 | Compliant | `TCPTransportFactory.SECURE_PROTOCOLS = { "TLSv1.2", "TLSv1.3" }` |
| TLS 1.3 RECOMMENDED | 9.2 | Compliant | Included in SECURE_PROTOCOLS |
| TLS 1.2 cipher suite blocklist (server) | 9.2.2 | Compliant | `isBlockedH2CipherSuite()` in `securityEstablished()`; GOAWAY INADEQUATE_SECURITY |
| ALPN configured in HTTPListener | 3.2 | Compliant | `setApplicationProtocols("h2", "http/1.1")` |
| Idle connection timeout (server) | 9.1 | Compliant | Graceful GOAWAY for HTTP/2; configurable via `idleTimeoutMs` |

## HTTP/2 Client — RFC 9113

### HTTP/1.1 Client — RFC 9112, RFC 9110

#### Request Sending — RFC 9112 sections 3 and 7

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Request-line format: method SP request-target SP HTTP-version | 9112 s3.1 | Compliant | `sendHTTP11Request()` |
| Host header required | 9112 s3.2 / 9110 s7.2 | Compliant | Always emitted with port normalization |
| Chunked transfer coding for request body | 9112 s7.1 | Compliant | `sendHTTP11Data()` / `endHTTP11Data()` |
| Final zero-length chunk terminates body | 9112 s7.1 | Compliant | `endHTTP11Data()` sends `0\r\n\r\n` |
| Connection: keep-alive header | 9112 s9.6 | Compliant | Sent when no explicit Connection header |

#### Response Parsing — RFC 9112 sections 4-7

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Status-line parsing | 9112 s4 | Compliant | `parseStatusLine()` |
| Header field parsing (CRLF-terminated) | 9112 s5 | Compliant | `parseHeaders()` |
| obs-fold handling (continuation lines) | 9112 s5.2 | Compliant | Lines starting with SP/HTAB appended to previous header value |
| Content-Length body framing | 9112 s6.3 | Compliant | `parseBody()` |
| Read-until-close when no Content-Length | 9112 s6.3 | Compliant | `parseBody()` with `contentLength == -1` |
| Transfer-Encoding priority over Content-Length | 9112 s6.3 | Compliant | Chunked preferred when both present |
| Content-Length vs Transfer-Encoding conflict | 9112 s6.2 | Compliant | Logs warning and ignores Content-Length when both present (client); server rejects with 400 |
| Chunked transfer coding (receiving) | 9112 s7.1 | Compliant | `parseChunkSize()` / `parseChunkData()` |
| Chunk extensions stripped | 9112 s7.1.1 | Compliant | Semicolon and after removed |
| Trailer section parsing | 9112 s7.1.2 | Compliant | `parseChunkTrailer()` delivers to handler |

#### HTTP Semantics — RFC 9110

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| HEAD response has no body | 9110 s9.3.2 | Compliant | Detected via method; `completeResponse()` called |
| 204 No Content has no body | 9110 s15.3.5 | Compliant | |
| 304 Not Modified has no body | 9110 s15.4.5 | Compliant | |
| 1xx informational responses | 9110 s15.2 | Compliant | `parseHeaders()` detects 1xx status, discards headers, and resets to STATUS_LINE for the final response |
| 101 Switching Protocols (h2c, WebSocket) | 9110 s15.2.2 | Compliant | `handleProtocolSwitch()` hook |
| Content-Length validation (multiple values, negative) | 9110 s8.6 | Compliant | `validateContentLength()` rejects differing multiples and negatives |
| 401 + WWW-Authenticate triggers auth retry | 9110 s11.6.1 | Compliant | `attemptAuthentication()` via `startBodyDiscard()` — response body (including chunked) fully consumed before retry |
| 407 + Proxy-Authenticate triggers auth retry | 9110 s11.7.1 | Compliant | Parallel code path using `Proxy-Authorization` header |
| Max response header size protection | 9112 s5 | Compliant | Configurable `maxResponseHeaderSize` (default 1 MB); connection closed when exceeded |

#### Connection Management — RFC 9112 section 9

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Persistent connections default for HTTP/1.1 | 9112 s9.3 | Compliant | `Connection: keep-alive` sent |
| Response `Connection: close` handling | 9112 s9.6 | Compliant | `completeResponse()` detects `Connection: close` and closes the connection |

#### Authentication — RFC 7617, RFC 7616

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| HTTP Basic Authentication | RFC 7617 | Compliant | `computeBasicAuth()` |
| HTTP Digest Authentication (MD5, MD5-sess) | RFC 7616 | Compliant | `computeDigestAuth()` |
| Digest SHA-256/SHA-256-sess | RFC 7616 s3.4 | Compliant | `MessageDigest` maps algorithm name; `userhash` parameter supported |
| 407 Proxy-Authenticate | RFC 9110 s11.7.1 | Compliant | `Proxy-Authorization` sent for 407 responses |

### HTTP/2 Client Connection Startup — RFC 9113 section 3

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| ALPN "h2" negotiation | 3.2 | Compliant | `securityEstablished()` checks ALPN |
| h2c cleartext upgrade (deprecated, intentionally retained) | 3.1 | Compliant | `sendHTTP11Request()` sends Upgrade headers; `completeH2cUpgrade()` handles 101 |
| Prior knowledge | 3.3 | Compliant | `connected()` sends preface when `h2WithPriorKnowledge` |
| Client connection preface (24-octet magic + SETTINGS) | 3.4 | Compliant | `sendConnectionPreface()` |
| HTTP2-Settings header (Base64url SETTINGS payload) | 3.1 | Compliant | `createHTTP2SettingsHeaderValue()` |

### HTTP/2 Client Frame Handling — RFC 9113 section 6

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| DATA frame reception with flow control | 6.1, 6.9 | Compliant | `dataFrameReceived()` + WINDOW_UPDATE |
| HEADERS + CONTINUATION reassembly | 6.2, 6.10 | Compliant | `headersFrameReceived()` / `continuationFrameReceived()` |
| PRIORITY frame (deprecated, no-op) | 6.3 | Compliant | `priorityFrameReceived()` is a no-op |
| RST_STREAM reception | 6.4 | Compliant | `rstStreamFrameReceived()` fails the stream |
| SETTINGS reception, processing, ACK | 6.5 | Compliant | `settingsFrameReceived()` processes all 6 params and sends ACK |
| SETTINGS_INITIAL_WINDOW_SIZE adjustment | 6.9.2 | Compliant | Adjusts all stream windows; overflow → FLOW_CONTROL_ERROR |
| PUSH_PROMISE reception | 6.6 | Compliant | Decodes headers via HPACK, delivers `PushPromise` to handler; accept registers stream, reject sends RST_STREAM REFUSED_STREAM |
| PING ACK | 6.7 | Compliant | Echoes non-ACK pings |
| GOAWAY reception | 6.8 | Compliant | Fails streams above lastStreamId, closes connection |
| GOAWAY sending with debug data | 6.8 | Compliant | `sendGoaway(int, String)` overload includes UTF-8 debug data in all GOAWAY frames |
| WINDOW_UPDATE reception | 6.9 | Compliant | Updates flow control; overflow → GOAWAY/RST_STREAM |
| WINDOW_UPDATE overflow detection | 6.9.1 | Compliant | Connection-level → GOAWAY; stream-level → RST_STREAM |

### HTTP/2 Client Stream Management — RFC 9113 section 5

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Client-initiated streams use odd IDs | 5.1.1 | Compliant | `nextStreamId` starts at 1, incremented by 2 |
| SETTINGS_MAX_CONCURRENT_STREAMS enforcement | 5.1.2 | Compliant | `sendRequest()` queues requests exceeding limit; `drainPendingRequests()` dispatches when capacity available |
| Flow control: connection and stream windows | 5.2 | Compliant | Via shared `H2FlowControl` |
| Error handling: GOAWAY for connection errors | 5.4.1 | Compliant | `sendGoaway()` reports highest server-initiated stream ID / `frameError()` |
| Error handling: RST_STREAM for stream errors | 5.4.2 | Compliant | `sendRstStream()` / `frameError()` |

### HTTP/2 Client Connection Management — RFC 9113 section 9

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Idle connection timeout | 9.1 | Compliant | Configurable `idleTimeoutMs`; fires GOAWAY NO_ERROR on expiry |
| TLS 1.2 cipher suite blocklist | 9.2.2 | Compliant | `isBlockedH2CipherSuite()` rejects non-AEAD suites; GOAWAY INADEQUATE_SECURITY |

### HTTP/2 Client Message Semantics — RFC 9113 section 8

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Request pseudo-headers (:method, :scheme, :authority, :path) | 8.3.1 | Compliant | `encodeRequestHeaders()` emits all four before regular headers |
| Connection-specific headers stripped | 8.2.2 | Compliant | `connection`, `transfer-encoding`, `upgrade`, `host` filtered |
| HPACK header compression | RFC 7541 | Compliant | Shared Encoder/Decoder |
| HPACK decompression failure → COMPRESSION_ERROR | 4.3 | Compliant | `processHeaders()` sends GOAWAY with COMPRESSION_ERROR |
| Server push refusal | 8.4 | Compliant | RST_STREAM REFUSED_STREAM when push disabled |
| HEADERS/CONTINUATION fragmentation (sending) | 4.3 / 6.2 | Compliant | `SendHeadersTask` fragments at SETTINGS_MAX_FRAME_SIZE |
| DATA frame splitting at SETTINGS_MAX_FRAME_SIZE | 4.2 | Compliant | `writeDataFrames()` |
| DATA frame flow control with pending queue | 6.9 | Compliant | `SendDataTask` + `drainPendingData()` |

---

## HTTP/3 Server — RFC 9114

The HTTP/3 implementation uses the **quiche** native library for all HTTP/3 framing (RFC 9114 section 7), QPACK header compression (RFC 9204), and QUIC transport (RFC 9000). The Java code bridges between quiche's h3 event model and the gumdrop `HTTPRequestHandler` API. Requirements handled entirely by quiche are marked "Compliant (quiche)".

### HTTP/3 Server Connection Setup — RFC 9114 section 3

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| ALPN "h3" negotiation | 3.1 | Compliant | `HTTP3Listener.createTransportFactory()` sets ALPN "h3" |
| TLS 1.3 mandatory | 3 | Compliant (quiche) | QUIC mandates TLS 1.3 via BoringSSL |
| SETTINGS frame exchange | 7.2.4 | Compliant (quiche) | quiche h3 module exchanges SETTINGS during `initH3()` |
| QPACK dynamic table capacity | RFC 9204 3.2.3 | Compliant | `DEFAULT_QPACK_MAX_TABLE_CAPACITY = 4096` configured via JNI |
| Unidirectional control streams | 6.2 | Compliant (quiche) | quiche manages control, QPACK encoder/decoder streams |

### HTTP/3 Server Request Handling — RFC 9114 section 4

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| HEADERS frame initiates request | 4.1 | Compliant | `HTTP3ServerHandler.onHeaders()` creates `H3Stream` |
| DATA frame carries body | 4.1 | Compliant | `HTTP3ServerHandler.onData()` drains via `quiche_h3_recv_body` |
| FIN completes message | 4.1 | Compliant | `HTTP3ServerHandler.onFinished()` calls `handler.requestComplete()` |
| Request pseudo-headers (:method, :scheme, :path) | 4.3.1 | Compliant | `H3Stream.onHeaders()` validates mandatory pseudo-headers; CONNECT exempted from :scheme/:path |
| Malformed request detection | 4.1.2 | Compliant | Missing pseudo-headers return 400 and close the stream |
| Connection-specific header stripping | 4.2 | Compliant | `H3Stream.flushHeaders()` strips Connection, Keep-Alive, Proxy-Connection, Transfer-Encoding, Upgrade |
| Trailer headers (subsequent HEADERS) | 4.1 | Compliant | `H3Stream.onHeaders()` dispatches post-body HEADERS to handler |
| Request body backpressure | 4.1 | Compliant | `H3Stream.pauseRequestBody()` / `resumeRequestBody()` with deferred read set |

### HTTP/3 Server Response Sending — RFC 9114 section 4

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Response HEADERS with :status pseudo-header | 4.3.2 | Compliant | `H3Stream.flushHeaders()` sends via `quiche_h3_send_response` |
| Response DATA frames | 4.1 | Compliant | `H3Stream.sendBody()` sends via `quiche_h3_send_body` |
| FIN to complete response | 4.1 | Compliant | `H3Stream.complete()` sends empty buffer with fin=true |
| Flow control buffering | RFC 9000 4 | Compliant | `H3Stream.enqueue()` buffers on QUICHE_ERR_DONE; `resumeWrite()` drains on ACK |

### HTTP/3 Server Push, GOAWAY, Error Handling

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Server push (PUSH_PROMISE) | 4.6 | Not implemented | `H3Stream.pushPromise()` returns false |
| GOAWAY reception (from client) | 5.2 | Compliant | `onGoaway()` records last-stream-ID, rejects new streams beyond it, sends server GOAWAY in response |
| GOAWAY sending (graceful shutdown) | 5.2 | Compliant | `close()` sends GOAWAY with highest client-initiated stream ID via `quiche_h3_send_goaway` before resetting streams |
| Stream reset handling | 8 | Compliant | `onReset()` ends span and cleans up stream |
| Request cancellation | 8 | Compliant | `H3Stream.cancel()` resets stream |
| HTTP/3 error codes | 8.1 | Compliant (quiche) | quiche manages H3 error codes in RESET_STREAM/STOP_SENDING |

### HTTP/3 Server Extended Features

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| WebSocket over HTTP/3 | RFC 9220 | Implemented | Extended CONNECT with `:protocol = "websocket"`, `SETTINGS_ENABLE_CONNECT_PROTOCOL = 1` via quiche, `H3Stream.upgradeToWebSocket()` bridges to `WebSocketConnection`, `HTTP3WebSocketListener` for service integration |
| 103 Early Hints (RFC 8297) | RFC 9114 s4 | Implemented | `H3Stream.sendInformational()` sends 1xx via `quiche_h3_send_response` / `quiche_h3_send_additional_headers`; state tracked by `responseStarted`; `flushHeaders()` uses `send_additional_headers` for final response after 1xx |
| Extensible priorities (server) | RFC 9218 4 | Compliant | `Priority` header passed through to handler in request headers |
| QUIC transport parameter tuning | RFC 9000 18 | Compliant | `HTTP3Listener` exposes `setQuicMax*()` setters that delegate to `QuicTransportFactory` |
| Authentication | RFC 9110 11 | Compliant | `H3Stream.onHeaders()` checks Authorization header via `HTTPAuthenticationProvider` |
| Telemetry / tracing | — | Compliant | `H3Stream.initTelemetrySpan()` / `endTelemetrySpan()` with OpenTelemetry attributes |

---

## HTTP/3 Client — RFC 9114

### HTTP/3 Client Connection Setup — RFC 9114 section 3

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| ALPN "h3" negotiation | 3.1 | Compliant | `HTTPClient.connectH3()` sets ALPN "h3" on `QuicTransportFactory` |
| TLS 1.3 mandatory | 3 | Compliant (quiche) | QUIC mandates TLS 1.3 via BoringSSL |
| SETTINGS frame exchange | 7.2.4 | Compliant (quiche) | quiche h3 module exchanges SETTINGS during `initH3()` |
| QPACK dynamic table capacity | RFC 9204 3.2.3 | Compliant | `DEFAULT_QPACK_MAX_TABLE_CAPACITY = 4096` configured via JNI |
| Alt-Svc discovery | 3.1 | Compliant | `HTTPClient.altSvcReceived()` parses `h3="host:port"` and initiates QUIC connection |

### HTTP/3 Client Request Sending — RFC 9114 section 4

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Request pseudo-headers (:method, :scheme, :authority, :path) | 4.3.1 | Compliant | `H3Request.buildHeaders()` emits all four pseudo-headers in order |
| HEADERS frame sent on new stream | 4.1 | Compliant | `HTTP3ClientHandler.sendRequest()` calls `quiche_h3_send_request` |
| DATA frames for request body | 4.1 | Compliant | `sendRequestBody()` sends via `quiche_h3_send_body`; buffers in `PendingWrite` on QUICHE_ERR_DONE and drains in `resumePendingWrites()` |
| FIN to complete request | 4.1 | Compliant | `H3Request.endRequestBody()` sends empty buffer with fin=true |
| GOAWAY rejection of new requests | 5.2 | Compliant | `sendRequest()` returns -1 with IOException when goaway is set |
| Priority (RFC 9218) | RFC 9218 4, 5 | Compliant | `H3Request.priority()` emits `Priority` header with `u=` urgency parameter |

### HTTP/3 Client Response Parsing — RFC 9114 section 4

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| :status pseudo-header extraction | 4.3.2 | Compliant | `H3ClientStream.extractStatus()` extracts :status; returns -1 if absent |
| :status mandatory validation | 4.3.2 | Compliant | Missing :status causes stream failure with IOException (malformed response) |
| Informational responses (1xx) | 4.1 | Compliant | 1xx HEADERS dispatch headers to handler, return to OPEN for final response |
| Response headers dispatched | 4.1 | Compliant | `H3ClientStream.onHeaders()` dispatches non-pseudo headers to handler |
| Response body data | 4.1 | Compliant | `H3ClientStream.onData()` dispatches body content to handler |
| Response completion (FIN) | 4.1 | Compliant | `H3ClientStream.onFinished()` calls `endResponseBody()` and `close()` |
| Stream reset handling | 8 | Compliant | `H3ClientStream.onReset()` calls `handler.failed()` with IOException |

### HTTP/3 Client GOAWAY and Connection Management

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| GOAWAY reception | 5.2 | Compliant | Records last-stream-ID; fails unprocessed streams (ID > last) with retryable IOException |
| Connection readiness callback | 3 | Compliant | `HTTP3ClientHandler.onConnectionReady()` fires readyCallback then polls |
| Resource cleanup | — | Compliant | `close()` resets all streams, frees h3 config and connection handles |

---

## QUIC Transport — RFC 9000

The QUIC transport layer uses the **quiche** native library for all protocol processing. The Java layer manages connection demultiplexing, UDP I/O, and exposes QUIC connections/streams to application protocol handlers.

### QUIC Connection Management — RFC 9000

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Connection ID generation (up to 20 bytes) | 5.1 | Compliant | `QuicEngine.generateConnectionId()` with SecureRandom |
| Version negotiation | 6.1 | Compliant | `QuicEngine.sendVersionNegotiation()` for unsupported versions |
| QUIC v1 support | 15 | Compliant | `QUICHE_PROTOCOL_VERSION_1 = 0x00000001` |
| QUIC v2 support | RFC 9369 | Compliant | `QUICHE_PROTOCOL_VERSION_2 = 0x6b3343cf` |
| Handshake (server accepts) | 7 | Compliant (quiche) | `QuicEngine.acceptConnection()` creates quiche connection |
| Handshake (client initiates) | 7 | Compliant (quiche) | `QuicEngine.connectTo()` sends Initial packet |
| HANDSHAKE_DONE confirmation | 7.3 | Compliant (quiche) | `QuicConnection.checkEstablished()` detects established state |
| TLS 1.3 via BoringSSL | RFC 9001 | Compliant (quiche) | SSL context created per connection |
| Idle timeout | 10.1 | Compliant (quiche) | `QuicConnection.scheduleTimeout()` uses quiche timeout |
| Immediate close (CONNECTION_CLOSE) | 10.2 | Compliant | `QuicConnection.close()` calls `quiche_conn_close` with H3_NO_ERROR (0x100) or NO_ERROR (0x0) and flushes before freeing |

### QUIC Stream Management — RFC 9000 section 2

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Bidirectional streams | 2.1 | Compliant | Client-initiated bidi streams (IDs 0, 4, 8, ...) |
| Unidirectional streams | 2.1 | Compliant (quiche) | Used by HTTP/3 for control and QPACK streams |
| Stream ID assignment | 2.1 | Compliant | `findNextStreamId()` uses even IDs for client-initiated |
| Flow control | 4 | Compliant (quiche) | quiche manages connection and stream-level flow control |
| Stream accept handler | — | Compliant | `QuicConnection.acceptStream()` for server-side new streams |

### QUIC Transport Parameters — RFC 9000 section 18

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| max_idle_timeout | 18.2 | Compliant | `DEFAULT_MAX_IDLE_TIMEOUT = 30000` ms |
| initial_max_data | 18.2 | Compliant | `DEFAULT_MAX_DATA = 10_000_000` |
| initial_max_stream_data_bidi_local | 18.2 | Compliant | `DEFAULT_MAX_STREAM_DATA = 1_000_000` |
| initial_max_stream_data_bidi_remote | 18.2 | Compliant | `DEFAULT_MAX_STREAM_DATA = 1_000_000` |
| initial_max_stream_data_uni | 18.2 | Compliant | `DEFAULT_MAX_STREAM_DATA = 1_000_000` |
| initial_max_streams_bidi | 18.2 | Compliant | `DEFAULT_MAX_STREAMS_BIDI = 100` |
| initial_max_streams_uni | 18.2 | Compliant | `DEFAULT_MAX_STREAMS_UNI = 100` |
| Congestion control algorithm | — | Compliant | Reno, CUBIC (default), BBR selectable via `setCongestionControl()` |

## IMAP Server — RFC 9051

### Connection and State Model

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| Server greeting (OK/PREAUTH/BYE) | 7.1 | Compliant | `sendGreeting()` sends OK with CAPABILITY |
| State machine (NOT_AUTHENTICATED → AUTHENTICATED → SELECTED → LOGOUT) | 3 | Compliant | `dispatchCommand()` routes by `IMAPState` |
| Tag validation | 2.2.1 | Compliant | `isValidTag()` checks ASTRING-CHAR excluding `+` |
| Command-tag matching in responses | 7.1 | Compliant | `sendTaggedOk/No/Bad()` echo the command tag |

### Any-State Commands

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| CAPABILITY | 6.1.1 | Compliant | `handleCapability()`, capability string built in `IMAPListener.getCapabilities()` |
| NOOP | 6.1.2 | Compliant | `handleNoop()`, sends mailbox updates if selected |
| LOGOUT | 6.1.3 | Compliant | `handleLogout()`, sends BYE then OK |

### Not-Authenticated Commands

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| STARTTLS | 6.2.1 | Compliant | `handleStartTLS()`, checks already-TLS and availability |
| AUTHENTICATE (SASL) | 6.2.2 | Compliant | `handleAuthenticate()`, dispatches to per-mechanism handlers |
| SASL initial response (inline) | RFC 4959 | Compliant | Parsed in `handleAuthenticate()` when `args` contains a space |
| LOGIN | 6.2.3 | Compliant | `handleLogin()`, PRIVACYREQUIRED on cleartext |
| LOGINDISABLED capability | 6.2.3 | Compliant | Advertised when `!secure && !allowPlaintextLogin` |
| SASL PLAIN | RFC 4616 | Compliant | `handleAuthPLAIN()` |
| SASL LOGIN | draft-murchison | Compliant | `handleAuthLOGIN()` |
| SASL CRAM-MD5 | RFC 2195 | Compliant | `handleAuthCRAMMD5()` |
| SASL DIGEST-MD5 | RFC 2831 | Compliant | `handleAuthDIGESTMD5()` (historic per RFC 6331) |
| SASL SCRAM-SHA-* | RFC 5802 | Compliant | `handleAuthSCRAM()` |
| SASL OAUTHBEARER | RFC 7628 | Compliant | `handleAuthOAUTHBEARER()` |
| SASL GSSAPI | RFC 4752 | Compliant | `handleAuthGSSAPI()` — §3.1 token exchange, §3.1 security layer (no-layer only), §3.2 service name, §3.3 mutual auth |
| SASL EXTERNAL | RFC 4422 App A | Compliant | `handleAuthEXTERNAL()`, TLS client cert |

### Authenticated Commands

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| SELECT | 6.3.1 | Compliant | `handleSelect()`, sends EXISTS/RECENT/FLAGS/UIDVALIDITY/UIDNEXT |
| EXAMINE | 6.3.2 | Compliant | `handleSelect(readOnly=true)` |
| CREATE | 6.3.3 | Compliant | `handleCreate()` |
| DELETE | 6.3.4 | Compliant | `handleDelete()` |
| RENAME | 6.3.5 | Compliant | `handleRename()` |
| SUBSCRIBE | 6.3.6 | Compliant | `handleSubscribe()` |
| UNSUBSCRIBE | 6.3.7 | Compliant | `handleUnsubscribe()` |
| LIST | 6.3.8 | Compliant | `handleList()`, includes mailbox attributes |
| LSUB (deprecated) | 6.3.9 | Compliant | `handleLsub()`, backward compatibility |
| NAMESPACE | RFC 2342 | Compliant | `handleNamespace()`, gated by `enableNAMESPACE` |
| STATUS | 6.3.11 | Compliant | `handleStatus()`, parses all StatusItem values |
| APPEND | 6.3.12 | Compliant | `handleAppend()` — `parseInternalDate()` parses IMAP date-time format `dd-Mon-yyyy HH:mm:ss ±hhmm` |
| IDLE | RFC 2177 | Compliant | `handleIdle()` — periodic timer polls mailbox and pushes untagged EXISTS/EXPUNGE during IDLE |
| GETQUOTA | RFC 9208 §3.1 | Compliant | `handleGetQuota()`, gated by `enableQUOTA` |
| GETQUOTAROOT | RFC 9208 §3.2 | Compliant | `handleGetQuotaRoot()` |
| SETQUOTA | RFC 9208 §3.3 | Compliant | `handleSetQuota()` |

### Selected Commands

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| CLOSE | 6.4.1 | Compliant | `handleClose()`, implicit expunge |
| UNSELECT | 6.4.2 | Compliant | `handleUnselect()`, no expunge |
| EXPUNGE | 6.4.3 | Compliant | `handleExpunge()`, sends per-message EXPUNGE |
| SEARCH | 6.4.4 | Compliant | `handleSearch()` + `SearchParser` |
| FETCH | 6.4.5 | Compliant | `handleFetch()`, supports FLAGS/ENVELOPE/BODY/RFC822 etc. |
| STORE | 6.4.6 | Compliant | `handleStore()`, supports FLAGS/+FLAGS/-FLAGS with .SILENT |
| COPY | 6.4.7 | Compliant | `handleCopy()` |
| MOVE | RFC 6851 | Compliant | `handleMove()`, gated by `enableMOVE` |
| UID prefix | 6.4.9 | Compliant | `handleUid()`, routes FETCH/SEARCH/STORE/COPY/MOVE/EXPUNGE |

### Server Responses

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| Untagged responses (* ...) | 7.1 | Compliant | `sendUntagged()` |
| Tagged OK/NO/BAD | 7.1 | Compliant | `sendTaggedOk/No/Bad()` |
| Continuation (+ ...) | 7.5 | Compliant | `sendContinuation()` |
| EXISTS / EXPUNGE unsolicited | 7.3.1 / 7.5 | Compliant | `sendMailboxUpdates()` compares UID snapshots to detect expunged messages and sends EXPUNGE before EXISTS |
| Response codes ([CAPABILITY], [TRYCREATE], etc.) | 7.1.1 | Compliant | Used in login, append, select responses |

### Extension Capabilities

| Capability | RFC | Status | Notes |
|-------------|---------|--------|-------|
| IMAP4rev2 | RFC 9051 | Compliant | Always advertised |
| IDLE | RFC 2177 | Compliant | `handleIdle()` — periodic timer polls mailbox and pushes untagged EXISTS/EXPUNGE during IDLE |
| NAMESPACE | RFC 2342 | Compliant | Personal, shared, and other-users namespaces |
| MOVE | RFC 6851 | Compliant | Atomic move with EXPUNGE |
| QUOTA | RFC 9208 | Compliant | GETQUOTA/GETQUOTAROOT/SETQUOTA |
| UNSELECT | RFC 9051 §6.4.2 | Compliant | Always advertised |
| UIDPLUS | RFC 4315 | Compliant | Always advertised |
| CHILDREN | RFC 3348 | Compliant | Always advertised |
| LIST-EXTENDED | RFC 5258 | Compliant | Always advertised |
| LIST-STATUS | RFC 5819 | Compliant | Always advertised |
| LITERAL- | RFC 7888 | Compliant | `LITERAL-` advertised; non-sync literals ({N+}) up to 4096 bytes accepted in all commands; `processLine()` buffers partial commands and consumes literal data; APPEND uses its own specialized binary path |
| ID | RFC 2971 | Compliant | `handleId()`, configurable server fields via `setServerIdFields()` |
| CONDSTORE/QRESYNC | RFC 7162 | Implemented | Per-message MODSEQ via `Mailbox`, ENABLE CONDSTORE/QRESYNC, HIGHESTMODSEQ in SELECT, MODSEQ in FETCH/SEARCH/STORE (UNCHANGEDSINCE), VANISHED (EARLIER) on QRESYNC SELECT, session-wide VANISHED instead of EXPUNGE |

## IMAP Client — RFC 9051

### Connection Setup

| Requirement | RFC | Status | Notes |
|-------------|---------|--------|-------|
| Plaintext (port 143) | RFC 9051 | Compliant | `IMAPClient` with default port |
| Implicit TLS (IMAPS, port 993) | RFC 8314 §3.3 | Compliant | `setSecure(true)` |
| STARTTLS upgrade | RFC 9051 §6.2.1 | Compliant | `starttls()` sends STARTTLS, handler upgrades |
| Server greeting parsing (OK/PREAUTH/BYE) | RFC 9051 §7.1 | Compliant | `dispatchGreeting()` |
| CAPABILITY from greeting | RFC 9051 §6.1.1 | Compliant | Parsed from OK [CAPABILITY ...] response code |

### Authentication

| Requirement | RFC | Status | Notes |
|-------------|---------|--------|-------|
| LOGIN command | RFC 9051 §6.2.3 | Compliant | `login()` |
| AUTHENTICATE with SASL | RFC 9051 §6.2.2 | Compliant | `authenticate()` |
| SASL initial response | RFC 4959 | Compliant | Sent inline in `authenticate()` |
| SASL challenge/response exchange | RFC 4422 | Compliant | `respond()` in ClientAuthExchange |
| SASL abort (*) | RFC 9051 §6.2.2 | Compliant | `abort()` sends `*` line |

### Mailbox Operations

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| SELECT | 6.3.1 | Compliant | `select()`, populates `MailboxInfo` |
| EXAMINE | 6.3.2 | Compliant | `examine()` |
| CREATE | 6.3.3 | Compliant | `create()` |
| DELETE | 6.3.4 | Compliant | `delete()` |
| RENAME | 6.3.5 | Compliant | `rename()` |
| SUBSCRIBE | 6.3.6 | Compliant | `subscribe()` |
| UNSUBSCRIBE | 6.3.7 | Compliant | `unsubscribe()` |
| LIST | 6.3.8 | Compliant | `list()` |
| LSUB (deprecated) | 6.3.9 | Compliant | `lsub()` |
| STATUS | 6.3.11 | Compliant | `status()` |
| NAMESPACE | RFC 2342 | Compliant | `namespace()` |
| APPEND | 6.3.12 | Compliant | `append()` with literal streaming |

### Message Operations

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| CLOSE | 6.4.1 | Compliant | `close()` |
| UNSELECT | 6.4.2 | Compliant | `unselect()` |
| EXPUNGE | 6.4.3 | Compliant | `expunge()` |
| SEARCH / UID SEARCH | 6.4.4 | Compliant | `search()`, `uidSearch()` |
| FETCH / UID FETCH | 6.4.5 | Compliant | `fetch()`, `uidFetch()`, literal streaming via `LiteralTracker` |
| STORE / UID STORE | 6.4.6 | Compliant | `store()`, `uidStore()` |
| COPY / UID COPY | 6.4.7 | Compliant | `copy()`, `uidCopy()` |
| MOVE / UID MOVE | RFC 6851 | Compliant | `move()`, `uidMove()` |
| NOOP | 6.1.2 | Compliant | `noop()` |
| LOGOUT | 6.1.3 | Compliant | `logout()` |
| IDLE | RFC 2177 | Compliant | `idle()`, DONE to exit |
| GETQUOTA | RFC 9208 §3.1 | Compliant | `getQuota()`, `ServerQuotaReplyHandler` callbacks |
| GETQUOTAROOT | RFC 9208 §3.2 | Compliant | `getQuotaRoot()`, `ServerQuotaReplyHandler` callbacks |

### Response Parsing

| Requirement | RFC 9051 Section | Status | Notes |
|-------------|---------|--------|-------|
| Tagged responses (OK/NO/BAD) | 7.1 | Compliant | `IMAPResponse.parse()` |
| Untagged responses (* ...) | 7.2–7.5 | Compliant | `dispatchUntagged()` |
| Continuation requests (+ ...) | 7.5 | Compliant | `dispatchContinuation()` |
| Literal data ({count}CRLF data) | 4.3 | Compliant | `LiteralTracker` byte-counting |
| Response codes ([CAPABILITY], [UIDVALIDITY], etc.) | 7.1.1 | Compliant | Parsed in `dispatchGreeting()` / `dispatchUntaggedStatus()` |
| Unsolicited EXISTS/EXPUNGE/FETCH | 7.3–7.4 | Compliant | `MailboxEventListener` delivery |

---

## POP3 Server — RFC 1939

### Connection and State Model

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| Three-state model (AUTHORIZATION, TRANSACTION, UPDATE) | 1939 §3 | Compliant | `POP3State` enum, `handleCommand()` dispatch |
| Server greeting (+OK with optional APOP timestamp) | 1939 §4 | Compliant | `sendGreeting()`, APOP timestamp generation |
| 512-octet maximum command length | 1939 §4 | Compliant | `MAX_LINE_LENGTH = 512` enforced by LineParser |
| Auto-logout inactivity timer (≥10 min recommended) | 1939 §3 | Compliant | `transactionTimeoutMs` (default 10 min) |
| +OK / -ERR status indicators | 1939 §3 | Compliant | `sendOK()` / `sendERR()` |
| Multi-line response termination (CRLF.CRLF) | 1939 §3 | Compliant | `MessageContentWriter` sends terminating "." |
| Dot-stuffing for lines beginning with "." | 1939 §3 | Compliant | `MessageContentWriter` byte-stuffing in sync/async paths |

### AUTHORIZATION Commands

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| USER command | 1939 §7 | Compliant | `handleUSER()` — stores username |
| PASS command (requires prior USER) | 1939 §7 | Compliant | `handlePASS()` — enforces USER→PASS sequence |
| APOP (MD5 challenge-response) | 1939 §7 | Compliant | `handleAPOP()` — configurable via `enableAPOP` |
| AUTH (SASL authentication) | 5034 §4 | Compliant | `handleAUTH()` — dispatches to mechanism handlers |
| AUTH initial response in same command | 5034 §4 | Compliant | Parsed from space-separated argument |
| AUTH cancellation with "*" | 5034 §4 | Compliant | `handleAuthContinuation()` checks for "*" |
| STLS (STARTTLS upgrade) | 2595 §4 | Compliant | `handleSTLS()` — rejects if already secure |
| UTF8 command | 6816 §2 | Compliant | `handleUTF8()` — configurable via `enableUTF8` |

### SASL Mechanisms

| Mechanism | RFC | Status | Notes |
|---|---|---|---|
| PLAIN | 4616 | Compliant | `handleAuthPLAIN()` |
| LOGIN | draft-murchison-sasl-login | Compliant | `handleAuthLOGIN()` |
| CRAM-MD5 | 2195 | Compliant | `handleAuthCRAMMD5()` |
| DIGEST-MD5 | 2831 | Compliant | `handleAuthDIGESTMD5()` |
| SCRAM-SHA-256 | 5802/7677 | Compliant | `handleAuthSCRAM()` |
| OAUTHBEARER | 7628 | Compliant | `handleAuthOAUTHBEARER()` |
| GSSAPI | 4752 | Compliant | `handleAuthGSSAPI()` — §3.1 token exchange + security layer, §3.2 service name |
| EXTERNAL | 4422 App A | Compliant | `handleAuthEXTERNAL()` — TLS client cert |

### TRANSACTION Commands

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| STAT (message count + total size) | 1939 §5 | Compliant | `handleSTAT()` |
| LIST (all messages or single) | 1939 §5 | Compliant | `handleLIST()` — multi-line or single-line |
| RETR (retrieve message) | 1939 §5 | Compliant | `handleRETR()` — chunked async write with dot-stuffing |
| DELE (mark for deletion) | 1939 §5 | Compliant | `handleDELE()` — checks already-deleted |
| NOOP | 1939 §5 | Compliant | `handleNOOP()` |
| RSET (undelete all) | 1939 §5 | Compliant | `handleRSET()` |
| TOP (headers + n lines of body) | 1939 §7 | Compliant | `handleTOP()` — optional command |
| UIDL (unique-id listing) | 1939 §7 | Compliant | `handleUIDL()` — optional command |

### UPDATE State

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| QUIT in TRANSACTION → UPDATE | 1939 §6 | Compliant | `handleQUIT()` closes mailbox with commit |
| QUIT in AUTHORIZATION → close | 1939 §5 | Compliant | `handleQUIT()` closes connection |
| Deleted messages removed during UPDATE | 1939 §6 | Compliant | `mailbox.close(true)` commits deletions |

### Extensions (RFC 2449)

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| CAPA command | 2449 §5 | Compliant | `handleCAPA()` — multi-line capability listing |
| USER capability advertised | 2449 §6.3 | Compliant | Always advertised |
| UIDL capability advertised | 2449 §6.3 | Compliant | Always advertised |
| TOP capability advertised | 2449 §6.3 | Compliant | Always advertised |
| SASL capability with mechanism list | 2449 §6.3 | Compliant | Conditional on Realm configuration |
| STLS capability | 2449 §6.3 | Compliant | Conditional on TLS context and not already secure |
| UTF8 capability | 6816 §2 | Compliant | Conditional on `enableUTF8` |
| PIPELINING capability | 2449 §6.8 | Compliant | Conditional on `enablePipelining` |
| IMPLEMENTATION capability | 2449 §6.9 | Compliant | "gumdrop" |
| RESP-CODES capability | 2449 §8 | Compliant | `[AUTH]`, `[SYS/TEMP]`, `[SYS/PERM]` codes in -ERR replies |
| AUTH-RESP-CODE capability | 3206 | Compliant | `[AUTH]` code for authentication failures |
| EXPIRE capability | 2449 §6.5 | Compliant | Configurable via `setExpireDays()` (NEVER, 0+, or suppressed) |
| LOGIN-DELAY capability | 2449 §6.6 | Compliant | Computed from `loginDelayMs`, advertised in seconds |

### Security

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| Implicit TLS (POP3S, port 995) | 8314 §3.3 | Compliant | `POP3Listener` secure mode |
| STLS before authentication | 2595 §4 | Compliant | STLS only in AUTHORIZATION state |
| SASL TLS-only mechanism gating | 5034 | Compliant | `mech.requiresTLS()` check in CAPA and AUTH |
| Login delay after failed auth | — | Compliant | `enforceLoginDelay()` with configurable delay |

---

## POP3 Client — RFC 1939

### Connection Setup

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| Parse server greeting (+OK / -ERR) | 1939 §4 | Compliant | `dispatchGreeting()` |
| Extract APOP timestamp from greeting | 1939 §7 | Compliant | `parseApopTimestamp()` |
| Implicit TLS (POP3S, port 995) | 8314 §3.3 | Compliant | `POP3Client.setSecure(true)` |
| STLS upgrade (STARTTLS) | 2595 §4 | Compliant | `stls()` → `endpoint.startTLS()` |

### Authentication

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| USER / PASS | 1939 §7 | Compliant | `user()` / `pass()` |
| APOP | 1939 §7 | Compliant | `apop()` |
| AUTH (SASL) | 5034 §4 | Compliant | `auth()` with initial response support |
| SASL continuation (+ challenge) | 5034 §4 | Compliant | `respond()` / `abort()` |
| CAPA (capability discovery) | 2449 §5 | Compliant | `capa()` — parses multi-line response |

### Mailbox Operations

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| STAT | 1939 §5 | Compliant | `stat()` |
| LIST (all or single) | 1939 §5 | Compliant | `list()` / `list(msgNum)` |
| RETR (retrieve message) | 1939 §5 | Compliant | `retr()` — streaming via DotUnstuffer |
| DELE | 1939 §5 | Compliant | `dele()` |
| RSET | 1939 §5 | Compliant | `rset()` |
| TOP (headers + n lines) | 1939 §7 | Compliant | `top()` — streaming via DotUnstuffer |
| UIDL (all or single) | 1939 §7 | Compliant | `uidl()` / `uidl(msgNum)` |
| NOOP | 1939 §5 | Compliant | `noop()` |
| QUIT | 1939 §6 | Compliant | `quit()` |

### Response Parsing

| Requirement | RFC Section | Status | Notes |
|---|---|---|---|
| +OK status indicator | 1939 §3 | Compliant | `POP3Response.parse()` |
| -ERR status indicator | 1939 §3 | Compliant | `POP3Response.parse()` |
| SASL continuation (+ ...) | 5034 §4 | Compliant | `POP3Response.parse()` |
| Multi-line response termination | 1939 §3 | Compliant | `DotUnstuffer` state machine |
| Dot-unstuffing | 1939 §3 | Compliant | `DotUnstuffer` — handles cross-buffer splits |
| Streaming content delivery | — | Compliant | `ServerRetrReplyHandler.handleMessageContent()` with backpressure |

---

## LDAP Client — RFC 4511

### Connection and Message Envelope

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| LDAPv3 protocol version | 4511 §4.2 | Compliant | `bind()` sends version=3 |
| BER encoding (ITU-T X.690) | 4511 §5.1 | Compliant | `BEREncoder` / `BERDecoder` |
| LDAPMessage envelope (messageID + protocolOp) | 4511 §4.2 | Compliant | `processMessage()` — decode sequence, extract messageID and tag |
| Message ID correlation | 4511 §4.1.1 | Compliant | `pendingCallbacks` map keyed by messageID |
| Incremental message IDs | 4511 §4.1.1.1 | Compliant | `AtomicInteger nextMessageId` |
| LDAPS (implicit TLS, port 636) | 4513 §3.1.3 | Compliant | `LDAPClient.setSecure(true)` → `TCPTransportFactory.setSecure()` |
| STARTTLS extended operation | 4511 §4.14, 4513 §3 | Compliant | `startTLS()` sends ExtendedRequest with OID `1.3.6.1.4.1.1466.20037` |
| TLS handshake after STARTTLS | 4513 §3 | Compliant | `securityEstablished()` → `handleTLSEstablished()` callback |

### Authentication

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| Simple bind (DN + password) | 4511 §4.2, 4513 §5.1 | Compliant | `bind()` — context tag 0 (simple auth) |
| Anonymous bind | 4511 §4.2.1 | Compliant | `bindAnonymous()` — empty DN and password |
| BindResponse handling | 4511 §4.2.2 | Compliant | `handleBindResponse()` — dispatches success/failure; handles `serverSaslCreds` [7] |
| Rebind on existing connection | 4511 §4.2 | Compliant | `rebind()` / `rebindSASL()` delegate to `bind()` / `bindSASL()` |
| SASL bind | 4513 §5.2 | Compliant | `bindSASL(SASLClientMechanism)` — context tag [3], multi-step `SASL_BIND_IN_PROGRESS` (code 14) handled internally via native `SASLUtils` crypto (non-blocking); GSSAPI (RFC 4752) supported with worker-thread offloading for KDC contact |

### Search Operations

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| SearchRequest encoding | 4511 §4.5.1 | Compliant | `search()` — BER-encodes all fields (baseDN, scope, deref, limits, filter, attrs) |
| Search scope (base/one/subtree) | 4511 §4.5.1.2 | Compliant | `SearchScope` enum with values 0/1/2 |
| Deref aliases (never/searching/finding/always) | 4511 §4.5.1.3 | Compliant | `DerefAliases` enum with values 0–3 |
| Size and time limits | 4511 §4.5.1 | Compliant | `SearchRequest` — `sizeLimit` / `timeLimit` fields |
| Types-only flag | 4511 §4.5.1 | Compliant | `SearchRequest.typesOnly` |
| Filter encoding — equality (=) | 4515 §3 | Compliant | `encodeFilter()` — equality match |
| Filter encoding — substring (*) | 4515 §3 | Compliant | `encodeFilter()` — initial/any/final substrings |
| Filter encoding — presence (=*) | 4515 §3 | Compliant | `encodeFilter()` — present filter |
| Filter encoding — >=, <= | 4515 §3 | Compliant | `encodeFilter()` — greaterOrEqual / lessOrEqual |
| Filter encoding — AND (&), OR (\|), NOT (!) | 4515 §3 | Compliant | `encodeFilter()` — compound filters |
| Filter encoding — approximate (~=) | 4515 §4 | Compliant | `encodeFilter()` — context tag 8, AttributeValueAssertion |
| Filter encoding — extensible match (:=) | 4515 §4 | Compliant | `encodeExtensibleMatchFilter()` — context tag 9, MatchingRuleAssertion |
| SearchResultEntry handling | 4511 §4.5.2 | Compliant | `handleSearchResultEntry()` — parses DN + attributes |
| SearchResultDone handling | 4511 §4.5.2 | Compliant | `handleSearchResultDone()` — parses LDAPResult |
| SearchResultReference handling | 4511 §4.5.3 | Compliant | `handleSearchResultReference()` — parses referral URLs |

### Modify Operations

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| ModifyRequest encoding | 4511 §4.6 | Compliant | `modify()` — encodes modifications list (op + attr + values) |
| Modification operations (add/delete/replace) | 4511 §4.6 | Compliant | `Modification.Operation` enum |
| ModifyResponse handling | 4511 §4.6 | Compliant | `handleModifyResponse()` |
| AddRequest encoding | 4511 §4.7 | Compliant | `add()` — encodes DN + attribute list |
| AddResponse handling | 4511 §4.7 | Compliant | `handleAddResponse()` |
| DelRequest encoding | 4511 §4.8 | Compliant | `delete()` — application tag 10, primitive |
| DelResponse handling | 4511 §4.8 | Compliant | `handleDeleteResponse()` |
| CompareRequest encoding | 4511 §4.10 | Compliant | `compare()` — encodes DN + AttributeValueAssertion |
| CompareResponse handling (TRUE/FALSE) | 4511 §4.10 | Compliant | `handleCompareResponse()` — dispatches compareTrue/compareFalse |
| ModifyDNRequest encoding | 4511 §4.9 | Compliant | `modifyDN()` — encodes DN, newRDN, deleteOldRDN, optional newSuperior |
| ModifyDNResponse handling | 4511 §4.9 | Compliant | `handleModifyDNResponse()` |

### Extended Operations

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| ExtendedRequest encoding | 4511 §4.12 | Compliant | `extended()` — application tag 23, context tags 0/1 |
| ExtendedResponse handling | 4511 §4.12 | Compliant | `handleExtendedResponse()` — parses responseName/responseValue |
| STARTTLS ExtendedRequest | 4511 §4.14 | Compliant | `startTLS()` → `extended(OID_STARTTLS)` |
| UnbindRequest | 4511 §4.3 | Compliant | `unbind()` — application tag 2, no response expected |

### LDAPResult Parsing

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| Result code | 4511 §4.1.9 | Compliant | `parseResult()` — maps to `LDAPResultCode` enum |
| Matched DN | 4511 §4.1.9 | Compliant | `parseResult()` — extracted from response |
| Diagnostic message | 4511 §4.1.9 | Compliant | `parseResult()` — extracted from response |
| Referrals | 4511 §4.1.9 | Compliant | `parseResult()` — context tag 3, parsed as URL list |

### Abandon, Controls, Notifications

| Requirement | RFC | Status | Implementation |
|---|---|---|---|
| AbandonRequest | 4511 §4.11 | Compliant | `abandon()` — application tag 16, removes pending callback |
| Controls (request) | 4511 §4.1.11 | Compliant | `setRequestControls()` — encoded as context tag 0 in LDAPMessage |
| Controls (response) | 4511 §4.1.11 | Compliant | `parseControls()` / `getResponseControls()` — parsed from message envelope |
| IntermediateResponse | 4511 §4.13 | Compliant | `handleIntermediateResponse()` — dispatched to `IntermediateResponseHandler` |
| Unsolicited Notification (messageID 0) | 4511 §4.4 | Compliant | `handleUnsolicitedNotification()` — Notice of Disconnection triggers close |

---

## Redis Client — RESP Protocol

### RESP Wire Format

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| Command encoding (array of bulk strings) | RESP spec — Sending commands | Compliant | `RESPEncoder.encodeCommand()` — `*N\r\n$len\r\narg\r\n...` |
| Simple String response (`+`) | RESP spec — Simple Strings | Compliant | `RESPDecoder` parses `+...\r\n` → `RESPType.SIMPLE_STRING` |
| Error response (`-`) | RESP spec — Errors | Compliant | `RESPDecoder` parses `-...\r\n` → `RESPType.ERROR` |
| Integer response (`:`) | RESP spec — Integers | Compliant | `RESPDecoder` parses `:N\r\n` → `RESPType.INTEGER` (signed 64-bit) |
| Bulk String response (`$`) | RESP spec — Bulk Strings | Compliant | `RESPDecoder` parses `$len\r\ndata\r\n` → `RESPType.BULK_STRING` |
| Array response (`*`) | RESP spec — Arrays | Compliant | `RESPDecoder` parses `*N\r\n...` → `RESPType.ARRAY` (recursive) |
| Null Bulk String (`$-1\r\n`) | RESP spec — Bulk Strings | Compliant | `RESPValue.nullValue()` dispatched to `handleNull()` |
| Null Array (`*-1\r\n`) | RESP spec — Arrays | Compliant | `RESPValue.nullValue()` dispatched to `handleNull()` |
| Streaming decode (partial data) | RESP spec | Compliant | `RESPDecoder.receive()` accumulates; `next()` returns when complete |
| Pipelining (multiple commands in-flight) | RESP spec — Pipelining | Compliant | FIFO `pendingCommands` queue correlates responses to callbacks |
| RESP3 Map (`%`) | RESP3 spec — Map | Compliant | `RESPDecoder` parses `%N\r\n...` → `RESPType.MAP`, flattened for `ArrayResultHandler` |
| RESP3 Set (`~`) | RESP3 spec — Set | Compliant | `RESPDecoder` parses `~N\r\n...` → `RESPType.SET` |
| RESP3 Double (`,`) | RESP3 spec — Double | Compliant | `RESPDecoder` parses `,value\r\n` including inf/nan |
| RESP3 Boolean (`#`) | RESP3 spec — Boolean | Compliant | `RESPDecoder` parses `#t`/`#f` → `RESPType.BOOLEAN` |
| RESP3 Null (`_`) | RESP3 spec — Null | Compliant | `RESPDecoder` parses `_\r\n` → `RESPType.NULL` |
| RESP3 Push (`>`) | RESP3 spec — Push | Compliant | `RESPDecoder` parses `>N\r\n...` → `RESPType.PUSH`, Pub/Sub dispatch |
| RESP3 Verbatim String (`=`) | RESP3 spec — Verbatim String | Compliant | `RESPDecoder` parses `=len\r\nenc:data\r\n` with encoding hint |
| RESP3 Big Number (`(`) | RESP3 spec — Big Number | Compliant | `RESPDecoder` parses `(value\r\n` → `RESPType.BIG_NUMBER` |
| RESP3 Blob Error (`!`) | RESP3 spec — Blob Error | Compliant | `RESPDecoder` parses `!len\r\ndata\r\n` → `RESPType.BLOB_ERROR` |

### Connection and Authentication

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| AUTH (legacy, password-only) | Redis command — AUTH | Compliant | `auth(password, handler)` |
| AUTH (ACL, username + password) | Redis 6+ — AUTH | Compliant | `auth(username, password, handler)` |
| PING | Redis command — PING | Compliant | `ping(handler)` and `ping(message, handler)` |
| SELECT (database index) | Redis command — SELECT | Compliant | `select(index, handler)` |
| ECHO | Redis command — ECHO | Compliant | `echo(message, handler)` |
| QUIT | Redis command — QUIT | Compliant | `quit()` — sends QUIT then closes |
| TLS connection | Redis 6+ TLS | Compliant | `RedisClient.setSecure(true)` → `TCPTransportFactory.setSecure()` |
| HELLO (RESP3 negotiation) | Redis 6+ — HELLO | Compliant | `hello(protover, handler)` and `hello(protover, user, pass, handler)` |
| CLIENT SETNAME | Redis command — CLIENT SETNAME | Compliant | `clientSetName(name, handler)` |
| CLIENT GETNAME | Redis command — CLIENT GETNAME | Compliant | `clientGetName(handler)` |
| CLIENT ID | Redis command — CLIENT ID | Compliant | `clientId(handler)` |
| RESET | Redis 6.2+ — RESET | Compliant | `reset(handler)` — clears Pub/Sub state and sends RESET |

### String Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| GET / SET | Redis command | Compliant | `get()`, `set()` (string and byte[] overloads) |
| SETEX / PSETEX | Redis command | Compliant | `setex()`, `psetex()` |
| SETNX / GETSET | Redis command | Compliant | `setnx()`, `getset()` |
| MGET / MSET | Redis command | Compliant | `mget()`, `mset()` |
| INCR / DECR / INCRBY / DECRBY | Redis command | Compliant | `incr()`, `decr()`, `incrby()`, `decrby()` |
| INCRBYFLOAT | Redis command | Compliant | `incrbyfloat()` |
| APPEND / STRLEN | Redis command | Compliant | `append()`, `strlen()` |

### Key Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| DEL / EXISTS | Redis command | Compliant | `del()`, `exists()` (single + multi-key) |
| EXPIRE / PEXPIRE / EXPIREAT | Redis command | Compliant | `expire()`, `pexpire()`, `expireat()` |
| TTL / PTTL / PERSIST | Redis command | Compliant | `ttl()`, `pttl()`, `persist()` |
| KEYS / RENAME / RENAMENX / TYPE | Redis command | Compliant | `keys()`, `rename()`, `renamenx()`, `type()` |
| SCAN / HSCAN / SSCAN / ZSCAN | Redis command — SCAN | Compliant | `scan()`, `hscan()`, `sscan()`, `zscan()` with MATCH/COUNT via `ScanResultHandler` |

### Hash Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| HGET / HSET / HSETNX | Redis command | Compliant | `hget()`, `hset()` (string + byte[]), `hsetnx()` |
| HMGET / HMSET | Redis command | Compliant | `hmget()`, `hmset()` |
| HGETALL / HKEYS / HVALS | Redis command | Compliant | `hgetall()`, `hkeys()`, `hvals()` |
| HDEL / HEXISTS / HLEN | Redis command | Compliant | `hdel()`, `hexists()`, `hlen()` |
| HINCRBY / HINCRBYFLOAT | Redis command | Compliant | `hincrby()`, `hincrbyfloat()` |

### List Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| LPUSH / RPUSH | Redis command | Compliant | `lpush()`, `rpush()` (variadic) |
| LPOP / RPOP | Redis command | Compliant | `lpop()`, `rpop()` |
| LRANGE / LLEN / LINDEX | Redis command | Compliant | `lrange()`, `llen()`, `lindex()` |
| LSET / LTRIM / LREM | Redis command | Compliant | `lset()`, `ltrim()`, `lrem()` |
| BLPOP / BRPOP | Redis command — BLPOP/BRPOP | Compliant | `blpop()`, `brpop()` with timeout and multiple keys |
| BLMOVE | Redis 6.2+ — BLMOVE | Compliant | `blmove(source, dest, whereFrom, whereTo, timeout, handler)` |

### Set Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| SADD / SREM | Redis command | Compliant | `sadd()`, `srem()` (variadic) |
| SMEMBERS / SISMEMBER / SCARD | Redis command | Compliant | `smembers()`, `sismember()`, `scard()` |
| SPOP / SRANDMEMBER | Redis command | Compliant | `spop()`, `srandmember()` |

### Sorted Set Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| ZADD / ZSCORE / ZRANK | Redis command | Compliant | `zadd()`, `zscore()`, `zrank()` |
| ZRANGE / ZRANGE WITHSCORES / ZREVRANGE | Redis command | Compliant | `zrange()`, `zrangeWithScores()`, `zrevrange()` |
| ZREM / ZCARD / ZINCRBY | Redis command | Compliant | `zrem()`, `zcard()`, `zincrby()` |

### Pub/Sub

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| SUBSCRIBE / PSUBSCRIBE | RESP spec — Pub/Sub | Compliant | `subscribe()`, `psubscribe()` — enters Pub/Sub mode |
| UNSUBSCRIBE / PUNSUBSCRIBE | RESP spec — Pub/Sub | Compliant | `unsubscribe()`, `punsubscribe()` — exits when count=0 |
| PUBLISH | Redis command — PUBLISH | Compliant | `publish()` (string + byte[] overloads) |
| message push dispatch | RESP spec — Pub/Sub | Compliant | `handlePubSubMessage()` — message, pmessage, subscribe, unsubscribe |
| Pattern message dispatch | RESP spec — Pub/Sub | Compliant | `handlePubSubMessage()` — psubscribe, punsubscribe |

### Transactions

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| MULTI / EXEC / DISCARD | Redis command | Compliant | `multi()`, `exec()`, `discard()` |
| WATCH / UNWATCH | Redis command | Compliant | `watch()`, `unwatch()` |

### Scripting

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| EVAL | Redis command — EVAL | Compliant | `eval(script, numKeys, keys, args, handler)` |
| EVALSHA | Redis command — EVALSHA | Compliant | `evalsha(sha1, numKeys, keys, args, handler)` |

### Server Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| INFO / DBSIZE | Redis command | Compliant | `info()`, `info(section)`, `dbsize()` |
| FLUSHDB / FLUSHALL | Redis command | Compliant | `flushdb()`, `flushall()` |
| TIME | Redis command | Compliant | `time()` |
| Generic command passthrough | — | Compliant | `command(handler, cmd, args)` — string and byte[] overloads |

### Stream Commands

| Requirement | Spec | Status | Implementation |
|---|---|---|---|
| XADD | Redis command — XADD | Compliant | `xadd(key, id, handler, fieldsAndValues...)` |
| XLEN | Redis command — XLEN | Compliant | `xlen(key, handler)` |
| XRANGE / XREVRANGE | Redis command — XRANGE | Compliant | `xrange()`, `xrevrange()` with optional COUNT |
| XREAD | Redis command — XREAD | Compliant | `xread(count, blockMillis, handler, keysAndIds...)` with COUNT and BLOCK |
| XTRIM | Redis command — XTRIM | Compliant | `xtrim(key, maxLen, handler)` |
| XACK | Redis command — XACK | Compliant | `xack(key, group, handler, ids...)` |
| XGROUP CREATE / DESTROY | Redis command — XGROUP | Compliant | `xgroupCreate()`, `xgroupDestroy()` with MKSTREAM |
| XPENDING | Redis command — XPENDING | Compliant | `xpending(key, group, handler)` |

---

## SMTP Server — RFC 5321

### Core Commands

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| 220 greeting on connection | §4.2 | **Compliant** | Via ConnectedState or default |
| HELO command | §4.1.1.1 | **Compliant** | Hostname validation |
| EHLO command | §4.1.1.1 | **Compliant** | Multi-line 250 with extensions |
| MAIL FROM command | §4.1.1.2 | **Compliant** | Address parsing, parameter handling |
| RCPT TO command | §4.1.1.3 | **Compliant** | Max recipients enforced |
| DATA command | §4.1.1.4 | **Compliant** | Dot transparency (§4.5.2) |
| RSET command | §4.1.1.5 | **Compliant** | Transaction state reset |
| QUIT command | §4.1.1.10 | **Compliant** | 221 and close |
| NOOP command | §4.1.1.9 | **Compliant** | 250 OK |
| HELP command | §4.1.1.8 | **Compliant** | Per-command help |
| VRFY command | §4.1.1.6 | **Compliant** | 252 (information hiding, §7.3) |
| EXPN command | §4.1.1.7 | **Compliant** | 502 (not implemented, optional) |
| Command sequencing | §4.5.1 | **Compliant** | State machine enforced |
| Multi-line responses | §4.2 | **Compliant** | Hyphen continuation |
| Max command line length | §4.5.3 | **Compliant** | 1000 octet limit |
| Enhanced status codes | RFC 2034 | **Compliant** | X.Y.Z format throughout |
| Pipelined commands | RFC 2920 | **Compliant** | PIPELINING advertised |

### SMTP Extensions (Server)

| Extension | RFC | Status | Notes |
|-----------|-----|--------|-------|
| SIZE | RFC 1870 | **Compliant** | Advertised in EHLO, enforced in DATA/BDAT |
| 8BITMIME | RFC 6152 | **Compliant** | BODY=8BITMIME parameter |
| SMTPUTF8 | RFC 6531 | **Compliant** | UTF-8 addresses and headers |
| PIPELINING | RFC 2920 | **Compliant** | Advertised, pipelined processing |
| CHUNKING | RFC 3030 | **Compliant** | BDAT command with LAST |
| BINARYMIME | RFC 3030 | **Compliant** | BODY=BINARYMIME requires BDAT |
| ENHANCEDSTATUSCODES | RFC 2034 | **Compliant** | All replies include ESC |
| DSN (RET, ENVID) | RFC 3461 §4.3–4.4 | **Compliant** | MAIL FROM envelope params |
| DSN (NOTIFY, ORCPT) | RFC 3461 §4.1–4.2 | **Compliant** | RCPT TO recipient params |
| LIMITS (RCPTMAX, MAILMAX) | RFC 9422 | **Compliant** | Configurable limits |
| REQUIRETLS | RFC 8689 | **Compliant** | Enforced when TLS active |
| MT-PRIORITY | RFC 6710 | **Compliant** | MIXER, STANAG4406, NSEP |
| FUTURERELEASE | RFC 4865 | **Compliant** | HOLDFOR/HOLDUNTIL |
| DELIVERBY | RFC 2852 | **Compliant** | BY=seconds;R/N |
| STARTTLS | RFC 3207 | **Compliant** | State reset after TLS |
| AUTH (PLAIN) | RFC 4954 / RFC 4616 | **Compliant** | Base64 initial-response |
| AUTH (LOGIN) | draft-murchison-sasl-login | **Compliant** | Multi-round username/password |
| AUTH (EXTERNAL) | RFC 4422 | **Compliant** | TLS client certificate |
| AUTH (CRAM-MD5) | RFC 2195 | **Compliant** | Challenge-response HMAC-MD5, realm delegation |
| AUTH (DIGEST-MD5) | RFC 2831 | **Compliant** | Challenge-response with rspauth, deprecated by RFC 6331 |
| AUTH (SCRAM-SHA-256) | RFC 5802 / RFC 7677 | **Compliant** | Multi-round: client-first → server-first → client-final → server-final |
| AUTH (OAUTHBEARER) | RFC 7628 | **Compliant** | Bearer token validation via Realm, JSON error challenge |
| AUTH (GSSAPI) | RFC 4752 | **Compliant** | §3.1 token exchange + security layer negotiation, §3.2 service name, §3.3 mutual auth |
| AUTH abort ("*") | RFC 4954 §4 | **Compliant** | Returns 501 and resets auth state |
| Implicit TLS (SMTPS) | RFC 8314 | **Compliant** | Port 465, greeting after TLS |
| Message Submission | RFC 6409 | **Compliant** | Port 587, auth required mode |
| XCLIENT | Postfix | **Compliant** | NAME, ADDR, PORT, PROTO, HELO, LOGIN, DESTADDR, DESTPORT |
| ETRN | RFC 1985 | **Compliant** | Recognized command, returns 458 (no relay queue) |

---

## SMTP Client — RFC 5321

### Core Client Operations

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| TCP connection / 220 greeting | §3.1, §4.2 | **Compliant** | State machine driven |
| EHLO command | §4.1.1.1 | **Compliant** | Full capability parsing (15 extensions) |
| HELO fallback | §4.1.1.1 | **Compliant** | When EHLO unsupported (502) |
| MAIL FROM command | §4.1.1.2 | **Compliant** | Full extension parameters |
| RCPT TO command | §4.1.1.3 | **Compliant** | 250/251/252 accepted, DSN params |
| VRFY command | §4.1.1.6 | **Compliant** | Generic reply handler |
| EXPN command | §4.1.1.7 | **Compliant** | Generic reply handler |
| DATA command | §4.1.1.4 | **Compliant** | Dot stuffing (§4.5.2) |
| RSET command | §4.1.1.5 | **Compliant** | Transaction reset |
| QUIT command | §4.1.1.10 | **Compliant** | Connection close |
| STARTTLS | RFC 3207 | **Compliant** | TLS upgrade, state reset |
| AUTH (generic) | RFC 4954 | **Compliant** | Any mechanism, base64 encoding |
| AUTH abort | RFC 4954 §4 | **Compliant** | Sends "*" |
| 421 service closing | §4.2.1 | **Compliant** | Handled in all states |
| Multi-line response parsing | §4.2 | **Compliant** | Continuation lines |
| Dot stuffing | §4.5.2 | **Compliant** | DotStuffer state machine |
| BDAT (CHUNKING) | RFC 3030 | **Compliant** | Automatic when server advertises |
| Implicit TLS (SMTPS) | RFC 8314 | **Compliant** | setSecure(true) |

### Client EHLO Capability Parsing

| Keyword | RFC | Status | Notes |
|---------|-----|--------|-------|
| STARTTLS | RFC 3207 | **Parsed** | ehloStarttls flag |
| SIZE | RFC 1870 | **Parsed** | ehloMaxSize value |
| AUTH | RFC 4954 | **Parsed** | ehloAuthMethods list |
| PIPELINING | RFC 2920 | **Parsed** | ehloPipelining flag |
| CHUNKING | RFC 3030 | **Parsed** | ehloChunking flag |
| 8BITMIME | RFC 6152 | **Parsed** | ehlo8BitMime flag |
| SMTPUTF8 | RFC 6531 | **Parsed** | ehloSmtpUtf8 flag |
| DSN | RFC 3461 | **Parsed** | ehloDsn flag |
| ENHANCEDSTATUSCODES | RFC 2034 | **Parsed** | ehloEnhancedStatusCodes flag |
| BINARYMIME | RFC 3030 | **Parsed** | ehloBinaryMime flag |
| REQUIRETLS | RFC 8689 | **Parsed** | ehloRequireTls flag |
| MT-PRIORITY | RFC 6710 | **Parsed** | ehloMtPriority flag |
| FUTURERELEASE | RFC 4865 | **Parsed** | ehloFutureRelease flag |
| DELIVERBY | RFC 2852 | **Parsed** | ehloDeliverBy flag |
| LIMITS | RFC 9422 | **Parsed** | RCPTMAX and MAILMAX values |

### Client MAIL FROM / RCPT TO Parameters

| Parameter | RFC | Status | Notes |
|-----------|-----|--------|-------|
| SIZE | RFC 1870 | **Supported** | Passed when size > 0 and server advertises |
| BODY | RFC 6152 / RFC 3030 | **Supported** | 7BIT, 8BITMIME, BINARYMIME via MailFromParams |
| SMTPUTF8 | RFC 6531 | **Supported** | Via MailFromParams when server advertises |
| RET / ENVID | RFC 3461 §4.3–4.4 | **Supported** | DSN envelope params via MailFromParams |
| REQUIRETLS | RFC 8689 | **Supported** | Via MailFromParams when server advertises |
| MT-PRIORITY | RFC 6710 | **Supported** | Via MailFromParams when server advertises |
| HOLDFOR / HOLDUNTIL | RFC 4865 | **Supported** | Via MailFromParams when server advertises |
| BY | RFC 2852 | **Supported** | Via MailFromParams when server advertises |
| NOTIFY / ORCPT | RFC 3461 §4.1–4.2 | **Supported** | rcptTo() DSN params when server advertises DSN |

---

## SPF — RFC 7208

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| check_host() function | §4 | **Compliant** | Async with DNS callbacks |
| Mechanism evaluation | §5 | **Compliant** | Ordered evaluation |
| `all` mechanism | §5.1 | **Compliant** | Matches any IP |
| `include` mechanism | §5.2 | **Compliant** | Recursive SPF lookup |
| `a` mechanism | §5.3 | **Compliant** | A/AAAA lookup |
| `mx` mechanism | §5.4 | **Compliant** | MX then A/AAAA lookup |
| `ptr` mechanism (deprecated) | §5.5 | **Compliant** | Implemented despite deprecation |
| `ip4` / `ip6` mechanism | §5.6 | **Compliant** | CIDR matching |
| `exists` mechanism | §5.7 | **Compliant** | A lookup existence check |
| `redirect` modifier | §6.1 | **Compliant** | Domain redirect |
| `exp` modifier | §6.2 | **Compliant** | Explanation string via DNS |
| Macro processing | §7 | **Compliant** | Macro expansion |
| DNS lookup limit (10) | §4.6.4 | **Compliant** | Enforced |
| Result codes | §2.6 | **Compliant** | PASS, FAIL, SOFTFAIL, NEUTRAL, NONE, TEMPERROR, PERMERROR |

---

## DKIM — RFC 6376

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Verifier actions | §6 | **Compliant** | Full verification flow |
| Signature extraction | §6.1 | **Compliant** | DKIM-Signature header parsing |
| Key retrieval via DNS TXT | §6.1.2 | **Compliant** | Async DNS lookup |
| Header canonicalization | §3.4.1–3.4.2 | **Compliant** | relaxed/simple |
| Body canonicalization | §3.4.3–3.4.4 | **Compliant** | relaxed/simple |
| DKIM-Signature field parsing | §3.5 | **Compliant** | All required tags |
| RSA-SHA256 algorithm | §3.3 | **Compliant** | Default algorithm |
| Raw header byte capture | §3.4 | **Compliant** | DKIMMessageParser |
| Body hash computation | §3.7 | **Compliant** | From raw bytes |
| DKIM signing | §5 | **Compliant** | DKIMSigner — full signing with body/header canonicalization |
| Ed25519-SHA256 | RFC 8463 | **Compliant** | Signing and verification; raw 32-byte key parsing (§4) |
| Result codes | §6.1 | **Compliant** | PASS, FAIL, NONE, TEMPERROR, PERMERROR, POLICY, NEUTRAL |

---

## DMARC — RFC 7489

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Policy discovery | §6 | **Compliant** | DNS TXT _dmarc lookup |
| TXT record parsing | §6.3 | **Compliant** | p=, sp=, adkim=, aspf=, pct= tags |
| Identifier alignment | §3.1 | **Compliant** | SPF and DKIM alignment |
| Organizational domain | §3.2 | **Compliant** | Domain hierarchy resolution |
| SPF result aggregation | §6 | **Compliant** | Via SPFCallback |
| DKIM result aggregation | §6 | **Compliant** | Via DKIMCallback |
| Policy evaluation / verdict | §6.3 | **Compliant** | PASS, FAIL, NONE, TEMPERROR, PERMERROR |
| Policy actions | §6.3 | **Compliant** | NONE, QUARANTINE, REJECT |
| From domain extraction | §6 | **Compliant** | DMARCMessageHandler |
| AuthPipeline integration | — | **Compliant** | SPF at MAIL FROM, DKIM/DMARC at end-of-data |
| Aggregate reporting (rua=) | §7.1 | **Compliant** | DMARCAggregateReport — XML report per Appendix C schema |
| Forensic / failure reporting | §7.2 | **Compliant** | DMARCForensicReport — ARF format per RFC 5965/6591; fo=/rf= parsing |

---

## WebDAV Server — RFC 4918

### HTTP Methods (RFC 9110)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| GET | RFC 9110 §9.3.1 | **Compliant** | Static files, directory listings |
| HEAD | RFC 9110 §9.3.2 | **Compliant** | Headers only |
| OPTIONS | RFC 9110 §9.3.7 | **Compliant** | Allow header, DAV: 1,2 |
| PUT | RFC 9110 §9.3.4 | **Compliant** | 201 Created / 204 No Content |
| DELETE (files) | RFC 9110 §9.3.5 | **Compliant** | Single file deletion |
| DELETE (collections) | RFC 4918 §9.6.1 | **Compliant** | Recursive depth-first delete with 207 Multi-Status on partial failure |
| If-Modified-Since | RFC 9110 §13.1.3 | **Compliant** | 304 Not Modified |
| ETag generation | RFC 9110 §8.8.3 | **Compliant** | MD5-based weak ETag |
| Content-Type detection | RFC 9110 §8.3 | **Compliant** | Extension-based mapping |

### WebDAV Methods (RFC 4918)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| PROPFIND — allprop | §9.1 | **Compliant** | All live + dead properties |
| PROPFIND — propname | §9.1 | **Compliant** | Property name enumeration (live + dead) |
| PROPFIND — named props | §9.1 | **Compliant** | Requested properties (live + dead) |
| PROPPATCH — set/remove | §9.2 | **Compliant** | Per-property set/remove with 200/403 status via DeadPropertyStore |
| MKCOL | §9.3 | **Compliant** | Parent-must-exist, 409, 415 if body |
| COPY (files) | §9.8 | **Compliant** | Destination, Overwrite |
| COPY (collections) | §9.8 | **Compliant** | Recursive with Depth |
| MOVE | §9.9 | **Compliant** | Destination, Overwrite, lock checks |
| LOCK (new) | §9.10 | **Compliant** | Exclusive + shared write locks |
| LOCK (refresh) | §9.10.2 | **Compliant** | By Lock-Token + Timeout |
| UNLOCK | §9.11 | **Compliant** | By Lock-Token |
| 207 Multi-Status | §13 | **Compliant** | PROPFIND / PROPPATCH responses |

### WebDAV Headers (RFC 4918 §10)

| Header | RFC Section | Status | Notes |
|--------|-------------|--------|-------|
| DAV | §10.1 | **Compliant** | Compliance classes 1,2 |
| Depth | §10.2 | **Compliant** | 0, 1, infinity |
| Destination | §10.3 | **Compliant** | URI resolution and validation |
| If (basic) | §10.4 | **Compliant** | Full grammar: tagged-list, no-tag-list, Not, state-tokens, ETags |
| If (tagged-list / ETag) | §10.4 | **Compliant** | IfHeaderParser — OR/AND semantics, weak ETag comparison |
| Lock-Token | §10.5 | **Compliant** | opaquelocktoken: scheme |
| Overwrite | §10.6 | **Compliant** | T (default) / F |
| Timeout | §10.7 | **Compliant** | Second-N, Infinite, max cap |

### Live Properties (RFC 4918 §15)

| Property | RFC Section | Status | Notes |
|----------|-------------|--------|-------|
| creationdate | §15.1 | **Compliant** | ISO 8601 format |
| displayname | §15.2 | **Compliant** | File name |
| getcontentlength | §15.3 | **Compliant** | File size (non-directories) |
| getcontenttype | §15.5 | **Compliant** | Extension-based MIME type |
| getetag | §15.6 | **Compliant** | MD5-based ETag |
| getlastmodified | §15.7 | **Compliant** | HTTP-date format |
| lockdiscovery | §15.8 | **Compliant** | Active locks enumeration |
| resourcetype | §15.9 | **Compliant** | collection or empty |
| supportedlock | §15.10 | **Compliant** | Exclusive + shared write |
| Dead properties | §4 | **Compliant** | xattr primary with sidecar fallback; async I/O; configurable via `setDeadPropertyStorage()` |

### Locking (RFC 4918 §6–7)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Exclusive write lock | §6.2 | **Compliant** | Scope EXCLUSIVE, Type WRITE |
| Shared write lock | §6.2 | **Compliant** | Scope SHARED, Type WRITE |
| Lock token (opaquelocktoken) | §6.5 | **Compliant** | UUID-based |
| Lock-null resources | §7.3 | **Compliant** | Empty file created on LOCK |
| Lock conflict detection | §6.1–6.2 | **Compliant** | Exclusive vs shared checks |
| Lock timeout | §10.7 | **Compliant** | Second-N, Infinite, max 604800s |
| Lock refresh | §9.10.2 | **Compliant** | By token + new timeout |
| Depth-infinity locks | §14.4 | **Compliant** | Covers child resources |
| Expired lock cleanup | §6.6 | **Compliant** | Automatic on access |

---

## WebSocket Server and Client — RFC 6455

### Opening Handshake — Server (§4.2)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Upgrade: websocket header | §4.2.1 | **Compliant** | Case-insensitive check |
| Connection: Upgrade header | §4.2.1 | **Compliant** | Case-insensitive, comma-separated |
| Sec-WebSocket-Key validation | §4.2.1 | **Compliant** | 16-byte base64 nonce verified |
| Sec-WebSocket-Version: 13 | §4.2.1 | **Compliant** | Exact match required |
| Sec-WebSocket-Accept calculation | §4.2.2 | **Compliant** | GUID + SHA-1 + Base64 |
| 101 Switching Protocols response | §4.2.2 | **Compliant** | Via HTTPResponseState.upgradeToWebSocket() |
| Sec-WebSocket-Protocol negotiation | §4.2.2 | **Compliant** | Via WebSocketService.selectSubprotocol() |
| Sec-WebSocket-Extensions | §9.1 | **Compliant** | Extension negotiation framework; permessage-deflate (RFC 7692) |

### Opening Handshake — Client (§4.1)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| 16-byte SecureRandom key generation | §4.1 step 7 | **Compliant** | WebSocketHandshake.generateKey() |
| Upgrade request headers | §4.1 | **Compliant** | Upgrade, Connection, Version, Key |
| Sec-WebSocket-Protocol header | §4.1 | **Compliant** | Optional subprotocol via setSubprotocol() |
| 101 response validation | §4.1 step 5 | **Compliant** | Upgrade, Connection, Accept checked |
| Sec-WebSocket-Accept verification | §4.1 step 5 | **Compliant** | Recomputed and compared |
| wss:// TLS support | §11.1.2 | **Compliant** | Via setSecure(true) |

### Frame Format (§5.2)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| FIN bit | §5.2 | **Compliant** | Parsed and used for fragmentation |
| RSV1-3 bits | §5.2 | **Compliant** | Non-zero RSV triggers close 1002 (no extensions negotiated) |
| Opcode (4-bit) | §5.2 | **Compliant** | 0x0–0xA handled, unknown → close 1002 |
| MASK bit + masking key | §5.2, §5.3 | **Compliant** | Applied via XOR algorithm |
| 7-bit payload length | §5.2 | **Compliant** | 0–125 bytes |
| 16-bit extended length | §5.2 | **Compliant** | 126 → 2-byte length |
| 64-bit extended length | §5.2 | **Compliant** | 127 → 8-byte length |

### Masking (§5.3)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Client-to-server masking | §5.1 | **Compliant** | clientMode flag controls masking |
| Server-to-client unmasked | §5.1 | **Compliant** | Server frames not masked |
| XOR masking algorithm | §5.3 | **Compliant** | Correct 4-byte rotating key XOR |
| Strong entropy for masking key | §5.3 | **Compliant** | SecureRandom used for masking key generation |

### Data Frames (§5.6)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Text frame (opcode 0x1) | §5.6 | **Compliant** | UTF-8 encoded |
| Binary frame (opcode 0x2) | §5.6 | **Compliant** | Raw bytes |
| Message delivery | §5.6 | **Compliant** | Dispatched to textMessageReceived/binaryMessageReceived |

### Fragmentation (§5.4)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Continuation frame (opcode 0x0) | §5.4 | **Compliant** | Assembled into complete message |
| FIN tracking | §5.4 | **Compliant** | Message complete when FIN=1 |
| Interleaved control frames | §5.4 | **Compliant** | Control frames handled during fragmentation |
| Unexpected continuation rejection | §5.4 | **Compliant** | Close 1002 if no message in progress |
| Unexpected data during fragment | §5.4 | **Compliant** | Close 1002 if already fragmenting |
| Max message size enforcement | §7.4.1 | **Compliant** | Configurable via setMaxMessageSize(); close 1009 on exceed |

### Control Frames (§5.5)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Close frame (opcode 0x8) | §5.5.1 | **Compliant** | 2-byte code + UTF-8 reason |
| Ping frame (opcode 0x9) | §5.5.2 | **Compliant** | Dispatched to pingReceived() |
| Pong frame (opcode 0xA) | §5.5.3 | **Compliant** | Auto-pong on ping; dispatched to pongReceived() |
| Control frame max 125 bytes | §5.5 | **Compliant** | Validated in WebSocketFrame |
| Control frame must be FIN | §5.5 | **Compliant** | Validated in WebSocketFrame |

### Closing Handshake (§7)

| Feature | RFC Section | Status | Notes |
|---------|-------------|--------|-------|
| Close frame exchange | §7.1 | **Compliant** | Bidirectional close handshake |
| CONNECTING → OPEN → CLOSING → CLOSED | §7.1 | **Compliant** | State enum tracks lifecycle |
| Close code in payload | §7.1.5 | **Compliant** | 2-byte big-endian code extracted |
| Close reason in payload | §7.1.6 | **Compliant** | UTF-8 string after code |
| Close code validation | §7.4 | **Compliant** | Codes 1004/1005/1006/1015 and out-of-range rejected → close 1002 |
| Close code 1000 (Normal) | §7.4.1 | **Compliant** | Defined in CloseCodes |
| Close code 1001 (Going Away) | §7.4.1 | **Compliant** | Defined in CloseCodes |
| Close code 1002 (Protocol Error) | §7.4.1 | **Compliant** | Used for protocol violations |
| Close code 1003 (Unsupported Data) | §7.4.1 | **Compliant** | Defined in CloseCodes |
| Close code 1009 (Message Too Big) | §7.4.1 | **Compliant** | Sent when max message size exceeded |
| Close code 1010 (Missing Extension) | §7.4.1 | **Compliant** | Defined in CloseCodes |
| Close code 1011 (Internal Error) | §7.4.1 | **Compliant** | Defined in CloseCodes |

---

## MIME — RFC 2045 / 2046 / 2047 / 2183 / 2231 / 5322

### Core Parsing (RFC 2045 / RFC 2046)

| Requirement | Section | Status | Notes |
|---|---|---|---|
| MIME-Version header parsing | RFC 2045 §4 | **Compliant** | `MIMEParser.handleMIMEVersionHeader()` → `MIMEHandler.mimeVersion()` |
| Content-Type header parsing | RFC 2045 §5 | **Compliant** | `MIMEParser.handleContentTypeHeader()` → `ContentTypeParser` |
| Content-Type parameter list (token, quoted-string) | RFC 2045 §5.1 | **Compliant** | `ContentTypeParser.parseParameterList()` with `MIMEUtils.isToken()` |
| Content-Transfer-Encoding header | RFC 2045 §6.1 | **Compliant** | `MIMEParser.handleContentTransferEncodingHeader()` handles 7bit/8bit/binary/base64/qp |
| Quoted-Printable decoding | RFC 2045 §6.7 | **Compliant** | `QuotedPrintableDecoder.decode()` — soft line breaks, hex escape, end-of-stream |
| Base64 decoding | RFC 2045 §6.8 | **Compliant** | `Base64Decoder.decode()` — streaming, handles incomplete quads, skips whitespace |
| Base64 line length validation | RFC 2045 §6.8 | **Compliant** | Optional strict mode rejects lines exceeding 76 characters |
| Content-ID header | RFC 2045 §7 | **Compliant** | `MIMEParser.handleContentIDHeader()` → `ContentID` / `ContentIDParser` |
| Content-Description header | RFC 2045 §8 | **Compliant** | `MIMEParser.handleContentDescriptionHeader()` with RFC 2047 decoding |
| Multipart boundary detection | RFC 2046 §5.1.1 | **Compliant** | `MIMEParser.detectBoundary()` / `checkBoundary()` — delimiter, close-delimiter, LWSP after |
| Boundary validation (1–70 chars) | RFC 2046 §5.1.1 | **Compliant** | `MIMEUtils.isValidBoundary()` |
| Multipart preamble/epilogue | RFC 2046 §5.1.1 | **Compliant** | `MIMEHandler.unexpectedContent()` delivers preamble/epilogue to handler |
| Nested multipart entities | RFC 2046 §5.1 | **Compliant** | `MIMEParser` tracks boundary stack, `startEntity()`/`endEntity()` lifecycle |
| Header line folding (obs-fold) | RFC 5322 §2.2 | **Compliant** | `MIMEParser.headerLine()` handles continuation lines |

### Content-Type / Content-Disposition / Content-ID

| Requirement | Section | Status | Notes |
|---|---|---|---|
| Content-Type structured value | RFC 2045 §5.1 | **Compliant** | `ContentType` — type, subtype, parameters |
| Content-Disposition structured value | RFC 2183 | **Compliant** | `ContentDisposition` — disposition-type, parameters |
| Content-ID structured value | RFC 2045 §7 / RFC 5322 §3.6.4 | **Compliant** | `ContentID` with `ContentIDParser` |
| Parameter continuations (name*0, name*1) | RFC 2231 §3 | **Compliant** | `ContentTypeParser.parseParameterList()` with RFC 2231 decoding |
| Extended parameter values (charset''encoded) | RFC 2231 §4 | **Compliant** | `ContentTypeParser.parseParameterList()` / `RFC2231Decoder` |

### Encoded Words (RFC 2047)

| Requirement | Section | Status | Notes |
|---|---|---|---|
| Encoded-word decoding (=?charset?encoding?text?=) | RFC 2047 §2 | **Compliant** | `RFC2047Decoder` — B and Q encodings |
| Encoded-word encoding | RFC 2047 §2 | **Compliant** | `RFC2047Encoder` — encoded-words limited to 75 characters per RFC 2047 §2 |
| RFC 2231 language in encoded words | RFC 2231 §5 | **Compliant** | `RFC2047Decoder` handles `=?charset*lang?...?=` |

### Internet Message Format (RFC 5322)

| Requirement | Section | Status | Notes |
|---|---|---|---|
| Email message parsing (headers + body) | RFC 5322 §2 | **Compliant** | `MessageParser` extends `MIMEParser` |
| Email address parsing (addr-spec, name-addr) | RFC 5322 §3.4 | **Compliant** | `EmailAddressParser` — mailbox, group, display-name |
| Group email addresses | RFC 5322 §3.4 | **Compliant** | `GroupEmailAddress` |
| Date/time formatting | RFC 5322 §3.3 | **Compliant** | `MessageDateTimeFormatter` |
| Message-ID parsing | RFC 5322 §3.6.4 | **Compliant** | `MessageIDParser` |
| Obsolete syntax tolerance | RFC 5322 §4 | **Compliant** | `ObsoleteParserUtils` — obsolete addresses, message-IDs |

---

## Auth/SASL — RFC 4422

### SASL Mechanisms

| Requirement | Section | Status | Notes |
|---|---|---|---|
| SASL mechanism enumeration | RFC 4422 | **Compliant** | `SASLMechanism` enum; `Realm.getSupportedSASLMechanisms()` |
| PLAIN mechanism | RFC 4616 §2 | **Compliant** | `SASLUtils.parsePlainCredentials()` — authzid NUL authcid NUL password |
| LOGIN mechanism | draft-murchison-sasl-login | **Compliant** | `SASLMechanism.LOGIN` declared; server handlers support it |
| CRAM-MD5 mechanism | RFC 2195 §2 | **Compliant** | `SASLUtils.generateCramMD5Challenge()`, `computeCramMD5Response()`, `verifyCramMD5()` |
| DIGEST-MD5 mechanism | RFC 2831 §2.1 | **Compliant** | `SASLUtils.generateDigestMD5Challenge()`, `parseDigestParams()`, `computeDigestHA1()` |
| SCRAM-SHA-256 mechanism | RFC 5802 §5 / RFC 7677 | **Compliant** | `SASLUtils.generateScramServerFirst()`; `Realm.getScramCredentials()` with PBKDF2 derivation |
| OAUTHBEARER mechanism | RFC 7628 §3.1 | **Compliant** | `SASLUtils.parseOAuthBearerCredentials()` — GS2 header + Bearer token |
| EXTERNAL mechanism | RFC 4422 Appendix A | **Compliant** | `SASLUtils.authenticateExternal()` — certificate extraction + authzid handling |
| APOP mechanism | RFC 1939 | **Compliant** | `Realm.getApopResponse()` |
| Proxy authorization (authzid) | RFC 4422 §4.2 | **Compliant** | `Realm.authorizeAs()` |

### Cryptographic Primitives

| Requirement | Section | Status | Notes |
|---|---|---|---|
| Base64 encoding/decoding | RFC 4648 §4 | **Compliant** | `SASLUtils.encodeBase64()` / `decodeBase64()` |
| HMAC-MD5 | RFC 2104 | **Compliant** | `SASLUtils.hmacMD5()` |
| HMAC-SHA256 | RFC 2104 | **Compliant** | `SASLUtils.hmacSHA256()` |
| MD5 hash | RFC 1321 | **Compliant** | `SASLUtils.md5()` / `md5Hex()` |
| SHA-256 hash | FIPS 180-4 | **Compliant** | `SASLUtils.sha256()` |

### Realm Implementations

| Requirement | Section | Status | Notes |
|---|---|---|---|
| BasicRealm — XML-based credential store | — | **Compliant** | Supports PLAIN, LOGIN, CRAM-MD5, DIGEST-MD5, SCRAM-SHA-256, EXTERNAL |
| LDAPRealm — LDAP simple bind | RFC 4513 §5.1.1 | **Compliant** | `LDAPRealm.passwordMatch()` performs search-then-bind |
| LDAPRealm — LDAP search filter | RFC 4515 | **Compliant** | `LDAPRealm.setUserFilter()` with placeholder substitution |
| LDAPRealm — SASL bind | RFC 4513 §5.2 | **Compliant** | `setSaslMechanism()` enables SASL for service and user binds via `SASLUtils.createClient()` (PLAIN, CRAM-MD5, DIGEST-MD5, EXTERNAL, GSSAPI) |

---

## OAuth 2.0 — RFC 6749 / RFC 7662 / RFC 6750

| Requirement | Section | Status | Notes |
|---|---|---|---|
| Token introspection request | RFC 7662 §2.1 | **Compliant** | `OAuthRealm.validateOAuthToken()` — POST with token + client credentials |
| Token introspection response parsing | RFC 7662 §2.2 | **Compliant** | `IntrospectionResponseHandler` — active, username, sub, scope, exp |
| Bearer token validation | RFC 6750 §2.1 | **Compliant** | `OAuthRealm.validateBearerToken()` delegates to introspection |
| Scope checking | RFC 6749 §3.3 | **Compliant** | `OAuthRealm.hasRequiredScopes()` / `hasRoleByScopes()` with configurable scope-to-role mapping |
| Token caching | — | **Compliant** | Configurable TTL cache with size limits and cleanup |
| Client credential authentication to introspection endpoint | RFC 7662 §2.1 | **Compliant** | Basic auth with client_id / client_secret |
| Local JWT validation | RFC 7519 | **Compliant** | `OAuthRealm.validateJWT()` — HS256, RS256, ES256; exp/nbf/iss/aud claims |
| Token expiration check | RFC 7662 §2.2 (exp field) | **Compliant** | `TokenValidationResult.isExpired()` checks exp timestamp |

---

## SOCKS Proxy

### Applicable RFCs

| RFC | Title | Status |
|-----|-------|--------|
| RFC 1928 | SOCKS Protocol Version 5 | Core |
| RFC 1929 | Username/Password Authentication for SOCKS V5 | Implemented |
| RFC 1961 | GSS-API Authentication Method for SOCKS Version 5 | Implemented |
| — | SOCKS4 Protocol (de facto standard) | Implemented |
| — | SOCKS4a Extension (de facto standard) | Implemented |

---

### RFC 1928 — SOCKS Protocol Version 5

#### Section 3 — Procedure for TCP-based Clients (Method Negotiation)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Client sends VER + NMETHODS + METHODS | §3 | **Compliant** | `SOCKSProtocolHandler.handleSOCKS5MethodNegotiation()` parses client greeting; `SOCKSClientHandler.sendSOCKS5MethodRequest()` sends it |
| Server selects one method and responds VER + METHOD | §3 | **Compliant** | `SOCKSProtocolHandler.sendSOCKS5MethodSelection()` |
| VER must be 0x05 | §3 | **Compliant** | `handleVersionDetect()` checks first byte |
| Method 0x00 — NO AUTHENTICATION REQUIRED | §3 | **Compliant** | `SOCKS5_AUTH_NONE` accepted when no Realm configured |
| Method 0x01 — GSSAPI | §3 | **Compliant** | `SOCKS5_AUTH_GSSAPI` selected when GSSAPIServer available; see RFC 1961 |
| Method 0x02 — USERNAME/PASSWORD | §3 | **Compliant** | `SOCKS5_AUTH_USERNAME_PASSWORD` selected when Realm configured; see RFC 1929 |
| Method 0xFF — NO ACCEPTABLE METHODS | §3 | **Compliant** | Sent when no client-offered methods are supported |
| Method selection priority | §3 | **Compliant** | GSSAPI > USERNAME/PASSWORD > NONE (mirrors server security preference) |

#### Section 4 — Requests

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Request format: VER + CMD + RSV + ATYP + DST.ADDR + DST.PORT | §4 | **Compliant** | `SOCKSProtocolHandler.handleSOCKS5Request()` parses full request; `SOCKSClientHandler.sendSOCKS5ConnectRequest()` constructs it |
| CMD 0x01 CONNECT | §4 | **Compliant** | Full CONNECT flow: resolve → filter → upstream connect → relay |
| CMD 0x02 BIND | §4 | **Compliant** | `SOCKSProtocolHandler.handleBind()` creates `SOCKSBindRelay` for single-use accept; see BIND procedure below |
| CMD 0x03 UDP ASSOCIATE | §4, §7 | **Compliant** | `SOCKSProtocolHandler.handleUDPAssociate()` creates `SOCKSUDPRelay` with per-association UDP ports; see §7 below |
| RSV byte MUST be 0x00 | §4 | **Compliant** | Consumed and ignored on parse; set to 0x00 on send |
| Reply after CONNECT success: relay data bidirectionally | §4 | **Compliant** | `SOCKSRelay` handles bidirectional forwarding after success reply |

#### Section 4 — BIND procedure

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| BIND request uses same format as CONNECT (VER + CMD + RSV + ATYP + DST.ADDR + DST.PORT) | §4 | **Compliant** | Parsed by the same `handleSOCKS5Request()` / `handleSOCKS4Request()` code paths |
| DST.ADDR and DST.PORT used to evaluate the BIND request | §4 | **Compliant** | DST.ADDR used for incoming peer IP validation; custom `BindHandler` receives the full request |
| First reply: BND.ADDR/BND.PORT of the listen socket | §4 | **Compliant** | `SOCKSBindRelay.start()` binds ephemeral port; reply sent via `sendSOCKS5ReplyWithPort` / `sendSOCKS4ReplyWithAddr` |
| Second reply: BND.ADDR/BND.PORT of the connecting peer | §4 | **Compliant** | Sent in `onBindAccepted()` with the accepted peer's address and port |
| After second reply: bidirectional relay | §4 | **Compliant** | Standard `SOCKSRelay` handles data forwarding with backpressure and metrics |
| Server validates incoming peer IP against DST.ADDR | §4 | **Compliant** | `SOCKSBindRelay.accepted()` validates source IP unless DST.ADDR was 0.0.0.0 |

#### Section 5 — Addressing

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| ATYP 0x01 — IPv4 (4 octets) | §5 | **Compliant** | Server and client both handle IPv4 addresses |
| ATYP 0x03 — DOMAINNAME (1-octet length + FQDN) | §5 | **Compliant** | Server resolves via async `DNSResolver`; client sends for proxy-side resolution |
| ATYP 0x04 — IPv6 (16 octets) | §5 | **Compliant** | Server and client both handle IPv6 addresses |
| Unrecognized ATYP | §5 | **Compliant** | Server replies REP=0x08 (address type not supported); client reports error |

#### Section 6 — Replies

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Reply format: VER + REP + RSV + ATYP + BND.ADDR + BND.PORT | §6 | **Compliant** | `SOCKSProtocolHandler.sendSOCKS5Reply()` constructs full reply |
| REP 0x00 — succeeded | §6 | **Compliant** | Sent after upstream connection established |
| REP 0x01 — general SOCKS server failure | §6 | **Compliant** | Used when max relays exceeded or upstream I/O error |
| REP 0x02 — connection not allowed by ruleset | §6 | **Compliant** | Used when CIDR destination filter blocks the request |
| REP 0x03 — network unreachable | §6 | **Defined** | Constant defined in `SOCKSConstants` |
| REP 0x04 — host unreachable | §6 | **Compliant** | Used when DNS resolution fails |
| REP 0x05 — connection refused | §6 | **Compliant** | Used when upstream connect fails |
| REP 0x06 — TTL expired | §6 | **Defined** | Constant defined in `SOCKSConstants` |
| REP 0x07 — command not supported | §6 | **Compliant** | Used for unrecognized command values |
| REP 0x08 — address type not supported | §6 | **Compliant** | Used for unrecognized ATYP values |
| BND.ADDR + BND.PORT in success reply | §6 | **Compliant** | Server-bound address and port included in reply |

#### Section 7 — Procedure for UDP-based clients

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| UDP ASSOCIATE reply contains BND.ADDR + BND.PORT for client-facing UDP | §7 | **Compliant** | `SOCKSProtocolHandler.handleUDPAssociate()` replies with the ephemeral port from `SOCKSUDPRelay` |
| UDP request header: RSV (2) + FRAG (1) + ATYP (1) + DST.ADDR (variable) + DST.PORT (2) + DATA | §7 | **Compliant** | `SOCKSUDPHeader.parse()` and `SOCKSUDPHeader.encode()` implement the full header codec |
| ATYP 0x01 (IPv4), 0x03 (DOMAINNAME), 0x04 (IPv6) in UDP header | §7 | **Compliant** | All three address types parsed and encoded |
| FRAG field: implementations not supporting fragmentation MUST drop datagrams with FRAG != 0x00 | §7 | **Compliant** | `SOCKSUDPRelay` silently drops fragmented datagrams per spec |
| Server MUST know expected source IP and drop datagrams from unexpected sources | §7 | **Compliant** | Source IP validated against DST.ADDR from request (or TCP remote address if 0.0.0.0) |
| Association terminates when TCP control connection terminates | §7 | **Compliant** | `SOCKSProtocolHandler.disconnected()` closes the `SOCKSUDPRelay` when TCP closes |
| Response datagrams encapsulated with UDP request header (source host as DST.ADDR/DST.PORT) | §7 | **Compliant** | `SOCKSUDPRelay.UpstreamHandler` encapsulates responses via `SOCKSUDPHeader.encode()` |
| Server relays datagrams silently, dropping those it cannot or will not relay | §7 | **Compliant** | Blocked destinations and DNS failures result in silent drop |
| DOMAINNAME resolution for UDP destinations | §7 | **Compliant** | Async resolution via `DNSResolver.forLoop()` |

---

### RFC 1929 — Username/Password Authentication for SOCKS V5

#### Section 2 — Sub-negotiation

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Sub-negotiation version 0x01 | §2 | **Compliant** | `SOCKS5_AUTH_USERPASS_VERSION = 0x01` |
| Client sends VER + ULEN + UNAME + PLEN + PASSWD | §2 | **Compliant** | `SOCKSProtocolHandler.handleSOCKS5UsernamePassword()` parses; `SOCKSClientHandler.sendUsernamePassword()` sends |
| ULEN: 1–255 octets | §2 | **Compliant** | Length read as unsigned byte |
| PLEN: 1–255 octets | §2 | **Compliant** | Length read as unsigned byte |
| Server responds VER + STATUS | §2 | **Compliant** | `SOCKSProtocolHandler.sendSOCKS5AuthResult()` |
| STATUS 0x00 = success | §2 | **Compliant** | Proceeds to SOCKS5 request phase |
| STATUS != 0x00 = failure, MUST close connection | §2 | **Compliant** | Server sends failure status and closes; client detects and reports error |
| Credentials verified against Realm | §2 | **Compliant** | `Realm.passwordMatch()` validates credentials |

---

### RFC 1961 — GSS-API Authentication Method for SOCKS Version 5

#### Section 3 — GSS-API Authentication Message Format

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Frame format: VER(0x01) + MTYP + LEN(2) + TOKEN(var) | §3 | **Compliant** | `SOCKSProtocolHandler.handleSOCKS5GSSAPI()` parses; `sendSOCKS5GSSAPIToken()` constructs |
| MTYP 0x01 — authentication message | §3 | **Compliant** | `SOCKS5_GSSAPI_MSG_AUTH = 0x01` |
| MTYP 0x02 — per-message encapsulation | §3, §5 | **Defined** | Constant defined for completeness; per-message encapsulation intentionally unsupported (see §5 below) |

#### Section 4 — Security Context Establishment

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Multi-round-trip token exchange | §4 | **Compliant** | Loop in `handleSOCKS5GSSAPI()` continues until `isContextEstablished()` |
| Server sends final token when context established | §4 | **Compliant** | Server token sent before transitioning to request phase |
| Failure indicated by 0xFF status | §4 | **Compliant** | `sendSOCKS5GSSAPIFailure()` sends VER + 0xFF + LEN=0 |
| Principal extracted after context establishment | §4 | **Compliant** | `Realm.mapKerberosPrincipal()` maps GSS principal to local user |

#### Section 5 — Per-message Encapsulation

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Per-message integrity/confidentiality protection | §5 | **Intentionally unsupported** | Server negotiates `SECURITY_LAYER_NONE` during GSSAPI setup (RFC 4752 §3.1); confidentiality and integrity are provided by TLS at the transport layer. Constant `SOCKS5_GSSAPI_MSG_ENCAPSULATION` defined for completeness. |

---

### SOCKS4 Protocol (de facto standard)

#### Request Format

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Request: VER(0x04) + CD + DSTPORT(2) + DSTIP(4) + USERID + NULL | §Request | **Compliant** | `SOCKSProtocolHandler.handleSOCKS4Request()` parses; `SOCKSClientHandler.sendSOCKS4Connect()` constructs |
| CD=1 CONNECT | §Request | **Compliant** | Full CONNECT flow supported |
| CD=2 BIND | §Request | **Compliant** | `SOCKSProtocolHandler.handleBind()` creates `SOCKSBindRelay` with `RawAcceptHandler`; two-reply flow with peer validation |
| USERID null-terminated | §Request | **Compliant** | `readNullTerminatedString()` in ISO 8859-1 encoding |
| USERID passed through in `SOCKSRequest` | §Request | **Compliant** | Available to `ConnectHandler` for custom authorization |

#### Reply Format

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Reply: VN(0x00) + CD + DSTPORT(2) + DSTIP(4) | §Reply | **Compliant** | `SOCKSProtocolHandler.sendSOCKS4Reply()` |
| CD=0x5a (90) — request granted | §Reply | **Compliant** | Sent after successful upstream connection |
| CD=0x5b (91) — request rejected or failed | §Reply | **Compliant** | Used for all error cases |
| CD=0x5c (92) — identd not reachable | §Reply | **Defined** | Constant defined in `SOCKSConstants`; not sent (no identd integration) |
| CD=0x5d (93) — identd userid mismatch | §Reply | **Defined** | Constant defined in `SOCKSConstants`; not sent (no identd integration) |

---

### SOCKS4a Extension (de facto standard)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| DSTIP = 0.0.0.x (x != 0) triggers server-side DNS | — | **Compliant** | `handleSOCKS4Request()` detects magic IP; hostname follows after userid NULL |
| Hostname appended after USERID NULL terminator | — | **Compliant** | `readNullTerminatedString()` reads hostname |
| Server resolves hostname before connecting | — | **Compliant** | Async resolution via `DNSResolver.forLoop()` |
| Client sends 0.0.0.1 + hostname for domain targets | — | **Compliant** | `SOCKSClientHandler.sendSOCKS4Connect()` uses SOCKS4a for unresolvable/IPv6 destinations |

---

### SOCKS Client (RFC 1928, RFC 1929, SOCKS4/4a)

| Requirement | Section | Status | Notes |
|-------------|---------|--------|-------|
| Client method negotiation (SOCKS5) | RFC 1928 §3 | **Compliant** | `SOCKSClientHandler.sendSOCKS5MethodRequest()` offers NO_AUTH and/or USERNAME_PASSWORD |
| Client username/password auth (SOCKS5) | RFC 1929 §2 | **Compliant** | `SOCKSClientHandler.sendUsernamePassword()` sends sub-negotiation |
| Client CONNECT request (SOCKS5) | RFC 1928 §4 | **Compliant** | `SOCKSClientHandler.sendSOCKS5ConnectRequest()` with IPv4/IPv6/DOMAINNAME |
| Client reply parsing (SOCKS5) | RFC 1928 §6 | **Compliant** | `SOCKSClientHandler.handleSOCKS5Reply()` handles all ATYP variants |
| Client CONNECT request (SOCKS4/4a) | SOCKS4 §Request | **Compliant** | `SOCKSClientHandler.sendSOCKS4Connect()` with SOCKS4a fallback |
| Client reply parsing (SOCKS4) | SOCKS4 §Reply | **Compliant** | `SOCKSClientHandler.handleSOCKS4Reply()` checks CD=0x5a |
| Composable handler wrapping | — | **Compliant** | `SOCKSClientHandler` wraps any `ProtocolHandler` for transparent tunneling |
