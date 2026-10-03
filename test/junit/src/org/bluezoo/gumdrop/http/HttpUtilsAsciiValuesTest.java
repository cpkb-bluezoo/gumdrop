/*
 * HttpUtilsAsciiValuesTest.java
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


package org.bluezoo.gumdrop.http;

import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Tests for {@link HttpUtils#requireAsciiFieldValues(Headers)}.
 *
 * <p>RFC 9110 section 5.5 allows octets above 0x7F in a field value only as
 * obsolete text, to be treated as opaque by the recipient, and says new fields
 * should be US-ASCII. A response header whose value holds such a character is
 * therefore rejected before it is sent rather than guessed at or re-encoded.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpUtilsAsciiValuesTest {

    @Test
    public void asciiValuesAreAccepted() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, "content-type", "text/plain; charset=utf-8");
        HeaderFields.add(h, "x-tab", "a\tb");
        HttpUtils.requireAsciiFieldValues(h);
    }

    @Test
    public void emptyHeadersAreAccepted() {
        HttpUtils.requireAsciiFieldValues(new ArrayList<Header>());
    }

    @Test
    public void nullValuesAreIgnored() {
        List<Header> h = new ArrayList<Header>();
        h.add(new Header("x-null", null));
        HttpUtils.requireAsciiFieldValues(h);
    }

    @Test
    public void nonAsciiValueIsRejectedNamingTheHeader() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, "x-custom", "café");
        try {
            HttpUtils.requireAsciiFieldValues(h);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("x-custom"));
            assertTrue(e.getMessage(), e.getMessage().contains("US-ASCII"));
        }
    }

    @Test
    public void latin1RangeCharacterIsRejectedToo() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, "x-custom", "\u0080");
        try {
            HttpUtils.requireAsciiFieldValues(h);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void theValueItselfIsNotEchoedInTheMessage() {
        List<Header> h = new ArrayList<Header>();
        HeaderFields.add(h, "x-secret", "s3crét");
        try {
            HttpUtils.requireAsciiFieldValues(h);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), !e.getMessage().contains("s3cr"));
        }
    }
}
