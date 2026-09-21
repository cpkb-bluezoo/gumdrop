/*
 * HttpResponseTest.java
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

package org.bluezoo.gumdrop.http.client;

import org.bluezoo.gumdrop.http.HttpStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link HttpResponse}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpResponseTest {

    @Test(expected = NullPointerException.class)
    public void statusIsRequired() {
        new HttpResponse(null);
    }

    @Test
    public void successRedirectionAndErrorFlags() {
        assertTrue(new HttpResponse(HttpStatus.OK).isSuccess());
        assertFalse(new HttpResponse(HttpStatus.OK).isError());
        assertFalse(new HttpResponse(HttpStatus.OK).isRedirection());

        assertTrue(new HttpResponse(HttpStatus.MOVED_PERMANENTLY).isRedirection());
        assertFalse(new HttpResponse(HttpStatus.MOVED_PERMANENTLY).isSuccess());

        assertTrue(new HttpResponse(HttpStatus.NOT_FOUND).isError());
        assertTrue(new HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR).isError());
    }

    @Test
    public void redirectLocationAndChain() {
        HttpResponse first = new HttpResponse(HttpStatus.FOUND, "/a");
        HttpResponse second = new HttpResponse(HttpStatus.FOUND, "/b", first);
        HttpResponse third = new HttpResponse(HttpStatus.OK, null, second);

        assertEquals("/a", second.getPreviousResponse().getRedirectLocation());
        assertEquals(2, third.getRedirectCount());
        assertEquals(0, first.getRedirectCount());
    }

    @Test
    public void toStringIncludesRedirectTarget() {
        HttpResponse r = new HttpResponse(HttpStatus.FOUND, "https://example.com/next");
        assertTrue(r.toString().contains("https://example.com/next"));
    }
}
