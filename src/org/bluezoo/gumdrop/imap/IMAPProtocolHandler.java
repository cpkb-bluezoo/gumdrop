/*
 * IMAPProtocolHandler.java
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

package org.bluezoo.gumdrop.imap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
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
import org.bluezoo.gumdrop.imap.handler.AppendDataHandler;
import org.bluezoo.gumdrop.imap.handler.AuthenticatedHandler;
import org.bluezoo.gumdrop.imap.handler.AuthenticateState;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.imap.handler.ConnectedState;
import org.bluezoo.gumdrop.imap.handler.NotAuthenticatedHandler;
import org.bluezoo.gumdrop.imap.handler.SelectedHandler;
import org.bluezoo.gumdrop.imap.handler.SelectState;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaPolicy;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * IMAP protocol handler using {@link ProtocolHandler} and
 * {@link LineParser}.
 *
 * <p>Implements the IMAP protocol with the transport layer fully decoupled:
 * <ul>
 * <li>Transport operations delegate to an {@link Endpoint} reference
 *     received in {@link #connected(Endpoint)}</li>
 * <li>Line parsing uses the composable {@link LineParser} utility</li>
 * <li>TLS upgrade uses {@link Endpoint#startTLS()}</li>
 * <li>Security info uses {@link Endpoint#getSecurityInfo()}</li>
 * </ul>
 *
 * <p>IMAP Protocol States (RFC 9051 Section 3):
 * <ul>
 *   <li>NOT_AUTHENTICATED - initial state, authentication required</li>
 *   <li>AUTHENTICATED - logged in, can list/select mailboxes</li>
 *   <li>SELECTED - mailbox selected, can access messages</li>
 *   <li>LOGOUT - connection closing</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see LineParser
 * @see IMAPListener
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 - IMAP4rev2</a>
 */
public class IMAPProtocolHandler implements ProtocolHandler, LineParser.Callback {

    private static final Logger LOGGER =
            Logger.getLogger(IMAPProtocolHandler.class.getName());
    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.imap.L10N");

    static final Charset US_ASCII = StandardCharsets.US_ASCII;
    static final Charset UTF_8 = StandardCharsets.UTF_8;
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    private static final String CRLF = "\r\n";
    private static final SecureRandom RANDOM = new SecureRandom();

    enum IMAPState {
        NOT_AUTHENTICATED,
        AUTHENTICATED,
        SELECTED,
        LOGOUT
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
        NTLM_TYPE1,
        NTLM_TYPE3
    }

    // Transport reference (set in connected())
    private Endpoint endpoint;

    private final IMAPListener server;

    // Handler instances
    private ClientConnected clientConnected;
    private NotAuthenticatedHandler notAuthenticatedHandler;
    private AuthenticatedHandler authenticatedHandler;
    private SelectedHandler selectedHandler;
    private AppendDataHandler appendDataHandler;

    private Realm realm;

    // Session state
    private IMAPState state = IMAPState.NOT_AUTHENTICATED;
    private String authenticatedUser = null;

    // Mailbox state
    private MailboxStore store = null;
    private Mailbox selectedMailbox = null;
    private boolean selectedReadOnly = false;

    // Command parsing
    private CharBuffer charBuffer;
    private String currentTag = null;

    // SASL authentication
    private AuthState authState = AuthState.NONE;
    private String pendingAuthTag = null;
    private String pendingAuthUsername = null;
    private String authChallenge = null;
    private String authNonce = null;
    private String scramStoredKey = null;
    private String scramServerKey = null;
    private int scramIterations = 4096;

    // IDLE state
    private boolean idling = false;
    private String idleTag = null;

    // STARTTLS state
    private boolean starttlsUsed = false;

    // APPEND literal state
    private String appendTag = null;
    private Mailbox appendMailbox = null;
    private long appendLiteralRemaining = 0;
    private String appendMailboxName = null;
    private long appendMessageSize = 0;

    // Telemetry
    private Span sessionSpan;
    private Span authenticatedSpan;

    /**
     * Creates a new IMAP endpoint handler.
     *
     * @param server the IMAP server configuration
     */
    public IMAPProtocolHandler(IMAPListener server) {
        this.server = server;
        this.charBuffer = CharBuffer.allocate(server.getMaxLineLength() + 2);
    }

    // ── ProtocolHandler implementation ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;

        initConnectionTrace();
        startSessionSpan();

        if (endpoint.isSecure()) {
            return;
        }

        try {
            sendGreeting();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error sending greeting", e);
            endpoint.close();
        }
    }

    @Override
    public void receive(ByteBuffer buffer) {
        if (appendLiteralRemaining > 0) {
            try {
                receiveLiteralData(buffer);
            } catch (IOException e) {
                String msg = L10N.getString("log.error_processing_data");
                LOGGER.log(Level.WARNING, msg, e);
            }
            return;
        }

        LineParser.parse(buffer, this);

        if (appendLiteralRemaining > 0 && buffer.hasRemaining()) {
            try {
                receiveLiteralData(buffer);
            } catch (IOException e) {
                String msg = L10N.getString("log.error_processing_data");
                LOGGER.log(Level.WARNING, msg, e);
            }
        }
    }

    @Override
    public void disconnected() {
        try {
            if (clientConnected != null) {
                clientConnected.disconnected();
            }

            if (state != IMAPState.LOGOUT) {
                endSessionSpanError("Client disconnected");
            } else {
                endSessionSpan("Connection closed");
            }
        } finally {
            try {
                if (selectedMailbox != null) {
                    try {
                        selectedMailbox.close(false);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "Error closing mailbox on disconnect", e);
                    }
                    selectedMailbox = null;
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
        if (state == IMAPState.NOT_AUTHENTICATED && !starttlsUsed) {
            try {
                sendGreeting();
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING,
                            "Failed to send greeting after TLS handshake", e);
                }
                closeEndpoint();
            }
        }
    }

    @Override
    public void error(Exception cause) {
        LOGGER.log(Level.WARNING, "IMAP transport error", cause);
        closeEndpoint();
    }

    // ── LineParser.Callback implementation ──

    @Override
    public void lineReceived(ByteBuffer line) {
        try {
            int lineLength = line.remaining();
            if (lineLength > server.getMaxLineLength() + 2) {
                sendTaggedBad(currentTag != null ? currentTag : "*",
                        L10N.getString("imap.err.line_too_long"));
                return;
            }

            charBuffer.clear();
            US_ASCII_DECODER.reset();
            CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
            if (result.isError()) {
                sendTaggedBad(currentTag != null ? currentTag : "*",
                        L10N.getString("imap.err.invalid_command_encoding"));
                return;
            }
            charBuffer.flip();

            int len = charBuffer.limit();
            if (len >= 2 && charBuffer.get(len - 2) == '\r'
                    && charBuffer.get(len - 1) == '\n') {
                charBuffer.limit(len - 2);
            } else if (len >= 1 && charBuffer.get(len - 1) == '\n') {
                charBuffer.limit(len - 1);
            }
            String lineStr = charBuffer.toString();

            if (LOGGER.isLoggable(Level.FINEST)) {
                String msg = L10N.getString("log.imap_command");
                msg = MessageFormat.format(msg, lineStr);
                LOGGER.finest(msg);
            }

            processLine(lineStr);

        } catch (IOException e) {
            String msg = L10N.getString("log.error_processing_data");
            LOGGER.log(Level.WARNING, msg, e);
        }
    }

    @Override
    public boolean continueLineProcessing() {
        return appendLiteralRemaining <= 0;
    }

    // ── Transport helpers ──

    private void closeEndpoint() {
        try {
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                if (state == IMAPState.LOGOUT) {
                    endSessionSpan("Connection closed");
                } else {
                    endSessionSpanError("Connection closed unexpectedly");
                }
            }

            if (selectedMailbox != null) {
                try {
                    selectedMailbox.close(false);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING,
                            "Error closing mailbox", e);
                }
                selectedMailbox = null;
            }
            if (store != null) {
                try {
                    store.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Error closing store", e);
                }
                store = null;
            }
        } finally {
            if (endpoint != null) {
                endpoint.close();
            }
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

    // ── Greeting ──

    private void sendGreeting() throws IOException {
        IMAPService service = server.getService();
        if (service != null) {
            clientConnected = service.createHandler(server);
        }
        if (clientConnected != null) {
            clientConnected.connected(new ConnectedStateImpl(), endpoint);
        } else {
            String caps = server.getCapabilities(false, endpoint.isSecure());
            sendUntagged("OK [CAPABILITY " + caps + "] "
                    + L10N.getString("imap.greeting"));
        }
    }

    // ── Telemetry ──

    private void initConnectionTrace() {
        if (!endpoint.isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig = endpoint.getTelemetryConfig();
        String spanName = L10N.getString("telemetry.imap_connection");
        Trace trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
        endpoint.setTrace(trace);

        if (trace != null) {
            Span rootSpan = trace.getRootSpan();
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip",
                    endpoint.getRemoteAddress().toString());
            rootSpan.addAttribute("rpc.system", "imap");
        }
    }

    private void startSessionSpan() {
        Trace trace = endpoint.getTrace();
        if (trace == null) {
            return;
        }

        String spanName = L10N.getString("telemetry.imap_session");
        sessionSpan = trace.startSpan(spanName, SpanKind.SERVER);
    }

    private void startAuthenticatedSpan(String username, String mechanism) {
        Trace trace = endpoint.getTrace();
        if (trace == null) {
            return;
        }

        String spanName = L10N.getString("telemetry.imap_authenticated");
        authenticatedSpan = trace.startSpan(spanName, SpanKind.INTERNAL);

        if (authenticatedSpan != null) {
            authenticatedSpan.addAttribute("enduser.id", username);
            authenticatedSpan.addAttribute("imap.auth.mechanism", mechanism);
        }

        addSessionAttribute("enduser.id", username);
        addSessionAttribute("imap.auth.mechanism", mechanism);
        addSessionEvent("AUTHENTICATED");
    }

    private void endSessionSpan(String message) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.setStatusOk();
            authenticatedSpan.end();
        }

        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }

        if (message != null) {
            sessionSpan.addAttribute("imap.result", message);
        }
        sessionSpan.setStatusOk();
        sessionSpan.end();
    }

    private void endSessionSpanError(String message) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.setStatusError(message);
            authenticatedSpan.end();
        }

        if (sessionSpan == null || sessionSpan.isEnded()) {
            return;
        }

        sessionSpan.setStatusError(message);
        sessionSpan.end();
    }

    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private void addSessionAttribute(String key, long value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private void addSessionAttribute(String key, boolean value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    private void addSessionEvent(String name) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(name);
        }
    }

    private void recordSessionException(Throwable exception) {
        if (sessionSpan != null && !sessionSpan.isEnded()
                && exception != null) {
            sessionSpan.recordExceptionWithCategory(exception);
        }
    }

    private void recordSessionException(Throwable exception,
            ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded()
                && exception != null) {
            sessionSpan.recordException(exception, category);
        }
    }

    private void recordImapError(ErrorCategory category, String message) {
        if (sessionSpan != null && !sessionSpan.isEnded()
                && category != null) {
            sessionSpan.recordError(category, message);
        }
    }

    // ── Literal data ──

    private void receiveLiteralData(ByteBuffer buffer) throws IOException {
        int available = buffer.remaining();
        int toConsume = (int) Math.min(available, appendLiteralRemaining);

        if (toConsume > 0) {
            ByteBuffer slice = buffer.slice();
            slice.limit(toConsume);

            appendMailbox.appendMessageContent(slice);

            buffer.position(buffer.position() + toConsume);
            appendLiteralRemaining -= toConsume;
        }

        if (appendLiteralRemaining == 0) {
            try {
                long uid = appendMailbox.endAppendMessage();
                appendMailbox.close(false);

                QuotaManager quotaManager = server.getQuotaManager();
                if (quotaManager != null) {
                    quotaManager.recordMessageAdded(authenticatedUser,
                            appendMessageSize);
                }

                addSessionEvent("APPEND_COMPLETE");
                addSessionAttribute("imap.append.uid", uid);

                sendTaggedOk(appendTag, "[APPENDUID "
                        + appendMailbox.getUidValidity() + " " + uid + "] "
                        + L10N.getString("imap.append_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to complete APPEND", e);
                addSessionEvent("APPEND_FAILED");
                recordSessionException(e);
                sendTaggedNo(appendTag, L10N.getString("imap.err.append_failed"));
            } finally {
                appendTag = null;
                appendMailbox = null;
                appendMailboxName = null;
                appendMessageSize = 0;
            }

            if (buffer.hasRemaining()) {
                receive(buffer);
            }
        }
    }

    // ── Line processing ──

    private void processLine(String line) throws IOException {
        if (line.isEmpty()) {
            return;
        }

        if (idling && line.equalsIgnoreCase("DONE")) {
            handleIdleDone();
            return;
        }

        if (authState != AuthState.NONE) {
            processSASLResponse(line);
            return;
        }

        int spaceIndex = line.indexOf(' ');
        if (spaceIndex <= 0) {
            sendTaggedBad("*", L10N.getString("imap.err.missing_tag"));
            return;
        }

        String tag = line.substring(0, spaceIndex);
        String rest = line.substring(spaceIndex + 1);

        if (!isValidTag(tag)) {
            sendTaggedBad("*", L10N.getString("imap.err.invalid_tag"));
            return;
        }

        currentTag = tag;

        spaceIndex = rest.indexOf(' ');
        String command;
        String arguments;
        if (spaceIndex > 0) {
            command = rest.substring(0, spaceIndex)
                    .toUpperCase(Locale.ENGLISH);
            arguments = rest.substring(spaceIndex + 1);
        } else {
            command = rest.toUpperCase(Locale.ENGLISH);
            arguments = "";
        }

        try {
            dispatchCommand(tag, command, arguments);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing IMAP command: "
                    + command, e);
            sendTaggedNo(tag, L10N.getString("imap.err.internal_error"));
        }
    }

    private boolean isValidTag(String tag) {
        if (tag.isEmpty() || tag.equals("*") || tag.equals("+")) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c <= 0x1f || c >= 0x7f || c == '(' || c == ')' || c == '{'
                    || c == ' ' || c == '%' || c == '*' || c == '"'
                    || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private void dispatchCommand(String tag, String command, String args)
            throws IOException {
        switch (command) {
            case "CAPABILITY":
                handleCapability(tag);
                return;
            case "NOOP":
                handleNoop(tag);
                return;
            case "LOGOUT":
                handleLogout(tag);
                return;
            default:
                break;
        }

        switch (state) {
            case NOT_AUTHENTICATED:
                dispatchNotAuthenticatedCommand(tag, command, args);
                break;
            case AUTHENTICATED:
                dispatchAuthenticatedCommand(tag, command, args);
                break;
            case SELECTED:
                dispatchSelectedCommand(tag, command, args);
                break;
            case LOGOUT:
                sendTaggedBad(tag, L10N.getString("imap.err.logging_out"));
                break;
        }
    }

    private void dispatchNotAuthenticatedCommand(String tag, String command,
            String args) throws IOException {
        switch (command) {
            case "STARTTLS":
                handleStartTLS(tag);
                break;
            case "AUTHENTICATE":
                handleAuthenticate(tag, args);
                break;
            case "LOGIN":
                handleLogin(tag, args);
                break;
            default:
                sendTaggedBad(tag, MessageFormat.format(
                        L10N.getString("imap.err.unknown_command"), command));
        }
    }

    private void dispatchAuthenticatedCommand(String tag, String command,
            String args) throws IOException {
        switch (command) {
            case "SELECT":
                handleSelect(tag, args, false);
                break;
            case "EXAMINE":
                handleSelect(tag, args, true);
                break;
            case "CREATE":
                handleCreate(tag, args);
                break;
            case "DELETE":
                handleDelete(tag, args);
                break;
            case "RENAME":
                handleRename(tag, args);
                break;
            case "SUBSCRIBE":
                handleSubscribe(tag, args);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(tag, args);
                break;
            case "LIST":
                handleList(tag, args);
                break;
            case "LSUB":
                handleLsub(tag, args);
                break;
            case "NAMESPACE":
                handleNamespace(tag);
                break;
            case "STATUS":
                handleStatus(tag, args);
                break;
            case "APPEND":
                handleAppend(tag, args);
                break;
            case "IDLE":
                handleIdle(tag);
                break;
            case "GETQUOTA":
                handleGetQuota(tag, args);
                break;
            case "GETQUOTAROOT":
                handleGetQuotaRoot(tag, args);
                break;
            case "SETQUOTA":
                handleSetQuota(tag, args);
                break;
            default:
                sendTaggedBad(tag, MessageFormat.format(
                        L10N.getString("imap.err.unknown_command"), command));
        }
    }

    private void dispatchSelectedCommand(String tag, String command, String args)
            throws IOException {
        switch (command) {
            case "SELECT":
                handleSelect(tag, args, false);
                break;
            case "EXAMINE":
                handleSelect(tag, args, true);
                break;
            case "CREATE":
            case "DELETE":
            case "RENAME":
            case "SUBSCRIBE":
            case "UNSUBSCRIBE":
            case "LIST":
            case "LSUB":
            case "NAMESPACE":
            case "STATUS":
            case "APPEND":
            case "IDLE":
            case "GETQUOTA":
            case "GETQUOTAROOT":
            case "SETQUOTA":
                dispatchAuthenticatedCommand(tag, command, args);
                break;
            case "CLOSE":
                handleClose(tag);
                break;
            case "UNSELECT":
                handleUnselect(tag);
                break;
            case "EXPUNGE":
                handleExpunge(tag);
                break;
            case "SEARCH":
                handleSearch(tag, args, false);
                break;
            case "UID":
                handleUid(tag, args);
                break;
            case "FETCH":
                handleFetch(tag, args, false);
                break;
            case "STORE":
                handleStore(tag, args, false);
                break;
            case "COPY":
                handleCopy(tag, args, false);
                break;
            case "MOVE":
                handleMove(tag, args, false);
                break;
            default:
                sendTaggedBad(tag, MessageFormat.format(
                        L10N.getString("imap.err.unknown_command"), command));
        }
    }

    // ── Any-state commands ──

    private void handleCapability(String tag) throws IOException {
        String caps = server.getCapabilities(
                state != IMAPState.NOT_AUTHENTICATED, endpoint.isSecure());
        sendUntagged("CAPABILITY " + caps);
        sendTaggedOk(tag, L10N.getString("imap.capability_complete"));
    }

    private void handleNoop(String tag) throws IOException {
        if (selectedMailbox != null) {
            sendMailboxUpdates();
        }
        sendTaggedOk(tag, L10N.getString("imap.noop_complete"));
    }

    private void handleLogout(String tag) throws IOException {
        state = IMAPState.LOGOUT;
        addSessionEvent("LOGOUT");
        endSessionSpan("LOGOUT");
        sendUntagged("BYE " + L10N.getString("imap.goodbye"));
        sendTaggedOk(tag, L10N.getString("imap.logout_complete"));
        closeEndpoint();
    }

    // ── NOT_AUTHENTICATED commands ──

    private void handleStartTLS(String tag) throws IOException {
        if (endpoint.isSecure()) {
            sendTaggedBad(tag, L10N.getString("imap.err.already_tls"));
            return;
        }

        if (!server.isSTARTTLSAvailable()) {
            sendTaggedNo(tag, L10N.getString("imap.err.starttls_unavailable"));
            return;
        }

        addSessionEvent("STARTTLS");
        addSessionAttribute("net.transport.security", "TLS");

        sendTaggedOk(tag, L10N.getString("imap.starttls_begin"));
        try {
            endpoint.startTLS();
            starttlsUsed = true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize TLS", e);
            closeEndpoint();
        }
    }

    private void handleLogin(String tag, String args) throws IOException {
        if (!endpoint.isSecure() && !server.isAllowPlaintextLogin()) {
            sendTaggedNo(tag, "[PRIVACYREQUIRED] "
                    + L10N.getString("imap.err.login_disabled"));
            return;
        }

        String[] parts = parseQuotedStrings(args, 2);
        if (parts == null || parts.length < 2) {
            sendTaggedBad(tag, L10N.getString("imap.err.login_syntax"));
            return;
        }

        String username = parts[0];
        String password = parts[1];

        if (!authenticateUser(username, password)) {
            sendTaggedNo(tag, "[AUTHENTICATIONFAILED] "
                    + L10N.getString("imap.err.auth_failed"));
            return;
        }

        authenticatedUser = username;

        if (notAuthenticatedHandler != null) {
            Principal principal = createPrincipal(username);
            notAuthenticatedHandler.authenticate(new AuthenticateStateImpl(tag),
                    principal, server.getMailboxFactory());
            return;
        }

        openMailStore(username);
        sendTaggedOk(tag, "[CAPABILITY "
                + server.getCapabilities(true, endpoint.isSecure()) + "] "
                + L10N.getString("imap.login_complete"));
    }

    private void handleAuthenticate(String tag, String args) throws IOException {
        String mechanism;
        String initialResponse = null;
        int spaceIndex = args.indexOf(' ');
        if (spaceIndex > 0) {
            mechanism = args.substring(0, spaceIndex)
                    .toUpperCase(Locale.ENGLISH);
            initialResponse = args.substring(spaceIndex + 1);
            if (initialResponse.equals("=")) {
                initialResponse = "";
            }
        } else {
            mechanism = args.toUpperCase(Locale.ENGLISH);
        }

        pendingAuthTag = tag;

        SASLMechanism mech = SASLMechanism.fromName(mechanism);
        if (mech == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.unsupported_mechanism"));
            return;
        }

        if (mech.requiresTLS() && !endpoint.isSecure()) {
            sendTaggedNo(tag, "[PRIVACYREQUIRED] "
                    + L10N.getString("imap.err.tls_required"));
            return;
        }

        switch (mech) {
            case PLAIN:
                handleAuthPLAIN(initialResponse);
                break;
            case LOGIN:
                handleAuthLOGIN(initialResponse);
                break;
            case CRAM_MD5:
                handleAuthCRAMMD5(initialResponse);
                break;
            case DIGEST_MD5:
                handleAuthDIGESTMD5(initialResponse);
                break;
            case SCRAM_SHA_256:
                handleAuthSCRAM(initialResponse);
                break;
            case OAUTHBEARER:
                handleAuthOAUTHBEARER(initialResponse);
                break;
            case GSSAPI:
                handleAuthGSSAPI(initialResponse);
                break;
            case EXTERNAL:
                handleAuthEXTERNAL(initialResponse);
                break;
            case NTLM:
                handleAuthNTLM(initialResponse);
                break;
            default:
                sendTaggedNo(tag, L10N.getString("imap.err.unsupported_mechanism"));
        }
    }

    // ── SASL handlers ──

    private void handleAuthPLAIN(String initialResponse) throws IOException {
        if (initialResponse == null) {
            authState = AuthState.PLAIN_RESPONSE;
            sendContinuation("");
        } else {
            processPlainCredentials(initialResponse);
        }
    }

    private void handleAuthLOGIN(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            try {
                pendingAuthUsername = SASLUtils.decodeBase64ToString(
                        initialResponse);
                authState = AuthState.LOGIN_PASSWORD;
                sendContinuation(SASLUtils.encodeBase64("Password:"));
            } catch (IllegalArgumentException e) {
                authFailed();
            }
        } else {
            authState = AuthState.LOGIN_USERNAME;
            sendContinuation(SASLUtils.encodeBase64("Username:"));
        }
    }

    private void handleAuthCRAMMD5(String initialResponse) throws IOException {
        Realm realm = getRealm();
        if (realm == null) {
            sendTaggedNo(pendingAuthTag,
                    L10N.getString("imap.err.auth_not_configured"));
            resetAuthState();
            return;
        }

        try {
            InetSocketAddress addr = (InetSocketAddress) endpoint
                    .getLocalAddress();
            authChallenge = SASLUtils.generateCramMD5Challenge(
                    addr.getHostName());
            authState = AuthState.CRAM_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(authChallenge));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate CRAM-MD5 challenge", e);
            authFailed();
        }
    }

    private void handleAuthDIGESTMD5(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            sendTaggedNo(pendingAuthTag,
                    L10N.getString("imap.err.digestmd5_no_initial"));
            return;
        }

        Realm realm = getRealm();
        if (realm == null) {
            sendTaggedNo(pendingAuthTag,
                    L10N.getString("imap.err.auth_not_configured"));
            return;
        }

        try {
            InetSocketAddress addr = (InetSocketAddress) endpoint
                    .getLocalAddress();
            authNonce = SASLUtils.generateNonce(16);
            String challenge = SASLUtils.generateDigestMD5Challenge(
                    addr.getHostName(), authNonce);
            authState = AuthState.DIGEST_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(challenge));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "Failed to generate DIGEST-MD5 challenge", e);
            authFailed();
        }
    }

    private void handleAuthSCRAM(String initialResponse) throws IOException {
        Realm realm = getRealm();
        if (realm == null) {
            sendTaggedNo(pendingAuthTag,
                    L10N.getString("imap.err.auth_not_configured"));
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
        } else {
            processOAuthBearerCredentials(initialResponse);
        }
    }

    private void handleAuthGSSAPI(String initialResponse) throws IOException {
        sendTaggedNo(pendingAuthTag,
                L10N.getString("imap.err.gssapi_unavailable"));
    }

    private void handleAuthEXTERNAL(String initialResponse) throws IOException {
        if (!endpoint.isSecure()) {
            sendTaggedNo(pendingAuthTag,
                    L10N.getString("imap.err.external_requires_tls"));
            return;
        }
        sendTaggedNo(pendingAuthTag,
                L10N.getString("imap.err.external_unavailable"));
    }

    private void handleAuthNTLM(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            processNtlmType1(initialResponse);
        } else {
            authState = AuthState.NTLM_TYPE1;
            sendContinuation("");
        }
    }

    // ── SASL response processing ──

    private void processSASLResponse(String line) throws IOException {
        if (line.equals("*")) {
            sendTaggedBad(pendingAuthTag, L10N.getString("imap.auth_aborted"));
            resetAuthState();
            return;
        }

        switch (authState) {
            case PLAIN_RESPONSE:
                processPlainCredentials(line);
                break;
            case LOGIN_USERNAME:
                processLoginUsername(line);
                break;
            case LOGIN_PASSWORD:
                processLoginPassword(line);
                break;
            case CRAM_MD5_RESPONSE:
                processCramMD5Response(line);
                break;
            case DIGEST_MD5_RESPONSE:
                processDigestMD5Response(line);
                break;
            case SCRAM_INITIAL:
                processScramClientFirst(line);
                break;
            case SCRAM_FINAL:
                processScramClientFinal(line);
                break;
            case OAUTH_RESPONSE:
                processOAuthBearerCredentials(line);
                break;
            case NTLM_TYPE1:
                processNtlmType1(line);
                break;
            case NTLM_TYPE3:
                processNtlmType3(line);
                break;
            default:
                authFailed();
        }
    }

    private void processPlainCredentials(String credentials) throws IOException {
        try {
            byte[] decoded = SASLUtils.decodeBase64(credentials);
            String[] parts = SASLUtils.parsePlainCredentials(decoded);
            String username = parts[1];
            String password = parts[2];

            if (authenticateUser(username, password)) {
                openMailStore(username, "PLAIN");
                authSucceeded();
            } else {
                authFailed();
            }
        } catch (IllegalArgumentException e) {
            authFailed();
        }
    }

    private void processLoginUsername(String line) throws IOException {
        try {
            pendingAuthUsername = SASLUtils.decodeBase64ToString(line);
            authState = AuthState.LOGIN_PASSWORD;
            sendContinuation(SASLUtils.encodeBase64("Password:"));
        } catch (IllegalArgumentException e) {
            authFailed();
        }
    }

    private void processLoginPassword(String line) throws IOException {
        try {
            String password = SASLUtils.decodeBase64ToString(line);

            if (authenticateUser(pendingAuthUsername, password)) {
                openMailStore(pendingAuthUsername, "LOGIN");
                authSucceeded();
            } else {
                authFailed();
            }
        } catch (IllegalArgumentException e) {
            authFailed();
        }
    }

    private void processCramMD5Response(String line) throws IOException {
        try {
            String response = SASLUtils.decodeBase64ToString(line);
            int spaceIndex = response.lastIndexOf(' ');
            if (spaceIndex <= 0) {
                authFailed();
                return;
            }

            String username = response.substring(0, spaceIndex);
            String digest = response.substring(spaceIndex + 1);

            Realm realm = getRealm();
            try {
                String expected = realm.getCramMD5Response(username, authChallenge);
                if (expected != null && expected.equalsIgnoreCase(digest)) {
                    openMailStore(username, "CRAM-MD5");
                    authSucceeded();
                } else {
                    authFailed();
                }
            } catch (UnsupportedOperationException e) {
                authFailed();
            }
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processDigestMD5Response(String line) throws IOException {
        try {
            String response = SASLUtils.decodeBase64ToString(line);
            Map<String, String> params = SASLUtils.parseDigestParams(response);

            String username = params.get("username");
            if (username == null) {
                authFailed();
                return;
            }

            Realm realm = getRealm();
            InetSocketAddress addr = (InetSocketAddress) endpoint
                    .getLocalAddress();
            String ha1 = realm.getDigestHA1(username, addr.getHostName());

            if (ha1 != null) {
                openMailStore(username, "DIGEST-MD5");
                sendContinuation(SASLUtils.encodeBase64("rspauth="));
                authSucceeded();
            } else {
                authFailed();
            }
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processScramClientFirst(String line) throws IOException {
        try {
            String clientFirst = SASLUtils.decodeBase64ToString(line);

            if (!clientFirst.startsWith("n,,")) {
                authFailed();
                return;
            }

            String attrString = clientFirst.substring(3);
            String username = null;
            String clientNonce = null;

            int partStart = 0;
            int attrLen = attrString.length();
            while (partStart <= attrLen) {
                int partEnd = attrString.indexOf(',', partStart);
                if (partEnd < 0) {
                    partEnd = attrLen;
                }
                String part = attrString.substring(partStart, partEnd);
                if (part.startsWith("n=")) {
                    username = part.substring(2);
                } else if (part.startsWith("r=")) {
                    clientNonce = part.substring(2);
                }
                partStart = partEnd + 1;
            }

            if (username == null || clientNonce == null) {
                authFailed();
                return;
            }

            pendingAuthUsername = username;

            String serverNonce = clientNonce + SASLUtils.generateNonce(16);
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String saltB64 = Base64.getEncoder().encodeToString(salt);

            authNonce = serverNonce;
            String serverFirst = SASLUtils.generateScramServerFirst(serverNonce,
                    saltB64, scramIterations);
            authState = AuthState.SCRAM_FINAL;
            sendContinuation(SASLUtils.encodeBase64(serverFirst));

        } catch (Exception e) {
            authFailed();
        }
    }

    private void processScramClientFinal(String line) throws IOException {
        try {
            String clientFinal = SASLUtils.decodeBase64ToString(line);

            Realm realm = getRealm();
            Realm.ScramCredentials creds = realm.getScramCredentials(
                    pendingAuthUsername);

            if (creds != null) {
                openMailStore(pendingAuthUsername, "SCRAM-SHA-256");
                String serverFinal = "v=" + SASLUtils.encodeBase64(new byte[32]);
                sendContinuation(SASLUtils.encodeBase64(serverFinal));
                authSucceeded();
            } else {
                authFailed();
            }
        } catch (UnsupportedOperationException e) {
            authFailed();
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processOAuthBearerCredentials(String line) throws IOException {
        try {
            String credentials = SASLUtils.decodeBase64ToString(line);
            Map<String, String> params =
                    SASLUtils.parseOAuthBearerCredentials(credentials);

            String user = params.get("user");
            String token = params.get("token");

            if (user == null || token == null) {
                authFailed();
                return;
            }

            Realm realm = getRealm();
            Realm.TokenValidationResult result = realm.validateBearerToken(token);
            if (result != null && result.valid) {
                openMailStore(user, "OAUTHBEARER");
                authSucceeded();
            } else {
                authFailed();
            }
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processNtlmType1(String line) throws IOException {
        try {
            byte[] type1 = SASLUtils.decodeBase64(line);
            if (type1.length < 8 || type1[0] != 'N' || type1[1] != 'T') {
                authFailed();
                return;
            }

            byte[] type2 = new byte[56];
            System.arraycopy("NTLMSSP\0".getBytes(US_ASCII), 0, type2, 0, 8);
            type2[8] = 0x02;

            authState = AuthState.NTLM_TYPE3;
            sendContinuation(SASLUtils.encodeBase64(type2));
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processNtlmType3(String line) throws IOException {
        try {
            byte[] type3 = SASLUtils.decodeBase64(line);
            if (type3.length < 8 || type3[0] != 'N' || type3[8] != 0x03) {
                authFailed();
                return;
            }

            String username = pendingAuthUsername != null
                    ? pendingAuthUsername : "user";

            Realm realm = getRealm();
            if (realm.userExists(username)) {
                openMailStore(username, "NTLM");
                authSucceeded();
            } else {
                authFailed();
            }
        } catch (Exception e) {
            authFailed();
        }
    }

    private void authSucceeded() throws IOException {
        sendTaggedOk(pendingAuthTag, "[CAPABILITY "
                + server.getCapabilities(true, endpoint.isSecure()) + "] "
                + L10N.getString("imap.auth_complete"));
        resetAuthState();
    }

    private void authFailed() throws IOException {
        addSessionEvent("AUTH_FAILED");
        sendTaggedNo(pendingAuthTag, "[AUTHENTICATIONFAILED] "
                + L10N.getString("imap.err.auth_failed"));
        resetAuthState();
    }

    private void resetAuthState() {
        authState = AuthState.NONE;
        pendingAuthTag = null;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
    }

    private boolean authenticateUser(String username, String password) {
        Realm realm = getRealm();
        if (realm == null) {
            return false;
        }
        return realm.passwordMatch(username, password);
    }

    private void openMailStore(String username) throws IOException {
        openMailStore(username, "PASSWORD", null);
    }

    private void openMailStore(String username, String mechanism)
            throws IOException {
        openMailStore(username, mechanism, null);
    }

    private void openMailStore(String username, String mechanism, String tag)
            throws IOException {
        authenticatedUser = username;

        if (notAuthenticatedHandler != null) {
            Principal principal = createPrincipal(username);
            String responseTag = (tag != null) ? tag : pendingAuthTag;
            notAuthenticatedHandler.authenticate(
                    new AuthenticateStateImpl(responseTag, mechanism),
                    principal, server.getMailboxFactory());
            return;
        }

        state = IMAPState.AUTHENTICATED;
        startAuthenticatedSpan(username, mechanism);

        MailboxFactory factory = server.getMailboxFactory();
        if (factory != null) {
            store = factory.createStore();
            store.open(username);
        }
    }

    private Principal createPrincipal(final String username) {
        return new Principal() {
            @Override
            public String getName() {
                return username;
            }

            @Override
            public String toString() {
                return "IMAPPrincipal[" + username + "]";
            }
        };
    }

    // ── AUTHENTICATED state commands ──

    private void handleSelect(String tag, String args, boolean readOnly)
            throws IOException {
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.select_syntax"));
            return;
        }

        if (authenticatedHandler != null) {
            if (readOnly) {
                authenticatedHandler.examine(new SelectStateImpl(tag), store,
                        mailboxName);
            } else {
                authenticatedHandler.select(new SelectStateImpl(tag), store,
                        mailboxName);
            }
            return;
        }

        try {
            if (selectedMailbox != null) {
                selectedMailbox.close(!selectedReadOnly);
                selectedMailbox = null;
            }

            selectedMailbox = store.openMailbox(mailboxName, readOnly);
            selectedReadOnly = readOnly;
            state = IMAPState.SELECTED;

            addSessionAttribute("imap.mailbox", mailboxName);
            addSessionAttribute("imap.mailbox.readonly", readOnly);
            addSessionEvent(readOnly ? "EXAMINE" : "SELECT");

            int count = selectedMailbox.getMessageCount();
            sendUntagged(count + " EXISTS");
            sendUntagged("0 RECENT");
            sendUntagged("FLAGS ("
                    + formatFlags(selectedMailbox.getPermanentFlags()) + ")");
            sendUntagged("OK [PERMANENTFLAGS ("
                    + formatFlags(selectedMailbox.getPermanentFlags())
                    + " \\*)]");
            sendUntagged("OK [UIDVALIDITY "
                    + selectedMailbox.getUidValidity() + "]");
            sendUntagged("OK [UIDNEXT " + selectedMailbox.getUidNext() + "]");

            String accessMode = readOnly ? "[READ-ONLY]" : "[READ-WRITE]";
            sendTaggedOk(tag, accessMode + " "
                    + L10N.getString("imap.select_complete"));

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open mailbox: "
                    + mailboxName, e);
            recordSessionException(e);
            sendTaggedNo(tag, L10N.getString("imap.err.mailbox_not_found"));
        }
    }

    private void handleCreate(String tag, String args) throws IOException {
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.create_syntax"));
            return;
        }

        try {
            store.createMailbox(mailboxName);
            sendTaggedOk(tag, L10N.getString("imap.create_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.create_failed"));
        }
    }

    private void handleDelete(String tag, String args) throws IOException {
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.delete_syntax"));
            return;
        }

        try {
            store.deleteMailbox(mailboxName);
            sendTaggedOk(tag, L10N.getString("imap.delete_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.delete_failed"));
        }
    }

    private void handleRename(String tag, String args) throws IOException {
        String[] parts = parseQuotedStrings(args, 2);
        if (parts == null || parts.length < 2) {
            sendTaggedBad(tag, L10N.getString("imap.err.rename_syntax"));
            return;
        }

        try {
            store.renameMailbox(parts[0], parts[1]);
            sendTaggedOk(tag, L10N.getString("imap.rename_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.rename_failed"));
        }
    }

    private void handleSubscribe(String tag, String args) throws IOException {
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.subscribe_syntax"));
            return;
        }

        try {
            store.subscribe(mailboxName);
            sendTaggedOk(tag, L10N.getString("imap.subscribe_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.subscribe_failed"));
        }
    }

    private void handleUnsubscribe(String tag, String args) throws IOException {
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.unsubscribe_syntax"));
            return;
        }

        try {
            store.unsubscribe(mailboxName);
            sendTaggedOk(tag, L10N.getString("imap.unsubscribe_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.unsubscribe_failed"));
        }
    }

    private void handleList(String tag, String args) throws IOException {
        String[] parts = parseQuotedStrings(args, 2);
        if (parts == null || parts.length < 2) {
            sendTaggedBad(tag, L10N.getString("imap.err.list_syntax"));
            return;
        }

        String reference = parts[0];
        String pattern = parts[1];

        try {
            List<String> mailboxes = store.listMailboxes(reference, pattern);
            for (String mailbox : mailboxes) {
                Set<MailboxAttribute> attrs =
                        store.getMailboxAttributes(mailbox);
                StringBuilder attrStr = new StringBuilder();
                for (MailboxAttribute attr : attrs) {
                    if (attrStr.length() > 0) {
                        attrStr.append(' ');
                    }
                    attrStr.append(attr.getImapAtom());
                }
                sendUntagged("LIST (" + attrStr + ") \""
                        + store.getHierarchyDelimiter() + "\" "
                        + quoteMailboxName(mailbox));
            }
            sendTaggedOk(tag, L10N.getString("imap.list_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.list_failed"));
        }
    }

    private void handleLsub(String tag, String args) throws IOException {
        String[] parts = parseQuotedStrings(args, 2);
        if (parts == null || parts.length < 2) {
            sendTaggedBad(tag, L10N.getString("imap.err.lsub_syntax"));
            return;
        }

        try {
            List<String> mailboxes = store.listSubscribed(parts[0], parts[1]);
            for (String mailbox : mailboxes) {
                sendUntagged("LSUB () \"" + store.getHierarchyDelimiter()
                        + "\" " + quoteMailboxName(mailbox));
            }
            sendTaggedOk(tag, L10N.getString("imap.lsub_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.lsub_failed"));
        }
    }

    private void handleNamespace(String tag) throws IOException {
        if (!server.isEnableNAMESPACE()) {
            sendTaggedBad(tag, L10N.getString("imap.err.unknown_command"));
            return;
        }

        String personal = "((\"" + store.getPersonalNamespace() + "\" \""
                + store.getHierarchyDelimiter() + "\"))";
        sendUntagged("NAMESPACE " + personal + " NIL NIL");
        sendTaggedOk(tag, L10N.getString("imap.namespace_complete"));
    }

    private void handleStatus(String tag, String args) throws IOException {
        int parenStart = args.indexOf('(');
        if (parenStart < 0) {
            sendTaggedBad(tag, L10N.getString("imap.err.status_syntax"));
            return;
        }

        String mailboxName = parseMailboxName(
                args.substring(0, parenStart).trim());
        String attrsStr = args.substring(parenStart + 1,
                args.lastIndexOf(')'));
        List<String> attrList = new ArrayList<String>();
        StringTokenizer attrTokenizer = new StringTokenizer(attrsStr);
        while (attrTokenizer.hasMoreTokens()) {
            attrList.add(attrTokenizer.nextToken());
        }
        String[] attrs = attrList.toArray(new String[0]);

        try {
            Mailbox mailbox = store.openMailbox(mailboxName, true);
            StringBuilder response = new StringBuilder();
            response.append("STATUS ").append(quoteMailboxName(mailboxName))
                    .append(" (");

            boolean first = true;
            for (String attr : attrs) {
                if (!first) {
                    response.append(" ");
                }
                first = false;

                switch (attr.toUpperCase()) {
                    case "MESSAGES":
                        response.append("MESSAGES ")
                                .append(mailbox.getMessageCount());
                        break;
                    case "UIDNEXT":
                        response.append("UIDNEXT ")
                                .append(mailbox.getUidNext());
                        break;
                    case "UIDVALIDITY":
                        response.append("UIDVALIDITY ")
                                .append(mailbox.getUidValidity());
                        break;
                    case "UNSEEN":
                        response.append("UNSEEN 0");
                        break;
                    case "RECENT":
                        response.append("RECENT 0");
                        break;
                    case "SIZE":
                        response.append("SIZE ")
                                .append(mailbox.getMailboxSize());
                        break;
                    default:
                        break;
                }
            }
            response.append(")");

            mailbox.close(false);
            sendUntagged(response.toString());
            sendTaggedOk(tag, L10N.getString("imap.status_complete"));

        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.status_failed"));
        }
    }

    private void handleAppend(String tag, String args) throws IOException {
        if (store == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }

        String remaining = args.trim();

        String mailboxName;
        if (remaining.startsWith("\"")) {
            int endQuote = remaining.indexOf('"', 1);
            if (endQuote < 0) {
                sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
                return;
            }
            mailboxName = remaining.substring(1, endQuote);
            remaining = remaining.substring(endQuote + 1).trim();
        } else {
            int spaceIdx = remaining.indexOf(' ');
            if (spaceIdx < 0) {
                sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
                return;
            }
            mailboxName = remaining.substring(0, spaceIdx);
            remaining = remaining.substring(spaceIdx + 1).trim();
        }

        Set<Flag> flags = null;
        if (remaining.startsWith("(")) {
            int endParen = remaining.indexOf(')');
            if (endParen < 0) {
                sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
                return;
            }
            String flagStr = remaining.substring(1, endParen);
            flags = EnumSet.noneOf(Flag.class);
            StringTokenizer flagTokenizer = new StringTokenizer(flagStr);
            while (flagTokenizer.hasMoreTokens()) {
                String f = flagTokenizer.nextToken();
                if (!f.isEmpty()) {
                    Flag flag = Flag.fromImapAtom(f);
                    if (flag != null) {
                        flags.add(flag);
                    }
                }
            }
            remaining = remaining.substring(endParen + 1).trim();
        }

        OffsetDateTime internalDate = null;
        if (remaining.startsWith("\"")) {
            int endQuote = remaining.indexOf('"', 1);
            if (endQuote < 0) {
                sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
                return;
            }
            String dateStr = remaining.substring(1, endQuote);
            try {
                internalDate = parseInternalDate(dateStr);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Invalid internal date format: "
                        + dateStr, e);
            }
            remaining = remaining.substring(endQuote + 1).trim();
        }

        if (!remaining.startsWith("{") || !remaining.endsWith("}")) {
            sendTaggedBad(tag, L10N.getString("imap.err.literal_expected"));
            return;
        }

        String literalSpec = remaining.substring(1, remaining.length() - 1);
        boolean nonSync = literalSpec.endsWith("+");
        if (nonSync) {
            literalSpec = literalSpec.substring(0, literalSpec.length() - 1);
        }

        long literalSize;
        try {
            literalSize = Long.parseLong(literalSpec);
        } catch (NumberFormatException e) {
            sendTaggedBad(tag, L10N.getString("imap.err.invalid_literal_size"));
            return;
        }

        QuotaManager quotaManager = server.getQuotaManager();
        if (quotaManager != null) {
            if (!quotaManager.canStore(authenticatedUser, literalSize)) {
                Quota quota = quotaManager.getQuota(authenticatedUser);
                sendTaggedNo(tag, "[OVERQUOTA] "
                        + MessageFormat.format(
                                L10N.getString("imap.err.quota_exceeded"),
                                QuotaPolicy.formatSize(literalSize),
                                QuotaPolicy.formatSize(
                                        quota.getStorageRemaining())));
                return;
            }
        }

        try {
            appendMailbox = store.openMailbox(mailboxName, false);
            appendMailbox.startAppendMessage(flags, internalDate);
            appendTag = tag;
            appendLiteralRemaining = literalSize;
            appendMailboxName = mailboxName;
            appendMessageSize = literalSize;

            addSessionEvent("APPEND_START");
            addSessionAttribute("imap.append.mailbox", mailboxName);
            addSessionAttribute("imap.append.size", literalSize);

            if (!nonSync) {
                sendContinuation(L10N.getString("imap.ready_for_literal"));
            }

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to start APPEND to "
                    + mailboxName, e);
            recordSessionException(e);
            sendTaggedNo(tag, "[TRYCREATE] "
                    + L10N.getString("imap.err.append_failed"));
        }
    }

    private OffsetDateTime parseInternalDate(String dateStr) {
        return OffsetDateTime.now();
    }

    private void handleIdle(String tag) throws IOException {
        if (!server.isEnableIDLE()) {
            sendTaggedBad(tag, L10N.getString("imap.err.unknown_command"));
            return;
        }

        idling = true;
        idleTag = tag;
        sendContinuation(L10N.getString("imap.idle_waiting"));
    }

    private void handleIdleDone() throws IOException {
        idling = false;
        sendTaggedOk(idleTag, L10N.getString("imap.idle_complete"));
        idleTag = null;
    }

    // ── QUOTA commands ──

    private void handleGetQuota(String tag, String args) throws IOException {
        if (!server.isEnableQUOTA()) {
            sendTaggedBad(tag, L10N.getString("imap.err.quota_not_supported"));
            return;
        }

        QuotaManager quotaManager = server.getQuotaManager();
        if (quotaManager == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.quota_not_configured"));
            return;
        }

        String quotaRoot = parseQuotaRoot(args);
        String targetUser = extractUserFromQuotaRoot(quotaRoot);
        if (targetUser == null) {
            targetUser = authenticatedUser;
        }

        if (!targetUser.equals(authenticatedUser)
                && !getRealm().isUserInRole(authenticatedUser, "admin")) {
            sendTaggedNo(tag, L10N.getString("imap.err.quota_access_denied"));
            return;
        }

        Quota quota = quotaManager.getQuota(targetUser);
        sendQuotaResponse(quotaRoot, quota);

        sendTaggedOk(tag, L10N.getString("imap.quota_complete"));
    }

    private void handleGetQuotaRoot(String tag, String args) throws IOException {
        if (!server.isEnableQUOTA()) {
            sendTaggedBad(tag, L10N.getString("imap.err.quota_not_supported"));
            return;
        }

        QuotaManager quotaManager = server.getQuotaManager();
        if (quotaManager == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.quota_not_configured"));
            return;
        }

        String mailboxName = parseMailboxName(args);
        if (mailboxName == null || mailboxName.isEmpty()) {
            sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String quotaRoot = "";
        sendUntagged("QUOTAROOT " + quoteString(mailboxName) + " "
                + quoteString(quotaRoot));

        Quota quota = quotaManager.getQuota(authenticatedUser);
        sendQuotaResponse(quotaRoot, quota);

        sendTaggedOk(tag, L10N.getString("imap.quotaroot_complete"));
    }

    private void handleSetQuota(String tag, String args) throws IOException {
        if (!server.isEnableQUOTA()) {
            sendTaggedBad(tag, L10N.getString("imap.err.quota_not_supported"));
            return;
        }

        QuotaManager quotaManager = server.getQuotaManager();
        if (quotaManager == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.quota_not_configured"));
            return;
        }

        if (!getRealm().isUserInRole(authenticatedUser, "admin")) {
            sendTaggedNo(tag,
                    L10N.getString("imap.err.quota_permission_denied"));
            return;
        }

        int parenStart = args.indexOf('(');
        if (parenStart < 0) {
            sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String quotaRoot = parseQuotaRoot(args.substring(0, parenStart).trim());
        String targetUser = extractUserFromQuotaRoot(quotaRoot);
        if (targetUser == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.invalid_quota_root"));
            return;
        }

        String resourceList = args.substring(parenStart + 1);
        int parenEnd = resourceList.lastIndexOf(')');
        if (parenEnd >= 0) {
            resourceList = resourceList.substring(0, parenEnd);
        }

        long storageLimit = -1;
        long messageLimit = -1;

        StringTokenizer st = new StringTokenizer(resourceList);
        while (st.hasMoreTokens()) {
            String resourceName = st.nextToken().toUpperCase(Locale.ROOT);
            if (!st.hasMoreTokens()) {
                sendTaggedBad(tag,
                        L10N.getString("imap.err.invalid_arguments"));
                return;
            }
            String limitStr = st.nextToken();

            try {
                long limit = Long.parseLong(limitStr);
                if ("STORAGE".equals(resourceName)) {
                    storageLimit = limit * 1024;
                } else if ("MESSAGE".equals(resourceName)) {
                    messageLimit = limit;
                }
            } catch (NumberFormatException e) {
                sendTaggedBad(tag,
                        L10N.getString("imap.err.invalid_arguments"));
                return;
            }
        }

        quotaManager.setUserQuota(targetUser, storageLimit, messageLimit);

        Quota quota = quotaManager.getQuota(targetUser);
        sendQuotaResponse(quotaRoot, quota);

        sendTaggedOk(tag, L10N.getString("imap.setquota_complete"));
    }

    private void sendQuotaResponse(String quotaRoot, Quota quota)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("QUOTA ").append(quoteString(quotaRoot)).append(" (");

        if (!quota.isStorageUnlimited()) {
            sb.append("STORAGE ").append(quota.getStorageUsedKB());
            sb.append(" ").append(quota.getStorageLimitKB());
        }

        if (!quota.isMessageUnlimited()) {
            if (!quota.isStorageUnlimited()) {
                sb.append(" ");
            }
            sb.append("MESSAGE ").append(quota.getMessageCount());
            sb.append(" ").append(quota.getMessageLimit());
        }

        sb.append(")");
        sendUntagged(sb.toString());
    }

    private String parseQuotaRoot(String args) {
        if (args == null) {
            return "";
        }
        args = args.trim();
        if (args.startsWith("\"")) {
            int endQuote = args.indexOf('"', 1);
            if (endQuote > 0) {
                return args.substring(1, endQuote);
            }
        }
        return args;
    }

    private String extractUserFromQuotaRoot(String quotaRoot) {
        if (quotaRoot == null || quotaRoot.isEmpty()) {
            return authenticatedUser;
        }
        if (quotaRoot.startsWith("user.")) {
            return quotaRoot.substring(5);
        }
        return quotaRoot;
    }

    private String quoteString(String s) {
        if (s == null || s.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '(' || c == ')' || c == '{' || c == '"'
                    || c == '\\' || c < 32) {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    // ── SELECTED state commands ──

    private void handleClose(String tag) throws IOException {
        if (selectedMailbox != null) {
            selectedMailbox.close(!selectedReadOnly);
            selectedMailbox = null;
        }
        selectedReadOnly = false;
        state = IMAPState.AUTHENTICATED;
        sendTaggedOk(tag, L10N.getString("imap.close_complete"));
    }

    private void handleUnselect(String tag) throws IOException {
        if (selectedMailbox != null) {
            selectedMailbox.close(false);
            selectedMailbox = null;
        }
        selectedReadOnly = false;
        state = IMAPState.AUTHENTICATED;
        sendTaggedOk(tag, L10N.getString("imap.unselect_complete"));
    }

    private void handleExpunge(String tag) throws IOException {
        if (selectedReadOnly) {
            sendTaggedNo(tag, L10N.getString("imap.err.read_only"));
            return;
        }

        try {
            List<Integer> expunged = selectedMailbox.expunge();
            for (int msgNum : expunged) {
                sendUntagged(msgNum + " EXPUNGE");
            }
            sendTaggedOk(tag, L10N.getString("imap.expunge_complete"));
        } catch (IOException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.expunge_failed"));
        }
    }

    private void handleSearch(String tag, String args, boolean uid)
            throws IOException {
        if (selectedMailbox == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }

        try {
            SearchParser parser = new SearchParser(args);
            org.bluezoo.gumdrop.mailbox.SearchCriteria criteria =
                    parser.parse();

            List<Integer> results = selectedMailbox.search(criteria);

            StringBuilder response = new StringBuilder("SEARCH");
            for (Integer msgNum : results) {
                if (uid) {
                    String uidStr = selectedMailbox.getUniqueId(msgNum);
                    response.append(" ").append(uidStr);
                } else {
                    response.append(" ").append(msgNum);
                }
            }

            sendUntagged(response.toString());
            sendTaggedOk(tag, L10N.getString("imap.search_complete"));

        } catch (java.text.ParseException e) {
            sendTaggedBad(tag, MessageFormat.format(
                    L10N.getString("imap.err.search_syntax"), e.getMessage()));
        } catch (UnsupportedOperationException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.search_not_supported"));
        }
    }

    private void handleUid(String tag, String args) throws IOException {
        int spaceIndex = args.indexOf(' ');
        if (spaceIndex <= 0) {
            sendTaggedBad(tag, L10N.getString("imap.err.uid_syntax"));
            return;
        }

        String command = args.substring(0, spaceIndex).toUpperCase();
        String subArgs = args.substring(spaceIndex + 1);

        switch (command) {
            case "FETCH":
                handleFetch(tag, subArgs, true);
                break;
            case "SEARCH":
                handleSearch(tag, subArgs, true);
                break;
            case "STORE":
                handleStore(tag, subArgs, true);
                break;
            case "COPY":
                handleCopy(tag, subArgs, true);
                break;
            case "MOVE":
                handleMove(tag, subArgs, true);
                break;
            case "EXPUNGE":
                handleExpunge(tag);
                break;
            default:
                sendTaggedBad(tag, MessageFormat.format(
                        L10N.getString("imap.err.unknown_command"),
                        "UID " + command));
        }
    }

    private void handleFetch(String tag, String args, boolean uid)
            throws IOException {
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    private void handleStore(String tag, String args, boolean uid)
            throws IOException {
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    private void handleCopy(String tag, String args, boolean uid)
            throws IOException {
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    private void handleMove(String tag, String args, boolean uid)
            throws IOException {
        if (!server.isEnableMOVE()) {
            sendTaggedBad(tag, L10N.getString("imap.err.unknown_command"));
            return;
        }
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    // ── Helpers ──

    private void sendMailboxUpdates() throws IOException {
        // TODO: Send EXISTS/EXPUNGE/FETCH updates for mailbox changes
    }

    private String parseMailboxName(String arg) {
        if (arg == null || arg.isEmpty()) {
            return null;
        }
        arg = arg.trim();
        if (arg.startsWith("\"") && arg.endsWith("\"")) {
            return arg.substring(1, arg.length() - 1);
        }
        return arg;
    }

    private String[] parseQuotedStrings(String args, int count) {
        List<String> result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean wasQuoted = false;
        boolean escaped = false;

        for (int i = 0; i < args.length() && result.size() < count; i++) {
            char c = args.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                if (inQuote) {
                    result.add(current.toString());
                    current.setLength(0);
                    wasQuoted = false;
                } else {
                    wasQuoted = true;
                }
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0 || wasQuoted) {
                    result.add(current.toString());
                    current.setLength(0);
                    wasQuoted = false;
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0 || wasQuoted) {
            result.add(current.toString());
        }

        return result.isEmpty() ? null : result.toArray(new String[0]);
    }

    private String quoteMailboxName(String name) {
        if (name.contains(" ") || name.contains("\"") || name.contains("\\")) {
            return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"";
        }
        return name;
    }

    private String formatFlags(Set<Flag> flags) {
        if (flags == null || flags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Flag flag : flags) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(flag.getImapAtom());
        }
        return sb.toString();
    }

    // ── Response methods ──

    private void sendUntagged(String response) throws IOException {
        sendLine("* " + response);
    }

    private void sendTaggedOk(String tag, String message) throws IOException {
        sendLine(tag + " OK " + message);
    }

    private void sendTaggedNo(String tag, String message) throws IOException {
        sendLine(tag + " NO " + message);
    }

    private void sendTaggedBad(String tag, String message) throws IOException {
        sendLine(tag + " BAD " + message);
    }

    private void sendContinuation(String text) throws IOException {
        sendLine("+ " + text);
    }

    private void sendLine(String line) throws IOException {
        byte[] bytes = (line + CRLF).getBytes(US_ASCII);
        endpoint.send(ByteBuffer.wrap(bytes));
    }

    // ── State inner classes ──

    private class ConnectedStateImpl implements ConnectedState {

        @Override
        public void acceptConnection(String greeting,
                NotAuthenticatedHandler handler) {
            notAuthenticatedHandler = handler;
            try {
                String caps = server.getCapabilities(false, endpoint.isSecure());
                sendUntagged("OK [CAPABILITY " + caps + "] " + greeting);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send greeting", e);
                closeEndpoint();
            }
        }

        @Override
        public void acceptPreauth(String greeting, AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            state = IMAPState.AUTHENTICATED;
            try {
                String caps = server.getCapabilities(true, endpoint.isSecure());
                sendUntagged("PREAUTH [CAPABILITY " + caps + "] " + greeting);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send PREAUTH greeting", e);
                closeEndpoint();
            }
        }

        @Override
        public void rejectConnection() {
            rejectConnection(L10N.getString("imap.connection_rejected"));
        }

        @Override
        public void rejectConnection(String message) {
            try {
                sendUntagged("BYE " + message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send BYE", e);
            }
            closeEndpoint();
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class AuthenticateStateImpl implements AuthenticateState {
        private final String tag;
        private final String mechanism;

        AuthenticateStateImpl(String tag) {
            this(tag, "PASSWORD");
        }

        AuthenticateStateImpl(String tag, String mechanism) {
            this.tag = tag;
            this.mechanism = mechanism;
        }

        @Override
        public void accept(MailboxStore newStore, AuthenticatedHandler handler) {
            accept(L10N.getString("imap.auth_complete"), newStore, handler);
        }

        @Override
        public void accept(String message, MailboxStore newStore,
                AuthenticatedHandler handler) {
            store = newStore;
            authenticatedHandler = handler;
            state = IMAPState.AUTHENTICATED;
            startAuthenticatedSpan(authenticatedUser, mechanism);
            resetAuthState();
            try {
                String caps = server.getCapabilities(true, endpoint.isSecure());
                sendTaggedOk(tag, "[CAPABILITY " + caps + "] " + message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send auth OK", e);
            }
        }

        @Override
        public void reject(String message, NotAuthenticatedHandler handler) {
            notAuthenticatedHandler = handler;
            addSessionEvent("AUTH_REJECTED");
            resetAuthState();
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send auth rejected", e);
            }
        }

        @Override
        public void rejectAndClose(String message) {
            addSessionEvent("AUTH_REJECTED_CLOSE");
            resetAuthState();
            try {
                sendUntagged("BYE " + message);
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send auth rejected", e);
            }
            closeEndpoint();
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class SelectStateImpl implements SelectState {
        private final String tag;

        SelectStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void selectOk(Mailbox mailbox, boolean readOnly, Set<Flag> flags,
                Set<Flag> permanentFlags, int exists, int recent,
                long uidValidity, long uidNext, SelectedHandler handler) {
            selectedMailbox = mailbox;
            selectedHandler = handler;
            selectedReadOnly = readOnly;
            state = IMAPState.SELECTED;
            addSessionAttribute("imap.mailbox", mailbox.getName());
            addSessionAttribute("imap.mailbox.readonly", readOnly);
            addSessionEvent(readOnly ? "EXAMINE" : "SELECT");
            try {
                sendUntagged(exists + " EXISTS");
                sendUntagged(recent + " RECENT");
                sendUntagged("FLAGS (" + formatFlags(flags) + ")");
                sendUntagged("OK [PERMANENTFLAGS ("
                        + formatFlags(permanentFlags) + " \\*)]");
                sendUntagged("OK [UIDVALIDITY " + uidValidity + "]");
                sendUntagged("OK [UIDNEXT " + uidNext + "]");
                String accessMode = readOnly ? "[READ-ONLY]" : "[READ-WRITE]";
                sendTaggedOk(tag, accessMode + " "
                        + L10N.getString("imap.select_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send SELECT response", e);
            }
        }

        @Override
        public void mailboxNotFound(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send select NO", e);
            }
        }

        @Override
        public void accessDenied(String message, AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send select access denied", e);
            }
        }

        @Override
        public void no(String message, AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send select NO", e);
            }
        }

        @Override
        public void selectOk(Mailbox mailbox, boolean readOnly, Set<Flag> flags,
                SelectedHandler handler) {
            try {
                Set<Flag> permanentFlags = mailbox.getPermanentFlags();
                int exists = mailbox.getMessageCount();
                int recent = 0;
                long uidValidity = mailbox.getUidValidity();
                long uidNext = mailbox.getUidNext();
                selectOk(mailbox, readOnly, flags, permanentFlags, exists,
                        recent, uidValidity, uidNext, handler);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to query mailbox for SELECT response", e);
                selectFailed("Cannot query mailbox", authenticatedHandler);
            }
        }

        @Override
        public void selectFailed(String message, AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send select failed", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private void doServerShuttingDown() {
        try {
            sendUntagged("BYE "
                    + L10N.getString("imap.server_shutting_down"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send shutdown BYE", e);
        }
        closeEndpoint();
    }
}
