/*
 * ZoneFileParserTest.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.dns.DnsType;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ZoneFileParserTest {

    @Test
    public void testParenthesisSoaAndIncrementalFeed() throws Exception {
        String zone = ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. (\n"
                + "  1 7200 3600 1209600 300 )\n"
                + "@ IN NS ns1.example.com.\n"
                + "long IN TXT ( \"part one\" \n"
                + "  \"part two\" )\n";
        Path file = Files.createTempFile("paren-zone", ".zone");
        Files.writeString(file, zone);
        try {
            ZoneFile loaded = ZoneFile.load(file);
            assertEquals(300, loaded.getMinimumTtl());
            ZoneLookupResult txt = loaded.lookup("long.example.com.", DnsType.TXT);
            assertEquals(ZoneLookupResult.STATUS_ANSWER, txt.getStatus());
            assertTrue(txt.getAnswers().get(0).getText().contains("part one"));
            assertTrue(txt.getAnswers().get(0).getText().contains("part two"));

            List<String> events = new ArrayList<String>();
            ZoneFileParser parser = new ZoneFileParser(new CollectingHandler(events));
            byte[] bytes = zone.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(64);
            int offset = 0;
            while (offset < bytes.length) {
                if (!parser.isUnderflow()) {
                    buf.clear();
                } else {
                    buf.compact();
                }
                int chunk = Math.min(buf.remaining(), bytes.length - offset);
                assertTrue("feed chunk must fit in buffer", chunk > 0);
                buf.put(bytes, offset, chunk);
                buf.flip();
                parser.receive(buf);
                offset += chunk;
            }
            parser.close();
            assertTrue(hasEventStartingWith(events, "record:@"));
            assertTrue(hasEventStartingWith(events, "record:long"));
            assertTrue(countEventsEqual(events, "endRecord") >= 3);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void testGenerateExpandsRecords() throws Exception {
        Path zone = Files.createTempFile("generate", ".zone");
        Files.writeString(zone, ""
                + "$ORIGIN example.com.\n"
                + "$TTL 300\n"
                + "@ IN SOA ns1.example.com. host.example.com. 1 7200 3600 1209600 300\n"
                + "@ IN NS ns1.example.com.\n"
                + "$GENERATE 1-2 db-$ A 192.0.2.$\n");
        try {
            ZoneFile loaded = ZoneFile.load(zone);
            ZoneLookupResult one = loaded.lookup("db-1.example.com.", DnsType.A);
            assertEquals(ZoneLookupResult.STATUS_ANSWER, one.getStatus());
            assertEquals("192.0.2.1", one.getAnswers().get(0).getAddress().getHostAddress());
            ZoneLookupResult two = loaded.lookup("db-2.example.com.", DnsType.A);
            assertEquals("192.0.2.2", two.getAnswers().get(0).getAddress().getHostAddress());
        } finally {
            Files.deleteIfExists(zone);
        }
    }

    private static boolean hasEventStartingWith(List<String> events, String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int countEventsEqual(List<String> events, String value) {
        int count = 0;
        for (int i = 0; i < events.size(); i++) {
            if (value.equals(events.get(i))) {
                count++;
            }
        }
        return count;
    }

    private static final class CollectingHandler implements ZoneFileHandler {
        private final List<String> events;

        CollectingHandler(List<String> events) {
            this.events = events;
        }

        @Override
        public void origin(String origin) {
            events.add("origin:" + origin);
        }

        @Override
        public void defaultTtl(int ttl) {
            events.add("ttl:" + ttl);
        }

        @Override
        public void include(String filename, String originOverride) {
            events.add("include:" + filename);
        }

        @Override
        public void unknownDirective(String name) {
            events.add("unknown:" + name);
        }

        @Override
        public void beginGenerate(String rangeSpec, String ownerTemplate) {
            events.add("generate:" + rangeSpec + ":" + ownerTemplate);
        }

        @Override
        public void beginRecord(String ownerToken) {
            events.add("record:" + ownerToken);
        }

        @Override
        public void appendField(String token) {
            events.add("field:" + token);
        }

        @Override
        public void endGenerate() {
            events.add("endGenerate");
        }

        @Override
        public void endRecord() {
            events.add("endRecord");
        }

        @Override
        public void endFile(Path file) {
        }
    }
}
