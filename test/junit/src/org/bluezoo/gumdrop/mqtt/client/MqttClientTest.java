/*
 * MqttClientTest.java
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

package org.bluezoo.gumdrop.mqtt.client;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.net.InetAddress;

import org.bluezoo.gumdrop.mqtt.codec.MqttVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.junit.Test;

/**
 * Configuration API and not-connected guards of {@link MqttClient}.
 * Connecting needs a live runtime and is covered by integration tests.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MqttClientTest {

    @Test
    public void fluentConfigurationReturnsSameInstance() throws Exception {
        MqttClient c = new MqttClient("localhost", 1883);
        assertSame(c, c.secure(false));
        assertSame(c, c.keystoreFile(null));
        assertSame(c, c.keystorePass("pw"));
        assertSame(c, c.clientCredentials(null));
        assertSame(c, c.trustManager(null));
        assertSame(c, c.host("example.org"));
        assertSame(c, c.host(InetAddress.getLoopbackAddress()));
        assertSame(c, c.port(1884));
        assertSame(c, c.socketPath("/tmp/none.sock"));
        assertSame(c, c.selectorLoop(null));
        assertSame(c, c.dnsResolver(null));
    }

    @Test
    public void plainSettersAccepted() {
        MqttClient c = new MqttClient();
        c.setVersion(MqttVersion.V5_0);
        c.setClientId("id");
        c.setCleanSession(false);
        c.setKeepAlive(10);
        c.setCredentials("user", "pass");
        c.setCredentials("user", null);
        c.setMessageStore(new InMemoryMessageStore());
        c.setWill("t", new byte[] {1}, QoS.AT_LEAST_ONCE, true);
        c.setSecure(true);
        c.setKeystoreFile(null);
        c.setKeystorePass(null);
        c.setClientCredentials(null);
        c.setTrustManager(null);
    }

    @Test
    public void alternateConstructors() throws Exception {
        new MqttClient(InetAddress.getLoopbackAddress(), 1883);
        new MqttClient("/tmp/none.sock");
        new MqttClient(null, "localhost", 1883);
        new MqttClient(null, InetAddress.getLoopbackAddress(), 1883);
        new MqttClient(null, "/tmp/none.sock");
    }

    @Test
    public void operationsBeforeConnectFail() {
        MqttClient c = new MqttClient();
        try {
            c.publish("t", new byte[0], QoS.AT_MOST_ONCE, false);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // not connected
        }
        try {
            c.publish("t", "x", QoS.AT_MOST_ONCE);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // not connected
        }
        try {
            c.subscribe("t", QoS.AT_MOST_ONCE);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // not connected
        }
        try {
            c.unsubscribe("t");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // not connected
        }
    }

    @Test
    public void disconnectBeforeConnectIsNoOp() {
        new MqttClient().disconnect();
    }
}
