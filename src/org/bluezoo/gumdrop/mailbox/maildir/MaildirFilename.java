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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
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
 *   <li>:2, - info separator (see {@link #INFO_SEPARATOR} for the one
 *       exception this implementation makes to the standard)</li>
 *   <li>SF - flags (Seen, Flagged)</li>
 * </ul>
 *
 * <p><b>Windows (issue #287):</b> a literal {@code ':'} is illegal in an
 * NTFS filename (reserved for Alternate Data Streams), so on Windows
 * this class generates {@code ,2,} in place of {@code :2,} -- outside
 * the Maildir spec, but {@link #MaildirFilename(String) parsing} accepts
 * either form, on every platform, regardless of which one generated it.
 * This keeps Unix generation spec-compliant and interoperable with other
 * Maildir tools (Dovecot, Courier, etc.), while still letting a mailbox
 * -- or a fixture checked into source control specifically so a {@code
 * git clone} on Windows doesn't fail outright -- move between platforms
 * without becoming unreadable.
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
        PID = String.valueOf(ProcessHandle.current().pid());
    }

    /**
     * Whether this JVM is running on Windows, where {@code ':'} can't
     * appear in a filename at all (issue #287).
     */
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    /**
     * The Maildir info-section separator this JVM generates new filenames
     * with: {@code ":2,"} everywhere except Windows, where {@code ",2,"}
     * is used instead. See the class documentation for why this is safe
     * -- {@link #MaildirFilename(String)} accepts both forms when
     * reading, on every platform, independent of which one is configured
     * for generation here.
     */
    static final String INFO_SEPARATOR = WINDOWS ? ",2," : ":2,";

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

        // Parse: <timestamp>.<unique>,S=<size><SEP><flags>
        // <SEP> is ":2," (standard) or ",2," (issue #287, Windows-safe) --
        // see findInfoSeparatorIndex.
        int infoIndex = findInfoSeparatorIndex(filename);
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
     * Finds the Maildir info-section separator in {@code filename}:
     * whichever of {@code ":2,"} (standard) or {@code ",2,"} (issue
     * #287's Windows-safe alternative) appears <em>last</em>. Checked
     * regardless of which one this platform generates, so a filename
     * produced elsewhere is always readable here.
     *
     * <p>The last occurrence, not the first, because any experimental
     * {@code key=value} fields (e.g. {@code ,S=<size>}) always precede
     * the real separator and, in the comma form specifically, share its
     * {@code ','} character -- the actual separator is only guaranteed
     * to be the rightmost match.
     *
     * @param filename the filename to search
     * @return the index of the 3-character separator, or -1 if neither form is present
     */
    private static int findInfoSeparatorIndex(String filename) {
        int colon = filename.lastIndexOf(":2,");
        int comma = filename.lastIndexOf(",2,");
        return Math.max(colon, comma);
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
        sb.append(INFO_SEPARATOR);
        
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

