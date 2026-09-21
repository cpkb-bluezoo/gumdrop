#!/usr/bin/env python3
"""Workstream C.1.4 — SMTP / IMAP / POP3 mail protocol renames."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Protocol and server renames — safe project-wide (mail-specific type names).
GLOBAL_RENAMES = [
    ("IMAPClientProtocolHandler", "ImapClientProtocolHandler"),
    ("POP3ClientProtocolHandler", "Pop3ClientProtocolHandler"),
    ("SMTPClientProtocolHandler", "SmtpClientProtocolHandler"),
    ("IMAPMessageDescriptor", "ImapMessageDescriptor"),
    ("SMTPConnectionMetadata", "SmtpConnectionMetadata"),
    ("DefaultIMAPService", "DefaultIMAPServer"),
    ("DefaultPOP3Service", "DefaultPOP3Server"),
    ("LocalDeliveryService", "LocalDeliveryServer"),
    ("SimpleRelayService", "SimpleRelayServer"),
    ("IMAPServerMetrics", "ImapServerMetrics"),
    ("POP3ServerMetrics", "Pop3ServerMetrics"),
    ("SMTPServerMetrics", "SmtpServerMetrics"),
    ("IMAPProtocolHandler", "ImapProtocolHandler"),
    ("POP3ProtocolHandler", "Pop3ProtocolHandler"),
    ("SMTPProtocolHandler", "SmtpProtocolHandler"),
    ("IMAPListener", "ImapListener"),
    ("POP3Listener", "Pop3Listener"),
    ("SMTPListener", "SmtpListener"),
    ("IMAPService", "ImapServer"),
    ("POP3Service", "Pop3Server"),
    ("SMTPService", "SmtpServer"),
    ("IMAPClient", "ImapClient"),
    ("POP3Client", "Pop3Client"),
    ("SMTPClient", "SmtpClient"),
    ("POP3Exception", "Pop3Exception"),
    ("SMTPException", "SmtpException"),
    ("SMTPPipeline", "SmtpPipeline"),
]

# Client reply handlers — mail packages only (longest names first).
MAIL_HANDLER_RENAMES = [
    ("ServerStarttlsReplyHandler", "StarttlsReplyHandler"),
    ("ServerCapabilityReplyHandler", "CapabilityReplyHandler"),
    ("ServerMessageReplyHandler", "MessageReplyHandler"),
    ("ServerConfirmSelectHandler", "ConfirmSelectHandler"),
    ("ServerMailFromReplyHandler", "MailFromReplyHandler"),
    ("ServerNamespaceReplyHandler", "NamespaceReplyHandler"),
    ("ServerAppendReplyHandler", "AppendReplyHandler"),
    ("ServerAuthAbortHandler", "AuthAbortHandler"),
    ("ServerAuthReplyHandler", "AuthReplyHandler"),
    ("ServerAuthTlsReplyHandler", "AuthTlsReplyHandler"),
    ("ServerCloseReplyHandler", "CloseReplyHandler"),
    ("ServerCopyReplyHandler", "CopyReplyHandler"),
    ("ServerDataReplyHandler", "DataReplyHandler"),
    ("ServerDeleReplyHandler", "DeleReplyHandler"),
    ("ServerEhloReplyHandler", "EhloReplyHandler"),
    ("ServerExpungeReplyHandler", "ExpungeReplyHandler"),
    ("ServerFetchReplyHandler", "FetchReplyHandler"),
    ("ServerHeloReplyHandler", "HeloReplyHandler"),
    ("ServerIdleEventHandler", "IdleEventHandler"),
    ("ServerListReplyHandler", "ListReplyHandler"),
    ("ServerLoginReplyHandler", "LoginReplyHandler"),
    ("ServerMailboxReplyHandler", "MailboxReplyHandler"),
    ("ServerMessageReplyHandler", "MessageReplyHandler"),
    ("ServerNoopReplyHandler", "NoopReplyHandler"),
    ("ServerQuotaReplyHandler", "QuotaReplyHandler"),
    ("ServerRcptToReplyHandler", "RcptToReplyHandler"),
    ("ServerRetrReplyHandler", "RetrReplyHandler"),
    ("ServerRsetReplyHandler", "RsetReplyHandler"),
    ("ServerSearchReplyHandler", "SearchReplyHandler"),
    ("ServerSelectReplyHandler", "SelectReplyHandler"),
    ("ServerStatusReplyHandler", "StatusReplyHandler"),
    ("ServerStoreReplyHandler", "StoreReplyHandler"),
    ("ServerApopReplyHandler", "ApopReplyHandler"),
    ("ServerCapaReplyHandler", "CapaReplyHandler"),
    ("ServerPassReplyHandler", "PassReplyHandler"),
    ("ServerStatReplyHandler", "StatReplyHandler"),
    ("ServerStlsReplyHandler", "StlsReplyHandler"),
    ("ServerTopReplyHandler", "TopReplyHandler"),
    ("ServerUidlReplyHandler", "UidlReplyHandler"),
    ("ServerUserReplyHandler", "UserReplyHandler"),
    ("ServerReplyHandler", "ReplyHandler"),
    ("ServerGreeting", "RemoteGreeting"),
]

SKIP_DIRS = {".git", "build", "dist", ".cursor"}
SKIP_FILES = {
    "scripts/c14-mail-rename.py",
    "src/org/bluezoo/gumdrop/smtp/SMTPService.java",
    "src/org/bluezoo/gumdrop/imap/IMAPService.java",
    "src/org/bluezoo/gumdrop/pop3/POP3Service.java",
    "src/org/bluezoo/gumdrop/smtp/SimpleRelayService.java",
    "src/org/bluezoo/gumdrop/smtp/LocalDeliveryService.java",
    "src/org/bluezoo/gumdrop/imap/DefaultIMAPService.java",
    "src/org/bluezoo/gumdrop/pop3/DefaultPOP3Service.java",
}

TEXT_SUFFIXES = {".java", ".xml", ".properties", ".md", ".html", ".txt", ".rc", ".json"}
MAIL_PREFIXES = ("src/org/bluezoo/gumdrop/smtp/", "src/org/bluezoo/gumdrop/imap/",
                 "src/org/bluezoo/gumdrop/pop3/")


def should_process(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    if rel in SKIP_FILES:
        return False
    for part in path.parts:
        if part in SKIP_DIRS:
            return False
    return path.suffix in TEXT_SUFFIXES or path.name == "build.xml"


def is_mail_test(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    return any(x in rel for x in ("/smtp/", "/imap/", "/pop3/"))


def is_mail_source(path: Path) -> bool:
    rel = path.relative_to(ROOT).as_posix()
    return any(rel.startswith(p) for p in MAIL_PREFIXES)


def apply_renames(content: str, renames: list[tuple[str, str]]) -> str:
    for old, new in renames:
        content = re.sub(r"\b" + re.escape(old) + r"\b", new, content)
    return content


def safe_rename(path: Path, new_name: str) -> None:
    target = path.with_name(new_name)
    if path.name == new_name:
        return
    if target.exists():
        tmp = path.with_name(f".__c14_rename__.{path.name}")
        path.rename(tmp)
        tmp.rename(target)
    else:
        path.rename(target)


def rename_java_files(renames: list[tuple[str, str]], mail_only: bool = False) -> None:
    for path in list(ROOT.rglob("*.java")):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        rel = path.relative_to(ROOT).as_posix()
        if rel in SKIP_FILES:
            continue
        if mail_only and not is_mail_source(path):
            continue
        for old, new in renames:
            if path.name == old + ".java":
                safe_rename(path, new + ".java")
                break


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "--files-only":
        rename_java_files(GLOBAL_RENAMES)
        rename_java_files(MAIL_HANDLER_RENAMES, mail_only=True)
        print("Renamed Java source files.")
        return

    changed = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or not should_process(path):
            continue
        original = path.read_text(encoding="utf-8", errors="replace")
        updated = apply_renames(original, GLOBAL_RENAMES)
    if is_mail_source(path) or is_mail_test(path):
            updated = apply_renames(updated, MAIL_HANDLER_RENAMES)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    rename_java_files(GLOBAL_RENAMES)
    rename_java_files(MAIL_HANDLER_RENAMES, mail_only=True)
    print(f"Updated {changed} files.")


if __name__ == "__main__":
    main()
