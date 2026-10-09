/*
 * ImapListener.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.util.CidrNetwork;
import java.util.List;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.auth.GssapiServer;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.quota.QuotaManager;
import java.net.InetAddress;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

/**
 * TCP transport listener for IMAP connections.
 * This endpoint supports both standard IMAP (port 143) and IMAPS (port 993),
 * with transparent SSL/TLS support and STARTTLS capability.
 *
 * <p>This implementation supports IMAP4rev2 as defined in RFC 9051, with
 * the following extensions:
 * <ul>
 *   <li>RFC 2177 - IDLE (push notifications)</li>
 *   <li>RFC 2342 - NAMESPACE</li>
 *   <li>RFC 6851 - MOVE</li>
 *   <li>RFC 9208 - QUOTA</li>
 *   <li>RFC 4978 - COMPRESS=DEFLATE</li>
 *   <li>RFC 6855 - UTF8=ACCEPT</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 - IMAP4rev2</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2177">RFC 2177 - IDLE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc2342">RFC 2342 - NAMESPACE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc6851">RFC 6851 - MOVE</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9208">RFC 9208 - QUOTA</a>
 */
public class ImapListener extends TcpListener {

    private EventLogger events() {
        return eventTelemetry().getLogger(ImapListener.class, ImapProtocolHandler.L10N);
    }

    /**
     * The default IMAP port (cleartext or with STARTTLS).
     */
    public static final int IMAP_DEFAULT_PORT = 143;

    /**
     * The default IMAPS port (implicit TLS).
     */
    public static final int IMAPS_DEFAULT_PORT = 993;

    // Configuration
    protected int port = -1;
    protected Realm realm;
    protected MailboxFactory mailboxFactory;
    protected QuotaManager quotaManager;
    // Timeouts (in milliseconds)
    protected long loginTimeoutMs = 60000;      // 1 minute for login
    protected long commandTimeoutMs = 300000;   // 5 minutes per command

    // Extension support
    protected boolean enableIDLE = true;
    protected boolean enableNAMESPACE = true;
    protected boolean enableQUOTA = true;
    protected boolean enableMOVE = true;
    protected boolean enableCOMPRESS = true;
    protected boolean enableUTF8ACCEPT = true;
    protected boolean enableSORT = true;
    protected boolean enableCONDSTORE = true;
    protected boolean enableQRESYNC = true;
    protected boolean enableOBJECTID = true;
    protected boolean enableNOTIFY = true;
    protected boolean enableMETADATA = true;

    // RFC 2971 — ID command server fields
    protected Map<String, String> serverIdFields;

    // Limits
    protected int maxLineLength = 8192;         // Max command line length
    protected int maxLiteralSize = 25 * 1024 * 1024; // 25MB max literal

    // Security options
    protected boolean allowPlaintextLogin = false;

    // RFC 4752 — GSSAPI/Kerberos authentication
    protected GssapiServer gssapiServer;

    // Back-reference to the owning server (null when used standalone)
    private org.bluezoo.gumdrop.imap.server.ImapServer server;

    private org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider sessionProvider;

    // Metrics for this endpoint (null if telemetry is not enabled)
    private ImapServerMetrics metrics;

    /**
     * Returns a short description of this endpoint.
     */
    @Override
    public String getDescription() {
        return secure ? "imaps" : "imap";
    }

    /**
     * Returns the port number this endpoint is bound to.
     */
    @Override
    public int getPort() {
        return port;
    }

    /**
     * Sets the port. Returns {@code this} for fluent configuration.
     *
     * @param port the port number
     * @return this listener
     */
    public ImapListener port(int port) {
        this.port = port;
        return this;
    }

    @Override
    public ImapListener bindWildcard() {
        super.bindWildcard();
        return this;
    }

    @Override
    public ImapListener addresses(InetAddress... addrs) {
        super.addresses(addrs);
        return this;
    }

    @Override
    public ImapListener secure(boolean flag) {
        super.secure(flag);
        return this;
    }

    @Override
    public ImapListener tls(TlsConfig tls) {
        super.tls(tls);
        return this;
    }

    @Override
    public ImapListener maxConnections(int max) {
        super.maxConnections(max);
        return this;
    }

    @Override
    public ImapListener maxConnectionsPerIP(int max) {
        super.maxConnectionsPerIP(max);
        return this;
    }

    @Override
    public ImapListener rateLimit(String rateLimit) {
        super.rateLimit(rateLimit);
        return this;
    }

    @Override
    public ImapListener maxAuthFailures(int max) {
        super.maxAuthFailures(max);
        return this;
    }

    @Override
    public ImapListener authLockoutTimeMs(long lockoutMs) {
        super.authLockoutTimeMs(lockoutMs);
        return this;
    }

    @Override
    public ImapListener allowedNetworks(List<CidrNetwork> allowedNetworks) {
        super.allowedNetworks(allowedNetworks);
        return this;
    }

    @Override
    public ImapListener blockedNetworks(List<CidrNetwork> blockedNetworks) {
        super.blockedNetworks(blockedNetworks);
        return this;
    }

    @Override
    public ImapListener name(String name) {
        super.name(name);
        return this;
    }

    @Override
    public ImapListener maxNetInSize(int size) {
        super.maxNetInSize(size);
        return this;
    }

    @Override
    public ImapListener maxNetOutSize(int size) {
        super.maxNetOutSize(size);
        return this;
    }

    @Override
    public ImapListener idleTimeoutMs(long idleTimeoutMs) {
        super.idleTimeoutMs(idleTimeoutMs);
        return this;
    }

    @Override
    public ImapListener readTimeoutMs(long readTimeoutMs) {
        super.readTimeoutMs(readTimeoutMs);
        return this;
    }

    @Override
    public ImapListener connectionTimeoutMs(long connectionTimeoutMs) {
        super.connectionTimeoutMs(connectionTimeoutMs);
        return this;
    }

    @Override
    public ImapListener maxDtlsPeers(int max) {
        super.maxDtlsPeers(max);
        return this;
    }

    /**
     * Returns the authentication realm.
     *
     * @return the realm for IMAP authentication, or null if no authentication
     */
    public Realm getRealm() {
        return realm;
    }

    /**
     * Sets the authentication realm for IMAP authentication.
     *
     * @param realm the realm to use for authentication
     * @return this
     */
    public ImapListener realm(Realm realm) {
        this.realm = realm;
        return this;
    }

    /**
     * Returns the GSSAPI server for Kerberos authentication (RFC 4752),
     * or null if GSSAPI is not configured.
     *
     * @return the GSSAPI server, or null
     */
    public GssapiServer getGSSAPIServer() {
        return gssapiServer;
    }

    /**
     * Sets the GSSAPI server for Kerberos authentication (RFC 4752).
     *
     * @param gssapiServer the GSSAPI server
     * @return this
     */
    public ImapListener gssapiServer(GssapiServer gssapiServer) {
        this.gssapiServer = gssapiServer;
        return this;
    }

    /**
     * Configures GSSAPI/Kerberos authentication (RFC 4752) by creating
     * a {@link GssapiServer} from the specified keytab and service
     * principal.
     *
     * <p>This is a convenience method equivalent to calling
     * {@code setGSSAPIServer(new GssapiServer(keytabPath, servicePrincipal))}.
     *
     * @param keytabPath the path to the Kerberos keytab file
     * @param servicePrincipal the service principal name
     *        (e.g. "imap/mail.example.com@EXAMPLE.COM")
     * @throws IOException if the keytab cannot be read or credentials
     *         cannot be acquired
     */
    public ImapListener configureGSSAPI(Path keytabPath, String servicePrincipal)
            throws IOException {
        this.gssapiServer = new GssapiServer(keytabPath, servicePrincipal);
        return this;
    }

    /**
     * Returns the mailbox factory for this endpoint.
     *
     * @return the mailbox factory, or null if not configured
     */
    public MailboxFactory getMailboxFactory() {
        return mailboxFactory;
    }

    /**
     * Sets the mailbox factory for creating mailbox store instances.
     * Each IMAP connection will use this factory to obtain a mail store
     * for the authenticated user.
     *
     * @param mailboxFactory the factory to create mailbox instances
     * @return this
     */
    public ImapListener mailboxFactory(MailboxFactory mailboxFactory) {
        this.mailboxFactory = mailboxFactory;
        return this;
    }

    /**
     * Returns the quota manager for this endpoint.
     *
     * @return the quota manager, or null if quotas are not enabled
     */
    public QuotaManager getQuotaManager() {
        return quotaManager;
    }

    /**
     * Sets the quota manager for enforcing storage quotas.
     * When configured, the IMAP endpoint will:
     * <ul>
     *   <li>Check quotas before APPEND commands</li>
     *   <li>Respond to GETQUOTA and GETQUOTAROOT commands</li>
     *   <li>Allow administrators to use SETQUOTA</li>
     *   <li>Track storage usage after message operations</li>
     * </ul>
     *
     * @param quotaManager the quota manager
     * @return this
     */
    public ImapListener quotaManager(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
        return this;
    }

    /**
     * Returns the login timeout in milliseconds.
     *
     * @return the login timeout in milliseconds
     */
    public long getLoginTimeoutMs() {
        return loginTimeoutMs;
    }

    /**
     * Sets the login timeout in milliseconds.
     * This is the maximum time allowed for authentication.
     *
     * @param loginTimeoutMs the timeout in milliseconds
     * @return this
     */
    public ImapListener loginTimeoutMs(long loginTimeoutMs) {
        this.loginTimeoutMs = loginTimeoutMs;
        return this;
    }

    /**
     * Returns the command timeout in milliseconds.
     *
     * @return the command timeout in milliseconds
     */
    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    /**
     * Sets the command timeout in milliseconds.
     * This is the maximum time for a single command to complete.
     *
     * @param commandTimeoutMs the timeout in milliseconds
     * @return this
     */
    public ImapListener commandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
        return this;
    }

    /**
     * Returns whether the IDLE extension is enabled.
     *
     * @return true if IDLE is enabled
     */
    public boolean isEnableIDLE() {
        return enableIDLE;
    }

    /**
     * Sets whether the IDLE extension is enabled.
     * IDLE provides push notification for mailbox changes.
     *
     * @param enableIDLE true to enable IDLE
     * @return this
     */
    public ImapListener enableIDLE(boolean enableIDLE) {
        this.enableIDLE = enableIDLE;
        return this;
    }

    /**
     * Returns whether the NAMESPACE extension is enabled.
     *
     * @return true if NAMESPACE is enabled
     */
    public boolean isEnableNAMESPACE() {
        return enableNAMESPACE;
    }

    /**
     * Sets whether the NAMESPACE extension is enabled.
     *
     * @param enableNAMESPACE true to enable NAMESPACE
     * @return this
     */
    public ImapListener enableNAMESPACE(boolean enableNAMESPACE) {
        this.enableNAMESPACE = enableNAMESPACE;
        return this;
    }

    /**
     * Returns whether the QUOTA extension is enabled.
     *
     * @return true if QUOTA is enabled
     */
    public boolean isEnableQUOTA() {
        return enableQUOTA;
    }

    /**
     * Sets whether the QUOTA extension is enabled.
     *
     * @param enableQUOTA true to enable QUOTA
     * @return this
     */
    public ImapListener enableQUOTA(boolean enableQUOTA) {
        this.enableQUOTA = enableQUOTA;
        return this;
    }

    /**
     * Returns whether the MOVE extension is enabled.
     *
     * @return true if MOVE is enabled
     */
    public boolean isEnableMOVE() {
        return enableMOVE;
    }

    /**
     * Sets whether the MOVE extension is enabled.
     *
     * @param enableMOVE true to enable MOVE
     * @return this
     */
    public ImapListener enableMOVE(boolean enableMOVE) {
        this.enableMOVE = enableMOVE;
        return this;
    }

    /**
     * Returns whether the COMPRESS=DEFLATE extension is enabled.
     *
     * @return true if COMPRESS=DEFLATE is enabled
     */
    public boolean isEnableCOMPRESS() {
        return enableCOMPRESS;
    }

    /**
     * Sets whether the COMPRESS=DEFLATE extension is enabled (RFC 4978).
     *
     * @param enableCOMPRESS true to allow {@code COMPRESS DEFLATE}
     * @return this
     */
    public ImapListener enableCOMPRESS(boolean enableCOMPRESS) {
        this.enableCOMPRESS = enableCOMPRESS;
        return this;
    }

    /**
     * Returns whether the UTF8=ACCEPT extension is enabled.
     *
     * @return true if UTF8=ACCEPT is enabled
     */
    public boolean isEnableUTF8ACCEPT() {
        return enableUTF8ACCEPT;
    }

    /**
     * Sets whether the UTF8=ACCEPT extension is enabled (RFC 6855).
     *
     * @param enableUTF8ACCEPT true to advertise and allow ENABLE UTF8=ACCEPT
     * @return this
     */
    public ImapListener enableUTF8ACCEPT(boolean enableUTF8ACCEPT) {
        this.enableUTF8ACCEPT = enableUTF8ACCEPT;
        return this;
    }

    /**
     * Returns whether the SORT extension (RFC 5256) is enabled.
     *
     * @return true if SORT is enabled
     */
    public boolean isEnableSORT() {
        return enableSORT;
    }

    /**
     * Sets whether the SORT extension (RFC 5256) is enabled.
     *
     * @param enableSORT true to advertise SORT and I18NLEVEL=1
     * @return this
     */
    public ImapListener enableSORT(boolean enableSORT) {
        this.enableSORT = enableSORT;
        return this;
    }

    /**
     * Returns whether CONDSTORE (RFC 7162) is enabled.
     *
     * @return true if CONDSTORE is enabled
     */
    public boolean isEnableCONDSTORE() {
        return enableCONDSTORE;
    }

    /**
     * Sets whether CONDSTORE (RFC 7162) is enabled.
     *
     * @param enableCONDSTORE true to enable CONDSTORE
     * @return this
     */
    public ImapListener enableCONDSTORE(boolean enableCONDSTORE) {
        this.enableCONDSTORE = enableCONDSTORE;
        return this;
    }

    /**
     * Returns whether QRESYNC (RFC 7162) is enabled.
     *
     * @return true if QRESYNC is enabled
     */
    public boolean isEnableQRESYNC() {
        return enableQRESYNC;
    }

    /**
     * Sets whether QRESYNC (RFC 7162) is enabled.
     *
     * @param enableQRESYNC true to enable QRESYNC
     * @return this
     */
    public ImapListener enableQRESYNC(boolean enableQRESYNC) {
        this.enableQRESYNC = enableQRESYNC;
        return this;
    }

    /**
     * Returns whether OBJECTID (RFC 8474) is enabled.
     *
     * @return true if OBJECTID is enabled
     */
    public boolean isEnableOBJECTID() {
        return enableOBJECTID;
    }

    /**
     * Sets whether OBJECTID (RFC 8474) is enabled.
     *
     * @param enableOBJECTID true to enable OBJECTID
     * @return this
     */
    public ImapListener enableOBJECTID(boolean enableOBJECTID) {
        this.enableOBJECTID = enableOBJECTID;
        return this;
    }

    /**
     * Returns whether RFC 5465 NOTIFY is enabled.
     *
     * @return true if NOTIFY is enabled
     */
    public boolean isEnableNOTIFY() {
        return enableNOTIFY;
    }

    /**
     * Sets whether RFC 5465 NOTIFY is enabled.
     *
     * @param enableNOTIFY true to enable NOTIFY
     * @return this
     */
    public ImapListener enableNOTIFY(boolean enableNOTIFY) {
        this.enableNOTIFY = enableNOTIFY;
        return this;
    }

    /**
     * Returns whether RFC 5464 METADATA is enabled.
     *
     * @return true if METADATA is enabled
     */
    public boolean isEnableMETADATA() {
        return enableMETADATA;
    }

    /**
     * Sets whether RFC 5464 METADATA is enabled.
     *
     * @param enableMETADATA true to enable METADATA
     * @return this
     */
    public ImapListener enableMETADATA(boolean enableMETADATA) {
        this.enableMETADATA = enableMETADATA;
        return this;
    }

    /**
     * Returns the server identification fields sent in response to the
     * ID command (RFC 2971). When {@code null}, a default set containing
     * "name" and "version" is used.
     *
     * @return the server ID fields, or null for defaults
     */
    public Map<String, String> getServerIdFields() {
        return serverIdFields;
    }

    /**
     * Sets the server identification fields. Keys should be the standard
     * field names defined in RFC 2971 section 3.3 (e.g. "name", "version",
     * "vendor"). Pass {@code null} to use defaults.
     *
     * @param fields the key-value pairs to advertise
     * @return this
     */
    public ImapListener serverIdFields(Map<String, String> fields) {
        this.serverIdFields = fields;
        return this;
    }

    /**
     * Returns whether plaintext LOGIN is allowed over non-TLS connections.
     *
     * @return true if plaintext login is allowed
     */
    public boolean isAllowPlaintextLogin() {
        return allowPlaintextLogin;
    }

    /**
     * Sets whether plaintext LOGIN is allowed over non-TLS connections.
     * <p><strong>WARNING:</strong> This should only be enabled for testing.
     * Enabling this in production exposes passwords to network eavesdropping.
     *
     * @param allowPlaintextLogin true to allow plaintext login
     * @return this
     */
    public ImapListener allowPlaintextLogin(boolean allowPlaintextLogin) {
        this.allowPlaintextLogin = allowPlaintextLogin;
        return this;
    }

    /**
     * Returns the maximum command line length.
     *
     * @return the max line length in bytes
     */
    public int getMaxLineLength() {
        return maxLineLength;
    }

    /**
     * Sets the maximum command line length.
     *
     * @param maxLineLength the max line length in bytes
     * @return this
     */
    public ImapListener maxLineLength(int maxLineLength) {
        this.maxLineLength = maxLineLength;
        return this;
    }

    /**
     * Returns the maximum literal size.
     *
     * @return the max literal size in bytes
     */
    public int getMaxLiteralSize() {
        return maxLiteralSize;
    }

    /**
     * Sets the maximum literal size.
     *
     * @param maxLiteralSize the max literal size in bytes
     * @return this
     */
    public ImapListener maxLiteralSize(int maxLiteralSize) {
        this.maxLiteralSize = maxLiteralSize;
        return this;
    }

    /**
     * Starts this endpoint, setting default port if not specified.
     */
    @Override
    public void start() {
        super.start();
        if (port <= 0) {
            port = secure ? IMAPS_DEFAULT_PORT : IMAP_DEFAULT_PORT;
        }

        // Set IMAP-specific idle timeout default (30 minutes per RFC 9051)
        if (getIdleTimeoutMs() == DEFAULT_IDLE_TIMEOUT_MS) {
            idleTimeoutMs(30 * 60 * 1000); // 30 minutes
        }

        if (realm == null) {
            events().warn("warn.no_realm_configured").emit();
        }

        if (isMetricsEnabled()) {
            metrics = new ImapServerMetrics(getTelemetryConfig());
        }
    }

    /**
     * Returns the metrics for this endpoint, or null if telemetry is
     * not enabled.
     *
     * @return the IMAP server metrics
     */
    public ImapServerMetrics getMetrics() {
        return metrics;
    }

    /**
     * Stops this endpoint.
     */
    @Override
    public void stop() {
        super.stop();
    }

    /**
     * Sets the owning server. Called by {@link org.bluezoo.gumdrop.imap.server.ImapServer} during
     * wiring.
     *
     * @param server the owning server
     * @return this
     */
    public ImapListener server(org.bluezoo.gumdrop.imap.server.ImapServer server) {
        this.server = server;
        return this;
    }

    /**
     * Returns the owning server, or null if used standalone.
     *
     * @return the owning server
     */
    public org.bluezoo.gumdrop.imap.server.ImapServer getServer() {
        return server;
    }

    public org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider getSessionProvider() {
        return sessionProvider;
    }

    public ImapListener sessionProvider(
            org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider sessionProvider) {
        this.sessionProvider = sessionProvider;
        return this;
    }

    /**
     * Opens the application handler pipeline for a new connection.
     *
     * @return the handler, or {@code null} for default protocol behaviour
     */
    public org.bluezoo.gumdrop.imap.server.ClientConnected openApplicationSession() {
        if (sessionProvider != null) {
            try {
                return sessionProvider.openSession(this);
            } catch (Exception e) {
                events().warn("warn.failed_create_imap_handler_session_provider").thrown(e).emit();
            }
        }
        org.bluezoo.gumdrop.imap.server.ImapServer srv = getServer();
        if (srv != null) {
            try {
                return srv.openSession(this);
            } catch (Exception e) {
                events().warn("warn.failed_create_imap_handler_server").thrown(e).emit();
            }
        }
        return null;
    }

    /**
     * Creates a new ImapProtocolHandler for a newly accepted
     * connection.
     *
     * <p>If an {@link org.bluezoo.gumdrop.imap.server.ImapServer} is set, the handler is obtained
     * from the server.
     *
     * @return a new IMAP endpoint handler
     */
    @Override
    protected ProtocolHandler createHandler() {
        return new ImapProtocolHandler(this);
    }

    /**
     * Checks if SSL/TLS context is available for STARTTLS.
     *
     * @return true if STARTTLS is supported, false otherwise
     */
    protected boolean isSTARTTLSAvailable() {
        return isTLSConfigured();
    }

    /**
     * Returns the list of capabilities to advertise.
     *
     * <p>RFC 9051 section 6.1.1 — CAPABILITY response.  Each token maps
     * to a specific RFC:
     * <ul>
     *   <li>{@code IMAP4rev2} — RFC 9051</li>
     *   <li>{@code STARTTLS} — RFC 9051 section 6.2.1</li>
     *   <li>{@code AUTH=*} — RFC 9051 section 6.2.2, individual
     *       mechanisms per RFC 4616, 5802, 7628, etc.</li>
     *   <li>{@code LOGINDISABLED} — RFC 9051 section 6.2.3</li>
     *   <li>{@code IDLE} — RFC 2177</li>
     *   <li>{@code NAMESPACE} — RFC 2342</li>
     *   <li>{@code QUOTA} — RFC 9208</li>
     *   <li>{@code MOVE} — RFC 6851</li>
     *   <li>{@code UNSELECT} — RFC 9051 section 6.4.2</li>
     *   <li>{@code UIDPLUS} — RFC 4315</li>
     *   <li>{@code CHILDREN} — RFC 3348</li>
     *   <li>{@code LIST-EXTENDED} — RFC 5258</li>
     *   <li>{@code LIST-STATUS} — RFC 5819</li>
     *   <li>{@code STATUS=SIZE} — RFC 8438 (SIZE status data item)</li>
     *   <li>{@code COMPRESS=DEFLATE} — RFC 4978 (when authenticated and
     *       compression is not yet active)</li>
     *   <li>{@code OBJECTID} — RFC 8474 (MAILBOXID/EMAILID); unlike
     *       CONDSTORE/QRESYNC/UTF8=ACCEPT this needs no {@code ENABLE} --
     *       once advertised, the FETCH items and SEARCH criterion it adds
     *       are simply usable</li>
     * </ul>
     *
     * @param authenticated true if the user is authenticated
     * @param secure true if the connection is using TLS
     * @return space-separated capability string
     */
    protected String getCapabilities(boolean authenticated, boolean secure) {
        return getCapabilities(authenticated, secure, false);
    }

    /**
     * Returns capability tokens for the current session state.
     *
     * @param authenticated true if the user is authenticated
     * @param secure true if the connection is using TLS
     * @param compressionActive true if DEFLATE is already enabled on the wire
     * @return space-separated capability string
     */
    protected String getCapabilities(boolean authenticated, boolean secure,
            boolean compressionActive) {
        StringBuilder caps = new StringBuilder();
        // RFC 9051 section 6.1.1: IMAP4rev1 is also named so that clients
        // that look only for it will connect
        caps.append("IMAP4rev1 IMAP4rev2");

        // RFC 9051 section 6.2.1 — advertise STARTTLS only pre-auth on cleartext
        if (!authenticated && !secure && isSTARTTLSAvailable()) {
            caps.append(" STARTTLS");
        }

        if (!authenticated) {
            // RFC 9051 section 6.2.2 — SASL mechanism advertisement
            if (realm != null) {
                Set<SaslMechanism> supported =
                        realm.getSupportedSASLMechanisms();
                for (SaslMechanism mech : supported) {
                    if (!secure && mech.requiresTLS()) {
                        continue;
                    }
                    caps.append(" AUTH=")
                            .append(mech.getMechanismName());
                }
            }
            // RFC 4752 — advertise GSSAPI when configured
            if (gssapiServer != null) {
                caps.append(" AUTH=GSSAPI");
            }
            // RFC 9051 section 6.2.3 — LOGINDISABLED on cleartext
            if (!secure && !allowPlaintextLogin) {
                caps.append(" LOGINDISABLED");
            }
        }

        if (authenticated) {
            if (enableIDLE) {
                caps.append(" IDLE");          // RFC 2177
            }
            if (enableNAMESPACE) {
                caps.append(" NAMESPACE");     // RFC 2342
            }
            if (enableQUOTA) {
                caps.append(" QUOTA");         // RFC 9208
            }
            if (enableMOVE) {
                caps.append(" MOVE");          // RFC 6851
            }
            if (enableCONDSTORE) {
                caps.append(" CONDSTORE");     // RFC 7162
            }
            if (enableQRESYNC) {
                caps.append(" QRESYNC");       // RFC 7162
            }
            if (enableCOMPRESS && !compressionActive) {
                caps.append(" COMPRESS=DEFLATE"); // RFC 4978
            }
            if (enableUTF8ACCEPT) {
                caps.append(" UTF8=ACCEPT");   // RFC 6855
            }
            if (enableSORT) {
                caps.append(" SORT");          // RFC 5256
                caps.append(" THREAD=ORDEREDSUBJECT");
                caps.append(" THREAD=REFERENCES");
                caps.append(" I18NLEVEL=1");   // RFC 5256 / RFC 5255
            }
            if (enableOBJECTID) {
                caps.append(" OBJECTID");      // RFC 8474
            }
            caps.append(" BINARY");            // RFC 3516
            caps.append(" PREVIEW");           // RFC 8970
            if (enableNOTIFY) {
                caps.append(" NOTIFY");        // RFC 5465
            }
            if (enableMETADATA) {
                caps.append(" METADATA");      // RFC 5464
            }
        }

        caps.append(" UNSELECT");              // RFC 9051 section 6.4.2
        caps.append(" UIDPLUS");               // RFC 4315
        caps.append(" CHILDREN");              // RFC 3348
        caps.append(" LIST-EXTENDED");         // RFC 5258
        caps.append(" LIST-STATUS");           // RFC 5819
        caps.append(" STATUS=SIZE");           // RFC 8438
        caps.append(" LITERAL-");              // RFC 7888
        caps.append(" ID");                    // RFC 2971

        return caps.toString();
    }

}
