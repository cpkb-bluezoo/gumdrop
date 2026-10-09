/*
 * ImapCommandEdgeBranchTest.java
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exercises argument-validation, disabled-extension and read-only branches
 * of the command handlers in {@link ImapProtocolHandler}, plus the lexer
 * level error handling (invalid encoding, over-long lines, literals) and the
 * unsolicited-notification guard.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapCommandEdgeBranchTest extends ImapSessionHarness {

    @Test(timeout = 30000)
    public void storeArgumentErrors() throws Exception {
        selectedInbox();
        bad("STORE 1");
        bad("STORE x +FLAGS (\\Seen)");
        bad("STORE 1 (UNCHANGEDSINCE abc) +FLAGS (\\Seen)");
        bad("STORE 1 (UNCHANGEDSINCE 5)");
        bad("STORE 1 BOGUS (\\Seen)");
        ok("STORE 1 (IGNORED) +FLAGS (\\Seen)");
        ok("STORE 1 (UNCHANGEDSINCE 0) +FLAGS.SILENT (\\Draft)");
    }

    @Test(timeout = 30000)
    public void readOnlySelectionRefusesModification() throws Exception {
        selectedInbox();
        ok("EXAMINE INBOX");
        no("STORE 1 +FLAGS (\\Seen)");
        no("MOVE 1 INBOX");
        no("EXPUNGE");
        ok("CREATE Archive");
        ok("COPY 1 Archive");
    }

    @Test(timeout = 30000)
    public void copyAndMoveArgumentErrors() throws Exception {
        selectedInbox();
        bad("COPY 1");
        bad("COPY x INBOX");
        bad("COPY 1 \"\"");
        bad("MOVE 1");
        bad("MOVE x INBOX");
        bad("MOVE 1 \"\"");
        bad("UID COPY 1");
        bad("UID MOVE 1");
        no("COPY 1 Missing");
        no("MOVE 1 Missing");
    }

    @Test(timeout = 30000)
    public void moveWhenDisabledIsUnknown() throws Exception {
        listener.enableMOVE(false);
        reconnect();
        selectedInbox();
        bad("MOVE 1 INBOX");
        assertSaw("MOVE");
    }

    @Test(timeout = 30000)
    public void sortAndThreadSyntaxAndCharsetErrors() throws Exception {
        selectedInbox();
        bad("SORT");
        bad("SORT (SUBJECT");
        no("SORT (SUBJECT) X-UNKNOWN-CHARSET ALL");
        bad("THREAD");
        bad("THREAD REFERENCES");
        no("THREAD REFERENCES X-UNKNOWN-CHARSET ALL");
        ok("SORT (SUBJECT) UTF-8 ALL");
        assertSaw("* SORT 1 3 2");
    }

    @Test(timeout = 30000)
    public void sortAndThreadDisabledAreUnknown() throws Exception {
        listener.enableSORT(false);
        reconnect();
        selectedInbox();
        bad("SORT (SUBJECT) UTF-8 ALL");
        bad("THREAD REFERENCES UTF-8 ALL");
    }

    @Test(timeout = 30000)
    public void namespaceDisabledIsUnknownAndListArgumentsValidated()
            throws Exception {
        listener.enableNAMESPACE(false);
        reconnect();
        login();
        bad("NAMESPACE");
        bad("LIST");
        bad("LIST onlyone");
        bad("LSUB");
        bad("LSUB onlyone");
        ok("LIST \"\" \"*\"");
        assertSaw("INBOX");
    }

    @Test(timeout = 30000)
    public void compressValidatedAndNotRepeated() throws Exception {
        listener.enableCOMPRESS(false);
        reconnect();
        login();
        bad("COMPRESS DEFLATE");
        listener.enableCOMPRESS(true);
        reconnect();
        login();
        bad("COMPRESS");
        bad("COMPRESS GZIP");
        ok("COMPRESS DEFLATE");
        final ImapDeflateLayer client = new ImapDeflateLayer();
        endpoint.setSendFilter(new org.bluezoo.gumdrop.testsupport
                .RecordingStubEndpoint.SendFilter() {
            @Override
            public byte[] filter(byte[] outbound) {
                try {
                    return client.inflate(ByteBuffer.wrap(outbound));
                } catch (java.util.zip.DataFormatException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        endpoint.clearResponses();
        byte[] wire = client.compressAndFlush(
                "c9 COMPRESS DEFLATE\r\n".getBytes(StandardCharsets.US_ASCII));
        handler.receive(ByteBuffer.wrap(wire));
        String line = endpoint.awaitLineStartingWith("c9 ");
        assertContains(line, " NO ");
        client.close();
    }

    @Test(timeout = 30000)
    public void invalidUtf8InCommandIsRejected() throws Exception {
        endpoint.clearResponses();
        byte[] head = "t1 NOOP ".getBytes(StandardCharsets.US_ASCII);
        byte[] bad = new byte[] { (byte) 0xc3, (byte) 0x28 };
        byte[] tail = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[head.length + bad.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(bad, 0, all, head.length, bad.length);
        System.arraycopy(tail, 0, all, head.length + bad.length, tail.length);
        sendBytes(all);
        String line = endpoint.awaitLineStartingWith("t1 ");
        assertContains(line, " BAD");
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void nonAsciiRequiresUtf8Accept() throws Exception {
        login();
        bad("SELECT éclair");
        ok("ENABLE UTF8=ACCEPT");
        no("SELECT éclair");
    }

    @Test(timeout = 30000)
    public void overlongLineIsRejectedAndSessionRecovers() throws Exception {
        listener.maxLineLength(64);
        reconnect();
        endpoint.clearResponses();
        StringBuilder sb = new StringBuilder("t1 NOOP ");
        for (int i = 0; i < 200; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        send(sb.toString());
        String line = endpoint.awaitLineStartingWith("t1 ");
        assertContains(line, " BAD");
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void generalLiteralsAreAssembledIntoTheCommand() throws Exception {
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " LOGIN {6}\r\n");
        endpoint.awaitLineStartingWith("+");
        send("editor {6+}\r\neditor\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " OK");
    }

    /**
     * A reply to a malformed command must carry that command's own tag, not
     * the tag of the previous, already completed command.
     */
    @Test(timeout = 30000)
    public void encodingErrorIsTaggedWithTheOffendingCommand() throws Exception {
        login();
        endpoint.clearResponses();
        send("x2 NOOP \u00e9\r\n");
        String line = lineStarting("x2 ");
        assertContains(line, " BAD");
        assertNull(endpoint.findLineStartingWith("t1 BAD"));
    }

    @Test(timeout = 30000)
    public void overlongLineIsTaggedWithTheOffendingCommand() throws Exception {
        listener.maxLineLength(64);
        reconnect();
        ok("NOOP");
        endpoint.clearResponses();
        StringBuilder sb = new StringBuilder("x2 NOOP ");
        for (int i = 0; i < 200; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        send(sb.toString());
        String line = lineStarting("x2 ");
        assertContains(line, " BAD");
        assertNull(endpoint.findLineStartingWith("t1 BAD"));
    }

    @Test(timeout = 30000)
    public void overlongContinuationIsTaggedWithItsCommand() throws Exception {
        listener.maxLineLength(64);
        reconnect();
        ok("NOOP");
        endpoint.clearResponses();
        send("x2 LOGIN {6}\r\n");
        endpoint.awaitLineStartingWith("+");
        StringBuilder sb = new StringBuilder("editor ");
        for (int i = 0; i < 200; i++) {
            sb.append('x');
        }
        sb.append("\r\n");
        send("editor");
        send(sb.toString());
        String line = lineStarting("x2 ");
        assertContains(line, " BAD");
    }

    @Test(timeout = 30000)
    public void oversizedLiteralIsTaggedWithTheOffendingCommand()
            throws Exception {
        listener.maxLiteralSize(4);
        reconnect();
        ok("NOOP");
        endpoint.clearResponses();
        send("x2 LOGIN {6}\r\n");
        String line = lineStarting("x2 ");
        assertContains(line, " NO");
    }

    @Test(timeout = 30000)
    public void oversizedGeneralLiteralIsRefused() throws Exception {
        listener.maxLiteralSize(4);
        reconnect();
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " LOGIN {6}\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO");
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void unsolicitedNotificationsWaitForQuietConnection()
            throws Exception {
        assertTrue(handler.canDeliverUnsolicitedNotify());
        endpoint.setSecure(true);
        tagCounter++;
        String tag = "t" + tagCounter;
        send(tag + " AUTHENTICATE PLAIN\r\n");
        endpoint.awaitLineStartingWith("+");
        assertFalse(handler.canDeliverUnsolicitedNotify());
        send("*\r\n");
        endpoint.awaitLineStartingWith(tag + " ");
        assertTrue(handler.canDeliverUnsolicitedNotify());
        login();
        send("t99 LOGIN {6}\r\n");
        endpoint.awaitLineStartingWith("+");
        assertFalse(handler.canDeliverUnsolicitedNotify());
        send("editor editor\r\n");
        assertTrue(handler.canDeliverUnsolicitedNotify());
        send("t100 APPEND INBOX {10}\r\n");
        assertFalse(handler.canDeliverUnsolicitedNotify());
    }

    @Test(timeout = 30000)
    public void greetingIsSentOnceSecurityIsEstablished() throws Exception {
        endpoint.clearResponses();
        handler.securityEstablished(null);
        assertSaw("* OK");
    }

    /**
     * RFC 9051 section 4.3: inside a quoted string a backslash escapes a
     * quote or another backslash, so the mailbox name is the unescaped text.
     */
    @Test(timeout = 30000)
    public void quotedMailboxNamesAreUnescaped() throws Exception {
        login();
        ok("CREATE \"say \\\"hi\\\"\"");
        ok("CREATE \"back\\\\slash\"");
        ok("LIST \"\" \"*\"");
        assertSaw("\"say \\\"hi\\\"\"");
        assertSaw("\"back\\\\slash\"");
        ok("DELETE \"say \\\"hi\\\"\"");
        ok("DELETE \"back\\\\slash\"");
        ok("LIST \"\" \"*\"");
        assertNotSaw("slash");
        assertNotSaw("say");
    }

    @Test(timeout = 30000)
    public void unterminatedQuotedMailboxNameIsRejected() throws Exception {
        login();
        bad("CREATE \"");
        bad("CREATE \"abc");
        bad("DELETE \"abc");
        ok("CREATE \"fine\"");
    }
}
