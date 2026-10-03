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
package org.bluezoo.gumdrop.mailbox.index;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EnumSet;

import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Branch tests for the small index value classes: {@link MessageIndexEntry}
 * flag tests and corrupt-serialisation rejection, {@link MailboxIndexKey}
 * identity, and the parts of {@link IndexedMessageContext} that expose the
 * threading headers, the e-mail id and the search capability matrix.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IndexValueBranchesTest {

    private static MessageIndexEntry entry(EnumSet<Flag> flags, String emailId) {
        return new MessageIndexEntry(7L, 3, 100L, 1000L, 2000L, flags, "loc",
            "from@x", "to@x", "cc@x", "bcc@x", "subject", "<id@x>", "<r1@x> <r2@x>",
            "<irt@x>", "kw", emailId);
    }

    private static byte[] serialise(MessageIndexEntry e) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        e.writeTo(out);
        out.flush();
        return bytes.toByteArray();
    }

    private static void putInt(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data).putInt(offset, value);
    }

    private static void expectCorrupt(byte[] data, String messagePart) {
        try {
            MessageIndexEntry.readFrom(new DataInputStream(new ByteArrayInputStream(data)));
            fail("corrupt entry accepted: " + messagePart);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    @Test
    public void hasFlagReportsEachFlagIndependently() {
        Flag[] all = Flag.values();
        for (int i = 0; i < all.length; i++) {
            MessageIndexEntry only = entry(EnumSet.of(all[i]), "");
            for (int j = 0; j < all.length; j++) {
                assertEquals(all[i] + "/" + all[j], i == j, only.hasFlag(all[j]));
            }
        }
        assertFalse(entry(EnumSet.noneOf(Flag.class), "").hasFlag(Flag.SEEN));
    }

    @Test
    public void toStringMentionsKeyFields() {
        String text = entry(EnumSet.of(Flag.SEEN), "").toString();
        assertTrue(text.contains("uid=7"));
        assertTrue(text.contains("from=from@x"));
        assertTrue(text.contains("subject=subject"));
    }

    @Test
    public void serialisationKeepsThreadingHeadersAndEmailId() throws IOException {
        MessageIndexEntry original = entry(EnumSet.of(Flag.FLAGGED), "abc123");
        byte[] data = serialise(original);
        assertEquals(original.getSerializedSize(), data.length);
        MessageIndexEntry copy = MessageIndexEntry.readFrom(
            new DataInputStream(new ByteArrayInputStream(data)));
        assertEquals("<r1@x> <r2@x>", copy.getReferences());
        assertEquals("<irt@x>", copy.getInReplyTo());
        assertEquals("abc123", copy.getEmailId());
        assertEquals("kw", copy.getKeywords());
    }

    @Test
    public void corruptDescriptorCountIsRejected() throws IOException {
        byte[] data = serialise(entry(EnumSet.noneOf(Flag.class), ""));
        putInt(data, 40, 3);
        expectCorrupt(data, "descriptor count");
    }

    @Test
    public void corruptVariableDataSizeIsRejected() throws IOException {
        byte[] negative = serialise(entry(EnumSet.noneOf(Flag.class), ""));
        putInt(negative, 44, -1);
        expectCorrupt(negative, "variable data size");
        byte[] huge = serialise(entry(EnumSet.noneOf(Flag.class), ""));
        putInt(huge, 44, 11 * 1024 * 1024);
        expectCorrupt(huge, "variable data size");
    }

    @Test
    public void corruptDescriptorBoundsAreRejected() throws IOException {
        byte[] badOffset = serialise(entry(EnumSet.noneOf(Flag.class), ""));
        putInt(badOffset, 48, -4);
        expectCorrupt(badOffset, "Invalid descriptor");
        byte[] badLength = serialise(entry(EnumSet.noneOf(Flag.class), ""));
        putInt(badLength, 52, -1);
        expectCorrupt(badLength, "Invalid descriptor");
        byte[] overrun = serialise(entry(EnumSet.noneOf(Flag.class), ""));
        putInt(overrun, 52, 100000);
        expectCorrupt(overrun, "Invalid descriptor");
    }

    @Test
    public void mailboxIndexKeyIdentity() {
        Path root = MemoryFileSystem.create().getPath("/a");
        MailboxIndexKey one = new MailboxIndexKey(root.resolve("x.gidx"));
        MailboxIndexKey same = new MailboxIndexKey(root.resolve("sub").resolve("..").resolve("x.gidx"));
        MailboxIndexKey other = new MailboxIndexKey(root.resolve("y.gidx"));
        assertTrue(one.equals(one));
        assertTrue(one.equals(same));
        assertEquals(one.hashCode(), same.hashCode());
        assertFalse(one.equals(other));
        assertFalse(one.equals("x.gidx"));
        assertFalse(one.equals(null));
        assertTrue(one.toString().endsWith("x.gidx"));
        try {
            new MailboxIndexKey(null);
            fail("null path accepted");
        } catch (NullPointerException expected) {
            assertEquals("indexPath", expected.getMessage());
        }
    }

    @Test
    public void contextExposesThreadingHeadersAndEmailId() throws IOException {
        IndexedMessageContext withId = new IndexedMessageContext(entry(EnumSet.noneOf(Flag.class), "id42"));
        assertEquals("id42", withId.getEmailId());
        assertEquals("<r1@x> <r2@x>", withId.getHeader("References"));
        assertEquals("<irt@x>", withId.getHeader("In-Reply-To"));
        assertEquals("bcc@x", withId.getHeader("Bcc"));
        assertEquals("from@x", withId.getHeader("Sender"));
        IndexedMessageContext withoutId = new IndexedMessageContext(entry(EnumSet.noneOf(Flag.class), ""));
        assertNull(withoutId.getEmailId());
        assertSame(withoutId.getEntry(), withoutId.getEntry());
    }

    @Test
    public void headersTextListsOnlyNonEmptyIndexedHeaders() throws IOException {
        IndexedMessageContext full = new IndexedMessageContext(entry(EnumSet.noneOf(Flag.class), ""));
        String text = full.getHeadersText().toString();
        assertTrue(text.contains("From: from@x\r\n"));
        assertTrue(text.contains("Cc: cc@x\r\n"));
        assertTrue(text.contains("Message-ID: <id@x>\r\n"));
        assertTrue(text.contains("References: <r1@x> <r2@x>\r\n"));
        assertTrue(text.contains("In-Reply-To: <irt@x>\r\n"));
        MessageIndexEntry sparse = new MessageIndexEntry(1L, 1, 1L, 0L, 0L,
            EnumSet.noneOf(Flag.class), "l", "", "", "", "", "only subject", "", "");
        String sparseText = new IndexedMessageContext(sparse).getHeadersText().toString();
        assertEquals("Subject: only subject\r\n", sparseText);
        assertEquals("", full.getBodyText().toString());
    }

    @Test
    public void searchCapabilityMatrix() {
        IndexedMessageContext ctx = new IndexedMessageContext(entry(EnumSet.noneOf(Flag.class), ""));
        IndexedMessageContext.SearchType[] answerable = {
            IndexedMessageContext.SearchType.FLAG, IndexedMessageContext.SearchType.SIZE,
            IndexedMessageContext.SearchType.DATE, IndexedMessageContext.SearchType.UID,
            IndexedMessageContext.SearchType.SEQUENCE, IndexedMessageContext.SearchType.FROM,
            IndexedMessageContext.SearchType.TO, IndexedMessageContext.SearchType.CC,
            IndexedMessageContext.SearchType.BCC, IndexedMessageContext.SearchType.SUBJECT};
        for (int i = 0; i < answerable.length; i++) {
            assertTrue(answerable[i].name(), ctx.canEvaluate(answerable[i]));
        }
        assertFalse(ctx.canEvaluate(IndexedMessageContext.SearchType.HEADER));
        assertFalse(ctx.canEvaluate(IndexedMessageContext.SearchType.BODY));
        assertFalse(ctx.canEvaluate(IndexedMessageContext.SearchType.TEXT));
        assertEquals(13, IndexedMessageContext.SearchType.values().length);
        assertSame(IndexedMessageContext.SearchType.BODY,
            IndexedMessageContext.SearchType.valueOf("BODY"));
    }
}
