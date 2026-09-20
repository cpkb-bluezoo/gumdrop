/*
 * MaildirLayout.java
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

package org.bluezoo.gumdrop.mailbox.maildir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Knowledge of the on-disk Maildir++ directory layout, kept in one place so
 * that {@link MaildirMailboxStore} deals only in mailbox names.
 *
 * <p>A Maildir is a directory with {@code cur}, {@code new} and {@code tmp}
 * subdirectories. The user's directory is itself the INBOX, and every other
 * mailbox is a sibling directory whose name starts with {@code .}
 * (for example {@code .Sent} or {@code .work.2024}).
 *
 * <p>Every method reports I/O failures as {@link IOException} rather than
 * treating an unreadable directory as an empty one, and none of them follow
 * symbolic links when walking or deleting.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MaildirLayout {

    private static final String CUR = "cur";
    private static final String NEW = "new";
    private static final String TMP = "tmp";

    private MaildirLayout() {
    }

    /**
     * Returns true if {@code dir} is a directory containing {@code cur},
     * {@code new} and {@code tmp} directories.
     */
    static boolean isMaildir(Path dir) {
        return Files.isDirectory(dir)
                && Files.isDirectory(dir.resolve(CUR))
                && Files.isDirectory(dir.resolve(NEW))
                && Files.isDirectory(dir.resolve(TMP));
    }

    /**
     * Lists the Maildir++ subfolders of a user directory: the child
     * directories whose names start with {@code .} and that are themselves
     * valid Maildirs, in name order.
     *
     * @param userDirectory the user's Maildir (the INBOX)
     * @throws IOException if the directory cannot be read
     */
    static List<Path> listSubfolders(Path userDirectory) throws IOException {
        List<Path> folders = new ArrayList<Path>();
        DirectoryStream<Path> children = Files.newDirectoryStream(userDirectory);
        try {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") && isMaildir(child)) {
                    folders.add(child);
                }
            }
        } finally {
            children.close();
        }
        Collections.sort(folders);
        return folders;
    }

    /**
     * Returns true if the Maildir holds any message, whether read
     * ({@code cur}) or not yet seen ({@code new}). {@code tmp} is ignored: it
     * only ever holds deliveries that have not been completed.
     *
     * @param maildir the Maildir to inspect
     * @throws IOException if a directory cannot be read
     */
    static boolean hasMessages(Path maildir) throws IOException {
        return !isEmpty(maildir.resolve(CUR)) || !isEmpty(maildir.resolve(NEW));
    }

    private static boolean isEmpty(Path dir) throws IOException {
        DirectoryStream<Path> entries = Files.newDirectoryStream(dir);
        try {
            return !entries.iterator().hasNext();
        } finally {
            entries.close();
        }
    }

    /**
     * Deletes a directory and everything beneath it. Symbolic links are
     * removed as links; their targets are never entered or deleted.
     *
     * @param dir the directory to delete
     * @throws IOException if anything cannot be deleted
     */
    static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
