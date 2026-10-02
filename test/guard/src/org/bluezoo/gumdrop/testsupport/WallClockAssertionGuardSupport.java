/*
 * WallClockAssertionGuardSupport.java
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds unit tests whose outcome depends on how long something took: an
 * assertion over a duration computed from {@code System.nanoTime()} or
 * {@code System.currentTimeMillis()}. Such a test passes or fails according
 * to machine load, so it belongs in {@code test/integration/src} as a
 * {@code *PerformanceTest} (see CONTRIBUTING.md).
 *
 * <p>Timestamps used as data ({@code long before = System.currentTimeMillis();}
 * then {@code assertTrue(span.getStart() >= before)}) are not durations and
 * are not flagged: a variable counts as a duration only when it is assigned
 * from a subtraction involving a clock read or another clock-derived value.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see CONTRIBUTING.md
 */
final class WallClockAssertionGuardSupport {

    private static final String[] CLOCK_CALLS = {"System.nanoTime()", "System.currentTimeMillis()"};

    private WallClockAssertionGuardSupport() {
    }

    /** Scans every unit test source file; returns "file: method" entries. */
    static List<String> findViolations() throws IOException {
        Path root = locateRoot();
        List<String> violations = new ArrayList<String>();
        if (root == null) {
            return violations;
        }
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Iterator<Path> it = walk.iterator(); it.hasNext();) {
                Path p = it.next();
                if (p.toString().endsWith(".java")) {
                    files.add(p);
                }
            }
        }
        Collections.sort(files);
        for (Path file : files) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String rel = root.relativize(file).toString();
            if (file.getFileName().toString().startsWith("WallClockAssertionGuard")) {
                continue; // its snippets deliberately contain the patterns it detects
            }
            for (String method : findViolatingMethods(source)) {
                violations.add(rel + ": " + method);
            }
        }
        return violations;
    }

    /** Returns the names of {@code @Test} methods in {@code source} that assert on a duration. */
    static List<String> findViolatingMethods(String source) {
        List<String> result = new ArrayList<String>();
        int from = 0;
        while (true) {
            int test = source.indexOf("@Test", from);
            if (test < 0) {
                break;
            }
            int sig = source.indexOf("void ", test);
            if (sig < 0) {
                break;
            }
            int nameStart = sig + "void ".length();
            int paren = source.indexOf('(', nameStart);
            int open = source.indexOf('{', paren);
            if (paren < 0 || open < 0) {
                break;
            }
            int close = matchingBrace(source, open);
            String name = source.substring(nameStart, paren).trim();
            if (assertsOnDuration(source.substring(open + 1, close))) {
                result.add(name);
            }
            from = close;
        }
        return result;
    }

    private static boolean assertsOnDuration(String body) {
        List<String> statements = splitStatements(body);
        Set<String> clockVars = new HashSet<String>();
        Set<String> durationVars = new HashSet<String>();
        for (int i = 0; i < statements.size(); i++) {
            String st = statements.get(i);
            String var = assignedVariable(st);
            if (var != null) {
                String expr = st.substring(assignmentIndex(st) + 1);
                int refs = clockReferences(expr, clockVars);
                boolean derivedFromDuration = mentionsAny(expr, durationVars);
                if (derivedFromDuration || (refs >= 2 && expr.indexOf('-') >= 0)) {
                    durationVars.add(var);
                } else if (refs >= 1) {
                    clockVars.add(var);
                }
            }
            if (isAssertion(st) && (mentionsAny(st, durationVars)
                    || (clockReferences(st, clockVars) >= 2 && st.indexOf('-') >= 0))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAssertion(String statement) {
        return statement.indexOf("assert") >= 0 && statement.indexOf('(') >= 0;
    }

    /** Counts clock reads in {@code expr}: direct clock calls plus mentions of clock-derived variables. */
    private static int clockReferences(String expr, Set<String> clockVars) {
        int count = 0;
        for (int i = 0; i < CLOCK_CALLS.length; i++) {
            int at = 0;
            while (true) {
                at = expr.indexOf(CLOCK_CALLS[i], at);
                if (at < 0) {
                    break;
                }
                count++;
                at += CLOCK_CALLS[i].length();
            }
        }
        for (String v : clockVars) {
            if (mentionsAny(expr, Collections.singleton(v))) {
                count++;
            }
        }
        return count;
    }

    private static boolean mentionsAny(String text, Set<String> names) {
        for (String n : names) {
            int at = 0;
            while (true) {
                at = text.indexOf(n, at);
                if (at < 0) {
                    break;
                }
                boolean startOk = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
                int end = at + n.length();
                boolean endOk = end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
                if (startOk && endOk) {
                    return true;
                }
                at = end;
            }
        }
        return false;
    }

    /** Index of the assignment '=' in a statement, or -1 (ignores ==, <=, >=, !=). */
    private static int assignmentIndex(String st) {
        int depth = 0;
        for (int i = 0; i < st.length(); i++) {
            char c = st.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '=' && depth == 0) {
                char prev = i > 0 ? st.charAt(i - 1) : ' ';
                char next = i + 1 < st.length() ? st.charAt(i + 1) : ' ';
                if (next != '=' && prev != '=' && prev != '<' && prev != '>' && prev != '!') {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The variable a statement assigns (declaration or plain assignment), or null. */
    private static String assignedVariable(String st) {
        int eq = assignmentIndex(st);
        if (eq < 0) {
            return null;
        }
        String lhs = st.substring(0, eq).trim();
        int end = lhs.length();
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(lhs.charAt(start - 1))) {
            start--;
        }
        return start < end ? lhs.substring(start, end) : null;
    }

    private static List<String> splitStatements(String body) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        int i = 0;
        int n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && body.charAt(j) != '"') {
                    j += body.charAt(j) == '\\' ? 2 : 1;
                }
                cur.append("\"\"");
                i = j + 1;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n && body.charAt(j) != '\'') {
                    j += body.charAt(j) == '\\' ? 2 : 1;
                }
                cur.append(body, i, Math.min(n, j + 1));
                i = j + 1;
                continue;
            }
            if (body.startsWith("//", i)) {
                int nl = body.indexOf('\n', i);
                i = nl < 0 ? n : nl;
                continue;
            }
            if (body.startsWith("/*", i)) {
                int endc = body.indexOf("*/", i + 2);
                i = endc < 0 ? n : endc + 2;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if ((c == ';' || c == '{' || c == '}') && depth <= 0) {
                if (cur.toString().trim().length() > 0) {
                    out.add(cur.toString().trim());
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
            i++;
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        int n = text.length();
        int j = open;
        while (j < n) {
            char c = text.charAt(j);
            if (c == '"') {
                j++;
                while (j < n && text.charAt(j) != '"') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
            } else if (c == '\'') {
                j++;
                while (j < n && text.charAt(j) != '\'') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
            } else if (text.startsWith("//", j)) {
                int nl = text.indexOf('\n', j);
                j = nl < 0 ? n : nl;
            } else if (text.startsWith("/*", j)) {
                int endc = text.indexOf("*/", j + 2);
                j = endc < 0 ? n : endc + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
            j++;
        }
        return n - 1;
    }

    private static Path locateRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve("test/junit/src");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("..").resolve("test/junit/src").normalize();
        return Files.isDirectory(parent) ? parent : null;
    }
}
