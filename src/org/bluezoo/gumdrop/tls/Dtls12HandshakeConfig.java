/*
 * Dtls12HandshakeConfig.java
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

import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * DTLS 1.2 configuration: composes a {@link Tls12HandshakeConfig} with
 * DTLS-specific cookie-exchange and fragmentation settings.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class Dtls12HandshakeConfig {

    private static final int DEFAULT_MAX_FRAGMENT_SIZE = 1024;

    private final Tls12HandshakeConfig base;
    private boolean requireCookie;
    private byte[] cookieSecret;
    private int maxFragmentSize = DEFAULT_MAX_FRAGMENT_SIZE;

    /**
     * Creates a DTLS configuration wrapping {@code base}.
     *
     * @param base the underlying TLS 1.2 handshake configuration
     */
    public Dtls12HandshakeConfig(Tls12HandshakeConfig base) {
        if (base == null) {
            throw new NullPointerException("base");
        }
        this.base = base;
        base.setDtlsTransport(true);
    }

    public Tls12HandshakeConfig getBase() {
        return base;
    }

    /**
     * Returns whether the server requires a valid RFC 6347 cookie before
     * allocating handshake state. Defaults to false -- enable for
     * internet-facing deployments open to arbitrary peers.
     *
     * @return true if cookie exchange is mandatory
     */
    public boolean isRequireCookie() {
        return requireCookie;
    }

    public void setRequireCookie(boolean requireCookie) {
        this.requireCookie = requireCookie;
    }

    public byte[] getCookieSecret() {
        return cookieSecret;
    }

    public void setCookieSecret(byte[] cookieSecret) {
        this.cookieSecret = cookieSecret;
    }

    public int getMaxFragmentSize() {
        return maxFragmentSize;
    }

    public void setMaxFragmentSize(int maxFragmentSize) {
        this.maxFragmentSize = maxFragmentSize;
    }

    public HandshakeRole getRole() {
        return base.getRole();
    }

    public ServerCredentials getServerCredentials() {
        return base.getServerCredentials();
    }

    public void setServerCredentials(ServerCredentials serverCredentials) {
        base.setServerCredentials(serverCredentials);
    }

    public ServerCredentialsResolver getServerCredentialsResolver() {
        return base.getServerCredentialsResolver();
    }

    public void setServerCredentialsResolver(ServerCredentialsResolver serverCredentialsResolver) {
        base.setServerCredentialsResolver(serverCredentialsResolver);
    }

    public ClientAuthPolicy getClientAuthPolicy() {
        return base.getClientAuthPolicy();
    }

    public void setClientAuthPolicy(ClientAuthPolicy clientAuthPolicy) {
        base.setClientAuthPolicy(clientAuthPolicy);
    }

    public X509TrustManager getClientTrustManager() {
        return base.getClientTrustManager();
    }

    public void setClientTrustManager(X509TrustManager clientTrustManager) {
        base.setClientTrustManager(clientTrustManager);
    }

    public ServerCredentials getClientCredentials() {
        return base.getClientCredentials();
    }

    public void setClientCredentials(ServerCredentials clientCredentials) {
        base.setClientCredentials(clientCredentials);
    }

    public X509TrustManager getTrustManager() {
        return base.getTrustManager();
    }

    public void setTrustManager(X509TrustManager trustManager) {
        base.setTrustManager(trustManager);
    }

    public String getServerName() {
        return base.getServerName();
    }

    public void setServerName(String serverName) {
        base.setServerName(serverName);
    }

    public List<String> getApplicationProtocols() {
        return base.getApplicationProtocols();
    }

    public void setApplicationProtocols(List<String> applicationProtocols) {
        base.setApplicationProtocols(applicationProtocols);
    }

    public List<Tls12CipherSuite> getCipherSuites() {
        return base.getCipherSuites();
    }

    public void setCipherSuites(List<Tls12CipherSuite> cipherSuites) {
        base.setCipherSuites(cipherSuites);
    }

    public TicketKeys getTicketKeys() {
        return base.getTicketKeys();
    }

    public void setTicketKeys(TicketKeys ticketKeys) {
        base.setTicketKeys(ticketKeys);
    }

    public Tls12ClientTicketStore getClientTicketStore() {
        return base.getClientTicketStore();
    }

    public void setClientTicketStore(Tls12ClientTicketStore clientTicketStore) {
        base.setClientTicketStore(clientTicketStore);
    }

    /**
     * Returns a copy of the base config suitable for one engine instance.
     *
     * @return a fresh {@link Tls12HandshakeConfig}
     */
    public Tls12HandshakeConfig copyBaseForEngine() {
        Tls12HandshakeConfig copy = new Tls12HandshakeConfig(base.getRole());
        copy.setDtlsTransport(true);
        copy.setServerCredentials(base.getServerCredentials());
        copy.setServerCredentialsResolver(base.getServerCredentialsResolver());
        copy.setClientAuthPolicy(base.getClientAuthPolicy());
        copy.setClientTrustManager(base.getClientTrustManager());
        copy.setClientCredentials(base.getClientCredentials());
        copy.setTrustManager(base.getTrustManager());
        copy.setServerName(base.getServerName());
        copy.setVerifyHostname(base.isVerifyHostname());
        copy.setApplicationProtocols(base.getApplicationProtocols());
        copy.setCipherSuites(base.getCipherSuites());
        copy.setTicketKeys(base.getTicketKeys());
        copy.setClientTicketStore(base.getClientTicketStore());
        copy.setDtlsCookie(base.getDtlsCookie());
        copy.setDtlsClientRandom(base.getDtlsClientRandom());
        return copy;
    }

}
