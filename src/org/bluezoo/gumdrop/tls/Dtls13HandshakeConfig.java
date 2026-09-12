/*
 * Dtls13HandshakeConfig.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.tls;

import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * DTLS 1.3 configuration wrapping {@link HandshakeConfig}.
 */
public final class Dtls13HandshakeConfig {

    private static final int DEFAULT_MAX_FRAGMENT_SIZE = 1024;

    private final HandshakeConfig base;
    private boolean requireCookie;
    private byte[] cookieSecret;
    private int maxFragmentSize = DEFAULT_MAX_FRAGMENT_SIZE;

    public Dtls13HandshakeConfig(HandshakeConfig base) {
        if (base == null) {
            throw new NullPointerException("base");
        }
        this.base = base;
        base.setMode(HandshakeMode.DTLS);
    }

    public HandshakeConfig getBase() {
        return base;
    }

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

    public List<CipherSuite> getCipherSuites() {
        return base.getCipherSuites();
    }

    public void setCipherSuites(List<CipherSuite> cipherSuites) {
        base.setCipherSuites(cipherSuites);
    }

    public List<org.bluezoo.gumdrop.crypto.NamedGroup> getNamedGroups() {
        return base.getNamedGroups();
    }

    public void setNamedGroups(List<org.bluezoo.gumdrop.crypto.NamedGroup> namedGroups) {
        base.setNamedGroups(namedGroups);
    }

    public TicketKeys getTicketKeys() {
        return base.getTicketKeys();
    }

    public void setTicketKeys(TicketKeys ticketKeys) {
        base.setTicketKeys(ticketKeys);
    }

    public SessionTicket getSessionTicket() {
        return base.getSessionTicket();
    }

    public void setSessionTicket(SessionTicket sessionTicket) {
        base.setSessionTicket(sessionTicket);
    }

    public HandshakeConfig copyBaseForEngine() {
        HandshakeConfig copy = new HandshakeConfig(base.getRole());
        copy.setMode(HandshakeMode.DTLS);
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
        copy.setNamedGroups(base.getNamedGroups());
        copy.setTicketKeys(base.getTicketKeys());
        copy.setSessionTicket(base.getSessionTicket());
        copy.setCookieValidator(base.getCookieValidator());
        return copy;
    }
}
