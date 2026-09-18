#!/usr/bin/env python3
"""Workstream C.1.6 — internal and remaining legacy acronym renames."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SHIM_FILES = {f"src/org/bluezoo/gumdrop/{p}" for p in [
    "Service.java",
    "http/HTTPService.java",
    "servlet/ServletService.java",
    "webdav/WebDAVService.java",
    "websocket/WebSocketService.java",
    "smtp/SMTPService.java",
    "imap/IMAPService.java",
    "pop3/POP3Service.java",
    "smtp/SimpleRelayService.java",
    "smtp/LocalDeliveryService.java",
    "imap/DefaultIMAPService.java",
    "pop3/DefaultPOP3Service.java",
    "ftp/FTPService.java",
    "ftp/file/AnonymousFTPService.java",
    "ftp/file/RoleBasedFTPService.java",
    "ftp/file/SimpleFTPService.java",
    "dns/DNSService.java",
    "mqtt/MQTTService.java",
    "mqtt/DefaultMQTTService.java",
    "socks/SOCKSService.java",
    "socks/DefaultSOCKSService.java",
    "mdns/MDNSService.java",
    "health/HealthService.java",
    "grpc/server/GrpcService.java",
    "servlet/manager/ManagerContainerService.java",
    "servlet/manager/ManagerContextService.java",
]}

SKIP_DIRS = {".git", "build", "dist", ".cursor"}
SKIP_FILES = {
    "scripts/c16-internal-rename.py",
    "test/junit/src/org/bluezoo/gumdrop/testsupport/Gumdrop3NamingConventionTest.java",
}

TEXT_SUFFIXES = {".java", ".xml", ".properties", ".md", ".html", ".txt", ".rc", ".json"}

ACRONYM_PREFIXES = [
    ("WEBDAV", "Webdav"), ("WebDAV", "Webdav"),
    ("HTTP3", "Http3"), ("HTTP2", "Http2"), ("HTTP", "Http"),
    ("SMTP", "Smtp"), ("IMAP", "Imap"), ("POP3", "Pop3"), ("FTP", "Ftp"),
    ("DNSSEC", "Dnssec"), ("DNS", "Dns"), ("AMQP", "Amqp"), ("MQTT", "Mqtt"),
    ("SOCKS", "Socks"), ("MDNS", "Mdns"), ("GRPC", "Grpc"), ("QUIC", "Quic"),
    ("TLS", "Tls"), ("UDP", "Udp"), ("TCP", "Tcp"), ("JWT", "Jwt"), ("JCA", "Jca"),
    ("HPACK", "Hpack"), ("QPACK", "Qpack"), ("LDAP", "Ldap"), ("MIME", "Mime"),
    ("JSP", "Jsp"), ("OTLP", "Otlp"), ("RFC2047", "Rfc2047"), ("RFC2231", "Rfc2231"),
    ("RFC", "Rfc"), ("BER", "Ber"), ("ASN1", "Asn1"), ("SASL", "Sasl"),
    ("GSSAPI", "Gssapi"), ("RESP", "Resp"), ("DKIM", "Dkim"), ("DMARC", "Dmarc"),
    ("SPF", "Spf"), ("DSN", "Dsn"), ("JSSE", "Jsse"), ("CIDR", "Cidr"),
    ("SPKI", "Spki"), ("DANE", "Dane"), ("DAV", "Dav"), ("DNSSD", "Dnssd"),
]

SHIM_TYPE_NAMES = {
    name.split("/")[-1].replace(".java", "")
    for name in SHIM_FILES
}

MANUAL_RENAMES = {
    # Legacy typo: AMQPLain → AmqpPlain
    "AMQPLainClientMechanism": "AmqpPlainClientMechanism",
    "SOCKSUDPRelay": "SocksUdpRelay",
    "DNSSDAdvertiser": "DnssdAdvertiser",
    "TcpDNSClientTransport": "TcpDnsClientTransport",
    "UdpDNSClientTransport": "UdpDnsClientTransport",
}

TYPE_PATTERN = re.compile(
    r"^\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+)?"
    r"(?:abstract\s+|final\s+)?(?:class|interface|enum)\s+(\w+)",
    re.MULTILINE,
)


def suggest_gumdrop3_name(type_name: str) -> str | None:
    if type_name in MANUAL_RENAMES:
        return MANUAL_RENAMES[type_name]
    if type_name in SHIM_TYPE_NAMES:
        return None
    for legacy_prefix, modern_prefix in ACRONYM_PREFIXES:
        if type_name.startswith(legacy_prefix) and len(type_name) > len(legacy_prefix):
            rest = type_name[len(legacy_prefix):]
            if rest.endswith("Service"):
                return modern_prefix + rest[:-7] + "Server"
            return modern_prefix + rest
    if type_name.endswith("Service") and type_name != "Service":
        return type_name[:-7] + "Server"
    if type_name == "Service":
        return "Server"
    if (type_name.startswith("Server") and type_name.endswith("Handler")
            and len(type_name) > len("Server")):
        return type_name[len("Server"):]
    return None


def discover_renames() -> list[tuple[str, str]]:
    seen: dict[str, str] = {}
    for manual, modern in MANUAL_RENAMES.items():
        seen[manual] = modern
    src_root = ROOT / "src/org/bluezoo/gumdrop"
    for path in src_root.rglob("*.java"):
        content = path.read_text(encoding="utf-8", errors="replace")
        for match in TYPE_PATTERN.finditer(content):
            type_name = match.group(1)
            suggested = suggest_gumdrop3_name(type_name)
            if suggested and suggested != type_name:
                seen[type_name] = suggested
    return sorted(seen.items(), key=lambda item: len(item[0]), reverse=True)


def should_process(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    if rel in SKIP_FILES or rel in SHIM_FILES:
        return False
    for part in path.parts:
        if part in SKIP_DIRS:
            return False
    return path.suffix in TEXT_SUFFIXES or path.name == "build.xml"


def apply_renames(content: str, renames: list[tuple[str, str]]) -> str:
    for old, new in renames:
        content = re.sub(r"\b" + re.escape(old) + r"\b", new, content)
    return content


def safe_rename(path: Path, new_name: str) -> None:
    target = path.with_name(new_name)
    if path.name == new_name:
        return
    if target.exists():
        tmp = path.with_name(f".__c16_rename__.{path.name}")
        path.rename(tmp)
        tmp.rename(target)
    else:
        path.rename(target)


def rename_java_files(renames: list[tuple[str, str]]) -> None:
    for path in list(ROOT.rglob("*.java")):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        rel = path.relative_to(ROOT).as_posix()
        if rel in SKIP_FILES or rel in SHIM_FILES:
            continue
        for old, new in renames:
            if path.name == old + ".java":
                safe_rename(path, new + ".java")
                break


def main() -> None:
    renames = discover_renames()
    if len(sys.argv) > 1 and sys.argv[1] == "--files-only":
        rename_java_files(renames)
        print(f"Renamed Java source files ({len(renames)} type mappings).")
        return

    changed = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or not should_process(path):
            continue
        original = path.read_text(encoding="utf-8", errors="replace")
        updated = apply_renames(original, renames)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    rename_java_files(renames)
    print(f"Updated {changed} files ({len(renames)} type mappings).")


if __name__ == "__main__":
    main()
