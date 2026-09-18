#!/usr/bin/env python3
"""Workstream C.2.3 — remaining protocol facade layout (FTP, DNS, MQTT, …)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/org/bluezoo/gumdrop"

PROTOCOLS = [
    {
        "name": "ftp",
        "servers": [
            ("FtpServer.java", "FtpServer.java"),
            ("file/AnonymousFTPServer.java", "file/AnonymousFTPServer.java"),
            ("file/RoleBasedFTPServer.java", "file/RoleBasedFTPServer.java"),
            ("file/SimpleFTPServer.java", "file/SimpleFTPServer.java"),
        ],
        "listeners": ["FtpListener"],
        "base_server": "FtpServer",
        "client": "FtpClient",
    },
    {
        "name": "dns",
        "servers": [("DnsServer.java", "DnsServer.java")],
        "listeners": ["DnsListener", "DoTListener", "DoQListener"],
        "base_server": "DnsServer",
    },
    {
        "name": "mqtt",
        "servers": [
            ("MqttServer.java", "MqttServer.java"),
            ("DefaultMQTTServer.java", "DefaultMQTTServer.java"),
        ],
        "listeners": ["MqttListener"],
        "base_server": "MqttServer",
        "client": "MqttClient",
    },
    {
        "name": "socks",
        "servers": [
            ("SocksServer.java", "SocksServer.java"),
            ("DefaultSOCKSServer.java", "DefaultSOCKSServer.java"),
        ],
        "listeners": ["SocksListener"],
        "base_server": "SocksServer",
    },
    {
        "name": "mdns",
        "servers": [("MdnsServer.java", "MdnsServer.java")],
        "listeners": ["MdnsListener"],
        "base_server": "MdnsServer",
    },
    {
        "name": "health",
        "servers": [("HealthServer.java", "HealthServer.java")],
        "listeners": [],
        "base_server": "HealthServer",
    },
]

SHIM_SUFFIX = "Service.java"
TOP_LEVEL_TYPE = re.compile(
    r"^[^\S\n]*(?:public\s+|protected\s+|private\s+)?"
    r"(?:(?:abstract|final|static)\s+)*"
    r"(?:class|interface|enum)\s+(\w+)",
    re.MULTILINE,
)
TYPE_DECL = re.compile(
    r"^[^\S\n]*public\s+(?:(?:abstract|final)\s+)?(?:class|interface|enum)\s+(\w+)",
    re.MULTILINE,
)
CONSTRUCTOR = re.compile(
    r"public\s+(\w+)\s*\((.*?)\)\s*(?:throws\s+[\w.\s,]+)?\s*\{",
    re.DOTALL,
)
IMPORT_LINE = re.compile(r"^import\s+([\w.]+);", re.MULTILINE)
CREATE_HANDLER = re.compile(
    r"^\s*(protected|public)\s+(?:abstract\s+)?[\w<>,\s]+\s+createHandler\s*\(",
    re.MULTILINE,
)
SET_SERVICE = re.compile(
    r"^(\s*)void setService\(",
    re.MULTILINE,
)


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
    return ", ".join(part.strip().split()[-1] for part in param_list.split(",") if part.strip())


def parse_constructors(content: str, type_name: str) -> list[tuple[str, str]]:
    ctors: list[tuple[str, str]] = []
    for match in CONSTRUCTOR.finditer(content):
        if match.group(1) != type_name:
            continue
        param_list = " ".join(match.group(2).split())
        ctors.append((param_list, param_names(param_list)))
    return ctors


def reexport_package(protocol: str, reexport_rel: str) -> str:
    base = f"org.bluezoo.gumdrop.{protocol}"
    if "/" in reexport_rel:
        sub = reexport_rel.rsplit("/", 1)[0].replace("/", ".")
        return f"{base}.{sub}"
    return base


def protocol_type_names(protocol_dir: Path, server_names: set[str]) -> set[str]:
    names: set[str] = set()
    if not protocol_dir.is_dir():
        return names
    for path in protocol_dir.rglob("*.java"):
        rel = path.relative_to(protocol_dir).as_posix()
        if rel.startswith("server/") or rel.endswith(SHIM_SUFFIX):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "Option 2" in text and "extends org.bluezoo.gumdrop." in text:
            continue
        for match in TOP_LEVEL_TYPE.finditer(text):
            if match.group(1) not in server_names:
                names.add(match.group(1))
    return names


def add_protocol_imports(content: str, protocol: str, server_names: set[str]) -> str:
    protocol_dir = SRC / protocol
    known = protocol_type_names(protocol_dir, server_names)
    existing = set(IMPORT_LINE.findall(content))
    needed: list[str] = []
    for name in sorted(known):
        if name in server_names:
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", content):
            continue
        for path in protocol_dir.rglob("*.java"):
            rel = path.relative_to(protocol_dir).as_posix()
            if rel.startswith("server/"):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if TOP_LEVEL_TYPE.search(text) and re.search(
                r"^[^\S\n]*(?:public\s+|protected\s+|private\s+)?"
                r"(?:(?:abstract|final|static)\s+)*"
                r"(?:class|interface|enum)\s+" + re.escape(name) + r"\b",
                text,
                re.MULTILINE,
            ):
                sub = rel.rsplit("/", 1)[0].replace("/", ".")
                pkg = f"org.bluezoo.gumdrop.{protocol}"
                if sub and sub != name:
                    pkg = f"{pkg}.{sub}" if not sub.endswith(".java") else pkg
                if "/" in rel:
                    pkg = f"org.bluezoo.gumdrop.{protocol}.{rel.rsplit('/', 1)[0].replace('/', '.')}"
                else:
                    pkg = f"org.bluezoo.gumdrop.{protocol}"
                fqcn = f"{pkg}.{name}"
                if fqcn not in existing:
                    needed.append(f"import {fqcn};")
                break
    if not needed:
        return content
    marker = f"package org.bluezoo.gumdrop.{protocol}.server;"
    return content.replace(marker, marker + "\n\n" + "\n".join(sorted(set(needed))), 1)


def server_reexport(protocol: str, type_name: str, kind: str,
                    ctors: list[tuple[str, str]], reexport_rel: str) -> str:
    target = f"org.bluezoo.gumdrop.{protocol}.server.{type_name}"
    pkg = reexport_package(protocol, reexport_rel)
    if kind == "abstract":
        decl = f"public abstract class {type_name} extends {target}"
        body = ""
    elif kind == "interface":
        decl = f"public interface {type_name} extends {target}"
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
                lines.append(f"    public {type_name}() {{\n        super();\n    }}")
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
            lines.append(f"    public {type_name}() {{\n        super();\n    }}")
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

{chr(10).join(lines)}
}}
"""


def fix_listener(protocol: str, listener: str, base_server: str) -> None:
    path = SRC / protocol / f"{listener}.java"
    if not path.exists():
        return
    server_fqn = f"org.bluezoo.gumdrop.{protocol}.server.{base_server}"
    content = path.read_text(encoding="utf-8")
    content = re.sub(
        rf"private {base_server} service;",
        f"private {server_fqn} service;",
        content,
    )
    content = content.replace(
        f"void setService({base_server} service)",
        f"public void setService({server_fqn} service)",
    )
    content = content.replace(
        f"public void setService({base_server} service)",
        f"public void setService({server_fqn} service)",
    )
    content = content.replace(
        f"public {base_server} getService()",
        f"public {server_fqn} getService()",
    )
    path.write_text(content, encoding="utf-8")


def publicize_create_handlers(protocol_dir: Path) -> None:
    server_dir = protocol_dir / "server"
    if not server_dir.is_dir():
        return
    for path in server_dir.glob("*.java"):
        content = path.read_text(encoding="utf-8")
        updated = CREATE_HANDLER.sub(
            lambda m: m.group(0).replace("protected", "public", 1),
            content,
        )
        if updated != content:
            path.write_text(updated, encoding="utf-8")


def write_server_package_info(protocol: str) -> None:
    path = SRC / protocol / "server" / "package-info.java"
    pkg = f"org.bluezoo.gumdrop.{protocol}"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f"/**\n"
        f" * {protocol.upper()} server-side facades. Primary entry types are re-exported\n"
        f" * at {{@link {pkg}}} (and subpackages where applicable) for ergonomics\n"
        f" * (§C.2 Option 2).\n"
        f" */\n"
        f"package {pkg}.server;\n",
        encoding="utf-8",
    )


def move_protocol(cfg: dict) -> None:
    protocol = cfg["name"]
    protocol_dir = SRC / protocol
    server_dir = protocol_dir / "server"
    server_dir.mkdir(parents=True, exist_ok=True)
    server_names = {Path(src).stem for src, _ in cfg["servers"]}

    for source_rel, reexport_rel in cfg["servers"]:
        type_name = Path(source_rel).stem
        src = protocol_dir / source_rel
        content = src.read_text(encoding="utf-8")
        if "Option 2" in content and "extends org.bluezoo.gumdrop." in content:
            continue
        kind = detect_kind(content, type_name)
        ctors = parse_constructors(content, type_name)
        impl = change_package(content, f"org.bluezoo.gumdrop.{protocol}.server")
        impl = add_protocol_imports(impl, protocol, server_names)
        (server_dir / f"{type_name}.java").write_text(impl, encoding="utf-8")
        reexport_path = protocol_dir / reexport_rel
        reexport_path.parent.mkdir(parents=True, exist_ok=True)
        reexport_path.write_text(
            server_reexport(protocol, type_name, kind, ctors, reexport_rel),
            encoding="utf-8",
        )

    client = cfg.get("client")
    if client:
        client_src = protocol_dir / "client" / f"{client}.java"
        if client_src.exists() and not (protocol_dir / f"{client}.java").exists():
            ctors = parse_constructors(client_src.read_text(encoding="utf-8"), client)
            (protocol_dir / f"{client}.java").write_text(
                client_reexport(protocol, client, ctors), encoding="utf-8"
            )
        elif client_src.exists():
            ctors = parse_constructors(client_src.read_text(encoding="utf-8"), client)
            (protocol_dir / f"{client}.java").write_text(
                client_reexport(protocol, client, ctors), encoding="utf-8"
            )

    for listener in cfg.get("listeners", []):
        fix_listener(protocol, listener, cfg["base_server"])

    publicize_create_handlers(protocol_dir)
    write_server_package_info(protocol)


def main() -> None:
    for cfg in PROTOCOLS:
        move_protocol(cfg)
        client = cfg.get("client")
        extra = f" + {client} re-export" if client else ""
        print(f"Moved {cfg['name']} server facades{extra}")


if __name__ == "__main__":
    main()
