/*
 * NntpAttachment.java
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

package org.bluezoo.gumdrop.nntp.client;

/**
 * A file to post with an article, as a uuencoded block in the body: the
 * way files travel on Usenet.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see NntpSession#postWithAttachments(byte[], java.util.List, NntpCallback)
 */
public final class NntpAttachment {

    private final String name;
    private final int mode;
    private final byte[] data;

    /**
     * Creates an attachment.
     *
     * @param name the file name; not empty, and without control characters
     * @param mode the Unix permission bits for the begin line, 0 to 0777
     * (0644 is the usual)
     * @param data the file's content (not copied)
     * @throws IllegalArgumentException if the name or the mode is not valid
     */
    public NntpAttachment(String name, int mode, byte[] data) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("an attachment needs a file name");
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < ' ' || name.charAt(i) == 0x7f) {
                throw new IllegalArgumentException("control character in the file name");
            }
        }
        if (mode < 0 || mode > 0777) {
            throw new IllegalArgumentException("mode out of range: " + Integer.toOctalString(mode));
        }
        if (data == null) {
            throw new IllegalArgumentException("an attachment needs content");
        }
        this.name = name;
        this.mode = mode;
        this.data = data;
    }

    /**
     * Returns the file name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the permission bits.
     *
     * @return the mode
     */
    public int getMode() {
        return mode;
    }

    /**
     * Returns the content.
     *
     * @return the bytes, not a copy
     */
    public byte[] getData() {
        return data;
    }

}
