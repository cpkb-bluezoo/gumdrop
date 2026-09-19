#!/usr/bin/env python3
"""Operator log L10N: scan and fix hardcoded Logger strings (L10nLogGuardTest).

Scans all of src/org/bluezoo/gumdrop (same rules as L10nLogGuardSupport).

Usage:
  python3 scripts/operator-log-l10n.py scan
  python3 scripts/operator-log-l10n.py inventory
  python3 scripts/operator-log-l10n.py apply
"""

from __future__ import annotations

import argparse
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GUMDROP = ROOT / "src/org/bluezoo/gumdrop"
LOGGER_LITERAL = re.compile(
    r"\b(?:LOGGER|logger)\.(?:log\s*\(\s*Level\.(?:INFO|WARNING|SEVERE|FINE|FINER|FINEST)\s*,\s*|"
    r"(?:info|warning|severe|fine|finer|finest)\s*\(\s*)\""
)
LOGGER_CALL_START = re.compile(
    r"\b(?:LOGGER|logger)\.(?:log\s*\(|(?:info|warning|severe|fine|finer|finest)\s*\()"
)
L10N_IN_TEXT = re.compile(
    r"L10N\.getString|MessageFormat\.format\s*\([^)]*L10N|Gumdrop\.L10N\.getString"
)


def _is_comment(line: str) -> bool:
    t = line.strip()
    return t.startswith("//") or t.startswith("*")


def _line_is_violation(line: str) -> bool:
    if _is_comment(line):
        return False
    return bool(LOGGER_LITERAL.search(line)) and not L10N_IN_TEXT.search(line)


def _extract_logger_statement(lines: list[str], start: int) -> str | None:
    parts: list[str] = []
    depth = 0
    started = False
    for i in range(start, len(lines)):
        line = lines[i]
        parts.append(line)
        for ch in line:
            if ch == "(":
                depth += 1
                started = True
            elif ch == ")":
                depth -= 1
        if started and ";" in line and depth <= 0:
            break
    return "\n".join(parts) if parts else None


def scan_violations() -> list[str]:
    """Return main:rel:line entries (and line text) matching the guard."""
    out: list[str] = []
    if not GUMDROP.is_dir():
        return ["(main source tree not found)"]
    for path in sorted(GUMDROP.rglob("*.java")):
        rel = path.relative_to(GUMDROP).as_posix()
        lines = path.read_text(encoding="utf-8").splitlines()
        reported: set[int] = set()
        for i, line in enumerate(lines):
            if _is_comment(line):
                continue
            if _line_is_violation(line):
                if (i + 1) not in reported:
                    reported.add(i + 1)
                    out.append(f"main:{rel}:{i + 1}: {line.strip()}")
                continue
            if not LOGGER_CALL_START.search(line) or '"' in line:
                continue
            stmt = _extract_logger_statement(lines, i)
            if not stmt or '"' not in stmt or L10N_IN_TEXT.search(stmt):
                continue
            if (i + 1) not in reported:
                reported.add(i + 1)
                out.append(f"main:{rel}:{i + 1}: {line.strip()}")
    return out


def _patch_file(path: Path, replacements: list[tuple[str, str]]) -> None:
    """Replace logger literals; skip pairs whose old text is already gone."""
    text = path.read_text(encoding="utf-8")
    orig = text
    for old, new in replacements:
        if old not in text:
            continue
        text = text.replace(old, new)
    if text != orig:
        path.write_text(text, encoding="utf-8")


def _append_bundle_locales(
    dir_path: Path,
    en: dict[str, str],
    locales: dict[str, dict[str, str]],
    sentinel_key: str,
) -> None:
    for name, mapping in [
        ("L10N.properties", en),
        ("L10N_en.properties", en),
        ("L10N_fr.properties", locales["fr"]),
        ("L10N_de.properties", locales["de"]),
        ("L10N_es.properties", locales["es"]),
    ]:
        path = dir_path / name
        text = path.read_text(encoding="utf-8")
        if sentinel_key in text:
            continue
        block = "\n# Operator logs (L10nLogGuard)\n" + "".join(
            f"{k}={mapping[k]}\n" for k in sorted(en.keys())
        )
        if not text.endswith("\n"):
            text += "\n"
        path.write_text(text + block, encoding="utf-8")


def _append_bundle_en_only(dir_path: Path, keys: dict[str, str]) -> None:
    sentinel = next(iter(keys))
    for name in [
        "L10N.properties",
        "L10N_en.properties",
        "L10N_fr.properties",
        "L10N_de.properties",
        "L10N_es.properties",
    ]:
        path = dir_path / name
        text = path.read_text(encoding="utf-8")
        if sentinel in text:
            continue
        block = "\n# Operator logs (L10nLogGuard)\n" + "".join(
            f"{k}={keys[k]}\n" for k in sorted(keys)
        )
        if not text.endswith("\n"):
            text += "\n"
        path.write_text(text + block, encoding="utf-8")


def inventory() -> None:
    violations = scan_violations()
    counts: Counter[str] = Counter()
    for entry in violations:
        if entry.startswith("main:"):
            rest = entry[5:].split(":", 1)[0]
            top = rest.split("/")[0] if "/" in rest else rest
            counts[top] += 1
    print(f"violations: {len(violations)}")
    for pkg, n in counts.most_common():
        print(f"  {pkg}: {n}")


# --- FTP (org.bluezoo.gumdrop.ftp) ---

FTP = ROOT / "src/org/bluezoo/gumdrop/ftp"
# English defaults (also used for L10N.properties and L10N_en.properties)
EN: dict[str, str] = {
    "debug.err_closing_data_connection": "Error closing data connection",
    "debug.data_protection_enabled": "Data protection enabled",
    "debug.data_protection_disabled": "Data protection disabled",
    "debug.passive_mode_enabled_port": "Passive mode enabled on port {0}",
    "warn.active_mode_unknown_host": "Active mode rejected: unknown host {0}",
    "debug.active_mode_configured": "Active mode configured to {0}:{1}",
    "warn.err_accepting_data_connection": "Error accepting data connection",
    "debug.data_connection_queued": "Data connection queued (arrived before command)",
    "warn.err_closing_data_on_abort": "Error closing data connection during abort",
    "debug.err_stopping_passive_connector": "Error stopping passive connector",
    "warn.ftp_data_transfer_setup_failed": "FTP data transfer setup failed",
    "warn.ftp_upload_open_failed": "FTP upload open failed",
    "warn.data_connection_error_listing": "Data connection error during listing",
    "warn.data_connection_error": "Data connection error",
    "warn.ftp_download_open_failed": "FTP download open failed",
    "warn.ftp_listing_failed": "FTP listing failed",
    "warn.cwd_failed": "CWD failed for {0}",
    "warn.cdup_failed": "CDUP failed",
    "warn.passive_mode_setup_failed": "Failed to set up passive mode",
    "warn.epsv_setup_failed": "Failed to set up extended passive mode",
    "warn.retr_failed": "RETR failed for {0}",
    "warn.stor_failed": "STOR failed for {0}",
    "warn.stou_failed": "STOU failed",
    "warn.stou_error_reply_failed": "Failed to send STOU error",
    "warn.appe_failed": "APPE failed for {0}",
    "warn.appe_error_reply_failed": "Failed to send APPE error",
    "warn.rnfr_failed": "RNFR failed for {0}",
    "warn.dele_failed": "DELE failed for {0}",
    "warn.rmd_failed": "RMD failed for {0}",
    "warn.mkd_failed": "MKD failed for {0}",
    "warn.list_failed": "LIST failed for {0}",
    "warn.nlst_failed": "NLST failed for {0}",
    "warn.failed_send_welcome_banner": "Failed to send welcome banner",
    "warn.stat_failed": "STAT failed for {0}",
    "warn.auth_tls_failed": "AUTH TLS failed",
    "warn.size_failed": "SIZE failed for {0}",
    "warn.mdtm_failed": "MDTM failed for {0}",
    "warn.mlst_failed": "MLST failed for {0}",
    "warn.mlsd_failed": "MLSD failed for {0}",
    "warn.ftp_transport_error": "FTP transport error",
    "warn.failed_send_login_response": "Failed to send login response",
    "warn.failed_send_service_unavailable": "Failed to send service unavailable",
    "warn.failed_send_need_password": "Failed to send need password reply",
    "warn.failed_send_need_account": "Failed to send need account reply",
    "warn.failed_send_login_successful": "Failed to send login successful",
    "warn.failed_send_command_ok": "Failed to send command ok",
    "warn.failed_send_cert_login_failure": "Failed to send certificate login failure",
    "warn.failed_send_login_rejection": "Failed to send login rejection",
    "warn.error_processing_ftp_command": "Error processing FTP command",
    "warn.cannot_write_error_reply": "Cannot write error reply",
    "warn.failed_send_ftp_reply": "Failed to send FTP reply {0}",
    "warn.failed_send_ftp_line": "Failed to send FTP line",
    "debug.client_connected": "FTP client connected to {0}",
    "debug.tls_established": "TLS established: {0}",
    "debug.sent_ftp_command_pass": "Sent FTP command: PASS ***",
    "debug.sent_ftp_command": "Sent FTP command: {0}",
    "debug.ignoring_response_in_state": "Ignoring response in state {0}: {1}",
    "debug.received_ftp_response": "Received FTP response: {0} {1}",
    "debug.directory_not_exist": "Directory does not exist: {0}",
    "debug.path_not_directory": "Path is not a directory: {0}",
    "debug.skip_list_child_error": "Skipping file due to error: {0} - {1}",
    "debug.listed_directory_items": "Listed {0} items in directory: {1}",
    "warn.security_violation_list_directory": "Security violation in listDirectory: {0}",
    "warn.error_listing_directory": "Error listing directory: {0}",
    "debug.changed_directory": "Changed directory from {0} to {1}",
    "warn.security_violation_change_directory": "Security violation in changeDirectory: {0}",
    "warn.error_changing_directory": "Error changing directory: {0}",
    "debug.error_file_info": "Error getting file info for {0}: {1}",
    "debug.created_directory": "Created directory: {0}",
    "warn.security_violation_create_directory": "Security violation in createDirectory: {0}",
    "warn.error_creating_directory": "Error creating directory: {0}",
    "debug.removed_directory": "Removed directory: {0}",
    "warn.security_violation_remove_directory": "Security violation in removeDirectory: {0}",
    "warn.error_removing_directory": "Error removing directory: {0}",
    "debug.deleted_file": "Deleted file: {0}",
    "warn.security_violation_delete_file": "Security violation in deleteFile: {0}",
    "warn.error_deleting_file": "Error deleting file: {0}",
    "debug.renamed_file": "Renamed {0} to {1}",
    "debug.file_not_found_reading": "File not found for reading: {0}",
    "debug.cannot_read_directory_as_file": "Cannot read directory as file: {0}",
    "debug.file_channel_restart_offset": "Positioned file channel at restart offset: {0}",
    "debug.opened_file_reading": "Opened file for reading: {0}{1}",
    "debug.opened_file_reading_offset_suffix": " (offset: {0})",
    "debug.write_denied_readonly": "Write denied - file system is read-only: {0}",
    "debug.cannot_write_to_directory": "Cannot write to directory: {0}",
    "debug.opened_file_writing": "Opened file for writing: {0}{1}",
    "debug.opened_file_writing_append": " (append mode)",
    "debug.opened_file_writing_overwrite": " (overwrite mode)",
    "warn.security_violation_open_reading": "Security violation in openForReading: {0}",
    "warn.error_opening_file_reading": "Error opening file for reading: {0}",
    "warn.security_violation_open_writing": "Security violation in openForWriting: {0}",
    "warn.error_opening_file_writing": "Error opening file for writing: {0}",
    "debug.generated_unique_name": "Generated unique name: {0}",
    "warn.security_violation_generate_unique_name": "Security violation in generateUniqueName: {0}",
    "warn.error_generating_unique_name": "Error generating unique name: {0}",
    "debug.allo_noop": "ALLO command for {0} ({1} bytes) - no-op",
    "debug.access_denied_missing_role": "Access denied for user {0}: missing role {1}",
    "debug.home_confinement_denied": "Home confinement denied for user {0}: path {1} is outside {2}",
    "debug.role_handler_connection": "FTP connection from {0}",
    "debug.authorization_denied": "Authorization denied: user={0}, operation={1}, path={2}",
    "debug.transfer_starting_detail": "Transfer starting: {0} {1} (size={2}) by {3}",
    "debug.role_handler_disconnected": "FTP disconnected: user={0}, duration={1}ms",
    "debug.session_closed": "FTP session closed",
    "warn.ftp_authentication_error": "FTP authentication error",
}

FR: dict[str, str] = {
    "debug.err_closing_data_connection": "Erreur lors de la fermeture de la connexion de données",
    "debug.data_protection_enabled": "Protection des données activée",
    "debug.data_protection_disabled": "Protection des données désactivée",
    "debug.passive_mode_enabled_port": "Mode passif activé sur le port {0}",
    "warn.active_mode_unknown_host": "Mode actif rejeté : hôte inconnu {0}",
    "debug.active_mode_configured": "Mode actif configuré vers {0}:{1}",
    "warn.err_accepting_data_connection": "Erreur lors de l''acceptation de la connexion de données",
    "debug.data_connection_queued": "Connexion de données mise en file (arrivée avant la commande)",
    "warn.err_closing_data_on_abort": "Erreur lors de la fermeture de la connexion de données lors de l''ABOR",
    "debug.err_stopping_passive_connector": "Erreur lors de l''arrêt du connecteur passif",
    "warn.ftp_data_transfer_setup_failed": "Échec de la configuration du transfert de données FTP",
    "warn.ftp_upload_open_failed": "Échec de l''ouverture du téléversement FTP",
    "warn.data_connection_error_listing": "Erreur de connexion de données lors du listage",
    "warn.data_connection_error": "Erreur de connexion de données",
    "warn.ftp_download_open_failed": "Échec de l''ouverture du téléchargement FTP",
    "warn.ftp_listing_failed": "Échec du listage FTP",
    "warn.cwd_failed": "Échec CWD pour {0}",
    "warn.cdup_failed": "Échec CDUP",
    "warn.passive_mode_setup_failed": "Échec de la configuration du mode passif",
    "warn.epsv_setup_failed": "Échec de la configuration du mode passif étendu",
    "warn.retr_failed": "Échec RETR pour {0}",
    "warn.stor_failed": "Échec STOR pour {0}",
    "warn.stou_failed": "Échec STOU",
    "warn.stou_error_reply_failed": "Échec de l''envoi de la réponse d''erreur STOU",
    "warn.appe_failed": "Échec APPE pour {0}",
    "warn.appe_error_reply_failed": "Échec de l''envoi de la réponse d''erreur APPE",
    "warn.rnfr_failed": "Échec RNFR pour {0}",
    "warn.dele_failed": "Échec DELE pour {0}",
    "warn.rmd_failed": "Échec RMD pour {0}",
    "warn.mkd_failed": "Échec MKD pour {0}",
    "warn.list_failed": "Échec LIST pour {0}",
    "warn.nlst_failed": "Échec NLST pour {0}",
    "warn.failed_send_welcome_banner": "Échec de l''envoi de la bannière de bienvenue",
    "warn.stat_failed": "Échec STAT pour {0}",
    "warn.auth_tls_failed": "Échec AUTH TLS",
    "warn.size_failed": "Échec SIZE pour {0}",
    "warn.mdtm_failed": "Échec MDTM pour {0}",
    "warn.mlst_failed": "Échec MLST pour {0}",
    "warn.mlsd_failed": "Échec MLSD pour {0}",
    "warn.ftp_transport_error": "Erreur de transport FTP",
    "warn.failed_send_login_response": "Échec de l''envoi de la réponse de connexion",
    "warn.failed_send_service_unavailable": "Échec de l''envoi du service indisponible",
    "warn.failed_send_need_password": "Échec de l''envoi de la demande de mot de passe",
    "warn.failed_send_need_account": "Échec de l''envoi de la demande de compte",
    "warn.failed_send_login_successful": "Échec de l''envoi de la connexion réussie",
    "warn.failed_send_command_ok": "Échec de l''envoi de la commande OK",
    "warn.failed_send_cert_login_failure": "Échec de l''envoi de l''échec de connexion par certificat",
    "warn.failed_send_login_rejection": "Échec de l''envoi du rejet de connexion",
    "warn.error_processing_ftp_command": "Erreur lors du traitement de la commande FTP",
    "warn.cannot_write_error_reply": "Impossible d''écrire la réponse d''erreur",
    "warn.failed_send_ftp_reply": "Échec de l''envoi de la réponse FTP {0}",
    "warn.failed_send_ftp_line": "Échec de l''envoi de la ligne FTP",
    "debug.client_connected": "Client FTP connecté à {0}",
    "debug.tls_established": "TLS établi : {0}",
    "debug.sent_ftp_command_pass": "Commande FTP envoyée : PASS ***",
    "debug.sent_ftp_command": "Commande FTP envoyée : {0}",
    "debug.ignoring_response_in_state": "Réponse ignorée à l''état {0} : {1}",
    "debug.received_ftp_response": "Réponse FTP reçue : {0} {1}",
    "debug.directory_not_exist": "Le répertoire n''existe pas : {0}",
    "debug.path_not_directory": "Le chemin n''est pas un répertoire : {0}",
    "debug.skip_list_child_error": "Fichier ignoré en raison d''une erreur : {0} - {1}",
    "debug.listed_directory_items": "{0} éléments listés dans le répertoire : {1}",
    "warn.security_violation_list_directory": "Violation de sécurité dans listDirectory : {0}",
    "warn.error_listing_directory": "Erreur lors du listage du répertoire : {0}",
    "debug.changed_directory": "Répertoire changé de {0} à {1}",
    "warn.security_violation_change_directory": "Violation de sécurité dans changeDirectory : {0}",
    "warn.error_changing_directory": "Erreur lors du changement de répertoire : {0}",
    "debug.error_file_info": "Erreur lors de la lecture des infos fichier pour {0} : {1}",
    "debug.created_directory": "Répertoire créé : {0}",
    "warn.security_violation_create_directory": "Violation de sécurité dans createDirectory : {0}",
    "warn.error_creating_directory": "Erreur lors de la création du répertoire : {0}",
    "debug.removed_directory": "Répertoire supprimé : {0}",
    "warn.security_violation_remove_directory": "Violation de sécurité dans removeDirectory : {0}",
    "warn.error_removing_directory": "Erreur lors de la suppression du répertoire : {0}",
    "debug.deleted_file": "Fichier supprimé : {0}",
    "warn.security_violation_delete_file": "Violation de sécurité dans deleteFile : {0}",
    "warn.error_deleting_file": "Erreur lors de la suppression du fichier : {0}",
    "debug.renamed_file": "Renommé {0} en {1}",
    "debug.file_not_found_reading": "Fichier introuvable pour lecture : {0}",
    "debug.cannot_read_directory_as_file": "Impossible de lire un répertoire comme fichier : {0}",
    "debug.file_channel_restart_offset": "Canal fichier positionné au redémarrage : {0}",
    "debug.opened_file_reading": "Fichier ouvert en lecture : {0}{1}",
    "debug.opened_file_reading_offset_suffix": " (décalage : {0})",
    "debug.write_denied_readonly": "Écriture refusée - système de fichiers en lecture seule : {0}",
    "debug.cannot_write_to_directory": "Impossible d''écrire dans le répertoire : {0}",
    "debug.opened_file_writing": "Fichier ouvert en écriture : {0}{1}",
    "debug.opened_file_writing_append": " (mode ajout)",
    "debug.opened_file_writing_overwrite": " (mode écrasement)",
    "warn.security_violation_open_reading": "Violation de sécurité dans openForReading : {0}",
    "warn.error_opening_file_reading": "Erreur lors de l''ouverture du fichier en lecture : {0}",
    "warn.security_violation_open_writing": "Violation de sécurité dans openForWriting : {0}",
    "warn.error_opening_file_writing": "Erreur lors de l''ouverture du fichier en écriture : {0}",
    "debug.generated_unique_name": "Nom unique généré : {0}",
    "warn.security_violation_generate_unique_name": "Violation de sécurité dans generateUniqueName : {0}",
    "warn.error_generating_unique_name": "Erreur lors de la génération du nom unique : {0}",
    "debug.allo_noop": "Commande ALLO pour {0} ({1} octets) - sans effet",
    "debug.access_denied_missing_role": "Accès refusé pour l''utilisateur {0} : rôle manquant {1}",
    "debug.home_confinement_denied": "Confinement home refusé pour {0} : le chemin {1} est hors de {2}",
    "debug.role_handler_connection": "Connexion FTP depuis {0}",
    "debug.authorization_denied": "Autorisation refusée : user={0}, operation={1}, path={2}",
    "debug.transfer_starting_detail": "Début du transfert : {0} {1} (taille={2}) par {3}",
    "debug.role_handler_disconnected": "FTP déconnecté : user={0}, durée={1}ms",
    "debug.session_closed": "Session FTP fermée",
    "warn.ftp_authentication_error": "Erreur d''authentification FTP",
}

DE: dict[str, str] = {
    "debug.err_closing_data_connection": "Fehler beim Schließen der Datenverbindung",
    "debug.data_protection_enabled": "Datenschutz aktiviert",
    "debug.data_protection_disabled": "Datenschutz deaktiviert",
    "debug.passive_mode_enabled_port": "Passiver Modus auf Port {0} aktiviert",
    "warn.active_mode_unknown_host": "Aktiver Modus abgelehnt: unbekannter Host {0}",
    "debug.active_mode_configured": "Aktiver Modus konfiguriert auf {0}:{1}",
    "warn.err_accepting_data_connection": "Fehler beim Annehmen der Datenverbindung",
    "debug.data_connection_queued": "Datenverbindung in Warteschlange (vor dem Befehl angekommen)",
    "warn.err_closing_data_on_abort": "Fehler beim Schließen der Datenverbindung während ABOR",
    "debug.err_stopping_passive_connector": "Fehler beim Stoppen des passiven Connectors",
    "warn.ftp_data_transfer_setup_failed": "Einrichten des FTP-Datentransfers fehlgeschlagen",
    "warn.ftp_upload_open_failed": "Öffnen des FTP-Uploads fehlgeschlagen",
    "warn.data_connection_error_listing": "Datenverbindungsfehler beim Auflisten",
    "warn.data_connection_error": "Datenverbindungsfehler",
    "warn.ftp_download_open_failed": "Öffnen des FTP-Downloads fehlgeschlagen",
    "warn.ftp_listing_failed": "FTP-Auflisten fehlgeschlagen",
    "warn.cwd_failed": "CWD fehlgeschlagen für {0}",
    "warn.cdup_failed": "CDUP fehlgeschlagen",
    "warn.passive_mode_setup_failed": "Einrichten des passiven Modus fehlgeschlagen",
    "warn.epsv_setup_failed": "Einrichten des erweiterten passiven Modus fehlgeschlagen",
    "warn.retr_failed": "RETR fehlgeschlagen für {0}",
    "warn.stor_failed": "STOR fehlgeschlagen für {0}",
    "warn.stou_failed": "STOU fehlgeschlagen",
    "warn.stou_error_reply_failed": "Senden der STOU-Fehlerantwort fehlgeschlagen",
    "warn.appe_failed": "APPE fehlgeschlagen für {0}",
    "warn.appe_error_reply_failed": "Senden der APPE-Fehlerantwort fehlgeschlagen",
    "warn.rnfr_failed": "RNFR fehlgeschlagen für {0}",
    "warn.dele_failed": "DELE fehlgeschlagen für {0}",
    "warn.rmd_failed": "RMD fehlgeschlagen für {0}",
    "warn.mkd_failed": "MKD fehlgeschlagen für {0}",
    "warn.list_failed": "LIST fehlgeschlagen für {0}",
    "warn.nlst_failed": "NLST fehlgeschlagen für {0}",
    "warn.failed_send_welcome_banner": "Senden des Willkommensbanners fehlgeschlagen",
    "warn.stat_failed": "STAT fehlgeschlagen für {0}",
    "warn.auth_tls_failed": "AUTH TLS fehlgeschlagen",
    "warn.size_failed": "SIZE fehlgeschlagen für {0}",
    "warn.mdtm_failed": "MDTM fehlgeschlagen für {0}",
    "warn.mlst_failed": "MLST fehlgeschlagen für {0}",
    "warn.mlsd_failed": "MLSD fehlgeschlagen für {0}",
    "warn.ftp_transport_error": "FTP-Transportfehler",
    "warn.failed_send_login_response": "Senden der Login-Antwort fehlgeschlagen",
    "warn.failed_send_service_unavailable": "Senden von Dienst nicht verfügbar fehlgeschlagen",
    "warn.failed_send_need_password": "Senden der Passwort-Anforderung fehlgeschlagen",
    "warn.failed_send_need_account": "Senden der Konto-Anforderung fehlgeschlagen",
    "warn.failed_send_login_successful": "Senden der erfolgreichen Anmeldung fehlgeschlagen",
    "warn.failed_send_command_ok": "Senden von Befehl OK fehlgeschlagen",
    "warn.failed_send_cert_login_failure": "Senden des Zertifikat-Login-Fehlers fehlgeschlagen",
    "warn.failed_send_login_rejection": "Senden der Login-Ablehnung fehlgeschlagen",
    "warn.error_processing_ftp_command": "Fehler bei der Verarbeitung des FTP-Befehls",
    "warn.cannot_write_error_reply": "Fehlerantwort kann nicht geschrieben werden",
    "warn.failed_send_ftp_reply": "Senden der FTP-Antwort {0} fehlgeschlagen",
    "warn.failed_send_ftp_line": "Senden der FTP-Zeile fehlgeschlagen",
    "debug.client_connected": "FTP-Client verbunden mit {0}",
    "debug.tls_established": "TLS hergestellt: {0}",
    "debug.sent_ftp_command_pass": "FTP-Befehl gesendet: PASS ***",
    "debug.sent_ftp_command": "FTP-Befehl gesendet: {0}",
    "debug.ignoring_response_in_state": "Antwort im Zustand {0} ignoriert: {1}",
    "debug.received_ftp_response": "FTP-Antwort empfangen: {0} {1}",
    "debug.directory_not_exist": "Verzeichnis existiert nicht: {0}",
    "debug.path_not_directory": "Pfad ist kein Verzeichnis: {0}",
    "debug.skip_list_child_error": "Datei wegen Fehler übersprungen: {0} - {1}",
    "debug.listed_directory_items": "{0} Elemente im Verzeichnis aufgelistet: {1}",
    "warn.security_violation_list_directory": "Sicherheitsverletzung in listDirectory: {0}",
    "warn.error_listing_directory": "Fehler beim Auflisten des Verzeichnisses: {0}",
    "debug.changed_directory": "Verzeichnis gewechselt von {0} nach {1}",
    "warn.security_violation_change_directory": "Sicherheitsverletzung in changeDirectory: {0}",
    "warn.error_changing_directory": "Fehler beim Wechseln des Verzeichnisses: {0}",
    "debug.error_file_info": "Fehler beim Abrufen der Dateiinfo für {0}: {1}",
    "debug.created_directory": "Verzeichnis erstellt: {0}",
    "warn.security_violation_create_directory": "Sicherheitsverletzung in createDirectory: {0}",
    "warn.error_creating_directory": "Fehler beim Erstellen des Verzeichnisses: {0}",
    "debug.removed_directory": "Verzeichnis entfernt: {0}",
    "warn.security_violation_remove_directory": "Sicherheitsverletzung in removeDirectory: {0}",
    "warn.error_removing_directory": "Fehler beim Entfernen des Verzeichnisses: {0}",
    "debug.deleted_file": "Datei gelöscht: {0}",
    "warn.security_violation_delete_file": "Sicherheitsverletzung in deleteFile: {0}",
    "warn.error_deleting_file": "Fehler beim Löschen der Datei: {0}",
    "debug.renamed_file": "Umbenannt {0} nach {1}",
    "debug.file_not_found_reading": "Datei zum Lesen nicht gefunden: {0}",
    "debug.cannot_read_directory_as_file": "Verzeichnis kann nicht als Datei gelesen werden: {0}",
    "debug.file_channel_restart_offset": "Dateikanal bei Restart-Offset positioniert: {0}",
    "debug.opened_file_reading": "Datei zum Lesen geöffnet: {0}{1}",
    "debug.opened_file_reading_offset_suffix": " (Offset: {0})",
    "debug.write_denied_readonly": "Schreiben verweigert - Dateisystem ist schreibgeschützt: {0}",
    "debug.cannot_write_to_directory": "In Verzeichnis kann nicht geschrieben werden: {0}",
    "debug.opened_file_writing": "Datei zum Schreiben geöffnet: {0}{1}",
    "debug.opened_file_writing_append": " (Anhängemodus)",
    "debug.opened_file_writing_overwrite": " (Überschreibmodus)",
    "warn.security_violation_open_reading": "Sicherheitsverletzung in openForReading: {0}",
    "warn.error_opening_file_reading": "Fehler beim Öffnen der Datei zum Lesen: {0}",
    "warn.security_violation_open_writing": "Sicherheitsverletzung in openForWriting: {0}",
    "warn.error_opening_file_writing": "Fehler beim Öffnen der Datei zum Schreiben: {0}",
    "debug.generated_unique_name": "Eindeutiger Name erzeugt: {0}",
    "warn.security_violation_generate_unique_name": "Sicherheitsverletzung in generateUniqueName: {0}",
    "warn.error_generating_unique_name": "Fehler beim Erzeugen des eindeutigen Namens: {0}",
    "debug.allo_noop": "ALLO-Befehl für {0} ({1} Bytes) - No-Op",
    "debug.access_denied_missing_role": "Zugriff verweigert für Benutzer {0}: fehlende Rolle {1}",
    "debug.home_confinement_denied": "Home-Beschränkung verweigert für {0}: Pfad {1} liegt außerhalb von {2}",
    "debug.role_handler_connection": "FTP-Verbindung von {0}",
    "debug.authorization_denied": "Autorisierung verweigert: user={0}, operation={1}, path={2}",
    "debug.transfer_starting_detail": "Transfer startet: {0} {1} (Größe={2}) von {3}",
    "debug.role_handler_disconnected": "FTP getrennt: user={0}, Dauer={1}ms",
    "debug.session_closed": "FTP-Sitzung geschlossen",
    "warn.ftp_authentication_error": "FTP-Authentifizierungsfehler",
}

ES: dict[str, str] = {
    "debug.err_closing_data_connection": "Error al cerrar la conexión de datos",
    "debug.data_protection_enabled": "Protección de datos activada",
    "debug.data_protection_disabled": "Protección de datos desactivada",
    "debug.passive_mode_enabled_port": "Modo pasivo activado en el puerto {0}",
    "warn.active_mode_unknown_host": "Modo activo rechazado: host desconocido {0}",
    "debug.active_mode_configured": "Modo activo configurado a {0}:{1}",
    "warn.err_accepting_data_connection": "Error al aceptar la conexión de datos",
    "debug.data_connection_queued": "Conexión de datos en cola (llegó antes del comando)",
    "warn.err_closing_data_on_abort": "Error al cerrar la conexión de datos durante ABOR",
    "debug.err_stopping_passive_connector": "Error al detener el conector pasivo",
    "warn.ftp_data_transfer_setup_failed": "Error al configurar la transferencia de datos FTP",
    "warn.ftp_upload_open_failed": "Error al abrir la subida FTP",
    "warn.data_connection_error_listing": "Error de conexión de datos durante el listado",
    "warn.data_connection_error": "Error de conexión de datos",
    "warn.ftp_download_open_failed": "Error al abrir la descarga FTP",
    "warn.ftp_listing_failed": "Error en el listado FTP",
    "warn.cwd_failed": "CWD falló para {0}",
    "warn.cdup_failed": "CDUP falló",
    "warn.passive_mode_setup_failed": "Error al configurar el modo pasivo",
    "warn.epsv_setup_failed": "Error al configurar el modo pasivo extendido",
    "warn.retr_failed": "RETR falló para {0}",
    "warn.stor_failed": "STOR falló para {0}",
    "warn.stou_failed": "STOU falló",
    "warn.stou_error_reply_failed": "Error al enviar la respuesta de error STOU",
    "warn.appe_failed": "APPE falló para {0}",
    "warn.appe_error_reply_failed": "Error al enviar la respuesta de error APPE",
    "warn.rnfr_failed": "RNFR falló para {0}",
    "warn.dele_failed": "DELE falló para {0}",
    "warn.rmd_failed": "RMD falló para {0}",
    "warn.mkd_failed": "MKD falló para {0}",
    "warn.list_failed": "LIST falló para {0}",
    "warn.nlst_failed": "NLST falló para {0}",
    "warn.failed_send_welcome_banner": "Error al enviar el banner de bienvenida",
    "warn.stat_failed": "STAT falló para {0}",
    "warn.auth_tls_failed": "AUTH TLS falló",
    "warn.size_failed": "SIZE falló para {0}",
    "warn.mdtm_failed": "MDTM falló para {0}",
    "warn.mlst_failed": "MLST falló para {0}",
    "warn.mlsd_failed": "MLSD falló para {0}",
    "warn.ftp_transport_error": "Error de transporte FTP",
    "warn.failed_send_login_response": "Error al enviar la respuesta de inicio de sesión",
    "warn.failed_send_service_unavailable": "Error al enviar servicio no disponible",
    "warn.failed_send_need_password": "Error al enviar solicitud de contraseña",
    "warn.failed_send_need_account": "Error al enviar solicitud de cuenta",
    "warn.failed_send_login_successful": "Error al enviar inicio de sesión correcto",
    "warn.failed_send_command_ok": "Error al enviar comando OK",
    "warn.failed_send_cert_login_failure": "Error al enviar fallo de inicio por certificado",
    "warn.failed_send_login_rejection": "Error al enviar rechazo de inicio de sesión",
    "warn.error_processing_ftp_command": "Error al procesar el comando FTP",
    "warn.cannot_write_error_reply": "No se puede escribir la respuesta de error",
    "warn.failed_send_ftp_reply": "Error al enviar la respuesta FTP {0}",
    "warn.failed_send_ftp_line": "Error al enviar la línea FTP",
    "debug.client_connected": "Cliente FTP conectado a {0}",
    "debug.tls_established": "TLS establecido: {0}",
    "debug.sent_ftp_command_pass": "Comando FTP enviado: PASS ***",
    "debug.sent_ftp_command": "Comando FTP enviado: {0}",
    "debug.ignoring_response_in_state": "Respuesta ignorada en estado {0}: {1}",
    "debug.received_ftp_response": "Respuesta FTP recibida: {0} {1}",
    "debug.directory_not_exist": "El directorio no existe: {0}",
    "debug.path_not_directory": "La ruta no es un directorio: {0}",
    "debug.skip_list_child_error": "Omitiendo archivo por error: {0} - {1}",
    "debug.listed_directory_items": "{0} elementos listados en el directorio: {1}",
    "warn.security_violation_list_directory": "Violación de seguridad en listDirectory: {0}",
    "warn.error_listing_directory": "Error al listar el directorio: {0}",
    "debug.changed_directory": "Directorio cambiado de {0} a {1}",
    "warn.security_violation_change_directory": "Violación de seguridad en changeDirectory: {0}",
    "warn.error_changing_directory": "Error al cambiar de directorio: {0}",
    "debug.error_file_info": "Error al obtener información del archivo {0}: {1}",
    "debug.created_directory": "Directorio creado: {0}",
    "warn.security_violation_create_directory": "Violación de seguridad en createDirectory: {0}",
    "warn.error_creating_directory": "Error al crear el directorio: {0}",
    "debug.removed_directory": "Directorio eliminado: {0}",
    "warn.security_violation_remove_directory": "Violación de seguridad en removeDirectory: {0}",
    "warn.error_removing_directory": "Error al eliminar el directorio: {0}",
    "debug.deleted_file": "Archivo eliminado: {0}",
    "warn.security_violation_delete_file": "Violación de seguridad en deleteFile: {0}",
    "warn.error_deleting_file": "Error al eliminar el archivo: {0}",
    "debug.renamed_file": "Renombrado {0} a {1}",
    "debug.file_not_found_reading": "Archivo no encontrado para lectura: {0}",
    "debug.cannot_read_directory_as_file": "No se puede leer un directorio como archivo: {0}",
    "debug.file_channel_restart_offset": "Canal de archivo posicionado en offset de reinicio: {0}",
    "debug.opened_file_reading": "Archivo abierto para lectura: {0}{1}",
    "debug.opened_file_reading_offset_suffix": " (offset: {0})",
    "debug.write_denied_readonly": "Escritura denegada - sistema de archivos de solo lectura: {0}",
    "debug.cannot_write_to_directory": "No se puede escribir en el directorio: {0}",
    "debug.opened_file_writing": "Archivo abierto para escritura: {0}{1}",
    "debug.opened_file_writing_append": " (modo append)",
    "debug.opened_file_writing_overwrite": " (modo sobrescritura)",
    "warn.security_violation_open_reading": "Violación de seguridad en openForReading: {0}",
    "warn.error_opening_file_reading": "Error al abrir el archivo para lectura: {0}",
    "warn.security_violation_open_writing": "Violación de seguridad en openForWriting: {0}",
    "warn.error_opening_file_writing": "Error al abrir el archivo para escritura: {0}",
    "debug.generated_unique_name": "Nombre único generado: {0}",
    "warn.security_violation_generate_unique_name": "Violación de seguridad en generateUniqueName: {0}",
    "warn.error_generating_unique_name": "Error al generar nombre único: {0}",
    "debug.allo_noop": "Comando ALLO para {0} ({1} bytes) - sin efecto",
    "debug.access_denied_missing_role": "Acceso denegado para usuario {0}: falta rol {1}",
    "debug.home_confinement_denied": "Confinamiento home denegado para {0}: la ruta {1} está fuera de {2}",
    "debug.role_handler_connection": "Conexión FTP desde {0}",
    "debug.authorization_denied": "Autorización denegada: user={0}, operation={1}, path={2}",
    "debug.transfer_starting_detail": "Inicio de transferencia: {0} {1} (tamaño={2}) por {3}",
    "debug.role_handler_disconnected": "FTP desconectado: user={0}, duración={1}ms",
    "debug.session_closed": "Sesión FTP cerrada",
    "warn.ftp_authentication_error": "Error de autenticación FTP",
}


def _ftp_append_properties() -> None:
    block_en = "\n# Operator logs (L10nLogGuard)\n" + "".join(
        f"{k}={v}\n" for k, v in sorted(EN.items())
    )
    for name, mapping in [
        ("L10N.properties", EN),
        ("L10N_en.properties", EN),
        ("L10N_fr.properties", FR),
        ("L10N_de.properties", DE),
        ("L10N_es.properties", ES),
    ]:
        path = FTP / name
        text = path.read_text(encoding="utf-8")
        if "debug.session_closed=" in text:
            continue
        block = "\n# Operator logs (L10nLogGuard)\n" + "".join(
            f"{k}={mapping[k]}\n" for k in sorted(EN.keys())
        )
        if not text.endswith("\n"):
            text += "\n"
        path.write_text(text + block, encoding="utf-8")


def __patch_file(path: Path, replacements: list[tuple[str, str]]) -> None:
    text = path.read_text(encoding="utf-8")
    orig = text
    for old, new in replacements:
        if old not in text:
            continue
        text = text.replace(old, new)
    if text != orig:
        path.write_text(text, encoding="utf-8")


def apply_ftp() -> None:
    _ftp_append_properties()

    l10n = "L10N"
    fp_l10n = "FtpProtocolHandler.L10N"

    __patch_file(FTP / "FtpDataConnection.java", [
        (
            'LOGGER.log(Level.FINE, "Error closing data connection", e);',
            f'LOGGER.log(Level.FINE, {fp_l10n}.getString("debug.err_closing_data_connection"), e);',
        ),
    ])

    coord = FTP / "FtpDataConnectionCoordinator.java"
    _patch_file(coord, [
        (
            'LOGGER.fine("Data protection " + (protect ? "enabled" : "disabled"));',
            f'LOGGER.fine({fp_l10n}.getString(protect ? "debug.data_protection_enabled" : "debug.data_protection_disabled"));',
        ),
        (
            'LOGGER.fine("Passive mode enabled on port " + passivePort);',
            f'LOGGER.fine(MessageFormat.format({fp_l10n}.getString("debug.passive_mode_enabled_port"), passivePort));',
        ),
        (
            'LOGGER.fine("Active mode rejected: unknown host " + host);',
            f'LOGGER.fine(MessageFormat.format({fp_l10n}.getString("warn.active_mode_unknown_host"), host));',
        ),
        (
            'LOGGER.fine("Active mode configured to " + host + ":" + port);',
            f'LOGGER.fine(MessageFormat.format({fp_l10n}.getString("debug.active_mode_configured"), host, port));',
        ),
        (
            'LOGGER.log(Level.WARNING, "Error accepting data connection", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.err_accepting_data_connection"), e);',
        ),
        (
            'LOGGER.fine("Data connection queued (arrived before command)");',
            f'LOGGER.fine({fp_l10n}.getString("debug.data_connection_queued"));',
        ),
        (
            'LOGGER.log(Level.WARNING, "Error closing data connection during abort", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.err_closing_data_on_abort"), e);',
        ),
        (
            'LOGGER.log(Level.FINE, "Error closing data connection", e);',
            f'LOGGER.log(Level.FINE, {fp_l10n}.getString("debug.err_closing_data_connection"), e);',
        ),
        (
            'LOGGER.log(Level.FINE, "Error stopping passive connector", e);',
            f'LOGGER.log(Level.FINE, {fp_l10n}.getString("debug.err_stopping_passive_connector"), e);',
        ),
        (
            'LOGGER.log(Level.WARNING, "FTP data transfer setup failed", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.ftp_data_transfer_setup_failed"), e);',
        ),
        (
            'LOGGER.log(Level.WARNING, "FTP download open failed", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.ftp_download_open_failed"), e);',
        ),
        (
            'LOGGER.log(Level.WARNING, "FTP listing failed", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.ftp_listing_failed"), e);',
        ),
        (
            'LOGGER.log(Level.WARNING, "FTP upload open failed", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.ftp_upload_open_failed"), e);',
        ),
        (
            'LOGGER.log(Level.WARNING, "Data connection error during listing", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.data_connection_error_listing"), e);',
        ),
        (
            'LOGGER.log(Level.WARNING, "Data connection error", e);',
            f'LOGGER.log(Level.WARNING, {fp_l10n}.getString("warn.data_connection_error"), e);',
        ),
    ])

    handler = FTP / "FtpProtocolHandler.java"
    hp: list[tuple[str, str]] = [
        ('LOGGER.log(Level.WARNING, "Failed to send welcome banner", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_welcome_banner"), e);'),
        ('LOGGER.log(Level.WARNING, "FTP transport error", cause);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.ftp_transport_error"), cause);'),
        ('LOGGER.log(Level.WARNING, "Error processing FTP command", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.error_processing_ftp_command"), e);'),
        ('LOGGER.log(Level.SEVERE, "Cannot write error reply", e2);',
         f'LOGGER.log(Level.SEVERE, {l10n}.getString("warn.cannot_write_error_reply"), e2);'),
        ('LOGGER.log(Level.WARNING, "Failed to send FTP reply " + code, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.failed_send_ftp_reply"), code), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send FTP line", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_ftp_line"), e);'),
        ('LOGGER.log(Level.WARNING, "CWD failed for " + targetPath, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.cwd_failed"), targetPath), error);'),
        ('LOGGER.log(Level.WARNING, "CDUP failed", error);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.cdup_failed"), error);'),
        ('LOGGER.log(Level.WARNING, "Failed to set up passive mode", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.passive_mode_setup_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to set up extended passive mode", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.epsv_setup_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "RETR failed for " + filePath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.retr_failed"), filePath), e);'),
        ('LOGGER.log(Level.WARNING, "STOR failed for " + filePath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.stor_failed"), filePath), e);'),
        ('LOGGER.log(Level.WARNING, "STOU failed", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.stou_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send STOU error", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.stou_error_reply_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "APPE failed for " + filePath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.appe_failed"), filePath), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send APPE error", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.appe_error_reply_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "RNFR failed for " + sourcePath, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.rnfr_failed"), sourcePath), error);'),
        ('LOGGER.log(Level.WARNING, "DELE failed for " + filePath, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.dele_failed"), filePath), error);'),
        ('LOGGER.log(Level.WARNING, "RMD failed for " + dirPath, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.rmd_failed"), dirPath), error);'),
        ('LOGGER.log(Level.WARNING, "MKD failed for " + dirPath, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.mkd_failed"), dirPath), error);'),
        ('LOGGER.log(Level.WARNING, "LIST failed for " + listPath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.list_failed"), listPath), e);'),
        ('LOGGER.log(Level.WARNING, "NLST failed for " + listPath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.nlst_failed"), listPath), e);'),
        ('LOGGER.log(Level.WARNING, "STAT failed for " + path, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.stat_failed"), path), error);'),
        ('LOGGER.log(Level.WARNING, "AUTH TLS failed", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.auth_tls_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "SIZE failed for " + path, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.size_failed"), path), error);'),
        ('LOGGER.log(Level.WARNING, "MDTM failed for " + path, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.mdtm_failed"), path), error);'),
        ('LOGGER.log(Level.WARNING, "MLST failed for " + path, error);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.mlst_failed"), path), error);'),
        ('LOGGER.log(Level.WARNING, "MLSD failed for " + listPath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.mlsd_failed"), listPath), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send login response", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_login_response"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send service unavailable", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_service_unavailable"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send need password reply", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_need_password"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send need account reply", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_need_account"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send login successful", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_login_successful"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send command ok", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_command_ok"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send certificate login failure", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_cert_login_failure"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to send login rejection", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.failed_send_login_rejection"), e);'),
    ]
    _patch_file(handler, hp)

    client = FTP / "client/FtpClientProtocolHandler.java"
    _patch_file(client, [
        ('LOGGER.fine("FTP client connected to " + ep.getRemoteAddress());',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.client_connected"), ep.getRemoteAddress()));'),
        ('LOGGER.fine("TLS established: " + info.getCipherSuite());',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.tls_established"), info.getCipherSuite()));'),
        ('LOGGER.fine("Sent FTP command: PASS ***");',
         f'LOGGER.fine({l10n}.getString("debug.sent_ftp_command_pass"));'),
        ('LOGGER.fine("Sent FTP command: " + command);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.sent_ftp_command"), command));'),
        ('LOGGER.fine("Ignoring response in state " + state + ": " + code);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.ignoring_response_in_state"), state, code));'),
        ('LOGGER.fine("Received FTP response: " + code + " " + message);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.received_ftp_response"), code, message));'),
    ])

    basic = FTP / "file/BasicFTPFileSystem.java"
    _patch_file(basic, [
        ('LOGGER.fine("Directory does not exist: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.directory_not_exist"), path));'),
        ('LOGGER.fine("Path is not a directory: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.path_not_directory"), path));'),
        ('LOGGER.fine("Skipping file due to error: " + child + " - " + e.getMessage());',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.skip_list_child_error"), child, e.getMessage()));'),
        ('LOGGER.fine("Listed " + files.size() + " items in directory: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.listed_directory_items"), files.size(), path));'),
        ('LOGGER.log(Level.WARNING, "Security violation in listDirectory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_list_directory"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error listing directory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_listing_directory"), path), e);'),
        ('LOGGER.fine("Changed directory from " + currentDirectory + " to " + newFtpPath);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.changed_directory"), currentDirectory, newFtpPath));'),
        ('LOGGER.log(Level.WARNING, "Security violation in changeDirectory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_change_directory"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error changing directory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_changing_directory"), path), e);'),
        ('LOGGER.fine("Error getting file info for " + path + ": " + e.getMessage());',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.error_file_info"), path, e.getMessage()));'),
        ('LOGGER.fine("Created directory: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.created_directory"), path));'),
        ('LOGGER.log(Level.WARNING, "Security violation in createDirectory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_create_directory"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error creating directory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_creating_directory"), path), e);'),
        ('LOGGER.fine("Removed directory: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.removed_directory"), path));'),
        ('LOGGER.log(Level.WARNING, "Security violation in removeDirectory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_remove_directory"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error removing directory: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_removing_directory"), path), e);'),
        ('LOGGER.fine("Deleted file: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.deleted_file"), path));'),
        ('LOGGER.log(Level.WARNING, "Security violation in deleteFile: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_delete_file"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error deleting file: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_deleting_file"), path), e);'),
        ('LOGGER.fine("Renamed " + fromPath + " to " + toPath);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.renamed_file"), fromPath, toPath));'),
        ('LOGGER.fine("File not found for reading: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.file_not_found_reading"), path));'),
        ('LOGGER.fine("Cannot read directory as file: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.cannot_read_directory_as_file"), path));'),
        ('LOGGER.fine("Positioned file channel at restart offset: " + restartOffset);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.file_channel_restart_offset"), restartOffset));'),
        (
            'LOGGER.fine("Opened file for reading: " + path + \n'
            '                           (restartOffset > 0 ? " (offset: " + restartOffset + ")" : ""));',
            'LOGGER.fine(MessageFormat.format(L10N.getString("debug.opened_file_reading"), path,\n'
            '                        restartOffset > 0 ? MessageFormat.format(L10N.getString("debug.opened_file_reading_offset_suffix"), restartOffset) : ""));',
        ),
        ('LOGGER.log(Level.WARNING, "Security violation in openForReading: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_open_reading"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error opening file for reading: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_opening_file_reading"), path), e);'),
        ('LOGGER.fine("Write denied - file system is read-only: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.write_denied_readonly"), path));'),
        ('LOGGER.fine("Cannot write to directory: " + path);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.cannot_write_to_directory"), path));'),
        (
            'LOGGER.fine("Opened file for writing: " + path + \n'
            '                           (append ? " (append mode)" : " (overwrite mode)"));',
            'LOGGER.fine(MessageFormat.format(L10N.getString("debug.opened_file_writing"), path,\n'
            '                        append ? L10N.getString("debug.opened_file_writing_append") : L10N.getString("debug.opened_file_writing_overwrite")));',
        ),
        ('LOGGER.log(Level.WARNING, "Security violation in openForWriting: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_open_writing"), path), e);'),
        ('LOGGER.log(Level.WARNING, "Error opening file for writing: " + path, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_opening_file_writing"), path), e);'),
        ('LOGGER.fine("Generated unique name: " + ftpPath);',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.generated_unique_name"), ftpPath));'),
        ('LOGGER.log(Level.WARNING, "Security violation in generateUniqueName: " + basePath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.security_violation_generate_unique_name"), basePath), e);'),
        ('LOGGER.log(Level.WARNING, "Error generating unique name: " + basePath, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({l10n}.getString("warn.error_generating_unique_name"), basePath), e);'),
        ('LOGGER.fine("ALLO command for " + path + " (" + size + " bytes) - no-op");',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.allo_noop"), path, size));'),
    ])

    role_aware = FTP / "file/RoleAwareFTPFileSystem.java"
    if "ResourceBundle" not in role_aware.read_text(encoding="utf-8"):
        text = role_aware.read_text(encoding="utf-8")
        text = text.replace(
            "import java.nio.file.Path;\n",
            "import java.nio.file.Path;\nimport java.text.MessageFormat;\nimport java.util.ResourceBundle;\n",
            1,
        )
        text = text.replace(
            "private static final Logger LOGGER = Logger.getLogger(RoleAwareFTPFileSystem.class.getName());",
            "private static final Logger LOGGER = Logger.getLogger(RoleAwareFTPFileSystem.class.getName());\n\n"
            '    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");',
            1,
        )
        role_aware.write_text(text, encoding="utf-8")
    _patch_file(role_aware, [
        (
            'LOGGER.fine("Access denied for user " + user\n'
            '                    + ": missing role " + role);',
            'LOGGER.fine(MessageFormat.format(L10N.getString("debug.access_denied_missing_role"), user, role));',
        ),
        (
            'LOGGER.fine("Home confinement denied for user " + user\n'
            '                    + ": path " + path + " is outside " + homePath);',
            'LOGGER.fine(MessageFormat.format(L10N.getString("debug.home_confinement_denied"), user, path, homePath));',
        ),
    ])

    role_handler = FTP / "file/RoleBasedFTPHandler.java"
    _patch_file(role_handler, [
        ('LOGGER.fine("FTP connection from " + metadata.getClientAddress());',
         f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.role_handler_connection"), metadata.getClientAddress()));'),
        (
            'LOGGER.fine("Authorization denied: user=" + username + \n'
            '                       ", operation=" + operation + ", path=" + path);',
            f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.authorization_denied"), username, operation, path));',
        ),
        (
            'String direction = upload ? "upload" : "download";\n'
            '            LOGGER.fine("Transfer starting: " + direction + " " + path + \n'
            '                       " (size=" + size + ") by " + metadata.getAuthenticatedUser());',
            'String direction = upload ? L10N.getString("transfer.upload") : L10N.getString("transfer.download");\n'
            '            LOGGER.fine(MessageFormat.format(L10N.getString("debug.transfer_starting_detail"), direction, path, size, metadata.getAuthenticatedUser()));',
        ),
        (
            'LOGGER.fine("FTP disconnected: user=" + metadata.getAuthenticatedUser() +\n'
            '                       ", duration=" + duration + "ms");',
            f'LOGGER.fine(MessageFormat.format({l10n}.getString("debug.role_handler_disconnected"), metadata.getAuthenticatedUser(), duration));',
        ),
    ])

    default_handler = FTP / "server/DefaultFtpHandler.java"
    _patch_file(default_handler, [
        ('LOGGER.fine("FTP session closed");',
         f'LOGGER.fine({l10n}.getString("debug.session_closed"));'),
        ('LOGGER.log(Level.WARNING, "FTP authentication error", e);',
         f'LOGGER.log(Level.WARNING, {l10n}.getString("warn.ftp_authentication_error"), e);'),
    ])

# --- HTTP (org.bluezoo.gumdrop.http) ---

HTTP = GUMDROP / "http"

HTTP_MAIN_EN = {
    "debug.scheduled_stream_priority": "Scheduled stream {0} for processing (priority-based)",
    "debug.removed_stream_scheduler": "Removed stream {0} from scheduler",
    "debug.selected_stream_starvation": "Selected stream {0} for starvation prevention (idle {1}ms)",
    "debug.selected_stream_burst": "Selected stream {0} for burst control (avoiding {1} burst)",
    "debug.updated_stream_priority": "Updated stream priority: {0}",
    "debug.removed_stream_priority_tree": "Removed stream {0} from priority tree",
    "warn.http_transport_error": "HTTP transport error",
    "warn.error_sending_data_frame": "Error sending data frame",
    "debug.switched_websocket_mode": "Switched to WebSocket mode for stream {0}",
    "warn.hpack_encode_failed": "Failed to encode headers using HPACK",
    "warn.error_sending_push_promise": "Error sending PUSH_PROMISE",
    "warn.failed_create_pushed_stream": "Failed to create pushed stream {0}",
    "warn.error_deferred_window_update": "Error sending deferred WINDOW_UPDATE",
    "debug.closing_idle_http_connection": "Closing idle HTTP connection after {0}ms",
    "warn.error_options_star_response": "Error sending OPTIONS * response",
    "warn.error_trace_response": "Error sending TRACE response",
    "debug.h2c_upgrade_pending_body": "h2c upgrade pending until request body consumed",
    "debug.consumed_h2_connection_preface": "Consumed HTTP/2 connection preface (24 bytes)",
    "debug.h2_frame_data_preview": "HTTP/2 frame data (first 9 bytes): hex=[{0}] ascii=[{1}]",
    "debug.sent_101_switching_protocols": "Sent 101 Switching Protocols, waiting for client preface",
    "warn.error_sending_window_update": "Error sending WINDOW_UPDATE",
    "debug.rst_stream_received": "RST_STREAM received: stream={0}, error={1}",
    "warn.error_sending_headers": "Error sending headers",
    "warn.error_flushing_h2_frames": "Error flushing HTTP/2 frames",
    "warn.error_websocket_data": "Error processing WebSocket data",
    "warn.server_push_failed": "Failed to execute server push for {0}",
    "debug.ignore_content_length_chunked_set": "Ignoring Content-Length; chunked encoding already set",
    "debug.ignore_content_length_chunked_precedence": "Ignoring Content-Length; chunked encoding takes precedence",
    "warn.connect_udp_upstream_open_failed": "Failed to open CONNECT-UDP upstream socket",
}

HTTP_CLIENT_EN = {
    "warn.http_request_failed": "HTTP request failed",
    "debug.sent_http11_h2c_upgrade": "Sent HTTP/1.1 request with h2c upgrade: {0} {1}",
    "debug.sent_http11_request": "Sent HTTP/1.1 request: {0} {1}",
    "warn.error_encoding_h2_request_headers": "Error encoding HTTP/2 request headers",
    "warn.error_in_response_handler": "Error in response handler",
    "debug.h2_preface_h2c_complete": "HTTP/2 connection preface sent, h2c upgrade complete to {0}:{1}",
    "debug.h2c_upgrade_accepted": "h2c upgrade accepted, switching to HTTP/2",
    "debug.h2c_upgrade_declined": "Server declined h2c upgrade, continuing with HTTP/1.1",
    "debug.connection_close_closing": "Server sent Connection: close — closing connection",
    "debug.response_complete": "Response complete",
    "debug.auth_retry_scheme": "Authentication retry initiated with {0}",
    "debug.h2_handshake_complete": "HTTP/2 handshake complete, ready for requests",
    "warn.hpack_decode_push_promise": "HPACK decode error in PUSH_PROMISE",
    "warn.error_push_promise_callback": "Error in pushPromise callback",
    "warn.error_sending_ping_ack": "Error sending PING ACK",
    "warn.hpack_decode_error": "HPACK decode error",
    "warn.error_draining_pending_data": "Error draining pending data",
    "warn.error_sending_h2_request": "Error sending HTTP/2 request",
    "warn.error_sending_h2_preface": "Error sending HTTP/2 connection preface",
    "warn.error_sending_settings_ack": "Error sending SETTINGS ACK",
    "warn.error_sending_rst_stream": "Error sending RST_STREAM",
    "warn.error_sending_goaway": "Error sending GOAWAY",
    "debug.h2_prior_knowledge_connected": "HTTP/2 connection established to {0}:{1} (prior knowledge)",
    "debug.http11_h2c_upgrade_pending": "HTTP/1.1 connection established to {0}:{1}, will attempt h2c upgrade",
    "debug.http11_connected": "HTTP/1.1 connection established to {0}:{1}",
    "warn.blocked_h2_cipher_suite_client": "HTTP/2 blocked cipher suite: {0}",
    "debug.h2_alpn_connected": "HTTP/2 (ALPN) connection established to {0}:{1}",
    "warn.error_sending_window_update": "Error sending WINDOW_UPDATE",
    "warn.error_notifying_handler": "Error notifying handler",
    "debug.idle_timeout_closing": "Idle timeout ({0}ms) — closing connection",
    "debug.error_closing_connection": "Error closing connection",
}

HTTP_H2_EN = {
    "debug.finest_parsing_frame": "Parsing frame: type={0}, flags={1}, stream={2}, length={3}",
    "debug.ignoring_unknown_frame_type": "Ignoring unknown frame type: {0}",
    "debug.finest_wrote_frame": "Wrote {0} frame: stream={1}, length={2}, flags={3}",
}



def ensure_scheduler_l10n() -> None:
    path = HTTP / "StreamPriorityScheduler.java"
    text = path.read_text(encoding="utf-8")
    if "ResourceBundle L10N" not in text:
        text = text.replace(
            "import java.util.logging.Logger;\n",
            "import java.text.MessageFormat;\nimport java.util.ResourceBundle;\nimport java.util.logging.Logger;\n",
            1,
        )
        text = text.replace(
            "private static final Logger LOGGER = Logger.getLogger(StreamPriorityScheduler.class.getName());",
            "private static final Logger LOGGER = Logger.getLogger(StreamPriorityScheduler.class.getName());\n\n"
            '    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.L10N");',
            1,
        )
        path.write_text(text, encoding="utf-8")


def ensure_default_handler_l10n() -> None:
    path = HTTP / "client/DefaultHttpResponseHandler.java"
    text = path.read_text(encoding="utf-8")
    if "ResourceBundle L10N" not in text:
        text = text.replace(
            "import java.nio.ByteBuffer;\n",
            "import java.nio.ByteBuffer;\nimport java.util.ResourceBundle;\n",
            1,
        )
        text = text.replace(
            "private static final Logger logger = Logger.getLogger(DefaultHttpResponseHandler.class.getName());",
            "private static final Logger logger = Logger.getLogger(DefaultHttpResponseHandler.class.getName());\n\n"
            '    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.http.client.L10N");',
            1,
        )
        path.write_text(text, encoding="utf-8")


def apply_http() -> None:
    _append_bundle_en_only(HTTP, HTTP_MAIN_EN)
    _append_bundle_en_only(HTTP / "client", HTTP_CLIENT_EN)
    _append_bundle_en_only(HTTP / "h2", HTTP_H2_EN)
    ensure_scheduler_l10n()
    ensure_default_handler_l10n()

    L = "L10N"
    H2L = "L10N"

    _patch_file(HTTP / "StreamPriorityScheduler.java", [
        ('LOGGER.fine("Scheduled stream " + selectedStream + " for processing (priority-based)");',
         'LOGGER.fine(MessageFormat.format(L10N.getString("debug.scheduled_stream_priority"), selectedStream));'),
        ('LOGGER.fine("Removed stream " + streamId + " from scheduler");',
         'LOGGER.fine(MessageFormat.format(L10N.getString("debug.removed_stream_scheduler"), streamId));'),
        ('LOGGER.fine("Selected stream " + streamId + " for starvation prevention (idle " + \n'
         '                        timeSinceLastSchedule + "ms)");',
         'LOGGER.fine(MessageFormat.format(L10N.getString("debug.selected_stream_starvation"), streamId, timeSinceLastSchedule));'),
        ('LOGGER.fine("Selected stream " + candidateStream + " for burst control (avoiding " + \n'
         '                        highestPriorityStream + " burst)");',
         'LOGGER.fine(MessageFormat.format(L10N.getString("debug.selected_stream_burst"), candidateStream, highestPriorityStream));'),
    ])

    _patch_file(HTTP / "StreamPriorityTree.java", [
        ('LOGGER.fine("Updated stream priority: " + nodes.get(streamId));',
         'LOGGER.fine(MessageFormat.format(L10N.getString("debug.updated_stream_priority"), nodes.get(streamId)));'),
        ('LOGGER.fine("Removed stream " + streamId + " from priority tree");',
         'LOGGER.fine(MessageFormat.format(L10N.getString("debug.removed_stream_priority_tree"), streamId));'),
    ])

    _patch_file(HTTP / "client/DefaultHttpResponseHandler.java", [
        ('logger.log(Level.WARNING, "HTTP request failed", ex);',
         'logger.log(Level.WARNING, L10N.getString("warn.http_request_failed"), ex);'),
    ])

    client = HTTP / "client/HttpClientProtocolHandler.java"
    cp = [
        ('LOGGER.fine("Sent HTTP/1.1 request with h2c upgrade: " + request.getMethod() + " " + request.getPath());',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.sent_http11_h2c_upgrade"), request.getMethod(), request.getPath()));'),
        ('LOGGER.fine("Sent HTTP/1.1 request: " + request.getMethod() + " " + request.getPath());',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.sent_http11_request"), request.getMethod(), request.getPath()));'),
        ('LOGGER.log(Level.WARNING, "Error encoding HTTP/2 request headers", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_encoding_h2_request_headers"), e);'),
        ('LOGGER.log(Level.WARNING, "Error in response handler", ex);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_in_response_handler"), ex);'),
        ('LOGGER.log(Level.WARNING, "Error in response handler", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_in_response_handler"), e);'),
        ('LOGGER.fine("HTTP/2 connection preface sent, h2c upgrade complete to " + host + ":" + port);',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.h2_preface_h2c_complete"), host, port));'),
        ('LOGGER.fine("h2c upgrade accepted, switching to HTTP/2");',
         f'LOGGER.fine({L}.getString("debug.h2c_upgrade_accepted"));'),
        ('LOGGER.fine("Server declined h2c upgrade, continuing with HTTP/1.1");',
         f'LOGGER.fine({L}.getString("debug.h2c_upgrade_declined"));'),
        ('LOGGER.fine("Server sent Connection: close — closing connection");',
         f'LOGGER.fine({L}.getString("debug.connection_close_closing"));'),
        ('LOGGER.fine("Response complete");',
         f'LOGGER.fine({L}.getString("debug.response_complete"));'),
        ('LOGGER.fine("Authentication retry initiated with " + scheme);',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.auth_retry_scheme"), scheme));'),
        ('LOGGER.log(Level.WARNING, "Error sending WINDOW_UPDATE", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_window_update"), e);'),
        ('LOGGER.fine("HTTP/2 handshake complete, ready for requests");',
         f'LOGGER.fine({L}.getString("debug.h2_handshake_complete"));'),
        ('LOGGER.log(Level.WARNING, "HPACK decode error in PUSH_PROMISE", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.hpack_decode_push_promise"), e);'),
        ('LOGGER.log(Level.WARNING, "Error in pushPromise callback", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_push_promise_callback"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending PING ACK", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_ping_ack"), e);'),
        ('LOGGER.log(Level.WARNING, "HPACK decode error", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.hpack_decode_error"), e);'),
        ('LOGGER.log(Level.WARNING, "Error draining pending data", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_draining_pending_data"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending HTTP/2 request", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_h2_request"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending HTTP/2 connection preface", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_h2_preface"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending SETTINGS ACK", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_settings_ack"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending RST_STREAM", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_rst_stream"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending GOAWAY", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_goaway"), e);'),
        ('LOGGER.fine("HTTP/2 connection established to " + host + ":" + port + " (prior knowledge)");',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.h2_prior_knowledge_connected"), host, port));'),
        ('LOGGER.fine("HTTP/1.1 connection established to " + host + ":" + port + ", will attempt h2c upgrade");',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.http11_h2c_upgrade_pending"), host, port));'),
        ('LOGGER.fine("HTTP/1.1 connection established to " + host + ":" + port);',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.http11_connected"), host, port));'),
        ('LOGGER.warning("HTTP/2 blocked cipher suite: " + cipher);',
         f'LOGGER.warning(MessageFormat.format({L}.getString("warn.blocked_h2_cipher_suite_client"), cipher));'),
        ('LOGGER.fine("HTTP/2 (ALPN) connection established to " + host + ":" + port);',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.h2_alpn_connected"), host, port));'),
        ('LOGGER.log(Level.WARNING, "Error notifying handler", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_notifying_handler"), e);'),
        ('LOGGER.fine("Idle timeout (" + idleTimeoutMs + "ms) — closing connection");',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.idle_timeout_closing"), idleTimeoutMs));'),
        ('LOGGER.log(Level.FINE, "Error closing connection", e);',
         f'LOGGER.log(Level.FINE, {L}.getString("debug.error_closing_connection"), e);'),
    ]
    _patch_file(client, cp)

    _patch_file(HTTP / "h2/H2Parser.java", [
        ('LOGGER.finest("Parsing frame: type=" + type + ", flags=" + flags +\n'
         '                    ", stream=" + streamId + ", length=" + length);',
         f'LOGGER.finest(MessageFormat.format({H2L}.getString("debug.finest_parsing_frame"), type, flags, streamId, length));'),
        ('LOGGER.fine("Ignoring unknown frame type: " + type);',
         f'LOGGER.fine(MessageFormat.format({H2L}.getString("debug.ignoring_unknown_frame_type"), type));'),
    ])

    _patch_file(HTTP / "h2/H2Writer.java", [
        ('LOGGER.finest("Wrote " + type + " frame: stream=" + streamId +\n'
         '                ", length=" + length + ", flags=" + flags);',
         f'LOGGER.finest(MessageFormat.format({H2L}.getString("debug.finest_wrote_frame"), type, streamId, length, flags));'),
    ])

    _patch_file(HTTP / "server/ConnectUdpRequestHandler.java", [
        ('LOGGER.log(Level.WARNING, "Failed to open CONNECT-UDP upstream socket", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.connect_udp_upstream_open_failed"), e);'),
    ])

    srv = HTTP / "server/HttpProtocolHandler.java"
    _patch_file(srv, [
        ('LOGGER.log(Level.WARNING, "Error sending data frame", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_data_frame"), e);'),
        ('LOGGER.fine("Switched to WebSocket mode for stream " + streamId);',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.switched_websocket_mode"), streamId));'),
        ('LOGGER.log(Level.WARNING, "Failed to encode headers using HPACK", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.hpack_encode_failed"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending PUSH_PROMISE", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_push_promise"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to create pushed stream " + streamId, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({L}.getString("warn.failed_create_pushed_stream"), streamId), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending deferred WINDOW_UPDATE", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_deferred_window_update"), e);'),
        ('LOGGER.fine("Closing idle HTTP connection after "\n'
         '                                + timeoutMs + "ms");',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.closing_idle_http_connection"), timeoutMs));'),
        ('LOGGER.log(Level.WARNING, "Error sending OPTIONS * response", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_options_star_response"), e);'),
        ('LOGGER.log(Level.WARNING, "Error sending TRACE response", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_trace_response"), e);'),
        ('LOGGER.fine("h2c upgrade pending until request body consumed");',
         f'LOGGER.fine({L}.getString("debug.h2c_upgrade_pending_body"));'),
        ('LOGGER.fine("Consumed HTTP/2 connection preface"\n'
         '                            + " (24 bytes)");',
         f'LOGGER.fine({L}.getString("debug.consumed_h2_connection_preface"));'),
        ('LOGGER.fine("HTTP/2 frame data (first 9 bytes): hex=[" + hex.toString().trim() \n'
         '                + "] ascii=[" + ascii.toString() + "]");',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.h2_frame_data_preview"), hex.toString().trim(), ascii.toString()));'),
        ('LOGGER.fine("Sent 101 Switching Protocols, waiting for client preface");',
         f'LOGGER.fine({L}.getString("debug.sent_101_switching_protocols"));'),
        ('LOGGER.log(Level.WARNING, "Error sending WINDOW_UPDATE", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_window_update"), e);'),
        ('LOGGER.fine("RST_STREAM received: stream=" + streamId\n'
         '                    + ", error="\n'
         '                    + H2FrameHandler.errorToString(errorCode));',
         f'LOGGER.fine(MessageFormat.format({L}.getString("debug.rst_stream_received"), streamId, H2FrameHandler.errorToString(errorCode)));'),
        ('LOGGER.log(Level.WARNING, "HTTP transport error", cause);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.http_transport_error"), cause);'),
        ('LOGGER.log(Level.WARNING, "Error sending headers", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_sending_headers"), e);'),
        ('LOGGER.log(Level.WARNING, "Error flushing HTTP/2 frames", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_flushing_h2_frames"), e);'),
    ])

    _patch_file(HTTP / "server/Stream.java", [
        ('LOGGER.log(Level.WARNING, "Error processing WebSocket data", e);',
         f'LOGGER.log(Level.WARNING, {L}.getString("warn.error_websocket_data"), e);'),
        ('LOGGER.log(Level.WARNING, "Failed to execute server push for " + uri, e);',
         f'LOGGER.log(Level.WARNING, MessageFormat.format({L}.getString("warn.server_push_failed"), uri), e);'),
        ('LOGGER.fine("Ignoring Content-Length; chunked "\n'
         '                                    + "encoding already set");',
         f'LOGGER.fine({L}.getString("debug.ignore_content_length_chunked_set"));'),
        ('LOGGER.fine("Ignoring Content-Length; chunked encoding takes "\n'
         '                    + "precedence");',
         f'LOGGER.fine({L}.getString("debug.ignore_content_length_chunked_precedence"));'),
    ])

def scan_cmd() -> None:
    violations = scan_violations()
    if not violations:
        print("No open logger L10N violations.")
        return
    for line in violations:
        print(line)


def apply_all() -> None:
    apply_ftp()
    apply_http()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("scan", help="List logger lines that fail L10nLogGuard")
    sub.add_parser("inventory", help="Violation counts by package")

    sub.add_parser("apply", help="Run registered L10N text replacements (idempotent)")

    args = parser.parse_args()
    if args.command == "scan":
        scan_cmd()
        return
    if args.command == "inventory":
        inventory()
        return

    apply_all()


if __name__ == "__main__":
    main()
