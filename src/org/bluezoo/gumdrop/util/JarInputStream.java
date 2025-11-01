/*
 * JarInputStream.java
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Wrapper for an input stream returned from a JarFile.getInputStream.
 * We cannot close the JarFile until the stream has been read, so we'll
 * close it in this class.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JarInputStream extends FilterInputStream {

    private JarFile jarFile;

    public JarInputStream(JarFile jarFile, JarEntry jarEntry) throws IOException {
        super(jarFile.getInputStream(jarEntry));
        this.jarFile = jarFile;
    }

    public void close() throws IOException {
        jarFile.close();
        super.close();
    }

}
