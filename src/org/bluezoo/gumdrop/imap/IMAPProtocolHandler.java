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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.bluezoo.gumdrop.imap.handler.AppendState;
import org.bluezoo.gumdrop.imap.handler.AuthenticatedHandler;
import org.bluezoo.gumdrop.imap.handler.AuthenticatedStatusState;
import org.bluezoo.gumdrop.imap.handler.AuthenticateState;
import org.bluezoo.gumdrop.imap.handler.ClientConnected;
import org.bluezoo.gumdrop.imap.handler.CloseState;
import org.bluezoo.gumdrop.imap.handler.ConnectedState;
import org.bluezoo.gumdrop.imap.handler.CopyState;
import org.bluezoo.gumdrop.imap.handler.CreateState;
import org.bluezoo.gumdrop.imap.handler.DeleteState;
import org.bluezoo.gumdrop.imap.handler.ExpungeState;
import org.bluezoo.gumdrop.imap.handler.FetchState;
import org.bluezoo.gumdrop.imap.handler.ListState;
import org.bluezoo.gumdrop.imap.handler.MoveState;
import org.bluezoo.gumdrop.imap.handler.NotAuthenticatedHandler;
import org.bluezoo.gumdrop.imap.handler.QuotaState;
import org.bluezoo.gumdrop.imap.handler.RenameState;
import org.bluezoo.gumdrop.imap.handler.SearchState;
import org.bluezoo.gumdrop.imap.handler.SelectedHandler;
import org.bluezoo.gumdrop.imap.handler.SelectedStatusState;
import org.bluezoo.gumdrop.imap.handler.SelectState;
import org.bluezoo.gumdrop.imap.handler.StoreState;
import org.bluezoo.gumdrop.imap.handler.SubscribeState;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.IMAPMessageDescriptor;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.MessageSet;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.StoreAction;
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
        OAUTH_RESPONSE
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

            if (appendDataHandler != null) {
                appendDataHandler.appendData(appendMailbox, slice);
            } else {
                appendMailbox.appendMessageContent(slice);
            }

            buffer.position(buffer.position() + toConsume);
            appendLiteralRemaining -= toConsume;

            if (appendDataHandler != null
                    && appendDataHandler.wantsPause()
                    && appendLiteralRemaining > 0) {
                appendDataHandler.setResumeCallback(
                        new AppendResumeTask());
                endpoint.pauseRead();
                return;
            }
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
        String authzid = null;
        if (initialResponse != null && !initialResponse.isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder()
                        .decode(initialResponse);
                String authzidParam = new String(decoded,
                        StandardCharsets.US_ASCII);
                if (!authzidParam.isEmpty()) {
                    authzid = authzidParam;
                }
            } catch (IllegalArgumentException e) {
                sendTaggedBad(pendingAuthTag,
                        L10N.getString("imap.err.invalid_base64"));
                return;
            }
        }

        Realm.CertificateAuthenticationResult result =
                SASLUtils.authenticateExternal(
                        endpoint, getRealm(), authzid);
        if (result == null || !result.valid) {
            authFailed();
            return;
        }

        openMailStore(result.username, "EXTERNAL");
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            if (readOnly) {
                selectedHandler.examine(new SelectStateImpl(tag), store,
                        mailboxName);
            } else {
                selectedHandler.select(new SelectStateImpl(tag), store,
                        mailboxName);
            }
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.create(new CreateStateImpl(tag), store,
                    mailboxName);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.create(new CreateStateImpl(tag), store,
                    mailboxName);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.delete(new DeleteStateImpl(tag), store,
                    mailboxName);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.delete(new DeleteStateImpl(tag), store,
                    mailboxName);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.rename(new RenameStateImpl(tag), store,
                    parts[0], parts[1]);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.rename(new RenameStateImpl(tag), store,
                    parts[0], parts[1]);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.subscribe(new SubscribeStateImpl(tag), store,
                    mailboxName);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.subscribe(new SubscribeStateImpl(tag), store,
                    mailboxName);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.unsubscribe(new SubscribeStateImpl(tag), store,
                    mailboxName);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.unsubscribe(new SubscribeStateImpl(tag), store,
                    mailboxName);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.list(new ListStateImpl(tag, false), store,
                    reference, pattern);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.list(new ListStateImpl(tag, false), store,
                    reference, pattern);
            return;
        }

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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.lsub(new ListStateImpl(tag, true), store,
                    parts[0], parts[1]);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.lsub(new ListStateImpl(tag, true), store,
                    parts[0], parts[1]);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            Set<StatusItem> statusItems = parseStatusItems(attrs);
            selectedHandler.status(
                    new SelectedStatusStateImpl(tag, mailboxName, attrs),
                    store, mailboxName, statusItems);
            return;
        }
        if (authenticatedHandler != null) {
            Set<StatusItem> statusItems = parseStatusItems(attrs);
            authenticatedHandler.status(
                    new AuthenticatedStatusStateImpl(tag, mailboxName, attrs),
                    store, mailboxName, statusItems);
            return;
        }

        executeStatus(tag, mailboxName, attrs);
    }

    private Set<StatusItem> parseStatusItems(String[] attrs) {
        Set<StatusItem> items = EnumSet.noneOf(StatusItem.class);
        for (String attr : attrs) {
            StatusItem item = StatusItem.fromImapName(attr);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private void executeStatus(String tag, String mailboxName, String[] attrs)
            throws IOException {
        try {
            Mailbox mailbox = store.openMailbox(mailboxName, true);
            StringBuilder response = new StringBuilder();
            String quotedName = quoteMailboxName(mailboxName);
            response.append("STATUS ");
            response.append(quotedName);
            response.append(" (");

            boolean first = true;
            for (String attr : attrs) {
                if (!first) {
                    response.append(' ');
                }
                first = false;

                String upper = attr.toUpperCase(Locale.ROOT);
                switch (upper) {
                    case "MESSAGES":
                        int msgCount = mailbox.getMessageCount();
                        response.append("MESSAGES ");
                        response.append(msgCount);
                        break;
                    case "UIDNEXT":
                        long uidNext = mailbox.getUidNext();
                        response.append("UIDNEXT ");
                        response.append(uidNext);
                        break;
                    case "UIDVALIDITY":
                        long uidValidity = mailbox.getUidValidity();
                        response.append("UIDVALIDITY ");
                        response.append(uidValidity);
                        break;
                    case "UNSEEN":
                        response.append("UNSEEN 0");
                        break;
                    case "RECENT":
                        response.append("RECENT 0");
                        break;
                    case "SIZE":
                        long size = mailbox.getMailboxSize();
                        response.append("SIZE ");
                        response.append(size);
                        break;
                    default:
                        break;
                }
            }
            response.append(')');

            mailbox.close(false);
            String statusResponse = response.toString();
            sendUntagged(statusResponse);
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

        if (literalSize < 0 || literalSize > server.getMaxLiteralSize()) {
            sendTaggedNo(tag, L10N.getString("imap.err.literal_too_large"));
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.append(
                    new AppendStateImpl(tag, literalSize, nonSync,
                            mailboxName, flags, internalDate),
                    store, mailboxName, flags, internalDate);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.append(
                    new AppendStateImpl(tag, literalSize, nonSync,
                            mailboxName, flags, internalDate),
                    store, mailboxName, flags, internalDate);
            return;
        }

        executeAppendDirect(tag, mailboxName, flags, internalDate,
                literalSize, nonSync);
    }

    private void executeAppendDirect(String tag, String mailboxName,
            Set<Flag> flags, OffsetDateTime internalDate,
            long literalSize, boolean nonSync) throws IOException {
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.getQuota(
                    new QuotaStateImpl(tag, QuotaStateImpl.Command.GET_QUOTA,
                            quotaRoot, targetUser),
                    quotaManager, store, quotaRoot);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.getQuota(
                    new QuotaStateImpl(tag, QuotaStateImpl.Command.GET_QUOTA,
                            quotaRoot, targetUser),
                    quotaManager, store, quotaRoot);
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

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.getQuotaRoot(
                    new QuotaStateImpl(tag,
                            QuotaStateImpl.Command.GET_QUOTA_ROOT,
                            mailboxName, authenticatedUser),
                    quotaManager, store, mailboxName);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.getQuotaRoot(
                    new QuotaStateImpl(tag,
                            QuotaStateImpl.Command.GET_QUOTA_ROOT,
                            mailboxName, authenticatedUser),
                    quotaManager, store, mailboxName);
            return;
        }

        String quotaRoot = "";
        String quotedMailbox = quoteString(mailboxName);
        String quotedRoot = quoteString(quotaRoot);
        sendUntagged("QUOTAROOT " + quotedMailbox + " " + quotedRoot);

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

        Map<String, Long> resourceLimits = new LinkedHashMap<String, Long>();
        if (storageLimit >= 0) {
            Long storageLimitValue = Long.valueOf(storageLimit);
            resourceLimits.put("STORAGE", storageLimitValue);
        }
        if (messageLimit >= 0) {
            Long messageLimitValue = Long.valueOf(messageLimit);
            resourceLimits.put("MESSAGE", messageLimitValue);
        }

        if (state == IMAPState.SELECTED && selectedHandler != null) {
            selectedHandler.setQuota(
                    new QuotaStateImpl(tag,
                            QuotaStateImpl.Command.SET_QUOTA,
                            quotaRoot, targetUser, storageLimit,
                            messageLimit),
                    quotaManager, store, quotaRoot, resourceLimits);
            return;
        }
        if (authenticatedHandler != null) {
            authenticatedHandler.setQuota(
                    new QuotaStateImpl(tag,
                            QuotaStateImpl.Command.SET_QUOTA,
                            quotaRoot, targetUser, storageLimit,
                            messageLimit),
                    quotaManager, store, quotaRoot, resourceLimits);
            return;
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
        if (selectedHandler != null) {
            selectedHandler.close(new CloseStateImpl(tag, true),
                    selectedMailbox);
            return;
        }

        if (selectedMailbox != null) {
            selectedMailbox.close(!selectedReadOnly);
            selectedMailbox = null;
        }
        selectedReadOnly = false;
        state = IMAPState.AUTHENTICATED;
        sendTaggedOk(tag, L10N.getString("imap.close_complete"));
    }

    private void handleUnselect(String tag) throws IOException {
        if (selectedHandler != null) {
            selectedHandler.unselect(new CloseStateImpl(tag, false),
                    selectedMailbox);
            return;
        }

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

        if (selectedHandler != null) {
            selectedHandler.expunge(new ExpungeStateImpl(tag),
                    selectedMailbox);
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

        if (selectedHandler != null) {
            try {
                SearchParser parser = new SearchParser(args);
                SearchCriteria criteria = parser.parse();
                SearchStateImpl searchState = new SearchStateImpl(tag, uid);
                searchState.setCriteria(criteria);
                if (uid) {
                    selectedHandler.uidSearch(searchState,
                            selectedMailbox, criteria);
                } else {
                    selectedHandler.search(searchState,
                            selectedMailbox, criteria);
                }
                return;
            } catch (ParseException e) {
                sendTaggedBad(tag, MessageFormat.format(
                        L10N.getString("imap.err.search_syntax"),
                        e.getMessage()));
                return;
            }
        }

        executeSearch(tag, args, uid);
    }

    private void executeSearch(String tag, String args, boolean uid)
            throws IOException {
        try {
            SearchParser parser = new SearchParser(args);
            SearchCriteria criteria = parser.parse();
            executeSearchWithCriteria(tag, criteria, uid);
        } catch (ParseException e) {
            sendTaggedBad(tag, MessageFormat.format(
                    L10N.getString("imap.err.search_syntax"), e.getMessage()));
        } catch (UnsupportedOperationException e) {
            sendTaggedNo(tag, L10N.getString("imap.err.search_not_supported"));
        }
    }

    private void executeSearchWithCriteria(String tag,
            SearchCriteria criteria, boolean uid) throws IOException {
        try {
            List<Integer> results = selectedMailbox.search(criteria);

            StringBuilder response = new StringBuilder("SEARCH");
            for (Integer msgNum : results) {
                if (uid) {
                    String uidStr = selectedMailbox.getUniqueId(msgNum);
                    response.append(' ');
                    response.append(uidStr);
                } else {
                    response.append(' ');
                    response.append(msgNum);
                }
            }

            String searchResponse = response.toString();
            sendUntagged(searchResponse);
            sendTaggedOk(tag, L10N.getString("imap.search_complete"));

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
        if (selectedMailbox == null) {
            sendTaggedNo(tag,
                    L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }

        int spaceIdx = args.indexOf(' ');
        if (spaceIdx <= 0) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String seqSetStr = args.substring(0, spaceIdx);
        String itemsStr = args.substring(spaceIdx + 1).trim();

        MessageSet seqSet;
        try {
            seqSet = MessageSet.parse(seqSetStr);
        } catch (IllegalArgumentException e) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        Set<String> fetchItems = parseFetchItems(itemsStr);
        if (fetchItems == null || fetchItems.isEmpty()) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        if (selectedHandler != null) {
            FetchStateImpl state =
                    new FetchStateImpl(tag, seqSet, fetchItems, uid);
            if (uid) {
                selectedHandler.uidFetch(state, selectedMailbox,
                        seqSet, fetchItems);
            } else {
                selectedHandler.fetch(state, selectedMailbox,
                        seqSet, fetchItems);
            }
            return;
        }

        executeFetch(tag, selectedMailbox, seqSet, fetchItems, uid);
    }

    private void handleStore(String tag, String args, boolean uid)
            throws IOException {
        if (selectedMailbox == null) {
            sendTaggedNo(tag,
                    L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }

        if (selectedReadOnly) {
            sendTaggedNo(tag, L10N.getString("imap.err.read_only"));
            return;
        }

        int spaceIdx = args.indexOf(' ');
        if (spaceIdx <= 0) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String seqSetStr = args.substring(0, spaceIdx);
        String rest = args.substring(spaceIdx + 1).trim();

        MessageSet seqSet;
        try {
            seqSet = MessageSet.parse(seqSetStr);
        } catch (IllegalArgumentException e) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        spaceIdx = rest.indexOf(' ');
        if (spaceIdx <= 0) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String actionStr = rest.substring(0, spaceIdx);
        String flagsStr = rest.substring(spaceIdx + 1).trim();

        boolean silent = actionStr.toUpperCase(Locale.ENGLISH)
                .endsWith(".SILENT");
        StoreAction action = StoreAction.fromImapKeyword(actionStr);
        if (action == null) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        Set<Flag> flags = parseFlagList(flagsStr);
        if (flags == null) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        if (selectedHandler != null) {
            StoreStateImpl state =
                    new StoreStateImpl(tag);
            if (uid) {
                selectedHandler.uidStore(state, selectedMailbox,
                        seqSet, action, flags, silent);
            } else {
                selectedHandler.store(state, selectedMailbox,
                        seqSet, action, flags, silent);
            }
            return;
        }

        executeStore(tag, selectedMailbox, seqSet, action, flags,
                silent, uid);
    }

    private void handleCopy(String tag, String args, boolean uid)
            throws IOException {
        if (selectedMailbox == null) {
            sendTaggedNo(tag,
                    L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }

        int spaceIdx = args.indexOf(' ');
        if (spaceIdx <= 0) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String seqSetStr = args.substring(0, spaceIdx);
        String mailboxName = parseMailboxName(
                args.substring(spaceIdx + 1).trim());

        if (mailboxName == null || mailboxName.isEmpty()) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        MessageSet seqSet;
        try {
            seqSet = MessageSet.parse(seqSetStr);
        } catch (IllegalArgumentException e) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        if (selectedHandler != null) {
            CopyStateImpl state = new CopyStateImpl(tag);
            if (uid) {
                selectedHandler.uidCopy(state, store, selectedMailbox,
                        seqSet, mailboxName);
            } else {
                selectedHandler.copy(state, store, selectedMailbox,
                        seqSet, mailboxName);
            }
            return;
        }

        executeCopy(tag, selectedMailbox, seqSet, mailboxName, uid);
    }

    private void handleMove(String tag, String args, boolean uid)
            throws IOException {
        if (!server.isEnableMOVE()) {
            sendTaggedBad(tag, MessageFormat.format(
                    L10N.getString("imap.err.unknown_command"), "MOVE"));
            return;
        }

        if (selectedMailbox == null) {
            sendTaggedNo(tag,
                    L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }

        if (selectedReadOnly) {
            sendTaggedNo(tag, L10N.getString("imap.err.read_only"));
            return;
        }

        int spaceIdx = args.indexOf(' ');
        if (spaceIdx <= 0) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        String seqSetStr = args.substring(0, spaceIdx);
        String mailboxName = parseMailboxName(
                args.substring(spaceIdx + 1).trim());

        if (mailboxName == null || mailboxName.isEmpty()) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        MessageSet seqSet;
        try {
            seqSet = MessageSet.parse(seqSetStr);
        } catch (IllegalArgumentException e) {
            sendTaggedBad(tag,
                    L10N.getString("imap.err.invalid_arguments"));
            return;
        }

        if (selectedHandler != null) {
            MoveStateImpl state = new MoveStateImpl(tag);
            if (uid) {
                selectedHandler.uidMove(state, store, selectedMailbox,
                        seqSet, mailboxName);
            } else {
                selectedHandler.move(state, store, selectedMailbox,
                        seqSet, mailboxName);
            }
            return;
        }

        executeMove(tag, selectedMailbox, seqSet, mailboxName, uid);
    }

    // ── FETCH execution ──

    private void executeFetch(String tag, Mailbox mailbox,
            MessageSet seqSet, Set<String> fetchItems, boolean uid)
            throws IOException {
        List<Integer> matching = resolveMatchingMessages(mailbox,
                seqSet, uid);
        boolean needsSeen = fetchNeedsSeen(fetchItems);

        FetchResponseWriter writer = new FetchResponseWriter(
                tag, mailbox, matching, fetchItems, uid, needsSeen);
        writer.writeNext();
    }

    /**
     * Chunked FETCH response writer with write-pacing.
     * Processes one message at a time and uses onWriteReady to pace
     * output, preventing unbounded netOut buffer growth when fetching
     * many or large messages.
     */
    private class FetchResponseWriter implements Runnable {
        private final String tag;
        private final Mailbox mailbox;
        private final List<Integer> matching;
        private final Set<String> fetchItems;
        private final boolean uid;
        private final boolean needsSeen;
        private int index;

        FetchResponseWriter(String tag, Mailbox mailbox,
                List<Integer> matching, Set<String> fetchItems,
                boolean uid, boolean needsSeen) {
            this.tag = tag;
            this.mailbox = mailbox;
            this.matching = matching;
            this.fetchItems = fetchItems;
            this.uid = uid;
            this.needsSeen = needsSeen;
        }

        @Override
        public void run() {
            writeNext();
        }

        void writeNext() {
            try {
                if (index < matching.size()) {
                    int msgNum = matching.get(index).intValue();
                    long msgUid = resolveUid(mailbox, msgNum);
                    sendFetchResponse(mailbox, msgNum, msgUid,
                            fetchItems, uid);
                    if (needsSeen && !selectedReadOnly) {
                        try {
                            mailbox.setFlags(msgNum,
                                    EnumSet.of(Flag.SEEN), true);
                        } catch (UnsupportedOperationException ignored) {
                            // Flags not supported by this mailbox
                        }
                    }
                    index++;

                    if (index < matching.size()) {
                        endpoint.onWriteReady(this);
                    } else {
                        finishFetch();
                    }
                } else {
                    finishFetch();
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Error during chunked FETCH write", e);
                endpoint.onWriteReady(null);
                try {
                    sendTaggedNo(tag,
                            L10N.getString("imap.err.internal_error"));
                } catch (IOException e2) {
                    LOGGER.log(Level.WARNING,
                            "Failed to send FETCH error", e2);
                }
            }
        }

        private void finishFetch() {
            endpoint.onWriteReady(null);
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.fetch_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send FETCH completion", e);
            }
        }
    }

    /**
     * Task to resume reading after an APPEND handler signals readiness.
     * Called by the AppendDataHandler when it is ready for more data.
     */
    private class AppendResumeTask implements Runnable {
        @Override
        public void run() {
            endpoint.resumeRead();
        }
    }

    private void sendFetchResponse(Mailbox mailbox, int msgNum,
            long msgUid, Set<String> fetchItems, boolean uid)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("* " + msgNum + " FETCH (").getBytes(US_ASCII));

        boolean first = true;
        for (String item : fetchItems) {
            if (!first) {
                out.write(' ');
            }
            first = false;

            String upper = item.toUpperCase(Locale.ENGLISH);
            if (upper.equals("FLAGS")) {
                writeFetchFlags(out, mailbox, msgNum);
            } else if (upper.equals("UID")) {
                out.write(("UID " + msgUid).getBytes(US_ASCII));
            } else if (upper.equals("RFC822.SIZE")) {
                writeFetchSize(out, mailbox, msgNum);
            } else if (upper.equals("INTERNALDATE")) {
                writeFetchInternalDate(out, mailbox, msgNum);
            } else if (upper.equals("ENVELOPE")) {
                writeFetchEnvelope(out, mailbox, msgNum);
            } else if (upper.equals("BODYSTRUCTURE")) {
                writeFetchBodyStructure(out, mailbox, msgNum, true);
            } else if (upper.equals("BODY") && !item.contains("[")) {
                writeFetchBodyStructure(out, mailbox, msgNum, false);
            } else if (upper.equals("RFC822")) {
                writeFetchLiteral(out, "RFC822", mailbox, msgNum,
                        FetchSection.FULL);
            } else if (upper.equals("RFC822.HEADER")) {
                writeFetchLiteral(out, "RFC822.HEADER", mailbox,
                        msgNum, FetchSection.HEADER);
            } else if (upper.equals("RFC822.TEXT")) {
                writeFetchLiteral(out, "RFC822.TEXT", mailbox,
                        msgNum, FetchSection.TEXT);
            } else if (upper.startsWith("BODY[")
                    || upper.startsWith("BODY.PEEK[")) {
                writeFetchBodySection(out, item, mailbox, msgNum);
            }
        }

        if (uid && !containsFetchItem(fetchItems, "UID")) {
            if (!first) {
                out.write(' ');
            }
            out.write(("UID " + msgUid).getBytes(US_ASCII));
        }

        out.write(")\r\n".getBytes(US_ASCII));
        endpoint.send(ByteBuffer.wrap(out.toByteArray()));
    }

    private void writeFetchFlags(ByteArrayOutputStream out,
            Mailbox mailbox, int msgNum) throws IOException {
        Set<Flag> flags = mailbox.getFlags(msgNum);
        out.write(("FLAGS (" + formatFlags(flags) + ")")
                .getBytes(US_ASCII));
    }

    private void writeFetchSize(ByteArrayOutputStream out,
            Mailbox mailbox, int msgNum) throws IOException {
        MessageDescriptor desc = mailbox.getMessage(msgNum);
        long size = (desc != null) ? desc.getSize() : 0;
        out.write(("RFC822.SIZE " + size).getBytes(US_ASCII));
    }

    private void writeFetchInternalDate(ByteArrayOutputStream out,
            Mailbox mailbox, int msgNum) throws IOException {
        OffsetDateTime date = resolveInternalDate(mailbox, msgNum);
        out.write(("INTERNALDATE \"" + formatInternalDate(date)
                + "\"").getBytes(US_ASCII));
    }

    private void writeFetchEnvelope(ByteArrayOutputStream out,
            Mailbox mailbox, int msgNum) throws IOException {
        MessageDescriptor desc = mailbox.getMessage(msgNum);
        if (desc instanceof IMAPMessageDescriptor) {
            IMAPMessageDescriptor imapDesc =
                    (IMAPMessageDescriptor) desc;
            IMAPMessageDescriptor.Envelope env =
                    imapDesc.getEnvelope();
            if (env != null) {
                out.write("ENVELOPE ".getBytes(US_ASCII));
                out.write(formatEnvelopeFromDescriptor(env)
                        .getBytes(US_ASCII));
                return;
            }
        }

        byte[] content = readMessageContent(mailbox, msgNum);
        byte[] headerBytes = extractHeaders(content);
        String headers = new String(headerBytes, US_ASCII);
        out.write("ENVELOPE ".getBytes(US_ASCII));
        out.write(formatEnvelopeFromHeaders(headers)
                .getBytes(US_ASCII));
    }

    private void writeFetchBodyStructure(ByteArrayOutputStream out,
            Mailbox mailbox, int msgNum, boolean extensible)
            throws IOException {
        MessageDescriptor desc = mailbox.getMessage(msgNum);
        if (desc instanceof IMAPMessageDescriptor) {
            IMAPMessageDescriptor imapDesc =
                    (IMAPMessageDescriptor) desc;
            IMAPMessageDescriptor.BodyStructure bs =
                    imapDesc.getBodyStructure();
            if (bs != null) {
                String name = extensible ? "BODYSTRUCTURE" : "BODY";
                out.write((name + " ").getBytes(US_ASCII));
                out.write(formatBodyStructureFromDescriptor(bs,
                        extensible).getBytes(US_ASCII));
                return;
            }
        }

        long size = (desc != null) ? desc.getSize() : 0;
        String name = extensible ? "BODYSTRUCTURE" : "BODY";
        out.write((name + " (\"text\" \"plain\""
                + " (\"charset\" \"us-ascii\")"
                + " NIL NIL \"7bit\" " + size + " 0)")
                .getBytes(US_ASCII));
    }

    enum FetchSection { FULL, HEADER, TEXT }

    private void writeFetchLiteral(ByteArrayOutputStream out,
            String itemName, Mailbox mailbox, int msgNum,
            FetchSection section) throws IOException {
        byte[] content = readMessageContent(mailbox, msgNum);
        byte[] data;
        switch (section) {
            case HEADER:
                data = extractHeaders(content);
                break;
            case TEXT:
                data = extractBody(content);
                break;
            default:
                data = content;
                break;
        }
        out.write((itemName + " {" + data.length + "}\r\n")
                .getBytes(US_ASCII));
        out.write(data);
    }

    private void writeFetchBodySection(ByteArrayOutputStream out,
            String item, Mailbox mailbox, int msgNum)
            throws IOException {
        String upper = item.toUpperCase(Locale.ENGLISH);
        int bracketOpen = item.indexOf('[');
        int bracketClose = item.indexOf(']', bracketOpen);
        if (bracketOpen < 0 || bracketClose < 0) {
            return;
        }

        String section = item.substring(bracketOpen + 1, bracketClose);
        String sectionUpper = section.toUpperCase(Locale.ENGLISH);

        long partialOffset = -1;
        long partialLength = -1;
        if (bracketClose + 1 < item.length()
                && item.charAt(bracketClose + 1) == '<') {
            int angleClose = item.indexOf('>', bracketClose + 1);
            if (angleClose > 0) {
                String partial = item.substring(
                        bracketClose + 2, angleClose);
                int dotIdx = partial.indexOf('.');
                if (dotIdx > 0) {
                    partialOffset = Long.parseLong(
                            partial.substring(0, dotIdx));
                    partialLength = Long.parseLong(
                            partial.substring(dotIdx + 1));
                }
            }
        }

        byte[] content = readMessageContent(mailbox, msgNum);
        byte[] data;
        if (sectionUpper.isEmpty()) {
            data = content;
        } else if (sectionUpper.equals("HEADER")) {
            data = extractHeaders(content);
        } else if (sectionUpper.equals("TEXT")) {
            data = extractBody(content);
        } else if (sectionUpper.startsWith("HEADER.FIELDS.NOT ")) {
            Set<String> fields = parseHeaderFieldNames(
                    section.substring("HEADER.FIELDS.NOT ".length()));
            data = extractHeaderFieldsNot(content, fields);
        } else if (sectionUpper.startsWith("HEADER.FIELDS ")) {
            Set<String> fields = parseHeaderFieldNames(
                    section.substring("HEADER.FIELDS ".length()));
            data = extractHeaderFields(content, fields);
        } else if (sectionUpper.equals("MIME")) {
            data = extractHeaders(content);
        } else {
            data = content;
        }

        if (partialOffset >= 0 && partialLength >= 0) {
            int offset = (int) Math.min(partialOffset, data.length);
            int length = (int) Math.min(partialLength,
                    data.length - offset);
            byte[] partial = new byte[length];
            System.arraycopy(data, offset, partial, 0, length);
            String origin = "<" + offset + ">";
            out.write((item.substring(0, bracketClose + 1)
                    + origin + " {" + length + "}\r\n")
                    .getBytes(US_ASCII));
            out.write(partial);
        } else {
            out.write((item.substring(0, bracketClose + 1)
                    + " {" + data.length + "}\r\n")
                    .getBytes(US_ASCII));
            out.write(data);
        }
    }

    // ── STORE execution ──

    private void executeStore(String tag, Mailbox mailbox,
            MessageSet seqSet, StoreAction action, Set<Flag> flags,
            boolean silent, boolean uid) throws IOException {
        List<Integer> matching = resolveMatchingMessages(mailbox,
                seqSet, uid);

        for (int i = 0; i < matching.size(); i++) {
            int msgNum = matching.get(i).intValue();
            try {
                switch (action) {
                    case REPLACE:
                        mailbox.replaceFlags(msgNum, flags);
                        break;
                    case ADD:
                        mailbox.setFlags(msgNum, flags, true);
                        break;
                    case REMOVE:
                        mailbox.setFlags(msgNum, flags, false);
                        break;
                }
                if (!silent) {
                    Set<Flag> newFlags = mailbox.getFlags(msgNum);
                    sendUntagged(msgNum + " FETCH (FLAGS ("
                            + formatFlags(newFlags) + "))");
                }
            } catch (UnsupportedOperationException e) {
                sendTaggedNo(tag,
                        L10N.getString("imap.err.read_only"));
                return;
            }
        }

        sendTaggedOk(tag, L10N.getString("imap.store_complete"));
    }

    // ── COPY execution ──

    private void executeCopy(String tag, Mailbox mailbox,
            MessageSet seqSet, String targetMailboxName, boolean uid)
            throws IOException {
        List<Integer> matching = resolveMatchingMessages(mailbox,
                seqSet, uid);

        try {
            Map<Integer, Long> uidMap = mailbox.copyMessages(matching,
                    targetMailboxName);

            if (uidMap != null && !uidMap.isEmpty()) {
                Mailbox target = store.openMailbox(
                        targetMailboxName, true);
                long uidValidity = target.getUidValidity();
                target.close(false);

                StringBuilder srcUids = new StringBuilder();
                StringBuilder destUids = new StringBuilder();
                boolean first = true;
                for (Map.Entry<Integer, Long> entry
                        : uidMap.entrySet()) {
                    if (!first) {
                        srcUids.append(',');
                        destUids.append(',');
                    }
                    first = false;
                    long srcUid = resolveUid(mailbox,
                            entry.getKey().intValue());
                    srcUids.append(srcUid);
                    destUids.append(entry.getValue());
                }

                sendTaggedOk(tag, "[COPYUID " + uidValidity + " "
                        + srcUids + " " + destUids + "] "
                        + L10N.getString("imap.copy_complete"));
            } else {
                sendTaggedOk(tag,
                        L10N.getString("imap.copy_complete"));
            }
        } catch (UnsupportedOperationException e) {
            sendTaggedNo(tag, "[TRYCREATE] "
                    + L10N.getString("imap.err.mailbox_not_found"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "COPY failed", e);
            sendTaggedNo(tag, "[TRYCREATE] "
                    + L10N.getString("imap.err.mailbox_not_found"));
        }
    }

    // ── MOVE execution ──

    private void executeMove(String tag, Mailbox mailbox,
            MessageSet seqSet, String targetMailboxName, boolean uid)
            throws IOException {
        List<Integer> matching = resolveMatchingMessages(mailbox,
                seqSet, uid);

        try {
            Map<Integer, Long> uidMap = mailbox.moveMessages(matching,
                    targetMailboxName);

            for (int i = matching.size() - 1; i >= 0; i--) {
                int msgNum = matching.get(i).intValue();
                sendUntagged(msgNum + " EXPUNGE");
            }

            if (uidMap != null && !uidMap.isEmpty()) {
                Mailbox target = store.openMailbox(
                        targetMailboxName, true);
                long uidValidity = target.getUidValidity();
                target.close(false);

                StringBuilder srcUids = new StringBuilder();
                StringBuilder destUids = new StringBuilder();
                boolean first = true;
                for (Map.Entry<Integer, Long> entry
                        : uidMap.entrySet()) {
                    if (!first) {
                        srcUids.append(',');
                        destUids.append(',');
                    }
                    first = false;
                    long srcUid = resolveUid(mailbox,
                            entry.getKey().intValue());
                    srcUids.append(srcUid);
                    destUids.append(entry.getValue());
                }

                sendTaggedOk(tag, "[COPYUID " + uidValidity + " "
                        + srcUids + " " + destUids + "] "
                        + L10N.getString("imap.move_complete"));
            } else {
                sendTaggedOk(tag,
                        L10N.getString("imap.move_complete"));
            }
        } catch (UnsupportedOperationException e) {
            sendTaggedNo(tag, "[TRYCREATE] "
                    + L10N.getString("imap.err.mailbox_not_found"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "MOVE failed", e);
            sendTaggedNo(tag, "[TRYCREATE] "
                    + L10N.getString("imap.err.mailbox_not_found"));
        }
    }

    // ── FETCH/STORE/COPY/MOVE helpers ──

    private List<Integer> resolveMatchingMessages(Mailbox mailbox,
            MessageSet seqSet, boolean uid) throws IOException {
        int msgCount = mailbox.getMessageCount();
        List<Integer> matching = new ArrayList<Integer>();

        if (uid) {
            long lastUid = mailbox.getUidNext() - 1;
            for (int msgNum = 1; msgNum <= msgCount; msgNum++) {
                if (mailbox.isDeleted(msgNum)) {
                    continue;
                }
                long msgUid = resolveUid(mailbox, msgNum);
                if (seqSet.contains(msgUid, lastUid)) {
                    matching.add(Integer.valueOf(msgNum));
                }
            }
        } else {
            for (int msgNum = 1; msgNum <= msgCount; msgNum++) {
                if (mailbox.isDeleted(msgNum)) {
                    continue;
                }
                if (seqSet.contains(msgNum, msgCount)) {
                    matching.add(Integer.valueOf(msgNum));
                }
            }
        }

        return matching;
    }

    private long resolveUid(Mailbox mailbox, int msgNum)
            throws IOException {
        try {
            return Long.parseLong(mailbox.getUniqueId(msgNum));
        } catch (NumberFormatException e) {
            return msgNum;
        }
    }

    private OffsetDateTime resolveInternalDate(Mailbox mailbox,
            int msgNum) throws IOException {
        MessageDescriptor desc = mailbox.getMessage(msgNum);
        if (desc instanceof IMAPMessageDescriptor) {
            OffsetDateTime date =
                    ((IMAPMessageDescriptor) desc).getInternalDate();
            if (date != null) {
                return date;
            }
        }
        return OffsetDateTime.now();
    }

    private static final DateTimeFormatter INTERNAL_DATE_FORMAT =
            DateTimeFormatter.ofPattern(
                    "dd-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH);

    private String formatInternalDate(OffsetDateTime date) {
        return date.format(INTERNAL_DATE_FORMAT);
    }

    private Set<String> parseFetchItems(String str) {
        Set<String> items = new LinkedHashSet<String>();
        str = str.trim();

        String upper = str.toUpperCase(Locale.ENGLISH);
        if (upper.equals("ALL")) {
            items.add("FLAGS");
            items.add("INTERNALDATE");
            items.add("RFC822.SIZE");
            items.add("ENVELOPE");
            return items;
        } else if (upper.equals("FAST")) {
            items.add("FLAGS");
            items.add("INTERNALDATE");
            items.add("RFC822.SIZE");
            return items;
        } else if (upper.equals("FULL")) {
            items.add("FLAGS");
            items.add("INTERNALDATE");
            items.add("RFC822.SIZE");
            items.add("ENVELOPE");
            items.add("BODY");
            return items;
        }

        if (str.startsWith("(") && str.endsWith(")")) {
            str = str.substring(1, str.length() - 1).trim();
        }

        int i = 0;
        while (i < str.length()) {
            while (i < str.length() && str.charAt(i) == ' ') {
                i++;
            }
            if (i >= str.length()) {
                break;
            }

            int start = i;
            String remaining = str.substring(i)
                    .toUpperCase(Locale.ENGLISH);
            if (remaining.startsWith("BODY[")
                    || remaining.startsWith("BODY.PEEK[")) {
                int bracketStart = str.indexOf('[', i);
                int depth = 1;
                int j = bracketStart + 1;
                while (j < str.length() && depth > 0) {
                    char c = str.charAt(j);
                    if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                    }
                    j++;
                }
                if (j < str.length() && str.charAt(j) == '<') {
                    int gt = str.indexOf('>', j);
                    if (gt > 0) {
                        j = gt + 1;
                    }
                }
                items.add(str.substring(start, j));
                i = j;
            } else {
                while (i < str.length() && str.charAt(i) != ' ') {
                    i++;
                }
                items.add(str.substring(start, i));
            }
        }

        return items;
    }

    private boolean fetchNeedsSeen(Set<String> fetchItems) {
        for (String item : fetchItems) {
            String upper = item.toUpperCase(Locale.ENGLISH);
            if (upper.equals("RFC822")
                    || upper.equals("RFC822.TEXT")
                    || (upper.startsWith("BODY[")
                        && !upper.startsWith("BODY.PEEK["))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFetchItem(Set<String> fetchItems,
            String target) {
        String targetUpper = target.toUpperCase(Locale.ENGLISH);
        for (String item : fetchItems) {
            if (item.toUpperCase(Locale.ENGLISH).equals(targetUpper)) {
                return true;
            }
        }
        return false;
    }

    private byte[] readMessageContent(Mailbox mailbox, int msgNum)
            throws IOException {
        ReadableByteChannel channel = mailbox.getMessageContent(msgNum);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            while (channel.read(buf) != -1) {
                buf.flip();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                bos.write(data);
                buf.clear();
            }
            return bos.toByteArray();
        } finally {
            channel.close();
        }
    }

    private byte[] extractHeaders(byte[] message) {
        for (int i = 0; i < message.length - 3; i++) {
            if (message[i] == '\r' && message[i + 1] == '\n'
                    && message[i + 2] == '\r'
                    && message[i + 3] == '\n') {
                byte[] headers = new byte[i + 4];
                System.arraycopy(message, 0, headers, 0, i + 4);
                return headers;
            }
        }
        return message;
    }

    private byte[] extractBody(byte[] message) {
        for (int i = 0; i < message.length - 3; i++) {
            if (message[i] == '\r' && message[i + 1] == '\n'
                    && message[i + 2] == '\r'
                    && message[i + 3] == '\n') {
                int bodyStart = i + 4;
                byte[] body = new byte[message.length - bodyStart];
                System.arraycopy(message, bodyStart, body, 0,
                        body.length);
                return body;
            }
        }
        return new byte[0];
    }

    private byte[] extractHeaderFields(byte[] message,
            Set<String> fieldNames) {
        byte[] headerBlock = extractHeaders(message);
        String headerStr = new String(headerBlock, US_ASCII);
        StringBuilder result = new StringBuilder();

        int lineStart = 0;
        boolean includeThisHeader = false;
        while (lineStart < headerStr.length()) {
            int lineEnd = headerStr.indexOf("\r\n", lineStart);
            if (lineEnd < 0) {
                lineEnd = headerStr.length();
            }
            String line = headerStr.substring(lineStart, lineEnd);
            if (line.isEmpty()) {
                break;
            }

            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (includeThisHeader) {
                    result.append(line).append("\r\n");
                }
            } else {
                int colonIdx = line.indexOf(':');
                includeThisHeader = false;
                if (colonIdx > 0) {
                    String fieldName = line.substring(0, colonIdx)
                            .trim();
                    for (String name : fieldNames) {
                        if (name.equalsIgnoreCase(fieldName)) {
                            includeThisHeader = true;
                            break;
                        }
                    }
                }
                if (includeThisHeader) {
                    result.append(line).append("\r\n");
                }
            }
            lineStart = lineEnd + 2;
        }
        result.append("\r\n");
        return result.toString().getBytes(US_ASCII);
    }

    private byte[] extractHeaderFieldsNot(byte[] message,
            Set<String> fieldNames) {
        byte[] headerBlock = extractHeaders(message);
        String headerStr = new String(headerBlock, US_ASCII);
        StringBuilder result = new StringBuilder();

        int lineStart = 0;
        boolean includeThisHeader = true;
        while (lineStart < headerStr.length()) {
            int lineEnd = headerStr.indexOf("\r\n", lineStart);
            if (lineEnd < 0) {
                lineEnd = headerStr.length();
            }
            String line = headerStr.substring(lineStart, lineEnd);
            if (line.isEmpty()) {
                break;
            }

            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (includeThisHeader) {
                    result.append(line).append("\r\n");
                }
            } else {
                int colonIdx = line.indexOf(':');
                includeThisHeader = true;
                if (colonIdx > 0) {
                    String fieldName = line.substring(0, colonIdx)
                            .trim();
                    for (String name : fieldNames) {
                        if (name.equalsIgnoreCase(fieldName)) {
                            includeThisHeader = false;
                            break;
                        }
                    }
                }
                if (includeThisHeader) {
                    result.append(line).append("\r\n");
                }
            }
            lineStart = lineEnd + 2;
        }
        result.append("\r\n");
        return result.toString().getBytes(US_ASCII);
    }

    private Set<String> parseHeaderFieldNames(String spec) {
        Set<String> names = new LinkedHashSet<String>();
        spec = spec.trim();
        if (spec.startsWith("(") && spec.endsWith(")")) {
            spec = spec.substring(1, spec.length() - 1);
        }
        StringTokenizer tok = new StringTokenizer(spec);
        while (tok.hasMoreTokens()) {
            names.add(tok.nextToken());
        }
        return names;
    }

    private Set<Flag> parseFlagList(String str) {
        str = str.trim();
        if (str.startsWith("(") && str.endsWith(")")) {
            str = str.substring(1, str.length() - 1).trim();
        }
        if (str.isEmpty()) {
            return EnumSet.noneOf(Flag.class);
        }
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        StringTokenizer tok = new StringTokenizer(str);
        while (tok.hasMoreTokens()) {
            String token = tok.nextToken();
            Flag flag = Flag.fromImapAtom(token);
            if (flag != null) {
                flags.add(flag);
            }
        }
        return flags;
    }

    private String formatEnvelopeFromDescriptor(
            IMAPMessageDescriptor.Envelope env) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        appendNilOrQuoted(sb, env.getDate() != null
                ? formatInternalDate(env.getDate()) : null);
        sb.append(' ');
        appendNilOrQuoted(sb, env.getSubject());
        sb.append(' ');
        appendAddressList(sb, env.getFrom());
        sb.append(' ');
        appendAddressList(sb, env.getSender());
        sb.append(' ');
        appendAddressList(sb, env.getReplyTo());
        sb.append(' ');
        appendAddressList(sb, env.getTo());
        sb.append(' ');
        appendAddressList(sb, env.getCc());
        sb.append(' ');
        appendAddressList(sb, env.getBcc());
        sb.append(' ');
        appendNilOrQuoted(sb, env.getInReplyTo());
        sb.append(' ');
        appendNilOrQuoted(sb, env.getMessageId());
        sb.append(')');
        return sb.toString();
    }

    private String formatEnvelopeFromHeaders(String headers) {
        String date = extractHeaderValue(headers, "Date");
        String subject = extractHeaderValue(headers, "Subject");
        String from = extractHeaderValue(headers, "From");
        String sender = extractHeaderValue(headers, "Sender");
        String replyTo = extractHeaderValue(headers, "Reply-To");
        String to = extractHeaderValue(headers, "To");
        String cc = extractHeaderValue(headers, "Cc");
        String bcc = extractHeaderValue(headers, "Bcc");
        String inReplyTo = extractHeaderValue(headers, "In-Reply-To");
        String messageId = extractHeaderValue(headers, "Message-Id");

        if (sender == null) {
            sender = from;
        }
        if (replyTo == null) {
            replyTo = from;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('(');
        appendNilOrQuoted(sb, date);
        sb.append(' ');
        appendNilOrQuoted(sb, subject);
        sb.append(' ');
        appendAddressFromHeader(sb, from);
        sb.append(' ');
        appendAddressFromHeader(sb, sender);
        sb.append(' ');
        appendAddressFromHeader(sb, replyTo);
        sb.append(' ');
        appendAddressFromHeader(sb, to);
        sb.append(' ');
        appendAddressFromHeader(sb, cc);
        sb.append(' ');
        appendAddressFromHeader(sb, bcc);
        sb.append(' ');
        appendNilOrQuoted(sb, inReplyTo);
        sb.append(' ');
        appendNilOrQuoted(sb, messageId);
        sb.append(')');
        return sb.toString();
    }

    private String extractHeaderValue(String headers, String name) {
        String nameLower = name.toLowerCase(Locale.ENGLISH);
        int lineStart = 0;
        while (lineStart < headers.length()) {
            int lineEnd = headers.indexOf("\r\n", lineStart);
            if (lineEnd < 0) {
                lineEnd = headers.length();
            }
            String line = headers.substring(lineStart, lineEnd);
            if (line.isEmpty()) {
                break;
            }
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String fieldName = line.substring(0, colonIdx).trim()
                        .toLowerCase(Locale.ENGLISH);
                if (fieldName.equals(nameLower)) {
                    StringBuilder value = new StringBuilder(
                            line.substring(colonIdx + 1).trim());
                    int nextLine = lineEnd + 2;
                    while (nextLine < headers.length()) {
                        int nextEnd = headers.indexOf("\r\n",
                                nextLine);
                        if (nextEnd < 0) {
                            nextEnd = headers.length();
                        }
                        String cont = headers.substring(
                                nextLine, nextEnd);
                        if (cont.isEmpty()
                                || (cont.charAt(0) != ' '
                                    && cont.charAt(0) != '\t')) {
                            break;
                        }
                        value.append(' ').append(cont.trim());
                        nextLine = nextEnd + 2;
                    }
                    return value.toString();
                }
            }
            lineStart = lineEnd + 2;
        }
        return null;
    }

    private void appendNilOrQuoted(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("NIL");
        } else {
            sb.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            sb.append('"');
        }
    }

    private void appendAddressList(StringBuilder sb,
            IMAPMessageDescriptor.Address[] addrs) {
        if (addrs == null || addrs.length == 0) {
            sb.append("NIL");
            return;
        }
        sb.append('(');
        for (int i = 0; i < addrs.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append('(');
            appendNilOrQuoted(sb, addrs[i].getName());
            sb.append(' ');
            appendNilOrQuoted(sb, addrs[i].getRoute());
            sb.append(' ');
            appendNilOrQuoted(sb, addrs[i].getMailbox());
            sb.append(' ');
            appendNilOrQuoted(sb, addrs[i].getHost());
            sb.append(')');
        }
        sb.append(')');
    }

    private void appendAddressFromHeader(StringBuilder sb,
            String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            sb.append("NIL");
            return;
        }

        sb.append("((");
        int angleOpen = headerValue.indexOf('<');
        if (angleOpen >= 0) {
            int angleClose = headerValue.indexOf('>', angleOpen);
            String displayName = headerValue.substring(0, angleOpen)
                    .trim();
            if (displayName.startsWith("\"")
                    && displayName.endsWith("\"")) {
                displayName = displayName.substring(1,
                        displayName.length() - 1);
            }
            String addr = (angleClose > angleOpen + 1)
                    ? headerValue.substring(angleOpen + 1, angleClose)
                    : "";
            int atIdx = addr.indexOf('@');
            String mailbox = (atIdx > 0)
                    ? addr.substring(0, atIdx) : addr;
            String host = (atIdx > 0)
                    ? addr.substring(atIdx + 1) : "";
            appendNilOrQuoted(sb,
                    displayName.isEmpty() ? null : displayName);
            sb.append(" NIL ");
            appendNilOrQuoted(sb, mailbox);
            sb.append(' ');
            appendNilOrQuoted(sb, host);
        } else {
            int atIdx = headerValue.indexOf('@');
            String mailbox = (atIdx > 0)
                    ? headerValue.substring(0, atIdx).trim()
                    : headerValue.trim();
            String host = (atIdx > 0)
                    ? headerValue.substring(atIdx + 1).trim() : "";
            sb.append("NIL NIL ");
            appendNilOrQuoted(sb, mailbox);
            sb.append(' ');
            appendNilOrQuoted(sb, host);
        }
        sb.append("))");
    }

    private String formatBodyStructureFromDescriptor(
            IMAPMessageDescriptor.BodyStructure bs,
            boolean extensible) {
        StringBuilder sb = new StringBuilder();
        formatBodyStructurePart(sb, bs, extensible);
        return sb.toString();
    }

    private void formatBodyStructurePart(StringBuilder sb,
            IMAPMessageDescriptor.BodyStructure bs,
            boolean extensible) {
        IMAPMessageDescriptor.BodyStructure[] parts = bs.getParts();
        if (parts != null && parts.length > 0) {
            sb.append('(');
            for (int i = 0; i < parts.length; i++) {
                formatBodyStructurePart(sb, parts[i], extensible);
            }
            sb.append(' ');
            appendNilOrQuoted(sb, bs.getSubtype());
            if (extensible) {
                sb.append(' ');
                formatBodyParams(sb, bs.getParameters());
                sb.append(" NIL NIL");
            }
            sb.append(')');
        } else {
            sb.append('(');
            appendNilOrQuoted(sb, bs.getType());
            sb.append(' ');
            appendNilOrQuoted(sb, bs.getSubtype());
            sb.append(' ');
            formatBodyParams(sb, bs.getParameters());
            sb.append(' ');
            appendNilOrQuoted(sb, bs.getContentId());
            sb.append(' ');
            appendNilOrQuoted(sb, bs.getDescription());
            sb.append(' ');
            appendNilOrQuoted(sb,
                    bs.getEncoding() != null
                            ? bs.getEncoding() : "7BIT");
            sb.append(' ').append(bs.getSize());

            if ("text".equalsIgnoreCase(bs.getType())) {
                sb.append(' ').append(bs.getLines());
            }

            if (extensible) {
                sb.append(' ');
                appendNilOrQuoted(sb, bs.getMd5());
                sb.append(' ');
                if (bs.getDisposition() != null) {
                    sb.append('(');
                    appendNilOrQuoted(sb, bs.getDisposition());
                    sb.append(' ');
                    formatBodyParams(sb,
                            bs.getDispositionParameters());
                    sb.append(')');
                } else {
                    sb.append("NIL");
                }
                sb.append(' ');
                String[] lang = bs.getLanguage();
                if (lang != null && lang.length > 0) {
                    if (lang.length == 1) {
                        appendNilOrQuoted(sb, lang[0]);
                    } else {
                        sb.append('(');
                        for (int i = 0; i < lang.length; i++) {
                            if (i > 0) {
                                sb.append(' ');
                            }
                            appendNilOrQuoted(sb, lang[i]);
                        }
                        sb.append(')');
                    }
                } else {
                    sb.append("NIL");
                }
                sb.append(' ');
                appendNilOrQuoted(sb, bs.getLocation());
            }
            sb.append(')');
        }
    }

    private void formatBodyParams(StringBuilder sb,
            Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            sb.append("NIL");
            return;
        }
        sb.append('(');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            first = false;
            appendNilOrQuoted(sb, entry.getKey());
            sb.append(' ');
            appendNilOrQuoted(sb, entry.getValue());
        }
        sb.append(')');
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
        name = stripControlChars(name);
        if (name.contains(" ") || name.contains("\"") || name.contains("\\")) {
            return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"";
        }
        return name;
    }

    private static String stripControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                StringBuilder sb = new StringBuilder(s.length());
                sb.append(s, 0, i);
                for (int j = i + 1; j < s.length(); j++) {
                    c = s.charAt(j);
                    if (c >= 0x20 && c != 0x7f) {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
        }
        return s;
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

    private class FetchStateImpl implements FetchState {
        private final String tag;
        private final MessageSet seqSet;
        private final Set<String> fetchItems;
        private final boolean uid;

        FetchStateImpl(String tag, MessageSet seqSet,
                Set<String> fetchItems, boolean uid) {
            this.tag = tag;
            this.seqSet = seqSet;
            this.fetchItems = fetchItems;
            this.uid = uid;
        }

        @Override
        public void proceed(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                executeFetch(tag, selectedMailbox, seqSet,
                        fetchItems, uid);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to execute FETCH", e);
                try {
                    sendTaggedNo(tag,
                            L10N.getString("imap.err.internal_error"));
                } catch (IOException e2) {
                    LOGGER.log(Level.WARNING,
                            "Failed to send FETCH error", e2);
                }
            }
        }

        @Override
        public void deny(String message, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send FETCH denied", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class StoreStateImpl implements StoreState {
        private final String tag;

        StoreStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void flagsUpdated(int sequenceNumber, Set<Flag> flags) {
            try {
                sendUntagged(sequenceNumber + " FETCH (FLAGS ("
                        + formatFlags(flags) + "))");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STORE FETCH response", e);
            }
        }

        @Override
        public void storeComplete(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.store_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STORE OK", e);
            }
        }

        @Override
        public void storeFailed(String message,
                SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STORE NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class CopyStateImpl implements CopyState {
        private final String tag;

        CopyStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void copied(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.copy_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send COPY OK", e);
            }
        }

        @Override
        public void copiedWithUid(long uidValidity, String sourceUids,
                String destUids, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedOk(tag, "[COPYUID " + uidValidity + " "
                        + sourceUids + " " + destUids + "] "
                        + L10N.getString("imap.copy_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send COPY OK", e);
            }
        }

        @Override
        public void mailboxNotFound(String message,
                SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, "[TRYCREATE] " + message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send COPY NO", e);
            }
        }

        @Override
        public void copyFailed(String message,
                SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send COPY NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class MoveStateImpl implements MoveState {
        private final String tag;

        MoveStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void messageExpunged(int sequenceNumber) {
            try {
                sendUntagged(sequenceNumber + " EXPUNGE");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send MOVE EXPUNGE", e);
            }
        }

        @Override
        public void moved(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.move_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send MOVE OK", e);
            }
        }

        @Override
        public void movedWithUid(long uidValidity, String sourceUids,
                String destUids, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedOk(tag, "[COPYUID " + uidValidity + " "
                        + sourceUids + " " + destUids + "] "
                        + L10N.getString("imap.move_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send MOVE OK", e);
            }
        }

        @Override
        public void mailboxNotFound(String message,
                SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, "[TRYCREATE] " + message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send MOVE NO", e);
            }
        }

        @Override
        public void moveFailed(String message,
                SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send MOVE NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class CloseStateImpl implements CloseState {
        private final String tag;
        private final boolean expunge;

        CloseStateImpl(String tag, boolean expunge) {
            this.tag = tag;
            this.expunge = expunge;
        }

        @Override
        public void closed(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            selectedHandler = null;
            selectedMailbox = null;
            selectedReadOnly = false;
            state = IMAPState.AUTHENTICATED;
            try {
                String key = expunge ? "imap.close_complete"
                        : "imap.unselect_complete";
                sendTaggedOk(tag, L10N.getString(key));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send CLOSE/UNSELECT OK", e);
            }
        }

        @Override
        public void closeFailed(String message, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send CLOSE/UNSELECT NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class ExpungeStateImpl implements ExpungeState {
        private final String tag;

        ExpungeStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void messageExpunged(int sequenceNumber) {
            try {
                sendUntagged(sequenceNumber + " EXPUNGE");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send EXPUNGE notification", e);
            }
        }

        @Override
        public void expungeComplete(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.expunge_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send EXPUNGE OK", e);
            }
        }

        @Override
        public void expungeFailed(String message, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send EXPUNGE NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class SearchStateImpl implements SearchState {
        private final String tag;
        private final boolean uid;
        private SearchCriteria criteria;

        SearchStateImpl(String tag, boolean uid) {
            this.tag = tag;
            this.uid = uid;
        }

        void setCriteria(SearchCriteria criteria) {
            this.criteria = criteria;
        }

        @Override
        public void proceed(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                executeSearchWithCriteria(tag, criteria, uid);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to execute SEARCH", e);
                try {
                    sendTaggedNo(tag,
                            L10N.getString("imap.err.internal_error"));
                } catch (IOException e2) {
                    LOGGER.log(Level.WARNING,
                            "Failed to send SEARCH error", e2);
                }
            }
        }

        @Override
        public void deny(String message, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send SEARCH denied", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class CreateStateImpl implements CreateState {
        private final String tag;

        CreateStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void created(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.create_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send CREATE OK", e);
            }
        }

        @Override
        public void created(String message, AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedOk(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send CREATE OK", e);
            }
        }

        @Override
        public void alreadyExists(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send CREATE NO", e);
            }
        }

        @Override
        public void cannotCreate(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send CREATE NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class DeleteStateImpl implements DeleteState {
        private final String tag;

        DeleteStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void deleted(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.delete_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send DELETE OK", e);
            }
        }

        @Override
        public void mailboxNotFound(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send DELETE NO", e);
            }
        }

        @Override
        public void cannotDelete(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send DELETE NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class RenameStateImpl implements RenameState {
        private final String tag;

        RenameStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void renamed(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.rename_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send RENAME OK", e);
            }
        }

        @Override
        public void mailboxNotFound(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send RENAME NO", e);
            }
        }

        @Override
        public void targetExists(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send RENAME NO", e);
            }
        }

        @Override
        public void cannotRename(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send RENAME NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class SubscribeStateImpl implements SubscribeState {
        private final String tag;

        SubscribeStateImpl(String tag) {
            this.tag = tag;
        }

        @Override
        public void subscribed(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedOk(tag,
                        L10N.getString("imap.subscribe_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send SUBSCRIBE OK", e);
            }
        }

        @Override
        public void mailboxNotFound(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send SUBSCRIBE NO", e);
            }
        }

        @Override
        public void subscribeFailed(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send SUBSCRIBE NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class ListStateImpl implements ListState {
        private final String tag;
        private final boolean lsub;

        ListStateImpl(String tag, boolean lsub) {
            this.tag = tag;
            this.lsub = lsub;
        }

        @Override
        public ListWriter beginList() {
            return new ListWriterImpl();
        }

        @Override
        public void listEntry(Set<MailboxAttribute> attributes,
                String delimiter, String name) {
            try {
                StringBuilder attrStr = new StringBuilder();
                for (MailboxAttribute attr : attributes) {
                    if (attrStr.length() > 0) {
                        attrStr.append(' ');
                    }
                    attrStr.append(attr.getImapAtom());
                }
                String cmd = lsub ? "LSUB" : "LIST";
                String quotedName = quoteMailboxName(name);
                String response = cmd + " (" + attrStr + ") \""
                        + delimiter + "\" " + quotedName;
                sendUntagged(response);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST/LSUB entry", e);
            }
        }

        @Override
        public void listComplete(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                String key = lsub ? "imap.lsub_complete"
                        : "imap.list_complete";
                sendTaggedOk(tag, L10N.getString(key));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST/LSUB OK", e);
            }
        }

        @Override
        public void listFailed(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send LIST/LSUB NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }

        private class ListWriterImpl implements ListWriter {

            @Override
            public void mailbox(Set<MailboxAttribute> attributes,
                    String delimiter, String name) {
                listEntry(attributes, delimiter, name);
            }

            @Override
            public void end(AuthenticatedHandler handler) {
                listComplete(handler);
            }
        }
    }

    private class AuthenticatedStatusStateImpl
            implements AuthenticatedStatusState {
        private final String tag;
        private final String mailboxName;
        private final String[] attrs;

        AuthenticatedStatusStateImpl(String tag, String mailboxName,
                String[] attrs) {
            this.tag = tag;
            this.mailboxName = mailboxName;
            this.attrs = attrs;
        }

        @Override
        public void proceed(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                executeStatus(tag, mailboxName, attrs);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to execute STATUS", e);
            }
        }

        @Override
        public void deny(String message, AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STATUS NO", e);
            }
        }

        @Override
        public void mailboxNotFound(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag,
                        L10N.getString("imap.err.status_failed"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STATUS NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class SelectedStatusStateImpl
            implements SelectedStatusState {
        private final String tag;
        private final String mailboxName;
        private final String[] attrs;

        SelectedStatusStateImpl(String tag, String mailboxName,
                String[] attrs) {
            this.tag = tag;
            this.mailboxName = mailboxName;
            this.attrs = attrs;
        }

        @Override
        public void proceed(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                executeStatus(tag, mailboxName, attrs);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to execute STATUS", e);
            }
        }

        @Override
        public void deny(String message, SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STATUS NO", e);
            }
        }

        @Override
        public void mailboxNotFound(SelectedHandler handler) {
            selectedHandler = handler;
            try {
                sendTaggedNo(tag,
                        L10N.getString("imap.err.status_failed"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send STATUS NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class AppendStateImpl implements AppendState {
        private final String tag;
        private final long literalSize;
        private final boolean nonSync;
        private final String mailboxName;
        private final Set<Flag> flags;
        private final OffsetDateTime internalDate;

        AppendStateImpl(String tag, long literalSize, boolean nonSync,
                String mailboxName, Set<Flag> flags,
                OffsetDateTime internalDate) {
            this.tag = tag;
            this.literalSize = literalSize;
            this.nonSync = nonSync;
            this.mailboxName = mailboxName;
            this.flags = flags;
            this.internalDate = internalDate;
        }

        @Override
        public void readyForData(Mailbox mailbox,
                AppendDataHandler handler) {
            try {
                mailbox.startAppendMessage(flags, internalDate);
                appendMailbox = mailbox;
                appendDataHandler = handler;
                appendTag = tag;
                appendLiteralRemaining = literalSize;
                appendMailboxName = mailboxName;
                appendMessageSize = literalSize;

                addSessionEvent("APPEND_START");
                addSessionAttribute("imap.append.mailbox", mailboxName);
                addSessionAttribute("imap.append.size", literalSize);

                if (!nonSync) {
                    sendContinuation(
                            L10N.getString("imap.ready_for_literal"));
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to start APPEND to " + mailboxName, e);
                try {
                    sendTaggedNo(tag, "[TRYCREATE] "
                            + L10N.getString("imap.err.append_failed"));
                } catch (IOException e2) {
                    LOGGER.log(Level.WARNING,
                            "Failed to send APPEND error", e2);
                }
            }
        }

        @Override
        public void tryCreate(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, "[TRYCREATE] "
                        + L10N.getString("imap.err.append_failed"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send APPEND TRYCREATE", e);
            }
        }

        @Override
        public void appendFailed(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send APPEND NO", e);
            }
        }

        @Override
        public void acceptLiteral(int size, AppendDataHandler handler) {
            readyForData(appendMailbox, handler);
        }

        @Override
        public void mailboxNotFound(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, "[TRYCREATE] " + message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send APPEND NO", e);
            }
        }

        @Override
        public void cannotAppend(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send APPEND NO", e);
            }
        }

        @Override
        public void messageTooLarge(long maxSize,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, "[TOOBIG] "
                        + L10N.getString("imap.err.literal_too_large"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send APPEND NO", e);
            }
        }

        @Override
        public void serverShuttingDown() {
            doServerShuttingDown();
        }
    }

    private class QuotaStateImpl implements QuotaState {
        enum Command { GET_QUOTA, GET_QUOTA_ROOT, SET_QUOTA }

        private final String tag;
        private final Command command;
        private final String quotaRootOrMailbox;
        private final String targetUser;
        private final long storageLimit;
        private final long messageLimit;

        QuotaStateImpl(String tag, Command command,
                String quotaRootOrMailbox, String targetUser) {
            this(tag, command, quotaRootOrMailbox, targetUser, -1, -1);
        }

        QuotaStateImpl(String tag, Command command,
                String quotaRootOrMailbox, String targetUser,
                long storageLimit, long messageLimit) {
            this.tag = tag;
            this.command = command;
            this.quotaRootOrMailbox = quotaRootOrMailbox;
            this.targetUser = targetUser;
            this.storageLimit = storageLimit;
            this.messageLimit = messageLimit;
        }

        @Override
        public void proceed(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                QuotaManager qm = server.getQuotaManager();
                switch (command) {
                    case GET_QUOTA: {
                        Quota quota = qm.getQuota(targetUser);
                        sendQuotaResponse(quotaRootOrMailbox, quota);
                        sendTaggedOk(tag,
                                L10N.getString("imap.quota_complete"));
                        break;
                    }
                    case GET_QUOTA_ROOT: {
                        String root = "";
                        String quotedMailbox =
                                quoteString(quotaRootOrMailbox);
                        String quotedRoot = quoteString(root);
                        sendUntagged("QUOTAROOT " + quotedMailbox
                                + " " + quotedRoot);
                        Quota quota = qm.getQuota(targetUser);
                        sendQuotaResponse(root, quota);
                        sendTaggedOk(tag,
                                L10N.getString("imap.quotaroot_complete"));
                        break;
                    }
                    case SET_QUOTA: {
                        qm.setUserQuota(targetUser, storageLimit,
                                messageLimit);
                        Quota quota = qm.getQuota(targetUser);
                        sendQuotaResponse(quotaRootOrMailbox, quota);
                        sendTaggedOk(tag,
                                L10N.getString("imap.setquota_complete"));
                        break;
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to execute quota command", e);
                try {
                    sendTaggedNo(tag,
                            L10N.getString("imap.err.quota_failed"));
                } catch (IOException e2) {
                    LOGGER.log(Level.WARNING,
                            "Failed to send quota error", e2);
                }
            }
        }

        @Override
        public void quotaNotSupported(AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag,
                        L10N.getString("imap.err.quota_not_supported"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send quota not supported", e);
            }
        }

        @Override
        public void sendQuota(String quotaRoot,
                Map<String, long[]> resources,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                StringBuilder sb = new StringBuilder();
                String quotedRoot = quoteString(quotaRoot);
                sb.append("QUOTA ");
                sb.append(quotedRoot);
                sb.append(" (");
                boolean first = true;
                for (Map.Entry<String, long[]> entry
                        : resources.entrySet()) {
                    if (!first) {
                        sb.append(' ');
                    }
                    first = false;
                    String key = entry.getKey();
                    long[] vals = entry.getValue();
                    sb.append(key);
                    sb.append(' ');
                    sb.append(vals[0]);
                    sb.append(' ');
                    sb.append(vals[1]);
                }
                sb.append(')');
                String response = sb.toString();
                sendUntagged(response);
                sendTaggedOk(tag,
                        L10N.getString("imap.quota_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send QUOTA response", e);
            }
        }

        @Override
        public void sendQuotaRoots(String mailboxName,
                List<String> quotaRoots,
                Map<String, Map<String, long[]>> quotas,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                StringBuilder rootsLine = new StringBuilder();
                String quotedMailbox = quoteString(mailboxName);
                rootsLine.append("QUOTAROOT ");
                rootsLine.append(quotedMailbox);
                for (String root : quotaRoots) {
                    String quotedRoot = quoteString(root);
                    rootsLine.append(' ');
                    rootsLine.append(quotedRoot);
                }
                String rootsResponse = rootsLine.toString();
                sendUntagged(rootsResponse);

                for (Map.Entry<String, Map<String, long[]>> entry
                        : quotas.entrySet()) {
                    StringBuilder sb = new StringBuilder();
                    String quotedKey = quoteString(entry.getKey());
                    sb.append("QUOTA ");
                    sb.append(quotedKey);
                    sb.append(" (");
                    boolean first = true;
                    Map<String, long[]> resMap = entry.getValue();
                    for (Map.Entry<String, long[]> res
                            : resMap.entrySet()) {
                        if (!first) {
                            sb.append(' ');
                        }
                        first = false;
                        String resKey = res.getKey();
                        long[] vals = res.getValue();
                        sb.append(resKey);
                        sb.append(' ');
                        sb.append(vals[0]);
                        sb.append(' ');
                        sb.append(vals[1]);
                    }
                    sb.append(')');
                    String quotaResponse = sb.toString();
                    sendUntagged(quotaResponse);
                }
                sendTaggedOk(tag,
                        L10N.getString("imap.quotaroot_complete"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send QUOTAROOT response", e);
            }
        }

        @Override
        public void quotaFailed(String message,
                AuthenticatedHandler handler) {
            authenticatedHandler = handler;
            try {
                sendTaggedNo(tag, message);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to send quota NO", e);
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
