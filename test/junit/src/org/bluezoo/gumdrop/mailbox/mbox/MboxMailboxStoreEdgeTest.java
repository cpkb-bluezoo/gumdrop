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
package org.bluezoo.gumdrop.mailbox.mbox;

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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge-case tests for {@link MboxMailboxStore} on an in-memory file system:
 * user name and mailbox name validation, files where directories are expected,
 * the IMAP list pattern matcher, subscription file parsing and the checks
 * made by create, delete and rename.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMailboxStoreEdgeTest {

    private Path root;
    private MboxMailboxStore store;

    @Before
    public void setUp() throws IOException {
        root = MemoryFileSystem.create().getPath("/store");
        Files.createDirectories(root);
        store = new MboxMailboxStore(root);
    }

    private void expectIo(Runnable0 action, String messagePart) {
        try {
            action.run();
            fail("expected IOException containing: " + messagePart);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    /** An action that may throw an IOException. */
    private interface Runnable0 {
        void run() throws IOException;
    }

    @Test
    public void hierarchyDelimiterAndExtension() {
        assertEquals('/', store.getHierarchyDelimiter());
        assertEquals(".mbox", store.getExtension());
        assertEquals(".mbx", new MboxMailboxStore(root, "mbx").getExtension());
    }

    @Test
    public void invalidUserNamesAreRejected() {
        String[] bad = {"..", ".", "a/b", "a\\b", "x:y", "   "};
        for (int i = 0; i < bad.length; i++) {
            final String name = bad[i];
            MboxMailboxStore fresh = new MboxMailboxStore(root);
            final MboxMailboxStore target = fresh;
            expectIo(new Runnable0() {
                @Override
                public void run() throws IOException {
                    target.open(name);
                }
            }, "Invalid username");
        }
    }

    @Test
    public void userPathThatIsAFileIsRejected() throws IOException {
        Files.write(root.resolve("carol"), new byte[] {1});
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.open("carol");
            }
        }, "not a directory");
    }

    @Test
    public void closeIsIdempotentAndForgetsTheUser() throws IOException {
        store.close();
        store.open("bob");
        store.close();
        store.close();
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.listMailboxes("", "*");
            }
        }, "not open");
    }

    @Test
    public void openMailboxRequiresARegularFile() throws IOException {
        store.open("bob");
        Files.createDirectories(root.resolve("bob").resolve("weird.mbox"));
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.openMailbox("weird", false);
            }
        }, "not a file");
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.openMailbox("absent", false);
            }
        }, "does not exist");
        Set<MailboxAttribute> attrs = store.getMailboxAttributes("weird");
        assertTrue(attrs.contains(MailboxAttribute.NOSELECT));
        assertTrue(store.getMailboxAttributes("absent").contains(MailboxAttribute.NONEXISTENT));
    }

    @Test
    public void emptyAndNullNamesMeanInbox() throws IOException {
        store.open("bob");
        Mailbox viaNull = store.openMailbox(null, true);
        assertEquals("INBOX", viaNull.getName());
        viaNull.close(false);
        Mailbox viaEmpty = store.openMailbox("", true);
        assertEquals("INBOX", viaEmpty.getName());
        viaEmpty.close(false);
        Mailbox viaMixedCase = store.openMailbox("inbox", true);
        assertEquals("INBOX", viaMixedCase.getName());
        viaMixedCase.close(false);
    }

    @Test
    public void inboxComponentsInsideNamesAreNormalised() throws IOException {
        store.open("bob");
        store.createMailbox("inbox/Child");
        List<String> all = store.listMailboxes("", "*");
        assertTrue(all.toString(), all.contains("INBOX/Child"));
    }

    @Test
    public void dangerousMailboxNamesAreRejected() throws IOException {
        store.open("bob");
        String[] bad = {"..", "a/../b", "a//b"};
        for (int i = 0; i < bad.length; i++) {
            final String name = bad[i];
            try {
                store.createMailbox(name);
                fail("created mailbox " + name);
            } catch (IOException expected) {
                assertTrue(name + ": " + expected.getMessage(),
                    expected.getMessage().contains("Invalid"));
            }
        }
    }

    @Test
    public void createDeleteAndRenameChecks() throws IOException {
        store.open("bob");
        store.createMailbox("a");
        store.createMailbox("deep/er/box");
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.createMailbox("a");
            }
        }, "already exists");
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("inbox");
            }
        }, "Cannot delete INBOX");
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("missing");
            }
        }, "does not exist");
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("missing", "x");
            }
        }, "does not exist");
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("a", "deep/er/box");
            }
        }, "already exists");
        Files.write(root.resolve("bob").resolve("full.mbox"), new byte[] {'F', 'r', 'o', 'm', ' '});
        expectIo(new Runnable0() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("full");
            }
        }, "not empty");
        store.subscribe("a");
        store.deleteMailbox("a");
        assertFalse(store.listSubscribed("", "*").contains("a"));
        assertTrue(store.getMailboxAttributes("deep/er/box").contains(MailboxAttribute.HASNOCHILDREN));
        store.createMailbox("deep/er");
        assertTrue(store.getMailboxAttributes("deep/er").contains(MailboxAttribute.HASCHILDREN));
    }

    @Test
    public void listPatternsMatchLikeImap() throws IOException {
        store.open("bob");
        String[] names = {"a", "a/b", "a/b/c", "ab", "B"};
        for (int i = 0; i < names.length; i++) {
            store.createMailbox(names[i]);
        }
        assertEquals(Arrays.asList("B", "INBOX", "a", "a/b", "a/b/c", "ab"), store.listMailboxes("", "*"));
        assertEquals(Arrays.asList("B", "INBOX", "a", "ab"), store.listMailboxes("", "%"));
        assertEquals(Arrays.asList("a/b"), store.listMailboxes("a/", "%"));
        assertEquals(Arrays.asList("a/b", "a/b/c"), store.listMailboxes("a/", "*"));
        assertEquals(Arrays.asList("a", "ab"), store.listMailboxes("", "a%"));
        assertEquals(Arrays.asList("B", "a/b", "ab"), store.listMailboxes("", "*b"));
        assertEquals(Arrays.asList("B", "a/b", "ab"), store.listMailboxes("", "*B"));
        assertEquals(Arrays.asList("a/b/c"), store.listMailboxes("", "a/%/c"));
        assertEquals(Arrays.asList("a/b/c"), store.listMailboxes("", "a/*/c"));
        assertEquals(Arrays.asList("a"), store.listMailboxes("", "A"));
        assertTrue(store.listMailboxes("", "").isEmpty());
        assertTrue(store.listMailboxes("", "zzz").isEmpty());
        assertTrue(store.listMailboxes("", "a/b/c/d").isEmpty());
    }

    @Test
    public void subscriptionFileIsParsedAndKeptAcrossReopen() throws IOException {
        Path user = root.resolve("bob");
        Files.createDirectories(user);
        String text = "# comment\n\n  spaced  \nINBOX\nwork\n";
        Files.write(user.resolve(".subscriptions"), text.getBytes(StandardCharsets.UTF_8));
        store.open("bob");
        List<String> subscribed = store.listSubscribed("", "*");
        assertEquals(Arrays.asList("INBOX", "spaced", "work"), subscribed);
        store.unsubscribe("work");
        store.subscribe("Play");
        store.close();
        MboxMailboxStore again = new MboxMailboxStore(root);
        again.open("bob");
        assertEquals(Arrays.asList("INBOX", "Play", "spaced"), again.listSubscribed("", "*"));
        assertEquals(Arrays.asList("spaced"), again.listSubscribed("", "s%"));
        again.close();
    }

    @Test
    public void renamingKeepsSubscriptionsOfInferiors() throws IOException {
        store.open("bob");
        store.createMailbox("old");
        store.createMailbox("old/kid");
        store.subscribe("old");
        store.subscribe("old/kid");
        store.renameMailbox("old", "new");
        List<String> subscribed = store.listSubscribed("", "*");
        assertTrue(subscribed.toString(), subscribed.contains("new"));
        assertTrue(subscribed.toString(), subscribed.contains("new/kid"));
        assertFalse(subscribed.contains("old"));
    }
}
