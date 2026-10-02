/*
 * ArcHeaderParserTagsTest
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tag scanning rules of {@link ArcHeaderParser}: how the {@code i=} instance
 * and {@code cv=} values are located, which headers are dispatched, and the
 * once-only end-of-headers signal.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ArcHeaderParserTagsTest {

    /** Records the events delivered by the parser. */
    private static final class Events implements ArcHeaderHandler {
        final List<String> log = new ArrayList<String>();
        final List<ArcCvResult> cvs = new ArrayList<ArcCvResult>();
        final List<DkimSignature> parsed = new ArrayList<DkimSignature>();
        int ends;

        @Override
        public void arcAuthenticationResults(int instance, String rawLine) {
            log.add("aar:" + instance);
        }

        @Override
        public void arcMessageSignature(int instance, String rawLine, DkimSignature sig) {
            log.add("ams:" + instance);
            parsed.add(sig);
        }

        @Override
        public void arcSeal(int instance, String rawLine, DkimSignature sig,
                            ArcCvResult sealCv) {
            log.add("as:" + instance);
            cvs.add(sealCv);
            parsed.add(sig);
        }

        @Override
        public void arcHeadersEnd() {
            ends++;
        }
    }

    private Events events;
    private ArcHeaderParser parser;

    @Before
    public void setUp() {
        events = new Events();
        parser = new ArcHeaderParser(events);
    }

    private void header(String name, String line) {
        parser.header(new DkimMessageParser.RawHeader(name,
                (line + "\r\n").getBytes(StandardCharsets.US_ASCII)));
    }

    private static final String SEAL_TAGS = "a=rsa-sha256; d=e.com; s=k; v=1; "
            + "h=arc-seal; bh=AA; b=BB";

    @Test
    public void instanceMayFollowOtherTagsAndSurroundingWhitespace() {
        header("ARC-Authentication-Results", "ARC-Authentication-Results: mx.e.com;  \t i=3 ; spf=pass");
        assertEquals(1, events.log.size());
        assertEquals("aar:3", events.log.get(0));
    }

    @Test
    public void firstInstanceTagWins() {
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i=2; i=5; spf=pass");
        assertEquals("aar:2", events.log.get(0));
    }

    @Test
    public void invalidInstancesAreIgnored() {
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i=abc; spf=pass");
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i=; spf=pass");
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i=0; spf=pass");
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i=1x; spf=pass");
        header("ARC-Authentication-Results", "ARC-Authentication-Results: spf=pass");
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i");
        header("ARC-Authentication-Results", "ARC-Authentication-Results: ;;; ;");
        assertTrue(events.log.toString(), events.log.isEmpty());
    }

    @Test
    public void instanceWithInnerWhitespaceAfterEqualsIsAccepted() {
        header("ARC-Authentication-Results", "ARC-Authentication-Results: i= 4;");
        assertEquals("aar:4", events.log.get(0));
    }

    @Test
    public void sealCvValuesAreRecognisedCaseInsensitively() {
        header("ARC-Seal", "ARC-Seal: i=1; cv=PASS; " + SEAL_TAGS);
        header("ARC-Seal", "ARC-Seal: i=2; cv=Fail; " + SEAL_TAGS);
        header("ARC-Seal", "ARC-Seal: i=3; cv=none; " + SEAL_TAGS);
        header("ARC-Seal", "ARC-Seal: i=4; cv=bogus; " + SEAL_TAGS);
        header("ARC-Seal", "ARC-Seal: i=5; cv= pass ; " + SEAL_TAGS);
        header("ARC-Seal", "ARC-Seal: i=6; " + SEAL_TAGS);
        header("ARC-Seal", "ARC-Seal: i=7; cv=; " + SEAL_TAGS);
        assertEquals(ArcCvResult.PASS, events.cvs.get(0));
        assertEquals(ArcCvResult.FAIL, events.cvs.get(1));
        assertEquals(ArcCvResult.NONE, events.cvs.get(2));
        assertEquals(ArcCvResult.NONE, events.cvs.get(3));
        assertEquals(ArcCvResult.PASS, events.cvs.get(4));
        assertEquals(ArcCvResult.NONE, events.cvs.get(5));
        assertEquals(ArcCvResult.NONE, events.cvs.get(6));
    }

    @Test
    public void cvIsNotReadFromMessageSignatures() {
        header("ARC-Message-Signature", "ARC-Message-Signature: i=1; cv=pass; v=1; "
                + "a=rsa-sha256; d=e.com; s=k; h=from; bh=AA; b=BB");
        assertEquals("ams:1", events.log.get(0));
        assertEquals("e.com", events.parsed.get(0).getDomain());
    }

    @Test
    public void incompleteSignatureTagsGiveNullParsedForm() {
        header("ARC-Seal", "ARC-Seal: i=1; cv=none");
        assertEquals("as:1", events.log.get(0));
        assertNull(events.parsed.get(0));
    }

    @Test
    public void headerNameIsMatchedCaseInsensitively() {
        header("arc-seal", "arc-seal: i=1; cv=pass; " + SEAL_TAGS);
        header("Arc-Message-Signature", "Arc-Message-Signature: i=1; " + SEAL_TAGS);
        header("ARC-AUTHENTICATION-RESULTS", "ARC-AUTHENTICATION-RESULTS: i=1; spf=pass");
        assertEquals(3, events.log.size());
    }

    @Test
    public void nonArcHeadersAndNullAreIgnored() {
        header("From", "From: i=1@example.com");
        parser.header(null);
        assertTrue(events.log.isEmpty());
    }

    @Test
    public void lineWithoutColonIsScannedWhole() {
        parser.header(new DkimMessageParser.RawHeader("ARC-Authentication-Results",
                "i=9; spf=pass".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("aar:9", events.log.get(0));
    }

    @Test
    public void endHeadersSignalsOnceAndStopsFurtherHeaders() {
        parser.endHeaders();
        parser.endHeaders();
        assertEquals(1, events.ends);
        header("ARC-Seal", "ARC-Seal: i=1; cv=pass; " + SEAL_TAGS);
        assertTrue(events.log.isEmpty());
    }

    @Test
    public void resetAllowsAnotherMessage() {
        parser.endHeaders();
        parser.reset();
        header("ARC-Seal", "ARC-Seal: i=1; cv=pass; " + SEAL_TAGS);
        parser.endHeaders();
        assertEquals(2, events.ends);
        assertEquals("as:1", events.log.get(0));
    }
}
