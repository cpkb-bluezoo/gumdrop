/*
 * ContributingStyleGuardSupport.java
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
 * Shared scanners for {@link ContributingStyleGuardTest}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ContributingStyleGuardSupport {

    static final String ALLOWLIST_RESOURCE = "contributing-style-allowlist.properties";

    private static final String[][] SOURCE_TREES = {
            {"main", "src/org/bluezoo/gumdrop"},
            {"junit", "test/junit/src"},
            {"integration", "test/integration/src"},
            {"examples", "examples"},
    };

    private static final Set<String> SKIP_RELATIVE = new HashSet<String>();
    static {
        SKIP_RELATIVE.add("org/bluezoo/gumdrop/testsupport/ContributingStyleGuardTest.java");
        SKIP_RELATIVE.add("org/bluezoo/gumdrop/testsupport/ContributingStyleGuardSupport.java");
    }

    enum Rule {
        LAMBDA("allowlist.lambda",
                Pattern.compile("\\)\\s*->|\\(\\s*\\)\\s*->|\\b[A-Za-z_][\\w]*\\s*->")),
        METHOD_REFERENCE("allowlist.methodref",
                Pattern.compile("\\b[A-Za-z_][\\w$]*::[A-Za-z_][\\w$]*\\b")),
        VIRTUAL_THREAD("allowlist.virtualthread",
                Pattern.compile("Thread\\.ofVirtual|startVirtualThread")),
        SCHEDULED("allowlist.scheduled",
                Pattern.compile("\\bScheduledFuture\\b|\\.scheduleAtFixedRate\\s*\\(")),
        FUNCTIONAL_INTERFACE("allowlist.functionalinterface",
                Pattern.compile("@FunctionalInterface\\b")),
        COMPLETABLE_FUTURE("allowlist.completablefuture",
                Pattern.compile("\\bCompletableFuture\\b")),
        FUTURE("allowlist.future",
                Pattern.compile(
                        "\\bimport java\\.util\\.concurrent\\.Future\\b|"
                                + "java\\.util\\.concurrent\\.Future\\s*<|"
                                + "(?<![A-Za-z])Future\\s*<"));

        final String allowlistProperty;
        final Pattern pattern;

        Rule(String allowlistProperty, Pattern pattern) {
            this.allowlistProperty = allowlistProperty;
            this.pattern = pattern;
        }
    }

    private ContributingStyleGuardSupport() {
    }

    static Set<String> loadAllowlist(Rule rule) throws IOException {
        InputStream in = ContributingStyleGuardSupport.class.getClassLoader()
                .getResourceAsStream(ALLOWLIST_RESOURCE);
        if (in == null) {
            throw new IOException("Missing test resource " + ALLOWLIST_RESOURCE);
        }
        Properties props = new Properties();
        try {
            props.load(in);
        } finally {
            in.close();
        }
        String raw = props.getProperty(rule.allowlistProperty, "").trim();
        if (raw.isEmpty()) {
            return Collections.emptySet();
        }
        raw = raw.replace('\r', ' ').replace('\n', ' ');
        Set<String> paths = new HashSet<String>();
        for (String part : raw.split(",")) {
            String entry = part.trim();
            while (entry.endsWith("\\")) {
                entry = entry.substring(0, entry.length() - 1).trim();
            }
            if (!entry.isEmpty()) {
                paths.add(entry);
            }
        }
        return paths;
    }

    static List<String> findViolations(Rule rule, Set<String> allowlist) throws IOException {
        List<String> violations = new ArrayList<String>();
        for (String[] tree : SOURCE_TREES) {
            String rootLabel = tree[0];
            Path root = locateTreeRoot(tree[1]);
            if (root == null) {
                continue;
            }
            scanTree(rootLabel, root, rule, allowlist, violations);
        }
        Collections.sort(violations);
        return violations;
    }

    static List<String> findStaleAllowlistEntries(Rule rule, Set<String> allowlist)
            throws IOException {
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
            if (!fileContainsViolation(file, rule)) {
                stale.add(entry);
            }
        }
        Collections.sort(stale);
        return stale;
    }

    private static void scanTree(String rootLabel, Path root, Rule rule,
            Set<String> allowlist, List<String> violations) throws IOException {
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
                scanFile(id, file, rule, violations);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void scanFile(String id, Path file, Rule rule, List<String> violations)
            throws IOException {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int lineNum = 0;
        for (String line : stripToCodeLines(source)) {
            lineNum++;
            if (rule.pattern.matcher(line).find()) {
                violations.add(id + ':' + lineNum + ": " + line.trim());
            }
        }
    }

    private static boolean fileContainsViolation(Path file, Rule rule) throws IOException {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        for (String line : stripToCodeLines(source)) {
            if (rule.pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static Iterable<String> stripToCodeLines(String source) {
        List<String> lines = new ArrayList<String>();
        String withoutBlocks = stripBlockComments(source);
        for (String line : withoutBlocks.split("\n", -1)) {
            lines.add(maskStringLiterals(line.split("//", 2)[0]));
        }
        return lines;
    }

    /** Masks double-quoted string contents so doc literals do not false-positive. */
    private static String maskStringLiterals(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (escape) {
                    out.append(' ');
                    escape = false;
                } else if (c == '\\') {
                    out.append(' ');
                    escape = true;
                } else if (c == '"') {
                    out.append('"');
                    inString = false;
                } else {
                    out.append(' ');
                }
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripBlockComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inBlock) {
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                inBlock = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
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
