/*
 * POP3ClientReplyEdgeTest.java
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


package org.bluezoo.gumdrop.pop3.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the malformed-reply and error-reply branches of
 * {@link Pop3ClientProtocolHandler}: unparseable STAT, LIST and UIDL lines,
 * server error replies that the handler classifies by message text, replies
 * that carry no status marker, and commands issued after the connection has
 * closed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class POP3ClientReplyEdgeTest {

    private Pop3ClientProtocolHandler handler;
    private POP3ClientProtocolHandlerTest.RecordingGreetingHandler greeting;
    private POP3ClientProtocolHandlerTest.StubEndpoint endpoint;

    @Before
    public void setUp() {
        greeting = new POP3ClientProtocolHandlerTest.RecordingGreetingHandler();
        handler = new Pop3ClientProtocolHandler(greeting);
        endpoint = new POP3ClientProtocolHandlerTest.StubEndpoint();
        handler.connected(endpoint);
        receive("+OK POP3 server ready");
        POP3ClientProtocolHandlerTest.RecordingUserHandler user =
                new POP3ClientProtocolHandlerTest.RecordingUserHandler();
        greeting.authState.user("alice", user);
        receive("+OK");
        POP3ClientProtocolHandlerTest.RecordingPassHandler pass =
                new POP3ClientProtocolHandlerTest.RecordingPassHandler();
        user.passwordState.pass("secret", pass);
        receive("+OK Maildrop locked");
        greeting.transactionState = pass.transactionState;
    }

    private void receive(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\r\n");
        }
        handler.receive(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testStatWithUnparseableNumbersReportsZeroes() {
        POP3ClientProtocolHandlerTest.RecordingStatHandler stat =
                new POP3ClientProtocolHandlerTest.RecordingStatHandler();
        greeting.transactionState.stat(stat);
        receive("+OK many bytes");
        assertTrue(stat.statReceived);
        assertEquals(0, stat.messageCount);
        assertEquals(0L, stat.totalSize);
    }

    @Test
    public void testStatWithoutSpaceReportsZeroes() {
        POP3ClientProtocolHandlerTest.RecordingStatHandler stat =
                new POP3ClientProtocolHandlerTest.RecordingStatHandler();
        greeting.transactionState.stat(stat);
        receive("+OK 7");
        assertTrue(stat.statReceived);
        assertEquals(0, stat.messageCount);
    }

    @Test
    public void testStatErrorReply() {
        POP3ClientProtocolHandlerTest.RecordingStatHandler stat =
                new POP3ClientProtocolHandlerTest.RecordingStatHandler();
        greeting.transactionState.stat(stat);
        receive("-ERR mailbox busy");
        assertTrue(stat.errorReceived);
        assertEquals("mailbox busy", stat.errorMessage);
    }

    @Test
    public void testListSingleMalformedReplyIsAnError() {
        POP3ClientProtocolHandlerTest.RecordingListHandler list =
                new POP3ClientProtocolHandlerTest.RecordingListHandler();
        greeting.transactionState.list(1, list);
        receive("+OK abc def");
        assertTrue(list.errorReceived);
        assertFalse(list.singleReceived);
    }

    @Test
    public void testListSingleWithoutSpaceIsAnError() {
        POP3ClientProtocolHandlerTest.RecordingListHandler list =
                new POP3ClientProtocolHandlerTest.RecordingListHandler();
        greeting.transactionState.list(1, list);
        receive("+OK 1");
        assertTrue(list.errorReceived);
    }

    @Test
    public void testListGenericErrorAndMissingMessageErrors() {
        POP3ClientProtocolHandlerTest.RecordingListHandler list =
                new POP3ClientProtocolHandlerTest.RecordingListHandler();
        greeting.transactionState.list(1, list);
        receive("-ERR boom");
        assertTrue(list.errorReceived);
        assertFalse(list.noSuchMessage);

        list = new POP3ClientProtocolHandlerTest.RecordingListHandler();
        greeting.transactionState.list(2, list);
        receive("-ERR message does not exist");
        assertTrue(list.noSuchMessage);
    }

    @Test
    public void testListMultiSkipsMalformedEntries() {
        POP3ClientProtocolHandlerTest.RecordingListHandler list =
                new POP3ClientProtocolHandlerTest.RecordingListHandler();
        greeting.transactionState.list(list);
        receive("+OK", "x y", "nospace", "1 120", ".");
        assertTrue(list.listComplete);
        assertEquals(1, list.entries.size());
        assertEquals(120L, list.entries.get(0).size);
    }

    @Test
    public void testUidlSingleMalformedAndNoSpaceAreErrors() {
        POP3ClientProtocolHandlerTest.RecordingUidlHandler uidl =
                new POP3ClientProtocolHandlerTest.RecordingUidlHandler();
        greeting.transactionState.uidl(1, uidl);
        receive("+OK abc uid");
        assertTrue(uidl.errorReceived);

        uidl = new POP3ClientProtocolHandlerTest.RecordingUidlHandler();
        greeting.transactionState.uidl(1, uidl);
        receive("+OK 1");
        assertTrue(uidl.errorReceived);
        assertFalse(uidl.singleReceived);
    }

    @Test
    public void testUidlErrorReplies() {
        POP3ClientProtocolHandlerTest.RecordingUidlHandler uidl =
                new POP3ClientProtocolHandlerTest.RecordingUidlHandler();
        greeting.transactionState.uidl(1, uidl);
        receive("-ERR no such message");
        assertTrue(uidl.noSuchMessage);

        uidl = new POP3ClientProtocolHandlerTest.RecordingUidlHandler();
        greeting.transactionState.uidl(2, uidl);
        receive("-ERR boom");
        assertTrue(uidl.errorReceived);
        assertFalse(uidl.noSuchMessage);
    }

    @Test
    public void testUidlMultiSkipsMalformedEntries() {
        POP3ClientProtocolHandlerTest.RecordingUidlHandler uidl =
                new POP3ClientProtocolHandlerTest.RecordingUidlHandler();
        greeting.transactionState.uidl(uidl);
        receive("+OK", "x uid", "nospace", "1 abc", ".");
        assertTrue(uidl.uidComplete);
        assertEquals(1, uidl.entries.size());
        assertEquals("abc", uidl.entries.get(0).uid);
    }

    @Test
    public void testDeleErrorClassification() {
        POP3ClientProtocolHandlerTest.RecordingDeleHandler dele =
                new POP3ClientProtocolHandlerTest.RecordingDeleHandler();
        greeting.transactionState.dele(1, dele);
        receive("-ERR message already marked");
        assertTrue(dele.alreadyDeleted);

        dele = new POP3ClientProtocolHandlerTest.RecordingDeleHandler();
        greeting.transactionState.dele(2, dele);
        receive("-ERR boom");
        assertTrue(dele.noSuchMessage);
        assertFalse(dele.alreadyDeleted);
    }

    @Test
    public void testReplyWithoutStatusMarkerIsIgnored() {
        POP3ClientProtocolHandlerTest.RecordingStatHandler stat =
                new POP3ClientProtocolHandlerTest.RecordingStatHandler();
        greeting.transactionState.stat(stat);
        receive("garbage without marker");
        assertFalse(stat.statReceived);
        assertFalse(stat.errorReceived);
        receive("+OK 2 40");
        assertTrue(stat.statReceived);
        assertEquals(2, stat.messageCount);
    }

    @Test
    public void testCommandAfterCloseReportsNotConnected() {
        endpoint.close();
        POP3ClientProtocolHandlerTest.RecordingStatHandler stat =
                new POP3ClientProtocolHandlerTest.RecordingStatHandler();
        greeting.errorReceived = false;
        greeting.transactionState.stat(stat);
        assertTrue(greeting.errorReceived);
        assertFalse(stat.statReceived);
        assertNull(stat.errorMessage);
    }
}
