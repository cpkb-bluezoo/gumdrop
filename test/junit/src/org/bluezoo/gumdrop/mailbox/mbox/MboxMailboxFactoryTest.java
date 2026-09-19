/*
 * MboxMailboxFactoryTest.java
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

package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MboxMailboxFactory}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMailboxFactoryTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mboxfactory");
    }

    @After
    public void tearDown() throws IOException {
        Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                try { Files.delete(file); } catch (IOException e) { /* ignore */ }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                try { Files.delete(dir); } catch (IOException e) { /* ignore */ }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    public void testDefaultConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory();
        assertNull(factory.getBaseDirectory());
        assertEquals(MboxMailboxStore.DEFAULT_EXTENSION, factory.getExtension());
    }

    @Test
    public void testPathConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir);
        assertNotNull(factory.getBaseDirectory());
        assertEquals(MboxMailboxStore.DEFAULT_EXTENSION, factory.getExtension());
    }

    @Test
    public void testPathAndExtensionConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir, ".mail");
        assertEquals(".mail", factory.getExtension());
    }

    @Test
    public void testFileConstructor() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir.toFile());
        assertNotNull(factory.getBaseDirectory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullPathConstructor() {
        new MboxMailboxFactory((Path) null);
    }

    @Test
    public void testSetBaseDirectoryString() {
        MboxMailboxFactory factory = new MboxMailboxFactory();
        factory.setBaseDirectory(tempDir.toString());
        assertNotNull(factory.getBaseDirectory());
    }

    @Test
    public void testSetBaseDirectoryPath() {
        MboxMailboxFactory factory = new MboxMailboxFactory();
        factory.setBaseDirectory(tempDir);
        assertNotNull(factory.getBaseDirectory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBaseDirectoryNullString() {
        new MboxMailboxFactory().setBaseDirectory((String) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetBaseDirectoryNullPath() {
        new MboxMailboxFactory().setBaseDirectory((Path) null);
    }

    @Test
    public void testSetExtension() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir);
        factory.setExtension(".mail");
        assertEquals(".mail", factory.getExtension());
    }

    @Test
    public void testCreateStore() {
        MboxMailboxFactory factory = new MboxMailboxFactory(tempDir);
        MailboxStore store = factory.createStore();
        assertNotNull(store);
        assertTrue(store instanceof MboxMailboxStore);
    }

    @Test(expected = IllegalStateException.class)
    public void testCreateStoreWithoutBasedir() {
        new MboxMailboxFactory().createStore();
    }
}
