/*
 * MailboxFactoriesTest.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.File;
import java.nio.file.Path;

import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxFactory;
import org.bluezoo.gumdrop.mailbox.maildir.MaildirMailboxStore;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxFactory;
import org.bluezoo.gumdrop.mailbox.mbox.MboxMailboxStore;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link MboxMailboxFactory} and {@link MaildirMailboxFactory}:
 * configuration, validation and store creation.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MailboxFactoriesTest {

    private static Path memPath(String p) {
        MemoryFileSystem mem = MemoryFileSystem.create();
        return mem.getPath(p);
    }

    // ===== mbox =====

    @Test
    public void testMboxDefaultsAndUnconfiguredStore() {
        MboxMailboxFactory f = new MboxMailboxFactory();
        assertNull(f.getBaseDirectory());
        assertEquals(MboxMailboxStore.DEFAULT_EXTENSION, f.getExtension());
        try {
            f.createStore();
            fail("unconfigured");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testMboxConstructorsAndSetters() {
        File dir = new File("mailroot");
        MboxMailboxFactory byFile = new MboxMailboxFactory(dir);
        assertEquals(dir.toPath().toAbsolutePath().normalize(), byFile.getBaseDirectory());
        Path mem = memPath("/mem/mail/../mail");
        MboxMailboxFactory byPath = new MboxMailboxFactory(mem);
        assertTrue(byPath.getBaseDirectory().toString().endsWith("mail"));
        MboxMailboxFactory custom = new MboxMailboxFactory(mem, ".mailbox");
        assertEquals(".mailbox", custom.getExtension());
        custom.setExtension(".x");
        assertEquals(".x", custom.getExtension());
        custom.setBaseDirectory("relative/dir");
        assertTrue(custom.getBaseDirectory().isAbsolute());
        custom.setBaseDirectory(mem);
        assertNotNull(custom.createStore());
        assertTrue(custom.createStore() instanceof MboxMailboxStore);
        assertEquals(".x", ((MboxMailboxStore) custom.createStore()).getExtension());
    }

    @Test
    public void testMboxNullBaseDirectoryRejected() {
        try {
            new MboxMailboxFactory((Path) null, ".mbox");
            fail("constructor");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        MboxMailboxFactory f = new MboxMailboxFactory();
        try {
            f.setBaseDirectory((String) null);
            fail("string");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            f.setBaseDirectory((Path) null);
            fail("path");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ===== maildir =====

    @Test
    public void testMaildirDefaultsAndUnconfiguredStore() {
        MaildirMailboxFactory f = new MaildirMailboxFactory();
        assertNull(f.getBaseDirectory());
        try {
            f.createStore();
            fail("unconfigured");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testMaildirConstructorsAndSetters() {
        File dir = new File("mailroot");
        MaildirMailboxFactory byFile = new MaildirMailboxFactory(dir);
        assertEquals(dir.toPath().toAbsolutePath().normalize(), byFile.getBaseDirectory());
        Path mem = memPath("/mem/maildirs");
        MaildirMailboxFactory byPath = new MaildirMailboxFactory(mem);
        assertTrue(byPath.createStore() instanceof MaildirMailboxStore);
        byPath.setBaseDirectory("relative/dir");
        assertTrue(byPath.getBaseDirectory().isAbsolute());
        byPath.setBaseDirectory(mem);
        assertNotNull(byPath.createStore());
    }

    @Test
    public void testMaildirNullBaseDirectoryRejected() {
        try {
            new MaildirMailboxFactory((Path) null);
            fail("constructor");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        MaildirMailboxFactory f = new MaildirMailboxFactory();
        try {
            f.setBaseDirectory((String) null);
            fail("string");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            f.setBaseDirectory((Path) null);
            fail("path");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
