/*
 * Tls13DeferredDispatch.java
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

package org.bluezoo.gumdrop.tls;

import org.bluezoo.gumdrop.tls.HandshakeAsyncScheduler;

/**
 * Routes {@link TlsEventSink} callbacks either directly or through pre-allocated
 * deferral slots on a connection's {@link HandshakeAsyncScheduler}. A single
 * {@link HandshakeEngine#processMessage} can emit many {@code handshakeDataReady}
 * calls (a full server flight), so argument-bearing callbacks use a fixed slot
 * array reset before each batch step rather than allocating a new consumer per call.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class Tls13DeferredDispatch {

    private static final int MAX_HANDSHAKE_DATA_SLOTS = 12;
    private static final int MAX_PROTOCOL_ERROR_SLOTS = 2;
    private static final int MAX_KEY_UPDATE_SLOTS = 2;

    interface Target {
        void deliverHandshakeData(byte[] data);

        void deliverHandshakeSecretsReady();

        void deliverApplicationSecretsReady();

        void deliverKeyUpdate(KeyUpdateDirection direction, byte[] newSecret);

        void deliverProtocolError(TlsProtocolError error);

        void deliverPeerClosed();
    }

    private final HandshakeAsyncScheduler scheduler;
    private final Target target;

    private int handshakeDataSlotIndex;
    private int protocolErrorSlotIndex;
    private int keyUpdateSlotIndex;

    private final HandshakeDataSlot[] handshakeDataSlots = new HandshakeDataSlot[MAX_HANDSHAKE_DATA_SLOTS];
    private final ProtocolErrorSlot[] protocolErrorSlots = new ProtocolErrorSlot[MAX_PROTOCOL_ERROR_SLOTS];
    private final KeyUpdateSlot[] keyUpdateSlots = new KeyUpdateSlot[MAX_KEY_UPDATE_SLOTS];

    private final Runnable handshakeSecretsReadyTask = new Runnable() {
        @Override
        public void run() {
            target.deliverHandshakeSecretsReady();
        }
    };
    private final Runnable applicationSecretsReadyTask = new Runnable() {
        @Override
        public void run() {
            target.deliverApplicationSecretsReady();
        }
    };
    private final Runnable peerClosedTask = new Runnable() {
        @Override
        public void run() {
            target.deliverPeerClosed();
        }
    };

    Tls13DeferredDispatch(HandshakeAsyncScheduler scheduler, Target target) {
        this.scheduler = scheduler;
        this.target = target;
        for (int i = 0; i < handshakeDataSlots.length; i++) {
            handshakeDataSlots[i] = new HandshakeDataSlot();
        }
        for (int i = 0; i < protocolErrorSlots.length; i++) {
            protocolErrorSlots[i] = new ProtocolErrorSlot();
        }
        for (int i = 0; i < keyUpdateSlots.length; i++) {
            keyUpdateSlots[i] = new KeyUpdateSlot();
        }
    }

    void resetSlots() {
        handshakeDataSlotIndex = 0;
        protocolErrorSlotIndex = 0;
        keyUpdateSlotIndex = 0;
    }

    void handshakeDataReady(byte[] data) {
        if (!scheduler.isDeferring()) {
            target.deliverHandshakeData(data);
            return;
        }
        HandshakeDataSlot slot = nextHandshakeDataSlot();
        slot.data = data;
        scheduler.dispatch(slot);
    }

    void handshakeSecretsReady() {
        if (!scheduler.isDeferring()) {
            target.deliverHandshakeSecretsReady();
            return;
        }
        scheduler.dispatch(handshakeSecretsReadyTask);
    }

    void applicationSecretsReady() {
        if (!scheduler.isDeferring()) {
            target.deliverApplicationSecretsReady();
            return;
        }
        scheduler.dispatch(applicationSecretsReadyTask);
    }

    void applicationTrafficSecretUpdated(KeyUpdateDirection direction, byte[] newSecret) {
        if (!scheduler.isDeferring()) {
            target.deliverKeyUpdate(direction, newSecret);
            return;
        }
        KeyUpdateSlot slot = nextKeyUpdateSlot();
        slot.direction = direction;
        slot.newSecret = newSecret;
        scheduler.dispatch(slot);
    }

    void protocolError(TlsProtocolError error) {
        if (!scheduler.isDeferring()) {
            target.deliverProtocolError(error);
            return;
        }
        ProtocolErrorSlot slot = nextProtocolErrorSlot();
        slot.error = error;
        scheduler.dispatch(slot);
    }

    void peerClosed() {
        if (!scheduler.isDeferring()) {
            target.deliverPeerClosed();
            return;
        }
        scheduler.dispatch(peerClosedTask);
    }

    private HandshakeDataSlot nextHandshakeDataSlot() {
        if (handshakeDataSlotIndex >= handshakeDataSlots.length) {
            throw new IllegalStateException("TLS 1.3 handshake deferred output exceeded slot capacity");
        }
        return handshakeDataSlots[handshakeDataSlotIndex++];
    }

    private ProtocolErrorSlot nextProtocolErrorSlot() {
        if (protocolErrorSlotIndex >= protocolErrorSlots.length) {
            throw new IllegalStateException("TLS 1.3 protocol-error deferred output exceeded slot capacity");
        }
        return protocolErrorSlots[protocolErrorSlotIndex++];
    }

    private KeyUpdateSlot nextKeyUpdateSlot() {
        if (keyUpdateSlotIndex >= keyUpdateSlots.length) {
            throw new IllegalStateException("TLS 1.3 key-update deferred output exceeded slot capacity");
        }
        return keyUpdateSlots[keyUpdateSlotIndex++];
    }

    private final class HandshakeDataSlot implements Runnable {
        byte[] data;

        @Override
        public void run() {
            target.deliverHandshakeData(data);
        }
    }

    private final class ProtocolErrorSlot implements Runnable {
        TlsProtocolError error;

        @Override
        public void run() {
            target.deliverProtocolError(error);
        }
    }

    private final class KeyUpdateSlot implements Runnable {
        KeyUpdateDirection direction;
        byte[] newSecret;

        @Override
        public void run() {
            target.deliverKeyUpdate(direction, newSecret);
        }
    }
}
