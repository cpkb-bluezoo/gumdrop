/*
 * HttpMethodSafetyTest.java
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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link HttpMethodSafety} (RFC 9001 0-RTT eligibility).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpMethodSafetyTest {

    @Test
    public void safeMethodsAreEarlyDataEligible() {
        assertTrue(HttpMethodSafety.isEarlyDataEligible("GET"));
        assertTrue(HttpMethodSafety.isEarlyDataEligible("HEAD"));
        assertTrue(HttpMethodSafety.isEarlyDataEligible("OPTIONS"));
        assertTrue(HttpMethodSafety.isEarlyDataEligible("TRACE"));
    }

    @Test
    public void methodMatchingIsCaseInsensitive() {
        assertTrue(HttpMethodSafety.isEarlyDataEligible("get"));
        assertTrue(HttpMethodSafety.isEarlyDataEligible("Head"));
    }

    @Test
    public void unsafeMethodsAreNotEarlyDataEligible() {
        assertFalse(HttpMethodSafety.isEarlyDataEligible("POST"));
        assertFalse(HttpMethodSafety.isEarlyDataEligible("PUT"));
        assertFalse(HttpMethodSafety.isEarlyDataEligible("PATCH"));
        assertFalse(HttpMethodSafety.isEarlyDataEligible("DELETE"));
        assertFalse(HttpMethodSafety.isEarlyDataEligible("CONNECT"));
    }

    @Test
    public void nullMethodIsNotEligible() {
        assertFalse(HttpMethodSafety.isEarlyDataEligible(null));
    }
}
