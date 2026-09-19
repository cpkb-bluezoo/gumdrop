/*
 * MqttListenerTest.java
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

package org.bluezoo.gumdrop.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.mqtt.server.MqttServer;
import org.junit.Test;

/**
 * Configuration and handler-creation tests for {@link MqttListener}.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttListenerTest {

    @Test
    public void defaults() {
        MqttListener l = new MqttListener();
        assertEquals(-1, l.getPort());
        assertEquals(1_048_576, l.getMaxPacketSize());
        assertEquals(60, l.getDefaultKeepAlive());
        assertNull(l.getRealm());
        assertNull(l.getServer());
        assertNull(l.getMetrics());
        assertEquals("mqtt", l.getDescription());
    }

    @Test
    public void settersAndFluentConfiguration() {
        MqttListener l = new MqttListener();
        l.setMaxPacketSize(4096);
        l.setDefaultKeepAlive(5);
        BasicRealm realm = new BasicRealm();
        l.setRealm(realm);
        assertEquals(4096, l.getMaxPacketSize());
        assertEquals(5, l.getDefaultKeepAlive());
        assertSame(realm, l.getRealm());
        assertSame(l, l.port(1884));
        assertEquals(1884, l.getPort());
        assertSame(l, l.bindWildcard());
        assertSame(l, l.secure(true));
        assertEquals("mqtts", l.getDescription());
        assertSame(l, l.secure(false));
    }

    @Test
    public void createHandlerUsesServer() {
        MqttListener l = new MqttListener();
        MqttServer server = new MqttServer();
        l.setServer(server);
        assertSame(server, l.getServer());
        ProtocolHandler h = l.createHandler();
        assertNotNull(h);
        assertTrue(h instanceof MqttProtocolHandler);
    }

    @Test
    public void createHandlerWithoutServerFails() {
        try {
            new MqttListener().createHandler();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // no server configured
        }
    }
}
