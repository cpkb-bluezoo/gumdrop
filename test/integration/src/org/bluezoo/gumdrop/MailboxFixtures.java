/*
 * MailboxFixtures.java
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

package org.bluezoo.gumdrop;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Helpers for working with the mailbox fixtures under
 * {@code test/integration/mailbox} without mutating the source tree.
 *
 * <p>The fixtures are checked into source control and MUST be treated as
 * immutable. Even nominally read-only operations on Maildir/mbox mailboxes can
 * create sidecar files (for example a {@code .gidx} search index), so any test
 * that opens a fixture mailbox must operate on a private, temporary copy and
 * discard it afterwards. Tests therefore {@link #copy(String) copy} the fixture
 * they need into a throwaway directory, point the server/store at the copy, and
 * {@link #delete(Path) delete} it during teardown.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailboxFixtures {

    /** Root of the checked-in, read-only mailbox fixtures. */
    public static final Path FIXTURE_ROOT =
            Paths.get("test/integration/mailbox").toAbsolutePath();

    private MailboxFixtures() {
    }

    /**
     * Copies a fixture subtree (relative to {@link #FIXTURE_ROOT}) into a fresh
     * temporary directory and returns the copied root. The caller owns the
     * returned directory and must pass it to {@link #delete(Path)} when done.
     *
     * @param relativeFixture the fixture subtree, e.g. {@code "mbox"} or
     *        {@code "maildir"}
     * @return the root of the temporary copy
     * @throws IOException if the fixture is missing or the copy fails
     */
    public static Path copy(String relativeFixture) throws IOException {
        Path source = FIXTURE_ROOT.resolve(relativeFixture);
        if (!Files.isDirectory(source)) {
            throw new IOException("Mailbox fixture not found: " + source);
        }
        final Path dest = Files.createTempDirectory("gumdrop-mailbox-");
        final Path root = source;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(root.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(root.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        return dest;
    }

    /**
     * Creates an empty temporary mailbox root, for stores that are populated at
     * runtime rather than seeded from a fixture (such as local delivery). The
     * caller must pass the result to {@link #delete(Path)} when done.
     *
     * @return the root of a fresh, empty temporary directory
     * @throws IOException if the directory cannot be created
     */
    public static Path newEmptyRoot() throws IOException {
        return Files.createTempDirectory("gumdrop-mailbox-");
    }

    /**
     * Recursively deletes a temporary directory tree previously returned by
     * {@link #copy(String)} or {@link #newEmptyRoot()}. This never throws:
     * failure to remove a throwaway tree should not fail a test.
     *
     * @param root the temporary root to remove (may be {@code null})
     */
    public static void delete(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            // best-effort cleanup
        }
    }
}
