/*
 * Gumdrop3NamingConventionTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.testsupport;

import org.junit.Test;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Workstream C.1 guardrail: every legacy-pattern public type in
 * {@code src/org/bluezoo/gumdrop} must be listed in
 * {@code gumdrop3-legacy-type-renames.properties} until it is renamed.
 *
 * @see docs/NAMING-TAXONOMY.md
 */
public class Gumdrop3NamingConventionTest {

    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "^\\s*public (?:final )?(?:class|interface|enum) (\\w+)",
            Pattern.MULTILINE);

    private static final String INVENTORY = "gumdrop3-legacy-type-renames.properties";

    @Test
    public void testSuggestGumdrop3NameExamples() {
        assertEquals("HttpServer", Gumdrop3NamingRules.suggestGumdrop3Name("HTTPService"));
        assertEquals("HttpClient", Gumdrop3NamingRules.suggestGumdrop3Name("HTTPClient"));
        assertEquals("SmtpServer", Gumdrop3NamingRules.suggestGumdrop3Name("SMTPService"));
        assertEquals("EhloReplyHandler",
                Gumdrop3NamingRules.suggestGumdrop3Name("ServerEhloReplyHandler"));
        assertEquals(null, Gumdrop3NamingRules.suggestGumdrop3Name("HttpServer"));
    }

    @Test
    public void testLegacyPublicTypesAreInventoried() throws Exception {
        Map<String, String> inventory = loadInventory();
        Set<String> publicTypes = collectPublicTypeNames(locateSourceRoot());

        List<String> undocumented = new ArrayList<String>();
        for (String typeName : publicTypes) {
            if (Gumdrop3NamingRules.isLegacyPublicTypeName(typeName)
                    && !inventory.containsKey(typeName)) {
                undocumented.add(typeName + " (suggested: "
                        + Gumdrop3NamingRules.suggestGumdrop3Name(typeName) + ")");
            }
        }
        if (!undocumented.isEmpty()) {
            fail("Legacy public types missing from " + INVENTORY + " — add LegacyName=TargetName "
                    + "lines (see docs/NAMING-TAXONOMY.md): " + undocumented);
        }

        List<String> stale = new ArrayList<String>();
        for (String legacyName : inventory.keySet()) {
            if (!publicTypes.contains(legacyName)) {
                stale.add(legacyName);
            }
        }
        if (!stale.isEmpty()) {
            fail("Stale entries in " + INVENTORY + " (type renamed or removed — delete line): "
                    + stale);
        }
    }

    @Test
    public void testInventoryTargetsMatchNamingRules() throws Exception {
        Map<String, String> inventory = loadInventory();
        List<String> mismatches = new ArrayList<String>();
        for (Map.Entry<String, String> entry : inventory.entrySet()) {
            String suggested = Gumdrop3NamingRules.suggestGumdrop3Name(entry.getKey());
            if (suggested != null && !suggested.equals(entry.getValue())) {
                mismatches.add(entry.getKey() + ": inventory=" + entry.getValue()
                        + " suggested=" + suggested);
            }
        }
        assertTrue("Inventory target names should match Gumdrop3NamingRules: " + mismatches,
                mismatches.isEmpty());
    }

    private static Map<String, String> loadInventory() throws IOException {
        InputStream in = Gumdrop3NamingConventionTest.class.getClassLoader()
                .getResourceAsStream(INVENTORY);
        if (in == null) {
            fail("Missing test resource " + INVENTORY);
        }
        Properties props = new Properties();
        try {
            props.load(in);
        } finally {
            in.close();
        }
        Map<String, String> inventory = new HashMap<String, String>();
        for (String key : props.stringPropertyNames()) {
            inventory.put(key, props.getProperty(key));
        }
        return inventory;
    }

    private static Set<String> collectPublicTypeNames(Path sourceRoot) throws IOException {
        final Set<String> names = new HashSet<String>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher matcher = PUBLIC_TYPE.matcher(content);
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return names;
    }

    private static Path locateSourceRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path candidate = cwd.resolve("src/org/bluezoo/gumdrop");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = cwd.getParent().resolve("src/org/bluezoo/gumdrop");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        fail("Cannot locate src/org/bluezoo/gumdrop from " + cwd);
        return null;
    }
}
