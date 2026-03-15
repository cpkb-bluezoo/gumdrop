/*
 * MQTTClient.java
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

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.ClientEndpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.TCPTransportFactory;
import org.bluezoo.gumdrop.mqtt.codec.ConnectPacket;
import org.bluezoo.gumdrop.mqtt.codec.MQTTVersion;
import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.InMemoryMessageStore;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageStore;

/**
 * High-level MQTT client facade.
 *
 * <p>Provides a simple API for connecting to MQTT brokers. Internally
 * creates a {@link TCPTransportFactory}, {@link ClientEndpoint}, and
 * {@link MQTTClientProtocolHandler}, following the same pattern as
 * {@code SMTPClient}.
 *
 * <h4>Usage</h4>
 * <pre>{@code
 * MQTTClient client = new MQTTClient("broker.example.com", 1883);
 * client.setClientId("myClient");
 * client.connect(new MQTTClientCallback() {
 *     public void connected(boolean sessionPresent, int returnCode) {
 *         client.subscribe("sensors/#", QoS.AT_LEAST_ONCE);
 *     }
 *     public void connectionLost(Exception cause) { ... }
 *     public void subscribeAcknowledged(int id, int[] qos) { ... }
 *     public void publishComplete(int id) { ... }
 * }, (topic, payload, qos, retain) -> {
 *     System.out.println("Received: " + topic);
 * });
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see MQTTClientProtocolHandler
 * @see MQTTClientCallback
 */
public class MQTTClient {

    private final String host;
    private final InetAddress hostAddress;
    private final int port;
    private final SelectorLoop selectorLoop;

    private boolean secure;
    private SSLContext sslContext;
    private X509TrustManager trustManager;
    private Path keystoreFile;
    private String keystorePass;

    private MQTTVersion version = MQTTVersion.V3_1_1;
    private String clientId;
    private boolean cleanSession = true;
    private int keepAlive = 60;
    private String username;
    private byte[] password;

    // Will
    private String willTopic;
    private byte[] willPayload;
    private QoS willQoS;
    private boolean willRetain;

    private MQTTMessageStore messageStore;
    private TCPTransportFactory transportFactory;
    private ClientEndpoint clientEndpoint;
    private MQTTClientProtocolHandler protocolHandler;

    public MQTTClient(String host, int port) {
        this(null, host, port);
    }

    public MQTTClient(SelectorLoop selectorLoop, String host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = host;
        this.hostAddress = null;
        this.port = port;
    }

    public MQTTClient(InetAddress host, int port) {
        this(null, host, port);
    }

    public MQTTClient(SelectorLoop selectorLoop, InetAddress host, int port) {
        this.selectorLoop = selectorLoop;
        this.host = null;
        this.hostAddress = host;
        this.port = port;
    }

    // ── Configuration ──

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public void setSSLContext(SSLContext context) {
        this.sslContext = context;
    }

    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    public void setKeystoreFile(Path path) {
        this.keystoreFile = path;
    }

    public void setKeystorePass(String pass) {
        this.keystorePass = pass;
    }

    public void setVersion(MQTTVersion version) {
        this.version = version;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }

    public void setKeepAlive(int keepAlive) {
        this.keepAlive = keepAlive;
    }

    public void setCredentials(String username, String password) {
        this.username = username;
        this.password = password != null
                ? password.getBytes(StandardCharsets.UTF_8) : null;
    }

    public void setMessageStore(MQTTMessageStore messageStore) {
        this.messageStore = messageStore;
    }

    public void setWill(String topic, byte[] payload, QoS qos, boolean retain) {
        this.willTopic = topic;
        this.willPayload = payload;
        this.willQoS = qos;
        this.willRetain = retain;
    }

    // ── Connection ──

    /**
     * Connects to the MQTT broker.
     *
     * @param callback lifecycle callback
     * @param messageListener message delivery callback
     * @throws IOException if the transport cannot be created
     */
    public void connect(MQTTClientCallback callback,
                        MQTTMessageListener messageListener) throws IOException {
        ConnectPacket connectPacket = buildConnectPacket();

        if (messageStore == null) {
            messageStore = new InMemoryMessageStore();
        }
        protocolHandler = new MQTTClientProtocolHandler(
                connectPacket, callback, messageListener, messageStore);

        transportFactory = new TCPTransportFactory();
        if (secure) {
            transportFactory.setSecure(true);
        }
        if (sslContext != null) {
            transportFactory.setSSLContext(sslContext);
        }
        if (trustManager != null) {
            transportFactory.setTrustManager(trustManager);
        }
        if (keystoreFile != null) {
            transportFactory.setKeystoreFile(keystoreFile);
            if (keystorePass != null) {
                transportFactory.setKeystorePass(keystorePass);
            }
        }
        transportFactory.start();

        if (selectorLoop != null) {
            if (hostAddress != null) {
                clientEndpoint = new ClientEndpoint(transportFactory,
                        selectorLoop, hostAddress, port);
            } else {
                clientEndpoint = new ClientEndpoint(transportFactory,
                        selectorLoop, host, port);
            }
        } else {
            if (hostAddress != null) {
                clientEndpoint = new ClientEndpoint(transportFactory,
                        hostAddress, port);
            } else {
                clientEndpoint = new ClientEndpoint(transportFactory,
                        host, port);
            }
        }
        clientEndpoint.connect(protocolHandler);
    }

    // ── Operations ──

    /**
     * Publishes a message to a topic.
     *
     * @return the packet ID (0 for QoS 0)
     */
    public int publish(String topic, byte[] payload, QoS qos, boolean retain) {
        checkConnected();
        return protocolHandler.publish(topic, payload, qos, retain);
    }

    /**
     * Publishes a UTF-8 string message.
     */
    public int publish(String topic, String payload, QoS qos) {
        return publish(topic,
                payload != null ? payload.getBytes(StandardCharsets.UTF_8) : new byte[0],
                qos, false);
    }

    /**
     * Subscribes to a single topic filter.
     *
     * @return the packet ID
     */
    public int subscribe(String topicFilter, QoS qos) {
        return subscribe(new String[]{topicFilter}, new QoS[]{qos});
    }

    /**
     * Subscribes to multiple topic filters.
     *
     * @return the packet ID
     */
    public int subscribe(String[] topicFilters, QoS[] qosLevels) {
        checkConnected();
        return protocolHandler.subscribe(topicFilters, qosLevels);
    }

    /**
     * Unsubscribes from topic filters.
     *
     * @return the packet ID
     */
    public int unsubscribe(String... topicFilters) {
        checkConnected();
        return protocolHandler.unsubscribe(topicFilters);
    }

    /**
     * Sends DISCONNECT and closes the connection.
     */
    public void disconnect() {
        if (protocolHandler != null) {
            protocolHandler.disconnect();
        }
    }

    private ConnectPacket buildConnectPacket() {
        ConnectPacket pkt = new ConnectPacket();
        pkt.setVersion(version);
        pkt.setClientId(clientId != null ? clientId : "");
        pkt.setCleanSession(cleanSession);
        pkt.setKeepAlive(keepAlive);
        if (username != null) {
            pkt.setUsername(username);
        }
        if (password != null) {
            pkt.setPassword(password);
        }
        if (willTopic != null) {
            pkt.setWillFlag(true);
            pkt.setWillTopic(willTopic);
            pkt.setWillPayload(willPayload);
            pkt.setWillQoS(willQoS != null ? willQoS : QoS.AT_MOST_ONCE);
            pkt.setWillRetain(willRetain);
        }
        return pkt;
    }

    private void checkConnected() {
        if (protocolHandler == null || protocolHandler.getEndpoint() == null) {
            throw new IllegalStateException("Not connected");
        }
    }
}
