/*
 * HeaderStreamTest.java
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


package org.bluezoo.gumdrop.mime.rfc5322;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.ParserLimits;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The RFC 5322 layer delivers a header an element at a time, so a header of
 * any size passes through a small buffer and nothing is held but one element.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HeaderStreamTest {

    /** Logs the events, and counts the mailboxes and ids. */
    static class Log extends HeaderStreamGoldenTest.Render {

        final List<String> events = new ArrayList<String>();
        int mailboxes;
        int ids;
        String[] last;
        private final StringBuilder text = new StringBuilder();

        @Override
        public void startAddressHeader(String name) {
            events.add("start " + name);
        }

        @Override
        public void mailbox(String displayName, String localPart, String domain) {
            mailboxes++;
            last = new String[] { localPart, domain };
            events.add("mailbox " + (displayName == null ? "-" : displayName) + "|" + localPart + "|" + domain);
        }

        @Override
        public void startGroup(String name) {
            events.add("group " + name);
        }

        @Override
        public void endGroup() {
            events.add("end group");
        }

        @Override
        public void endAddressHeader() {
            events.add("end");
        }

        @Override
        public void startMessageIDHeader(String name) {
            events.add("start " + name);
        }

        @Override
        public void messageID(String localPart, String domain) {
            ids++;
            last = new String[] { localPart, domain };
        }

        @Override
        public void endMessageIDHeader() {
            events.add("end");
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) {
            events.add("obsolete " + type);
        }

        @Override
        public void malformedHeader(String name, String reason) {
            events.add("malformed " + name + ": " + reason);
        }

        /** The text after a failure arrives in pieces of no particular size. */
        @Override
        public void headerContent(String s) {
            text.append(s);
        }

        @Override
        public void endHeader() {
            events.add("text \"" + text + "\"");
            text.setLength(0);
        }

    }

    private static Log run(String message, ParserLimits limits, boolean smtputf8) throws MimeParseException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return feed(bytes, bytes.length + 64, bytes.length, limits, smtputf8);
    }

    /** Feeds the message through a buffer of the capacity, step bytes at a time. */
    private static Log feed(byte[] bytes, int capacity, int step, ParserLimits limits, boolean smtputf8)
            throws MimeParseException {
        Log log = new Log();
        MessageParser parser = new MessageParser();
        parser.setSmtputf8(smtputf8);
        if (limits != null) {
            parser.setLimits(limits);
        }
        parser.setMessageHandler(log);
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        for (int at = 0; at < bytes.length; at += step) {
            buffer.put(bytes, at, Math.min(step, bytes.length - at));
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        buffer.flip();
        parser.receive(buffer);
        parser.close();
        return log;
    }

    private static ParserLimits elementLimit(int limit) {
        ParserLimits limits = new ParserLimits();
        limits.setMaxElementLength(limit);
        return limits;
    }

    /** count addresses folded onto lines well under 998 bytes. */
    private static String manyAddresses(int count) {
        StringBuilder s = new StringBuilder("To: ");
        int line = 4;
        for (int i = 0; i < count; i++) {
            String a = "User " + i + " <user" + i + "@example.org>, ";
            if (line + a.length() > 900) {
                s.append("\r\n ");
                line = 1;
            }
            line += a.length();
            s.append(a);
        }
        s.append("last@example.org\r\n\r\nbody");
        return s.toString();
    }

    @Test
    public void aHeaderOfThousandsOfAddressesPassesThroughASmallBufferAnAddressAtATime()
            throws MimeParseException {
        // a header of about 200 KB through a buffer of 2 KB, with a limit of
        // 100 bytes on what is held: nothing can have been held but one address
        String message = manyAddresses(8000);
        assertTrue(message.length() > 200000);
        Log log = feed(message.getBytes(StandardCharsets.UTF_8), 2048, 700, elementLimit(100), false);
        assertEquals(8001, log.mailboxes);
        assertEquals("last", log.last[0]);
        assertEquals("example.org", log.last[1]);
        assertEquals("end", log.events.get(log.events.size() - 1));
        for (String e : log.events) {
            assertFalse(e, e.startsWith("malformed"));
        }
    }

    @Test
    public void aReferencesHeaderOfThousandsOfIdsStreamsToo() throws MimeParseException {
        StringBuilder message = new StringBuilder("References:");
        int line = 11;
        for (int i = 0; i < 6000; i++) {
            String id = " <id" + i + "@example.org>";
            if (line + id.length() > 900) {
                message.append("\r\n");
                line = 0;
            }
            line += id.length();
            message.append(id);
        }
        message.append("\r\n\r\nbody");
        Log log = feed(message.toString().getBytes(StandardCharsets.UTF_8), 2048, 333, elementLimit(100), false);
        assertEquals(6000, log.ids);
        assertEquals("id5999", log.last[0]);
        assertEquals("example.org", log.last[1]);
    }

    @Test
    public void theEventsAreTheSameWhateverTheChunking() throws MimeParseException {
        String message = manyAddresses(60);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        List<String> whole = feed(bytes, bytes.length + 64, bytes.length, null, false).events;
        int[][] shapes = { { 2048, 1 }, { 2048, 7 }, { 3000, 100 }, { 1500, 400 } };
        for (int[] shape : shapes) {
            assertEquals(whole, feed(bytes, shape[0], shape[1], null, false).events);
        }
    }

    @Test
    public void aGroupIsAStartItsMembersAndAnEnd() throws MimeParseException {
        Log log = run("To: Team: a@x.org, B <b@y.org>;, c@z.org, undisclosed-recipients:;\r\n\r\n", null, false);
        assertEquals(Arrays.asList("start To", "group Team", "mailbox -|a|x.org", "mailbox B|b|y.org",
            "end group", "mailbox -|c|z.org", "group undisclosed-recipients", "end group", "end"),
            log.events);
    }

    @Test
    public void whatWasReadBeforeABadElementIsDeliveredAndTheRestIsText() throws MimeParseException {
        Log log = run("To: a@x.org, Bob <b@y.org>, this is no address, d@w.org\r\n\r\n", null, false);
        assertEquals(Arrays.asList("start To", "mailbox -|a|x.org", "mailbox Bob|b|y.org",
            "malformed To: not a mailbox", "text \"this is no address, d@w.org\""), log.events);
    }

    @Test
    public void aBadElementInsideAGroupEndsTheGroupWithTheHeader() throws MimeParseException {
        Log log = run("To: Team: a@x.org, nonsense, b@y.org;\r\n\r\n", null, false);
        assertEquals(Arrays.asList("start To", "group Team", "mailbox -|a|x.org"), log.events.subList(0, 3));
        assertTrue(log.events.get(3).startsWith("malformed To"));
        // no end group and no end: the failure ended them
        assertFalse(log.events.contains("end group"));
        assertFalse(log.events.contains("end"));
    }

    @Test
    public void theElementLimitReachesTheRfc5322Layer() throws MimeParseException {
        StringBuilder long60 = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            long60.append('x');
        }
        String message = "To: a@x.org, " + long60 + " <b@y.org>\r\n\r\n";
        Log log = run(message, elementLimit(40), false);
        boolean tooLong = false;
        for (String e : log.events) {
            tooLong |= e.contains("too long");
        }
        assertTrue(log.events.toString(), tooLong);
        assertEquals(1, log.mailboxes);
        // within the limit it is fine
        log = run(message, elementLimit(200), false);
        assertEquals(2, log.mailboxes);
    }

    @Test
    public void smtpUtf8ReadsUtf8NamesAsTheGrammarAndOtherwiseSaysItIsObsolete() throws MimeParseException {
        String message = "To: Jos\u00e9 <jose@example.org>\r\n\r\n";
        Log log = run(message, null, true);
        assertEquals(Arrays.asList("start To", "mailbox Jos\u00e9|jose|example.org", "end"), log.events);
        log = run(message, null, false);
        assertEquals(Arrays.asList("start To", "obsolete OBSOLETE_ADDRESS_SYNTAX",
            "mailbox Jos\u00e9|jose|example.org", "end"), log.events);
    }

    @Test
    public void anEncodedWordCannotChangeTheStructure() throws MimeParseException {
        // decoded, this display name would contain a comma and angle brackets
        Log log = run("To: =?utf-8?q?a=2C_b_=3Cc=40d=3E?= <e@f.org>, g@h.org\r\n\r\n", null, false);
        assertEquals(Arrays.asList("start To", "mailbox a, b <c@d>|e|f.org", "mailbox -|g|h.org", "end"),
            log.events);
    }

    @Test
    public void theOlderEventsAreStillDeliveredAndNothingIsDeliveredTwice() throws MimeParseException {
        Log log = new Log() {
            @Override
            public boolean wantsLegacyEvents() {
                return true;
            }
        };
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(log);
        parser.receive(ByteBuffer.wrap(("Date: 21 Nov 97 09:55:06 GMT\r\n"
            + "To: a@x.org, b@y.org\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
        parser.close();
        int dates = 0;
        int addressHeaders = 0;
        int obsolete = 0;
        for (String e : log.out) {
            if (e.startsWith("date ")) {
                dates++;
            } else if (e.equals("LEGACY addressHeader To")) {
                addressHeaders++;
            }
        }
        for (String e : log.events) {
            if (e.startsWith("obsolete ")) {
                obsolete++;
            }
        }
        assertEquals(1, dates);
        assertEquals(1, addressHeaders);
        // the older events report the obsolete date; the stream leaves it to them
        assertEquals(log.events.toString(), 1, obsolete);
        assertEquals(Arrays.asList("obsolete OBSOLETE_DATE_TIME_SYNTAX", "start To", "mailbox -|a|x.org",
            "mailbox -|b|y.org", "end"), log.events);
    }

    private static Log runLegacy(String headers) throws MimeParseException {
        Log log = new Log() {
            @Override
            public boolean wantsLegacyEvents() {
                return true;
            }
        };
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(log);
        parser.receive(ByteBuffer.wrap((headers + "\r\n").getBytes(StandardCharsets.UTF_8)));
        parser.close();
        return log;
    }

    @Test
    public void whatWasReadBeforeABadElementIsStillGivenToTheOlderEvents() throws MimeParseException {
        Log log = runLegacy("To: a@x.org, garbage\r\n");
        assertEquals(Arrays.asList("LEGACY addressHeader To", "LEGACY unexpectedHeader To"), log.out);
    }

    @Test
    public void aGroupIsGivenToTheOlderEventsAndNotDropped() throws MimeParseException {
        Log log = runLegacy("Cc: undisclosed-recipients:;\r\n");
        assertEquals(Arrays.asList("LEGACY addressHeader Cc"), log.out);
    }

}
