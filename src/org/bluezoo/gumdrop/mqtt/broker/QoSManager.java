/*
 * QoSManager.java
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

package org.bluezoo.gumdrop.mqtt.broker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bluezoo.gumdrop.mqtt.codec.QoS;
import org.bluezoo.gumdrop.mqtt.store.MQTTMessageContent;

/**
 * Tracks in-flight QoS 1 and QoS 2 message state for a single MQTT session.
 *
 * <p>QoS 1 flow: PUBLISH → PUBACK<br>
 * QoS 2 flow: PUBLISH → PUBREC → PUBREL → PUBCOMP
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class QoSManager {

    /** State of a QoS 2 exchange. */
    public enum QoS2State {
        WAIT_PUBREC,
        WAIT_PUBREL,
        WAIT_PUBCOMP
    }

    /**
     * An in-flight message awaiting acknowledgment.
     */
    public static class InFlightMessage {
        private final int packetId;
        private final String topic;
        private final MQTTMessageContent content;
        private final QoS qos;
        private volatile QoS2State qos2State;
        private volatile int retryCount;

        public InFlightMessage(int packetId, String topic,
                               MQTTMessageContent content, QoS qos) {
            this.packetId = packetId;
            this.topic = topic;
            this.content = content;
            this.qos = qos;
            if (qos == QoS.EXACTLY_ONCE) {
                this.qos2State = QoS2State.WAIT_PUBREC;
            }
        }

        public int getPacketId() { return packetId; }
        public String getTopic() { return topic; }
        public MQTTMessageContent getContent() { return content; }
        public QoS getQoS() { return qos; }
        public QoS2State getQoS2State() { return qos2State; }
        public void setQoS2State(QoS2State state) { this.qos2State = state; }
        public int getRetryCount() { return retryCount; }
        public void incrementRetryCount() { retryCount++; }
    }

    private final AtomicInteger nextPacketId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, InFlightMessage> outbound =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, InFlightMessage> inbound =
            new ConcurrentHashMap<>();

    /**
     * Allocates the next packet identifier (1-65535, wrapping).
     */
    public int nextPacketId() {
        int id;
        do {
            id = nextPacketId.getAndUpdate(v -> (v >= 65535) ? 1 : v + 1);
        } while (outbound.containsKey(id) || inbound.containsKey(id));
        return id;
    }

    /**
     * Registers an outbound in-flight message (we published, awaiting ack).
     */
    public void trackOutbound(InFlightMessage msg) {
        outbound.put(msg.getPacketId(), msg);
    }

    /**
     * Called when PUBACK is received for a QoS 1 outbound message.
     *
     * @return the completed message, or null if not found
     */
    public InFlightMessage completeQoS1Outbound(int packetId) {
        return outbound.remove(packetId);
    }

    /**
     * Called when PUBREC is received for a QoS 2 outbound message.
     * Transitions to WAIT_PUBCOMP state.
     *
     * @return the in-flight message, or null
     */
    public InFlightMessage receivedPubRec(int packetId) {
        InFlightMessage msg = outbound.get(packetId);
        if (msg != null && msg.getQoS() == QoS.EXACTLY_ONCE) {
            msg.setQoS2State(QoS2State.WAIT_PUBCOMP);
        }
        return msg;
    }

    /**
     * Called when PUBCOMP is received, completing a QoS 2 outbound exchange.
     *
     * @return the completed message, or null
     */
    public InFlightMessage completePubComp(int packetId) {
        return outbound.remove(packetId);
    }

    /**
     * Registers an inbound QoS 2 message (peer published, we sent PUBREC).
     */
    public void trackInboundQoS2(InFlightMessage msg) {
        msg.setQoS2State(QoS2State.WAIT_PUBREL);
        inbound.put(msg.getPacketId(), msg);
    }

    /**
     * Called when PUBREL is received for an inbound QoS 2 message.
     * Completes the exchange and returns the message for delivery.
     *
     * @return the message to deliver, or null
     */
    public InFlightMessage receivedPubRel(int packetId) {
        return inbound.remove(packetId);
    }

    /**
     * Returns whether a QoS 2 inbound message is already tracked
     * (duplicate detection).
     */
    public boolean isInboundQoS2Tracked(int packetId) {
        return inbound.containsKey(packetId);
    }

    /**
     * Returns the number of outbound in-flight messages.
     */
    public int outboundCount() {
        return outbound.size();
    }

    /**
     * Returns the number of inbound QoS 2 messages awaiting PUBREL.
     */
    public int inboundCount() {
        return inbound.size();
    }

    /**
     * Returns all outbound in-flight messages (for session resume/retry).
     */
    public Map<Integer, InFlightMessage> getOutboundMessages() {
        return outbound;
    }

    /**
     * Clears all tracked state.
     */
    public void clear() {
        outbound.clear();
        inbound.clear();
    }
}
