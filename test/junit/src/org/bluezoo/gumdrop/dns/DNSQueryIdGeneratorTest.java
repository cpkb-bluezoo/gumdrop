/*
 * DNSQueryIdGeneratorTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.dns;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class DNSQueryIdGeneratorTest {

    @Test
    public void testAllocateAvoidsInUse() {
        Set<Integer> inUse = new HashSet<>();
        inUse.add(42);
        int id = DNSQueryIdGenerator.allocate(inUse);
        assertNotEquals(42, id);
        assertTrue(id >= 1 && id <= 65535);
    }

    @Test
    public void testAllocateNotSequential() {
        int first = DNSQueryIdGenerator.allocate(new HashSet<Integer>());
        int second = DNSQueryIdGenerator.allocate(new HashSet<Integer>());
        assertFalse("IDs should not be trivially sequential",
                second == first + 1 || second == first);
    }

    @Test
    public void testSyntheticInRange() {
        for (int i = 0; i < 100; i++) {
            int id = DNSQueryIdGenerator.allocateSynthetic();
            assertTrue(id >= 1 && id <= 65535);
        }
    }
}
