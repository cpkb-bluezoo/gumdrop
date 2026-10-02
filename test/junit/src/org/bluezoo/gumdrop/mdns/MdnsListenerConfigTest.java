/*
 * MdnsListenerConfigTest.java
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

package org.bluezoo.gumdrop.mdns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.net.InetAddress;

import org.junit.Test;

/**
 * Configuration surface of {@link MdnsListener}: defaults, fluent
 * overrides and the owning-server link, none of which touch a socket.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MdnsListenerConfigTest {

    @Test
    public void defaults() {
        MdnsListener l = new MdnsListener();
        assertEquals(5353, l.getPort());
        assertEquals("mdns", l.getDescription());
        assertFalse(l.isBound());
        assertNull(l.getServer());
    }

    @Test
    public void fluentOverridesReturnSameInstance() throws Exception {
        MdnsListener l = new MdnsListener();
        assertSame(l, l.port(5454));
        assertEquals(5454, l.getPort());
        assertSame(l, l.bindWildcard());
        assertSame(l, l.addresses(InetAddress.getLoopbackAddress()));
        assertSame(l, l.secure(false));
        l.setPort(1234);
        assertEquals(1234, l.getPort());
        l.setServer(null);
        assertNull(l.getServer());
    }
}
