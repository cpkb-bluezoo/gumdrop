package org.bluezoo.gumdrop.mailbox.mbox;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MboxMailboxStore}.
 */
public class MboxMailboxStoreTest {

    private Path tempDir;
    private MboxMailboxStore store;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("mboxstore");
        store = new MboxMailboxStore(tempDir);
    }

    @After
    public void tearDown() throws IOException {
        try {
            store.close();
        } catch (IOException e) { /* ignore */ }
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                });
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
}
