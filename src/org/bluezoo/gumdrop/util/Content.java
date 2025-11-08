/*
 * Content.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.util;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Interface by which byte content of arbitrary length can be retrieved or
 * stored.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface Content {

    /**
     * Returns an input stream from which the bytes of this content can be
     * read.
     */
    InputStream getInputStream() throws IOException;

    /**
     * Returns an output stream to which the bytes of this content can be
     * written.
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * Returns the content length in bytes.
     */
    long length();

    /**
     * Create a new content of this type.
     * @param fileName the name of the file to associate this content with
     */
    Content create(String fileName) throws IOException;

}
