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
import org.bluezoo.gumdrop.mailbox.MailboxStore;
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

    private Path tempDir;
    private MboxMailboxStore store;

    @Before
    public void setUp() throws IOException {
        tempDir = MemoryFileSystem.create().getPath("/mbox");
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
    public void testGetQuotaRoot() throws IOException {
        store.open("testuser");
        assertEquals("testuser", store.getQuotaRoot("INBOX"));
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
    public void testQuotaCountsEveryMailboxFileIncludingHiddenDirectories() throws IOException {
        store.open("alice");
        Files.write(tempDir.resolve("alice/INBOX.mbox"), new byte[2048]);
        Files.createDirectories(tempDir.resolve("alice/.hidden"));
        Files.write(tempDir.resolve("alice/.hidden/x.mbox"), new byte[1024]);
        Files.write(tempDir.resolve("alice/.subscriptions"), new byte[9999]);
        Files.write(tempDir.resolve("alice/notes.txt"), new byte[9999]);
        MailboxStore.Quota quota = store.getQuota("alice");
        assertEquals(3, quota.getStorageUsed());
        assertEquals(2, quota.getMessageCount());
    }

    @Test
    public void testQuotaForOtherRootIsNull() throws IOException {
        store.open("alice");
        assertNull(store.getQuota("bob"));
    }
}
