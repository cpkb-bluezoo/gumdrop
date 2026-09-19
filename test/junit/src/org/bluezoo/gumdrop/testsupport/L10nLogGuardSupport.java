/*
 * L10nLogGuardSupport.java
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects hardcoded string literals in operator-facing {@code Logger} calls.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class L10nLogGuardSupport {

    private static final Pattern LOGGER_LITERAL = Pattern.compile(
            "\\b(?:LOGGER|logger)\\.(?:log\\s*\\(\\s*Level\\.(?:INFO|WARNING|SEVERE|FINE|FINER|FINEST)\\s*,\\s*|"
                    + "(?:info|warning|severe|fine|finer|finest)\\s*\\(\\s*)\"");

    private static final Pattern LOGGER_CALL_START = Pattern.compile(
            "\\b(?:LOGGER|logger)\\.(?:log\\s*\\(|(?:info|warning|severe|fine|finer|finest)\\s*\\()");

    private static final Pattern L10N_IN_TEXT = Pattern.compile(
            "L10N\\.getString|MessageFormat\\.format\\s*\\([^)]*L10N|Gumdrop\\.L10N\\.getString");

    private static final String MAIN_ROOT = "src/org/bluezoo/gumdrop";

    private L10nLogGuardSupport() {
    }

    static List<String> findViolations() throws IOException {
        Path root = locateMainRoot();
        if (root == null) {
            return Collections.singletonList("(main source tree not found)");
        }
        List<String> violations = new ArrayList<String>();
        scanPackage(root, root, violations);
        Collections.sort(violations);
        return violations;
    }

    private static void scanPackage(Path mainRoot, Path pkgRoot, List<String> violations)
            throws IOException {
        Files.walkFileTree(pkgRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = mainRoot.relativize(file).toString().replace('\\', '/');
                scanFile(rel, Files.readAllLines(file, StandardCharsets.UTF_8), violations);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void scanFile(String rel, List<String> lines, List<String> violations) {
        Set<Integer> reported = new HashSet<Integer>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isCommentLine(line)) {
                continue;
            }
            if (lineLooksLikeViolation(line)) {
                int lineNum = i + 1;
                if (reported.add(lineNum)) {
                    violations.add("main:" + rel + ":" + lineNum + ": " + line.trim());
                }
                continue;
            }
            if (!LOGGER_CALL_START.matcher(line).find()) {
                continue;
            }
            if (line.indexOf('"') >= 0) {
                continue;
            }
            String stmt = extractLoggerStatement(lines, i);
            if (stmt == null || !stmt.contains("\"")) {
                continue;
            }
            if (L10N_IN_TEXT.matcher(stmt).find()) {
                continue;
            }
            int lineNum = i + 1;
            if (reported.add(lineNum)) {
                violations.add("main:" + rel + ":" + lineNum + ": " + line.trim());
            }
        }
    }

    private static String extractLoggerStatement(List<String> lines, int start) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            sb.append(line);
            if (i + 1 < lines.size()) {
                sb.append('\n');
            }
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '(') {
                    depth++;
                    started = true;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (started && line.indexOf(';') >= 0 && depth <= 0) {
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static boolean isCommentLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*");
    }

    private static boolean lineLooksLikeViolation(String line) {
        if (isCommentLine(line)) {
            return false;
        }
        return LOGGER_LITERAL.matcher(line).find()
                && !L10N_IN_TEXT.matcher(line).find();
    }

    private static Path locateMainRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve(MAIN_ROOT);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve(MAIN_ROOT).normalize();
        if (Files.isDirectory(parent)) {
            return parent;
        }
        return null;
    }
}
