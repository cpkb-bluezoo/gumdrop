/*
 * H3ResponseHeaderAsciiTest.java
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


package org.bluezoo.gumdrop.http.h3;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * An HTTP/3 response header whose value is not US-ASCII is rejected as the
 * handler sets it (RFC 9110 section 5.5), for both final and informational
 * responses, so the mistake surfaces at the call that made it.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class H3ResponseHeaderAsciiTest {

    private H3ServerFlowTest.Fixture fixtureWithRequest() {
        H3ServerFlowTest.Fixture f = new H3ServerFlowTest.Fixture();
        H3Stream stream = f.open();
        H3ServerFlowTest.feed(stream, H3ServerFlowTest.headersFrame(":method", "GET",
                ":scheme", "https", ":path", "/", ":authority", "x"));
        stream.readFinished();
        return f;
    }

    @Test
    public void nonAsciiResponseHeaderIsRejected() {
        H3ServerFlowTest.Fixture f = fixtureWithRequest();
        f.rec.state.status(200);
        try {
            f.rec.state.header("x-custom", "caf\u00e9");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("x-custom"));
        }
    }

    @Test
    public void nonAsciiInformationalHeaderIsRejected() {
        H3ServerFlowTest.Fixture f = fixtureWithRequest();
        f.rec.state.status(103);
        try {
            f.rec.state.header("x-custom", "caf\u00e9");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("x-custom"));
        }
    }

    @Test
    public void asciiResponseHeaderIsAccepted() {
        H3ServerFlowTest.Fixture f = fixtureWithRequest();
        f.rec.state.status(200);
        f.rec.state.header("x-custom", "plain");
        f.rec.state.endHeaders();
    }
}
