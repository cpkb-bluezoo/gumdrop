/*
 * MailboxIdFile.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.mailbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads and writes the small sidecar file holding a mailbox's RFC 8474
 * MAILBOXID: a stable identifier for the mailbox itself (as opposed to
 * {@link MessageIndex the per-message search index}, which is rebuilt
 * routinely and covers only messages).
 *
 * <p>Deliberately its own tiny sidecar rather than a field inside the
 * {@code .gidx} message index: the index is expected to be discarded and
 * rebuilt on corruption or a format version bump (see {@link MessageIndex
 * MessageIndex}'s version handling), but MAILBOXID must survive that --
 * losing it and generating a new one on every index rebuild would defeat
 * the point of the identifier being stable.
 *
 * <p>Format is a single line holding the ID, no header -- unlike {@code
 * .uidlist}/{@code .modseq}, this file carries only one value and never
 * grows, so there is nothing worth a versioned header for.
 *
 * <p>The stored value is a random {@link UUID}'s canonical string form,
 * which already satisfies RFC 8474's {@code validid} grammar ({@code
 * ALPHA / DIGIT / "-" / "_"}: a UUID string is hex digits and hyphens
 * only) without any further encoding.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxIdFile {

    private static final Logger LOGGER = Logger.getLogger(MailboxIdFile.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.mailbox.L10N");

    private MailboxIdFile() {
    }

    /**
     * Reads the MAILBOXID from {@code sidecarPath}.
     *
     * @param sidecarPath path to the sidecar file
     * @return the stored ID, or null if the file does not exist, is
     *         empty, or does not hold a validly-formed ID (in which case
     *         a warning is logged and the caller should generate and
     *         {@link #save persist} a new one)
     * @throws IOException if the file exists but cannot be read
     */
    public static String load(Path sidecarPath) throws IOException {
        if (!Files.exists(sidecarPath)) {
            return null;
        }
        String line;
        try (BufferedReader reader =
                Files.newBufferedReader(sidecarPath, StandardCharsets.UTF_8)) {
            line = reader.readLine();
        }
        if (line != null) {
            line = line.trim();
        }
        if (line == null || line.isEmpty() || !isValidObjectId(line)) {
            LOGGER.log(Level.WARNING, MessageFormat.format(
                    L10N.getString("warn.invalid_mailboxid_file"), sidecarPath));
            return null;
        }
        return line;
    }

    /**
     * Atomically writes {@code id} to {@code sidecarPath}.
     *
     * @param sidecarPath path to the sidecar file
     * @param id the MAILBOXID to write
     * @throws IOException if the file cannot be written
     */
    public static void save(Path sidecarPath, String id) throws IOException {
        Path parent = sidecarPath.getParent();
        String prefix = sidecarPath.getFileName().toString() + "-";
        Path tempPath = (parent != null)
                ? Files.createTempFile(parent, prefix, ".tmp")
                : Files.createTempFile(prefix, ".tmp");
        try {
            Files.write(tempPath, (id + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tempPath, sidecarPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
    }

    /**
     * Generates a new MAILBOXID.
     *
     * @return a freshly-generated, RFC 8474-valid ID
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Checks whether {@code id} satisfies RFC 8474's {@code validid}
     * grammar: {@code 1*(ALPHA / DIGIT / "-" / "_")}.
     */
    private static boolean isValidObjectId(String id) {
        if (id.isEmpty()) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

}
