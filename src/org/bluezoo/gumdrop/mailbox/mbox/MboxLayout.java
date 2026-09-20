/*
 * MboxLayout.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Knowledge of the on-disk layout of an mbox store, kept in one place so
 * that {@link MboxMailboxStore} deals only in mailbox names.
 *
 * <p>Each mailbox is a regular file whose name ends with the store's
 * extension. Hierarchy is expressed with directories: the mailbox
 * {@code work/2024} is the file {@code work/2024.mbox} (a mailbox
 * {@code work} may exist alongside as {@code work.mbox}).
 *
 * <p>Every method reports I/O failures as {@link IOException} rather than
 * treating an unreadable directory as an empty one, and none of them descend
 * through symbolic links to directories, so a scan cannot loop or leave the
 * user's directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MboxLayout {

    private MboxLayout() {
    }

    private static boolean isMailboxFile(Path path, String extension) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().endsWith(extension);
    }

    private static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Finds every mailbox file beneath a user directory. Directories whose
     * names start with {@code .} are not searched.
     *
     * @param userDirectory the user's directory
     * @param extension the mailbox file extension, including the dot
     * @return the mailbox files, in path order
     * @throws IOException if a directory cannot be read
     */
    static List<Path> findMailboxFiles(Path userDirectory, String extension)
            throws IOException {
        List<Path> found = new ArrayList<Path>();
        List<Path> pending = new ArrayList<Path>();
        pending.add(userDirectory);
        while (!pending.isEmpty()) {
            Path current = pending.remove(pending.size() - 1);
            DirectoryStream<Path> children = Files.newDirectoryStream(current);
            try {
                for (Path child : children) {
                    if (isMailboxFile(child, extension)) {
                        found.add(child);
                    } else if (isRealDirectory(child)
                            && !child.getFileName().toString().startsWith(".")) {
                        pending.add(child);
                    }
                }
            } finally {
                children.close();
            }
        }
        Collections.sort(found);
        return found;
    }

    /**
     * Returns true if a directory directly contains at least one mailbox
     * file.
     *
     * @param directory the directory to inspect
     * @param extension the mailbox file extension, including the dot
     * @throws IOException if the directory cannot be read
     */
    static boolean hasMailboxFiles(Path directory, String extension)
            throws IOException {
        DirectoryStream<Path> children = Files.newDirectoryStream(directory);
        try {
            for (Path child : children) {
                if (isMailboxFile(child, extension)) {
                    return true;
                }
            }
        } finally {
            children.close();
        }
        return false;
    }

    /**
     * Totals the mailbox files anywhere beneath a directory, including
     * under hidden directories, so that storage cannot be hidden from a
     * quota by placing it in one.
     *
     * @param directory the directory to measure
     * @param extension the mailbox file extension, including the dot
     * @return {total bytes, file count}
     * @throws IOException if a directory cannot be read
     */
    static long[] measure(Path directory, String extension) throws IOException {
        long totalSize = 0;
        long fileCount = 0;
        List<Path> pending = new ArrayList<Path>();
        pending.add(directory);
        while (!pending.isEmpty()) {
            Path current = pending.remove(pending.size() - 1);
            DirectoryStream<Path> children = Files.newDirectoryStream(current);
            try {
                for (Path child : children) {
                    if (isRealDirectory(child)) {
                        pending.add(child);
                    } else if (isMailboxFile(child, extension)) {
                        totalSize += Files.size(child);
                        fileCount++;
                    }
                }
            } finally {
                children.close();
            }
        }
        return new long[] { totalSize, fileCount };
    }
}
