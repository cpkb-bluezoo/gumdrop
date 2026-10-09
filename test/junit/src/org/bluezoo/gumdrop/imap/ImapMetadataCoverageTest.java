/*
 * ImapMetadataCoverageTest.java
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

package org.bluezoo.gumdrop.imap;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.testsupport.memfs.MemoryFileSystem;

import static org.junit.Assert.*;

/**
 * Covers RFC 5464 metadata: the argument parser, entry name validation,
 * the file-backed store and {@link ImapMetadataSupport} driven through a
 * stub host with a synchronous storage executor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapMetadataCoverageTest {

    private Path userDir;
    private ImapMetadataFileStore store;

    @Before
    public void setUp() throws Exception {
        MemoryFileSystem mem = MemoryFileSystem.create();
        userDir = mem.getPath("/home/u");
        Files.createDirectories(userDir);
        store = new ImapMetadataFileStore(userDir);
    }

    // ---- parser ----

    private static ImapMetadataGetRequest get(String args) throws Exception {
        return new ImapMetadataParser(args).parseGet();
    }

    private static void badGet(String args) {
        try {
            new ImapMetadataParser(args).parseGet();
            fail("expected ParseException for: " + args);
        } catch (ParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void badSet(String args) {
        try {
            new ImapMetadataParser(args).parseSet();
            fail("expected ParseException for: " + args);
        } catch (ParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void testParseGetSingleEntry() throws Exception {
        ImapMetadataGetRequest r = get("INBOX /private/comment");
        assertEquals("INBOX", r.getMailboxName());
        assertEquals(-1, r.getMaxSize());
        assertEquals(0, r.getDepth());
        assertEquals(Arrays.asList("/private/comment"), r.getEntryNames());
        assertFalse(r.isServerMetadata());
    }

    @Test
    public void testParseGetLeadingOptions() throws Exception {
        ImapMetadataGetRequest r = get(
                "(MAXSIZE 100 DEPTH infinity) INBOX (/shared/a /shared/b)");
        assertEquals(100, r.getMaxSize());
        assertEquals(Integer.MAX_VALUE, r.getDepth());
        assertEquals(Arrays.asList("/shared/a", "/shared/b"),
                r.getEntryNames());
    }

    @Test
    public void testParseGetTrailingOptionsGroup() throws Exception {
        ImapMetadataGetRequest r = get("INBOX (DEPTH 1) /private/x");
        assertEquals(1, r.getDepth());
        assertEquals(Arrays.asList("/private/x"), r.getEntryNames());
        r = get("INBOX (MAXSIZE 5 DEPTH 0) (/private/x)");
        assertEquals(5, r.getMaxSize());
        assertEquals(0, r.getDepth());
        assertEquals(Arrays.asList("/private/x"), r.getEntryNames());
    }

    @Test
    public void testParseGetServerMetadata() throws Exception {
        ImapMetadataGetRequest r = get("\"\" /shared/comment");
        assertTrue(r.isServerMetadata());
        r = get("(/shared/comment /shared/admin)");
        assertTrue(r.isServerMetadata());
        assertEquals(2, r.getEntryNames().size());
        r = get("\"quoted \\\"mbox\\\"\" shared/comment");
        assertEquals("quoted \"mbox\"", r.getMailboxName());
        assertEquals(Arrays.asList("/shared/comment"), r.getEntryNames());
    }

    @Test
    public void testParseGetRejectsMalformed() {
        badGet("");
        badGet("INBOX");
        badGet("(MAXSIZE) INBOX /shared/a");
        badGet("(DEPTH 7) INBOX /shared/a");
        badGet("INBOX /shared/a trailing");
        badGet("INBOX (/shared/a");
        badGet("\"unterminated /shared/a");
        badGet("INBOX )");
        badGet("INBOX ()");
        badGet("INBOX (  )");
        badGet("(DEPTH 1) INBOX ()");
        badGet("INBOX (DEPTH 1) ()");
        badGet("()");
    }

    @Test
    public void testParseSet() throws Exception {
        ImapMetadataSetRequest r = new ImapMetadataParser(
                "INBOX (/private/a \"quoted \\\\ val\" /private/b NIL"
                + " /private/c bare)").parseSet();
        assertEquals("INBOX", r.getMailboxName());
        List<ImapMetadataSetRequest.EntryValue> e = r.getEntries();
        assertEquals(3, e.size());
        assertEquals("quoted \\ val", e.get(0).value);
        assertNull(e.get(1).value);
        assertEquals("bare", e.get(2).value);
        r = new ImapMetadataParser("\"\" (/shared/a \"v\")").parseSet();
        assertEquals("", r.getMailboxName());
    }

    @Test
    public void testParseSetRejectsMalformed() {
        badSet("");
        badSet("INBOX");
        badSet("INBOX ()");
        badSet("\"\" (  )");
        badSet("INBOX /private/a \"v\"");
        badSet("INBOX (/private/a)");
        badSet("INBOX (/private/a {3})");
        badSet("INBOX (/private/a \"v\") junk");
        badSet("INBOX (/private/a \"unterminated)");
        badSet("INBOX (/private/a \"v\"");
    }

    // ---- entry names and scope ----

    @Test
    public void testEntryNames() {
        assertTrue(ImapMetadataEntryNames.isValidEntryName("/private/comment"));
        assertTrue(ImapMetadataEntryNames.isValidEntryName(
                "/shared/vendor/acme/thing"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName(
                "/shared/vendor/acme"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName(null));
        assertFalse(ImapMetadataEntryNames.isValidEntryName(""));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/*"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/a%b"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private//a"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/a/"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("private/a"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/other/a"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/é"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private/a b\u0001"));
        assertFalse(ImapMetadataEntryNames.isValidEntryName("/private"));
        assertTrue(ImapMetadataEntryNames.isReadOnly("/SHARED/ADMIN"));
        assertFalse(ImapMetadataEntryNames.isReadOnly("/shared/comment"));
        assertNull(ImapMetadataEntryNames.canonicalEntryName(null));
        assertEquals("/private/a",
                ImapMetadataEntryNames.canonicalEntryName("/Private/A"));
        assertNull(ImapMetadataScope.fromEntryName(null));
        assertNull(ImapMetadataScope.fromEntryName("/x/y"));
        assertSame(ImapMetadataScope.SHARED,
                ImapMetadataScope.fromEntryName("/Shared"));
        assertSame(ImapMetadataScope.PRIVATE,
                ImapMetadataScope.fromEntryName("/private/a"));
        assertEquals("/shared", ImapMetadataScope.SHARED.getPrefix());
    }

    // ---- file store ----

    @Test
    public void testFileStoreConstructorRejectsNull() {
        try {
            new ImapMetadataFileStore(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("userDirectory", expected.getMessage());
        }
    }

    @Test
    public void testFileStoreSetGetAndList() throws Exception {
        store.set("INBOX", "/private/comment", "hello");
        store.set("INBOX", "/shared/comment", "shared");
        store.set("INBOX", "/private/vendor/a/b", "deep");
        store.set("INBOX", "/private/vendor/a", "mid");
        store.set("", "/shared/comment", "server");
        store.set("a/b:c%d\\e", "/private/x/y", "weird");
        assertEquals("hello", store.get("INBOX", "/Private/Comment"));
        assertNull(store.get("INBOX", "/private/none"));
        assertNull(store.get("INBOX", "/bogus/x"));
        assertEquals("server", store.get("", "/shared/comment"));
        assertEquals("weird", store.get("a/b:c%d\\e", "/private/x/y"));
        assertEquals("a%2fb%3ac%25d%5ce",
                ImapMetadataFileStore.encodeMailboxName("a/b:c%d\\e"));

        List<String> names = new ArrayList<String>();
        names.add("/private/comment");
        names.add("/private/none");
        Map<String, String> got = store.getEntries("INBOX", names);
        assertEquals(1, got.size());

        Map<String, String> d0 = store.listWithDepth("INBOX",
                "/private/vendor/a", 0);
        assertEquals(1, d0.size());
        Map<String, String> d1 = store.listWithDepth("INBOX",
                "/private/vendor", 1);
        assertEquals(1, d1.size());
        d1 = store.listWithDepth("INBOX", "/private/vendor/a", 1);
        assertEquals(2, d1.size());
        Map<String, String> dInf = store.listWithDepth("INBOX",
                "/private/vendor", Integer.MAX_VALUE);
        assertEquals(2, dInf.size());
        assertEquals(0, store.listWithDepth("INBOX", "/nothing", 1).size());

        Set<String> all = store.allEntryNames("INBOX");
        assertTrue(all.contains("/shared/comment"));
        assertTrue(all.contains("/private/comment"));
        assertEquals(4, all.size());

        store.set("INBOX", "/private/comment", null);
        assertNull(store.get("INBOX", "/private/comment"));
        store.set("INBOX", "/private/comment", null);
    }

    @Test
    public void testFileStoreSetValidation() throws Exception {
        try {
            store.set("INBOX", "/shared/admin", "x");
            fail("read-only");
        } catch (IOException expected) {
            assertEquals("read-only", expected.getMessage());
        }
        try {
            store.set("INBOX", "/bogus/x", "x");
            fail("invalid");
        } catch (IOException expected) {
            assertEquals("invalid entry", expected.getMessage());
        }
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= ImapMetadataFileStore.MAX_VALUE_BYTES; i++) {
            big.append('x');
        }
        try {
            store.set("INBOX", "/private/big", big.toString());
            fail("too large");
        } catch (IOException expected) {
            assertEquals("too large", expected.getMessage());
        }
        for (int i = 0; i < ImapMetadataFileStore.MAX_ENTRIES_PER_TARGET; i++) {
            store.set("INBOX", "/private/e" + i, "v");
        }
        store.set("INBOX", "/private/e0", "replaced");
        try {
            store.set("INBOX", "/private/overflow", "v");
            fail("too many");
        } catch (IOException expected) {
            assertEquals("too many", expected.getMessage());
        }
    }

    @Test
    public void testFileStoreDeleteAndRename() throws Exception {
        store.deleteMailbox("never");
        store.renameMailbox("never", "other");
        store.set("old/box", "/private/a", "1");
        store.set("old/box", "/shared/a", "2");
        store.renameMailbox("old/box", "new/box");
        assertNull(store.get("old/box", "/private/a"));
        assertEquals("1", store.get("new/box", "/private/a"));
        assertEquals("2", store.get("new/box", "/shared/a"));
        store.deleteMailbox("new/box");
        assertNull(store.get("new/box", "/private/a"));
        assertTrue(store.allEntryNames("new/box").isEmpty());
    }

    // ---- support ----

    private static final class StubHost implements ImapMetadataSupport.Host {
        final ImapListener listener = new ImapListener();
        final List<String> out = new ArrayList<String>();
        ImapMetadataFileStore metadataStore;
        boolean mailboxExists = true;
        boolean failStorage;
        boolean throwOnSend;

        @Override
        public ImapListener getServer() {
            return listener;
        }

        @Override
        public MailboxStore getStore() {
            return null;
        }

        @Override
        public ImapMetadataFileStore getMetadataStore() {
            return metadataStore;
        }

        @Override
        public void sendUntagged(String line) throws IOException {
            record("U " + line);
        }

        @Override
        public void sendTaggedOk(String tag, String message)
                throws IOException {
            record(tag + " OK " + message);
        }

        @Override
        public void sendTaggedNo(String tag, String message)
                throws IOException {
            record(tag + " NO " + message);
        }

        @Override
        public void sendTaggedBad(String tag, String message)
                throws IOException {
            record(tag + " BAD " + message);
        }

        private void record(String line) throws IOException {
            if (throwOnSend) {
                throw new IOException("closed");
            }
            out.add(line);
        }

        @Override
        public String quoteMailboxName(String mailboxName) {
            return "\"" + mailboxName + "\"";
        }

        @Override
        public String quoteMetadataValue(String value) {
            return "\"" + value + "\"";
        }

        @Override
        public boolean mailboxExists(String mailboxName) {
            return mailboxExists;
        }

        @Override
        public <T> void submitStorage(Callable<T> work,
                StorageExecutor.Callback<T> callback) {
            if (failStorage) {
                callback.failed(new IOException("boom"));
                return;
            }
            T result;
            try {
                result = work.call();
            } catch (Exception e) {
                callback.failed(e);
                return;
            }
            callback.completed(result);
        }
    }

    private StubHost host() {
        StubHost h = new StubHost();
        h.metadataStore = store;
        return h;
    }

    private static String last(StubHost h) {
        return h.out.get(h.out.size() - 1);
    }

    @Test
    public void testSupportDisabledAndSyntaxErrors() throws Exception {
        StubHost h = host();
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        h.listener.enableMETADATA(false);
        s.handleGetMetadata("a1", "INBOX /private/x");
        assertTrue(last(h), last(h).startsWith("a1 BAD"));
        s.handleSetMetadata("a2", "INBOX (/private/x \"v\")");
        assertTrue(last(h), last(h).startsWith("a2 BAD"));
        h.listener.enableMETADATA(true);
        s.handleGetMetadata("a3", "INBOX");
        assertTrue(last(h), last(h).startsWith("a3 BAD"));
        s.handleSetMetadata("a4", "INBOX");
        assertTrue(last(h), last(h).startsWith("a4 BAD"));
        s.handleGetMetadata("a5", "INBOX /bogus/x");
        assertTrue(last(h), last(h).startsWith("a5 BAD"));
        s.handleSetMetadata("a6", "INBOX (/bogus/x \"v\")");
        assertTrue(last(h), last(h).startsWith("a6 BAD"));
        s.handleSetMetadata("a7", "INBOX (/shared/admin \"v\")");
        assertTrue(last(h), last(h).startsWith("a7 NO"));
    }

    @Test
    public void testSupportMissingMailbox() throws Exception {
        StubHost h = host();
        h.mailboxExists = false;
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        s.handleGetMetadata("a1", "INBOX /private/x");
        assertTrue(last(h), last(h).startsWith("a1 NO"));
        s.handleSetMetadata("a2", "INBOX (/private/x \"v\")");
        assertTrue(last(h), last(h).startsWith("a2 NO"));
        s.handleGetMetadata("a3", "\"\" /shared/comment");
        assertTrue(h.out.get(h.out.size() - 2).startsWith("U METADATA"));
        assertTrue(last(h), last(h).startsWith("a3 OK"));
        s.handleSetMetadata("a4", "\"\" (/shared/comment \"v\")");
        assertTrue(last(h), last(h).startsWith("a4 OK"));
    }

    @Test
    public void testSupportSetThenGet() throws Exception {
        StubHost h = host();
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        s.handleSetMetadata("a1",
                "INBOX (/private/a \"one\" /shared/admin2 \"two\")");
        assertTrue(last(h), last(h).startsWith("a1 OK"));
        h.out.clear();
        s.handleGetMetadata("a2", "INBOX (/private/a /private/missing)");
        assertEquals(2, h.out.size());
        assertEquals("U METADATA \"INBOX\" (/private/a \"one\")", h.out.get(0));
        assertTrue(h.out.get(1), h.out.get(1).startsWith("a2 OK"));
        h.out.clear();
        s.handleGetMetadata("a3", "(DEPTH infinity) INBOX /private/a");
        assertTrue(h.out.get(0), h.out.get(0).contains("/private/a"));
        h.out.clear();
        s.handleGetMetadata("a4", "(MAXSIZE 1) INBOX /private/a");
        assertEquals("U METADATA \"INBOX\" ()", h.out.get(0));
        assertTrue(h.out.get(1), h.out.get(1).contains("LONGENTRIES 3"));
        h.out.clear();
        s.handleGetMetadata("a5", "\"\" /shared/admin");
        assertEquals("U METADATA \"\" ()", h.out.get(0));
        Path serverDir = userDir.resolve(".imap-metadata").resolve("server");
        Files.createDirectories(serverDir);
        Files.write(serverDir.resolve("shared.properties"),
                "/shared/admin=ignored\n/shared/other=\n".getBytes(
                        StandardCharsets.UTF_8));
        h.out.clear();
        s.handleGetMetadata("a6", "\"\" (/shared/admin /shared/other)");
        assertTrue(h.out.get(0), h.out.get(0).contains("mailto:postmaster"));
        assertTrue(h.out.get(0), h.out.get(0).contains("/shared/other \"\""));
    }

    @Test
    public void testSupportStoreErrors() throws Exception {
        StubHost h = host();
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= ImapMetadataFileStore.MAX_VALUE_BYTES; i++) {
            big.append('x');
        }
        s.handleSetMetadata("a1", "INBOX (/private/a \"" + big + "\")");
        assertTrue(last(h), last(h).contains("MAXSIZE"));
        for (int i = 0; i < ImapMetadataFileStore.MAX_ENTRIES_PER_TARGET; i++) {
            store.set("INBOX", "/private/e" + i, "v");
        }
        s.handleSetMetadata("a2", "INBOX (/private/overflow \"v\")");
        assertTrue(last(h), last(h).contains("TOOMANY"));
        h.failStorage = true;
        s.handleSetMetadata("a3", "INBOX (/private/a \"v\")");
        assertTrue(last(h), last(h).startsWith("a3 NO"));
        s.handleGetMetadata("a4", "INBOX /private/a");
        assertTrue(last(h), last(h).startsWith("a4 NO"));
    }

    @Test
    public void testSupportNoStoreConfigured() throws Exception {
        StubHost h = host();
        h.metadataStore = null;
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        s.handleGetMetadata("a1", "INBOX /private/a");
        assertTrue(last(h), last(h).startsWith("a1 NO"));
        s.handleSetMetadata("a2", "INBOX (/private/a \"v\")");
        assertTrue(last(h), last(h).startsWith("a2 NO"));
        s.onMailboxDeleted("INBOX");
        s.onMailboxRenamed("INBOX", "Other");
    }

    @Test
    public void testSupportSetIoErrorFromStore() throws Exception {
        StubHost h = host();
        // an entry made read-only by case is rejected earlier; force the
        // generic IOException branch with a store whose root is a file
        Path blocker = userDir.resolve(".imap-metadata");
        Files.write(blocker, new byte[] {1});
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        s.handleSetMetadata("a1", "INBOX (/private/a \"v\")");
        assertTrue(last(h), last(h).startsWith("a1 NO"));
    }

    @Test
    public void testSupportSendFailuresAreSwallowed() throws Exception {
        StubHost h = host();
        h.throwOnSend = true;
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        s.handleSetMetadata("a1", "INBOX (/private/a \"v\")");
        s.handleGetMetadata("a2", "INBOX /private/a");
        h.failStorage = true;
        s.handleSetMetadata("a3", "INBOX (/private/a \"v\")");
        s.handleGetMetadata("a4", "INBOX /private/a");
        assertTrue(h.out.isEmpty());
    }

    @Test
    public void testSupportMailboxHooks() throws Exception {
        StubHost h = host();
        ImapMetadataSupport s = new ImapMetadataSupport(h);
        store.set("box", "/private/a", "1");
        s.onMailboxRenamed("box", "box2");
        assertEquals("1", store.get("box2", "/private/a"));
        s.onMailboxDeleted("box2");
        assertNull(store.get("box2", "/private/a"));
    }
}
