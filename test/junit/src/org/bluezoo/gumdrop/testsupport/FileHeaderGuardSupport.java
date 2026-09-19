/*
 * FileHeaderGuardSupport.java
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

package org.bluezoo.gumdrop.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scans Java sources for the full LGPL file header required by CONTRIBUTING.md.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class FileHeaderGuardSupport {

    static final String ALLOWLIST_RESOURCE = "contributing-header-allowlist.properties";

    static final String REQUIRED_SNIPPET =
            "You should have received a copy of the GNU Lesser General Public License";

    private static final Pattern COPYRIGHT = Pattern.compile(
            "Copyright\\s*\\(C\\)\\s*\\d{4}");

    private static final String[][] SOURCE_TREES = {
            {"main", "src/org/bluezoo/gumdrop"},
            {"junit", "test/junit/src"},
            {"integration", "test/integration/src"},
    };

    private static final Set<String> SKIP_RELATIVE = new HashSet<String>();
    static {
        SKIP_RELATIVE.add("org/bluezoo/gumdrop/testsupport/FileHeaderGuardTest.java");
        SKIP_RELATIVE.add("org/bluezoo/gumdrop/testsupport/FileHeaderGuardSupport.java");
    }

    private FileHeaderGuardSupport() {
    }

    static Set<String> loadAllowlist() throws IOException {
        InputStream in = FileHeaderGuardSupport.class.getClassLoader()
                .getResourceAsStream(ALLOWLIST_RESOURCE);
        if (in == null) {
            return Collections.emptySet();
        }
        Properties props = new Properties();
        try {
            props.load(in);
        } finally {
            in.close();
        }
        String raw = props.getProperty("allowlist.paths", "").trim();
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        raw = raw.replace('\r', ' ').replace('\n', ' ');
        Set<String> paths = new HashSet<String>();
        for (String part : raw.split(",")) {
            String entry = part.trim();
            if (!entry.isEmpty()) {
                paths.add(entry);
            }
        }
        return paths;
    }

    static List<String> findViolations(Set<String> allowlist) throws IOException {
        List<String> violations = new ArrayList<String>();
        for (String[] tree : SOURCE_TREES) {
            String rootLabel = tree[0];
            Path root = locateTreeRoot(tree[1]);
            if (root == null) {
                continue;
            }
            scanTree(rootLabel, root, allowlist, violations);
        }
        Collections.sort(violations);
        return violations;
    }

    static List<String> findStaleAllowlistEntries(Set<String> allowlist) throws IOException {
        List<String> stale = new ArrayList<String>();
        for (String entry : allowlist) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                stale.add(entry + " (malformed allowlist entry)");
                continue;
            }
            String rootLabel = entry.substring(0, colon);
            String rel = entry.substring(colon + 1);
            Path root = locateTreeRootByLabel(rootLabel);
            if (root == null) {
                stale.add(entry + " (unknown root label)");
                continue;
            }
            Path file = root.resolve(rel);
            if (!Files.isRegularFile(file)) {
                stale.add(entry + " (file missing)");
                continue;
            }
            if (!fileViolates(file)) {
                stale.add(entry);
            }
        }
        Collections.sort(stale);
        return stale;
    }

    private static void scanTree(String rootLabel, Path root, Set<String> allowlist,
            List<String> violations) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (rootLabel.equals("junit") && SKIP_RELATIVE.contains(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                String id = rootLabel + ':' + relative;
                if (allowlist.contains(id)) {
                    return FileVisitResult.CONTINUE;
                }
                if (fileViolates(file)) {
                    violations.add(id);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean fileViolates(Path file) throws IOException {
        String head = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (head.length() > 8192) {
            head = head.substring(0, 8192);
        }
        if (!COPYRIGHT.matcher(head).find()) {
            return true;
        }
        return head.indexOf(REQUIRED_SNIPPET) < 0;
    }

    private static Path locateTreeRoot(String relativePath) {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve(relativePath);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve(relativePath).normalize();
        if (Files.isDirectory(parent)) {
            return parent;
        }
        return null;
    }

    private static Path locateTreeRootByLabel(String rootLabel) {
        for (String[] tree : SOURCE_TREES) {
            if (tree[0].equals(rootLabel)) {
                return locateTreeRoot(tree[1]);
            }
        }
        return null;
    }
}
