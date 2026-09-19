/*
 * ArcHeaderParserTest.java
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

package org.bluezoo.gumdrop.smtp.auth;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ArcHeaderParser} (RFC 8617 header set grouping).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ArcHeaderParserTest {

    @Test
    public void testNoArcHeadersReturnsEmpty() {
        RecordingHandler handler = new RecordingHandler();
        feedHeaders(handler,
                raw("From", "From: a@example.com\r\n"));
        handler.assertEnd(false, Collections.<ArcSet>emptyList());
    }

    @Test
    public void testSingleCompleteSet() {
        RecordingHandler handler = new RecordingHandler();
        feedHeaders(handler,
                raw("From", "From: a@example.com\r\n"),
                raw("ARC-Authentication-Results",
                        "ARC-Authentication-Results: i=1; mx.example.com; spf=pass\r\n"),
                raw("ARC-Message-Signature",
                        "ARC-Message-Signature: i=1; a=rsa-sha256; d=example.com; s=sel; "
                                + "h=from; bh=abc; b=def\r\n"),
                raw("ARC-Seal",
                        "ARC-Seal: i=1; a=rsa-sha256; d=example.com; s=sel; "
                                + "h=arc-seal:arc-message-signature:arc-authentication-results; "
                                + "bh=abc; b=def; cv=none\r\n"));

        assertEquals(1, handler.sets.size());
        assertEquals(1, handler.sets.get(0).getInstance());
        assertNotNull(handler.sets.get(0).getAuthenticationResults());
        assertNotNull(handler.sets.get(0).getMessageSignature());
        assertNotNull(handler.sets.get(0).getSeal());
        assertEquals(ArcCvResult.NONE, handler.sets.get(0).getSealCv());
    }

    @Test
    public void testMissingInstanceInSequenceFailsParse() {
        RecordingHandler handler = new RecordingHandler();
        feedHeaders(handler,
                raw("ARC-Authentication-Results",
                        "ARC-Authentication-Results: i=2; mx.example.com; spf=pass\r\n"),
                raw("ARC-Message-Signature",
                        "ARC-Message-Signature: i=2; a=rsa-sha256; d=example.com; s=sel; "
                                + "h=from; bh=abc; b=def\r\n"),
                raw("ARC-Seal",
                        "ARC-Seal: i=2; a=rsa-sha256; d=example.com; s=sel; "
                                + "h=arc-seal; bh=abc; b=def; cv=pass\r\n"));

        assertTrue(handler.malformed);
    }

    private static void feedHeaders(RecordingHandler handler,
                                    DkimMessageParser.RawHeader... headers) {
        ArcHeaderParser parser = new ArcHeaderParser(handler);
        for (int i = 0; i < headers.length; i++) {
            parser.header(headers[i]);
        }
        parser.endHeaders();
        handler.finalizeSets();
    }

    private static DkimMessageParser.RawHeader raw(String name, String line) {
        return new DkimMessageParser.RawHeader(name,
                line.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Test handler that mirrors {@link ArcValidator}'s chain grouping.
     */
    private static final class RecordingHandler implements ArcHeaderHandler {

        private final Map<Integer, MutableSet> byInstance =
                new HashMap<Integer, MutableSet>();
        boolean malformed;
        List<ArcSet> sets = Collections.emptyList();

        @Override
        public void arcAuthenticationResults(int instance, String rawLine) {
            mutableSet(instance).aar = rawLine;
        }

        @Override
        public void arcMessageSignature(int instance, String rawLine,
                                        DkimSignature parsed) {
            MutableSet set = mutableSet(instance);
            set.ams = rawLine;
            set.parsedAms = parsed;
        }

        @Override
        public void arcSeal(int instance, String rawLine, DkimSignature parsed,
                            ArcCvResult sealCv) {
            MutableSet set = mutableSet(instance);
            set.as = rawLine;
            set.parsedAs = parsed;
            set.sealCv = sealCv;
        }

        @Override
        public void arcHeadersEnd() {
            finalizeSets();
        }

        void finalizeSets() {
            if (byInstance.isEmpty()) {
                sets = Collections.emptyList();
                return;
            }
            int max = 0;
            for (Integer key : byInstance.keySet()) {
                if (key.intValue() > max) {
                    max = key.intValue();
                }
            }
            for (int inst = 1; inst <= max; inst++) {
                MutableSet set = byInstance.get(Integer.valueOf(inst));
                if (set == null || set.aar == null || set.ams == null
                        || set.as == null) {
                    malformed = true;
                    sets = Collections.emptyList();
                    return;
                }
            }
            List<ArcSet> built = new ArrayList<ArcSet>();
            for (int inst = 1; inst <= max; inst++) {
                MutableSet m = byInstance.get(Integer.valueOf(inst));
                built.add(new ArcSet(inst, m.aar, m.ams, m.as, m.sealCv,
                        m.parsedAms, m.parsedAs));
            }
            sets = built;
        }

        void assertEnd(boolean expectMalformed, List<ArcSet> expectEmpty) {
            assertEquals(expectMalformed, malformed);
            assertEquals(expectEmpty.size(), sets.size());
        }

        private MutableSet mutableSet(int instance) {
            Integer key = Integer.valueOf(instance);
            MutableSet set = byInstance.get(key);
            if (set == null) {
                set = new MutableSet();
                byInstance.put(key, set);
            }
            return set;
        }
    }

    private static final class MutableSet {
        String aar;
        String ams;
        String as;
        ArcCvResult sealCv = ArcCvResult.NONE;
        DkimSignature parsedAms;
        DkimSignature parsedAs;
    }

}
