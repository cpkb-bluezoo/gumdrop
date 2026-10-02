/*
 * MemoryTemp.java
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

package org.bluezoo.gumdrop.testsupport.memfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deterministic stand-ins for the temporary-file factories, backed by a
 * private {@link MemoryFileSystem} so unit tests never touch the real file
 * system.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MemoryTemp {

    private MemoryTemp() {
    }

    /**
     * Returns the path of a new, empty file on a fresh in-memory file system.
     *
     * @param prefix file name prefix
     * @param suffix file name suffix
     * @return the created file
     * @throws IOException if the file cannot be created
     */
    public static Path createTempFile(String prefix, String suffix) throws IOException {
        Path dir = createTempDirectory(prefix);
        Path file = dir.resolve(prefix + suffix);
        Files.createFile(file);
        return file;
    }

    /**
     * Returns a new, empty directory on a fresh in-memory file system.
     *
     * @param prefix a name hint
     * @return the created directory
     * @throws IOException if the directory cannot be created
     */
    public static Path createTempDirectory(String prefix) throws IOException {
        MemoryFileSystem fs = MemoryFileSystem.create();
        Path dir = fs.getPath("/tmp", prefix);
        Files.createDirectories(dir);
        return dir;
    }
}
