#!/usr/bin/env python3
"""Workstream C.1.5 — remaining protocol renames (FTP, DNS, MQTT, AMQP, …)."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Legacy names kept as @Deprecated shims (prior slices + this slice).
SHIM_LEGACY_NAMES = set()  # shims are skipped via SHIM_FILES only

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
    "scripts/c15-remaining-rename.py",
    "test/junit/src/org/bluezoo/gumdrop/testsupport/Gumdrop3NamingConventionTest.java",
}

TEXT_SUFFIXES = {".java", ".xml", ".properties", ".md", ".html", ".txt", ".rc", ".json"}

HANDLER_PACKAGE_MARKERS = (
    "/ftp/", "/amqp/", "/grpc/", "/socks/", "/mqtt/", "/redis/",
    "src/org/bluezoo/gumdrop/ftp/",
    "src/org/bluezoo/gumdrop/amqp/",
    "src/org/bluezoo/gumdrop/grpc/",
    "src/org/bluezoo/gumdrop/socks/",
    "src/org/bluezoo/gumdrop/mqtt/",
    "src/org/bluezoo/gumdrop/redis/",
)

HANDLER_RENAMES = [
    ("ServerChannelCloseHandler", "ChannelCloseHandler"),
    ("ServerChannelOpenHandler", "ChannelOpenHandler"),
    ("ServerExchangeDeclareHandler", "ExchangeDeclareHandler"),
    ("ServerQueueDeclareHandler", "QueueDeclareHandler"),
    ("ServerConfirmSelectHandler", "ConfirmSelectHandler"),
    ("ServerSimpleReplyHandler", "SimpleReplyHandler"),
    ("ServerAuthTlsReplyHandler", "AuthTlsReplyHandler"),
    ("ServerAcctReplyHandler", "AcctReplyHandler"),
    ("ServerQueueBindHandler", "QueueBindHandler"),
    ("ServerTxRollbackHandler", "TxRollbackHandler"),
    ("ServerTxCommitHandler", "TxCommitHandler"),
    ("ServerTxSelectHandler", "TxSelectHandler"),
    ("ServerConsumeHandler", "ConsumeHandler"),
    ("ServerPasvReplyHandler", "PasvReplyHandler"),
    ("ServerPortReplyHandler", "PortReplyHandler"),
    ("ServerEpsvReplyHandler", "EpsvReplyHandler"),
    ("ServerCwdReplyHandler", "CwdReplyHandler"),
    ("ServerListReplyHandler", "ListReplyHandler"),
    ("ServerMkdReplyHandler", "MkdReplyHandler"),
    ("ServerPassReplyHandler", "PassReplyHandler"),
    ("ServerPwdReplyHandler", "PwdReplyHandler"),
    ("ServerRetrReplyHandler", "RetrReplyHandler"),
    ("ServerStorReplyHandler", "StorReplyHandler"),
    ("ServerCancelHandler", "CancelHandler"),
    ("ServerCloseHandler", "CloseHandler"),
    ("ServerFlowHandler", "FlowHandler"),
    ("ServerOpenHandler", "OpenHandler"),
    ("ServerTuneHandler", "TuneHandler"),
    ("ServerUserReplyHandler", "UserReplyHandler"),
    ("ServerReplyHandler", "ReplyHandler"),
    ("ServerGreeting", "RemoteGreeting"),
]


def load_global_renames() -> list[tuple[str, str]]:
    return []


def should_process(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    if rel in SKIP_FILES or rel in SHIM_FILES:
        return False
    for part in path.parts:
        if part in SKIP_DIRS:
            return False
    return path.suffix in TEXT_SUFFIXES or path.name == "build.xml"


def is_handler_scope(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    return any(marker in rel for marker in HANDLER_PACKAGE_MARKERS)


def apply_renames(content: str, renames: list[tuple[str, str]]) -> str:
    for old, new in renames:
        content = re.sub(r"\b" + re.escape(old) + r"\b", new, content)
    return content


def safe_rename(path: Path, new_name: str) -> None:
    target = path.with_name(new_name)
    if path.name == new_name:
        return
    if target.exists():
        tmp = path.with_name(f".__c15_rename__.{path.name}")
        path.rename(tmp)
        tmp.rename(target)
    else:
        path.rename(target)


def rename_java_files(renames: list[tuple[str, str]], scope_check=None) -> None:
    for path in list(ROOT.rglob("*.java")):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        rel = path.relative_to(ROOT).as_posix()
        if rel in SKIP_FILES or rel in SHIM_FILES:
            continue
        if scope_check is not None and not scope_check(path):
            continue
        for old, new in renames:
            if path.name == old + ".java":
                safe_rename(path, new + ".java")
                break


def main() -> None:
    global_renames = load_global_renames()
    if len(sys.argv) > 1 and sys.argv[1] == "--files-only":
        rename_java_files(global_renames)
        rename_java_files(HANDLER_RENAMES, is_handler_scope)
        print("Renamed Java source files.")
        return

    changed = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or not should_process(path):
            continue
        original = path.read_text(encoding="utf-8", errors="replace")
        updated = apply_renames(original, global_renames)
        if is_handler_scope(path):
            updated = apply_renames(updated, HANDLER_RENAMES)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    rename_java_files(global_renames)
    rename_java_files(HANDLER_RENAMES, is_handler_scope)
    print(f"Updated {changed} files.")


if __name__ == "__main__":
    main()
