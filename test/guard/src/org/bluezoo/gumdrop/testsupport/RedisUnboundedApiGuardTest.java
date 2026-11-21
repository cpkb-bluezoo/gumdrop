/*
 * RedisUnboundedApiGuardTest.java
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Redis client offers no call that returns, or passes to a handler, a
 * value whose size depends on the server's data. A payload (a value, a list
 * element, a published message) is delivered only as chunks; an identifier,
 * a scalar or an error line is delivered whole, but is bounded by the
 * connection's maximum field length. This reads the public signatures of
 * {@code org.bluezoo.gumdrop.redis.client} and {@code .codec} from source and
 * fails on a method that returns a byte array, a collection or a decoded
 * value, or that hands a handler one, or a string that is not on the
 * allow-list below. Each entry on the list has its reason: a string a handler
 * is given must be one the library bounds.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisUnboundedApiGuardTest {

    private static final String[] PACKAGES = {
        "org/bluezoo/gumdrop/redis/client", "org/bluezoo/gumdrop/redis/codec"
    };

    /**
     * Callback methods that are given a string, each with why it is bounded.
     * Keyed by {@code Type.method}; every overload is covered.
     */
    private static final Map<String, String> BOUNDED_STRINGS = new LinkedHashMap<String, String>();

    /** Public methods that return a buffer, with why it is not the server's data. */
    private static final Map<String, String> BUFFER_RETURNS = new LinkedHashMap<String, String>();

    static {
        String error = "an error line or message, bounded by the maximum field length";
        String scalar = "a scalar, bounded by the maximum field length";
        String identifier = "an identifier (key, field, channel, pattern, cursor), "
                + "bounded by the maximum field length";
        BOUNDED_STRINGS.put("StringResultHandler.handleResult", scalar);
        BOUNDED_STRINGS.put("StringResultHandler.handleError", error);
        BOUNDED_STRINGS.put("IntegerResultHandler.handleError", error);
        BOUNDED_STRINGS.put("BooleanResultHandler.handleError", error);
        BOUNDED_STRINGS.put("BulkResultHandler.handleComplete", error);
        BOUNDED_STRINGS.put("ArrayResultHandler.handleComplete", error);
        BOUNDED_STRINGS.put("KeyArrayResultHandler.handleComplete", error);
        BOUNDED_STRINGS.put("MapResultHandler.handleComplete", error);
        BOUNDED_STRINGS.put("ScanResultHandler.handleComplete", error);
        BOUNDED_STRINGS.put("ReplyEventHandler.handleComplete", error);
        BOUNDED_STRINGS.put("KeyArrayResultHandler.handleKey", identifier);
        BOUNDED_STRINGS.put("ScanResultHandler.handleCursor", identifier);
        BOUNDED_STRINGS.put("ScanResultHandler.handleKey", identifier);
        BOUNDED_STRINGS.put("MapResultHandler.handleField", identifier);
        BOUNDED_STRINGS.put("InvalidationHandler.invalidate", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handleSubscribed", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handlePatternSubscribed", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handleUnsubscribed", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handlePatternUnsubscribed", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handleShardSubscribed", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handleShardUnsubscribed", identifier);
        BOUNDED_STRINGS.put("MessageHandler.handleError", error);
        BOUNDED_STRINGS.put("RespEventHandler.bigNumber", "a number, bounded by the maximum line length");
        BOUNDED_STRINGS.put("RespEventHandler.simpleString", scalar);
        BOUNDED_STRINGS.put("RespEventHandler.error", error);
        BOUNDED_STRINGS.put("RespEventHandler.beginString",
                "the three-letter format of a verbatim string, or null");
        BOUNDED_STRINGS.put("InvalidationHandler.beginString",
                "the three-letter format of a verbatim string, or null");
        BOUNDED_STRINGS.put("InvalidationHandler.bigNumber", "a number, bounded by the maximum line length");
        BOUNDED_STRINGS.put("InvalidationHandler.simpleString", scalar);
        BOUNDED_STRINGS.put("InvalidationHandler.error", error);
        BOUNDED_STRINGS.put("ReplyEventAdapter.handleComplete", error);
        BOUNDED_STRINGS.put("ReplyEventAdapter.bigNumber", "a number, bounded by the maximum line length");
        BOUNDED_STRINGS.put("ReplyEventAdapter.simpleString", scalar);
        BOUNDED_STRINGS.put("ReplyEventAdapter.error", error);
        BOUNDED_STRINGS.put("ReplyEventAdapter.beginString",
                "the three-letter format of a verbatim string, or null");

        BUFFER_RETURNS.put("RespEncoder", "encodes the request, which the caller supplied");
    }

    private static final Pattern TYPE = Pattern.compile(
            "(?m)^\\s*public\\s+((?:(?:abstract|final|static)\\s+)*)(class|interface|enum)\\s+(\\w+)");

    private static final Pattern METHOD = Pattern.compile(
            "^\\s*((?:(?:public|protected|private|static|final|abstract|default|synchronized|native)\\s+)*)"
            + "(?:<[^>]+>\\s+)?([\\w.<>\\[\\],? ]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[\\w.,\\s]+)?$");

    /** One public or protected method of a public type. */
    private static final class Method {
        final String type;
        final boolean callbackType;
        final String name;
        final String returns;
        final List<String[]> params = new ArrayList<String[]>();

        Method(String type, boolean callbackType, String name, String returns) {
            this.type = type;
            this.callbackType = callbackType;
            this.name = name;
            this.returns = returns;
        }

        String key() {
            return type + "." + name;
        }
    }

    @Test
    public void noPublicMethodReturnsOrHandsAHandlerAValueSizedByTheServer() throws Exception {
        List<Method> methods = readMethods();
        assertTrue("found the public API: " + methods.size(), methods.size() > 100);
        List<String> violations = new ArrayList<String>();
        Set<String> used = new HashSet<String>();
        for (Method m : methods) {
            checkReturn(m, violations, used);
            if (m.callbackType) {
                checkParameters(m, violations, used);
            }
        }
        if (!violations.isEmpty()) {
            fail("The Redis client must not return, or pass to a handler, a value whose size "
                    + "depends on the server's data (see the class comment):\n  "
                    + join(violations));
        }
        List<String> stale = new ArrayList<String>();
        for (String key : BOUNDED_STRINGS.keySet()) {
            if (!used.contains(key)) {
                stale.add(key);
            }
        }
        for (String key : BUFFER_RETURNS.keySet()) {
            if (!used.contains(key)) {
                stale.add(key);
            }
        }
        if (!stale.isEmpty()) {
            fail("allow-list entries no longer needed, remove them:\n  " + join(stale));
        }
    }

    private static void checkReturn(Method m, List<String> violations, Set<String> used) {
        String r = m.returns;
        if (r.equals("void") || r.equals("boolean") || r.equals("int") || r.equals("long")
                || r.equals("double") || r.equals("ReplyFlow")) {
            return;
        }
        if (r.equals("ByteBuffer") && BUFFER_RETURNS.containsKey(m.type)) {
            used.add(m.type);
            return;
        }
        if (isUnbounded(r) || r.equals("ByteBuffer") || r.equals("String") && m.callbackType) {
            violations.add(m.key() + " returns " + r);
        }
    }

    private static void checkParameters(Method m, List<String> violations, Set<String> used) {
        for (String[] p : m.params) {
            String type = p[0];
            if (isUnbounded(type)) {
                violations.add(m.key() + " is given " + type + " " + p[1]);
            } else if (type.equals("ByteBuffer")) {
                if (!p[1].equals("chunk")) {
                    violations.add(m.key() + " is given a buffer that is not a chunk: " + p[1]);
                }
            } else if (type.equals("String")) {
                if (BOUNDED_STRINGS.containsKey(m.key())) {
                    used.add(m.key());
                } else {
                    violations.add(m.key() + " is given a string (" + p[1]
                            + "): bound it, and add it to the allow-list with the reason");
                }
            }
        }
    }

    private static boolean isUnbounded(String type) {
        String t = type.trim().replaceFirst("^java\\.(util|io)\\.", "");
        return t.equals("byte[]") || t.equals("Object") || t.equals("RespValue")
                || t.matches("(List|Map|Set|Collection|Iterable|Iterator)(<.*>)?")
                || t.equals("InputStream") || t.equals("char[]");
    }

    // ── reading the source ──

    private static List<Method> readMethods() throws Exception {
        final List<Method> out = new ArrayList<Method>();
        for (int i = 0; i < PACKAGES.length; i++) {
            Path dir = locate("src/" + PACKAGES[i]);
            final List<Path> files = new ArrayList<Path>();
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java") && !name.equals("package-info.java")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path file : files) {
                readFile(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), out);
            }
        }
        return out;
    }

    private static void readFile(String raw, List<Method> out) {
        String source = blank(raw);
        Matcher m = TYPE.matcher(source);
        while (m.find()) {
            // only top-level types: the declaration is at brace depth 0
            if (depthAt(source, m.start()) != 0) {
                continue;
            }
            String modifiers = m.group(1);
            String kind = m.group(2);
            String name = m.group(3);
            int open = source.indexOf('{', m.end());
            int close = matching(source, open);
            String body = source.substring(open + 1, close);
            // what the library calls with what it reads: interfaces and
            // abstract classes for handlers, not the classes that drive them
            boolean callback = (name.endsWith("Handler") || name.endsWith("Sink") || name.endsWith("Adapter"))
                    && (kind.equals("interface") || modifiers.contains("abstract"));
            members(name, kind.equals("interface"), callback, body, out);
        }
    }

    // Splits a type body into its members, skipping nested blocks.
    private static void members(String type, boolean iface, boolean callback, String body,
                                List<Method> out) {
        int depth = 0;
        StringBuilder segment = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    method(type, iface, callback, segment.toString(), out);
                    segment.setLength(0);
                }
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 0) {
                if (c == ';') {
                    method(type, iface, callback, segment.toString(), out);
                    segment.setLength(0);
                } else {
                    segment.append(c);
                }
            }
        }
    }

    private static void method(String type, boolean iface, boolean callback, String segment,
                               List<Method> out) {
        String s = segment.replaceAll("@\\w+(\\([^)]*\\))?", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || s.indexOf('(') < 0 || s.contains("=")) {
            return;
        }
        Matcher m = METHOD.matcher(s);
        if (!m.matches()) {
            return;
        }
        String modifiers = m.group(1);
        boolean visible = iface ? !modifiers.contains("private")
                : modifiers.contains("public") || modifiers.contains("protected");
        if (!visible) {
            return;
        }
        Method method = new Method(type, callback, m.group(3), m.group(2).trim());
        String params = m.group(4).trim();
        if (!params.isEmpty()) {
            int depth = 0;
            int start = 0;
            for (int i = 0; i <= params.length(); i++) {
                char c = i < params.length() ? params.charAt(i) : ',';
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    String one = params.substring(start, i).trim().replaceFirst("^final\\s+", "");
                    int sp = one.lastIndexOf(' ');
                    if (sp > 0) {
                        method.params.add(new String[] { one.substring(0, sp).trim(), one.substring(sp + 1) });
                    }
                    start = i + 1;
                }
            }
        }
        out.add(method);
    }

    private static int depthAt(String s, int index) {
        int depth = 0;
        for (int i = 0; i < index; i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }

    private static int matching(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("unbalanced braces");
    }

    // Replaces comments, strings and characters by spaces, keeping the layout.
    private static String blank(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (; i < end; i++) {
                    sb.append(s.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                sb.append(' ');
                i++;
                while (i < s.length() && s.charAt(i) != quote) {
                    if (s.charAt(i) == '\\') {
                        sb.append(' ');
                        i++;
                    }
                    sb.append(' ');
                    i++;
                }
                sb.append(' ');
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static Path locate(String relative) {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve(relative);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = cwd.resolve("../" + relative).normalize();
        if (Files.isDirectory(parent)) {
            return parent;
        }
        throw new IllegalStateException("Could not locate " + relative + " from " + cwd);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append("\n  ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

}
