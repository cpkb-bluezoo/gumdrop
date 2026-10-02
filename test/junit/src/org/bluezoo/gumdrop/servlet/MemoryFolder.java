/*
 * MemoryFolder.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

/**
 * A scratch folder on a private in-memory file system, used by the servlet
 * unit tests in place of a real temporary folder so that web application
 * roots, WAR files and JSP sources never touch the disk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MemoryFolder {

    private final Path root;

    /**
     * Creates a new, empty folder on a fresh in-memory file system.
     */
    public MemoryFolder() {
        MemoryFileSystem fs = MemoryFileSystem.create();
        root = fs.getPath("/tmp");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the folder itself.
     */
    public Path getRoot() {
        return root;
    }

    /**
     * Creates a sub-directory (and any missing parents) of the folder.
     */
    public Path newFolder(String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Creates an empty file directly inside the folder.
     */
    public Path newFile(String name) throws IOException {
        Path file = root.resolve(name);
        Files.createFile(file);
        return file;
    }

    /**
     * Writes a file, creating any missing parent directories.
     */
    public static Path write(Path file, byte[] data) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, data);
        return file;
    }

    /**
     * Writes a UTF-8 text file below {@code dir}, creating any missing parent
     * directories.
     */
    public static Path write(Path dir, String relative, String text) throws IOException {
        Path file = dir.resolve(relative);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return write(file, data);
    }

    /**
     * Writes a binary file below {@code dir}, creating any missing parent
     * directories.
     */
    public static Path write(Path dir, String relative, byte[] data) throws IOException {
        Path file = dir.resolve(relative);
        return write(file, data);
    }

    /**
     * Creates a context rooted at a path on an in-memory file system. The
     * path-taking constructor of {@link Context} is package-private, so tests
     * outside this package go through here.
     */
    public static Context context(Container container, String contextPath, Path root) {
        return new Context(container, contextPath, root);
    }
}
