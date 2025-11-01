/*
 * Content.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
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
