/*
 * NoThreadSleepGuardTest.java
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

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.fail;

/**
 * Phase-5 guardrail: {@code test/junit} must not use {@code Thread.sleep}
 * or deadline polling to wait for async work. Async tests should block on
 * a cross-thread signal ({@link java.util.concurrent.CountDownLatch},
 * handler callback, {@link RecordingStubEndpoint}, etc.); {@code @Test(timeout=…)}
 * is only a hang guard.
 *
 * <p>A small allowlist covers tests that intentionally exercise real time
 * (rate limits, timers, cache expiry, filesystem mtimes). Add new entries
 * only when sleeping is the behaviour under test, not to paper over races.
 */
public class NoThreadSleepGuardTest {

    /**
     * Paths under {@code test/junit/src/} permitted to call
     * {@code Thread.sleep} or poll with {@code currentTimeMillis} deadlines.
     */
    private static final Set<String> THREAD_SLEEP_ALLOWLIST = new HashSet<String>(Arrays.asList(
            "org/bluezoo/gumdrop/ScheduledTimerTest.java",
            "org/bluezoo/gumdrop/ratelimit/RateLimiterTest.java",
            "org/bluezoo/gumdrop/ratelimit/AuthenticationRateLimiterTest.java",
            "org/bluezoo/gumdrop/http/HTTPDateCacheTest.java",
            "org/bluezoo/gumdrop/http/client/AltSvcCacheTest.java",
            "org/bluezoo/gumdrop/telemetry/SpanTest.java",
            "org/bluezoo/gumdrop/servlet/jsp/JSPDependencyTrackerTest.java",
            "org/bluezoo/gumdrop/servlet/session/SessionManagerTest.java",
            "org/bluezoo/gumdrop/mailbox/index/MailboxIndexerTest.java",
            "org/bluezoo/gumdrop/webdav/WebDAVPropfindDeadPropertiesParallelTest.java",
            "org/bluezoo/gumdrop/quic/QuicTestPeer.java"
    ));

    @Test
    public void junitSourcesMustNotUseThreadSleepOutsideAllowlist() throws Exception {
        Path junitSrc = locateJunitSourceRoot();
        final List<String> violations = new ArrayList<String>();
        Files.walkFileTree(junitSrc, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = junitSrc.relativize(file).toString().replace('\\', '/');
                if (relative.equals("org/bluezoo/gumdrop/testsupport/NoThreadSleepGuardTest.java")) {
                    return FileVisitResult.CONTINUE;
                }
                if (THREAD_SLEEP_ALLOWLIST.contains(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                scanFile(relative, new String(Files.readAllBytes(file), StandardCharsets.UTF_8), violations);
                return FileVisitResult.CONTINUE;
            }
        });
        if (!violations.isEmpty()) {
            fail("Thread.sleep / deadline polling found in test/junit (use latches instead; "
                    + "see CONTRIBUTING.md). Violations:\n  "
                    + join(violations, "\n  "));
        }
    }

    private static Path locateJunitSourceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve("test/junit/src");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("../test/junit/src").normalize();
        if (Files.isDirectory(parent)) {
            return parent;
        }
        throw new IllegalStateException("Could not locate test/junit/src from " + cwd);
    }

    private static void scanFile(String relativePath, String source, List<String> violations) {
        if (containsThreadSleepInCode(source)) {
            violations.add(relativePath + ": Thread.sleep");
        }
        if (containsDeadlinePollLoop(source)) {
            violations.add(relativePath + ": while (...currentTimeMillis...) { Thread.sleep }");
        }
    }

    private static boolean containsThreadSleepInCode(String source) {
        String withoutBlockComments = stripBlockComments(source);
        String[] lines = withoutBlockComments.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String code = codePortion(lines[i]);
            if (code.contains("Thread.sleep(")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDeadlinePollLoop(String source) {
        if (!source.contains("Thread.sleep(") || !source.contains("currentTimeMillis")) {
            return false;
        }
        String withoutBlockComments = stripBlockComments(source);
        int whileIdx = 0;
        while (true) {
            int w = withoutBlockComments.indexOf("while (", whileIdx);
            if (w < 0) {
                break;
            }
            int condEnd = withoutBlockComments.indexOf(')', w);
            if (condEnd < 0) {
                break;
            }
            String condition = withoutBlockComments.substring(w, condEnd);
            if (condition.contains("currentTimeMillis")) {
                int brace = withoutBlockComments.indexOf('{', condEnd);
                if (brace >= 0) {
                    int close = findMatchingBrace(withoutBlockComments, brace);
                    if (close > brace) {
                        String body = withoutBlockComments.substring(brace, close + 1);
                        if (body.contains("Thread.sleep(")) {
                            return true;
                        }
                    }
                }
            }
            whileIdx = condEnd + 1;
        }
        return false;
    }

    private static String stripBlockComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (i + 1 < source.length() && source.charAt(i) == '/' && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 2;
                continue;
            }
            out.append(source.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static String codePortion(String line) {
        int idx = line.indexOf("//");
        if (idx >= 0) {
            return line.substring(0, idx);
        }
        return line;
    }

    private static int findMatchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
