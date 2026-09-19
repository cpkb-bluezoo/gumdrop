#!/usr/bin/env python3
"""Replace English-copy values in L10N_fr/de/es with translations."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GUMDROP = ROOT / "src/org/bluezoo/gumdrop"
LOCALES = ("fr", "es", "de")

sys.path.insert(0, str(Path(__file__).resolve().parent))
from l10n_operator_overrides import OVERRIDES_EXTRA  # noqa: E402


def escape_fr(s: str) -> str:
    return s.replace("'", "''")


def norm_dash(s: str) -> str:
    return s.replace("\u2014", "-").replace("\u2013", "-")


# English -> (fr, es, de); French values use '' for apostrophes in properties files.
OVERRIDES: dict[str, tuple[str, str, str]] = {
    "  -> {0}": ("  -> {0}", "  -> {0}", "  -> {0}"),
    " (offset: {0})": (" (décalage : {0})", " (offset: {0})", " (Offset: {0})"),
    "{0} bytes": ("{0} octets", "{0} bytes", "{0} Bytes"),
    "{0} KB": ("{0} Ko", "{0} KB", "{0} KB"),
    "{0} MB": ("{0} Mo", "{0} MB", "{0} MB"),
    "{0} GB": ("{0} Go", "{0} GB", "{0} GB"),
    "{0} TB": ("{0} To", "{0} TB", "{0} TB"),
    "{0} {1}": ("{0} {1}", "{0} {1}", "{0} {1}"),
    "Name": ("Nom", "Nombre", "Name"),
    "Servlets": ("Servlets", "Servlets", "Servlets"),
    "Total": ("Total", "Total", "Total"),
    "Session": ("Session", "Sesión", "Sitzung"),
    "Error": ("Erreur", "Error", "Fehler"),
    "DELE": ("DELE", "DELE", "DELE"),
    "LIST": ("LIST", "LIST", "LIST"),
    "RETR": ("RETR", "RETR", "RETR"),
    "STOR": ("STOR", "STOR", "STOR"),
    "RNFR/RNTO": ("RNFR/RNTO", "RNFR/RNTO", "RNFR/RNTO"),
    "Jan  1 00:00": (" 1 jan 00:00", " 1 ene 00:00", " 1 Jan 00:00"),
    "EPSV ALL OK": ("EPSV ALL OK", "EPSV ALL OK", "EPSV ALL OK"),
    "raw acceptor": ("accepteur brut", "aceptador en bruto", "Roh-Acceptor"),
    "jdk.net.ExtendedSocketOptions not available": (
        "jdk.net.ExtendedSocketOptions non disponible",
        "jdk.net.ExtendedSocketOptions no disponible",
        "jdk.net.ExtendedSocketOptions nicht verfügbar",
    ),
}
OVERRIDES.update(OVERRIDES_EXTRA)


def _failed_to_send(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Échec de l'envoi de {rest}"),
        f"Error al enviar {rest}",
        f"Senden von {rest} fehlgeschlagen",
    )


def _failed_to_complete(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Échec de l'achèvement de {rest}"),
        f"Error al completar {rest}",
        f"Abschließen von {rest} fehlgeschlagen",
    )


def _failed_to_start(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Échec du démarrage de {rest}"),
        f"Error al iniciar {rest}",
        f"Starten von {rest} fehlgeschlagen",
    )


def _failed_to(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Échec de {rest}"),
        f"Error al {rest.lower() if rest[0].isupper() else rest}",
        f"{rest} fehlgeschlagen",
    )


def _error_sending(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Erreur lors de l'envoi de {rest}"),
        f"Error al enviar {rest}",
        f"Fehler beim Senden von {rest}",
    )


def _error_closing(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Erreur lors de la fermeture de {rest}"),
        f"Error al cerrar {rest}",
        f"Fehler beim Schließen von {rest}",
    )


def _error_during(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Erreur pendant {rest}"),
        f"Error durante {rest}",
        f"Fehler während {rest}",
    )


def _error_in(rest: str) -> tuple[str, str, str]:
    return (
        escape_fr(f"Erreur dans {rest}"),
        f"Error en {rest}",
        f"Fehler in {rest}",
    )


def _error_generic(rest: str) -> tuple[str, str, str]:
    low = rest.lower()
    if low.startswith("processing "):
        tail = rest[11:]
        return (
            escape_fr(f"Erreur lors du traitement de {tail}"),
            f"Error al procesar {tail}",
            f"Fehler bei der Verarbeitung von {tail}",
        )
    if low.startswith("handling "):
        tail = rest[9:]
        return (
            escape_fr(f"Erreur lors du traitement de {tail}"),
            f"Error al manejar {tail}",
            f"Fehler bei der Behandlung von {tail}",
        )
    if low.startswith("writing "):
        tail = rest[8:]
        return (
            escape_fr(f"Erreur lors de l'écriture de {tail}"),
            f"Error al escribir {tail}",
            f"Fehler beim Schreiben von {tail}",
        )
    if low.startswith("reading "):
        tail = rest[8:]
        return (
            escape_fr(f"Erreur lors de la lecture de {tail}"),
            f"Error al leer {tail}",
            f"Fehler beim Lesen von {tail}",
        )
    if low.startswith("accepting "):
        tail = rest[10:]
        return (
            escape_fr(f"Erreur lors de l'acceptation de {tail}"),
            f"Error al aceptar {tail}",
            f"Fehler beim Annehmen von {tail}",
        )
    if low.startswith("dispatching "):
        tail = rest[12:]
        return (
            escape_fr(f"Erreur lors de l'acheminement de {tail}"),
            f"Error al despachar {tail}",
            f"Fehler beim Dispatchen von {tail}",
        )
    if low.startswith("setting up "):
        tail = rest[11:]
        return (
            escape_fr(f"Erreur lors de la configuration de {tail}"),
            f"Error al configurar {tail}",
            f"Fehler beim Einrichten von {tail}",
        )
    if low.startswith("isolating "):
        tail = rest[10:]
        return (
            escape_fr(f"Erreur lors de l'isolation de {tail}"),
            f"Error al aislar {tail}",
            f"Fehler beim Isolieren von {tail}",
        )
    return (
        escape_fr(f"Erreur : {rest}"),
        f"Error: {rest}",
        f"Fehler: {rest}",
    )


def translate(en: str) -> tuple[str, str, str]:
    en = norm_dash(en)
    if en in OVERRIDES:
        return OVERRIDES[en]

    if en.startswith("Failed to send "):
        return _failed_to_send(en[15:])
    if en.startswith("Failed to complete "):
        return _failed_to_complete(en[19:])
    if en.startswith("Failed to start "):
        return _failed_to_start(en[16:])
    if en.startswith("Failed to "):
        return _failed_to(en[10:])
    if en.startswith("Error sending "):
        return _error_sending(en[14:])
    if en.startswith("Error closing "):
        return _error_closing(en[14:])
    if en.startswith("Error during "):
        return _error_during(en[13:])
    if en.startswith("Error in "):
        return _error_in(en[9:])
    if en.startswith("Error "):
        return _error_generic(en[6:])

    raise KeyError(en)


def parse_props(path: Path) -> dict[str, str]:
    keys: dict[str, str] = {}
    if not path.exists():
        return keys
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        keys[k.strip()] = v
    return keys


def write_props(path: Path, keys_order: list[str], values: dict[str, str]) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    out: list[str] = []
    seen: set[str] = set()
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            k = stripped.split("=", 1)[0].strip()
            if k in values:
                out.append(f"{k}={values[k]}")
                seen.add(k)
                continue
        out.append(line)
    path.write_text("\n".join(out) + ("\n" if out and out[-1] else ""), encoding="utf-8")


def apply_translations() -> list[str]:
    missing: list[str] = []
    for base in sorted(GUMDROP.rglob("L10N.properties")):
        dir_path = base.parent
        default = parse_props(base)
        for loc in LOCALES:
            loc_path = dir_path / f"L10N_{loc}.properties"
            loc_keys = parse_props(loc_path)
            updates: dict[str, str] = dict(loc_keys)
            changed = False
            for k, v in default.items():
                if k not in loc_keys or loc_keys[k] != v or len(v) <= 3:
                    continue
                try:
                    fr, es, de = translate(v)
                except KeyError:
                    if v not in missing:
                        missing.append(v)
                    continue
                new_val = {"fr": fr, "es": es, "de": de}[loc]
                if new_val != v:
                    updates[k] = new_val
                    changed = True
            if changed:
                lines = loc_path.read_text(encoding="utf-8").splitlines()
                out: list[str] = []
                for line in lines:
                    stripped = line.strip()
                    if stripped and not stripped.startswith("#") and "=" in stripped:
                        key = stripped.split("=", 1)[0].strip()
                        if key in updates and updates[key] != loc_keys.get(key):
                            out.append(f"{key}={updates[key]}")
                            continue
                    out.append(line)
                text = "\n".join(out)
                if text and not text.endswith("\n"):
                    text += "\n"
                loc_path.write_text(text, encoding="utf-8")
    return missing


def inventory_count() -> int:
    unique: set[tuple[str, str]] = set()
    for base in sorted(GUMDROP.rglob("L10N.properties")):
        dir_path = base.parent
        rel = str(dir_path.relative_to(GUMDROP))
        default = parse_props(base)
        for loc in LOCALES:
            loc_keys = parse_props(dir_path / f"L10N_{loc}.properties")
            for k, v in default.items():
                if k in loc_keys and loc_keys[k] == v and len(v) > 3:
                    unique.add((rel, k))
    return len(unique)


def main() -> None:
    missing = apply_translations()
    if missing:
        print(f"missing translations for {len(missing)} unique English strings:", file=sys.stderr)
        for m in sorted(missing):
            print(m, file=sys.stderr)
    print(inventory_count(), "unique keys need translation")


if __name__ == "__main__":
    main()
