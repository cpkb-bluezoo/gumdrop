/*
 * POP3ProtocolHandler.java
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

package org.bluezoo.gumdrop.pop3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.pop3.handler.AuthenticateState;
import org.bluezoo.gumdrop.pop3.handler.AuthorizationHandler;
import org.bluezoo.gumdrop.pop3.handler.ClientConnected;
import org.bluezoo.gumdrop.pop3.handler.ConnectedState;
import org.bluezoo.gumdrop.pop3.handler.ListState;
import org.bluezoo.gumdrop.pop3.handler.MailboxStatusState;
import org.bluezoo.gumdrop.pop3.handler.MarkDeletedState;
import org.bluezoo.gumdrop.pop3.handler.ResetState;
import org.bluezoo.gumdrop.pop3.handler.RetrieveState;
import org.bluezoo.gumdrop.pop3.handler.TopState;
import org.bluezoo.gumdrop.pop3.handler.TransactionHandler;
import org.bluezoo.gumdrop.pop3.handler.UidlState;
import org.bluezoo.gumdrop.pop3.handler.UpdateState;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.util.ByteArrays;

/**
 * POP3 protocol handler using {@link ProtocolHandler} and
 * {@link LineParser}.
 *
 * <p>Implements the POP3 protocol with the transport layer fully decoupled:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses the composable {@link LineParser} utility</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * <p>This handler works with any transport: TCP via TCPEndpoint, or
 * potentially QUIC streams via QuicStreamEndpoint (for POP3-over-QUIC,
 * if ever standardised).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see LineParser
 * @see POP3Listener
 */
public class POP3ProtocolHandler
        implements ProtocolHandler, LineParser.Callback,
                   ConnectedState, AuthenticateState, MailboxStatusState,
                   ListState, RetrieveState, MarkDeletedState, ResetState,
                   TopState, UidlState, UpdateState {

    private static final Logger LOGGER =
            Logger.getLogger(POP3ProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.pop3.L10N");

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    private static final int MAX_LINE_LENGTH = 512;
    private static final String CRLF = "\r\n";

    enum POP3State {
        AUTHORIZATION,
        TRANSACTION,
        UPDATE
    }

    enum AuthState {
        NONE,
        PLAIN_RESPONSE,
        LOGIN_USERNAME,
        LOGIN_PASSWORD,
        CRAM_MD5_RESPONSE,
        DIGEST_MD5_RESPONSE,
        SCRAM_INITIAL,
        SCRAM_FINAL,
        OAUTH_RESPONSE,
        GSSAPI_EXCHANGE,
        EXTERNAL_CERT,
        NTLM_TYPE1,
        NTLM_TYPE3
    }

    // Transport reference (set in connected())
    private Endpoint endpoint;

    private final POP3Listener server;
    private final long connectionTimeMillis;
    private final String apopTimestamp;

    private Realm realm;

    // Handler interfaces for staged handler pattern
    private ClientConnected clientConnected;
    private AuthorizationHandler authorizationHandler;
    private TransactionHandler transactionHandler;

    // POP3 session state
    private POP3State state = POP3State.AUTHORIZATION;
    private String username;
    private MailboxStore store;
    private Mailbox mailbox;
    private boolean utf8Mode;
    private boolean stlsUsed;
    private long lastActivityTime;
    private long failedAuthAttempts;
    private long lastFailedAuthTime;

    // SASL state
    private AuthState authState = AuthState.NONE;
    private String pendingAuthUsername;
    private String authChallenge;
    private String authNonce;
    private String authClientNonce;
    private byte[] authSalt;
    private int authIterations = 4096;
    private byte[] ntlmChallenge;

    private CharBuffer charBuffer;

    // Telemetry
    private Trace connectionTrace;
    private Span sessionSpan;
    private Span authenticatedSpan;

    /**
     * Creates a new POP3 endpoint handler.
     *
     * @param server the POP3 server configuration
     */
    public POP3ProtocolHandler(POP3Listener server) {
        this.server = server;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.lastActivityTime = connectionTimeMillis;
        this.charBuffer = CharBuffer.allocate(MAX_LINE_LENGTH + 2);

        if (server.isEnableAPOP()) {
            this.apopTimestamp = generateAPOPTimestamp();
        } else {
            this.apopTimestamp = null;
        }
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;

        initConnectionTrace();
        startSessionSpan();

        // For implicit TLS (POP3S), defer greeting until securityEstablished
        if (endpoint.isSecure()) {
            return;
        }

        sendGreetingWithHandler();
    }

    @Override
    public void receive(ByteBuffer data) {
        lastActivityTime = System.currentTimeMillis();
        LineParser.parse(data, this);
    }

    @Override
    public void disconnected() {
        try {
            if (clientConnected != null) {
                clientConnected.disconnected();
            }

            endAuthenticatedSpan();
            if (state == POP3State.UPDATE) {
                endSessionSpan();
            } else {
                endSessionSpanError("Connection lost");
            }
        } finally {
            try {
                if (mailbox != null && state != POP3State.UPDATE) {
                    try {
                        mailbox.close(false);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "Error closing mailbox on disconnect", e);
                    }
                    mailbox = null;
                }
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "Error closing store on disconnect", e);
                    }
                    store = null;
                }
            }
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        // For POP3S (implicit TLS), send greeting now
        // For STLS, the greeting was already sent
        if (state == POP3State.AUTHORIZATION && !stlsUsed) {
            sendGreetingWithHandler();
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "POP3 transport error", cause);
        closeEndpoint();
    }

    // ── LineParser.Callback implementation ──

    @Override
    public void lineReceived(ByteBuffer line) {
        try {
            int lineLength = line.remaining();
            if (lineLength > MAX_LINE_LENGTH + 2) {
                sendERR(L10N.getString("pop3.err.line_too_long"));
                closeEndpoint();
                return;
            }

            charBuffer.clear();
            US_ASCII_DECODER.reset();
            CoderResult result =
                    US_ASCII_DECODER.decode(line, charBuffer, true);
            if (result.isError()) {
                sendERR(L10N.getString(
                        "pop3.err.invalid_command_encoding"));
                return;
            }
            charBuffer.flip();

            int len = charBuffer.limit();
            if (len >= 2
                    && charBuffer.get(len - 2) == '\r'
                    && charBuffer.get(len - 1) == '\n') {
                charBuffer.limit(len - 2);
                len -= 2;
            }

            int spaceIndex = -1;
            for (int i = 0; i < len; i++) {
                if (charBuffer.get(i) == ' ') {
                    spaceIndex = i;
                    break;
                }
            }

            String command;
            String args;
            if (spaceIndex > 0) {
                charBuffer.limit(spaceIndex);
                command = charBuffer.toString()
                        .toUpperCase(Locale.ENGLISH);
                charBuffer.limit(len);
                charBuffer.position(spaceIndex + 1);
                args = charBuffer.toString();
            } else {
                command = charBuffer.toString()
                        .toUpperCase(Locale.ENGLISH);
                args = "";
            }

            if (LOGGER.isLoggable(Level.FINEST)) {
                String msg = L10N.getString("log.pop3_command");
                msg = MessageFormat.format(msg, command, args);
                LOGGER.finest(msg);
            }

            handleCommand(command, args);

        } catch (IOException e) {
            String logMsg =
                    L10N.getString("log.error_processing_data");
            LOGGER.log(Level.WARNING, logMsg, e);
            try {
                sendERR(L10N.getString("pop3.err.internal_error"));
            } catch (IOException e2) {
                String errMsg =
                        L10N.getString("log.error_sending_response");
                LOGGER.log(Level.SEVERE, errMsg, e2);
            }
            closeEndpoint();
        }
    }

    @Override
    public boolean continueLineProcessing() {
        return true;
    }

    // ── Transport helpers (delegate to Endpoint) ──

    private void sendLine(String line) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("POP3 response: " + line);
        }
        byte[] data = (line + CRLF).getBytes(US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(data);
        endpoint.send(buf);
    }

    private void sendOK(String message) throws IOException {
        sendLine("+OK " + message);
    }

    private void sendERR(String message) throws IOException {
        sendLine("-ERR " + message);
    }

    private void closeEndpoint() {
        try {
            endAuthenticatedSpan();
            endSessionSpan();
        } finally {
            if (endpoint != null) {
                endpoint.close();
            }
        }
    }

    // ── Greeting ──

    private void sendGreetingWithHandler() {
        POP3Service service = server.getService();
        if (service != null) {
            clientConnected = service.createHandler(server);
        }
        if (clientConnected != null) {
            clientConnected.connected(this, endpoint);
        } else {
            sendGreeting();
        }
    }

    private void sendGreeting() {
        try {
            if (apopTimestamp != null) {
                sendOK(MessageFormat.format(
                        L10N.getString("pop3.greeting_apop"),
                        apopTimestamp));
            } else {
                sendOK(L10N.getString("pop3.greeting"));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send greeting", e);
            closeEndpoint();
        }
    }

    // ── Realm ──

    private Realm getRealm() {
        if (realm == null) {
            Realm serverRealm = server.getRealm();
            if (serverRealm != null) {
                SelectorLoop loop = endpoint.getSelectorLoop();
                if (loop != null) {
                    realm = serverRealm.forSelectorLoop(loop);
                } else {
                    realm = serverRealm;
                }
            }
        }
        return realm;
    }

    // ── APOP timestamp ──

    private String generateAPOPTimestamp() {
        long pid = getProcessId();
        long timestamp = System.currentTimeMillis();
        return "<" + pid + "." + timestamp + "@pop3>";
    }

    private static long getProcessId() {
        String runtimeName = java.lang.management.ManagementFactory
                .getRuntimeMXBean().getName();
        int atIndex = runtimeName.indexOf('@');
        if (atIndex > 0) {
            try {
                return Long.parseLong(
                        runtimeName.substring(0, atIndex));
            } catch (NumberFormatException e) {
                // Fall through
            }
        }
        return System.nanoTime() & 0x7FFFFFFFFFFFFFFFL;
    }

    // ── Command dispatch ──

    private void handleCommand(String command, String args)
            throws IOException {
        switch (command) {
            case "QUIT":
                handleQUIT(args);
                return;
            case "CAPA":
                handleCAPA(args);
                return;
            case "NOOP":
                handleNOOP(args);
                return;
        }

        switch (state) {
            case AUTHORIZATION:
                handleAuthorizationCommand(command, args);
                break;
            case TRANSACTION:
                handleTransactionCommand(command, args);
                break;
            case UPDATE:
                sendERR(L10N.getString(
                        "pop3.err.command_not_valid_update"));
                break;
        }
    }

    private void handleAuthorizationCommand(String command, String args)
            throws IOException {
        if (authState != AuthState.NONE) {
            handleAuthContinuation(
                    command + (args.isEmpty() ? "" : " " + args));
            return;
        }

        switch (command) {
            case "USER":
                handleUSER(args);
                break;
            case "PASS":
                handlePASS(args);
                break;
            case "APOP":
                handleAPOP(args);
                break;
            case "AUTH":
                handleAUTH(args);
                break;
            case "STLS":
                handleSTLS(args);
                break;
            case "UTF8":
                handleUTF8(args);
                break;
            default:
                sendERR(MessageFormat.format(
                        L10N.getString("pop3.err.unknown_command"),
                        command));
        }
    }

    private void handleTransactionCommand(String command, String args)
            throws IOException {
        switch (command) {
            case "STAT":
                handleSTAT(args);
                break;
            case "LIST":
                handleLIST(args);
                break;
            case "RETR":
                handleRETR(args);
                break;
            case "DELE":
                handleDELE(args);
                break;
            case "RSET":
                handleRSET(args);
                break;
            case "TOP":
                handleTOP(args);
                break;
            case "UIDL":
                handleUIDL(args);
                break;
            default:
                sendERR(MessageFormat.format(
                        L10N.getString("pop3.err.unknown_command"),
                        command));
        }
    }

    // ── AUTHORIZATION commands ──

    private void handleUSER(String args) throws IOException {
        if (args.isEmpty()) {
            sendERR(L10N.getString("pop3.err.username_required"));
            return;
        }
        username = args;
        sendOK(L10N.getString("pop3.user_accepted"));
    }

    private void handlePASS(String args) throws IOException {
        if (username == null) {
            sendERR(L10N.getString("pop3.err.user_command_required"));
            return;
        }

        enforceLoginDelay();

        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            closeEndpoint();
            return;
        }

        if (realm.passwordMatch(username, args)) {
            if (openMailbox(username)) {
                state = POP3State.TRANSACTION;
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("POP3 USER/PASS auth successful: "
                            + username);
                }
                recordAuthenticationSuccess("USER/PASS");
                sendOK(L10N.getString("pop3.mailbox_opened"));
            }
        } else {
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 USER/PASS auth failed: "
                        + username);
            }
            recordAuthenticationFailure("USER/PASS", username);
            username = null;
            sendERR(L10N.getString("pop3.err.auth_failed"));
        }
    }

    private void handleAPOP(String args) throws IOException {
        if (apopTimestamp == null) {
            sendERR(L10N.getString("pop3.err.apop_not_supported"));
            return;
        }

        int spaceIndex = args.indexOf(' ');
        if (spaceIndex < 0) {
            sendERR(L10N.getString("pop3.err.apop_requires_args"));
            return;
        }

        String user = args.substring(0, spaceIndex);
        String clientDigest = args.substring(spaceIndex + 1);

        enforceLoginDelay();

        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            closeEndpoint();
            return;
        }

        try {
            String expected =
                    realm.getApopResponse(user, apopTimestamp);
            if (expected != null
                    && expected.equalsIgnoreCase(clientDigest)) {
                username = user;
                if (openMailbox(username)) {
                    state = POP3State.TRANSACTION;
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("POP3 APOP auth successful: "
                                + user);
                    }
                    recordAuthenticationSuccess("APOP");
                    sendOK(L10N.getString("pop3.mailbox_opened"));
                }
                return;
            }

            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 APOP auth failed: " + user);
            }
            recordAuthenticationFailure("APOP", user);
            sendERR(L10N.getString("pop3.err.auth_failed"));

        } catch (UnsupportedOperationException e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(L10N.getString("log.apop_not_supported"));
            }
            sendERR(L10N.getString("pop3.err.apop_not_available"));
        }
    }

    private void handleSTLS(String args) throws IOException {
        if (endpoint.isSecure()) {
            sendERR(L10N.getString("pop3.err.already_tls"));
            return;
        }

        if (!server.isSTARTTLSAvailable()) {
            sendERR(L10N.getString("pop3.err.stls_not_supported"));
            return;
        }

        sendOK(L10N.getString("pop3.stls_begin"));

        try {
            endpoint.startTLS();
            stlsUsed = true;
            recordStartTLSSuccess();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize TLS", e);
            recordStartTLSFailure(e);
            closeEndpoint();
        }
    }

    private void handleUTF8(String args) throws IOException {
        if (!server.isEnableUTF8()) {
            sendERR(L10N.getString("pop3.err.utf8_not_supported"));
            return;
        }
        utf8Mode = true;
        sendOK(L10N.getString("pop3.utf8_enabled"));
    }

    // ── SASL AUTH ──

    private void handleAUTH(String args) throws IOException {
        if (args.isEmpty()) {
            Realm realm = getRealm();
            if (realm != null) {
                sendOK(L10N.getString("pop3.auth_mechanisms"));
                java.util.Set<SASLMechanism> supported =
                        realm.getSupportedSASLMechanisms();
                for (SASLMechanism mech : supported) {
                    if (!endpoint.isSecure() && mech.requiresTLS()) {
                        continue;
                    }
                    if (mech == SASLMechanism.EXTERNAL
                            && !endpoint.isSecure()) {
                        continue;
                    }
                    sendLine(mech.getMechanismName());
                }
                sendLine(".");
            } else {
                sendERR(L10N.getString(
                        "pop3.err.auth_not_configured"));
            }
            return;
        }

        String mechanism;
        String initialResponse = null;
        int spaceIndex = args.indexOf(' ');
        if (spaceIndex > 0) {
            mechanism = args.substring(0, spaceIndex)
                    .toUpperCase(Locale.ENGLISH);
            initialResponse = args.substring(spaceIndex + 1);
        } else {
            mechanism = args.toUpperCase(Locale.ENGLISH);
        }

        switch (mechanism) {
            case "PLAIN":
                handleAuthPLAIN(initialResponse);
                break;
            case "LOGIN":
                handleAuthLOGIN(initialResponse);
                break;
            case "CRAM-MD5":
                handleAuthCRAMMD5(initialResponse);
                break;
            case "DIGEST-MD5":
                handleAuthDIGESTMD5(initialResponse);
                break;
            case "SCRAM-SHA-256":
                handleAuthSCRAM(initialResponse);
                break;
            case "OAUTHBEARER":
                handleAuthOAUTHBEARER(initialResponse);
                break;
            case "GSSAPI":
                handleAuthGSSAPI(initialResponse);
                break;
            case "EXTERNAL":
                handleAuthEXTERNAL(initialResponse);
                break;
            case "NTLM":
                handleAuthNTLM(initialResponse);
                break;
            default:
                sendERR(L10N.getString(
                        "pop3.err.unsupported_mechanism"));
        }
    }

    private void handleAuthPLAIN(String initialResponse)
            throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            authState = AuthState.PLAIN_RESPONSE;
            sendContinuation("");
            return;
        }
        processPlainCredentials(initialResponse);
    }

    private void handleAuthLOGIN(String initialResponse)
            throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder()
                        .decode(initialResponse);
                pendingAuthUsername = new String(decoded, US_ASCII);
                authState = AuthState.LOGIN_PASSWORD;
                sendContinuation(Base64.getEncoder().encodeToString(
                        "Password:".getBytes(US_ASCII)));
            } catch (IllegalArgumentException e) {
                sendERR(L10N.getString("pop3.err.invalid_base64"));
                resetAuthState();
            }
        } else {
            authState = AuthState.LOGIN_USERNAME;
            sendContinuation(Base64.getEncoder().encodeToString(
                    "Username:".getBytes(US_ASCII)));
        }
    }

    private void handleAuthCRAMMD5(String initialResponse)
            throws IOException {
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return;
        }

        try {
            InetSocketAddress addr =
                    (InetSocketAddress) endpoint.getLocalAddress();
            authChallenge = SASLUtils.generateCramMD5Challenge(
                    addr.getHostName());
            authState = AuthState.CRAM_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(authChallenge));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Failed to generate CRAM-MD5 challenge", e);
            sendERR(L10N.getString("pop3.err.internal_error"));
            resetAuthState();
        }
    }

    private void handleAuthDIGESTMD5(String initialResponse)
            throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            sendERR(L10N.getString(
                    "pop3.err.digestmd5_no_initial"));
            return;
        }

        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return;
        }

        try {
            authNonce = SASLUtils.generateNonce(16);
            String hostname = ((InetSocketAddress)
                    endpoint.getLocalAddress()).getHostName();
            String challenge = SASLUtils.generateDigestMD5Challenge(
                    hostname, authNonce);
            authState = AuthState.DIGEST_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(challenge));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "DIGEST-MD5 challenge generation error", e);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            resetAuthState();
        }
    }

    private void handleAuthSCRAM(String initialResponse)
            throws IOException {
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return;
        }

        if (initialResponse != null && !initialResponse.isEmpty()) {
            processScramClientFirst(initialResponse);
        } else {
            authState = AuthState.SCRAM_INITIAL;
            sendContinuation("");
        }
    }

    private void handleAuthOAUTHBEARER(String initialResponse)
            throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            authState = AuthState.OAUTH_RESPONSE;
            sendContinuation("");
            return;
        }
        processOAuthBearerCredentials(initialResponse);
    }

    private void handleAuthGSSAPI(String initialResponse)
            throws IOException {
        sendERR(L10N.getString("pop3.err.gssapi_not_available"));
    }

    private void handleAuthEXTERNAL(String initialResponse)
            throws IOException {
        if (!endpoint.isSecure()) {
            sendERR(L10N.getString(
                    "pop3.err.external_requires_tls"));
            return;
        }

        Certificate[] peerCerts =
                endpoint.getSecurityInfo().getPeerCertificates();
        if (peerCerts == null || peerCerts.length == 0) {
            sendERR(L10N.getString("pop3.err.no_client_cert"));
            return;
        }

        X509Certificate clientCert = (X509Certificate) peerCerts[0];
        String certUsername = extractCertificateUsername(clientCert);

        if (certUsername == null) {
            sendERR(L10N.getString("pop3.err.cert_username_error"));
            return;
        }

        String authzid = certUsername;
        if (initialResponse != null && !initialResponse.isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder()
                        .decode(initialResponse);
                String authzidParam = new String(decoded, US_ASCII);
                if (!authzidParam.isEmpty()) {
                    authzid = authzidParam;
                }
            } catch (IllegalArgumentException e) {
                sendERR(L10N.getString("pop3.err.invalid_base64"));
                return;
            }
        }

        Realm realm = getRealm();
        if (realm != null) {
            realm.passwordMatch(certUsername, "");
        }

        if (openMailbox(authzid)) {
            username = authzid;
            state = POP3State.TRANSACTION;
            recordAuthenticationSuccess("AUTH EXTERNAL");
            sendOK(L10N.getString("pop3.mailbox_opened"));
        } else {
            recordAuthenticationFailure("AUTH EXTERNAL", authzid);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        }
    }

    private void handleAuthNTLM(String initialResponse)
            throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            try {
                byte[] type1Msg = Base64.getDecoder()
                        .decode(initialResponse);
                if (type1Msg.length < 8
                        || !new String(type1Msg, 0, 8, US_ASCII)
                                .equals("NTLMSSP\0")) {
                    sendERR(L10N.getString(
                            "pop3.err.invalid_ntlm_message"));
                    return;
                }
                byte[] type2Msg = generateNTLMType2Challenge();
                authState = AuthState.NTLM_TYPE3;
                sendContinuation(Base64.getEncoder()
                        .encodeToString(type2Msg));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "NTLM Type 2 generation error", e);
                sendERR(L10N.getString("pop3.err.ntlm_failed"));
            }
        } else {
            authState = AuthState.NTLM_TYPE1;
            sendContinuation("");
        }
    }

    // ── SASL continuation ──

    private void handleAuthContinuation(String data) throws IOException {
        if ("*".equals(data.trim())) {
            sendERR(L10N.getString("pop3.err.auth_aborted"));
            resetAuthState();
            return;
        }

        switch (authState) {
            case PLAIN_RESPONSE:
                processPlainCredentials(data);
                break;
            case LOGIN_USERNAME:
                processLoginUsername(data);
                break;
            case LOGIN_PASSWORD:
                processLoginPassword(data);
                break;
            case CRAM_MD5_RESPONSE:
                processCramMD5Response(data);
                break;
            case DIGEST_MD5_RESPONSE:
                processDigestMD5Response(data);
                break;
            case SCRAM_INITIAL:
                processScramClientFirst(data);
                break;
            case SCRAM_FINAL:
                processScramClientFinal(data);
                break;
            case OAUTH_RESPONSE:
                processOAuthBearerCredentials(data);
                break;
            case NTLM_TYPE1:
                processNTLMType1(data);
                break;
            case NTLM_TYPE3:
                processNTLMType3(data);
                break;
            default:
                sendERR(L10N.getString(
                        "pop3.err.unexpected_auth_data"));
                resetAuthState();
        }
    }

    private void processPlainCredentials(String data)
            throws IOException {
        try {
            byte[] decoded = SASLUtils.decodeBase64(data);
            String[] parts = SASLUtils.parsePlainCredentials(decoded);
            String authzid = parts[0];
            String authcid = parts[1];
            String password = parts[2];
            String user = authzid.isEmpty() ? authcid : authzid;

            if (authenticateAndOpenMailbox(user, password, "PLAIN")) {
                return;
            }
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    private void processLoginUsername(String data) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            pendingAuthUsername = new String(decoded, US_ASCII);
            authState = AuthState.LOGIN_PASSWORD;
            sendContinuation(Base64.getEncoder().encodeToString(
                    "Password:".getBytes(US_ASCII)));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
            resetAuthState();
        }
    }

    private void processLoginPassword(String data) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String password = new String(decoded, US_ASCII);
            if (authenticateAndOpenMailbox(
                    pendingAuthUsername, password, "LOGIN")) {
                return;
            }
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    private void processCramMD5Response(String data)
            throws IOException {
        try {
            String response = SASLUtils.decodeBase64ToString(data);
            int spaceIndex = response.indexOf(' ');
            if (spaceIndex < 0) {
                sendERR(L10N.getString(
                        "pop3.err.invalid_crammd5_format"));
                resetAuthState();
                return;
            }
            String user = response.substring(0, spaceIndex);
            String clientDigest = response.substring(spaceIndex + 1);

            Realm realm = getRealm();
            if (realm == null) {
                sendERR(L10N.getString(
                        "pop3.err.auth_not_configured"));
                resetAuthState();
                return;
            }

            try {
                String expected =
                        realm.getCramMD5Response(user, authChallenge);
                if (expected != null
                        && expected.equalsIgnoreCase(clientDigest)) {
                    if (openMailbox(user)) {
                        username = user;
                        state = POP3State.TRANSACTION;
                        recordAuthenticationSuccess("AUTH CRAM-MD5");
                        sendOK(L10N.getString("pop3.mailbox_opened"));
                        resetAuthState();
                        return;
                    }
                }
            } catch (UnsupportedOperationException e) {
                sendERR(L10N.getString(
                        "pop3.err.crammd5_not_available"));
                resetAuthState();
                return;
            }

            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            recordAuthenticationFailure("AUTH CRAM-MD5", user);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    private void processOAuthBearerCredentials(String data)
            throws IOException {
        try {
            String credentials =
                    SASLUtils.decodeBase64ToString(data);
            Map<String, String> params =
                    SASLUtils.parseOAuthBearerCredentials(credentials);
            String user = params.get("user");
            String token = params.get("token");

            if (user == null || token == null) {
                sendERR(L10N.getString(
                        "pop3.err.invalid_oauthbearer_format"));
                resetAuthState();
                return;
            }

            Realm realm = getRealm();
            if (realm != null) {
                Realm.TokenValidationResult result =
                        realm.validateBearerToken(token);
                if (result == null) {
                    result = realm.validateOAuthToken(token);
                }
                if (result != null && result.valid
                        && user.equals(result.username)) {
                    if (openMailbox(user)) {
                        username = user;
                        state = POP3State.TRANSACTION;
                        recordAuthenticationSuccess(
                                "AUTH OAUTHBEARER");
                        sendOK(L10N.getString(
                                "pop3.mailbox_opened"));
                        resetAuthState();
                        return;
                    }
                }
            }

            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            recordAuthenticationFailure("AUTH OAUTHBEARER", user);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    private void processDigestMD5Response(String data)
            throws IOException {
        try {
            String digestResponse =
                    SASLUtils.decodeBase64ToString(data);
            Map<String, String> params =
                    SASLUtils.parseDigestParams(digestResponse);
            String digestUsername = params.get("username");
            String responseHash = params.get("response");

            if (digestUsername != null && responseHash != null) {
                Realm realm = getRealm();
                if (realm != null) {
                    String hostname = ((InetSocketAddress)
                            endpoint.getLocalAddress()).getHostName();
                    String ha1 = realm.getDigestHA1(
                            digestUsername, hostname);
                    if (ha1 != null) {
                        if (openMailbox(digestUsername)) {
                            username = digestUsername;
                            state = POP3State.TRANSACTION;
                            recordAuthenticationSuccess(
                                    "AUTH DIGEST-MD5");
                            sendOK(L10N.getString(
                                    "pop3.mailbox_opened"));
                            resetAuthState();
                            return;
                        }
                    }
                }
            }

            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            recordAuthenticationFailure(
                    "AUTH DIGEST-MD5", digestUsername);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    private void processScramClientFirst(String data)
            throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String clientFirst = new String(decoded, US_ASCII);

            if (!clientFirst.startsWith("n,,")) {
                sendERR(L10N.getString(
                        "pop3.err.invalid_scram_message"));
                resetAuthState();
                return;
            }

            String messageBody = clientFirst.substring(3);
            int partStart = 0;
            int msgLen = messageBody.length();
            while (partStart <= msgLen) {
                int partEnd = messageBody.indexOf(',', partStart);
                if (partEnd < 0) {
                    partEnd = msgLen;
                }
                String part =
                        messageBody.substring(partStart, partEnd);
                if (part.startsWith("n=")) {
                    pendingAuthUsername = part.substring(2);
                } else if (part.startsWith("r=")) {
                    authClientNonce = part.substring(2);
                }
                partStart = partEnd + 1;
            }

            if (pendingAuthUsername == null
                    || authClientNonce == null) {
                sendERR(L10N.getString(
                        "pop3.err.invalid_scram_message"));
                resetAuthState();
                return;
            }

            SecureRandom random = new SecureRandom();
            byte[] serverNonceBytes = new byte[16];
            random.nextBytes(serverNonceBytes);
            authNonce = authClientNonce
                    + ByteArrays.toHexString(serverNonceBytes);
            authSalt = new byte[16];
            random.nextBytes(authSalt);
            authIterations = 4096;

            String serverFirst = "r=" + authNonce
                    + ",s=" + Base64.getEncoder()
                            .encodeToString(authSalt)
                    + ",i=" + authIterations;
            authState = AuthState.SCRAM_FINAL;
            sendContinuation(Base64.getEncoder().encodeToString(
                    serverFirst.getBytes(US_ASCII)));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "SCRAM client first processing error", e);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            resetAuthState();
        }
    }

    private void processScramClientFinal(String data)
            throws IOException {
        try {
            Base64.getDecoder().decode(data);

            if (pendingAuthUsername != null) {
                Realm realm = getRealm();
                if (realm != null) {
                    try {
                        Realm.ScramCredentials creds =
                                realm.getScramCredentials(
                                        pendingAuthUsername);
                        if (creds != null) {
                            if (openMailbox(pendingAuthUsername)) {
                                username = pendingAuthUsername;
                                state = POP3State.TRANSACTION;
                                recordAuthenticationSuccess(
                                        "AUTH SCRAM-SHA-256");
                                sendOK(L10N.getString(
                                        "pop3.mailbox_opened"));
                                resetAuthState();
                                return;
                            }
                        }
                    } catch (UnsupportedOperationException e) {
                        sendERR(L10N.getString(
                                "pop3.err.scram_not_available"));
                        resetAuthState();
                        return;
                    }
                }
            }

            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            recordAuthenticationFailure(
                    "AUTH SCRAM-SHA-256", pendingAuthUsername);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    private void processNTLMType1(String data) throws IOException {
        try {
            byte[] type1Msg = Base64.getDecoder().decode(data);
            if (type1Msg.length < 8
                    || !new String(type1Msg, 0, 8, US_ASCII)
                            .equals("NTLMSSP\0")) {
                sendERR(L10N.getString(
                        "pop3.err.invalid_ntlm_message"));
                resetAuthState();
                return;
            }
            byte[] type2Msg = generateNTLMType2Challenge();
            authState = AuthState.NTLM_TYPE3;
            sendContinuation(Base64.getEncoder()
                    .encodeToString(type2Msg));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "NTLM Type 2 generation error", e);
            sendERR(L10N.getString("pop3.err.ntlm_failed"));
            resetAuthState();
        }
    }

    private void processNTLMType3(String data) throws IOException {
        try {
            byte[] type3Msg = Base64.getDecoder().decode(data);
            String ntlmUsername = parseNTLMType3Username(type3Msg);

            if (ntlmUsername != null) {
                Realm realm = getRealm();
                if (realm != null && realm.userExists(ntlmUsername)) {
                    if (openMailbox(ntlmUsername)) {
                        username = ntlmUsername;
                        state = POP3State.TRANSACTION;
                        recordAuthenticationSuccess("AUTH NTLM");
                        sendOK(L10N.getString(
                                "pop3.mailbox_opened"));
                        resetAuthState();
                        return;
                    }
                }
            }

            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            recordAuthenticationFailure("AUTH NTLM", ntlmUsername);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "NTLM Type 3 parsing error", e);
            sendERR(L10N.getString("pop3.err.ntlm_failed"));
        } finally {
            resetAuthState();
        }
    }

    // ── SASL helpers ──

    private boolean authenticateAndOpenMailbox(
            String user, String password, String mechanism)
            throws IOException {
        enforceLoginDelay();

        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return false;
        }

        if (realm.passwordMatch(user, password)) {
            if (openMailbox(user)) {
                username = user;
                state = POP3State.TRANSACTION;
                recordAuthenticationSuccess("AUTH " + mechanism);
                sendOK(L10N.getString("pop3.mailbox_opened"));
                return true;
            }
        }

        failedAuthAttempts++;
        lastFailedAuthTime = System.currentTimeMillis();
        recordAuthenticationFailure("AUTH " + mechanism, user);
        return false;
    }

    private void enforceLoginDelay() {
        if (failedAuthAttempts > 0) {
            long delay = server.getLoginDelayMs();
            long elapsed =
                    System.currentTimeMillis() - lastFailedAuthTime;
            if (elapsed < delay) {
                try {
                    Thread.sleep(delay - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void resetAuthState() {
        authState = AuthState.NONE;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
        authClientNonce = null;
        authSalt = null;
        ntlmChallenge = null;
    }

    private void sendContinuation(String data) throws IOException {
        sendLine("+ " + data);
    }

    private String extractCertificateUsername(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        int partStart = 0;
        int dnLen = dn.length();
        while (partStart <= dnLen) {
            int partEnd = dn.indexOf(',', partStart);
            if (partEnd < 0) {
                partEnd = dnLen;
            }
            String trimmed = dn.substring(partStart, partEnd).trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
            partStart = partEnd + 1;
        }
        return null;
    }

    private byte[] generateNTLMType2Challenge() throws Exception {
        SecureRandom random = new SecureRandom();
        ntlmChallenge = new byte[8];
        random.nextBytes(ntlmChallenge);

        ByteBuffer buffer = ByteBuffer.allocate(56);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("NTLMSSP\0".getBytes(US_ASCII));
        buffer.putInt(2);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(56);
        buffer.putInt(0x00008205);
        buffer.put(ntlmChallenge);
        buffer.putLong(0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(56);
        return buffer.array();
    }

    private String parseNTLMType3Username(byte[] type3Message) {
        try {
            if (type3Message.length < 64
                    || !new String(type3Message, 0, 8, US_ASCII)
                            .equals("NTLMSSP\0")) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(type3Message);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.position(36);
            short userLen = buf.getShort();
            buf.getShort();
            int userOffset = buf.getInt();
            if (userOffset + userLen <= type3Message.length) {
                return new String(type3Message, userOffset,
                        userLen, "UTF-16LE");
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE,
                    "Failed to parse NTLM Type 3 username", e);
        }
        return null;
    }

    // ── ConnectedState ──

    @Override
    public void acceptConnection(String greeting,
                                  AuthorizationHandler handler) {
        this.authorizationHandler = handler;
        try {
            sendOK(greeting);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send greeting", e);
            closeEndpoint();
        }
    }

    @Override
    public void acceptConnectionWithApop(String greeting,
                                          String timestamp,
                                          AuthorizationHandler handler) {
        this.authorizationHandler = handler;
        try {
            sendOK(greeting + " " + timestamp);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send APOP greeting", e);
            closeEndpoint();
        }
    }

    @Override
    public void rejectConnection() {
        try {
            sendERR(L10N.getString(
                    "pop3.err.connection_rejected"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send rejection", e);
        }
        closeEndpoint();
    }

    @Override
    public void rejectConnection(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send rejection", e);
        }
        closeEndpoint();
    }

    @Override
    public void serverShuttingDown() {
        try {
            sendERR(L10N.getString(
                    "pop3.err.server_shutting_down"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send shutdown message", e);
        }
        closeEndpoint();
    }

    // ── AuthenticateState ──

    @Override
    public void accept(Mailbox mailbox,
                        TransactionHandler handler) {
        this.mailbox = mailbox;
        this.transactionHandler = handler;
        this.state = POP3State.TRANSACTION;
        try {
            sendOK(L10N.getString("pop3.mailbox_opened"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send mailbox opened", e);
            closeEndpoint();
        }
    }

    @Override
    public void reject(String message,
                        AuthorizationHandler handler) {
        this.authorizationHandler = handler;
        failedAuthAttempts++;
        lastFailedAuthTime = System.currentTimeMillis();
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send auth rejection", e);
        }
    }

    @Override
    public void rejectAndClose(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send rejection", e);
        }
        closeEndpoint();
    }

    // ── MailboxStatusState ──

    @Override
    public void sendStatus(int messageCount, long totalSize,
                            TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(messageCount + " " + totalSize);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send mailbox status", e);
        }
    }

    // ── ListState ──

    @Override
    public void sendListing(int messageNumber, long size,
                             TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(messageNumber + " " + size);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send LIST response", e);
        }
    }

    @Override
    public ListWriter beginListing(int messageCount) {
        try {
            sendOK(messageCount + " messages");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send LIST header", e);
        }
        return new ListWriterImpl();
    }

    @Override
    public void noSuchMessage(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(L10N.getString("pop3.err.no_such_message"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send no such message", e);
        }
    }

    @Override
    public void messageDeleted(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(L10N.getString("pop3.err.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send message deleted", e);
        }
    }

    @Override
    public void error(String message, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send error", e);
        }
    }

    private class ListWriterImpl implements ListWriter {
        @Override
        public void message(int messageNumber, long size) {
            try {
                sendLine(messageNumber + " " + size);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST entry", e);
            }
        }

        @Override
        public void end(TransactionHandler handler) {
            transactionHandler = handler;
            try {
                sendLine(".");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST terminator", e);
            }
        }
    }

    // ── RetrieveState ──

    @Override
    public void sendMessage(long size,
                             ReadableByteChannel content,
                             TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(size + " octets");
            sendMessageContent(content);
            sendLine(".");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send message", e);
        }
    }

    // ── MarkDeletedState ──

    @Override
    public void markedDeleted(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(L10N.getString("pop3.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send marked deleted", e);
        }
    }

    @Override
    public void markedDeleted(String message,
                               TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send marked deleted", e);
        }
    }

    @Override
    public void alreadyDeleted(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(L10N.getString("pop3.err.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send already deleted", e);
        }
    }

    // ── ResetState ──

    @Override
    public void resetComplete(int messageCount, long totalSize,
                               TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(MessageFormat.format(
                    L10N.getString("pop3.reset_response"),
                    messageCount, totalSize));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send reset response", e);
        }
    }

    // ── TopState ──

    @Override
    public void sendTop(ReadableByteChannel content,
                         TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(L10N.getString("pop3.top_follows"));
            sendMessageContent(content);
            sendLine(".");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send TOP", e);
        }
    }

    // ── UidlState ──

    @Override
    public void sendUid(int messageNumber, String uid,
                         TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(messageNumber + " " + uid);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send UIDL response", e);
        }
    }

    @Override
    public UidlWriter beginListing() {
        try {
            sendOK(L10N.getString("pop3.uidl_follows"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send UIDL header", e);
        }
        return new UidlWriterImpl();
    }

    private class UidlWriterImpl implements UidlWriter {
        @Override
        public void message(int messageNumber, String uid) {
            try {
                sendLine(messageNumber + " " + uid);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send UIDL entry", e);
            }
        }

        @Override
        public void end(TransactionHandler handler) {
            transactionHandler = handler;
            try {
                sendLine(".");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send UIDL terminator", e);
            }
        }
    }

    // ── UpdateState ──

    @Override
    public void commitAndClose() {
        try {
            sendOK(L10N.getString("pop3.quit_response"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send QUIT response", e);
        }
        closeEndpoint();
    }

    @Override
    public void commitAndClose(String message) {
        try {
            sendOK(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send QUIT response", e);
        }
        closeEndpoint();
    }

    @Override
    public void partialCommit(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send partial commit", e);
        }
        closeEndpoint();
    }

    @Override
    public void updateFailed(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send update failed", e);
        }
        closeEndpoint();
    }

    // ── TRANSACTION commands ──

    private void handleSTAT(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.mailboxStatus(this, mailbox);
            return;
        }
        try {
            int count = mailbox.getMessageCount();
            long size = mailbox.getMailboxSize();
            sendOK(count + " " + size);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to get mailbox statistics", e);
            sendERR(L10N.getString(
                    "pop3.err.cannot_access_mailbox"));
        }
    }

    private void handleLIST(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        int msgNum = args.isEmpty() ? 0 : parseMessageNumber(args);
        if (!args.isEmpty() && msgNum < 0) {
            sendERR(L10N.getString(
                    "pop3.err.invalid_message_number"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.list(this, mailbox, msgNum);
            return;
        }
        try {
            if (args.isEmpty()) {
                int count = mailbox.getMessageCount();
                sendOK(count + " messages");
                Iterator<MessageDescriptor> messages =
                        mailbox.getMessageList();
                while (messages.hasNext()) {
                    MessageDescriptor msg = messages.next();
                    if (msg != null
                            && !mailbox.isDeleted(
                                    msg.getMessageNumber())) {
                        sendLine(msg.getMessageNumber()
                                + " " + msg.getSize());
                    }
                }
                sendLine(".");
            } else {
                MessageDescriptor msg = mailbox.getMessage(msgNum);
                if (msg == null || mailbox.isDeleted(msgNum)) {
                    sendERR(L10N.getString(
                            "pop3.err.no_such_message"));
                } else {
                    sendOK(msgNum + " " + msg.getSize());
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to list messages", e);
            sendERR(L10N.getString(
                    "pop3.err.cannot_access_mailbox"));
        }
    }

    private void handleRETR(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        int msgNum = parseMessageNumber(args);
        if (msgNum < 0) {
            sendERR(L10N.getString(
                    "pop3.err.invalid_message_number"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.retrieveMessage(
                    this, mailbox, msgNum);
            return;
        }
        try {
            if (mailbox.isDeleted(msgNum)) {
                sendERR(L10N.getString(
                        "pop3.err.message_deleted"));
                return;
            }
            MessageDescriptor msg = mailbox.getMessage(msgNum);
            if (msg == null) {
                sendERR(L10N.getString(
                        "pop3.err.no_such_message"));
                return;
            }
            sendOK(msg.getSize() + " octets");
            ReadableByteChannel channel =
                    mailbox.getMessageContent(msgNum);
            try {
                sendMessageContent(channel);
            } finally {
                channel.close();
            }
            sendLine(".");
            recordMessageRetrieve(msgNum, msg.getSize());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to retrieve message " + msgNum, e);
            recordSessionException(e);
            sendERR(L10N.getString("pop3.err.cannot_retrieve"));
        }
    }

    private void handleDELE(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        int msgNum = parseMessageNumber(args);
        if (msgNum < 0) {
            sendERR(L10N.getString(
                    "pop3.err.invalid_message_number"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.markDeleted(this, mailbox, msgNum);
            return;
        }
        try {
            MessageDescriptor msg = mailbox.getMessage(msgNum);
            if (msg == null) {
                sendERR(L10N.getString(
                        "pop3.err.no_such_message"));
                return;
            }
            if (mailbox.isDeleted(msgNum)) {
                sendERR(L10N.getString(
                        "pop3.err.already_deleted"));
                return;
            }
            mailbox.deleteMessage(msgNum);
            recordMessageDelete(msgNum);
            sendOK(L10N.getString("pop3.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to delete message " + msgNum, e);
            recordSessionException(e);
            sendERR(L10N.getString("pop3.err.cannot_delete"));
        }
    }

    private void handleRSET(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.reset(this, mailbox);
            return;
        }
        try {
            mailbox.undeleteAll();
            int count = mailbox.getMessageCount();
            long size = mailbox.getMailboxSize();
            sendOK(MessageFormat.format(
                    L10N.getString("pop3.reset_response"),
                    count, size));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to reset mailbox", e);
            sendERR(L10N.getString("pop3.err.cannot_reset"));
        }
    }

    private void handleTOP(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        List<String> partList = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(args);
        while (st.hasMoreTokens()) {
            partList.add(st.nextToken());
        }
        String[] parts = partList.toArray(new String[0]);
        if (parts.length < 2) {
            sendERR(L10N.getString("pop3.err.top_requires_args"));
            return;
        }
        int msgNum = parseMessageNumber(parts[0]);
        int lines = parseNonNegativeNumber(parts[1]);
        if (msgNum < 0 || lines < 0) {
            sendERR(L10N.getString("pop3.err.invalid_arguments"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.top(this, mailbox, msgNum, lines);
            return;
        }
        try {
            if (mailbox.isDeleted(msgNum)) {
                sendERR(L10N.getString(
                        "pop3.err.message_deleted"));
                return;
            }
            MessageDescriptor msg = mailbox.getMessage(msgNum);
            if (msg == null) {
                sendERR(L10N.getString(
                        "pop3.err.no_such_message"));
                return;
            }
            sendOK(L10N.getString("pop3.top_follows"));
            ReadableByteChannel channel =
                    mailbox.getMessageTop(msgNum, lines);
            try {
                sendMessageContent(channel);
            } finally {
                channel.close();
            }
            sendLine(".");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to retrieve top of message " + msgNum, e);
            sendERR(L10N.getString("pop3.err.cannot_retrieve"));
        }
    }

    private void handleUIDL(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        int msgNum = args.isEmpty() ? 0 : parseMessageNumber(args);
        if (!args.isEmpty() && msgNum < 0) {
            sendERR(L10N.getString(
                    "pop3.err.invalid_message_number"));
            return;
        }
        if (transactionHandler != null) {
            transactionHandler.uidl(this, mailbox, msgNum);
            return;
        }
        try {
            if (args.isEmpty()) {
                sendOK(L10N.getString("pop3.uidl_follows"));
                Iterator<MessageDescriptor> messages =
                        mailbox.getMessageList();
                while (messages.hasNext()) {
                    MessageDescriptor msg = messages.next();
                    if (msg != null
                            && !mailbox.isDeleted(
                                    msg.getMessageNumber())) {
                        String uid = mailbox.getUniqueId(
                                msg.getMessageNumber());
                        sendLine(msg.getMessageNumber()
                                + " " + uid);
                    }
                }
                sendLine(".");
            } else {
                MessageDescriptor msg = mailbox.getMessage(msgNum);
                if (msg == null || mailbox.isDeleted(msgNum)) {
                    sendERR(L10N.getString(
                            "pop3.err.no_such_message"));
                } else {
                    String uid = mailbox.getUniqueId(msgNum);
                    sendOK(msgNum + " " + uid);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to get unique IDs", e);
            sendERR(L10N.getString(
                    "pop3.err.cannot_access_mailbox"));
        }
    }

    // ── Commands valid in all states ──

    private void handleCAPA(String args) throws IOException {
        sendOK(L10N.getString("pop3.capa_follows"));
        sendLine("USER");
        sendLine("UIDL");
        sendLine("TOP");

        Realm realm = getRealm();
        if (realm != null) {
            java.util.Set<SASLMechanism> supported =
                    realm.getSupportedSASLMechanisms();
            if (!supported.isEmpty()) {
                StringBuilder saslLine = new StringBuilder("SASL");
                for (SASLMechanism mech : supported) {
                    if (!endpoint.isSecure()
                            && mech.requiresTLS()) {
                        continue;
                    }
                    if (mech == SASLMechanism.EXTERNAL
                            && !endpoint.isSecure()) {
                        continue;
                    }
                    saslLine.append(" ")
                            .append(mech.getMechanismName());
                }
                sendLine(saslLine.toString());
            }
        }

        if (apopTimestamp != null) {
            sendLine("APOP");
        }
        if (!endpoint.isSecure()
                && server.isSTARTTLSAvailable()) {
            sendLine("STLS");
        }
        if (server.isEnableUTF8()) {
            sendLine("UTF8");
        }
        if (server.isEnablePipelining()) {
            sendLine("PIPELINING");
        }
        sendLine("IMPLEMENTATION gumdrop");
        sendLine(".");
    }

    private void handleNOOP(String args) throws IOException {
        sendOK(L10N.getString("pop3.noop"));
    }

    private void handleQUIT(String args) throws IOException {
        addSessionEvent("QUIT");

        if (state == POP3State.TRANSACTION) {
            state = POP3State.UPDATE;
            if (transactionHandler != null) {
                transactionHandler.quit(this, mailbox);
                return;
            }
            try {
                if (mailbox != null) {
                    mailbox.close(true);
                    mailbox = null;
                }
                if (store != null) {
                    store.close();
                    store = null;
                }
                sendOK(L10N.getString("pop3.quit_success"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to update mailbox", e);
                recordSessionException(e);
                sendERR(L10N.getString("pop3.quit_partial"));
            }
        } else {
            sendOK(L10N.getString("pop3.goodbye"));
        }
        closeEndpoint();
    }

    // ── Helpers ──

    private boolean openMailbox(String user) throws IOException {
        MailboxFactory factory = server.getMailboxFactory();
        if (factory == null) {
            sendERR(L10N.getString(
                    "pop3.err.mailbox_not_configured"));
            return false;
        }

        if (authorizationHandler != null) {
            final String finalUser = user;
            Principal principal = new Principal() {
                @Override
                public String getName() {
                    return finalUser;
                }

                @Override
                public String toString() {
                    return "POP3Principal[" + finalUser + "]";
                }
            };
            authorizationHandler.authenticate(
                    this, principal, factory);
            return state == POP3State.TRANSACTION;
        }

        try {
            store = factory.createStore();
            store.open(user);
            mailbox = store.openMailbox("INBOX", false);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to open mailbox for user: " + user, e);
            if (store != null) {
                try {
                    store.close();
                } catch (IOException ce) {
                    LOGGER.log(Level.FINE,
                            "Error closing store after failure", ce);
                }
                store = null;
            }
            sendERR(L10N.getString(
                    "pop3.err.cannot_open_mailbox"));
            return false;
        }
    }

    private int parseMessageNumber(String str) {
        try {
            int num = Integer.parseInt(str);
            return (num > 0) ? num : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int parseNonNegativeNumber(String str) {
        try {
            int num = Integer.parseInt(str);
            return (num >= 0) ? num : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void sendMessageContent(ReadableByteChannel channel)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        StringBuilder lineBuilder = new StringBuilder();

        while (channel.read(buffer) != -1
                || buffer.position() > 0) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                if (b == '\n') {
                    String line = lineBuilder.toString();
                    if (line.endsWith("\r")) {
                        line = line.substring(
                                0, line.length() - 1);
                    }
                    if (line.startsWith(".")) {
                        sendLine("." + line);
                    } else {
                        sendLine(line);
                    }
                    lineBuilder.setLength(0);
                } else {
                    lineBuilder.append((char) (b & 0xFF));
                }
            }
            buffer.clear();
        }

        if (lineBuilder.length() > 0) {
            String line = lineBuilder.toString();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.startsWith(".")) {
                sendLine("." + line);
            } else {
                sendLine(line);
            }
        }
    }

    // ── Telemetry ──

    private void initConnectionTrace() {
        if (endpoint.isTelemetryEnabled()) {
            TelemetryConfig config = endpoint.getTelemetryConfig();
            String traceName = L10N.getString(
                    "telemetry.pop3_connection");
            connectionTrace = config.createTrace(traceName);
            Span rootSpan = connectionTrace.getRootSpan();
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip",
                    endpoint.getRemoteAddress().toString());
            rootSpan.addAttribute("rpc.system", "pop3");
        }
    }

    private void startSessionSpan() {
        if (connectionTrace != null) {
            String spanName = L10N.getString(
                    "telemetry.pop3_session");
            sessionSpan = connectionTrace.startSpan(
                    spanName, SpanKind.SERVER);
            sessionSpan.addEvent("SESSION_START");
        }
    }

    private void startAuthenticatedSpan() {
        if (sessionSpan != null) {
            String spanName = L10N.getString(
                    "telemetry.pop3_authenticated");
            authenticatedSpan = sessionSpan.startChild(
                    spanName, SpanKind.SERVER);
            authenticatedSpan.addAttribute(
                    "enduser.id", username);
            authenticatedSpan.addEvent("AUTHENTICATED");
        }
    }

    private void endSessionSpan() {
        if (sessionSpan != null) {
            sessionSpan.addEvent("SESSION_END");
            sessionSpan.setStatusOk();
            sessionSpan.end();
            sessionSpan = null;
        }
        if (connectionTrace != null) {
            connectionTrace.end();
            connectionTrace = null;
        }
    }

    private void endSessionSpanError(String error) {
        if (sessionSpan != null) {
            sessionSpan.addEvent("SESSION_ERROR");
            sessionSpan.setStatusError(error);
            sessionSpan.end();
            sessionSpan = null;
        }
        if (connectionTrace != null) {
            connectionTrace.end();
            connectionTrace = null;
        }
    }

    private void endAuthenticatedSpan() {
        if (authenticatedSpan != null) {
            authenticatedSpan.setStatusOk();
            authenticatedSpan.end();
            authenticatedSpan = null;
        }
    }

    private void addSessionEvent(String event) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(event);
        }
    }

    private void recordSessionException(Exception e) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordExceptionWithCategory(e);
        }
    }

    private void recordAuthenticationSuccess(String mechanism) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(
                    "pop3.auth.mechanism", mechanism);
            sessionSpan.addAttribute("enduser.id", username);
        }
        addSessionEvent("AUTH_SUCCESS");
        startAuthenticatedSpan();
    }

    private void recordAuthenticationFailure(
            String mechanism, String user) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(
                    "pop3.auth.mechanism", mechanism);
            if (user != null) {
                sessionSpan.addAttribute(
                        "pop3.auth.attempted_user", user);
            }
        }
        addSessionEvent("AUTH_FAILURE");
    }

    private void recordStartTLSSuccess() {
        addSessionEvent("STARTTLS_SUCCESS");
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(
                    "net.protocol.name", "pop3s");
        }
    }

    private void recordStartTLSFailure(Exception e) {
        addSessionEvent("STARTTLS_FAILURE");
        recordSessionException(e);
    }

    private void recordMessageRetrieve(int msgNum, long size) {
        if (authenticatedSpan != null
                && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addEvent("RETR");
            authenticatedSpan.addAttribute(
                    "pop3.message.number", msgNum);
            authenticatedSpan.addAttribute(
                    "pop3.message.size", size);
        }
    }

    private void recordMessageDelete(int msgNum) {
        if (authenticatedSpan != null
                && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addEvent("DELE");
            authenticatedSpan.addAttribute(
                    "pop3.message.number", (long) msgNum);
        }
    }
}
