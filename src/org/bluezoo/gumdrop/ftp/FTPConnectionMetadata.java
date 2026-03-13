/*
 * FTPConnectionMetadata.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import java.net.InetSocketAddress;
import java.security.cert.Certificate;

/**
 * Provides comprehensive metadata about an active FTP connection.
 * Tracks the protocol state defined in RFC 959 section 3: data representation
 * type (section 3.1.1), file structure (section 3.1.2), and transmission
 * mode (section 3.4).
 *
 * <p>It includes network information, security status, authentication details,
 * FTP protocol state, and connection lifecycle information.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class FTPConnectionMetadata {

    private InetSocketAddress clientAddress;
    private InetSocketAddress serverAddress;
    private boolean secureConnection;
    private Certificate[] clientCertificates;
    private String cipherSuite;
    private String protocolVersion;
    private final long connectionStartTimeMillis;
    private final String connectorDescription;

    // Authentication and session state
    private boolean authenticated;
    private String authenticatedUser;
    private String currentDirectory;
    private FTPTransferMode transferMode;
    private FTPTransferType transferType;

    // RFC 959 section 3.1.1.4: byte size for TYPE L
    private int localByteSize = 8;

    // Data connection state
    private String dataHost;
    private int dataPort;
    private boolean passiveMode;
    
    // SITE command response (for multi-line responses)
    private String siteCommandResponse;

    /**
     * Transfer modes for FTP data connections.
     * RFC 959 section 3.4.
     */
    public enum FTPTransferMode {
        /** Stream mode (default). RFC 959 section 3.4.1. */
        STREAM,
        /** Block mode. RFC 959 section 3.4.2. */
        BLOCK,
        /** Compressed mode. RFC 959 section 3.4.3. */
        COMPRESSED
    }

    /**
     * Transfer types for FTP data representation.
     * RFC 959 section 3.1.1.
     */
    public enum FTPTransferType {
        /** ASCII type (default, MUST be accepted). RFC 959 section 3.1.1.1. */
        ASCII,
        /** Image type (binary). RFC 959 section 3.1.1.3. */
        BINARY,
        /** EBCDIC type. RFC 959 section 3.1.1.2. */
        EBCDIC,
        /** Local type with byte size. RFC 959 section 3.1.1.4. */
        LOCAL
    }

    /**
     * Constructs a new FTPConnectionMetadata instance.
     *
     * @param clientAddress the remote client's socket address
     * @param serverAddress the local server's socket address
     * @param secureConnection true if the connection is currently secured with TLS/SSL
     * @param clientCertificates client X.509 certificates if mutual TLS is used, null otherwise
     * @param cipherSuite the TLS cipher suite in use, null if not secure
     * @param protocolVersion the TLS protocol version (e.g., "TLSv1.2"), null if not secure
     * @param connectionStartTimeMillis the timestamp when the connection was established
     * @param connectorDescription a description of the connector (e.g., "ftp", "ftps")
     */
    public FTPConnectionMetadata(
            InetSocketAddress clientAddress,
            InetSocketAddress serverAddress,
            boolean secureConnection,
            Certificate[] clientCertificates,
            String cipherSuite,
            String protocolVersion,
            long connectionStartTimeMillis,
            String connectorDescription) {
        this.clientAddress = clientAddress;
        this.serverAddress = serverAddress;
        this.secureConnection = secureConnection;
        this.clientCertificates = clientCertificates != null ? clientCertificates.clone() : null;
        this.cipherSuite = cipherSuite;
        this.protocolVersion = protocolVersion;
        this.connectionStartTimeMillis = connectionStartTimeMillis;
        this.connectorDescription = connectorDescription;
        
        // RFC 959 section 3: default transfer parameters
        this.authenticated = false;
        this.authenticatedUser = null;
        this.currentDirectory = "/";
        this.transferMode = FTPTransferMode.STREAM;   // RFC 959 section 3.4.1
        this.transferType = FTPTransferType.ASCII;     // RFC 959 section 3.1.1.1
        this.passiveMode = false;
    }

    // Network and security getters

    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    public InetSocketAddress getServerAddress() {
        return serverAddress;
    }

    public boolean isSecureConnection() {
        return secureConnection;
    }

    public Certificate[] getClientCertificates() {
        return clientCertificates != null ? clientCertificates.clone() : null;
    }

    public String getCipherSuite() {
        return cipherSuite;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public long getConnectionStartTimeMillis() {
        return connectionStartTimeMillis;
    }

    public String getConnectorDescription() {
        return connectorDescription;
    }

    // Authentication and session state getters

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    public String getCurrentDirectory() {
        return currentDirectory;
    }

    public FTPTransferMode getTransferMode() {
        return transferMode;
    }

    public FTPTransferType getTransferType() {
        return transferType;
    }

    // Data connection state getters

    public String getDataHost() {
        return dataHost;
    }

    public int getDataPort() {
        return dataPort;
    }

    public boolean isPassiveMode() {
        return passiveMode;
    }

    // Package-private setters for FTPConnection to update state

    void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    void setAuthenticatedUser(String authenticatedUser) {
        this.authenticatedUser = authenticatedUser;
    }

    void setCurrentDirectory(String currentDirectory) {
        this.currentDirectory = currentDirectory;
    }

    void setTransferMode(FTPTransferMode transferMode) {
        this.transferMode = transferMode;
    }

    void setTransferType(FTPTransferType transferType) {
        this.transferType = transferType;
    }

    void setClientAddress(InetSocketAddress clientAddress) {
        this.clientAddress = clientAddress;
    }

    void setServerAddress(InetSocketAddress serverAddress) {
        this.serverAddress = serverAddress;
    }

    void setSecureConnection(boolean secureConnection) {
        this.secureConnection = secureConnection;
    }

    void setClientCertificates(Certificate[] clientCertificates) {
        this.clientCertificates = clientCertificates != null ? clientCertificates.clone() : null;
    }

    void setCipherSuite(String cipherSuite) {
        this.cipherSuite = cipherSuite;
    }

    void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /**
     * Returns the byte size for TYPE L transfers (RFC 959 section 3.1.1.4).
     *
     * @return the local byte size, default 8
     */
    public int getLocalByteSize() {
        return localByteSize;
    }

    void setLocalByteSize(int localByteSize) {
        this.localByteSize = localByteSize;
    }

    void setDataConnection(String dataHost, int dataPort, boolean passiveMode) {
        this.dataHost = dataHost;
        this.dataPort = dataPort;
        this.passiveMode = passiveMode;
    }

    /**
     * Convenience method to get connection duration in milliseconds.
     * @return how long the connection has been active
     */
    public long getConnectionDurationMillis() {
        return System.currentTimeMillis() - connectionStartTimeMillis;
    }

    /**
     * Convenience method to check if the connection is on a standard FTP port (21).
     * @return true if the server port is 21
     */
    public boolean isStandardFTPPort() {
        return serverAddress != null && serverAddress.getPort() == 21;
    }

    /**
     * Convenience method to check if the connection is on a standard FTPS port (990).
     * @return true if the server port is 990
     */
    public boolean isSecureFTPPort() {
        return serverAddress != null && serverAddress.getPort() == 990;
    }
    
    /**
     * Sets a custom response message for SITE commands.
     * <p>
     * This allows handlers to provide multi-line responses for SITE
     * commands like SITE QUOTA.
     * 
     * @param response the response message (may contain newlines)
     */
    public void setSiteCommandResponse(String response) {
        this.siteCommandResponse = response;
    }
    
    /**
     * Gets the custom SITE command response, if set.
     * 
     * @return the response message, or null if not set
     */
    public String getSiteCommandResponse() {
        return siteCommandResponse;
    }
    
    /**
     * Clears the SITE command response.
     */
    void clearSiteCommandResponse() {
        this.siteCommandResponse = null;
    }

}
