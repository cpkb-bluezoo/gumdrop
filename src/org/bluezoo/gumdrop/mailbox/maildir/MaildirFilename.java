/*
 * MaildirFilename.java
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

import org.bluezoo.gumdrop.mailbox.Flag;

import java.lang.management.ManagementFactory;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles Maildir filename parsing and generation.
 * 
 * <p>Maildir filenames encode message metadata including delivery time,
 * uniqueness identifiers, size, and flags. The format is:
 * <pre>
 * &lt;timestamp&gt;.&lt;unique&gt;,S=&lt;size&gt;:2,&lt;flags&gt;
 * </pre>
 * 
 * <p>Example: {@code 1733356800000.12345.1,S=4523:2,SF}
 * <ul>
 *   <li>1733356800000 - delivery timestamp in milliseconds</li>
 *   <li>12345.1 - process ID and counter for uniqueness</li>
 *   <li>S=4523 - message size in bytes</li>
 *   <li>:2, - info separator (always ":2," for standard Maildir)</li>
 *   <li>SF - flags (Seen, Flagged)</li>
 * </ul>
 * 
 * <p>Standard flags encoded as single uppercase letters:
 * <ul>
 *   <li>D - Draft (\Draft)</li>
 *   <li>F - Flagged (\Flagged)</li>
 *   <li>R - Replied (\Answered)</li>
 *   <li>S - Seen (\Seen)</li>
 *   <li>T - Trashed (\Deleted)</li>
 * </ul>
 * 
 * <p>Keywords (custom flags) are encoded as lowercase letters a-z,
 * with the mapping stored in a separate keywords file.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirFilename {

    /** Process ID for uniqueness */
    private static final String PID;
    
    /** Counter for uniqueness within the same millisecond */
    private static final AtomicLong COUNTER = new AtomicLong(0);

    static {
        // Get process ID - try ProcessHandle first (Java 9+), fall back to RuntimeMXBean
        String pid;
        try {
            // Java 9+ way - doesn't require network access
            pid = String.valueOf(ProcessHandle.current().pid());
        } catch (Exception | NoClassDefFoundError e) {
            // Fallback for older Java or restricted environments
            try {
                String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
                int atIndex = runtimeName.indexOf('@');
                if (atIndex > 0) {
                    pid = runtimeName.substring(0, atIndex);
                } else {
                    pid = runtimeName;
                }
            } catch (Exception e2) {
                // Last resort - use a random value
                pid = String.valueOf(System.nanoTime() % 100000);
            }
        }
        PID = pid;
    }

    private final long timestamp;
    private final String uniquePart;
    private final long size;
    private final Set<Flag> flags;
    private final Set<Integer> keywordIndices;

    /**
     * Parses a Maildir filename.
     *
     * @param filename the filename to parse
     */
    public MaildirFilename(String filename) {
        this.flags = EnumSet.noneOf(Flag.class);
        this.keywordIndices = new HashSet<>();

        // Parse: <timestamp>.<unique>,S=<size>:2,<flags>
        // First, find the info separator ":2,"
        int infoIndex = filename.indexOf(":2,");
        String basePart;
        String flagsPart;
        if (infoIndex >= 0) {
            basePart = filename.substring(0, infoIndex);
            flagsPart = filename.substring(infoIndex + 3);
        } else {
            basePart = filename;
            flagsPart = "";
        }

        // Parse size from base part
        int sizeIndex = basePart.indexOf(",S=");
        long parsedSize = -1;
        if (sizeIndex >= 0) {
            String sizeStr = basePart.substring(sizeIndex + 3);
            basePart = basePart.substring(0, sizeIndex);
            try {
                parsedSize = Long.parseLong(sizeStr);
            } catch (NumberFormatException e) {
                // Ignore invalid size
            }
        }
        this.size = parsedSize;

        // Parse timestamp and unique part
        int firstDot = basePart.indexOf('.');
        if (firstDot > 0) {
            try {
                this.timestamp = Long.parseLong(basePart.substring(0, firstDot));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid timestamp in filename: " + filename);
            }
            this.uniquePart = basePart.substring(firstDot + 1);
        } else {
            throw new IllegalArgumentException("Invalid Maildir filename format: " + filename);
        }

        // Parse flags
        parseFlags(flagsPart);
    }

    /**
     * Creates a new Maildir filename with the specified parameters.
     *
     * @param timestamp the delivery timestamp
     * @param uniquePart the unique identifier part
     * @param size the message size in bytes
     * @param flags the message flags
     * @param keywordIndices indices of keywords (for lowercase letters)
     */
    public MaildirFilename(long timestamp, String uniquePart, long size, 
            Set<Flag> flags, Set<Integer> keywordIndices) {
        this.timestamp = timestamp;
        this.uniquePart = uniquePart;
        this.size = size;
        this.flags = flags != null ? EnumSet.copyOf(flags) : EnumSet.noneOf(Flag.class);
        this.keywordIndices = keywordIndices != null ? new HashSet<>(keywordIndices) : new HashSet<>();
    }

    /**
     * Generates a new unique Maildir filename for a message being delivered.
     *
     * @param size the message size in bytes
     * @param flags the initial flags
     * @param keywordIndices the keyword indices
     * @return a new unique filename
     */
    public static MaildirFilename generate(long size, Set<Flag> flags, Set<Integer> keywordIndices) {
        long timestamp = System.currentTimeMillis();
        String uniquePart = PID + "." + COUNTER.incrementAndGet();
        return new MaildirFilename(timestamp, uniquePart, size, flags, keywordIndices);
    }

    /**
     * Parses flags from the flags portion of a Maildir filename.
     */
    private void parseFlags(String flagsPart) {
        for (int i = 0; i < flagsPart.length(); i++) {
            char c = flagsPart.charAt(i);
            switch (c) {
                case 'D':
                    flags.add(Flag.DRAFT);
                    break;
                case 'F':
                    flags.add(Flag.FLAGGED);
                    break;
                case 'R':
                    flags.add(Flag.ANSWERED);
                    break;
                case 'S':
                    flags.add(Flag.SEEN);
                    break;
                case 'T':
                    flags.add(Flag.DELETED);
                    break;
                default:
                    // Lowercase letters are keyword indices
                    if (c >= 'a' && c <= 'z') {
                        keywordIndices.add(c - 'a');
                    }
                    break;
            }
        }
    }

    /**
     * Returns the delivery timestamp.
     *
     * @return timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the unique part of the filename.
     *
     * @return the unique identifier
     */
    public String getUniquePart() {
        return uniquePart;
    }

    /**
     * Returns the message size.
     *
     * @return size in bytes, or -1 if not specified
     */
    public long getSize() {
        return size;
    }

    /**
     * Returns the message flags.
     *
     * @return set of flags
     */
    public Set<Flag> getFlags() {
        return EnumSet.copyOf(flags);
    }

    /**
     * Returns the keyword indices.
     *
     * @return set of keyword indices (0-25 mapping to a-z)
     */
    public Set<Integer> getKeywordIndices() {
        return new HashSet<>(keywordIndices);
    }

    /**
     * Returns a new filename with the specified flags.
     *
     * @param newFlags the new flags
     * @param newKeywordIndices the new keyword indices
     * @return a new MaildirFilename with updated flags
     */
    public MaildirFilename withFlags(Set<Flag> newFlags, Set<Integer> newKeywordIndices) {
        return new MaildirFilename(timestamp, uniquePart, size, newFlags, newKeywordIndices);
    }

    /**
     * Returns the base filename (without flags).
     * This is used for matching files across flag changes.
     *
     * @return the base filename
     */
    public String getBaseFilename() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp);
        sb.append('.');
        sb.append(uniquePart);
        if (size >= 0) {
            sb.append(",S=");
            sb.append(size);
        }
        return sb.toString();
    }

    /**
     * Returns the full filename including flags.
     *
     * @return the complete filename
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getBaseFilename());
        sb.append(":2,");
        
        // Flags must be in alphabetical order per Maildir spec
        if (flags.contains(Flag.DRAFT)) {
            sb.append('D');
        }
        if (flags.contains(Flag.FLAGGED)) {
            sb.append('F');
        }
        if (flags.contains(Flag.ANSWERED)) {
            sb.append('R');
        }
        if (flags.contains(Flag.SEEN)) {
            sb.append('S');
        }
        if (flags.contains(Flag.DELETED)) {
            sb.append('T');
        }
        
        // Keywords as lowercase letters (sorted)
        int[] sortedKeywords = new int[keywordIndices.size()];
        int idx = 0;
        for (Integer ki : keywordIndices) {
            sortedKeywords[idx++] = ki;
        }
        java.util.Arrays.sort(sortedKeywords);
        for (int ki : sortedKeywords) {
            if (ki >= 0 && ki < 26) {
                sb.append((char) ('a' + ki));
            }
        }
        
        return sb.toString();
    }

}

