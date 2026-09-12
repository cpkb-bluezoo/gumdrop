/*
 * HandshakeConfig.java
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

import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.bluezoo.gumdrop.crypto.NamedGroup;

/**
 * Configuration for one {@link HandshakeEngine} instance: role, identity,
 * trust, and negotiation preferences. Mutable, plain getters/setters --
 * built once per connection and handed to the engine's constructor, not
 * shared or reused across handshakes.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class HandshakeConfig {

    private final HandshakeRole role;

    private HandshakeMode mode = HandshakeMode.QUIC;
    private ServerCredentials serverCredentials;
    private ServerCredentialsResolver serverCredentialsResolver;
    private X509TrustManager trustManager;
    private String serverName;
    private boolean verifyHostname = true;
    private List<String> applicationProtocols = new ArrayList<String>();
    private byte[] localTransportParameters;
    private List<NamedGroup> namedGroups = defaultNamedGroups();
    private List<CipherSuite> cipherSuites = defaultCipherSuites();

    // Client-certificate authentication (mTLS).
    private ClientAuthPolicy clientAuthPolicy = ClientAuthPolicy.NONE;
    private X509TrustManager clientTrustManager;
    private ServerCredentials clientCredentials;

    // Session resumption / PSK / 0-RTT.
    private TicketKeys ticketKeys;
    private SessionTicket sessionTicket;
    private boolean enableEarlyData;
    private int maxEarlyDataSize = 16384;
    private AntiReplay antiReplay;
    private TransportParameterConsistencyChecker transportParameterConsistencyChecker = PERMISSIVE_CHECKER;
    private int earlyDataFreshnessMs = 10000;

    /** DTLS 1.3 server-only HelloRetryRequest cookie hook (RFC 9147 section 5.2). */
    private CookieValidator cookieValidator;

    private static final TransportParameterConsistencyChecker PERMISSIVE_CHECKER =
            new TransportParameterConsistencyChecker() {
                @Override
                public boolean isConsistent(byte[] remembered, byte[] current) {
                    return true;
                }
            };

    /**
     * Creates a configuration for one side of a handshake.
     *
     * @param role client or server
     */
    public HandshakeConfig(HandshakeRole role) {
        this.role = role;
    }

    /**
     * Returns this configuration's role.
     *
     * @return client or server
     */
    public HandshakeRole getRole() {
        return role;
    }

    /**
     * Returns the transport this handshake's bytes are bound to. Defaults
     * to {@link HandshakeMode#QUIC}.
     *
     * @return the handshake mode
     */
    public HandshakeMode getMode() {
        return mode;
    }

    /**
     * Sets the transport this handshake's bytes are bound to.
     *
     * @param mode the handshake mode
     */
    public void setMode(HandshakeMode mode) {
        this.mode = mode;
    }

    /**
     * Returns the server's identity (certificate chain and private key).
     * Server role only. Ignored in favor of
     * {@link #getServerCredentialsResolver} when that is set.
     *
     * @return the server credentials, or null if not yet set
     */
    public ServerCredentials getServerCredentials() {
        return serverCredentials;
    }

    /**
     * Sets the server's identity. Server role only.
     *
     * @param serverCredentials the server credentials
     */
    public void setServerCredentials(ServerCredentials serverCredentials) {
        this.serverCredentials = serverCredentials;
    }

    /**
     * Returns the SNI-based server credential resolver. Server role only.
     * When set, takes priority over {@link #getServerCredentials}'s fixed
     * value with no fallback to it.
     *
     * @return the resolver, or null to always use {@link #getServerCredentials}
     */
    public ServerCredentialsResolver getServerCredentialsResolver() {
        return serverCredentialsResolver;
    }

    /**
     * Sets the SNI-based server credential resolver. Server role only.
     *
     * @param serverCredentialsResolver the resolver, or null to always
     *        use {@link #getServerCredentials}
     */
    public void setServerCredentialsResolver(ServerCredentialsResolver serverCredentialsResolver) {
        this.serverCredentialsResolver = serverCredentialsResolver;
    }

    /**
     * Returns the client-certificate authentication policy. Server role
     * only. Defaults to {@link ClientAuthPolicy#NONE}.
     *
     * @return the client authentication policy
     */
    public ClientAuthPolicy getClientAuthPolicy() {
        return clientAuthPolicy;
    }

    /**
     * Sets the client-certificate authentication policy. Server role only.
     *
     * @param clientAuthPolicy the client authentication policy
     */
    public void setClientAuthPolicy(ClientAuthPolicy clientAuthPolicy) {
        this.clientAuthPolicy = (clientAuthPolicy != null) ? clientAuthPolicy : ClientAuthPolicy.NONE;
    }

    /**
     * Returns the trust manager used to verify the client's certificate
     * chain. Server role only, consulted only when
     * {@link #getClientAuthPolicy} isn't {@link ClientAuthPolicy#NONE}.
     *
     * @return the client trust manager, or null if not set
     */
    public X509TrustManager getClientTrustManager() {
        return clientTrustManager;
    }

    /**
     * Sets the trust manager used to verify the client's certificate
     * chain. Server role only.
     *
     * @param clientTrustManager the client trust manager
     */
    public void setClientTrustManager(X509TrustManager clientTrustManager) {
        this.clientTrustManager = clientTrustManager;
    }

    /**
     * Returns the certificate chain and private key to present when the
     * server requests client authentication. Client role only. Null
     * responds with an empty certificate list (RFC 8446 section 4.4.2
     * permits this) -- the handshake still proceeds unless the server
     * enforces {@link ClientAuthPolicy#REQUIRE}.
     *
     * @return the client's own credentials, or null to present none
     */
    public ServerCredentials getClientCredentials() {
        return clientCredentials;
    }

    /**
     * Sets the certificate chain and private key to present when the
     * server requests client authentication. Client role only.
     *
     * @param clientCredentials the client's own credentials, or null to present none
     */
    public void setClientCredentials(ServerCredentials clientCredentials) {
        this.clientCredentials = clientCredentials;
    }

    /**
     * Returns the trust manager used to verify the peer's certificate
     * chain. Client role only, for verifying the server's certificate.
     *
     * @return the trust manager, or null if not yet set
     */
    public X509TrustManager getTrustManager() {
        return trustManager;
    }

    /**
     * Sets the trust manager used to verify the peer's certificate
     * chain. Client role only.
     *
     * @param trustManager the trust manager
     */
    public void setTrustManager(X509TrustManager trustManager) {
        this.trustManager = trustManager;
    }

    /**
     * Returns the expected server hostname, sent as SNI and checked
     * against the server's certificate. Client role only.
     *
     * @return the server name, or null if not yet set
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Sets the expected server hostname. Client role only.
     *
     * @param serverName the server name
     */
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    /**
     * Returns whether the server certificate's subject alternative names
     * are checked against {@link #getServerName}. Defaults to true.
     * Independent of whether a server name is sent as SNI at all --
     * disabling this skips only the identity check, not trust chain
     * verification, and not the SNI extension itself.
     *
     * @return true if hostname verification is enabled
     */
    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * Sets whether the server certificate's subject alternative names are
     * checked against {@link #getServerName}. Client role only.
     *
     * @param verifyHostname false to accept any hostname/certificate pairing
     */
    public void setVerifyHostname(boolean verifyHostname) {
        this.verifyHostname = verifyHostname;
    }

    /**
     * Returns the ALPN protocol names to offer (client) or accept
     * (server), in preference order.
     *
     * @return the application protocol names
     */
    public List<String> getApplicationProtocols() {
        return applicationProtocols;
    }

    /**
     * Sets the ALPN protocol names to offer or accept, in preference
     * order.
     *
     * @param applicationProtocols the application protocol names
     */
    public void setApplicationProtocols(List<String> applicationProtocols) {
        this.applicationProtocols = applicationProtocols;
    }

    /**
     * Returns this side's QUIC {@code transport_parameters} extension
     * payload (RFC 9000 section 18), to be sent to the peer. Opaque to
     * this engine -- it only carries these bytes on the wire and reports
     * the peer's own bytes back via
     * {@link TlsEventSink#peerTransportParameters}.
     *
     * @return the local transport parameters bytes
     */
    public byte[] getLocalTransportParameters() {
        return localTransportParameters;
    }

    /**
     * Sets this side's QUIC transport parameters bytes.
     *
     * @param localTransportParameters the local transport parameters bytes
     */
    public void setLocalTransportParameters(byte[] localTransportParameters) {
        this.localTransportParameters = localTransportParameters;
    }

    /**
     * Returns the key-exchange groups to offer/accept, in preference
     * order. Defaults to every group {@link NamedGroup} defines, hybrid
     * PQC group first.
     *
     * @return the named groups
     */
    public List<NamedGroup> getNamedGroups() {
        return namedGroups;
    }

    /**
     * Sets the key-exchange groups to offer/accept, in preference order.
     *
     * @param namedGroups the named groups
     */
    public void setNamedGroups(List<NamedGroup> namedGroups) {
        this.namedGroups = namedGroups;
    }

    /**
     * Returns the cipher suites to offer/accept, in preference order.
     *
     * @return the cipher suites
     */
    public List<CipherSuite> getCipherSuites() {
        return cipherSuites;
    }

    /**
     * Sets the cipher suites to offer/accept, in preference order.
     *
     * @param cipherSuites the cipher suites
     */
    public void setCipherSuites(List<CipherSuite> cipherSuites) {
        this.cipherSuites = cipherSuites;
    }

    /**
     * Returns the server's session-ticket encryption keyring. Server
     * role only. Ticket issuance is automatic -- once this is non-null,
     * every completed handshake sends a {@code NewSessionTicket}
     * immediately after the server's own {@code Finished}.
     *
     * @return the ticket keyring, or null to never issue tickets
     */
    public TicketKeys getTicketKeys() {
        return ticketKeys;
    }

    /**
     * Sets the server's session-ticket encryption keyring. Server role
     * only.
     *
     * @param ticketKeys the ticket keyring, or null to never issue tickets
     */
    public void setTicketKeys(TicketKeys ticketKeys) {
        this.ticketKeys = ticketKeys;
    }

    /**
     * Returns the session ticket to present for resumption. Client role
     * only.
     *
     * @return the session ticket, or null for a full (non-resumed) handshake
     */
    public SessionTicket getSessionTicket() {
        return sessionTicket;
    }

    /**
     * Sets the session ticket to present for resumption. Client role only.
     *
     * @param sessionTicket the session ticket, or null for a full handshake
     */
    public void setSessionTicket(SessionTicket sessionTicket) {
        this.sessionTicket = sessionTicket;
    }

    /**
     * Returns whether 0-RTT early data is offered (client, when a
     * session ticket with early-data support is presented) or accepted
     * (server). Defaults to false. Independent of ticket issuance itself --
     * a server can issue resumption tickets with
     * {@link #getMaxEarlyDataSize} of 0 while still leaving this false.
     *
     * @return true if early data is enabled
     */
    public boolean isEnableEarlyData() {
        return enableEarlyData;
    }

    /**
     * Sets whether 0-RTT early data is offered or accepted.
     *
     * @param enableEarlyData true to enable early data
     */
    public void setEnableEarlyData(boolean enableEarlyData) {
        this.enableEarlyData = enableEarlyData;
    }

    /**
     * Returns the maximum 0-RTT data size minted into new tickets, when
     * {@link #isEnableEarlyData} is true. Server role only. Defaults to
     * 16384.
     *
     * @return the maximum early data size in bytes
     */
    public int getMaxEarlyDataSize() {
        return maxEarlyDataSize;
    }

    /**
     * Sets the maximum 0-RTT data size minted into new tickets.
     *
     * @param maxEarlyDataSize the maximum early data size in bytes
     */
    public void setMaxEarlyDataSize(int maxEarlyDataSize) {
        this.maxEarlyDataSize = maxEarlyDataSize;
    }

    /**
     * Returns the server's 0-RTT anti-replay cache. Server role only.
     *
     * @return the anti-replay cache, or null to accept every 0-RTT attempt
     */
    public AntiReplay getAntiReplay() {
        return antiReplay;
    }

    /**
     * Sets the server's 0-RTT anti-replay cache.
     *
     * @param antiReplay the anti-replay cache, or null to accept every 0-RTT attempt
     */
    public void setAntiReplay(AntiReplay antiReplay) {
        this.antiReplay = antiReplay;
    }

    /**
     * Returns the checker enforcing RFC 9000 section 7.4.1's rule that a
     * server must not accept 0-RTT data unless its current transport
     * parameters are at least as permissive as the ones a ticket
     * remembers. Server role only. Defaults to a permissive checker that
     * always returns true.
     *
     * @return the consistency checker
     */
    public TransportParameterConsistencyChecker getTransportParameterConsistencyChecker() {
        return transportParameterConsistencyChecker;
    }

    /**
     * Sets the transport parameter consistency checker.
     *
     * @param transportParameterConsistencyChecker the checker, or null
     *        to restore the default permissive behavior
     */
    public void setTransportParameterConsistencyChecker(
            TransportParameterConsistencyChecker transportParameterConsistencyChecker) {
        this.transportParameterConsistencyChecker =
                (transportParameterConsistencyChecker != null) ? transportParameterConsistencyChecker : PERMISSIVE_CHECKER;
    }

    /**
     * Returns how fresh a resumed ticket's age must be to accept 0-RTT
     * (RFC 8446 section 8.3's small-tolerance window check) -- distinct
     * from a ticket's own multi-hour/day {@code lifetimeSeconds}, which
     * governs whether resumption is accepted at all, not just 0-RTT.
     * Server role only. Defaults to 10000 (10 seconds).
     *
     * @return the early-data freshness window, milliseconds
     */
    public int getEarlyDataFreshnessMs() {
        return earlyDataFreshnessMs;
    }

    /**
     * Sets the early-data freshness window.
     *
     * @param earlyDataFreshnessMs the freshness window, milliseconds
     */
    public void setEarlyDataFreshnessMs(int earlyDataFreshnessMs) {
        this.earlyDataFreshnessMs = earlyDataFreshnessMs;
    }

    /**
     * Returns the DTLS 1.3 cookie validator, or null if disabled.
     *
     * @return the cookie validator, or null
     */
    public CookieValidator getCookieValidator() {
        return cookieValidator;
    }

    /**
     * Sets the DTLS 1.3 cookie validator. Server role only.
     *
     * @param cookieValidator the validator, or null to disable
     */
    public void setCookieValidator(CookieValidator cookieValidator) {
        this.cookieValidator = cookieValidator;
    }

    private static List<NamedGroup> defaultNamedGroups() {
        List<NamedGroup> groups = new ArrayList<NamedGroup>();
        groups.add(NamedGroup.X25519_MLKEM768);
        groups.add(NamedGroup.X25519);
        groups.add(NamedGroup.SECP256R1);
        groups.add(NamedGroup.SECP384R1);
        return groups;
    }

    private static List<CipherSuite> defaultCipherSuites() {
        List<CipherSuite> suites = new ArrayList<CipherSuite>();
        suites.add(CipherSuite.TLS_AES_128_GCM_SHA256);
        suites.add(CipherSuite.TLS_AES_256_GCM_SHA384);
        suites.add(CipherSuite.TLS_CHACHA20_POLY1305_SHA256);
        return suites;
    }

}
