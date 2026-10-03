/*
 * ConnectCandidatesTest.java
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


package org.bluezoo.gumdrop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link ConnectCandidates}: RFC 8305 section 4 ordering
 * (address families alternate, starting with the first address's family)
 * and advancing through the list after failed attempts.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ConnectCandidatesTest {

    private static InetAddress addr(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    private static List<String> drain(ConnectCandidates c) {
        List<String> out = new ArrayList<String>();
        InetAddress a;
        while ((a = c.next()) != null) {
            out.add(a.getHostAddress());
        }
        return out;
    }

    @Test
    public void familiesAlternateStartingWithTheFirstFamily() throws Exception {
        ConnectCandidates c = new ConnectCandidates(Arrays.asList(
                addr("2001:db8::1"), addr("2001:db8::2"),
                addr("192.0.2.1"), addr("192.0.2.2")));
        assertEquals(Arrays.asList("2001:db8:0:0:0:0:0:1", "192.0.2.1",
                "2001:db8:0:0:0:0:0:2", "192.0.2.2"), drain(c));
    }

    @Test
    public void leftoverAddressesOfOneFamilyFollowInOrder() throws Exception {
        ConnectCandidates c = new ConnectCandidates(Arrays.asList(
                addr("192.0.2.1"), addr("192.0.2.2"), addr("192.0.2.3"),
                addr("2001:db8::1")));
        assertEquals(Arrays.asList("192.0.2.1", "2001:db8:0:0:0:0:0:1",
                "192.0.2.2", "192.0.2.3"), drain(c));
    }

    @Test
    public void singleAddressHasNoFallback() throws Exception {
        ConnectCandidates c = new ConnectCandidates(Arrays.asList(addr("192.0.2.1")));
        assertTrue(c.hasNext());
        assertEquals(addr("192.0.2.1"), c.next());
        assertFalse(c.hasNext());
        assertNull(c.next());
    }

    @Test
    public void emptyListYieldsNothing() {
        ConnectCandidates c = new ConnectCandidates(new ArrayList<InetAddress>());
        assertFalse(c.hasNext());
        assertNull(c.next());
    }
}
