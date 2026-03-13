/*
 * QuicTransportFactoryTest.java
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

package org.bluezoo.gumdrop.quic;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QuicTransportFactory} early data (0-RTT) support.
 * RFC 9250 section 4.5.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QuicTransportFactoryTest {

    @Test
    public void testEarlyDataDisabledByDefault() {
        QuicTransportFactory factory = new QuicTransportFactory();
        assertFalse("0-RTT should be disabled by default",
                factory.isEarlyDataEnabled());
    }

    @Test
    public void testSetEarlyDataEnabled() {
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setEarlyDataEnabled(true);
        assertTrue("0-RTT should be enabled after setter",
                factory.isEarlyDataEnabled());
    }

    @Test
    public void testSetEarlyDataDisabled() {
        QuicTransportFactory factory = new QuicTransportFactory();
        factory.setEarlyDataEnabled(true);
        factory.setEarlyDataEnabled(false);
        assertFalse("0-RTT should be disabled after toggle",
                factory.isEarlyDataEnabled());
    }
}
