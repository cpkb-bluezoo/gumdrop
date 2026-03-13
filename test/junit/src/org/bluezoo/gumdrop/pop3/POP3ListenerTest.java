/*
 * POP3ListenerTest.java
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

package org.bluezoo.gumdrop.pop3;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link POP3Listener} configuration properties.
 */
public class POP3ListenerTest {

    @Test
    public void testDefaultExpireDays() {
        POP3Listener listener = new POP3Listener();
        assertEquals(-1, listener.getExpireDays());
    }

    @Test
    public void testSetExpireDays() {
        POP3Listener listener = new POP3Listener();
        listener.setExpireDays(30);
        assertEquals(30, listener.getExpireDays());
    }

    @Test
    public void testSetExpireNever() {
        POP3Listener listener = new POP3Listener();
        listener.setExpireDays(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, listener.getExpireDays());
    }

    @Test
    public void testDefaultLoginDelay() {
        POP3Listener listener = new POP3Listener();
        assertEquals(0, listener.getLoginDelayMs());
    }

    @Test
    public void testSetLoginDelay() {
        POP3Listener listener = new POP3Listener();
        listener.setLoginDelayMs(5000);
        assertEquals(5000, listener.getLoginDelayMs());
    }

    @Test
    public void testDefaultPipelining() {
        POP3Listener listener = new POP3Listener();
        assertFalse(listener.isEnablePipelining());
    }

    @Test
    public void testSetPipelining() {
        POP3Listener listener = new POP3Listener();
        listener.setEnablePipelining(true);
        assertTrue(listener.isEnablePipelining());
    }
}
