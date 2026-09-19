/*
 * DNSQueryIdGeneratorTest.java
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

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSQueryIdGeneratorTest {

    @Test
    public void testAllocateAvoidsInUse() {
        Set<Integer> inUse = new HashSet<>();
        inUse.add(42);
        int id = DnsQueryIdGenerator.allocate(inUse);
        assertNotEquals(42, id);
        assertTrue(id >= 1 && id <= 65535);
    }

    @Test
    public void testAllocateNotSequential() {
        int first = DnsQueryIdGenerator.allocate(new HashSet<Integer>());
        int second = DnsQueryIdGenerator.allocate(new HashSet<Integer>());
        assertFalse("IDs should not be trivially sequential",
                second == first + 1 || second == first);
    }

    @Test
    public void testSyntheticInRange() {
        for (int i = 0; i < 100; i++) {
            int id = DnsQueryIdGenerator.allocateSynthetic();
            assertTrue(id >= 1 && id <= 65535);
        }
    }
}
