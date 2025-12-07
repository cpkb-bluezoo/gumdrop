/*
 * MaildirUidList.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mailbox.maildir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the UID list file for a Maildir mailbox.
 * 
 * <p>The UID list file maps message filenames to persistent UIDs,
 * which are required by IMAP. The file format is:
 * <pre>
 * # gumdrop-uidlist v1
 * uidvalidity 1733356800
 * uidnext 42
 * 1 1733356800000.12345.1,S=4523
 * 2 1733356800001.12345.2,S=1234
 * ...
 * </pre>
 * 
 * <p>The base filename (without flags) is stored, since flags can change
 * via renames without affecting the message identity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirUidList {

    private static final Logger LOGGER = Logger.getLogger(MaildirUidList.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.mailbox.L10N");

    private static final String UIDLIST_FILENAME = ".uidlist";
    private static final String HEADER_LINE = "# gumdrop-uidlist v1";

    private final Path maildirPath;
    private final Path uidlistPath;

    private long uidValidity;
    private long uidNext;
    
    /** Maps base filename (without flags) to UID */
    private Map<String, Long> filenameToUid;
    
    /** Maps UID to base filename */
    private Map<Long, String> uidToFilename;

    private boolean dirty;

    /**
     * Creates a UID list manager for the specified Maildir.
     *
     * @param maildirPath the path to the Maildir directory
     */
    public MaildirUidList(Path maildirPath) {
        this.maildirPath = maildirPath;
        this.uidlistPath = maildirPath.resolve(UIDLIST_FILENAME);
        this.filenameToUid = new HashMap<>();
        this.uidToFilename = new HashMap<>();
        this.dirty = false;
    }

    /**
     * Loads the UID list from disk, or creates a new one if it doesn't exist.
     *
     * @throws IOException if the file cannot be read
     */
    public void load() throws IOException {
        filenameToUid.clear();
        uidToFilename.clear();

        if (!Files.exists(uidlistPath)) {
            // Initialize new UID list
            uidValidity = System.currentTimeMillis() / 1000;
            uidNext = 1;
            dirty = true;
            return;
        }

        BufferedReader reader = Files.newBufferedReader(uidlistPath, StandardCharsets.UTF_8);
        try {
            String line = reader.readLine();
            
            // Check header
            if (line == null || !line.equals(HEADER_LINE)) {
                String msg = MessageFormat.format(L10N.getString("err.invalid_uidlist_header"), maildirPath);
                LOGGER.warning(msg);
                uidValidity = System.currentTimeMillis() / 1000;
                uidNext = 1;
                dirty = true;
                return;
            }

            // Read metadata lines
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith("uidvalidity ")) {
                    try {
                        uidValidity = Long.parseLong(line.substring(12));
                    } catch (NumberFormatException e) {
                        String msg = MessageFormat.format(L10N.getString("err.invalid_uidvalidity"), line);
                        LOGGER.warning(msg);
                    }
                } else if (line.startsWith("uidnext ")) {
                    try {
                        uidNext = Long.parseLong(line.substring(8));
                    } catch (NumberFormatException e) {
                        String msg = MessageFormat.format(L10N.getString("err.invalid_uidnext"), line);
                        LOGGER.warning(msg);
                    }
                } else {
                    // UID mapping line: "uid basefilename"
                    int spaceIndex = line.indexOf(' ');
                    if (spaceIndex > 0) {
                        try {
                            long uid = Long.parseLong(line.substring(0, spaceIndex));
                            String baseFilename = line.substring(spaceIndex + 1);
                            filenameToUid.put(baseFilename, uid);
                            uidToFilename.put(uid, baseFilename);
                        } catch (NumberFormatException e) {
                            String msg = MessageFormat.format(L10N.getString("err.invalid_uid_mapping"), line);
                            LOGGER.warning(msg);
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }

        dirty = false;
    }

    /**
     * Saves the UID list to disk.
     *
     * @throws IOException if the file cannot be written
     */
    public void save() throws IOException {
        if (!dirty) {
            return;
        }

        // Write to temp file first, then rename for atomicity
        Path tempPath = maildirPath.resolve(UIDLIST_FILENAME + ".tmp");

        BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8);
        try {
            writer.write(HEADER_LINE);
            writer.newLine();
            writer.write("uidvalidity ");
            writer.write(Long.toString(uidValidity));
            writer.newLine();
            writer.write("uidnext ");
            writer.write(Long.toString(uidNext));
            writer.newLine();

            // Write UID mappings sorted by UID
            long[] sortedUids = new long[uidToFilename.size()];
            int idx = 0;
            for (Long uid : uidToFilename.keySet()) {
                sortedUids[idx++] = uid;
            }
            java.util.Arrays.sort(sortedUids);

            for (long uid : sortedUids) {
                String baseFilename = uidToFilename.get(uid);
                writer.write(Long.toString(uid));
                writer.write(' ');
                writer.write(baseFilename);
                writer.newLine();
            }
        } finally {
            writer.close();
        }

        // Atomic rename
        Files.move(tempPath, uidlistPath, StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }

    /**
     * Returns the UIDVALIDITY for this mailbox.
     *
     * @return the UIDVALIDITY value
     */
    public long getUidValidity() {
        return uidValidity;
    }

    /**
     * Returns the next UID that will be assigned.
     *
     * @return the next UID value
     */
    public long getUidNext() {
        return uidNext;
    }

    /**
     * Returns the UID for a message file.
     *
     * @param baseFilename the base filename (without flags)
     * @return the UID, or -1 if not found
     */
    public long getUid(String baseFilename) {
        Long uid = filenameToUid.get(baseFilename);
        return uid != null ? uid : -1;
    }

    /**
     * Returns the base filename for a UID.
     *
     * @param uid the UID
     * @return the base filename, or null if not found
     */
    public String getFilename(long uid) {
        return uidToFilename.get(uid);
    }

    /**
     * Assigns a new UID to a message file.
     *
     * @param baseFilename the base filename (without flags)
     * @return the assigned UID
     */
    public long assignUid(String baseFilename) {
        // Check if already assigned
        Long existing = filenameToUid.get(baseFilename);
        if (existing != null) {
            return existing;
        }

        long uid = uidNext++;
        filenameToUid.put(baseFilename, uid);
        uidToFilename.put(uid, baseFilename);
        dirty = true;
        return uid;
    }

    /**
     * Removes a UID mapping when a message is expunged.
     *
     * @param baseFilename the base filename
     */
    public void removeUid(String baseFilename) {
        Long uid = filenameToUid.remove(baseFilename);
        if (uid != null) {
            uidToFilename.remove(uid);
            dirty = true;
        }
    }

    /**
     * Returns whether the UID list has been modified.
     *
     * @return true if dirty
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Returns the number of UID mappings.
     *
     * @return the count
     */
    public int size() {
        return filenameToUid.size();
    }

}

