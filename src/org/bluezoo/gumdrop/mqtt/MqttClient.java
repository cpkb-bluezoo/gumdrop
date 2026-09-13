/*
 * MqttClient.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.mqtt;

import java.net.InetAddress;

import org.bluezoo.gumdrop.SelectorLoop;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.mqtt.client.MqttClient} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.mqtt.client.MqttClient
 * @see docs/NAMING-TAXONOMY.md
 */
public class MqttClient extends org.bluezoo.gumdrop.mqtt.client.MqttClient {

    public MqttClient(String host, int port) {
        super(host, port);
    }
    public MqttClient(SelectorLoop selectorLoop, String host, int port) {
        super(selectorLoop, host, port);
    }
    public MqttClient(InetAddress host, int port) {
        super(host, port);
    }
    public MqttClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        super(selectorLoop, host, port);
    }
    public MqttClient(String socketPath) {
        super(socketPath);
    }
    public MqttClient(SelectorLoop selectorLoop, String socketPath) {
        super(selectorLoop, socketPath);
    }
}
