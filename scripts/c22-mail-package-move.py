#!/usr/bin/env python3
"""Workstream C.2.2 — mail protocol facade layout (SMTP, IMAP, POP3)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/org/bluezoo/gumdrop"

PROTOCOLS = [
    {
        "name": "smtp",
        "servers": ["SmtpServer", "SimpleRelayServer", "LocalDeliveryServer"],
        "client": "SmtpClient",
        "listener": "SmtpListener",
    },
    {
        "name": "imap",
        "servers": ["ImapServer", "DefaultIMAPServer"],
        "client": "ImapClient",
        "listener": "ImapListener",
    },
    {
        "name": "pop3",
        "servers": ["Pop3Server", "DefaultPOP3Server"],
        "client": "Pop3Client",
        "listener": "Pop3Listener",
    },
]

SKIP_ROOT = {"package-info.java"}
SHIM_SUFFIX = "Service.java"

TYPE_DECL = re.compile(
    r"^\s*public\s+(?:(?:abstract|final)\s+)?(?:class|interface|enum)\s+(\w+)",
    re.MULTILINE,
)
PKG_PRIVATE = re.compile(
    r"^\s*(?:abstract\s+|final\s+)?(?:class|interface|enum)\s+(\w+)",
    re.MULTILINE,
)
CONSTRUCTOR = re.compile(
    r"public\s+(\w+)\s*\((.*?)\)\s*(?:throws\s+[\w.\s,]+)?\s*\{",
    re.DOTALL,
)
IMPORT_LINE = re.compile(r"^import\s+([\w.]+);", re.MULTILINE)


def change_package(content: str, package: str) -> str:
    return re.sub(
        r"^package\s+[\w.]+;",
        f"package {package};",
        content,
        count=1,
        flags=re.MULTILINE,
    )


def detect_kind(content: str, type_name: str) -> str:
    for match in TYPE_DECL.finditer(content):
        if match.group(1) != type_name:
            continue
        line = match.group(0)
        if "interface" in line:
            return "interface"
        if "abstract class" in line:
            return "abstract"
        return "class"
    return "class"


def param_names(param_list: str) -> str:
    if not param_list.strip():
        return ""
    names: list[str] = []
    for part in param_list.split(","):
        part = part.strip()
        if not part:
            continue
        names.append(part.split()[-1])
    return ", ".join(names)


def parse_constructors(content: str, type_name: str) -> list[tuple[str, str]]:
    ctors: list[tuple[str, str]] = []
    for match in CONSTRUCTOR.finditer(content):
        if match.group(1) != type_name:
            continue
        param_list = " ".join(match.group(2).split())
        ctors.append((param_list, param_names(param_list)))
    return ctors


def root_type_names(protocol_dir: Path) -> set[str]:
    names: set[str] = set()
    for path in protocol_dir.iterdir():
        if not path.name.endswith(".java") or path.name in SKIP_ROOT:
            continue
        if path.name.endswith(SHIM_SUFFIX):
            continue
        if path.is_dir():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "extends org.bluezoo.gumdrop." in text and "Option 2" in text:
            continue
        for pat in (TYPE_DECL, PKG_PRIVATE):
            for match in pat.finditer(text):
                names.add(match.group(1))
    return names


def add_root_imports(content: str, protocol: str, server_types: set[str]) -> str:
    root_types = root_type_names(SRC / protocol)
    existing = set(IMPORT_LINE.findall(content))
    needed: list[str] = []
    for name in sorted(root_types):
        if name in server_types:
            continue
        if re.search(r"\b" + re.escape(name) + r"\b", content):
            fqcn = f"org.bluezoo.gumdrop.{protocol}.{name}"
            if fqcn not in existing:
                needed.append(f"import {fqcn};")
    if not needed:
        return content
    marker = f"package org.bluezoo.gumdrop.{protocol}.server;"
    return content.replace(marker, marker + "\n\n" + "\n".join(needed), 1)


def server_reexport(protocol: str, type_name: str, kind: str, ctors: list[tuple[str, str]]) -> str:
    target = f"org.bluezoo.gumdrop.{protocol}.server.{type_name}"
    pkg = f"org.bluezoo.gumdrop.{protocol}"
    if kind == "abstract":
        decl = f"public abstract class {type_name} extends {target}"
        body = ""
    else:
        decl = f"public class {type_name} extends {target}"
        lines = []
        for param_list, arg_list in ctors:
            if param_list:
                lines.append(
                    f"    public {type_name}({param_list}) {{\n"
                    f"        super({arg_list});\n"
                    f"    }}"
                )
            else:
                lines.append(
                    f"    public {type_name}() {{\n"
                    f"        super();\n"
                    f"    }}"
                )
        body = ("\n\n" + "\n\n".join(lines) + "\n") if lines else ""
    return f"""/*
 * {type_name}.java
 * Copyright (C) 2026 Chris Burdess
 */

package {pkg};

/**
 * Protocol-root re-export of {{@link {target}}} (Gumdrop 3 §C.2 Option 2).
 *
 * @see {target}
 * @see CONTRIBUTING.md
 */
{decl} {{{body}}}
"""


def client_reexport(protocol: str, type_name: str, ctors: list[tuple[str, str]]) -> str:
    target = f"org.bluezoo.gumdrop.{protocol}.client.{type_name}"
    pkg = f"org.bluezoo.gumdrop.{protocol}"
    lines = []
    for param_list, arg_list in ctors:
        if param_list:
            lines.append(
                f"    public {type_name}({param_list}) {{\n"
                f"        super({arg_list});\n"
                f"    }}"
            )
        else:
            lines.append(
                f"    public {type_name}() {{\n"
                f"        super();\n"
                f"    }}"
            )
    body = "\n\n".join(lines)
    return f"""/*
 * {type_name}.java
 * Copyright (C) 2026 Chris Burdess
 */

package {pkg};

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {{@link {target}}} (Gumdrop 3 §C.2 Option 2).
 *
 * @see {target}
 * @see CONTRIBUTING.md
 */
public class {type_name} extends {target} {{

{body}
}}
"""


def fix_listener_server_type(protocol: str, listener: str, server_type: str) -> None:
    path = SRC / protocol / f"{listener}.java"
    server_fqn = f"org.bluezoo.gumdrop.{protocol}.server.{server_type}"
    content = path.read_text(encoding="utf-8")
    content = re.sub(
        rf"private {server_type} service;",
        f"private {server_fqn} service;",
        content,
    )
    content = content.replace(
        f"void setService({server_type} service)",
        f"void setService({server_fqn} service)",
    )
    content = content.replace(
        f"public {server_type} getService()",
        f"public {server_fqn} getService()",
    )
    path.write_text(content, encoding="utf-8")


def write_server_package_info(protocol: str) -> None:
    path = SRC / protocol / "server" / "package-info.java"
    pkg = f"org.bluezoo.gumdrop.{protocol}"
    path.write_text(
        f"/**\n"
        f" * {protocol.upper()} server-side facades. Primary entry types are re-exported\n"
        f" * at {{@link {pkg}}} for ergonomics (§C.2 Option 2).\n"
        f" */\n"
        f"package {pkg}.server;\n",
        encoding="utf-8",
    )


def move_protocol(cfg: dict) -> None:
    protocol = cfg["name"]
    protocol_dir = SRC / protocol
    server_dir = protocol_dir / "server"
    server_dir.mkdir(parents=True, exist_ok=True)
    server_types = set(cfg["servers"])

    for type_name in cfg["servers"]:
        src = protocol_dir / f"{type_name}.java"
        content = src.read_text(encoding="utf-8")
        if "Option 2" in content and "extends org.bluezoo.gumdrop." in content:
            continue
        kind = detect_kind(content, type_name)
        ctors = parse_constructors(content, type_name)
        impl = change_package(content, f"org.bluezoo.gumdrop.{protocol}.server")
        impl = add_root_imports(impl, protocol, server_types)
        (server_dir / f"{type_name}.java").write_text(impl, encoding="utf-8")
        src.write_text(server_reexport(protocol, type_name, kind, ctors), encoding="utf-8")

    client_src = protocol_dir / "client" / f"{cfg['client']}.java"
    ctors = parse_constructors(client_src.read_text(encoding="utf-8"), cfg["client"])
    (protocol_dir / f"{cfg['client']}.java").write_text(
        client_reexport(protocol, cfg["client"], ctors), encoding="utf-8"
    )

    fix_listener_server_type(protocol, cfg["listener"], cfg["servers"][0])
    write_server_package_info(protocol)


def main() -> None:
    for cfg in PROTOCOLS:
        move_protocol(cfg)
        print(f"Moved {cfg['name']} server facades + {cfg['client']} re-export")


if __name__ == "__main__":
    main()
