/*
 * Tls12DeferredDispatch.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

/**
 * Same role as {@link Tls13DeferredDispatch} for {@link Tls12EventSink} callbacks.
 */
final class Tls12DeferredDispatch {

    private static final int MAX_HANDSHAKE_DATA_SLOTS = 12;
    private static final int MAX_PROTOCOL_ERROR_SLOTS = 2;

    interface Target {
        void deliverHandshakeData(byte[] data);

        void deliverKeysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client,
                DirectionalKeyMaterial server);

        void deliverChangeCipherSpec();

        void deliverHandshakeComplete();

        void deliverProtocolError(TlsProtocolError error);
    }

    private final HandshakeAsyncScheduler scheduler;
    private final Target target;

    private int handshakeDataSlotIndex;
    private int protocolErrorSlotIndex;

    private final HandshakeDataSlot[] handshakeDataSlots = new HandshakeDataSlot[MAX_HANDSHAKE_DATA_SLOTS];
    private final ProtocolErrorSlot[] protocolErrorSlots = new ProtocolErrorSlot[MAX_PROTOCOL_ERROR_SLOTS];

    private final Runnable changeCipherSpecTask = new Runnable() {
        @Override
        public void run() {
            target.deliverChangeCipherSpec();
        }
    };
    private final Runnable handshakeCompleteTask = new Runnable() {
        @Override
        public void run() {
            target.deliverHandshakeComplete();
        }
    };

    Tls12DeferredDispatch(HandshakeAsyncScheduler scheduler, Target target) {
        this.scheduler = scheduler;
        this.target = target;
        for (int i = 0; i < handshakeDataSlots.length; i++) {
            handshakeDataSlots[i] = new HandshakeDataSlot();
        }
        for (int i = 0; i < protocolErrorSlots.length; i++) {
            protocolErrorSlots[i] = new ProtocolErrorSlot();
        }
    }

    void resetSlots() {
        handshakeDataSlotIndex = 0;
        protocolErrorSlotIndex = 0;
        keysReadySlotIndex = 0;
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

    void keysReady(Tls12CipherSuite cipher, DirectionalKeyMaterial client, DirectionalKeyMaterial server) {
        if (!scheduler.isDeferring()) {
            target.deliverKeysReady(cipher, client, server);
            return;
        }
        KeysReadySlot slot = nextKeysReadySlot();
        slot.cipher = cipher;
        slot.client = client;
        slot.server = server;
        scheduler.dispatch(slot);
    }

    void sendChangeCipherSpec() {
        if (!scheduler.isDeferring()) {
            target.deliverChangeCipherSpec();
            return;
        }
        scheduler.dispatch(changeCipherSpecTask);
    }

    void handshakeComplete() {
        if (!scheduler.isDeferring()) {
            target.deliverHandshakeComplete();
            return;
        }
        scheduler.dispatch(handshakeCompleteTask);
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

    private HandshakeDataSlot nextHandshakeDataSlot() {
        if (handshakeDataSlotIndex >= handshakeDataSlots.length) {
            throw new IllegalStateException("TLS 1.2 handshake deferred output exceeded slot capacity");
        }
        return handshakeDataSlots[handshakeDataSlotIndex++];
    }

    private ProtocolErrorSlot nextProtocolErrorSlot() {
        if (protocolErrorSlotIndex >= protocolErrorSlots.length) {
            throw new IllegalStateException("TLS 1.2 protocol-error deferred output exceeded slot capacity");
        }
        return protocolErrorSlots[protocolErrorSlotIndex++];
    }

    private int keysReadySlotIndex;
    private final KeysReadySlot keysReadySlot = new KeysReadySlot();

    private KeysReadySlot nextKeysReadySlot() {
        if (keysReadySlotIndex != 0) {
            throw new IllegalStateException("TLS 1.2 keysReady deferred output exceeded slot capacity");
        }
        keysReadySlotIndex = 1;
        return keysReadySlot;
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

    private final class KeysReadySlot implements Runnable {
        Tls12CipherSuite cipher;
        DirectionalKeyMaterial client;
        DirectionalKeyMaterial server;

        @Override
        public void run() {
            target.deliverKeysReady(cipher, client, server);
        }
    }
}
