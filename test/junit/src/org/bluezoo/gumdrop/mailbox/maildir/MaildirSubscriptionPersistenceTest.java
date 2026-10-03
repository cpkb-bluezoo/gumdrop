/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mailbox.maildir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link MaildirMailboxStore} writes its subscription list as it
 * changes, as the mbox store does, so a crash before close loses nothing.
 * A crash is simulated by opening a second store on the same file system
 * without closing the first.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirSubscriptionPersistenceTest {

    private Path root;
    private MaildirMailboxStore store;

    @Before
    public void setUp() throws IOException {
        root = MemoryFileSystem.create().getPath("/mail");
        Files.createDirectories(root);
        store = new MaildirMailboxStore(root);
        store.open("bob");
    }

    private MaildirMailboxStore crashAndReopen() throws IOException {
        MaildirMailboxStore again = new MaildirMailboxStore(root);
        again.open("bob");
        return again;
    }

    @Test
    public void subscribeSurvivesACrash() throws IOException {
        store.subscribe("Work");
        assertEquals(Arrays.asList("INBOX", "Work"), crashAndReopen().listSubscribed("", "*"));
    }

    @Test
    public void unsubscribeSurvivesACrash() throws IOException {
        store.subscribe("Work");
        store.subscribe("Play");
        store.unsubscribe("Work");
        assertEquals(Arrays.asList("INBOX", "Play"), crashAndReopen().listSubscribed("", "*"));
    }

    @Test
    public void deleteSurvivesACrash() throws IOException {
        store.createMailbox("Gone");
        store.subscribe("Gone");
        store.deleteMailbox("Gone");
        assertFalse(crashAndReopen().listSubscribed("", "*").contains("Gone"));
    }

    @Test
    public void renameSurvivesACrash() throws IOException {
        store.createMailbox("old");
        store.createMailbox("old/kid");
        store.subscribe("old");
        store.subscribe("old/kid");
        store.renameMailbox("old", "new");
        assertEquals(Arrays.asList("INBOX", "new", "new/kid"),
            crashAndReopen().listSubscribed("", "*"));
    }

    @Test
    public void writesLeaveNoTemporaryFileBehind() throws IOException {
        store.subscribe("Work");
        store.subscribe("Play");
        DirectoryStream<Path> entries = Files.newDirectoryStream(root.resolve("bob"));
        try {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                assertFalse(name, name.endsWith(".tmp"));
            }
        } finally {
            entries.close();
        }
        assertTrue(Files.exists(root.resolve("bob").resolve(".subscriptions")));
    }
}
