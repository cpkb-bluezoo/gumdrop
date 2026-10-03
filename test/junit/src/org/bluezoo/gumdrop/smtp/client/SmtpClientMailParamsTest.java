/*
 * SmtpClientMailParamsTest.java
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

package org.bluezoo.gumdrop.smtp.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks the MAIL FROM extension parameters {@link SmtpClientProtocolHandler}
 * appends for each capability the server advertises, the replies that arrive
 * in the middle of a BDAT transfer, and the guards on commands sent after the
 * session has closed.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SmtpClientMailParamsTest {

    private final List<String> sent = new ArrayList<String>();
    private SmtpClientSessionFlowTest.Recorder rec;
    private SmtpClientProtocolHandler handler;

    @Before
    public void setUp() {
        sent.clear();
        rec = new SmtpClientSessionFlowTest.Recorder();
        SmtpClientSessionFlowTest.TlsFailingEndpoint endpoint =
                new SmtpClientSessionFlowTest.TlsFailingEndpoint(sent);
        handler = new SmtpClientProtocolHandler(rec);
        handler.connected(endpoint);
    }

    private void reply(String text) {
        handler.receive(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private static EmailAddress addr(String address) {
        int at = address.indexOf('@');
        return new EmailAddress(null, address.substring(0, at),
                address.substring(at + 1), true);
    }

    private String last() {
        return sent.isEmpty() ? "" : sent.get(sent.size() - 1);
    }

    private void ready(String... capabilityLines) {
        reply("220 mx.example.com ESMTP ready\r\n");
        handler.ehlo("client.example.com", rec);
        StringBuilder sb = new StringBuilder();
        sb.append("250-mx.example.com\r\n");
        for (int i = 0; i < capabilityLines.length; i++) {
            sb.append("250-").append(capabilityLines[i]).append("\r\n");
        }
        sb.append("250 HELP\r\n");
        reply(sb.toString());
    }

    private static MailFromParams fullParams() {
        MailFromParams params = new MailFromParams();
        params.body = "8BITMIME";
        params.smtpUtf8 = true;
        params.ret = "FULL";
        params.envid = "env1";
        params.requireTls = true;
        params.mtPriority = Integer.valueOf(3);
        params.holdFor = 60L;
        params.holdUntil = "2999-01-01T00:00:00Z";
        params.by = "120;R";
        return params;
    }

    @Test
    public void testMailFromCarriesEveryAdvertisedExtension() {
        ready("SIZE 5000", "8BITMIME", "SMTPUTF8", "DSN", "REQUIRETLS",
                "MT-PRIORITY MIXER", "FUTURERELEASE 604800 2012-01-01T00:00:00Z",
                "DELIVERBY 604800");
        handler.mailFrom(addr("s@example.org"), 100L, fullParams(), rec);
        String command = last();
        assertEquals("MAIL FROM:<s@example.org> SIZE=100 BODY=8BITMIME SMTPUTF8 RET=FULL"
                + " ENVID=env1 REQUIRETLS MT-PRIORITY=3 HOLDFOR=60"
                + " HOLDUNTIL=2999-01-01T00:00:00Z BY=120;R", command);
    }

    @Test
    public void testMailFromOmitsUnadvertisedExtensions() {
        ready();
        handler.mailFrom(addr("s@example.org"), 100L, fullParams(), rec);
        assertEquals("MAIL FROM:<s@example.org>", last());
    }

    @Test
    public void testMailFromBinaryMimeAloneAllowsBody() {
        ready("BINARYMIME");
        MailFromParams params = new MailFromParams();
        params.body = "BINARYMIME";
        handler.mailFrom(addr("s@example.org"), 0L, params, rec);
        assertEquals("MAIL FROM:<s@example.org> BODY=BINARYMIME", last());
    }

    @Test
    public void testMailFromNullSenderAndNullParams() {
        ready("SIZE 5000");
        handler.mailFrom(null, 10L, null, rec);
        assertEquals("MAIL FROM:<> SIZE=10", last());
    }

    @Test
    public void testMailFromEmptyParamsAddsNothing() {
        ready("8BITMIME", "DSN", "FUTURERELEASE 1", "DELIVERBY 1", "MT-PRIORITY");
        handler.mailFrom(addr("s@example.org"), 0L, new MailFromParams(), rec);
        assertEquals("MAIL FROM:<s@example.org>", last());
    }

    @Test
    public void testMailFromHoldUntilWithoutHoldFor() {
        ready("FUTURERELEASE 1");
        MailFromParams params = new MailFromParams();
        params.holdUntil = "2999-01-01T00:00:00Z";
        handler.mailFrom(addr("s@example.org"), 0L, params, rec);
        assertEquals("MAIL FROM:<s@example.org> HOLDUNTIL=2999-01-01T00:00:00Z", last());
    }

    @Test
    public void testEhloCapabilityEdgeLines() {
        ready("SIZE", "AUTH", "AUTH  PLAIN   LOGIN ", "LIMITS RCPTMAX=3 MAILMAX=5 OTHER=1",
                "LIMITS MAILMAX=zz", "ENHANCEDSTATUSCODES");
        assertEquals(0L, rec.ehloMaxSize);
        assertEquals(2, rec.ehloAuth.size());
        assertEquals("PLAIN", rec.ehloAuth.get(0));
        assertEquals("LOGIN", rec.ehloAuth.get(1));
    }

    // -- BDAT replies in the middle of a transfer --

    private void startBdat() {
        ready("CHUNKING");
        handler.mailFrom(addr("s@example.org"), rec);
        reply("250 ok\r\n");
        handler.rcptTo(addr("r@example.net"), rec);
        reply("250 ok\r\n");
        rec.ready.data(rec);
        assertNotNull(rec.data);
        rec.data.writeContent(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void testBdatChunkAcceptedMidTransferKeepsTransferOpen() {
        startBdat();
        reply("250 chunk ok\r\n");
        assertEquals(0, rec.sessionTemp);
        assertNull(rec.messagePermanent);
        rec.data.endMessage(rec);
        assertEquals("BDAT 0 LAST", last());
    }

    @Test
    public void testBdatChunkTemporaryFailureMidTransferAbandonsTransfer() {
        startBdat();
        reply("451 try later\r\n");
        try {
            rec.data.writeContent(ByteBuffer.wrap("d".getBytes(StandardCharsets.US_ASCII)));
            fail("transfer should have been abandoned");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        assertTrue(handler.isConnected());
    }

    @Test
    public void testBdatChunkPermanentFailureMidTransferAbandonsTransfer() {
        startBdat();
        reply("554 never\r\n");
        try {
            rec.data.endMessage(rec);
            fail("transfer should have been abandoned");
        } catch (IllegalStateException expected) {
            assertNotNull(expected);
        }
        assertTrue(handler.isConnected());
    }

    @Test
    public void testStrayReplyDuringPlainDataModeIsIgnored() {
        ready();
        handler.mailFrom(addr("s@example.org"), rec);
        reply("250 ok\r\n");
        handler.rcptTo(addr("r@example.net"), rec);
        reply("250 ok\r\n");
        rec.ready.data(rec);
        reply("354 go\r\n");
        assertNotNull(rec.data);
        int before = sent.size();
        reply("250 stray\r\n");
        assertEquals(before, sent.size());
        assertNull(rec.error);
        assertTrue(handler.isConnected());
    }

    // -- closed session guards --

    @Test
    public void testCommandAfterCloseReportsNotConnected() {
        ready();
        handler.close();
        assertFalse(handler.isConnected());
        handler.ehlo("c.example.com", rec);
        assertNotNull(rec.error);
        assertEquals("Not connected", rec.error.getMessage());
    }

    @Test
    public void testReplyAfterCloseIsIgnored() {
        ready();
        handler.close();
        rec.mailOk = false;
        reply("250 late\r\n");
        assertFalse(rec.mailOk);
        assertNull(rec.error);
    }

    @Test
    public void testCrlfInCommandArgumentIsRejected() {
        ready();
        try {
            handler.helo("a\r\nQUIT", rec);
            fail("CRLF must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }
}
