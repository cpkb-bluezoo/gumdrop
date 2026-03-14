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
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
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
import org.bluezoo.gumdrop.auth.GSSAPIServer;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.SASLMechanism;
import org.bluezoo.gumdrop.auth.SASLUtils;
import org.bluezoo.gumdrop.mime.HeaderLineTooLongException;
import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
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
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.util.ByteArrays;

/**
 * POP3 server protocol handler (RFC 1939).
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
 * <p>POP3 Protocol States (RFC 1939 section 3):
 * <ul>
 *   <li>AUTHORIZATION — initial state, authentication required</li>
 *   <li>TRANSACTION — authenticated, mailbox access</li>
 *   <li>UPDATE — after QUIT, commit deletions</li>
 * </ul>
 *
 * <p>Supported extensions:
 * <ul>
 *   <li>CAPA — RFC 2449 (extension mechanism)</li>
 *   <li>STLS — RFC 2595 section 4 (STARTTLS for POP3)</li>
 *   <li>AUTH — RFC 5034 (SASL authentication), mechanisms:
 *       PLAIN (RFC 4616), LOGIN, CRAM-MD5 (RFC 2195),
 *       DIGEST-MD5 (RFC 2831), SCRAM (RFC 5802),
 *       OAUTHBEARER (RFC 7628), EXTERNAL (RFC 4422 Appendix A)</li>
 *   <li>UTF8 — RFC 6816 (UTF-8 support)</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see LineParser
 * @see POP3Listener
 * @see <a href="https://www.rfc-editor.org/rfc/rfc1939">RFC 1939 — POP3</a>
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

    // RFC 1939 section 3 — POP3 session states
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
        EXTERNAL_CERT
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
    private GSSAPIServer.GSSAPIExchange gssapiExchange;
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

            // SASL continuation data must preserve original case
            if (state == POP3State.AUTHORIZATION
                    && authState != AuthState.NONE) {
                String rawLine = charBuffer.toString();
                handleAuthContinuation(rawLine);
                return;
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
        ByteBuffer lineBuf = US_ASCII.encode(line);
        ByteBuffer buf = ByteBuffer.allocate(lineBuf.remaining() + 2);
        buf.put(lineBuf);
        buf.put((byte) '\r');
        buf.put((byte) '\n');
        buf.flip();
        endpoint.send(buf);
    }

    // RFC 1939 section 3 — +OK positive status indicator
    private void sendOK(String message) throws IOException {
        sendLine("+OK " + message);
    }

    // RFC 1939 section 3 — -ERR negative status indicator
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

    // ── Greeting (RFC 1939 section 4) ──

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

    // RFC 1939 section 4 — server greeting with optional APOP timestamp
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

    // ── APOP timestamp (RFC 1939 section 7 — APOP command) ──

    // RFC 1939 section 7 — APOP timestamp in angle brackets
    private String generateAPOPTimestamp() {
        long pid = getProcessId();
        long timestamp = System.currentTimeMillis();
        return "<" + pid + "." + timestamp + "@pop3>";
    }

    private static long getProcessId() {
        return ProcessHandle.current().pid();
    }

    // ── Command dispatch (RFC 1939 section 3 — state-based) ──

    // RFC 1939 section 3 — dispatch by protocol state
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

    // ── AUTHORIZATION commands (RFC 1939 section 4, 7) ──

    // RFC 1939 section 7 — USER command (AUTHORIZATION state)
    private void handleUSER(String args) throws IOException {
        if (args.isEmpty()) {
            sendERR(L10N.getString("pop3.err.username_required"));
            return;
        }
        username = args;
        sendOK(L10N.getString("pop3.user_accepted"));
    }

    // RFC 1939 section 7 — PASS command (must follow USER)
    private void handlePASS(String args) throws IOException {
        if (username == null) {
            sendERR(L10N.getString("pop3.err.user_command_required"));
            return;
        }

        final String passUsername = username;
        enforceLoginDelay(() -> {
            try {
                Realm realm = getRealm();
                if (realm == null) {
                    sendERR(L10N.getString(
                            "pop3.err.auth_not_configured"));
                    closeEndpoint();
                    return;
                }

                if (realm.passwordMatch(passUsername, args)) {
                    if (openMailbox(passUsername)) {
                        state = POP3State.TRANSACTION;
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info(
                                    "POP3 USER/PASS auth successful: "
                                            + passUsername);
                        }
                        recordAuthenticationSuccess("USER/PASS");
                        sendOK(L10N.getString("pop3.mailbox_opened"));
                    }
                } else {
                    failedAuthAttempts++;
                    lastFailedAuthTime = System.currentTimeMillis();
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning(
                                "POP3 USER/PASS auth failed: "
                                        + passUsername);
                    }
                    recordAuthenticationFailure("USER/PASS",
                            passUsername);
                    username = null;
                    sendERR(L10N.getString("pop3.err.auth_failed"));
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        "Error during PASS authentication", e);
                closeEndpoint();
            }
        });
    }

    // RFC 1939 section 7 — APOP command (MD5-based challenge-response)
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

        final String user = args.substring(0, spaceIndex);
        final String clientDigest = args.substring(spaceIndex + 1);

        enforceLoginDelay(() -> {
            try {
                Realm realm = getRealm();
                if (realm == null) {
                    sendERR(L10N.getString(
                            "pop3.err.auth_not_configured"));
                    closeEndpoint();
                    return;
                }

                try {
                    String expected =
                            realm.getApopResponse(user,
                                    apopTimestamp);
                    if (expected != null
                            && expected.equalsIgnoreCase(
                                    clientDigest)) {
                        username = user;
                        if (openMailbox(username)) {
                            state = POP3State.TRANSACTION;
                            if (LOGGER.isLoggable(Level.INFO)) {
                                LOGGER.info(
                                        "POP3 APOP auth successful: "
                                                + user);
                            }
                            recordAuthenticationSuccess("APOP");
                            sendOK(L10N.getString(
                                    "pop3.mailbox_opened"));
                        }
                        return;
                    }

                    failedAuthAttempts++;
                    lastFailedAuthTime =
                            System.currentTimeMillis();
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning(
                                "POP3 APOP auth failed: " + user);
                    }
                    recordAuthenticationFailure("APOP", user);
                    sendERR(L10N.getString(
                            "pop3.err.auth_failed"));

                } catch (UnsupportedOperationException e) {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.warning(L10N.getString(
                                "log.apop_not_supported"));
                    }
                    sendERR(L10N.getString(
                            "pop3.err.apop_not_available"));
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        "Error during APOP authentication", e);
                closeEndpoint();
            }
        });
    }

    // RFC 2595 section 4 — STLS command (STARTTLS for POP3)
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

    // RFC 6816 section 2 — UTF8 command (AUTHORIZATION state only)
    private void handleUTF8(String args) throws IOException {
        if (!server.isEnableUTF8()) {
            sendERR(L10N.getString("pop3.err.utf8_not_supported"));
            return;
        }
        utf8Mode = true;
        sendOK(L10N.getString("pop3.utf8_enabled"));
    }

    // ── SASL AUTH (RFC 5034 — POP3 SASL Authentication Mechanism) ──

    // RFC 5034 section 4 — AUTH command with optional initial response
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
                // RFC 4752 — advertise GSSAPI when configured
                if (server.getGSSAPIServer() != null) {
                    sendLine("GSSAPI");
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
            default:
                sendERR(L10N.getString(
                        "pop3.err.unsupported_mechanism"));
        }
    }

    // RFC 4616 — SASL PLAIN mechanism
    private void handleAuthPLAIN(String initialResponse)
            throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            authState = AuthState.PLAIN_RESPONSE;
            sendContinuation("");
            return;
        }
        processPlainCredentials(initialResponse);
    }

    // SASL LOGIN mechanism (draft-murchison-sasl-login)
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

    // RFC 2195 — SASL CRAM-MD5 mechanism
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

    // RFC 2831 — SASL DIGEST-MD5 mechanism
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

    // RFC 5802 / RFC 7677 — SASL SCRAM-SHA-256 mechanism
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

    // RFC 7628 — SASL OAUTHBEARER mechanism
    private void handleAuthOAUTHBEARER(String initialResponse)
            throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            authState = AuthState.OAUTH_RESPONSE;
            sendContinuation("");
            return;
        }
        processOAuthBearerCredentials(initialResponse);
    }

    // RFC 4752 — SASL GSSAPI mechanism (Kerberos V5)
    private void handleAuthGSSAPI(String initialResponse)
            throws IOException {
        GSSAPIServer gssapiServer = server.getGSSAPIServer();
        if (gssapiServer == null) {
            sendERR(L10N.getString("pop3.err.gssapi_not_available"));
            return;
        }
        try {
            gssapiExchange = gssapiServer.createExchange();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "GSSAPI exchange creation failed", e);
            sendERR(L10N.getString("pop3.err.gssapi_not_available"));
            return;
        }
        authState = AuthState.GSSAPI_EXCHANGE;
        if (initialResponse != null && !initialResponse.isEmpty()) {
            processGSSAPIToken(initialResponse);
        } else {
            sendContinuation("");
        }
    }

    // RFC 4752 §3.1 — processes a GSSAPI token exchange step
    private void processGSSAPIToken(String line) throws IOException {
        try {
            byte[] clientToken = SASLUtils.decodeBase64(line);
            byte[] responseToken = gssapiExchange.acceptToken(clientToken);

            if (gssapiExchange.isContextEstablished()) {
                byte[] challenge =
                        gssapiExchange.generateSecurityLayerChallenge();
                String encoded = SASLUtils.encodeBase64(challenge);
                sendContinuation(encoded);
                return;
            }

            if (responseToken != null && responseToken.length > 0) {
                String encoded = SASLUtils.encodeBase64(responseToken);
                sendContinuation(encoded);
            } else {
                sendContinuation("");
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "GSSAPI token rejected", e);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            resetAuthState();
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
            resetAuthState();
        }
    }

    // RFC 4752 §3.1 para 7-8 — processes the security layer response
    private void processGSSAPISecurityLayer(String line) throws IOException {
        try {
            byte[] wrapped = SASLUtils.decodeBase64(line);
            String gssName =
                    gssapiExchange.validateSecurityLayerResponse(wrapped);
            Realm realm = getRealm();
            String localUser = null;
            if (realm != null) {
                localUser = realm.mapKerberosPrincipal(gssName);
            }
            if (localUser == null) {
                localUser = gssName;
                int atIndex = localUser.indexOf('@');
                if (atIndex > 0) {
                    localUser = localUser.substring(0, atIndex);
                }
            }
            if (openMailbox(localUser)) {
                username = localUser;
                state = POP3State.TRANSACTION;
                recordAuthenticationSuccess("AUTH GSSAPI");
                sendOK(L10N.getString("pop3.mailbox_opened"));
            } else {
                recordAuthenticationFailure("AUTH GSSAPI", localUser);
                sendERR(L10N.getString("pop3.err.auth_failed"));
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "GSSAPI security layer failed", e);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    // RFC 4422 Appendix A — SASL EXTERNAL mechanism (TLS client cert)
    private void handleAuthEXTERNAL(String initialResponse)
            throws IOException {
        String authzid = null;
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

        Realm.CertificateAuthenticationResult result =
                SASLUtils.authenticateExternal(
                        endpoint, getRealm(), authzid);
        if (result == null || !result.valid) {
            recordAuthenticationFailure("AUTH EXTERNAL", authzid);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            return;
        }

        if (openMailbox(result.username)) {
            username = result.username;
            state = POP3State.TRANSACTION;
            recordAuthenticationSuccess("AUTH EXTERNAL");
            sendOK(L10N.getString("pop3.mailbox_opened"));
        } else {
            recordAuthenticationFailure("AUTH EXTERNAL",
                    result.username);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        }
    }

    // ── SASL continuation (RFC 5034 section 4 — "+" continuation) ──

    // RFC 5034 section 4 — client cancellation with "*"
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
            case GSSAPI_EXCHANGE:
                if (gssapiExchange != null
                        && gssapiExchange.isContextEstablished()) {
                    processGSSAPISecurityLayer(data);
                } else {
                    processGSSAPIToken(data);
                }
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

            authenticateAndOpenMailbox(user, password, "PLAIN",
                    () -> {
                        try {
                            sendERR(L10N.getString(
                                    "pop3.err.auth_failed"));
                        } catch (IOException e) {
                            LOGGER.log(Level.SEVERE,
                                    "Error sending auth failure", e);
                            closeEndpoint();
                        } finally {
                            resetAuthState();
                        }
                    });
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
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
            authenticateAndOpenMailbox(
                    pendingAuthUsername, password, "LOGIN",
                    () -> {
                        try {
                            sendERR(L10N.getString(
                                    "pop3.err.auth_failed"));
                        } catch (IOException e) {
                            LOGGER.log(Level.SEVERE,
                                    "Error sending auth failure", e);
                            closeEndpoint();
                        } finally {
                            resetAuthState();
                        }
                    });
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
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

    // ── SASL helpers ──

    private void authenticateAndOpenMailbox(
            String user, String password, String mechanism,
            Runnable onFailure) {
        enforceLoginDelay(() -> {
            try {
                Realm realm = getRealm();
                if (realm == null) {
                    sendERR(L10N.getString(
                            "pop3.err.auth_not_configured"));
                    return;
                }

                if (realm.passwordMatch(user, password)) {
                    if (openMailbox(user)) {
                        username = user;
                        state = POP3State.TRANSACTION;
                        recordAuthenticationSuccess(
                                "AUTH " + mechanism);
                        sendOK(L10N.getString(
                                "pop3.mailbox_opened"));
                        return;
                    }
                }

                failedAuthAttempts++;
                lastFailedAuthTime = System.currentTimeMillis();
                recordAuthenticationFailure(
                        "AUTH " + mechanism, user);
                onFailure.run();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        "Error during authentication", e);
                closeEndpoint();
            }
        });
    }

    private void enforceLoginDelay(Runnable continuation) {
        if (failedAuthAttempts > 0) {
            long delay = server.getLoginDelayMs();
            long elapsed =
                    System.currentTimeMillis() - lastFailedAuthTime;
            if (elapsed < delay) {
                endpoint.scheduleTimer(delay - elapsed, continuation);
                return;
            }
        }
        continuation.run();
    }

    private void resetAuthState() {
        authState = AuthState.NONE;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
        authClientNonce = null;
        authSalt = null;
        if (gssapiExchange != null) {
            gssapiExchange.dispose();
            gssapiExchange = null;
        }
    }

    // RFC 5034 section 4 — "+" continuation for SASL exchanges
    private void sendContinuation(String data) throws IOException {
        sendLine("+ " + data);
    }

    // ── ConnectedState (RFC 1939 section 4 — server greeting) ──

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

    // ── AuthenticateState (RFC 1939 section 4 — AUTHORIZATION→TRANSACTION) ──

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

    // ── MailboxStatusState (RFC 1939 section 5 — STAT response) ──

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

    // ── ListState (RFC 1939 section 5 — LIST response) ──

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

    // ── RetrieveState (RFC 1939 section 5 — RETR response) ──

    @Override
    public void sendMessage(long size,
                             ReadableByteChannel content,
                             TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(size + " octets");
            startChunkedMessageWrite(content, null);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send message", e);
        }
    }

    @Override
    public void sendMessage(long size,
                             AsyncMessageContent content,
                             TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(size + " octets");
            startChunkedMessageWrite(content, null);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to send async message", e);
        }
    }

    // ── MarkDeletedState (RFC 1939 section 5 — DELE response) ──

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

    // ── ResetState (RFC 1939 section 5 — RSET response) ──

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

    // ── TopState (RFC 1939 section 7 — TOP response) ──

    @Override
    public void sendTop(ReadableByteChannel content,
                         TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(L10N.getString("pop3.top_follows"));
            startChunkedMessageWrite(content, null);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send TOP", e);
        }
    }

    // ── UidlState (RFC 1939 section 7 — UIDL response) ──

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

    // ── UpdateState (RFC 1939 section 6 — commit deletions, close) ──

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

    // RFC 1939 section 5 — STAT command (TRANSACTION state)
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

    // RFC 1939 section 5 — LIST command (optional msg number argument)
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

    // RFC 1939 section 5 — RETR command (retrieve message)
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
            long messageSize = msg.getSize();
            sendOK(messageSize + " octets");
            RetrCompletion retrCompletion =
                    new RetrCompletion(msgNum, messageSize);
            AsyncMessageContent asyncContent = null;
            try {
                asyncContent = mailbox.openAsyncContent(msgNum);
            } catch (IOException ignored) {
                /* Fall back to sync channel when async unavailable */
            }
            if (asyncContent != null) {
                startChunkedMessageWrite(asyncContent, retrCompletion);
            } else {
                ReadableByteChannel channel =
                        mailbox.getMessageContent(msgNum);
                startChunkedMessageWrite(channel, retrCompletion);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to retrieve message " + msgNum, e);
            recordSessionException(e);
            sendERR(L10N.getString("pop3.err.cannot_retrieve"));
        }
    }

    // RFC 1939 section 5 — DELE command (mark message for deletion)
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

    // RFC 1939 section 5 — RSET command (unmark all deleted messages)
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

    // RFC 1939 section 7 — TOP command (optional, headers + n lines)
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
            AsyncMessageContent asyncContent = null;
            try {
                asyncContent = mailbox.openAsyncContent(msgNum);
            } catch (IOException ignored) {
                /* Fall back to sync channel when async unavailable */
            }
            if (asyncContent != null) {
                long bodyOff = asyncContent.bodyOffset();
                if (bodyOff >= 0 && lines >= 0) {
                    long topEnd;
                    try {
                        topEnd = mailbox.getMessageTopEndOffset(
                                msgNum, lines);
                    } catch (UnsupportedOperationException e2) {
                        topEnd = asyncContent.size();
                    }
                    startChunkedMessageWrite(asyncContent, topEnd,
                            null);
                } else {
                    asyncContent.close();
                    ReadableByteChannel channel =
                            mailbox.getMessageTop(msgNum, lines);
                    startChunkedMessageWrite(channel, null);
                }
            } else {
                ReadableByteChannel channel =
                        mailbox.getMessageTop(msgNum, lines);
                startChunkedMessageWrite(channel, null);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to retrieve top of message " + msgNum, e);
            String msg = (e.getCause() instanceof HeaderLineTooLongException)
                ? L10N.getString("pop3.err.header_line_too_long")
                : L10N.getString("pop3.err.cannot_retrieve");
            sendERR(msg);
        }
    }

    // RFC 1939 section 7 — UIDL command (optional, unique-id listing)
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

    // ── Commands valid in all states (RFC 1939 section 5, RFC 2449) ──

    // RFC 2449 section 5 — CAPA command (capability listing)
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

        // RFC 2449 section 8 — extended response codes
        sendLine("RESP-CODES");

        // RFC 3206 — authentication-specific response codes
        sendLine("AUTH-RESP-CODE");

        // RFC 2449 section 6.5 — message retention policy
        int expireDays = server.getExpireDays();
        if (expireDays >= 0) {
            if (expireDays == Integer.MAX_VALUE) {
                sendLine("EXPIRE NEVER");
            } else {
                sendLine("EXPIRE " + expireDays);
            }
        }

        // RFC 2449 section 6.6 — minimum login delay
        long loginDelayMs = server.getLoginDelayMs();
        if (loginDelayMs > 0) {
            long delaySec = Math.max(1, loginDelayMs / 1000);
            sendLine("LOGIN-DELAY " + delaySec);
        }

        sendLine("IMPLEMENTATION gumdrop");
        sendLine(".");
    }

    // RFC 1939 section 5 — NOOP command
    private void handleNOOP(String args) throws IOException {
        sendOK(L10N.getString("pop3.noop"));
    }

    // RFC 1939 section 6 — QUIT in TRANSACTION state enters UPDATE state;
    // RFC 1939 section 5 — QUIT in AUTHORIZATION state closes connection
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

    /**
     * Begins a chunked message write from an {@link AsyncMessageContent},
     * reading the full content.
     */
    private void startChunkedMessageWrite(AsyncMessageContent content,
            Runnable completion) {
        startChunkedMessageWrite(content, content.size(), completion);
    }

    /**
     * Begins a chunked message write from an {@link AsyncMessageContent},
     * reading up to {@code endOffset} bytes.
     */
    private void startChunkedMessageWrite(AsyncMessageContent content,
            long endOffset, Runnable completion) {
        MessageContentWriter writer =
                new MessageContentWriter(content, endOffset, completion);
        writer.writeNextChunk();
    }

    /**
     * Begins a chunked message content write with backpressure.
     * The writer reads data from the channel in chunks, sends each
     * chunk via the endpoint, and waits for the write buffer to drain
     * before sending the next chunk.  When finished, it sends the
     * POP3 terminator line and runs the completion callback.
     */
    private void startChunkedMessageWrite(ReadableByteChannel channel,
            Runnable completion) {
        MessageContentWriter writer =
                new MessageContentWriter(null, channel, completion);
        writer.writeNextChunk();
    }

    /**
     * Chunked message content writer with write-pacing.
     * Reads from AsynchronousFileChannel or ReadableByteChannel in batches,
     * uses onWriteReady to pace output. Dot-stuffing and line-counting
     * run on the SelectorLoop thread.
     *
     * <p>RFC 1939 section 3 — multi-line responses use byte-stuffing
     * (lines beginning with "." are prefixed with an extra ".") and are
     * terminated by a line containing only ".".
     */
    private class MessageContentWriter implements Runnable {

        private static final int CHUNK_SIZE = 32768;
        private static final int MAX_LINES_PER_CHUNK = 512;

        private final AsynchronousFileChannel asyncChannel;
        private final AsyncMessageContent asyncContent;
        private final ReadableByteChannel channel;
        private final Runnable completion;
        private final ByteBuffer readBuf;
        private final StringBuilder lineBuilder;
        private long filePosition;
        private long readLimit;
        private boolean channelExhausted;
        private ByteBuffer pendingAsyncBuf;

        MessageContentWriter(AsynchronousFileChannel asyncChannel,
                ReadableByteChannel channel,
                Runnable completion) {
            this.asyncChannel = asyncChannel;
            this.asyncContent = null;
            this.channel = channel;
            this.completion = completion;
            this.readBuf = ByteBuffer.allocate(CHUNK_SIZE);
            this.lineBuilder = new StringBuilder();
            this.readLimit = Long.MAX_VALUE;
        }

        MessageContentWriter(AsyncMessageContent asyncContent,
                long readLimit, Runnable completion) {
            this.asyncChannel = null;
            this.asyncContent = asyncContent;
            this.channel = null;
            this.completion = completion;
            this.readBuf = ByteBuffer.allocate(CHUNK_SIZE);
            this.lineBuilder = new StringBuilder();
            this.readLimit = readLimit;
        }

        @Override
        public void run() {
            writeNextChunk();
        }

        void writeNextChunk() {
            if (asyncContent != null) {
                writeNextChunkAsyncContent();
            } else if (asyncChannel != null) {
                writeNextChunkAsync();
            } else {
                writeNextChunkSync();
            }
        }

        private void writeNextChunkAsyncContent() {
            if (pendingAsyncBuf != null) {
                processBufferAndContinue(null);
                return;
            }
            if (filePosition >= readLimit) {
                channelExhausted = true;
                processBufferAndContinue(null);
                return;
            }
            int toRead = (int) Math.min(CHUNK_SIZE,
                    readLimit - filePosition);
            ByteBuffer buf = ByteBufferPool.acquire(toRead);
            buf.limit(toRead);
            asyncContent.read(buf, filePosition,
                    new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer result,
                        ByteBuffer attachment) {
                    if (result == null || result <= 0) {
                        ByteBufferPool.release(attachment);
                        endpoint.execute(() -> {
                            channelExhausted = true;
                            processBufferAndContinue(null);
                        });
                        return;
                    }
                    filePosition += result;
                    attachment.flip();
                    endpoint.execute(() -> {
                        processBufferAndContinue(attachment);
                    });
                }

                @Override
                public void failed(Throwable exc,
                        ByteBuffer attachment) {
                    ByteBufferPool.release(attachment);
                    LOGGER.log(Level.WARNING,
                            "Async content read failed", exc);
                    endpoint.execute(() -> {
                        closeChannels();
                        endpoint.onWriteReady(null);
                    });
                }
            });
        }

        private void writeNextChunkAsync() {
            if (pendingAsyncBuf != null) {
                processBufferAndContinue(null);
                return;
            }
            ByteBuffer buf = ByteBufferPool.acquire(CHUNK_SIZE);
            asyncChannel.read(buf, filePosition, null,
                    new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer result, Void attachment) {
                            if (result == null || result <= 0) {
                                ByteBufferPool.release(buf);
                                endpoint.execute(() -> {
                                    channelExhausted = true;
                                    processBufferAndContinue(null);
                                });
                                return;
                            }
                            final int bytesRead = result;
                            filePosition += bytesRead;
                            buf.flip();
                            endpoint.execute(() -> {
                                processBufferAndContinue(buf);
                            });
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            ByteBufferPool.release(buf);
                            LOGGER.log(Level.WARNING,
                                    "Async file read failed during message write", exc);
                            endpoint.execute(() -> {
                                closeChannels();
                                endpoint.onWriteReady(null);
                            });
                        }
                    });
        }

        private void processBufferAndContinue(ByteBuffer buf) {
            if (buf != null) {
                if (pendingAsyncBuf != null) {
                    ByteBufferPool.release(pendingAsyncBuf);
                }
                pendingAsyncBuf = buf;
            }
            try {
                int linesWritten = 0;
                ByteBuffer toProcess = pendingAsyncBuf;

                while (linesWritten < MAX_LINES_PER_CHUNK) {
                    if (toProcess == null || !toProcess.hasRemaining()) {
                        if (pendingAsyncBuf != null && !pendingAsyncBuf.hasRemaining()) {
                            ByteBufferPool.release(pendingAsyncBuf);
                            pendingAsyncBuf = null;
                        }
                        break;
                    }

                    while (toProcess.hasRemaining()
                            && linesWritten < MAX_LINES_PER_CHUNK) {
                        byte b = toProcess.get();
                        if (b == '\n') {
                            String line = stripCR(lineBuilder);
                            lineBuilder.setLength(0);
                            if (line.startsWith(".")) {
                                sendLine("." + line);
                            } else {
                                sendLine(line);
                            }
                            linesWritten++;
                        } else {
                            lineBuilder.append((char) (b & 0xFF));
                        }
                    }
                }

                boolean hasMore = (pendingAsyncBuf != null
                                && pendingAsyncBuf.hasRemaining())
                        || !channelExhausted;

                if (hasMore) {
                    endpoint.onWriteReady(this);
                } else {
                    if (pendingAsyncBuf != null) {
                        ByteBufferPool.release(pendingAsyncBuf);
                        pendingAsyncBuf = null;
                    }
                    finishTransfer();
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error during chunked message write", e);
                if (pendingAsyncBuf != null) {
                    ByteBufferPool.release(pendingAsyncBuf);
                    pendingAsyncBuf = null;
                }
                closeChannels();
                endpoint.onWriteReady(null);
            }
        }

        private void writeNextChunkSync() {
            try {
                int linesWritten = 0;

                while (linesWritten < MAX_LINES_PER_CHUNK) {
                    if (!readBuf.hasRemaining() && !channelExhausted) {
                        readBuf.clear();
                        int bytesRead = channel.read(readBuf);
                        if (bytesRead == -1) {
                            channelExhausted = true;
                        }
                        readBuf.flip();
                    }

                    if (!readBuf.hasRemaining()) {
                        break;
                    }

                    while (readBuf.hasRemaining()
                            && linesWritten < MAX_LINES_PER_CHUNK) {
                        byte b = readBuf.get();
                        if (b == '\n') {
                            String line = stripCR(lineBuilder);
                            lineBuilder.setLength(0);
                            if (line.startsWith(".")) {
                                sendLine("." + line);
                            } else {
                                sendLine(line);
                            }
                            linesWritten++;
                        } else {
                            lineBuilder.append((char) (b & 0xFF));
                        }
                    }
                }

                boolean hasMore = readBuf.hasRemaining()
                        || !channelExhausted;

                if (hasMore) {
                    endpoint.onWriteReady(this);
                } else {
                    finishTransfer();
                }

            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error during chunked message write", e);
                closeChannels();
                endpoint.onWriteReady(null);
            }
        }

        private void finishTransfer() {
            try {
                if (lineBuilder.length() > 0) {
                    String line = stripCR(lineBuilder);
                    lineBuilder.setLength(0);
                    if (line.startsWith(".")) {
                        sendLine("." + line);
                    } else {
                        sendLine(line);
                    }
                }
                sendLine(".");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error finishing chunked message write", e);
            }
            closeChannels();
            endpoint.onWriteReady(null);
            if (completion != null) {
                completion.run();
            }
        }

        private void closeChannels() {
            if (asyncContent != null) {
                try {
                    asyncContent.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing async content", e);
                }
            }
            if (asyncChannel != null) {
                try {
                    asyncChannel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing async message channel", e);
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing message channel", e);
                }
            }
        }
    }

    /**
     * Completion callback for RETR that records the message retrieval
     * in telemetry after the chunked write finishes.
     */
    private class RetrCompletion implements Runnable {
        private final int messageNumber;
        private final long messageSize;

        RetrCompletion(int messageNumber, long messageSize) {
            this.messageNumber = messageNumber;
            this.messageSize = messageSize;
        }

        @Override
        public void run() {
            recordMessageRetrieve(messageNumber, messageSize);
        }
    }

    /** Strips all CR characters from a line builder to prevent response smuggling. */
    private static String stripCR(StringBuilder sb) {
        StringBuilder result = new StringBuilder(sb.length());
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c != '\r') {
                result.append(c);
            }
        }
        return result.toString();
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
