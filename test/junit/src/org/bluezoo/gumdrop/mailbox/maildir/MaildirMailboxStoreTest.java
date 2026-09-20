/*
 * MaildirMailboxStoreTest.java
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

import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the mailbox-management side of {@link MaildirMailboxStore}
 * (listing, deletion, attributes, quota) on an in-memory file system.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirMailboxStoreTest {

    private MemoryFileSystem mem;
    private Path root;
    private Path userDir;
    private MaildirMailboxStore store;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        root = mem.getPath("/mail");
        Files.createDirectories(root);
        userDir = root.resolve("alice");
        store = new MaildirMailboxStore(root);
        store.open("alice");
    }

    @After
    public void tearDown() throws IOException {
        store.close();
    }

    private static void writeBytes(Path file, int length) throws IOException {
        Files.write(file, new byte[length]);
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private void expectIOException(String what, IoAction action) {
        try {
            action.run();
            fail("expected IOException: " + what);
        } catch (IOException expected) {
            // refused
        }
    }

    /** Anonymous-class friendly stand-in for a throwing runnable. */
    private interface IoAction {
        void run() throws IOException;
    }

    // Opening

    @Test
    public void testOpenCreatesInboxStructure() {
        assertTrue(Files.isDirectory(userDir.resolve("cur")));
        assertTrue(Files.isDirectory(userDir.resolve("new")));
        assertTrue(Files.isDirectory(userDir.resolve("tmp")));
    }

    @Test
    public void testOpenRejectsUnsafeUsernames() throws IOException {
        MaildirMailboxStore other = new MaildirMailboxStore(root);
        String[] bad = { "a/b", "a\\b", "..", "x..y", ".hidden" };
        for (int i = 0; i < bad.length; i++) {
            try {
                other.open(bad[i]);
                fail("expected IOException for " + bad[i]);
            } catch (IOException expected) {
                // refused
            }
        }
    }

    // Listing

    @Test
    public void testFreshStoreListsOnlyInbox() throws IOException {
        assertEquals(Arrays.asList("INBOX"), store.listMailboxes("", "*"));
    }

    @Test
    public void testListIncludesCreatedMailboxesSortedIgnoringCase() throws IOException {
        store.createMailbox("Sent");
        store.createMailbox("archive");
        store.createMailbox("Drafts");
        assertEquals(Arrays.asList("archive", "Drafts", "INBOX", "Sent"),
                store.listMailboxes("", "*"));
    }

    @Test
    public void testNestedMailboxUsesMaildirPlusPlusName() throws IOException {
        store.createMailbox("work/2024");
        assertTrue(Files.isDirectory(userDir.resolve(".work.2024/cur")));
        assertTrue(store.listMailboxes("", "*").contains("work/2024"));
    }

    @Test
    public void testPercentWildcardDoesNotCrossHierarchy() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/2024");
        assertEquals(Arrays.asList("INBOX", "work"), store.listMailboxes("", "%"));
        assertEquals(Arrays.asList("INBOX", "work", "work/2024"),
                store.listMailboxes("", "*"));
    }

    @Test
    public void testListWithReferenceAndPattern() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/2024");
        store.createMailbox("play");
        assertEquals(Arrays.asList("work", "work/2024"),
                store.listMailboxes("work", "*"));
    }

    @Test
    public void testDirectoriesThatAreNotMaildirsAreNotListed() throws IOException {
        Files.createDirectories(userDir.resolve(".incomplete/cur"));
        Files.createDirectories(userDir.resolve(".incomplete/new"));
        Files.createDirectories(userDir.resolve("plain/cur"));
        Files.createDirectories(userDir.resolve("plain/new"));
        Files.createDirectories(userDir.resolve("plain/tmp"));
        writeText(userDir.resolve(".notadir"), "x");
        assertEquals(Arrays.asList("INBOX"), store.listMailboxes("", "*"));
    }

    @Test
    public void testEncodedMailboxNamesRoundTrip() throws IOException {
        store.createMailbox("a b");
        assertTrue(store.listMailboxes("", "*").contains("a b"));
    }

    @Test
    public void testListRequiresOpenStore() throws IOException {
        store.close();
        expectIOException("listMailboxes on closed store", new IoAction() {
            @Override
            public void run() throws IOException {
                store.listMailboxes("", "*");
            }
        });
        store.open("alice");
    }

    // Create

    @Test
    public void testCreateExistingOrInboxRefused() throws IOException {
        store.createMailbox("Sent");
        expectIOException("duplicate", new IoAction() {
            @Override
            public void run() throws IOException {
                store.createMailbox("Sent");
            }
        });
        expectIOException("INBOX", new IoAction() {
            @Override
            public void run() throws IOException {
                store.createMailbox("inbox");
            }
        });
    }

    // Delete

    @Test
    public void testDeleteEmptyMailboxRemovesItEntirely() throws IOException {
        store.createMailbox("Sent");
        writeText(userDir.resolve(".Sent/.gidx"), "index");
        writeText(userDir.resolve(".Sent/tmp/in-flight"), "x");
        store.deleteMailbox("Sent");
        assertFalse(Files.exists(userDir.resolve(".Sent")));
        assertEquals(Arrays.asList("INBOX"), store.listMailboxes("", "*"));
    }

    @Test
    public void testDeleteUnsubscribes() throws IOException {
        store.createMailbox("Sent");
        store.subscribe("Sent");
        assertTrue(store.listSubscribed("", "*").contains("Sent"));
        store.deleteMailbox("Sent");
        assertFalse(store.listSubscribed("", "*").contains("Sent"));
    }

    @Test
    public void testDeleteRefusesInboxAndMissing() {
        expectIOException("INBOX", new IoAction() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("INBOX");
            }
        });
        expectIOException("missing", new IoAction() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("Nope");
            }
        });
    }

    @Test
    public void testDeleteRefusedWhenCurHasMessages() throws IOException {
        store.createMailbox("Sent");
        writeText(userDir.resolve(".Sent/cur/1.msg:2,S"), "m");
        expectIOException("non-empty cur", new IoAction() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("Sent");
            }
        });
        assertTrue(Files.exists(userDir.resolve(".Sent/cur/1.msg:2,S")));
    }

    @Test
    public void testDeleteRefusedWhenNewHasUnreadMessages() throws IOException {
        store.createMailbox("Sent");
        writeText(userDir.resolve(".Sent/new/1.msg"), "unread");
        expectIOException("non-empty new", new IoAction() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("Sent");
            }
        });
        assertTrue("unread message must survive",
                Files.exists(userDir.resolve(".Sent/new/1.msg")));
    }

    @Test
    public void testDeleteRefusesDirectoryThatIsNotAMaildir() throws IOException {
        Files.createDirectories(userDir.resolve(".junk/cur"));
        writeText(userDir.resolve(".junk/precious.txt"), "keep me");
        expectIOException("not a maildir", new IoAction() {
            @Override
            public void run() throws IOException {
                store.deleteMailbox("junk");
            }
        });
        assertTrue(Files.exists(userDir.resolve(".junk/precious.txt")));
    }

    @Test
    public void testDeleteLeavesRelatedMailboxesAlone() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/2024");
        store.deleteMailbox("work");
        assertFalse(Files.exists(userDir.resolve(".work")));
        assertTrue(Files.isDirectory(userDir.resolve(".work.2024/cur")));
    }

    // Rename

    @Test
    public void testRenameMovesMessagesAndSubscription() throws IOException {
        store.createMailbox("Old");
        writeText(userDir.resolve(".Old/cur/1.msg:2,S"), "m");
        store.subscribe("Old");
        store.renameMailbox("Old", "New");
        assertFalse(Files.exists(userDir.resolve(".Old")));
        assertTrue(Files.exists(userDir.resolve(".New/cur/1.msg:2,S")));
        List<String> subs = store.listSubscribed("", "*");
        assertTrue(subs.contains("New"));
        assertFalse(subs.contains("Old"));
    }

    @Test
    public void testRenameRefusals() throws IOException {
        store.createMailbox("A");
        store.createMailbox("B");
        expectIOException("INBOX source", new IoAction() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("INBOX", "X");
            }
        });
        expectIOException("INBOX target", new IoAction() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("A", "INBOX");
            }
        });
        expectIOException("missing source", new IoAction() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("Nope", "X");
            }
        });
        expectIOException("target exists", new IoAction() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("A", "B");
            }
        });
    }

    // Attributes

    @Test
    public void testAttributesWithAndWithoutChildren() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/2024");
        store.createMailbox("play");
        assertTrue(store.getMailboxAttributes("work").contains(MailboxAttribute.HASCHILDREN));
        assertTrue(store.getMailboxAttributes("work/2024")
                .contains(MailboxAttribute.HASNOCHILDREN));
        assertTrue(store.getMailboxAttributes("play").contains(MailboxAttribute.HASNOCHILDREN));
    }

    @Test
    public void testInboxHasChildrenOnlyWhenSubfoldersExist() throws IOException {
        assertTrue(store.getMailboxAttributes("INBOX")
                .contains(MailboxAttribute.HASNOCHILDREN));
        store.createMailbox("Sent");
        assertTrue(store.getMailboxAttributes("INBOX").contains(MailboxAttribute.HASCHILDREN));
    }

    @Test
    public void testMissingMailboxIsNoSelect() throws IOException {
        Set<MailboxAttribute> attrs = store.getMailboxAttributes("Ghost");
        assertTrue(attrs.contains(MailboxAttribute.NOSELECT));
        assertFalse(attrs.contains(MailboxAttribute.HASCHILDREN));
        assertFalse(attrs.contains(MailboxAttribute.HASNOCHILDREN));
    }

    @Test
    public void testNonMaildirChildDoesNotCountAsChild() throws IOException {
        store.createMailbox("work");
        Files.createDirectories(userDir.resolve(".work.junk"));
        assertTrue(store.getMailboxAttributes("work")
                .contains(MailboxAttribute.HASNOCHILDREN));
    }

    // Quota

    @Test
    public void testQuotaRoot() throws IOException {
        assertEquals("alice", store.getQuotaRoot("INBOX"));
        assertNull(store.getQuota("bob"));
    }

    @Test
    public void testQuotaCountsCurAndNewAcrossAllMailboxes() throws IOException {
        store.createMailbox("Sent");
        writeBytes(userDir.resolve("cur/a"), 2048);
        writeBytes(userDir.resolve("new/b"), 1024);
        writeBytes(userDir.resolve(".Sent/cur/c"), 1024);
        MailboxStore.Quota quota = store.getQuota("alice");
        assertNotNull(quota);
        assertEquals("alice", quota.getRoot());
        assertEquals(3, quota.getMessageCount());
        assertEquals(4, quota.getStorageUsed());
        assertEquals(-1, quota.getStorageLimit());
        assertEquals(-1, quota.getMessageLimit());
    }

    @Test
    public void testQuotaIgnoresTmpHiddenFilesAndStrayDirectories() throws IOException {
        writeBytes(userDir.resolve("cur/a"), 1024);
        writeBytes(userDir.resolve("cur/.hidden"), 5000);
        writeBytes(userDir.resolve("tmp/in-flight"), 5000);
        writeBytes(userDir.resolve(".subscriptions"), 5000);
        Files.createDirectories(userDir.resolve("cur/subdir"));
        MailboxStore.Quota quota = store.getQuota("alice");
        assertEquals(1, quota.getMessageCount());
        assertEquals(1, quota.getStorageUsed());
    }

    @Test
    public void testQuotaOfEmptyStore() throws IOException {
        MailboxStore.Quota quota = store.getQuota("alice");
        assertEquals(0, quota.getMessageCount());
        assertEquals(0, quota.getStorageUsed());
    }

    // Symbolic links planted in the user's directory

    private Path outsideWithFile() throws IOException {
        Path outside = mem.getPath("/outside");
        Files.createDirectories(outside);
        writeText(outside.resolve("precious.txt"), "not yours to delete");
        return outside;
    }

    @Test
    public void testDeleteMailboxRemovesLinksButNeverTheirTargets() throws IOException {
        Path outside = outsideWithFile();
        store.createMailbox("Sent");
        Files.createSymbolicLink(userDir.resolve(".Sent/tmp/escape"), outside);
        Files.createSymbolicLink(userDir.resolve(".Sent/link"), outside);
        store.deleteMailbox("Sent");
        assertFalse(Files.exists(userDir.resolve(".Sent"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("not yours to delete", new String(
                Files.readAllBytes(outside.resolve("precious.txt")), StandardCharsets.UTF_8));
    }

    @Test
    public void testQuotaDoesNotCountThroughDirectoryLinks() throws IOException {
        Path outside = outsideWithFile();
        Files.createDirectories(outside.resolve("cur"));
        writeBytes(outside.resolve("cur/big"), 4096);
        Files.createSymbolicLink(userDir.resolve(".elsewhere"), outside);
        writeBytes(userDir.resolve("cur/mine"), 1024);
        MailboxStore.Quota quota = store.getQuota("alice");
        assertEquals(1, quota.getMessageCount());
        assertEquals(1, quota.getStorageUsed());
    }

    // Rename moves inferior mailboxes too (RFC 3501 6.3.5)

    private boolean exists(String relative) {
        return Files.exists(userDir.resolve(relative), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    @Test
    public void testRenameMovesInferiorMailboxesToo() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/2024");
        store.createMailbox("work/2024/q1");
        store.createMailbox("play");
        writeText(userDir.resolve(".work.2024/cur/1.msg:2,S"), "m");
        store.subscribe("work");
        store.subscribe("work/2024");
        store.renameMailbox("work", "job");
        assertEquals(Arrays.asList("INBOX", "job", "job/2024", "job/2024/q1", "play"),
                store.listMailboxes("", "*"));
        assertFalse(exists(".work"));
        assertFalse(exists(".work.2024"));
        assertFalse(exists(".work.2024.q1"));
        assertTrue(exists(".job.2024.q1/cur"));
        assertTrue("messages travel with their mailbox", exists(".job.2024/cur/1.msg:2,S"));
        List<String> subs = store.listSubscribed("", "*");
        assertTrue(subs.toString(), subs.contains("job"));
        assertTrue(subs.toString(), subs.contains("job/2024"));
        assertFalse(subs.toString(), subs.contains("work"));
        assertFalse(subs.toString(), subs.contains("work/2024"));
    }

    @Test
    public void testRenameLeavesMailboxesThatOnlyShareAPrefixAlone() throws IOException {
        store.createMailbox("work");
        store.createMailbox("workshop");
        store.createMailbox("work/x");
        store.renameMailbox("work", "job");
        assertTrue("workshop is not an inferior of work", exists(".workshop/cur"));
        assertTrue(exists(".job.x/cur"));
        assertFalse(exists(".work.x"));
    }

    @Test
    public void testRenameOfANameNeedingEncodingMovesItsInferiors() throws IOException {
        store.createMailbox("a:b");
        store.createMailbox("a:b/c");
        store.renameMailbox("a:b", "z");
        assertEquals(Arrays.asList("INBOX", "z", "z/c"), store.listMailboxes("", "*"));
    }

    @Test
    public void testRenameToAnInferiorOfItself() throws IOException {
        store.createMailbox("a");
        store.createMailbox("a/c");
        store.renameMailbox("a", "a/b");
        assertEquals(Arrays.asList("a/b", "a/b/c", "INBOX"), store.listMailboxes("", "*"));
    }

    @Test
    public void testRenameRefusedWhenAnInferiorsTargetExistsAndNothingMoves() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/x");
        store.createMailbox("job/x");
        expectIOException("inferior target exists", new IoAction() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("work", "job");
            }
        });
        assertTrue(exists(".work/cur"));
        assertTrue(exists(".work.x/cur"));
        assertFalse(exists(".job"));
        assertTrue(exists(".job.x/cur"));
    }

    @Test
    public void testFailedRenameIsRolledBack() throws IOException {
        store.createMailbox("work");
        store.createMailbox("work/x");
        writeText(userDir.resolve(".work/cur/1.msg:2,S"), "m");
        store.subscribe("work");
        mem.failMovesTo(userDir.resolve(".job.x"));
        expectIOException("second move fails", new IoAction() {
            @Override
            public void run() throws IOException {
                store.renameMailbox("work", "job");
            }
        });
        assertTrue("the first move was undone", exists(".work/cur/1.msg:2,S"));
        assertFalse(exists(".job"));
        assertTrue(exists(".work.x/cur"));
        assertTrue("subscriptions are unchanged", store.listSubscribed("", "*").contains("work"));
    }
}
