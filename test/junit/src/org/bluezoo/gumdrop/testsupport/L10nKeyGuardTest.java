/*
 * L10nKeyGuardTest.java
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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Every {@code L10N.getString("key")} in main source must name a key that
 * exists in the bundle the file loads, in the default bundle and in each
 * of the four translations. A missing key throws
 * {@code MissingResourceException} at the moment a message is logged, which
 * on an event-loop thread hides the original error and can kill the loop.
 * Files whose bundle cannot be determined statically (no single
 * {@code getBundle} with a literal name or a package-relative
 * {@code .L10N}) are skipped.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see CONTRIBUTING.md
 */
public class L10nKeyGuardTest {

    private static final String MAIN_ROOT = "src";
    private static final String[] SUFFIXES = {"", "_en", "_fr", "_es", "_de"};
    private static final String GET_BUNDLE = "ResourceBundle.getBundle(";
    private static final String GET_STRING = "L10N.getString(\"";

    @Test
    public void everyReferencedKeyExistsInAllLanguages() throws IOException {
        Path root = locateRoot();
        assertNotNull("main source root not found", root);
        List<Path> files = new ArrayList<Path>();
        Path base = root.resolve("org/bluezoo/gumdrop");
        try (Stream<Path> walk = Files.walk(base)) {
            for (Iterator<Path> it = walk.iterator(); it.hasNext();) {
                Path p = it.next();
                if (p.toString().endsWith(".java")) {
                    files.add(p);
                }
            }
        }
        Collections.sort(files);
        Map<String, Properties> cache = new HashMap<String, Properties>();
        List<String> problems = new ArrayList<String>();
        for (Path file : files) {
            check(root, file, cache, problems);
        }
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "L10N keys referenced but missing from a bundle:");
            for (String p : problems) {
                sb.append("\n  ").append(p);
            }
            fail(sb.toString());
        }
    }

    private static void check(Path root, Path file, Map<String, Properties> cache,
            List<String> problems) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String bundle = bundleName(text);
        if (bundle == null) {
            return;
        }
        List<String> keys = keysUsed(text);
        if (keys.isEmpty()) {
            return;
        }
        String rel = root.relativize(file).toString();
        for (String suffix : SUFFIXES) {
            String path = bundle.replace('.', '/') + suffix + ".properties";
            Properties props = cache.get(path);
            if (props == null) {
                props = load(root.resolve(path));
                cache.put(path, props);
            }
            for (String key : keys) {
                if (props == null || !props.containsKey(key)) {
                    problems.add(rel + ": " + key + " (" + bundle + suffix + ")");
                }
            }
        }
    }

    /** Returns the single bundle this file loads, or null if unknown. */
    static String bundleName(String text) {
        String found = null;
        int from = 0;
        while (true) {
            int at = text.indexOf(GET_BUNDLE, from);
            if (at < 0) {
                break;
            }
            int argStart = at + GET_BUNDLE.length();
            int argEnd = text.indexOf(')', argStart);
            String arg = text.substring(argStart, argEnd < 0 ? text.length() : argEnd).trim();
            String name = null;
            if (arg.startsWith("\"")) {
                int close = arg.indexOf('"', 1);
                if (close > 0 && arg.substring(close + 1).trim().isEmpty()) {
                    name = arg.substring(1, close);
                }
            } else if (arg.contains("getPackage().getName()") && arg.contains("\".L10N\"")) {
                int p = text.indexOf("package ");
                int semi = text.indexOf(';', p);
                if (p >= 0 && semi > p) {
                    name = text.substring(p + "package ".length(), semi).trim() + ".L10N";
                }
            }
            if (name == null || (found != null && !found.equals(name))) {
                return null;
            }
            found = name;
            from = argStart;
        }
        return found;
    }

    static List<String> keysUsed(String text) {
        List<String> keys = new ArrayList<String>();
        int from = 0;
        while (true) {
            int at = text.indexOf(GET_STRING, from);
            if (at < 0) {
                break;
            }
            int start = at + GET_STRING.length();
            int end = text.indexOf('"', start);
            if (end < 0) {
                break;
            }
            String key = text.substring(start, end);
            if (!keys.contains(key)) {
                keys.add(key);
            }
            from = end;
        }
        return keys;
    }

    private static Properties load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        return props;
    }

    private static Path locateRoot() {
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
