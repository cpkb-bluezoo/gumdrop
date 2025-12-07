/*
 * IMAPConnection.java
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

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Realm;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.quota.QuotaPolicy;
import org.bluezoo.gumdrop.sasl.SASLMechanism;
import org.bluezoo.gumdrop.sasl.SASLUtils;
import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.StringTokenizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for the IMAP4rev2 protocol.
 * This manages one IMAP session over a TCP connection, handling command
 * parsing, authentication, and mailbox operations according to RFC 9051.
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
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 - IMAP4rev2</a>
 */
public class IMAPConnection extends Connection {

    private static final Logger LOGGER = Logger.getLogger(IMAPConnection.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.imap.L10N");

    static final Charset US_ASCII = StandardCharsets.US_ASCII;
    static final Charset UTF_8 = StandardCharsets.UTF_8;
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    private static final String CRLF = "\r\n";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * IMAP session states according to RFC 9051.
     */
    enum IMAPState {
        NOT_AUTHENTICATED,  // Initial state, must authenticate
        AUTHENTICATED,      // Logged in, can list/select mailboxes
        SELECTED,          // Mailbox selected, can access messages
        LOGOUT             // Closing connection
    }

    /**
     * SASL authentication states.
     */
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

    // Server reference
    private final IMAPServer server;

    // Session state
    private IMAPState state = IMAPState.NOT_AUTHENTICATED;
    private String authenticatedUser = null;
    
    // Mailbox state
    private MailboxStore store = null;
    private Mailbox selectedMailbox = null;
    private boolean selectedReadOnly = false;

    // Command parsing
    private StringBuilder lineBuffer = new StringBuilder();
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

    // APPEND literal state
    private String appendTag = null;
    private Mailbox appendMailbox = null;
    private long appendLiteralRemaining = 0;
    private String appendMailboxName = null;  // For telemetry
    private long appendMessageSize = 0;       // For telemetry

    // Telemetry
    private Span sessionSpan;           // Session span (entire connection)
    private Span authenticatedSpan;     // Authenticated state span

    /**
     * Creates a new IMAP connection.
     * 
     * @param server the parent IMAP server
     * @param engine the SSL engine (null if not using SSL)
     * @param secure true if using implicit TLS
     */
    public IMAPConnection(IMAPServer server, SSLEngine engine, boolean secure) {
        super(engine, secure);
        this.server = server;
    }

    /**
     * Initializes the connection and sends the IMAP greeting.
     * Called after the connection is created but before registration with selector.
     */
    @Override
    public void init() throws IOException {
        super.init();

        // Initialize telemetry trace for this connection
        initConnectionTrace();
        startSessionSpan();
        
        // Send IMAP greeting (RFC 9051 Section 7.1)
        String caps = server.getCapabilities(false, secure);
        sendUntagged("OK [CAPABILITY " + caps + "] " + L10N.getString("imap.greeting"));
    }

    // ========================================================================
    // Telemetry Methods
    // ========================================================================

    /**
     * Initializes the connection-level telemetry trace.
     */
    private void initConnectionTrace() {
        if (!isTelemetryEnabled()) {
            return;
        }

        TelemetryConfig telemetryConfig = getTelemetryConfig();
        String spanName = L10N.getString("telemetry.imap_connection");
        Trace trace = telemetryConfig.createTrace(spanName, SpanKind.SERVER);
        setTrace(trace);

        if (trace != null) {
            Span rootSpan = trace.getRootSpan();
            // Add connection-level attributes
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip", getRemoteSocketAddress().toString());
            rootSpan.addAttribute("rpc.system", "imap");
        }
    }

    /**
     * Starts the session span (covers the entire IMAP session).
     */
    private void startSessionSpan() {
        Trace trace = getTrace();
        if (trace == null) {
            return;
        }

        String spanName = L10N.getString("telemetry.imap_session");
        sessionSpan = trace.startSpan(spanName, SpanKind.SERVER);
    }

    /**
     * Starts the authenticated span when user successfully authenticates.
     *
     * @param username the authenticated username
     * @param mechanism the authentication mechanism used
     */
    private void startAuthenticatedSpan(String username, String mechanism) {
        Trace trace = getTrace();
        if (trace == null) {
            return;
        }

        String spanName = L10N.getString("telemetry.imap_authenticated");
        authenticatedSpan = trace.startSpan(spanName, SpanKind.INTERNAL);

        if (authenticatedSpan != null) {
            authenticatedSpan.addAttribute("enduser.id", username);
            authenticatedSpan.addAttribute("imap.auth.mechanism", mechanism);
        }

        // Also add to session span
        addSessionAttribute("enduser.id", username);
        addSessionAttribute("imap.auth.mechanism", mechanism);
        addSessionEvent("AUTHENTICATED");
    }

    /**
     * Ends the session span normally.
     *
     * @param message completion message
     */
    private void endSessionSpan(String message) {
        // End authenticated span first if still open
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

    /**
     * Ends the session span with error status.
     *
     * @param message error message
     */
    private void endSessionSpanError(String message) {
        // End authenticated span first if still open
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

    /**
     * Adds a string attribute to the session span.
     */
    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds a long attribute to the session span.
     */
    private void addSessionAttribute(String key, long value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds a boolean attribute to the session span.
     */
    private void addSessionAttribute(String key, boolean value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an event to the session span.
     */
    private void addSessionEvent(String name) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(name);
        }
    }

    /**
     * Records an exception on the session span with automatic category detection.
     */
    private void recordSessionException(Throwable exception) {
        if (sessionSpan != null && !sessionSpan.isEnded() && exception != null) {
            sessionSpan.recordExceptionWithCategory(exception);
        }
    }

    /**
     * Records an exception with an explicit error category on the session span.
     */
    private void recordSessionException(Throwable exception, ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded() && exception != null) {
            sessionSpan.recordException(exception, category);
        }
    }

    /**
     * Records an IMAP error in telemetry with category classification.
     *
     * @param category the error category
     * @param message the error message
     */
    private void recordImapError(ErrorCategory category, String message) {
        if (sessionSpan != null && !sessionSpan.isEnded() && category != null) {
            sessionSpan.recordError(category, message);
        }
    }

    /**
     * Called when data is received from the client.
     */
    @Override
    public void receive(ByteBuffer buffer) {
        try {
            // Check if we're receiving APPEND literal data
            if (appendLiteralRemaining > 0) {
                receiveLiteralData(buffer);
                return;
            }
            
            // Decode bytes to characters
            CharBuffer chars = US_ASCII_DECODER.decode(buffer);
            
            // Accumulate in line buffer
            while (chars.hasRemaining()) {
                char c = chars.get();
                
                if (c == '\n') {
                    // End of line - process command
                    String line = lineBuffer.toString();
                    lineBuffer.setLength(0);
                    
                    // Remove trailing CR if present
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    
                    processLine(line);
                } else if (lineBuffer.length() >= server.getMaxLineLength()) {
                    // Line too long
                    sendTaggedBad(currentTag != null ? currentTag : "*", 
                        L10N.getString("imap.err.line_too_long"));
                    lineBuffer.setLength(0);
                } else {
                    lineBuffer.append(c);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error processing IMAP input", e);
        }
    }

    /**
     * Receives literal data for APPEND command.
     */
    private void receiveLiteralData(ByteBuffer buffer) throws IOException {
        int available = buffer.remaining();
        int toConsume = (int) Math.min(available, appendLiteralRemaining);
        
        if (toConsume > 0) {
            // Create a slice of the buffer with just the data we need
            ByteBuffer slice = buffer.slice();
            slice.limit(toConsume);
            
            // Pass to mailbox
            appendMailbox.appendMessageContent(slice);
            
            // Advance buffer position
            buffer.position(buffer.position() + toConsume);
            appendLiteralRemaining -= toConsume;
        }
        
        // Check if we've received all the literal data
        if (appendLiteralRemaining == 0) {
            // Complete the append
            try {
                long uid = appendMailbox.endAppendMessage();
                appendMailbox.close(false);

                // Record bytes added to quota
                QuotaManager quotaManager = server.getQuotaManager();
                if (quotaManager != null) {
                    quotaManager.recordMessageAdded(authenticatedUser, appendMessageSize);
                }

                // Track successful append in telemetry
                addSessionEvent("APPEND_COMPLETE");
                addSessionAttribute("imap.append.uid", uid);

                sendTaggedOk(appendTag, "[APPENDUID " + appendMailbox.getUidValidity() + 
                    " " + uid + "] " + L10N.getString("imap.append_complete"));
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
            
            // Process any remaining data in the buffer as normal input
            if (buffer.hasRemaining()) {
                receive(buffer);
            }
        }
    }

    /**
     * Processes a complete line of input.
     */
    private void processLine(String line) throws IOException {
        if (line.isEmpty()) {
            return;
        }
        
        // Check for IDLE continuation
        if (idling && line.equalsIgnoreCase("DONE")) {
            handleIdleDone();
            return;
        }
        
        // Check for SASL continuation
        if (authState != AuthState.NONE) {
            processSASLResponse(line);
            return;
        }
        
        // Parse command: tag SP command [SP arguments]
        int spaceIndex = line.indexOf(' ');
        if (spaceIndex <= 0) {
            sendTaggedBad("*", L10N.getString("imap.err.missing_tag"));
            return;
        }
        
        String tag = line.substring(0, spaceIndex);
        String rest = line.substring(spaceIndex + 1);
        
        // Validate tag (alphanumeric, no special chars)
        if (!isValidTag(tag)) {
            sendTaggedBad("*", L10N.getString("imap.err.invalid_tag"));
            return;
        }
        
        currentTag = tag;
        
        // Parse command and arguments
        spaceIndex = rest.indexOf(' ');
        String command;
        String arguments;
        if (spaceIndex > 0) {
            command = rest.substring(0, spaceIndex).toUpperCase(Locale.ENGLISH);
            arguments = rest.substring(spaceIndex + 1);
        } else {
            command = rest.toUpperCase(Locale.ENGLISH);
            arguments = "";
        }
        
        try {
            dispatchCommand(tag, command, arguments);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing IMAP command: " + command, e);
            sendTaggedNo(tag, L10N.getString("imap.err.internal_error"));
        }
    }

    /**
     * Validates an IMAP tag.
     */
    private boolean isValidTag(String tag) {
        if (tag.isEmpty() || tag.equals("*") || tag.equals("+")) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c <= 0x1f || c >= 0x7f || c == '(' || c == ')' || 
                c == '{' || c == ' ' || c == '%' || c == '*' ||
                c == '"' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    /**
     * Dispatches a command to the appropriate handler.
     */
    private void dispatchCommand(String tag, String command, String args) throws IOException {
        // Commands valid in any state
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
        }
        
        // State-specific commands
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

    /**
     * Dispatches commands valid in NOT_AUTHENTICATED state.
     */
    private void dispatchNotAuthenticatedCommand(String tag, String command, String args) 
            throws IOException {
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

    /**
     * Dispatches commands valid in AUTHENTICATED state.
     */
    private void dispatchAuthenticatedCommand(String tag, String command, String args) 
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
            default:
                sendTaggedBad(tag, MessageFormat.format(
                    L10N.getString("imap.err.unknown_command"), command));
        }
    }

    /**
     * Dispatches commands valid in SELECTED state.
     * Includes all AUTHENTICATED commands plus message operations.
     */
    private void dispatchSelectedCommand(String tag, String command, String args) 
            throws IOException {
        switch (command) {
            // Mailbox management (also valid in SELECTED)
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
                
            // Selected-state specific commands
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

    // ========================================================================
    // Any-State Commands
    // ========================================================================

    private void handleCapability(String tag) throws IOException {
        String caps = server.getCapabilities(state != IMAPState.NOT_AUTHENTICATED, secure);
        sendUntagged("CAPABILITY " + caps);
        sendTaggedOk(tag, L10N.getString("imap.capability_complete"));
    }

    private void handleNoop(String tag) throws IOException {
        // Send any pending notifications
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
        close();
    }

    // ========================================================================
    // NOT_AUTHENTICATED Commands
    // ========================================================================

    private void handleStartTLS(String tag) throws IOException {
        if (secure) {
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
        initializeSSLState();
        secure = true;
    }

    private void handleLogin(String tag, String args) throws IOException {
        if (!secure && !server.isAllowPlaintextLogin()) {
            // Require TLS for LOGIN unless explicitly allowed
            sendTaggedNo(tag, "[PRIVACYREQUIRED] " + L10N.getString("imap.err.login_disabled"));
            return;
        }
        
        // Parse: userid SP password
        String[] parts = parseQuotedStrings(args, 2);
        if (parts == null || parts.length < 2) {
            sendTaggedBad(tag, L10N.getString("imap.err.login_syntax"));
            return;
        }
        
        String username = parts[0];
        String password = parts[1];
        
        if (authenticateUser(username, password)) {
            openMailStore(username);
            sendTaggedOk(tag, "[CAPABILITY " + server.getCapabilities(true, secure) + "] " +
                L10N.getString("imap.login_complete"));
        } else {
            sendTaggedNo(tag, "[AUTHENTICATIONFAILED] " + L10N.getString("imap.err.auth_failed"));
        }
    }

    private void handleAuthenticate(String tag, String args) throws IOException {
        // Parse: mechanism [SP initial-response]
        String mechanism;
        String initialResponse = null;
        int spaceIndex = args.indexOf(' ');
        if (spaceIndex > 0) {
            mechanism = args.substring(0, spaceIndex).toUpperCase(Locale.ENGLISH);
            initialResponse = args.substring(spaceIndex + 1);
            // Handle "=" meaning empty initial response
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
        
        // Check TLS requirement
        if (mech.requiresTLS() && !secure) {
            sendTaggedNo(tag, "[PRIVACYREQUIRED] " + L10N.getString("imap.err.tls_required"));
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

    // ========================================================================
    // SASL Mechanism Handlers
    // ========================================================================

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
                pendingAuthUsername = SASLUtils.decodeBase64ToString(initialResponse);
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
        Realm realm = server.getRealm();
        if (realm == null) {
            sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.auth_not_configured"));
            resetAuthState();
            return;
        }
        
        try {
            InetSocketAddress addr = (InetSocketAddress) getLocalSocketAddress();
            authChallenge = SASLUtils.generateCramMD5Challenge(addr.getHostName());
            authState = AuthState.CRAM_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(authChallenge));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate CRAM-MD5 challenge", e);
            authFailed();
        }
    }

    private void handleAuthDIGESTMD5(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.digestmd5_no_initial"));
            return;
        }
        
        Realm realm = server.getRealm();
        if (realm == null) {
            sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.auth_not_configured"));
            return;
        }
        
        try {
            InetSocketAddress addr = (InetSocketAddress) getLocalSocketAddress();
            authNonce = SASLUtils.generateNonce(16);
            String challenge = SASLUtils.generateDigestMD5Challenge(addr.getHostName(), authNonce);
            authState = AuthState.DIGEST_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(challenge));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate DIGEST-MD5 challenge", e);
            authFailed();
        }
    }

    private void handleAuthSCRAM(String initialResponse) throws IOException {
        Realm realm = server.getRealm();
        if (realm == null) {
            sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.auth_not_configured"));
            return;
        }
        
        if (initialResponse != null && !initialResponse.isEmpty()) {
            processScramClientFirst(initialResponse);
        } else {
            authState = AuthState.SCRAM_INITIAL;
            sendContinuation("");
        }
    }

    private void handleAuthOAUTHBEARER(String initialResponse) throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            authState = AuthState.OAUTH_RESPONSE;
            sendContinuation("");
        } else {
            processOAuthBearerCredentials(initialResponse);
        }
    }

    private void handleAuthGSSAPI(String initialResponse) throws IOException {
        // TODO: Implement GSSAPI using javax.security.sasl
        sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.gssapi_unavailable"));
    }

    private void handleAuthEXTERNAL(String initialResponse) throws IOException {
        if (!secure) {
            sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.external_requires_tls"));
            return;
        }
        
        // TODO: Extract username from TLS client certificate
        sendTaggedNo(pendingAuthTag, L10N.getString("imap.err.external_unavailable"));
    }

    private void handleAuthNTLM(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            processNtlmType1(initialResponse);
        } else {
            authState = AuthState.NTLM_TYPE1;
            sendContinuation("");
        }
    }

    // ========================================================================
    // SASL Response Processing
    // ========================================================================

    private void processSASLResponse(String line) throws IOException {
        // Check for abort
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
            String username = parts[1]; // authcid
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
            
            Realm realm = server.getRealm();
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
        // Simplified DIGEST-MD5 - validate basic parameters
        try {
            String response = SASLUtils.decodeBase64ToString(line);
            Map<String, String> params = SASLUtils.parseDigestParams(response);
            
            String username = params.get("username");
            if (username == null) {
                authFailed();
                return;
            }
            
            Realm realm = server.getRealm();
            InetSocketAddress addr = (InetSocketAddress) getLocalSocketAddress();
            String ha1 = realm.getDigestHA1(username, addr.getHostName());
            
            if (ha1 != null) {
                openMailStore(username, "DIGEST-MD5");
                // Send rspauth for mutual auth
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
        // Simplified SCRAM - parse and validate basic structure
        try {
            String clientFirst = SASLUtils.decodeBase64ToString(line);
            
            // Parse n,,n=username,r=client-nonce
            if (!clientFirst.startsWith("n,,")) {
                authFailed();
                return;
            }
            
            String[] parts = clientFirst.substring(3).split(",");
            String username = null;
            String clientNonce = null;
            
            for (String part : parts) {
                if (part.startsWith("n=")) {
                    username = part.substring(2);
                } else if (part.startsWith("r=")) {
                    clientNonce = part.substring(2);
                }
            }
            
            if (username == null || clientNonce == null) {
                authFailed();
                return;
            }
            
            pendingAuthUsername = username;
            
            // Generate server-first-message
            String serverNonce = clientNonce + SASLUtils.generateNonce(16);
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String saltB64 = Base64.getEncoder().encodeToString(salt);
            
            authNonce = serverNonce;
            String serverFirst = SASLUtils.generateScramServerFirst(serverNonce, saltB64, scramIterations);
            authState = AuthState.SCRAM_FINAL;
            sendContinuation(SASLUtils.encodeBase64(serverFirst));
            
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processScramClientFinal(String line) throws IOException {
        // Simplified SCRAM validation using getScramCredentials
        try {
            String clientFinal = SASLUtils.decodeBase64ToString(line);
            
            Realm realm = server.getRealm();
            Realm.ScramCredentials creds = realm.getScramCredentials(pendingAuthUsername);
            
            if (creds != null) {
                openMailStore(pendingAuthUsername, "SCRAM-SHA-256");
                // Send server-final with signature (simplified - should use creds.serverKey)
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
            Map<String, String> params = SASLUtils.parseOAuthBearerCredentials(credentials);
            
            String user = params.get("user");
            String token = params.get("token");
            
            if (user == null || token == null) {
                authFailed();
                return;
            }
            
            Realm realm = server.getRealm();
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
        // Simplified NTLM - send Type 2 challenge
        try {
            byte[] type1 = SASLUtils.decodeBase64(line);
            if (type1.length < 8 || type1[0] != 'N' || type1[1] != 'T') {
                authFailed();
                return;
            }
            
            // Generate simple Type 2 challenge
            byte[] type2 = new byte[56];
            System.arraycopy("NTLMSSP\0".getBytes(US_ASCII), 0, type2, 0, 8);
            type2[8] = 0x02; // Type 2
            
            authState = AuthState.NTLM_TYPE3;
            sendContinuation(SASLUtils.encodeBase64(type2));
        } catch (Exception e) {
            authFailed();
        }
    }

    private void processNtlmType3(String line) throws IOException {
        // Simplified NTLM Type 3 validation
        try {
            byte[] type3 = SASLUtils.decodeBase64(line);
            if (type3.length < 8 || type3[0] != 'N' || type3[8] != 0x03) {
                authFailed();
                return;
            }
            
            // Extract username from Type 3 (simplified)
            // In production, properly parse the NTLM message structure
            String username = pendingAuthUsername != null ? pendingAuthUsername : "user";
            
            Realm realm = server.getRealm();
            // Check if user exists using userExists()
            // A full implementation would validate the NTLM response hash
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
        sendTaggedOk(pendingAuthTag, "[CAPABILITY " + server.getCapabilities(true, secure) + "] " +
            L10N.getString("imap.auth_complete"));
        resetAuthState();
    }

    private void authFailed() throws IOException {
        addSessionEvent("AUTH_FAILED");
        sendTaggedNo(pendingAuthTag, "[AUTHENTICATIONFAILED] " + L10N.getString("imap.err.auth_failed"));
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
        Realm realm = server.getRealm();
        if (realm == null) {
            return false;
        }
        return realm.passwordMatch(username, password);
    }

    private void openMailStore(String username) throws IOException {
        openMailStore(username, "PASSWORD");
    }

    private void openMailStore(String username, String mechanism) throws IOException {
        authenticatedUser = username;
        state = IMAPState.AUTHENTICATED;

        // Start authenticated span for telemetry
        startAuthenticatedSpan(username, mechanism);
        
        MailboxFactory factory = server.getMailboxFactory();
        if (factory != null) {
            store = factory.createStore();
            store.open(username);
        }
    }

    // ========================================================================
    // AUTHENTICATED State Commands
    // ========================================================================

    private void handleSelect(String tag, String args, boolean readOnly) throws IOException {
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null) {
            sendTaggedBad(tag, L10N.getString("imap.err.select_syntax"));
            return;
        }
        
        try {
            // Close previously selected mailbox
            if (selectedMailbox != null) {
                selectedMailbox.close(!selectedReadOnly);
                selectedMailbox = null;
            }
            
            selectedMailbox = store.openMailbox(mailboxName, readOnly);
            selectedReadOnly = readOnly;
            state = IMAPState.SELECTED;

            // Add telemetry for mailbox selection
            addSessionAttribute("imap.mailbox", mailboxName);
            addSessionAttribute("imap.mailbox.readonly", readOnly);
            addSessionEvent(readOnly ? "EXAMINE" : "SELECT");
            
            // Send mailbox information
            int count = selectedMailbox.getMessageCount();
            sendUntagged(count + " EXISTS");
            sendUntagged("0 RECENT"); // TODO: Track recent count
            sendUntagged("FLAGS (" + formatFlags(selectedMailbox.getPermanentFlags()) + ")");
            sendUntagged("OK [PERMANENTFLAGS (" + formatFlags(selectedMailbox.getPermanentFlags()) + " \\*)]");
            sendUntagged("OK [UIDVALIDITY " + selectedMailbox.getUidValidity() + "]");
            sendUntagged("OK [UIDNEXT " + selectedMailbox.getUidNext() + "]");
            
            String accessMode = readOnly ? "[READ-ONLY]" : "[READ-WRITE]";
            sendTaggedOk(tag, accessMode + " " + L10N.getString("imap.select_complete"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open mailbox: " + mailboxName, e);
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
                Set<String> attrs = store.getMailboxAttributes(mailbox);
                String attrStr = attrs.isEmpty() ? "" : "\\" + String.join(" \\", attrs);
                sendUntagged("LIST (" + attrStr + ") \"" + store.getHierarchyDelimiter() + 
                    "\" " + quoteMailboxName(mailbox));
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
                sendUntagged("LSUB () \"" + store.getHierarchyDelimiter() + 
                    "\" " + quoteMailboxName(mailbox));
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
        
        String personal = "((\"" + store.getPersonalNamespace() + "\" \"" + 
            store.getHierarchyDelimiter() + "\"))";
        sendUntagged("NAMESPACE " + personal + " NIL NIL");
        sendTaggedOk(tag, L10N.getString("imap.namespace_complete"));
    }

    private void handleStatus(String tag, String args) throws IOException {
        // Parse: mailbox-name SP "(" status-att *(SP status-att) ")"
        int parenStart = args.indexOf('(');
        if (parenStart < 0) {
            sendTaggedBad(tag, L10N.getString("imap.err.status_syntax"));
            return;
        }
        
        String mailboxName = parseMailboxName(args.substring(0, parenStart).trim());
        String attrsStr = args.substring(parenStart + 1, args.lastIndexOf(')'));
        List<String> attrList = new ArrayList<>();
        StringTokenizer attrTokenizer = new StringTokenizer(attrsStr);
        while (attrTokenizer.hasMoreTokens()) {
            attrList.add(attrTokenizer.nextToken());
        }
        String[] attrs = attrList.toArray(new String[0]);
        
        try {
            Mailbox mailbox = store.openMailbox(mailboxName, true);
            StringBuilder response = new StringBuilder();
            response.append("STATUS ").append(quoteMailboxName(mailboxName)).append(" (");
            
            boolean first = true;
            for (String attr : attrs) {
                if (!first) response.append(" ");
                first = false;
                
                switch (attr.toUpperCase()) {
                    case "MESSAGES":
                        response.append("MESSAGES ").append(mailbox.getMessageCount());
                        break;
                    case "UIDNEXT":
                        response.append("UIDNEXT ").append(mailbox.getUidNext());
                        break;
                    case "UIDVALIDITY":
                        response.append("UIDVALIDITY ").append(mailbox.getUidValidity());
                        break;
                    case "UNSEEN":
                        response.append("UNSEEN 0"); // TODO: Count unseen
                        break;
                    case "RECENT":
                        response.append("RECENT 0"); // TODO: Count recent
                        break;
                    case "SIZE":
                        response.append("SIZE ").append(mailbox.getMailboxSize());
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
        
        // Parse APPEND arguments: mailbox [FLAGS (flag-list)] [DATE-TIME] literal
        // Example: APPEND INBOX (\Seen) "01-Jan-2025 12:00:00 +0000" {1234}
        
        String remaining = args.trim();
        
        // Parse mailbox name
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
        
        // Parse optional FLAGS
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
                    // Unknown flags are silently ignored per IMAP spec
                }
            }
            remaining = remaining.substring(endParen + 1).trim();
        }
        
        // Parse optional DATE-TIME
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
                // Invalid date format, use current time
                LOGGER.log(Level.FINE, "Invalid internal date format: " + dateStr, e);
            }
            remaining = remaining.substring(endQuote + 1).trim();
        }
        
        // Parse literal size {nnnn} or {nnnn+} (literal+ for non-synchronizing)
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
        
        // Check quota before accepting the message
        QuotaManager quotaManager = server.getQuotaManager();
        if (quotaManager != null) {
            if (!quotaManager.canStore(authenticatedUser, literalSize)) {
                Quota quota = quotaManager.getQuota(authenticatedUser);
                sendTaggedNo(tag, "[OVERQUOTA] " + MessageFormat.format(
                    L10N.getString("imap.err.quota_exceeded"),
                    QuotaPolicy.formatSize(literalSize),
                    QuotaPolicy.formatSize(quota.getStorageRemaining())));
                return;
            }
        }
        
        // Open the target mailbox for append
        try {
            appendMailbox = store.openMailbox(mailboxName, false);
            appendMailbox.startAppendMessage(flags, internalDate);
            appendTag = tag;
            appendLiteralRemaining = literalSize;
            appendMailboxName = mailboxName;
            appendMessageSize = literalSize;

            // Track append start in telemetry
            addSessionEvent("APPEND_START");
            addSessionAttribute("imap.append.mailbox", mailboxName);
            addSessionAttribute("imap.append.size", literalSize);
            
            // Send continuation request (unless non-synchronizing literal)
            if (!nonSync) {
                sendContinuation(L10N.getString("imap.ready_for_literal"));
            }
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to start APPEND to " + mailboxName, e);
            recordSessionException(e);
            sendTaggedNo(tag, "[TRYCREATE] " + L10N.getString("imap.err.append_failed"));
        }
    }

    /**
     * Parses an IMAP internal date-time string.
     * Format: "dd-Mon-yyyy hh:mm:ss +zzzz"
     */
    private OffsetDateTime parseInternalDate(String dateStr) {
        // Simple parsing - a full implementation would use DateTimeFormatter
        // For now, return current time as fallback
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

    // ========================================================================
    // QUOTA Extension Commands (RFC 9208)
    // ========================================================================

    /**
     * Handles GETQUOTA command.
     * Returns the quota values for the named quota root.
     * 
     * Syntax: GETQUOTA quota-root
     * Response: * QUOTA quota-root (resource-name usage limit ...)
     */
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
        
        // Parse quota root (typically empty string "" for user's root)
        String quotaRoot = parseQuotaRoot(args);
        
        // Only users can access their own quota, admins can access others
        String targetUser = extractUserFromQuotaRoot(quotaRoot);
        if (targetUser == null) {
            targetUser = authenticatedUser;
        }
        
        // Check authorization - admin or self
        if (!targetUser.equals(authenticatedUser) && 
            !server.getRealm().isUserInRole(authenticatedUser, "admin")) {
            sendTaggedNo(tag, L10N.getString("imap.err.quota_access_denied"));
            return;
        }
        
        Quota quota = quotaManager.getQuota(targetUser);
        sendQuotaResponse(quotaRoot, quota);
        sendTaggedOk(tag, L10N.getString("imap.quota_complete"));
    }

    /**
     * Handles GETQUOTAROOT command.
     * Returns the list of quota roots for the given mailbox.
     * 
     * Syntax: GETQUOTAROOT mailbox
     * Response: * QUOTAROOT mailbox quota-root ...
     *           * QUOTA quota-root (resource-name usage limit ...)
     */
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
        
        // Parse mailbox name
        String mailboxName = parseMailboxName(args);
        if (mailboxName == null || mailboxName.isEmpty()) {
            sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
            return;
        }
        
        // The quota root for a user's mailbox is typically "" (empty string)
        // representing the user's entire storage quota
        String quotaRoot = "";
        
        // Send QUOTAROOT response
        sendUntagged("QUOTAROOT " + quoteString(mailboxName) + " " + quoteString(quotaRoot));
        
        // Send QUOTA response for the root
        Quota quota = quotaManager.getQuota(authenticatedUser);
        sendQuotaResponse(quotaRoot, quota);
        
        sendTaggedOk(tag, L10N.getString("imap.quotaroot_complete"));
    }

    /**
     * Handles SETQUOTA command.
     * Sets quota limits for a quota root. Typically admin-only.
     * 
     * Syntax: SETQUOTA quota-root (resource-name resource-limit ...)
     */
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
        
        // Only admins can set quotas
        if (!server.getRealm().isUserInRole(authenticatedUser, "admin")) {
            sendTaggedNo(tag, L10N.getString("imap.err.quota_permission_denied"));
            return;
        }
        
        // Parse: quota-root (STORAGE limit) or (STORAGE limit MESSAGE limit)
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
        
        // Parse resource limits
        long storageLimit = -1;
        long messageLimit = -1;
        
        StringTokenizer st = new StringTokenizer(resourceList);
        while (st.hasMoreTokens()) {
            String resourceName = st.nextToken().toUpperCase(Locale.ROOT);
            if (!st.hasMoreTokens()) {
                sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
                return;
            }
            String limitStr = st.nextToken();
            
            try {
                long limit = Long.parseLong(limitStr);
                if ("STORAGE".equals(resourceName)) {
                    // IMAP STORAGE is in KB, convert to bytes
                    storageLimit = limit * 1024;
                } else if ("MESSAGE".equals(resourceName)) {
                    messageLimit = limit;
                }
            } catch (NumberFormatException e) {
                sendTaggedBad(tag, L10N.getString("imap.err.invalid_arguments"));
                return;
            }
        }
        
        // Set the quota
        quotaManager.setUserQuota(targetUser, storageLimit, messageLimit);
        
        // Send the updated quota in response
        Quota quota = quotaManager.getQuota(targetUser);
        sendQuotaResponse(quotaRoot, quota);
        
        sendTaggedOk(tag, L10N.getString("imap.setquota_complete"));
    }

    /**
     * Sends a QUOTA untagged response.
     */
    private void sendQuotaResponse(String quotaRoot, Quota quota) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("QUOTA ").append(quoteString(quotaRoot)).append(" (");
        
        // STORAGE resource (in KB)
        if (!quota.isStorageUnlimited()) {
            sb.append("STORAGE ").append(quota.getStorageUsedKB());
            sb.append(" ").append(quota.getStorageLimitKB());
        }
        
        // MESSAGE resource (if applicable)
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

    /**
     * Parses a quota root from command arguments.
     * Handles both quoted and unquoted forms.
     */
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

    /**
     * Extracts username from a quota root.
     * The quota root format is typically "" for the current user
     * or "user.username" for a specific user.
     */
    private String extractUserFromQuotaRoot(String quotaRoot) {
        if (quotaRoot == null || quotaRoot.isEmpty()) {
            return authenticatedUser;
        }
        if (quotaRoot.startsWith("user.")) {
            return quotaRoot.substring(5);
        }
        return quotaRoot;
    }

    /**
     * Quotes a string for IMAP response if needed.
     */
    private String quoteString(String s) {
        if (s == null || s.isEmpty()) {
            return "\"\"";
        }
        // Check if quoting is needed
        boolean needsQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '(' || c == ')' || c == '{' || c == '"' || c == '\\' || c < 32) {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            return s;
        }
        // Escape and quote
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

    // ========================================================================
    // SELECTED State Commands
    // ========================================================================

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
            selectedMailbox.close(false); // Don't expunge
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

    private void handleSearch(String tag, String args, boolean uid) throws IOException {
        if (selectedMailbox == null) {
            sendTaggedNo(tag, L10N.getString("imap.err.no_mailbox_selected"));
            return;
        }
        
        try {
            // Parse the search criteria
            SearchParser parser = new SearchParser(args);
            SearchCriteria criteria = parser.parse();
            
            // Execute the search
            List<Integer> results = selectedMailbox.search(criteria);
            
            // Build response
            StringBuilder response = new StringBuilder("SEARCH");
            for (Integer msgNum : results) {
                if (uid) {
                    // For UID SEARCH, return UIDs instead of sequence numbers
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
        // UID command: UID FETCH/SEARCH/STORE/COPY/MOVE
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
                    L10N.getString("imap.err.unknown_command"), "UID " + command));
        }
    }

    private void handleFetch(String tag, String args, boolean uid) throws IOException {
        // TODO: Implement full FETCH
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    private void handleStore(String tag, String args, boolean uid) throws IOException {
        // TODO: Implement STORE
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    private void handleCopy(String tag, String args, boolean uid) throws IOException {
        // TODO: Implement COPY
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    private void handleMove(String tag, String args, boolean uid) throws IOException {
        if (!server.isEnableMOVE()) {
            sendTaggedBad(tag, L10N.getString("imap.err.unknown_command"));
            return;
        }
        // TODO: Implement MOVE
        sendTaggedNo(tag, L10N.getString("imap.err.not_implemented"));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

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
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean wasQuoted = false; // Track if current token was quoted (for empty strings)
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
                    // Closing quote - add the token (even if empty)
                    result.add(current.toString());
                    current.setLength(0);
                    wasQuoted = false;
                } else {
                    // Opening quote
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
            return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return name;
    }

    /**
     * Formats a set of flags as a space-separated string of IMAP atoms.
     */
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

    // ========================================================================
    // Response Methods
    // ========================================================================

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
        send(ByteBuffer.wrap(bytes));
    }

    /**
     * Called when the connection is closed by the peer.
     * Uses try-finally to guarantee resource cleanup and telemetry completion.
     */
    @Override
    protected void disconnected() throws IOException {
        try {
            // End telemetry spans
            if (state != IMAPState.LOGOUT) {
                // Unexpected disconnection
                endSessionSpanError("Client disconnected");
            } else {
                endSessionSpan("Connection closed");
            }
        } finally {
            // Clean up resources in finally block
            try {
                if (selectedMailbox != null) {
                    selectedMailbox.close(false);
                    selectedMailbox = null;
                }
            } finally {
                if (store != null) {
                    store.close();
                    store = null;
                }
            }
        }
    }

    @Override
    public void close() {
        // End telemetry spans if not already ended
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            if (state == IMAPState.LOGOUT) {
                endSessionSpan("Connection closed");
            } else {
                endSessionSpanError("Connection closed unexpectedly");
            }
        }

        try {
            if (selectedMailbox != null) {
                selectedMailbox.close(false);
            }
            if (store != null) {
                store.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing mailbox", e);
        }
        super.close();
    }

}

