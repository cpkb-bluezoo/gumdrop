/*
 * HeaderStreamGoldenTest.java
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.bluezoo.gumdrop.mime.MimeParseException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * What the RFC 5322 layer makes of the address, message-id and date headers
 * when it reads them as elements, with the older events not wanted. The table
 * is the one the Rust parser this design comes from is tested with. Each row
 * is also run with the input cut at every position, and a byte at a time,
 * and must give the same events.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
@SuppressWarnings("removal") // exercises the older events, which are deprecated for removal
public class HeaderStreamGoldenTest {

    /** name, value (one char per byte), expected events */
    private static final String[][] CASES = {
        {"To", "\"a\".\"b\"@example.com",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [-|\"a\".\"b\"|example.com]"},
        {"To", "Bob <a.\"b c\".d@example.com>",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [Bob|a.\"b c\".d|example.com]"},
        {"To", "\"a\".@example.com",
            "malformed (not a mailbox) \"\\\"a\\\".@example.com\""},
        {"To", "a@example.com",
            "addresses [-|a|example.com]"},
        {"To", "Alice <alice@example.com>",
            "addresses [Alice|alice|example.com]"},
        {"To", "\"Alice B\" <alice@example.com>",
            "addresses [Alice B|alice|example.com]"},
        {"To", "Alice <alice@example.com>, Bob <bob@example.com>",
            "addresses [Alice|alice|example.com, Bob|bob|example.com]"},
        {"To", "alice@example.com, bob@example.com",
            "addresses [-|alice|example.com, -|bob|example.com]"},
        {"To", "=?UTF-8?Q?Jos=C3=A9?= <jose@example.com>",
            "addresses [Jos\u00e9|jose|example.com]"},
        {"To", "\"=?UTF-8?B?Sm9zw6k=?=\" <jose@example.com>",
            "addresses [Jos\u00e9|jose|example.com]"},
        {"To", "Alice (comment) <alice@example.com>",
            "addresses [Alice|alice|example.com]"},
        {"To", "alice@example.com (Alice)",
            "addresses [-|alice|example.com]"},
        {"To", "Team: a@x.org, b@y.org;",
            "addresses [group Team{-|a|x.org, -|b|y.org}]"},
        {"To", "Team: a@x.org;, c@z.org",
            "addresses [group Team{-|a|x.org}, -|c|z.org]"},
        {"To", "undisclosed-recipients:;",
            "addresses [group undisclosed-recipients{}]"},
        {"To", "\"Smith, John\" <js@example.com>",
            "addresses [Smith, John|js|example.com]"},
        {"To", "Smith, John <js@example.com>",
            "malformed (not a mailbox) \"Smith, John <js@example.com>\""},
        {"To", "a@x.org,,b@y.org",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [-|a|x.org, -|b|y.org]"},
        {"To", "a@x.org, , b@y.org,",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [-|a|x.org, -|b|y.org]"},
        {"To", "<alice@example.com>",
            "addresses [-|alice|example.com]"},
        {"To", "Alice <alice@example.com",
            "malformed (a quoted string, comment or angle bracket is not closed) \"Alice <alice@example.com\""},
        {"To", "Alice alice@example.com",
            "malformed (not a mailbox) \"Alice alice@example.com\""},
        {"To", "not an address",
            "malformed (not a mailbox) \"not an address\""},
        {"To", "<@a.com,@b.com:alice@example.com>",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [-|alice|example.com]"},
        {"To", "\"quoted local\"@example.com",
            "addresses [-|\"quoted local\"|example.com]"},
        {"To", "alice@[192.0.2.1]",
            "addresses [-|alice|[192.0.2.1]]"},
        {"To", "alice@example",
            "addresses [-|alice|example]"},
        {"To", "x@y.org, garbage, z@w.org",
            "malformed (not a mailbox) \"garbage, z@w.org\""},
        {"To", "Alice <alice@example.com>, garbage",
            "malformed (not a mailbox) \"garbage\""},
        {"To", "garbage, Alice <alice@example.com>",
            "malformed (not a mailbox) \"garbage, Alice <alice@example.com>\""},
        {"To", "",
            "addresses []"},
        {"To", "   ",
            "addresses []"},
        {"To", "a@b.c,d@e.f",
            "addresses [-|a|b.c, -|d|e.f]"},
        {"To", "A <a@b.c> (c1) , (c2) B <b@c.d>",
            "addresses [A|a|b.c, B|b|c.d]"},
        {"To", "Jos\u00e9 <jose@example.com>",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [Jos\u00e9|jose|example.com]"},
        {"To", "Jos\u00c3\u00a9 <jose@example.com>",
            "obsolete OBSOLETE_ADDRESS_SYNTAX; addresses [Jos\u00e9|jose|example.com]"},
        {"From", "Alice <alice@example.com>",
            "addresses [Alice|alice|example.com]"},
        {"Message-ID", "<abc@example.com>",
            "ids [<abc@example.com>]"},
        {"References", "<a@b.org> <c@d.org>",
            "ids [<a@b.org>, <c@d.org>]"},
        {"References", "<a@b.org>, <c@d.org>",
            "ids [<a@b.org>, <c@d.org>]"},
        {"References", "<a@b.org> (comment) <c@d.org>",
            "ids [<a@b.org>, <c@d.org>]"},
        {"References", "a@b.org",
            "obsolete OBSOLETE_MESSAGE_ID_SYNTAX; ids [<a@b.org>]"},
        {"References", "<abc>",
            "malformed (not a message-id) \"<abc>\""},
        {"References", "<a@b.org",
            "malformed (a message-id is not closed) \"<a@b.org\""},
        {"References", "<a b@c.org>",
            "malformed (not a message-id) \"<a b@c.org>\""},
        {"References", "garbage <a@b.org>",
            "malformed (not a message-id) \"garbage <a@b.org>\""},
        {"References", "<a@b.org> garbage",
            "malformed (not a message-id) \"garbage\""},
        {"References", "",
            "ids []"},
        {"References", "<\"q a\"@example.com>",
            "malformed (not a message-id) \"<\\\"q a\\\"@example.com>\""},
        {"References", "<a@[1.2.3.4]>",
            "ids [<a@[1.2.3.4]>]"},
        {"Date", "Tue, 6 Oct 2026 10:00:00 +0000",
            "date Tue, 6 Oct 2026 10:00:00 +0000"},
        {"Date", "Tue, 6 Oct 2026 10:00:00 +0000 (UTC)",
            "date Tue, 6 Oct 2026 10:00:00 +0000"},
        {"Date", "6 Oct 2026 10:00:00 GMT",
            "obsolete OBSOLETE_DATE_TIME_SYNTAX; date Tue, 6 Oct 2026 10:00:00 +0000"},
        {"Date", "Tue, 06 Oct 26 10:00 EST",
            "obsolete OBSOLETE_DATE_TIME_SYNTAX; date Tue, 6 Oct 2026 10:00:00 -0500"},
        {"Date", "Tue, 6 Oct 2026 10:00:00 -0500",
            "date Tue, 6 Oct 2026 10:00:00 -0500"},
        {"Date", "garbage",
            "malformed (not a date and time) \"garbage\""},
        {"Date", "",
            "malformed (not a date and time) \"\""},
        {"Date", "Tue, 6 Oct 2026 25:00:00 +0000",
            "malformed (not a date and time) \"Tue, 6 Oct 2026 25:00:00 +0000\""},
    };

    /** Renders the events of the headers as text. */
    static class Render implements MessageHandler {

        final List<String> out = new ArrayList<String>();
        private List<String> addresses = new ArrayList<String>();
        private List<String> members;
        private String group;
        private List<String> ids = new ArrayList<String>();
        private String malformedReason;
        private StringBuilder malformedText;

        @Override
        public boolean wantsLegacyEvents() {
            return false;
        }

        @Override
        public void setLocator(org.bluezoo.gumdrop.mime.MimeLocator locator) {
        }

        @Override
        public void startEntity(String boundary) {
        }

        @Override
        public void contentType(org.bluezoo.gumdrop.mime.ContentType contentType) {
        }

        @Override
        public void contentDisposition(org.bluezoo.gumdrop.mime.ContentDisposition contentDisposition) {
        }

        @Override
        public void contentTransferEncoding(String encoding) {
        }

        @Override
        public void contentID(org.bluezoo.gumdrop.mime.ContentID contentID) {
        }

        @Override
        public void contentDescription(String description) {
        }

        @Override
        public void mimeVersion(org.bluezoo.gumdrop.mime.MimeVersion version) {
        }

        @Override
        public void endHeaders() {
        }

        @Override
        public void bodyContent(ByteBuffer data) {
        }

        @Override
        public void unexpectedContent(ByteBuffer data) {
        }

        @Override
        public void endEntity(String boundary) {
        }

        @Override
        public void header(String name, String value) {
            out.add("LEGACY header " + name);
        }

        @Override
        public void unexpectedHeader(String name, String value) {
            out.add("LEGACY unexpectedHeader " + name);
        }

        @Override
        public void dateHeader(String name, OffsetDateTime date) {
            out.add("date " + MessageDateTimeFormatter.format(date));
        }

        @Override
        public void addressHeader(String name, List<EmailAddress> list) {
            out.add("LEGACY addressHeader " + name);
        }

        @Override
        public void messageIDHeader(String name, List<org.bluezoo.gumdrop.mime.ContentID> list) {
            out.add("LEGACY messageIDHeader " + name);
        }

        @Override
        public void obsoleteStructure(ObsoleteStructureType type) {
            out.add("obsolete " + type);
        }

        @Override
        public void startAddressHeader(String name) {
            addresses.clear();
            group = null;
            members = null;
        }

        @Override
        public void mailbox(String displayName, String localPart, String domain) {
            String m = (displayName == null ? "-" : displayName) + "|" + localPart + "|" + domain;
            if (members != null) {
                members.add(m);
            } else {
                addresses.add(m);
            }
        }

        @Override
        public void startGroup(String name) {
            group = name;
            members = new ArrayList<String>();
        }

        @Override
        public void endGroup() {
            addresses.add("group " + group + "{" + join(members) + "}");
            members = null;
        }

        @Override
        public void endAddressHeader() {
            out.add("addresses [" + join(addresses) + "]");
        }

        @Override
        public void startMessageIDHeader(String name) {
            ids.clear();
        }

        @Override
        public void messageID(String localPart, String domain) {
            ids.add("<" + localPart + "@" + domain + ">");
        }

        @Override
        public void endMessageIDHeader() {
            out.add("ids [" + join(ids) + "]");
        }

        @Override
        public void malformedHeader(String name, String reason) {
            malformedReason = reason;
            malformedText = new StringBuilder();
        }

        @Override
        public void headerContent(String text) {
            if (malformedText != null) {
                malformedText.append(text);
            }
        }

        @Override
        public void endHeader() {
            if (malformedReason != null) {
                out.add("malformed (" + malformedReason + ") " + quote(malformedText.toString()));
                malformedReason = null;
                malformedText = null;
            }
        }

        private static String join(List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(list.get(i));
            }
            return sb.toString();
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        String events() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < out.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(out.get(i));
            }
            return sb.toString();
        }

    }

    static String render(byte[] message, int[] cuts) throws MimeParseException {
        Render handler = new Render();
        MessageParser parser = new MessageParser();
        parser.setMessageHandler(handler);
        ByteBuffer buffer = ByteBuffer.allocate(message.length + 64);
        int at = 0;
        for (int i = 0; i <= cuts.length; i++) {
            int to = i < cuts.length ? Math.min(at + cuts[i], message.length) : message.length;
            buffer.put(message, at, to - at);
            at = to;
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }
        buffer.flip();
        parser.receive(buffer);
        parser.close();
        return handler.events();
    }

    private static byte[] message(String name, String value) {
        return (name + ": " + value + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    public void headersAreReadAsRecorded() throws MimeParseException {
        StringBuilder wrong = new StringBuilder();
        for (String[] c : CASES) {
            String got = render(message(c[0], c[1]), new int[0]);
            if (!c[2].equals(got)) {
                wrong.append(c[0]).append(": ").append(c[1]).append("\n  expected ").append(c[2])
                    .append("\n  but was  ").append(got).append('\n');
            }
        }
        assertEquals("", wrong.toString());
    }

    @Test
    public void headersAreTheSameWhereverTheInputIsCut() throws MimeParseException {
        for (String[] c : CASES) {
            byte[] message = message(c[0], c[1]);
            String whole = render(message, new int[0]);
            for (int cut = 1; cut < message.length; cut++) {
                assertEquals(c[0] + ": " + c[1] + " cut at " + cut, whole, render(message, new int[] { cut }));
            }
            int[] ones = new int[message.length];
            java.util.Arrays.fill(ones, 1);
            assertEquals(c[0] + ": " + c[1] + " byte by byte", whole, render(message, ones));
        }
    }

}
