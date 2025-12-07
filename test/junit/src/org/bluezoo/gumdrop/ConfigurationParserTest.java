/*
 * ConfigurationParserTest.java
 * Copyright (C) 2025 Chris Burdess
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

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

/**
 * Unit tests for ConfigurationParser.
 */
public class ConfigurationParserTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("config-test").toFile();
    }

    @After
    public void tearDown() {
        // Clean up temp files
        if (tempDir != null && tempDir.exists()) {
            deleteRecursively(tempDir);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private File createConfigFile(String content) throws IOException {
        File file = new File(tempDir, "gumdroprc.xml");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
        return file;
    }

    @Test
    public void testEmptyConfiguration() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n<gumdrop></gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertNotNull(result);
        assertNotNull(result.getRegistry());
        assertTrue(result.getRegistry().getComponentIds().isEmpty());
    }

    @Test
    public void testSimpleComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"testRealm\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("testRealm"));
    }

    @Test
    public void testComponentWithSimpleProperty() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"testRealm\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"name\">Test Realm</property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("testRealm"));
    }

    @Test
    public void testComponentWithAttributeProperties() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"myRealm\" class=\"org.bluezoo.gumdrop.BasicRealm\" " +
            "         name=\"My Realm\">\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("myRealm"));
    }

    @Test
    public void testMultipleComponents() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "  <realm id=\"realm2\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "  <realm id=\"realm3\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        Set<String> ids = result.getRegistry().getComponentIds();
        assertEquals(3, ids.size());
        assertTrue(ids.contains("realm1"));
        assertTrue(ids.contains("realm2"));
        assertTrue(ids.contains("realm3"));
    }

    @Test
    public void testDefaultClassName() throws Exception {
        // Using 'realm' element should default to BasicRealm class
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"defaultRealm\">\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("defaultRealm"));
    }

    @Test
    public void testListProperty() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realmWithList\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"items\">\n" +
            "      <list>\n" +
            "      </list>\n" +
            "    </property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realmWithList"));
    }

    @Test
    public void testMapProperty() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realmWithMap\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"config\">\n" +
            "      <map>\n" +
            "        <entry key=\"setting1\">value1</entry>\n" +
            "      </map>\n" +
            "    </property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realmWithMap"));
    }

    @Test
    public void testComponentReference() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "  <realm id=\"realm2\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"delegate\" ref=\"realm1\"/>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
        assertTrue(result.getRegistry().hasComponent("realm2"));
    }

    @Test
    public void testReferenceWithHashPrefix() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "  <realm id=\"realm2\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"delegate\" ref=\"#realm1\"/>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
        assertTrue(result.getRegistry().hasComponent("realm2"));
    }

    @Test
    public void testServerComponent() throws Exception {
        // Server is a known element type
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <server id=\"httpServer\" port=\"8080\">\n" +
            "  </server>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("httpServer"));
    }

    @Test
    public void testNestedPropertyElement() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"name\">Nested Value</property>\n" +
            "    <property name=\"timeout\">30</property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
    }

    @Test
    public void testWhitespaceInPropertyValue() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"name\">  Value with spaces  </property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
    }

    @Test
    public void testContainerComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <container id=\"myContainer\">\n" +
            "  </container>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("myContainer"));
    }

    @Test
    public void testMailboxFactoryComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <mailbox-factory id=\"mbox\">\n" +
            "  </mailbox-factory>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("mbox"));
    }

    @Test
    public void testSmtpHandlerComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <smtp-handler-factory id=\"smtp\">\n" +
            "  </smtp-handler-factory>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("smtp"));
    }

    @Test
    public void testFtpHandlerComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <ftp-handler-factory id=\"ftp\">\n" +
            "  </ftp-handler-factory>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("ftp"));
    }

    @Test
    public void testDataSourceComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <data-source id=\"testDS\">\n" +
            "  </data-source>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("testDS"));
    }

    @Test
    public void testMailSessionComponent() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <mail-session id=\"mailSession\">\n" +
            "  </mail-session>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("mailSession"));
    }

    @Test
    public void testListWithReferences() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "  <realm id=\"realm2\" class=\"org.bluezoo.gumdrop.BasicRealm\"/>\n" +
            "  <realm id=\"realmHolder\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"realms\">\n" +
            "      <list>\n" +
            "        <ref ref=\"realm1\"/>\n" +
            "        <ref ref=\"realm2\"/>\n" +
            "      </list>\n" +
            "    </property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
        assertTrue(result.getRegistry().hasComponent("realm2"));
        assertTrue(result.getRegistry().hasComponent("realmHolder"));
    }

    @Test
    public void testTildeExpansionInPath() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"path\">~/config</property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
    }

    @Test
    public void testSpecialCharactersInValues() throws Exception {
        String config = "<?xml version=\"1.0\"?>\n" +
            "<gumdrop>\n" +
            "  <realm id=\"realm1\" class=\"org.bluezoo.gumdrop.BasicRealm\">\n" +
            "    <property name=\"pattern\">&lt;html&gt;&amp;test&lt;/html&gt;</property>\n" +
            "  </realm>\n" +
            "</gumdrop>";
        File file = createConfigFile(config);

        ConfigurationParser parser = new ConfigurationParser();
        ParseResult result = parser.parse(file);

        assertTrue(result.getRegistry().hasComponent("realm1"));
    }
}

