/*
 * QuicTlsDeferredDispatch.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.quic.tls;

import org.bluezoo.gumdrop.quic.packet.TransportParameters;
import org.bluezoo.gumdrop.tls.SessionTicket;
import org.bluezoo.gumdrop.tls.TlsProtocolError;

/**
 * Pre-allocated deferral slots for {@link QuicTlsClientEngine} and
 * {@link QuicTlsServerEngine} listener callbacks.
 */
final class QuicTlsDeferredDispatch {

    private static final int MAX_CRYPTO_DATA_SLOTS = 12;
    private static final int MAX_TRANSPORT_PARAMETERS_SLOTS = 2;
    private static final int MAX_PROTOCOL_ERROR_SLOTS = 2;
    private static final int MAX_SESSION_TICKET_SLOTS = 4;

    interface Target {
        void deliverCryptoData(EncryptionLevel level, long offset, byte[] data);

        void deliverHandshakeSecretsAvailable();

        void deliverHandshakeFinished();

        void deliverTransportParameters(TransportParameters parameters);

        void deliverCryptoProcessingFailed(EncryptionLevel level, Throwable cause);

        void deliverEarlySecretsAvailable();

        void deliverEarlyDataOutcomeKnown(boolean accepted);

        void deliverNewSessionTicketReceived(SessionTicket ticket);
    }

    private final QuicHandshakeAsyncOffload offload;
    private final Target target;

    private int initialCryptoSlotIndex;
    private int handshakeCryptoSlotIndex;
    private int transportParametersSlotIndex;
    private int protocolErrorSlotIndex;
    private int sessionTicketSlotIndex;

    private final InitialCryptoSlot[] initialCryptoSlots = new InitialCryptoSlot[MAX_CRYPTO_DATA_SLOTS];
    private final HandshakeCryptoSlot[] handshakeCryptoSlots = new HandshakeCryptoSlot[MAX_CRYPTO_DATA_SLOTS];
    private final TransportParametersSlot[] transportParametersSlots =
            new TransportParametersSlot[MAX_TRANSPORT_PARAMETERS_SLOTS];
    private final ProtocolErrorSlot[] protocolErrorSlots = new ProtocolErrorSlot[MAX_PROTOCOL_ERROR_SLOTS];
    private final SessionTicketSlot[] sessionTicketSlots = new SessionTicketSlot[MAX_SESSION_TICKET_SLOTS];

    private final Runnable handshakeSecretsAvailableTask = new Runnable() {
        @Override
        public void run() {
            target.deliverHandshakeSecretsAvailable();
        }
    };
    private final Runnable handshakeFinishedTask = new Runnable() {
        @Override
        public void run() {
            target.deliverHandshakeFinished();
        }
    };
    private final Runnable earlySecretsAvailableTask = new Runnable() {
        @Override
        public void run() {
            target.deliverEarlySecretsAvailable();
        }
    };
    private final EarlyDataOutcomeSlot earlyDataOutcomeSlot = new EarlyDataOutcomeSlot();

    QuicTlsDeferredDispatch(QuicHandshakeAsyncOffload offload, Target target) {
        this.offload = offload;
        this.target = target;
        for (int i = 0; i < initialCryptoSlots.length; i++) {
            initialCryptoSlots[i] = new InitialCryptoSlot();
        }
        for (int i = 0; i < handshakeCryptoSlots.length; i++) {
            handshakeCryptoSlots[i] = new HandshakeCryptoSlot();
        }
        for (int i = 0; i < transportParametersSlots.length; i++) {
            transportParametersSlots[i] = new TransportParametersSlot();
        }
        for (int i = 0; i < protocolErrorSlots.length; i++) {
            protocolErrorSlots[i] = new ProtocolErrorSlot();
        }
        for (int i = 0; i < sessionTicketSlots.length; i++) {
            sessionTicketSlots[i] = new SessionTicketSlot();
        }
    }

    void resetSlots() {
        initialCryptoSlotIndex = 0;
        handshakeCryptoSlotIndex = 0;
        transportParametersSlotIndex = 0;
        protocolErrorSlotIndex = 0;
        sessionTicketSlotIndex = 0;
        earlyDataOutcomeSlot.used = false;
    }

    void initialCryptoData(long offset, byte[] data) {
        if (!offload.isDeferring()) {
            target.deliverCryptoData(EncryptionLevel.INITIAL, offset, data);
            return;
        }
        InitialCryptoSlot slot = nextInitialCryptoSlot();
        slot.offset = offset;
        slot.data = data;
        offload.dispatch(slot);
    }

    void handshakeCryptoData(long offset, byte[] data) {
        if (!offload.isDeferring()) {
            target.deliverCryptoData(EncryptionLevel.HANDSHAKE, offset, data);
            return;
        }
        HandshakeCryptoSlot slot = nextHandshakeCryptoSlot();
        slot.offset = offset;
        slot.data = data;
        offload.dispatch(slot);
    }

    void handshakeSecretsAvailable() {
        if (!offload.isDeferring()) {
            target.deliverHandshakeSecretsAvailable();
            return;
        }
        offload.dispatch(handshakeSecretsAvailableTask);
    }

    void handshakeFinished() {
        if (!offload.isDeferring()) {
            target.deliverHandshakeFinished();
            return;
        }
        offload.dispatch(handshakeFinishedTask);
    }

    void transportParameters(TransportParameters parameters) {
        if (!offload.isDeferring()) {
            target.deliverTransportParameters(parameters);
            return;
        }
        TransportParametersSlot slot = nextTransportParametersSlot();
        slot.parameters = parameters;
        offload.dispatch(slot);
    }

    void protocolError(EncryptionLevel level, TlsProtocolError error) {
        if (!offload.isDeferring()) {
            target.deliverCryptoProcessingFailed(level, new java.io.IOException(error.toString()));
            return;
        }
        ProtocolErrorSlot slot = nextProtocolErrorSlot();
        slot.level = level;
        slot.error = error;
        offload.dispatch(slot);
    }

    void earlySecretsAvailable() {
        if (!offload.isDeferring()) {
            target.deliverEarlySecretsAvailable();
            return;
        }
        offload.dispatch(earlySecretsAvailableTask);
    }

    void earlyDataOutcomeKnown(boolean accepted) {
        if (!offload.isDeferring()) {
            target.deliverEarlyDataOutcomeKnown(accepted);
            return;
        }
        if (earlyDataOutcomeSlot.used) {
            throw new IllegalStateException("QUIC early-data outcome deferred output exceeded slot capacity");
        }
        earlyDataOutcomeSlot.used = true;
        earlyDataOutcomeSlot.accepted = accepted;
        offload.dispatch(earlyDataOutcomeSlot);
    }

    void newSessionTicketReceived(SessionTicket ticket) {
        if (!offload.isDeferring()) {
            target.deliverNewSessionTicketReceived(ticket);
            return;
        }
        SessionTicketSlot slot = nextSessionTicketSlot();
        slot.ticket = ticket;
        offload.dispatch(slot);
    }

    private InitialCryptoSlot nextInitialCryptoSlot() {
        if (initialCryptoSlotIndex >= initialCryptoSlots.length) {
            throw new IllegalStateException("QUIC Initial CRYPTO deferred output exceeded slot capacity");
        }
        return initialCryptoSlots[initialCryptoSlotIndex++];
    }

    private HandshakeCryptoSlot nextHandshakeCryptoSlot() {
        if (handshakeCryptoSlotIndex >= handshakeCryptoSlots.length) {
            throw new IllegalStateException("QUIC Handshake CRYPTO deferred output exceeded slot capacity");
        }
        return handshakeCryptoSlots[handshakeCryptoSlotIndex++];
    }

    private TransportParametersSlot nextTransportParametersSlot() {
        if (transportParametersSlotIndex >= transportParametersSlots.length) {
            throw new IllegalStateException("QUIC transport-parameters deferred output exceeded slot capacity");
        }
        return transportParametersSlots[transportParametersSlotIndex++];
    }

    private ProtocolErrorSlot nextProtocolErrorSlot() {
        if (protocolErrorSlotIndex >= protocolErrorSlots.length) {
            throw new IllegalStateException("QUIC protocol-error deferred output exceeded slot capacity");
        }
        return protocolErrorSlots[protocolErrorSlotIndex++];
    }

    private SessionTicketSlot nextSessionTicketSlot() {
        if (sessionTicketSlotIndex >= sessionTicketSlots.length) {
            throw new IllegalStateException("QUIC session-ticket deferred output exceeded slot capacity");
        }
        return sessionTicketSlots[sessionTicketSlotIndex++];
    }

    private final class InitialCryptoSlot implements Runnable {
        long offset;
        byte[] data;

        @Override
        public void run() {
            target.deliverCryptoData(EncryptionLevel.INITIAL, offset, data);
        }
    }

    private final class HandshakeCryptoSlot implements Runnable {
        long offset;
        byte[] data;

        @Override
        public void run() {
            target.deliverCryptoData(EncryptionLevel.HANDSHAKE, offset, data);
        }
    }

    private final class TransportParametersSlot implements Runnable {
        TransportParameters parameters;

        @Override
        public void run() {
            target.deliverTransportParameters(parameters);
        }
    }

    private final class ProtocolErrorSlot implements Runnable {
        EncryptionLevel level;
        TlsProtocolError error;

        @Override
        public void run() {
            target.deliverCryptoProcessingFailed(level, new java.io.IOException(error.toString()));
        }
    }

    private final class SessionTicketSlot implements Runnable {
        SessionTicket ticket;

        @Override
        public void run() {
            target.deliverNewSessionTicketReceived(ticket);
        }
    }

    private final class EarlyDataOutcomeSlot implements Runnable {
        boolean used;
        boolean accepted;

        @Override
        public void run() {
            target.deliverEarlyDataOutcomeKnown(accepted);
        }
    }
}
