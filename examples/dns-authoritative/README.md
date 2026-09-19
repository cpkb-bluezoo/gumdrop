# Authoritative DNS Example

This example runs an **authoritative** DNS server (not a caching forwarder) for
`example.com` using a BIND-style zone file and `AuthoritativeZoneHandler`.

## Features demonstrated

- `DnsServer.compose()` with `AuthoritativeZoneHandler`
- Zone file with SOA, NS, A, MX, and wildcard (`*`) records
- Non-privileged port 5353 (no root required)

## Build

From the gumdrop tree root:

```bash
ant build
javac -cp build/core examples/dns-authoritative/AuthoritativeDnsExample.java
```

## Run

From the gumdrop tree root (so the default zone path resolves):

```bash
java -cp build/core:examples/dns-authoritative AuthoritativeDnsExample
```

Or pass an explicit zone file:

```bash
java -cp build/core:examples/dns-authoritative AuthoritativeDnsExample /path/to/example.com.zone
```

## Query with dig

```bash
dig @127.0.0.1 -p 5353 www.example.com A +norecurse
dig @127.0.0.1 -p 5353 example.com MX +norecurse
dig @127.0.0.1 -p 5353 anything.example.com A +norecurse
dig @127.0.0.1 -p 5353 outside.test A +norecurse
```

The last query should receive `REFUSED` (not authoritative for that name).

## Zone file format

See `example.com.zone`. Supported directives and types:

- `$ORIGIN`, `$TTL`, `$INCLUDE` (path relative to the including file)
- SOA, NS, A, AAAA, CNAME, MX, TXT, PTR
- Wildcard owner `*` (becomes `*.origin`)

For a caching resolver that forwards to upstream servers, use
`UpstreamRelayHandler` instead (documented in `web/dns.html`).
