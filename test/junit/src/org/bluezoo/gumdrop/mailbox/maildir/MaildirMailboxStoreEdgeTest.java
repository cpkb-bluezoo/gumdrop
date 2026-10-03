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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge-case tests for {@link MaildirMailboxStore} on an in-memory file
 * system: construction and user name validation, the IMAP list pattern
 * matcher, subscription persistence, and the checks made by create, delete
 * and rename, including inferior hierarchies.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirMailboxStoreEdgeTest {

    private Path root;
    private MaildirMailboxStore store;

    @Before
    public void setUp() throws IOException {
        root = MemoryFileSystem.create().getPath("/mail");
        Files.createDirectories(root);
        store = new MaildirMailboxStore(root);
    }

    /** An action that may throw an IOException. */
    private interface Action {
        void run() throws IOException;
    }

    private static void expectIo(Action action, String messagePart) {
        try {
            action.run();
            fail("expected IOException containing: " + messagePart);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    @Test
    public void constructorRejectsNullRoot() {
        try {
            new MaildirMailboxStore(null);
            fail("null root accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Root directory"));
        }
    }

    @Test
    public void userDirectoryIsOnlyKnownWhileOpen() throws IOException {
        assertNull(store.getUserDirectory());
        store.open("bob");
        assertEquals(root.resolve("bob"), store.getUserDirectory());
        store.close();
        assertNull(store.getUserDirectory());
        store.close();
    }

    @Test
    public void invalidUserNamesAreRejected() {
        try {
            store.open(null);
            fail("null user accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Username"));
        } catch (IOException unexpected) {
            fail("wrong exception type");
        }
        try {
            store.open("");
            fail("empty user accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Username"));
        } catch (IOException unexpected) {
            fail("wrong exception type");
        }
        String[] bad = {"a/b", "a\\b", "..", "a..b", ".hidden"};
        for (int i = 0; i < bad.length; i++) {
            final String name = bad[i];
            expectIo(new Action() {
                @Override
                public void run() throws IOException {
                    store.open(name);
                }
            }, "Invalid username");
        }
    }

    @Test
    public void operationsRequireAnOpenStore() {
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.listMailboxes("", "*");
            }
        }, "not open");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.openMailbox("INBOX", true);
            }
        }, "not open");
    }

    @Test
    public void listPatternsMatchLikeImapAndSortWithoutCase() throws IOException {
        store.open("bob");
        String[] names = {"a", "a/b", "a/b/c", "ab", "B"};
        for (int i = 0; i < names.length; i++) {
            store.createMailbox(names[i]);
        }
        assertEquals(Arrays.asList("a", "a/b", "a/b/c", "ab", "B", "INBOX"), store.listMailboxes("", "*"));
        assertEquals(Arrays.asList("a", "ab", "B", "INBOX"), store.listMailboxes("", "%"));
        assertEquals(Arrays.asList("a/b"), store.listMailboxes("a/", "%"));
        assertEquals(Arrays.asList("a/b", "a/b/c"), store.listMailboxes("a/", "*"));
        assertEquals(Arrays.asList("a", "ab"), store.listMailboxes("", "a%"));
        assertEquals(Arrays.asList("a/b", "ab", "B"), store.listMailboxes("", "*b"));
        assertEquals(Arrays.asList("a/b/c"), store.listMailboxes("", "a/%/c"));
        assertEquals(Arrays.asList("a/b/c"), store.listMailboxes("", "a/*/c"));
        assertEquals(Arrays.asList("INBOX"), store.listMailboxes("", "inbox"));
        assertTrue(store.listMailboxes("", "zzz").isEmpty());
        assertTrue(store.listMailboxes("", "a/b/c/d").isEmpty());
    }

    @Test
    public void subscriptionsAreParsedAndPersisted() throws IOException {
        Path user = root.resolve("bob");
        Files.createDirectories(user);
        String text = "# comment\n\n  spaced  \nINBOX\nwork\n";
        Files.write(user.resolve(".subscriptions"), text.getBytes(StandardCharsets.UTF_8));
        store.open("bob");
        assertEquals(Arrays.asList("INBOX", "spaced", "work"), store.listSubscribed("", "*"));
        store.unsubscribe("work");
        store.subscribe("inbox");
        store.subscribe("Play");
        assertEquals(Arrays.asList("INBOX", "Play", "spaced"), store.listSubscribed("", "*"));
        assertEquals(Arrays.asList("spaced"), store.listSubscribed("", "s%"));
        store.close();
        MaildirMailboxStore again = new MaildirMailboxStore(root);
        again.open("bob");
        assertEquals(Arrays.asList("INBOX", "Play", "spaced"), again.listSubscribed("", "*"));
        again.close();
    }

    @Test
    public void freshUserIsSubscribedToInbox() throws IOException {
        store.open("fresh");
        assertEquals(Arrays.asList("INBOX"), store.listSubscribed("", "*"));
    }

    @Test
    public void createDeleteAndOpenChecks() throws IOException {
        store.open("bob");
        store.createMailbox("a");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.createMailbox("a");
            }
        }, "already exists");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.createMailbox("inbox");
            }
        }, "Cannot create INBOX");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("INBOX");
            }
        }, "Cannot delete INBOX");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("missing");
            }
        }, "does not exist");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.openMailbox("missing", false);
            }
        }, "does not exist");
        Path cur = root.resolve("bob").resolve(".a").resolve("cur");
        Files.write(cur.resolve("1.msg:2,"), "x".getBytes(StandardCharsets.UTF_8));
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("a");
            }
        }, "not empty");
        Files.delete(cur.resolve("1.msg:2,"));
        store.subscribe("a");
        store.deleteMailbox("a");
        assertFalse(store.listSubscribed("", "*").contains("a"));
        assertFalse(store.listMailboxes("", "*").contains("a"));
    }

    @Test
    public void renameChecksAndInferiors() throws IOException {
        store.open("bob");
        store.createMailbox("old");
        store.createMailbox("old/kid");
        store.createMailbox("taken");
        store.subscribe("old");
        store.subscribe("old/kid");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("INBOX", "x");
            }
        }, "Cannot rename INBOX");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("old", "inbox");
            }
        }, "Cannot rename to INBOX");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("missing", "x");
            }
        }, "does not exist");
        expectIo(new Action() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("old", "taken");
            }
        }, "already exists");
        store.renameMailbox("old", "new");
        List<String> all = store.listMailboxes("", "*");
        assertTrue(all.toString(), all.contains("new"));
        assertTrue(all.toString(), all.contains("new/kid"));
        assertFalse(all.contains("old"));
        List<String> subscribed = store.listSubscribed("", "*");
        assertTrue(subscribed.toString(), subscribed.contains("new"));
        assertTrue(subscribed.toString(), subscribed.contains("new/kid"));
        assertFalse(subscribed.contains("old/kid"));
    }

    @Test
    public void attributesDistinguishMissingLeafAndParent() throws IOException {
        store.open("bob");
        store.createMailbox("parent");
        store.createMailbox("parent/child");
        Set<MailboxAttribute> missing = store.getMailboxAttributes("nothing");
        assertTrue(missing.contains(MailboxAttribute.NOSELECT));
        assertTrue(store.getMailboxAttributes("parent").contains(MailboxAttribute.HASCHILDREN));
        assertTrue(store.getMailboxAttributes("parent/child").contains(MailboxAttribute.HASNOCHILDREN));
        Mailbox box = store.openMailbox("parent/child", true);
        assertEquals("parent/child", box.getName());
        box.close(false);
    }
}
