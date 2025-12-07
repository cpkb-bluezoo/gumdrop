/*
 * JarInputStream.java
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
