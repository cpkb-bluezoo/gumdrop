/*
 * Tls12HandshakeConfig.java
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

/**
 * Configuration for one {@link Tls12HandshakeEngine} instance: role,
 * identity, trust, and negotiation preferences. Mutable, plain
 * getters/setters -- built once per connection and handed to the engine's
 * constructor, not shared or reused across handshakes.
 *
 * <p>Deliberately separate from {@link HandshakeConfig} (TLS 1.3), though
 * several fields reuse exactly the same types -- {@link ServerCredentials},
 * {@link ServerCredentialsResolver}, {@link ClientAuthPolicy} -- since
 * those concepts (identity, SNI dispatch, mTLS policy) don't differ
 * between TLS versions. No {@code namedGroups} field (this engine only
 * ever speaks secp256r1 ECDHE) and no {@link HandshakeMode}/QUIC concept
 * (TLS 1.2 has no QUIC mapping).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Tls12HandshakeConfig {

    private final HandshakeRole role;

    private ServerCredentials serverCredentials;
    private ServerCredentialsResolver serverCredentialsResolver;
    private X509TrustManager trustManager;
    private String serverName;
    private boolean verifyHostname = true;
    private List<String> applicationProtocols = new ArrayList<String>();
    private List<Tls12CipherSuite> cipherSuites = defaultCipherSuites();

    // Client-certificate authentication (mTLS).
    private ClientAuthPolicy clientAuthPolicy = ClientAuthPolicy.NONE;
    private X509TrustManager clientTrustManager;
    private ServerCredentials clientCredentials;

    // RFC 5077 session ticket resumption.
    private TicketKeys ticketKeys;
    private Tls12ClientTicketStore clientTicketStore;

    /**
     * DTLS-only (RFC 6347 section 4.2.1): cookie echoed in ClientHello2.
     * {@code null} means an empty cookie on the wire when {@link #dtlsTransport}
     * is true; ignored entirely when {@link #dtlsTransport} is false.
     */
    private byte[] dtlsCookie;
    /** DTLS-only: reuse this ClientHello random on cookie retry (RFC 6347). */
    private byte[] dtlsClientRandom;

    /**
     * When true, ClientHello uses the DTLS wire format (cookie field after
     * session_id). False for TCP TLS 1.2.
     */
    private boolean dtlsTransport;

    /**
     * Creates a configuration for one side of a handshake.
     *
     * @param role client or server
     */
    public Tls12HandshakeConfig(HandshakeRole role) {
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
     * responds with an empty certificate list (RFC 5246 section 7.4.6
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
     * (server), in preference order (RFC 7301).
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
     * Returns the cipher suites to offer/accept, in preference order.
     *
     * @return the cipher suites
     */
    public List<Tls12CipherSuite> getCipherSuites() {
        return cipherSuites;
    }

    /**
     * Sets the cipher suites to offer/accept, in preference order.
     *
     * @param cipherSuites the cipher suites
     */
    public void setCipherSuites(List<Tls12CipherSuite> cipherSuites) {
        this.cipherSuites = cipherSuites;
    }

    /**
     * Returns the server's session-ticket encryption keyring (RFC 5077).
     * Server role only. Ticket issuance is automatic -- once this is
     * non-null, every completed full (non-resumed) handshake whose client
     * advertised {@code SessionTicket} support sends a
     * {@code NewSessionTicket} immediately after the server's own
     * {@code Finished}.
     *
     * @return the ticket keyring, or null to never accept or issue tickets
     */
    public TicketKeys getTicketKeys() {
        return ticketKeys;
    }

    /**
     * Sets the server's session-ticket encryption keyring. Server role
     * only.
     *
     * @param ticketKeys the ticket keyring, or null to never accept or issue tickets
     */
    public void setTicketKeys(TicketKeys ticketKeys) {
        this.ticketKeys = ticketKeys;
    }

    /**
     * Returns the shared client-side ticket cache consulted to offer
     * resumption. Client role only. {@code null} disables offering
     * resumption entirely -- the {@code SessionTicket} extension is
     * omitted, not just sent empty.
     *
     * @return the client ticket store, or null for a full handshake every time
     */
    public Tls12ClientTicketStore getClientTicketStore() {
        return clientTicketStore;
    }

    /**
     * Sets the shared client-side ticket cache. Client role only.
     *
     * @param clientTicketStore the client ticket store, or null to disable
     *        offering resumption
     */
    public void setClientTicketStore(Tls12ClientTicketStore clientTicketStore) {
        this.clientTicketStore = clientTicketStore;
    }

    /**
     * Returns the DTLS cookie to include in a ClientHello, or {@code null}
     * for TCP (RFC 5246) where no cookie field exists.
     *
     * @return the cookie bytes, or null
     */
    public byte[] getDtlsCookie() {
        return dtlsCookie;
    }

    /**
     * Sets the DTLS cookie for ClientHello (RFC 6347 section 4.2.1).
     * {@code null} omits the field on TCP connections.
     *
     * @param dtlsCookie the cookie, or null
     */
    public void setDtlsCookie(byte[] dtlsCookie) {
        this.dtlsCookie = dtlsCookie;
    }

    /**
     * Returns the ClientHello random to reuse after HelloVerifyRequest.
     *
     * @return the preserved random, or null to generate a fresh one
     */
    public byte[] getDtlsClientRandom() {
        return dtlsClientRandom;
    }

    /**
     * Preserves the ClientHello random across a DTLS cookie retry.
     *
     * @param dtlsClientRandom the random from the first ClientHello, or null
     */
    public void setDtlsClientRandom(byte[] dtlsClientRandom) {
        this.dtlsClientRandom = dtlsClientRandom;
    }

    /**
     * Returns whether this handshake uses DTLS ClientHello framing.
     *
     * @return true for DTLS, false for TCP
     */
    public boolean isDtlsTransport() {
        return dtlsTransport;
    }

    /**
     * Sets whether ClientHello uses DTLS framing (cookie field).
     *
     * @param dtlsTransport true for DTLS
     */
    public void setDtlsTransport(boolean dtlsTransport) {
        this.dtlsTransport = dtlsTransport;
    }

    private static List<Tls12CipherSuite> defaultCipherSuites() {
        List<Tls12CipherSuite> suites = new ArrayList<Tls12CipherSuite>();
        suites.add(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256);
        suites.add(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256);
        suites.add(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256);
        suites.add(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256);
        suites.add(Tls12CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384);
        suites.add(Tls12CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384);
        return suites;
    }

}
