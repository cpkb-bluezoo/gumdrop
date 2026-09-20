/*
 * MboxMailboxStoreTest.java
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

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxNameCodec;
import org.junit.After;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MboxMailboxStore}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MboxMailboxStoreTest {

    private MemoryFileSystem mem;
    private Path tempDir;
    private MboxMailboxStore store;

    @Before
    public void setUp() throws IOException {
        mem = MemoryFileSystem.create();
        tempDir = mem.getPath("/mbox");
        Files.createDirectories(tempDir);
        store = new MboxMailboxStore(tempDir);
    }

    @After
    public void tearDown() throws IOException {
        try {
            store.close();
        } catch (IOException e) { /* ignore */ }
    }

    @Test
    public void testConstructorDefaults() {
        assertEquals(MboxMailboxStore.DEFAULT_EXTENSION, store.getExtension());
        assertEquals('/', store.getHierarchyDelimiter());
    }

    @Test
    public void testConstructorCustomExtension() {
        MboxMailboxStore custom = new MboxMailboxStore(tempDir, ".mail");
        assertEquals(".mail", custom.getExtension());
    }

    @Test
    public void testConstructorExtensionNormalization() {
        MboxMailboxStore custom = new MboxMailboxStore(tempDir, "mbox");
        assertEquals(".mbox", custom.getExtension());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNullRoot() {
        new MboxMailboxStore((Path) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNullExtension() {
        new MboxMailboxStore(tempDir, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorEmptyExtension() {
        new MboxMailboxStore(tempDir, "");
    }

    @Test
    public void testOpenCreatesUserDirectory() throws IOException {
        store.open("testuser");
        assertTrue(Files.isDirectory(tempDir.resolve("testuser")));
    }

    @Test
    public void testOpenCreatesInbox() throws IOException {
        store.open("testuser");
        assertTrue(Files.exists(tempDir.resolve("testuser").resolve("INBOX.mbox")));
    }

    @Test(expected = IOException.class)
    public void testOpenTwice() throws IOException {
        store.open("testuser");
        store.open("testuser2");
    }

    @Test(expected = IOException.class)
    public void testOperationBeforeOpen() throws IOException {
        store.listMailboxes("", "*");
    }

    @Test
    public void testListMailboxes() throws IOException {
        store.open("testuser");
        List<String> mailboxes = store.listMailboxes("", "*");
        assertTrue(mailboxes.contains("INBOX"));
    }

    @Test
    public void testCreateMailbox() throws IOException {
        store.open("testuser");
        store.createMailbox("Sent");
        List<String> mailboxes = store.listMailboxes("", "*");
        assertTrue(mailboxes.contains("Sent"));
    }

    @Test(expected = IOException.class)
    public void testCreateInbox() throws IOException {
        store.open("testuser");
        store.createMailbox("INBOX");
    }

    @Test(expected = IOException.class)
    public void testCreateDuplicateMailbox() throws IOException {
        store.open("testuser");
        store.createMailbox("Drafts");
        store.createMailbox("Drafts");
    }

    @Test
    public void testDeleteMailbox() throws IOException {
        store.open("testuser");
        store.createMailbox("Trash");
        store.deleteMailbox("Trash");
        List<String> mailboxes = store.listMailboxes("", "*");
        assertFalse(mailboxes.contains("Trash"));
    }

    @Test(expected = IOException.class)
    public void testDeleteInbox() throws IOException {
        store.open("testuser");
        store.deleteMailbox("INBOX");
    }

    @Test(expected = IOException.class)
    public void testDeleteNonExistent() throws IOException {
        store.open("testuser");
        store.deleteMailbox("DoesNotExist");
    }

    @Test
    public void testRenameMailbox() throws IOException {
        store.open("testuser");
        store.createMailbox("OldName");
        store.renameMailbox("OldName", "NewName");
        List<String> mailboxes = store.listMailboxes("", "*");
        assertFalse(mailboxes.contains("OldName"));
        assertTrue(mailboxes.contains("NewName"));
    }

    @Test
    public void testSubscriptions() throws IOException {
        store.open("testuser");
        store.subscribe("Sent");
        List<String> subscribed = store.listSubscribed("", "*");
        assertTrue(subscribed.contains("INBOX"));
        assertTrue(subscribed.contains("Sent"));
    }

    @Test
    public void testUnsubscribe() throws IOException {
        store.open("testuser");
        store.subscribe("Sent");
        store.unsubscribe("Sent");
        List<String> subscribed = store.listSubscribed("", "*");
        assertFalse(subscribed.contains("Sent"));
    }

    @Test
    public void testGetMailboxAttributes() throws IOException {
        store.open("testuser");
        Set<MailboxAttribute> attrs = store.getMailboxAttributes("INBOX");
        assertNotNull(attrs);
        assertFalse(attrs.contains(MailboxAttribute.NONEXISTENT));
    }

    @Test
    public void testGetMailboxAttributesNonExistent() throws IOException {
        store.open("testuser");
        Set<MailboxAttribute> attrs = store.getMailboxAttributes("NoSuchMailbox");
        assertTrue(attrs.contains(MailboxAttribute.NONEXISTENT));
    }

    @Test
    public void testOpenMailbox() throws IOException {
        store.open("testuser");
        Mailbox mbox = store.openMailbox("INBOX", true);
        assertNotNull(mbox);
        assertEquals("INBOX", mbox.getName());
        assertTrue(mbox.isReadOnly());
        mbox.close(false);
    }

    @Test(expected = IOException.class)
    public void testOpenNonExistentMailbox() throws IOException {
        store.open("testuser");
        store.openMailbox("NoSuchMailbox", true);
    }

    @Test
    public void testCloseAndReopen() throws IOException {
        store.open("testuser");
        store.createMailbox("Drafts");
        store.close();

        store.open("testuser");
        List<String> mailboxes = store.listMailboxes("", "*");
        assertTrue(mailboxes.contains("Drafts"));
    }

    @Test
    public void testListMailboxesWildcardPercent() throws IOException {
        store.open("testuser");
        store.createMailbox("Sent");
        List<String> topLevel = store.listMailboxes("", "%");
        assertTrue(topLevel.contains("INBOX"));
        assertTrue(topLevel.contains("Sent"));
    }

    @Test
    public void testInboxCaseInsensitive() throws IOException {
        store.open("testuser");
        Mailbox m1 = store.openMailbox("inbox", true);
        assertNotNull(m1);
        assertEquals("INBOX", m1.getName());
        m1.close(false);
    }

    @Test
    public void testNestedAndEncodedMailboxNamesAreListedDecoded() throws IOException {
        store.open("alice");
        store.createMailbox("work/2024");
        store.createMailbox("a:b");
        store.createMailbox("x:y/z");
        List<String> names = store.listMailboxes("", "*");
        assertTrue(names.toString(), names.contains("work/2024"));
        assertTrue(names.toString(), names.contains("a:b"));
        assertTrue(names.toString(), names.contains("x:y/z"));
        assertTrue(Files.isRegularFile(tempDir.resolve("alice/work/2024.mbox")));
        assertEquals("a=3Ab", MailboxNameCodec.encode("a:b"));
        assertTrue(Files.isRegularFile(tempDir.resolve("alice/a=3Ab.mbox")));
    }

    @Test
    public void testHiddenDirectoriesAndForeignFilesAreNotListed() throws IOException {
        store.open("alice");
        Files.createDirectories(tempDir.resolve("alice/.trash"));
        Files.createFile(tempDir.resolve("alice/.trash/junk.mbox"));
        Files.createFile(tempDir.resolve("alice/notes.txt"));
        List<String> names = store.listMailboxes("", "*");
        assertEquals(java.util.Arrays.asList("INBOX"), names);
    }

    @Test
    public void testChildDetectionUsesEncodedDirectoryName() throws IOException {
        store.open("alice");
        store.createMailbox("a:b");
        store.createMailbox("a:b/c");
        assertTrue(store.getMailboxAttributes("a:b").contains(MailboxAttribute.HASCHILDREN));
    }

    @Test
    public void testChildDetectionWithPlainName() throws IOException {
        store.open("alice");
        store.createMailbox("work");
        assertTrue(store.getMailboxAttributes("work").contains(MailboxAttribute.HASNOCHILDREN));
        store.createMailbox("work/2024");
        assertTrue(store.getMailboxAttributes("work").contains(MailboxAttribute.HASCHILDREN));
    }

    @Test
    public void testListingDoesNotDescendThroughDirectoryLinks() throws IOException {
        store.open("alice");
        Path outside = tempDir.resolveSibling("outside");
        Files.createDirectories(outside);
        Files.write(outside.resolve("stolen.mbox"), new byte[4096]);
        Files.createSymbolicLink(tempDir.resolve("alice/shared"), outside);
        assertEquals(java.util.Arrays.asList("INBOX"), store.listMailboxes("", "*"));
    }

    // Rename moves inferior mailboxes and companion files (RFC 3501 6.3.5)

    private Path user(String relative) {
        return tempDir.resolve("alice").resolve(relative);
    }

    private boolean present(String relative) {
        return Files.exists(user(relative), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    @Test
    public void testRenameMovesTheInferiorsDirectoryToo() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("a/b");
        store.createMailbox("a/b/c");
        Files.write(user("a/b.mbox"), "From x\n\nbody\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        store.renameMailbox("a", "z");
        assertEquals(java.util.Arrays.asList("INBOX", "z", "z/b", "z/b/c"),
                store.listMailboxes("", "*"));
        assertFalse(present("a.mbox"));
        assertFalse(present("a"));
        assertTrue(Files.size(user("z/b.mbox")) > 0);
    }

    @Test
    public void testRenameMovesSearchIndexesWithTheirMailboxes() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("a/b");
        Files.write(user("a.mbox.gidx"), new byte[] { 1 });
        Files.write(user("a/b.mbox.gidx"), new byte[] { 2 });
        store.renameMailbox("a", "z");
        assertTrue(present("z.mbox.gidx"));
        assertTrue(present("z/b.mbox.gidx"));
        assertFalse("no orphaned index is left behind", present("a.mbox.gidx"));
    }

    @Test
    public void testRenameLeavesMailboxesThatOnlyShareAPrefixAlone() throws IOException {
        store.open("alice");
        store.createMailbox("work");
        store.createMailbox("workshop");
        store.createMailbox("work/x");
        store.renameMailbox("work", "job");
        assertTrue(present("workshop.mbox"));
        assertTrue(present("job/x.mbox"));
        assertFalse(present("work/x.mbox"));
    }

    @Test
    public void testRenameInboxKeepsInboxAndItsInferiors() throws IOException {
        store.open("alice");
        Files.write(user("INBOX.mbox"), "From x\n\nold mail\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Files.write(user("INBOX.mbox.gidx"), new byte[] { 9 });
        store.createMailbox("INBOX/sub");
        store.renameMailbox("INBOX", "archive");
        assertTrue(Files.size(user("archive.mbox")) > 0);
        assertEquals("INBOX is recreated empty", 0, Files.size(user("INBOX.mbox")));
        assertTrue("the index goes with the messages", present("archive.mbox.gidx"));
        assertFalse(present("INBOX.mbox.gidx"));
        assertTrue("INBOX's own inferiors are not renamed (RFC 3501)", present("INBOX/sub.mbox"));
        List<String> subs = store.listSubscribed("", "*");
        assertTrue(subs.toString(), subs.contains("INBOX"));
        assertTrue(subs.toString(), subs.contains("archive"));
    }

    @Test
    public void testRenameToAnInferiorOfItselfWithoutInferiors() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.renameMailbox("a", "a/b");
        assertTrue(present("a/b.mbox"));
        assertFalse(present("a.mbox"));
    }

    @Test
    public void testRenameToAnInferiorOfItselfWithInferiorsIsRefused() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("a/c");
        try {
            store.renameMailbox("a", "a/b");
            fail("expected an IOException");
        } catch (IOException expected) {
            // the hierarchy cannot move into itself
        }
        assertTrue(present("a.mbox"));
        assertTrue(present("a/c.mbox"));
        assertFalse(present("a/b.mbox"));
    }

    @Test
    public void testRenameRefusedWhenBothHierarchiesAlreadyExist() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("a/x");
        store.createMailbox("z/y");
        try {
            store.renameMailbox("a", "z");
            fail("expected an IOException");
        } catch (IOException expected) {
            // the two hierarchies would have to be merged
        }
        assertTrue(present("a.mbox"));
        assertTrue(present("a/x.mbox"));
        assertFalse(present("z.mbox"));
        assertTrue(present("z/y.mbox"));
    }

    @Test
    public void testRenameOntoAnExistingHierarchyPlaceholder() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("z/y");
        store.renameMailbox("a", "z");
        assertTrue(present("z.mbox"));
        assertTrue("existing inferiors of z stay", present("z/y.mbox"));
    }

    @Test
    public void testRenameMovesSubscriptionsOfInferiors() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("a/b");
        store.subscribe("a");
        store.subscribe("a/b");
        store.renameMailbox("a", "z");
        List<String> subs = store.listSubscribed("", "*");
        assertTrue(subs.toString(), subs.contains("z"));
        assertTrue(subs.toString(), subs.contains("z/b"));
        assertFalse(subs.toString(), subs.contains("a"));
        assertFalse(subs.toString(), subs.contains("a/b"));
    }

    @Test
    public void testFailedRenameIsRolledBack() throws IOException {
        store.open("alice");
        store.createMailbox("a");
        store.createMailbox("a/b");
        mem.failMovesTo(user("z.mbox"));
        try {
            store.renameMailbox("a", "z");
            fail("expected an IOException");
        } catch (IOException expected) {
            // the file move fails after the directory has moved
        }
        assertTrue(present("a.mbox"));
        assertTrue("the inferiors directory was moved back", present("a/b.mbox"));
        assertFalse(present("z"));
        assertFalse(present("z.mbox"));
    }
}
