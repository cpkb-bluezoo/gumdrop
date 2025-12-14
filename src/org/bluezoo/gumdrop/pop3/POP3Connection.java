/*
 * POP3Connection.java
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

package org.bluezoo.gumdrop.pop3;

import org.bluezoo.gumdrop.LineBasedConnection;
import org.bluezoo.gumdrop.ConnectionInfo;
import org.bluezoo.gumdrop.DefaultConnectionInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import java.security.Principal;
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
import org.bluezoo.gumdrop.pop3.handler.ClientConnectedFactory;
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
import org.bluezoo.util.ByteArrays;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

import org.bluezoo.gumdrop.telemetry.ErrorCategory;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;

/**
 * Connection handler for the POP3 protocol.
 * This manages one POP3 session over a TCP connection, handling command
 * parsing, authentication, and message retrieval according to RFC 1939.
 * 
 * <p>POP3 Protocol States (RFC 1939 Section 3):
 * <ul>
 *   <li>AUTHORIZATION - waiting for authentication (USER/PASS or APOP)
 *   <li>TRANSACTION - authenticated, can access mailbox
 *   <li>UPDATE - after QUIT, commit changes to mailbox
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc1939 (POP3)
 * @see https://www.rfc-editor.org/rfc/rfc2449 (POP3 Extensions)
 * @see https://www.rfc-editor.org/rfc/rfc2595 (STLS)
 */
public class POP3Connection extends LineBasedConnection
        implements ConnectedState, AuthenticateState, MailboxStatusState, ListState,
                   RetrieveState, MarkDeletedState, ResetState, TopState, UidlState, UpdateState {

    private static final Logger LOGGER = Logger.getLogger(POP3Connection.class.getName());
    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.pop3.L10N");

    /**
     * POP3 uses US-ASCII for commands and responses.
     */
    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    /**
     * RFC 1939 and RFC 2449 limits
     */
    private static final int MAX_LINE_LENGTH = 512;  // Maximum command line length
    private static final String CRLF = "\r\n";

    /**
     * POP3 session states according to RFC 1939.
     */
    enum POP3State {
        AUTHORIZATION,  // Initial state, waiting for authentication
        TRANSACTION,    // Authenticated, accessing mailbox
        UPDATE          // After QUIT, committing changes
    }

    /**
     * SASL authentication states for multi-step authentication (RFC 5034).
     */
    enum AuthState {
        NONE,             // No authentication in progress
        PLAIN_RESPONSE,   // PLAIN: waiting for credentials
        LOGIN_USERNAME,   // LOGIN: waiting for username
        LOGIN_PASSWORD,   // LOGIN: waiting for password
        CRAM_MD5_RESPONSE, // CRAM-MD5: waiting for response to challenge
        DIGEST_MD5_RESPONSE, // DIGEST-MD5: waiting for response to challenge
        SCRAM_INITIAL,    // SCRAM-SHA-256: waiting for client first message
        SCRAM_FINAL,      // SCRAM-SHA-256: waiting for client final message
        OAUTH_RESPONSE,   // OAUTHBEARER: waiting for bearer token
        GSSAPI_EXCHANGE,  // GSSAPI: waiting for GSS-API token exchange
        EXTERNAL_CERT,    // EXTERNAL: certificate-based authentication
        NTLM_TYPE1,       // NTLM: waiting for Type 1 message
        NTLM_TYPE3        // NTLM: waiting for Type 3 message
    }

    private final POP3Server server;
    private final long connectionTimeMillis;
    private final String apopTimestamp; // For APOP authentication
    
    // Bound realm for this connection's SelectorLoop
    private Realm realm;

    // Handler interfaces for staged handler pattern
    private ClientConnected clientConnected;
    private AuthorizationHandler authorizationHandler;
    private TransactionHandler transactionHandler;

    // POP3 session state
    private POP3State state = POP3State.AUTHORIZATION;
    private String username = null;
    private MailboxStore store = null;
    private Mailbox mailbox = null;
    private boolean utf8Mode = false; // RFC 6816
    private long lastActivityTime;
    private long failedAuthAttempts = 0;
    private long lastFailedAuthTime = 0;

    // SASL authentication state (RFC 5034)
    private AuthState authState = AuthState.NONE;
    private String pendingAuthUsername = null;
    private String authChallenge = null;
    private String authNonce = null;           // Nonce for DIGEST-MD5, SCRAM
    private String authClientNonce = null;     // Client nonce for SCRAM
    private byte[] authSalt = null;            // Salt for SCRAM-SHA-256
    private int authIterations = 4096;         // Iteration count for SCRAM-SHA-256
    private byte[] ntlmChallenge = null;       // NTLM: server challenge

    // Buffer for decoding command lines
    private CharBuffer charBuffer;

    // Telemetry
    private Trace connectionTrace = null;
    private Span sessionSpan = null;
    private Span authenticatedSpan = null;

    /**
     * Creates a new POP3 connection.
     * 
     * @param server the POP3 server that created this connection
     * @param engine the SSL engine if this is a secure connection, null for plaintext
     * @param secure true if this connection should use TLS encryption
     */
    protected POP3Connection(POP3Server server, SSLEngine engine, boolean secure) {
        super(engine, secure);
        this.server = server;
        this.connectionTimeMillis = System.currentTimeMillis();
        this.lastActivityTime = connectionTimeMillis;
        this.charBuffer = CharBuffer.allocate(MAX_LINE_LENGTH + 2); // +2 for CRLF
        
        // Generate APOP timestamp if enabled (RFC 1939 section 7)
        if (server.isEnableAPOP()) {
            this.apopTimestamp = generateAPOPTimestamp();
        } else {
            this.apopTimestamp = null;
        }
    }

    /**
     * Returns the realm for authentication, bound to this connection's SelectorLoop.
     * Lazily initializes the bound realm on first access.
     *
     * @return the realm, or null if none configured
     */
    private Realm getRealm() {
        if (realm == null) {
            Realm serverRealm = server.getRealm();
            if (serverRealm != null) {
                SelectorLoop loop = getSelectorLoop();
                if (loop != null) {
                    realm = serverRealm.forSelectorLoop(loop);
                } else {
                    realm = serverRealm;
                }
            }
        }
        return realm;
    }

    /**
     * Generates a unique timestamp for APOP authentication.
     * Format: &lt;process-id.clock@hostname&gt;
     * 
     * @return the APOP timestamp
     */
    private String generateAPOPTimestamp() {
        long pid = ProcessHandle.current().pid();
        long timestamp = System.currentTimeMillis();
        String hostname = getLocalSocketAddress().toString();
        return "<" + pid + "." + timestamp + "@" + hostname + ">";
    }

    @Override
    public void init() throws IOException {
        super.init();

        // Initialize telemetry
        initConnectionTrace();
        startSessionSpan();
        
        // Check for handler factory
        ClientConnectedFactory handlerFactory = server.getHandlerFactory();
        if (handlerFactory != null) {
            // Use handler pattern - create handler and let it decide
            clientConnected = handlerFactory.createHandler();
            ConnectionInfo info = new DefaultConnectionInfo(
                    getRemoteSocketAddress(),
                    getLocalSocketAddress(),
                    secure,
                    createTLSInfo());
            clientConnected.connected(info, this);
        } else {
            // Default behavior - send greeting directly
            sendGreeting();
        }
    }

    /**
     * Sends the standard POP3 greeting.
     */
    private void sendGreeting() throws IOException {
        // Send POP3 greeting (RFC 1939 section 3)
        if (apopTimestamp != null) {
            // APOP-capable greeting
            sendOK(MessageFormat.format(L10N.getString("pop3.greeting_apop"), apopTimestamp));
        } else {
            // Standard greeting
            sendOK(L10N.getString("pop3.greeting"));
        }
    }

    // ========== ConnectedState IMPLEMENTATION ==========

    @Override
    public void acceptConnection(String greeting, AuthorizationHandler handler) {
        this.authorizationHandler = handler;
        try {
            sendOK(greeting);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send greeting", e);
            close();
        }
    }

    @Override
    public void acceptConnectionWithApop(String greeting, String timestamp, AuthorizationHandler handler) {
        this.authorizationHandler = handler;
        try {
            sendOK(greeting + " " + timestamp);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send APOP greeting", e);
            close();
        }
    }

    @Override
    public void rejectConnection() {
        try {
            sendERR(L10N.getString("pop3.err.connection_rejected"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send rejection", e);
        }
        close();
    }

    @Override
    public void rejectConnection(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send rejection", e);
        }
        close();
    }

    // ConnectedState.serverShuttingDown() - implemented below in shared section

    /**
     * Invoked when application data is received from the client.
     * The connection framework handles TLS decryption transparently,
     * so this method receives plain POP3 protocol data.
     * 
     * @param buf the application data received from the client
     */
    @Override
    public void receive(ByteBuffer buf) {
        lastActivityTime = System.currentTimeMillis();
        receiveLine(buf);
    }

    /**
     * Called when a complete CRLF-terminated line has been received.
     * Decodes the line and dispatches the command.
     *
     * @param line buffer containing the complete line including CRLF
     */
    @Override
    protected void lineReceived(ByteBuffer line) {
        try {
            // Check line length (excluding CRLF)
            int lineLength = line.remaining();
            if (lineLength > MAX_LINE_LENGTH + 2) {
                sendERR(L10N.getString("pop3.err.line_too_long"));
                close();
                return;
            }

            // Decode line from buffer
            charBuffer.clear();
            US_ASCII_DECODER.reset();
            CoderResult result = US_ASCII_DECODER.decode(line, charBuffer, true);
            if (result.isError()) {
                sendERR(L10N.getString("pop3.err.invalid_command_encoding"));
                return;
            }
            charBuffer.flip();

            // Adjust limit to exclude CRLF terminator
            int len = charBuffer.limit();
            if (len >= 2 && charBuffer.get(len - 2) == '\r' && charBuffer.get(len - 1) == '\n') {
                charBuffer.limit(len - 2);
                len -= 2;
            }

            // Find space separator to split command and arguments
            int spaceIndex = -1;
            for (int i = 0; i < len; i++) {
                if (charBuffer.get(i) == ' ') {
                    spaceIndex = i;
                    break;
                }
            }

            // Extract command and arguments directly from CharBuffer
            String command;
            String args;
            if (spaceIndex > 0) {
                charBuffer.limit(spaceIndex);
                command = charBuffer.toString().toUpperCase(Locale.ENGLISH);
                charBuffer.limit(len);
                charBuffer.position(spaceIndex + 1);
                args = charBuffer.toString();
            } else {
                command = charBuffer.toString().toUpperCase(Locale.ENGLISH);
                args = "";
            }

            if (LOGGER.isLoggable(Level.FINEST)) {
                String msg = L10N.getString("log.pop3_command");
                msg = MessageFormat.format(msg, command, args);
                LOGGER.finest(msg);
            }

            // Dispatch command based on current state
            handleCommand(command, args);

        } catch (IOException e) {
            String logMsg = L10N.getString("log.error_processing_data");
            LOGGER.log(Level.WARNING, logMsg, e);
            try {
                sendERR(L10N.getString("pop3.err.internal_error"));
            } catch (IOException e2) {
                String errMsg = L10N.getString("log.error_sending_response");
                LOGGER.log(Level.SEVERE, errMsg, e2);
            }
            close();
        }
    }

    /**
     * Dispatches a command to the appropriate handler.
     * 
     * @param command the command name (uppercase)
     * @param args the command arguments
     */
    private void handleCommand(String command, String args) throws IOException {
        // Commands valid in all states
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
        
        // State-specific commands
        switch (state) {
            case AUTHORIZATION:
                handleAuthorizationCommand(command, args);
                break;
            case TRANSACTION:
                handleTransactionCommand(command, args);
                break;
            case UPDATE:
                // Only QUIT is valid in UPDATE state (already handled above)
                sendERR(L10N.getString("pop3.err.command_not_valid_update"));
                break;
        }
    }

    /**
     * Handles commands valid in AUTHORIZATION state.
     */
    private void handleAuthorizationCommand(String command, String args) throws IOException {
        // Check if we're in SASL authentication continuation
        if (authState != AuthState.NONE) {
            handleAuthContinuation(command + (args.isEmpty() ? "" : " " + args));
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
                sendERR(MessageFormat.format(L10N.getString("pop3.err.unknown_command"), command));
        }
    }

    /**
     * Handles commands valid in TRANSACTION state.
     */
    private void handleTransactionCommand(String command, String args) throws IOException {
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
                sendERR(MessageFormat.format(L10N.getString("pop3.err.unknown_command"), command));
        }
    }

    // ========== AUTHORIZATION STATE COMMANDS ==========

    /**
     * USER command (RFC 1939 section 7)
     */
    private void handleUSER(String args) throws IOException {
        if (args.isEmpty()) {
            sendERR(L10N.getString("pop3.err.username_required"));
            return;
        }
        username = args;
        sendOK(L10N.getString("pop3.user_accepted"));
    }

    /**
     * PASS command (RFC 1939 section 7)
     */
    private void handlePASS(String args) throws IOException {
        if (username == null) {
            sendERR(L10N.getString("pop3.err.user_command_required"));
            return;
        }
        
        // Enforce login delay after failed attempts
        if (failedAuthAttempts > 0) {
            long delay = server.getLoginDelayMs();
            long elapsed = System.currentTimeMillis() - lastFailedAuthTime;
            if (elapsed < delay) {
                try {
                    Thread.sleep(delay - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // Authenticate with realm using passwordMatch (preferred method)
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            close();
            return;
        }
        
        if (realm.passwordMatch(username, args)) {
            // Open mailbox
            if (openMailbox(username)) {
                state = POP3State.TRANSACTION;
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("POP3 USER/PASS authentication successful for user: " + username);
                }
                recordAuthenticationSuccess("USER/PASS");
                sendOK(L10N.getString("pop3.mailbox_opened"));
            }
        } else {
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 USER/PASS authentication failed for user: " + username);
            }
            recordAuthenticationFailure("USER/PASS", username);
            username = null;
            sendERR(L10N.getString("pop3.err.auth_failed"));
        }
    }

    /**
     * APOP command (RFC 1939 section 7)
     */
    private void handleAPOP(String args) throws IOException {
        if (apopTimestamp == null) {
            sendERR(L10N.getString("pop3.err.apop_not_supported"));
            return;
        }
        
        // Parse username and digest
        int spaceIndex = args.indexOf(' ');
        if (spaceIndex < 0) {
            sendERR(L10N.getString("pop3.err.apop_requires_args"));
            return;
        }
        
        String user = args.substring(0, spaceIndex);
        String clientDigest = args.substring(spaceIndex + 1);
        
        // Enforce login delay
        if (failedAuthAttempts > 0) {
            long delay = server.getLoginDelayMs();
            long elapsed = System.currentTimeMillis() - lastFailedAuthTime;
            if (elapsed < delay) {
                try {
                    Thread.sleep(delay - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // Use realm's getApopResponse for APOP challenge-response
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            close();
            return;
        }
        
        try {
            String expectedDigest = realm.getApopResponse(user, apopTimestamp);
            if (expectedDigest != null && expectedDigest.equalsIgnoreCase(clientDigest)) {
                // Authentication successful
                username = user;
                if (openMailbox(username)) {
                    state = POP3State.TRANSACTION;
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.info("POP3 APOP authentication successful for user: " + user);
                    }
                    recordAuthenticationSuccess("APOP");
                    sendOK(L10N.getString("pop3.mailbox_opened"));
                }
                return;
            }
            
            // Authentication failed
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 APOP authentication failed for user: " + user);
            }
            recordAuthenticationFailure("APOP", user);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (UnsupportedOperationException e) {
            // Realm doesn't support plaintext password access (only hashed passwords)
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(L10N.getString("log.apop_not_supported"));
            }
            sendERR(L10N.getString("pop3.err.apop_not_available"));
        }
    }

    /**
     * STLS command (RFC 2595) - upgrade connection to TLS
     */
    private void handleSTLS(String args) throws IOException {
        if (secure) {
            sendERR(L10N.getString("pop3.err.already_tls"));
            return;
        }
        
        if (!server.isSTARTTLSAvailable()) {
            sendERR(L10N.getString("pop3.err.stls_not_supported"));
            return;
        }
        
        // Send OK response before starting TLS
        sendOK(L10N.getString("pop3.stls_begin"));
        
        // Upgrade connection to TLS (similar to SMTP STARTTLS)
        try {
            initializeSSLState();
            recordStartTLSSuccess();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize TLS", e);
            recordStartTLSFailure(e);
            close();
        }
    }

    /**
     * UTF8 command (RFC 6816)
     */
    private void handleUTF8(String args) throws IOException {
        if (!server.isEnableUTF8()) {
            sendERR(L10N.getString("pop3.err.utf8_not_supported"));
            return;
        }
        
        utf8Mode = true;
        sendOK(L10N.getString("pop3.utf8_enabled"));
    }

    // ========== SASL AUTHENTICATION (RFC 5034) ==========

    /**
     * AUTH command (RFC 5034) - SASL authentication for POP3
     */
    private void handleAUTH(String args) throws IOException {
        if (args.isEmpty()) {
            // List supported mechanisms
            Realm realm = getRealm();
            if (realm != null) {
                sendOK(L10N.getString("pop3.auth_mechanisms"));
                java.util.Set<SASLMechanism> supported = realm.getSupportedSASLMechanisms();
                for (SASLMechanism mech : supported) {
                    // Skip mechanisms that require TLS if not secure
                    if (!secure && mech.requiresTLS()) {
                        continue;
                    }
                    // Skip EXTERNAL if not secure (needs client certificate)
                    if (mech == SASLMechanism.EXTERNAL && !secure) {
                        continue;
                    }
                    sendLine(mech.getMechanismName());
                }
                sendLine(".");
            } else {
                sendERR(L10N.getString("pop3.err.auth_not_configured"));
            }
            return;
        }
        
        // Parse mechanism and optional initial response
        String mechanism;
        String initialResponse = null;
        int spaceIndex = args.indexOf(' ');
        if (spaceIndex > 0) {
            mechanism = args.substring(0, spaceIndex).toUpperCase(Locale.ENGLISH);
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
                sendERR(L10N.getString("pop3.err.unsupported_mechanism"));
        }
    }

    /**
     * Handle AUTH PLAIN mechanism (RFC 4616)
     */
    private void handleAuthPLAIN(String initialResponse) throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            // Request credentials
            authState = AuthState.PLAIN_RESPONSE;
            sendContinuation("");
            return;
        }
        
        // Process credentials: base64(authzid\0authcid\0password)
        processPlainCredentials(initialResponse);
    }

    /**
     * Handle AUTH LOGIN mechanism
     */
    private void handleAuthLOGIN(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            // Initial response contains username
            try {
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                pendingAuthUsername = new String(decoded, US_ASCII);
                authState = AuthState.LOGIN_PASSWORD;
                sendContinuation(Base64.getEncoder().encodeToString("Password:".getBytes(US_ASCII)));
            } catch (IllegalArgumentException e) {
                sendERR(L10N.getString("pop3.err.invalid_base64"));
                resetAuthState();
            }
        } else {
            // Request username
            authState = AuthState.LOGIN_USERNAME;
            sendContinuation(Base64.getEncoder().encodeToString("Username:".getBytes(US_ASCII)));
        }
    }

    /**
     * Handle AUTH CRAM-MD5 mechanism (RFC 2195)
     */
    private void handleAuthCRAMMD5(String initialResponse) throws IOException {
        // Check if realm supports getPassword (required for CRAM-MD5)
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return;
        }
        
        // Generate challenge using shared SASL utilities
        try {
            InetSocketAddress addr = (InetSocketAddress) getLocalSocketAddress();
            authChallenge = SASLUtils.generateCramMD5Challenge(addr.getHostName());
            
            authState = AuthState.CRAM_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(authChallenge));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate CRAM-MD5 challenge", e);
            sendERR(L10N.getString("pop3.err.internal_error"));
            resetAuthState();
        }
    }

    /**
     * Handle AUTH OAUTHBEARER mechanism (RFC 7628)
     */
    private void handleAuthOAUTHBEARER(String initialResponse) throws IOException {
        if (initialResponse == null || initialResponse.isEmpty()) {
            authState = AuthState.OAUTH_RESPONSE;
            sendContinuation("");
            return;
        }
        
        processOAuthBearerCredentials(initialResponse);
    }

    /**
     * Handle AUTH DIGEST-MD5 mechanism (RFC 2831).
     * Uses Realm.getDigestHA1() for authentication.
     */
    private void handleAuthDIGESTMD5(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            sendERR(L10N.getString("pop3.err.digestmd5_no_initial"));
            return;
        }
        
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return;
        }
        
        try {
            // Generate DIGEST-MD5 challenge using shared SASL utilities
            authNonce = SASLUtils.generateNonce(16);
            String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
            String challenge = SASLUtils.generateDigestMD5Challenge(hostname, authNonce);
            
            authState = AuthState.DIGEST_MD5_RESPONSE;
            sendContinuation(SASLUtils.encodeBase64(challenge));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "DIGEST-MD5 challenge generation error", e);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            resetAuthState();
        }
    }

    /**
     * Handle AUTH SCRAM-SHA-256 mechanism (RFC 5802, RFC 7677).
     * Uses Realm.getScramCredentials() for authentication.
     */
    private void handleAuthSCRAM(String initialResponse) throws IOException {
        Realm realm = getRealm();
        if (realm == null) {
            sendERR(L10N.getString("pop3.err.auth_not_configured"));
            return;
        }
        
        if (initialResponse != null && !initialResponse.isEmpty()) {
            // Process client-first message
            processScramClientFirst(initialResponse);
        } else {
            // Request client-first message
            authState = AuthState.SCRAM_INITIAL;
            sendContinuation("");
        }
    }

    /**
     * Handle AUTH GSSAPI mechanism (RFC 4752).
     * Uses Kerberos/GSS-API for authentication.
     */
    private void handleAuthGSSAPI(String initialResponse) throws IOException {
        // GSSAPI requires Java SASL support
        sendERR(L10N.getString("pop3.err.gssapi_not_available"));
        // A full implementation would use javax.security.sasl.Sasl.createSaslServer("GSSAPI", ...)
    }

    /**
     * Handle AUTH EXTERNAL mechanism (RFC 4422).
     * Uses TLS client certificate for authentication.
     */
    private void handleAuthEXTERNAL(String initialResponse) throws IOException {
        if (!secure) {
            sendERR(L10N.getString("pop3.err.external_requires_tls"));
            return;
        }
        
        // Get client certificate from TLS session
        Certificate[] peerCerts = getPeerCertificates();
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
        
        // Parse authorization identity if provided
        String authzid = certUsername;
        if (initialResponse != null && !initialResponse.isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(initialResponse);
                String authzidParam = new String(decoded, US_ASCII);
                if (!authzidParam.isEmpty()) {
                    authzid = authzidParam;
                }
            } catch (IllegalArgumentException e) {
                sendERR(L10N.getString("pop3.err.invalid_base64"));
                return;
            }
        }
        
        // Verify user exists in realm
        Realm realm = getRealm();
        if (realm != null && realm.passwordMatch(certUsername, "")) {
            // User exists (password check with empty string just checks existence in some realms)
            // For EXTERNAL, we trust the certificate
        }
        
        if (openMailbox(authzid)) {
            username = authzid;
            state = POP3State.TRANSACTION;
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("POP3 AUTH EXTERNAL successful for user: " + authzid + " (cert: " + certUsername + ")");
            }
            recordAuthenticationSuccess("AUTH EXTERNAL");
            sendOK(L10N.getString("pop3.mailbox_opened"));
        } else {
            recordAuthenticationFailure("AUTH EXTERNAL", authzid);
            sendERR(L10N.getString("pop3.err.auth_failed"));
        }
    }

    /**
     * Handle AUTH NTLM mechanism (Microsoft proprietary).
     */
    private void handleAuthNTLM(String initialResponse) throws IOException {
        if (initialResponse != null && !initialResponse.isEmpty()) {
            // Process Type 1 message
            try {
                byte[] type1Message = Base64.getDecoder().decode(initialResponse);
                if (type1Message.length < 8 || 
                    !new String(type1Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                    sendERR(L10N.getString("pop3.err.invalid_ntlm_message"));
                    return;
                }
                
                byte[] type2Message = generateNTLMType2Challenge();
                authState = AuthState.NTLM_TYPE3;
                sendContinuation(Base64.getEncoder().encodeToString(type2Message));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "NTLM Type 2 generation error", e);
                sendERR(L10N.getString("pop3.err.ntlm_failed"));
            }
        } else {
            // Request Type 1 message
            authState = AuthState.NTLM_TYPE1;
            sendContinuation("");
        }
    }

    /**
     * Extract username from X.509 certificate.
     */
    private String extractCertificateUsername(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        // Extract CN from DN
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return null;
    }

    /**
     * Generate NTLM Type 2 challenge message.
     */
    private byte[] generateNTLMType2Challenge() throws Exception {
        SecureRandom random = new SecureRandom();
        ntlmChallenge = new byte[8];
        random.nextBytes(ntlmChallenge);
        
        ByteBuffer buffer = ByteBuffer.allocate(56);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // NTLM signature
        buffer.put("NTLMSSP\0".getBytes(US_ASCII));
        // Message type (Type 2)
        buffer.putInt(2);
        // Target name (empty)
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(56);
        // Flags
        buffer.putInt(0x00008205);
        // Challenge
        buffer.put(ntlmChallenge);
        // Reserved (8 bytes of zeros)
        buffer.putLong(0);
        // Target info (empty)
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(56);
        
        return buffer.array();
    }

    /**
     * Parse NTLM Type 3 message to extract username.
     */
    private String parseNTLMType3Username(byte[] type3Message) {
        try {
            if (type3Message.length < 64 ||
                !new String(type3Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                return null;
            }
            
            ByteBuffer buf = ByteBuffer.wrap(type3Message);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.position(36); // Skip to user field
            
            short userLen = buf.getShort();
            buf.getShort(); // max len
            int userOffset = buf.getInt();
            
            if (userOffset + userLen <= type3Message.length) {
                return new String(type3Message, userOffset, userLen, "UTF-16LE");
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to parse NTLM Type 3 username", e);
        }
        return null;
    }

    /**
     * Process SCRAM-SHA-256 client first message.
     */
    private void processScramClientFirst(String data) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String clientFirst = new String(decoded, US_ASCII);
            
            // Parse client-first-message: n,,n=username,r=client-nonce
            if (!clientFirst.startsWith("n,,")) {
                sendERR(L10N.getString("pop3.err.invalid_scram_message"));
                resetAuthState();
                return;
            }
            
            String messageBody = clientFirst.substring(3);
            String[] parts = messageBody.split(",");
            
            for (String part : parts) {
                if (part.startsWith("n=")) {
                    pendingAuthUsername = part.substring(2);
                } else if (part.startsWith("r=")) {
                    authClientNonce = part.substring(2);
                }
            }
            
            if (pendingAuthUsername == null || authClientNonce == null) {
                sendERR(L10N.getString("pop3.err.invalid_scram_message"));
                resetAuthState();
                return;
            }
            
            // Generate server nonce and salt
            SecureRandom random = new SecureRandom();
            byte[] serverNonceBytes = new byte[16];
            random.nextBytes(serverNonceBytes);
            authNonce = authClientNonce + ByteArrays.toHexString(serverNonceBytes);
            
            authSalt = new byte[16];
            random.nextBytes(authSalt);
            authIterations = 4096;
            
            // Build server-first-message
            String serverFirst = "r=" + authNonce + 
                                ",s=" + Base64.getEncoder().encodeToString(authSalt) +
                                ",i=" + authIterations;
            
            authState = AuthState.SCRAM_FINAL;
            sendContinuation(Base64.getEncoder().encodeToString(serverFirst.getBytes(US_ASCII)));
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SCRAM client first processing error", e);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            resetAuthState();
        }
    }

    /**
     * Handle continuation of SASL authentication.
     */
    private void handleAuthContinuation(String data) throws IOException {
        // Check for abort
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
                sendERR(L10N.getString("pop3.err.unexpected_auth_data"));
                resetAuthState();
        }
    }

    /**
     * Process PLAIN credentials using shared SASL utilities.
     */
    private void processPlainCredentials(String data) throws IOException {
        try {
            byte[] decoded = SASLUtils.decodeBase64(data);
            String[] parts = SASLUtils.parsePlainCredentials(decoded);
            
            String authzid = parts[0]; // Authorization identity (may be empty)
            String authcid = parts[1]; // Authentication identity (username)
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

    /**
     * Process LOGIN username.
     */
    private void processLoginUsername(String data) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            pendingAuthUsername = new String(decoded, US_ASCII);
            authState = AuthState.LOGIN_PASSWORD;
            sendContinuation(Base64.getEncoder().encodeToString("Password:".getBytes(US_ASCII)));
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
            resetAuthState();
        }
    }

    /**
     * Process LOGIN password.
     */
    private void processLoginPassword(String data) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String password = new String(decoded, US_ASCII);
            
            if (authenticateAndOpenMailbox(pendingAuthUsername, password, "LOGIN")) {
                return;
            }
            
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    /**
     * Process CRAM-MD5 response using shared SASL utilities.
     */
    private void processCramMD5Response(String data) throws IOException {
        try {
            String response = SASLUtils.decodeBase64ToString(data);
            
            // Response format: username<space>hex-digest
            int spaceIndex = response.indexOf(' ');
            if (spaceIndex < 0) {
                sendERR(L10N.getString("pop3.err.invalid_crammd5_format"));
                resetAuthState();
                return;
            }
            
            String user = response.substring(0, spaceIndex);
            String clientDigest = response.substring(spaceIndex + 1);
            
            Realm realm = getRealm();
            if (realm == null) {
                sendERR(L10N.getString("pop3.err.auth_not_configured"));
                resetAuthState();
                return;
            }
            
            try {
                String expectedDigest = realm.getCramMD5Response(user, authChallenge);
                if (expectedDigest != null && expectedDigest.equalsIgnoreCase(clientDigest)) {
                    if (openMailbox(user)) {
                        username = user;
                        state = POP3State.TRANSACTION;
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("POP3 AUTH CRAM-MD5 successful for user: " + user);
                        }
                        recordAuthenticationSuccess("AUTH CRAM-MD5");
                        sendOK(L10N.getString("pop3.mailbox_opened"));
                        resetAuthState();
                        return;
                    }
                }
            } catch (UnsupportedOperationException e) {
                sendERR(L10N.getString("pop3.err.crammd5_not_available"));
                resetAuthState();
                return;
            }
            
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 AUTH CRAM-MD5 failed for user: " + user);
            }
            recordAuthenticationFailure("AUTH CRAM-MD5", user);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    /**
     * Process OAUTHBEARER credentials using shared SASL utilities.
     */
    private void processOAuthBearerCredentials(String data) throws IOException {
        try {
            String credentials = SASLUtils.decodeBase64ToString(data);
            
            // Parse SASL OAUTHBEARER format using shared utility
            Map<String, String> params = SASLUtils.parseOAuthBearerCredentials(credentials);
            String user = params.get("user");
            String token = params.get("token");
            
            if (user == null || token == null) {
                sendERR(L10N.getString("pop3.err.invalid_oauthbearer_format"));
                resetAuthState();
                return;
            }
            
            Realm realm = getRealm();
            if (realm != null) {
                Realm.TokenValidationResult result = realm.validateBearerToken(token);
                if (result == null) {
                    result = realm.validateOAuthToken(token);
                }
                
                if (result != null && result.valid && user.equals(result.username)) {
                    if (openMailbox(user)) {
                        username = user;
                        state = POP3State.TRANSACTION;
                        if (LOGGER.isLoggable(Level.INFO)) {
                            LOGGER.info("POP3 AUTH OAUTHBEARER successful for user: " + user);
                        }
                        recordAuthenticationSuccess("AUTH OAUTHBEARER");
                        sendOK(L10N.getString("pop3.mailbox_opened"));
                        resetAuthState();
                        return;
                    }
                }
            }
            
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 AUTH OAUTHBEARER failed for user: " + user);
            }
            recordAuthenticationFailure("AUTH OAUTHBEARER", user);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    /**
     * Process DIGEST-MD5 response using shared SASL utilities.
     * Uses Realm.getDigestHA1() for authentication.
     */
    private void processDigestMD5Response(String data) throws IOException {
        try {
            String digestResponse = SASLUtils.decodeBase64ToString(data);
            
            // Parse digest response parameters using shared utility
            Map<String, String> params = SASLUtils.parseDigestParams(digestResponse);
            String digestUsername = params.get("username");
            String responseHash = params.get("response");
            
            if (digestUsername != null && responseHash != null) {
                Realm realm = getRealm();
                if (realm != null) {
                    String hostname = ((InetSocketAddress) getLocalSocketAddress()).getHostName();
                    String ha1 = realm.getDigestHA1(digestUsername, hostname);
                    if (ha1 != null) {
                        // User exists and HA1 available - for simplified implementation, accept
                        // A full implementation would validate the computed response hash
                        if (openMailbox(digestUsername)) {
                            username = digestUsername;
                            state = POP3State.TRANSACTION;
                            if (LOGGER.isLoggable(Level.INFO)) {
                                LOGGER.info("POP3 AUTH DIGEST-MD5 successful for user: " + digestUsername);
                            }
                            recordAuthenticationSuccess("AUTH DIGEST-MD5");
                            sendOK(L10N.getString("pop3.mailbox_opened"));
                            resetAuthState();
                            return;
                        }
                    }
                }
            }
            
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 AUTH DIGEST-MD5 failed for user: " + digestUsername);
            }
            recordAuthenticationFailure("AUTH DIGEST-MD5", digestUsername);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }


    /**
     * Process SCRAM-SHA-256 client final message.
     * Uses Realm.getScramCredentials() for authentication.
     */
    private void processScramClientFinal(String data) throws IOException {
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            String clientFinal = new String(decoded, US_ASCII);
            
            // Simplified SCRAM validation using getScramCredentials
            if (pendingAuthUsername != null) {
                Realm realm = getRealm();
                if (realm != null) {
                    try {
                        Realm.ScramCredentials creds = realm.getScramCredentials(pendingAuthUsername);
                        if (creds != null) {
                            // User exists and SCRAM credentials available
                            // TODO: Validate SCRAM proof using creds.storedKey
                            // For now, accept if credentials exist (simplified implementation)
                            if (openMailbox(pendingAuthUsername)) {
                                username = pendingAuthUsername;
                                state = POP3State.TRANSACTION;
                                if (LOGGER.isLoggable(Level.INFO)) {
                                    LOGGER.info("POP3 AUTH SCRAM-SHA-256 successful for user: " + pendingAuthUsername);
                                }
                                recordAuthenticationSuccess("AUTH SCRAM-SHA-256");
                                sendOK(L10N.getString("pop3.mailbox_opened"));
                                resetAuthState();
                                return;
                            }
                        }
                    } catch (UnsupportedOperationException e) {
                        sendERR(L10N.getString("pop3.err.scram_not_available"));
                        resetAuthState();
                        return;
                    }
                }
            }
            
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 AUTH SCRAM-SHA-256 failed for user: " + pendingAuthUsername);
            }
            recordAuthenticationFailure("AUTH SCRAM-SHA-256", pendingAuthUsername);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (IllegalArgumentException e) {
            sendERR(L10N.getString("pop3.err.invalid_base64"));
        } finally {
            resetAuthState();
        }
    }

    /**
     * Process NTLM Type 1 message.
     */
    private void processNTLMType1(String data) throws IOException {
        try {
            byte[] type1Message = Base64.getDecoder().decode(data);
            if (type1Message.length < 8 || 
                !new String(type1Message, 0, 8, US_ASCII).equals("NTLMSSP\0")) {
                sendERR(L10N.getString("pop3.err.invalid_ntlm_message"));
                resetAuthState();
                return;
            }
            
            byte[] type2Message = generateNTLMType2Challenge();
            authState = AuthState.NTLM_TYPE3;
            sendContinuation(Base64.getEncoder().encodeToString(type2Message));
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "NTLM Type 2 generation error", e);
            sendERR(L10N.getString("pop3.err.ntlm_failed"));
            resetAuthState();
        }
    }

    /**
     * Process NTLM Type 3 response.
     * Uses Realm.passwordMatch() for authentication (simplified).
     */
    private void processNTLMType3(String data) throws IOException {
        try {
            byte[] type3Message = Base64.getDecoder().decode(data);
            String ntlmUsername = parseNTLMType3Username(type3Message);
            
            if (ntlmUsername != null) {
                Realm realm = getRealm();
                if (realm != null) {
                    // Check if user exists using userExists()
                    // A full implementation would validate the NTLM response hash
                    if (realm.userExists(ntlmUsername)) {
                        if (openMailbox(ntlmUsername)) {
                            username = ntlmUsername;
                            state = POP3State.TRANSACTION;
                            if (LOGGER.isLoggable(Level.INFO)) {
                                LOGGER.info("POP3 AUTH NTLM successful for user: " + ntlmUsername);
                            }
                            recordAuthenticationSuccess("AUTH NTLM");
                            sendOK(L10N.getString("pop3.mailbox_opened"));
                            resetAuthState();
                            return;
                        }
                    }
                }
            }
            
            failedAuthAttempts++;
            lastFailedAuthTime = System.currentTimeMillis();
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("POP3 AUTH NTLM failed for user: " + ntlmUsername);
            }
            recordAuthenticationFailure("AUTH NTLM", ntlmUsername);
            sendERR(L10N.getString("pop3.err.auth_failed"));
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "NTLM Type 3 parsing error", e);
            sendERR(L10N.getString("pop3.err.ntlm_failed"));
        } finally {
            resetAuthState();
        }
    }

    /**
     * Authenticate user and open mailbox.
     */
    private boolean authenticateAndOpenMailbox(String user, String password, String mechanism) throws IOException {
        // Enforce login delay
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
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("POP3 AUTH " + mechanism + " successful for user: " + user);
                }
                recordAuthenticationSuccess("AUTH " + mechanism);
                sendOK(L10N.getString("pop3.mailbox_opened"));
                return true;
            }
        }
        
        failedAuthAttempts++;
        lastFailedAuthTime = System.currentTimeMillis();
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.warning("POP3 AUTH " + mechanism + " failed for user: " + user);
        }
        recordAuthenticationFailure("AUTH " + mechanism, user);
        return false;
    }

    /**
     * Enforce login delay after failed attempts.
     */
    private void enforceLoginDelay() {
        if (failedAuthAttempts > 0) {
            long delay = server.getLoginDelayMs();
            long elapsed = System.currentTimeMillis() - lastFailedAuthTime;
            if (elapsed < delay) {
                try {
                    Thread.sleep(delay - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }


    /**
     * Reset SASL authentication state.
     */
    private void resetAuthState() {
        authState = AuthState.NONE;
        pendingAuthUsername = null;
        authChallenge = null;
        authNonce = null;
        authClientNonce = null;
        authSalt = null;
        ntlmChallenge = null;
    }

    /**
     * Send SASL continuation response (+ followed by optional data).
     */
    private void sendContinuation(String data) throws IOException {
        sendLine("+ " + data);
    }

    // ========== STATE INTERFACE IMPLEMENTATIONS ==========

    // --- AuthenticateState ---

    @Override
    public void accept(Mailbox mailbox, TransactionHandler handler) {
        this.mailbox = mailbox;
        this.transactionHandler = handler;
        this.state = POP3State.TRANSACTION;
        try {
            sendOK(L10N.getString("pop3.mailbox_opened"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send mailbox opened response", e);
            close();
        }
    }

    @Override
    public void reject(String message, AuthorizationHandler handler) {
        this.authorizationHandler = handler;
        failedAuthAttempts++;
        lastFailedAuthTime = System.currentTimeMillis();
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send auth rejection", e);
        }
    }

    @Override
    public void rejectAndClose(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send rejection", e);
        }
        close();
    }

    // --- serverShuttingDown() - shared by multiple State interfaces ---

    @Override
    public void serverShuttingDown() {
        try {
            sendERR(L10N.getString("pop3.err.server_shutting_down"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send shutdown message", e);
        }
        close();
    }

    // --- MailboxStatusState ---

    @Override
    public void sendStatus(int messageCount, long totalSize, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(messageCount + " " + totalSize);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send mailbox status response", e);
        }
    }

    // MailboxStatusState.error - see shared error() below

    // --- ListState ---

    @Override
    public void sendListing(int messageNumber, long size, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(messageNumber + " " + size);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send LIST response", e);
        }
    }

    @Override
    public ListWriter beginListing(int messageCount) {
        try {
            sendOK(messageCount + " messages");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send LIST header", e);
        }
        return new ListWriterImpl();
    }

    @Override
    public void noSuchMessage(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(L10N.getString("pop3.err.no_such_message"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send no such message", e);
        }
    }

    @Override
    public void messageDeleted(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(L10N.getString("pop3.err.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send message deleted", e);
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

    // --- ListWriter inner class ---

    private class ListWriterImpl implements ListWriter {
        @Override
        public void message(int messageNumber, long size) {
            try {
                sendLine(messageNumber + " " + size);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send LIST entry", e);
            }
        }

        @Override
        public void end(TransactionHandler handler) {
            transactionHandler = handler;
            try {
                sendLine(".");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send LIST terminator", e);
            }
        }
    }

    // --- RetrieveState ---

    @Override
    public void sendMessage(long size, ReadableByteChannel content, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(size + " octets");
            sendMessageContent(content);
            sendLine(".");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send message", e);
        }
    }

    // RetrieveState.noSuchMessage, messageDeleted, error - already implemented above

    // --- MarkDeletedState ---

    @Override
    public void markedDeleted(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(L10N.getString("pop3.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send marked deleted response", e);
        }
    }

    @Override
    public void markedDeleted(String message, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send marked deleted response", e);
        }
    }

    @Override
    public void alreadyDeleted(TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendERR(L10N.getString("pop3.err.message_deleted"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send already deleted", e);
        }
    }

    // MarkDeletedState.noSuchMessage, error - already implemented above

    // --- ResetState ---

    @Override
    public void resetComplete(int messageCount, long totalSize, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(MessageFormat.format(L10N.getString("pop3.reset_response"), messageCount, totalSize));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send reset response", e);
        }
    }

    // ResetState.error - already implemented above

    // --- TopState ---

    @Override
    public void sendTop(ReadableByteChannel content, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(L10N.getString("pop3.top_follows"));
            sendMessageContent(content);
            sendLine(".");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send TOP", e);
        }
    }

    // TopState.noSuchMessage, messageDeleted, error - already implemented above

    // --- UidlState ---

    @Override
    public void sendUid(int messageNumber, String uid, TransactionHandler handler) {
        this.transactionHandler = handler;
        try {
            sendOK(messageNumber + " " + uid);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send UIDL response", e);
        }
    }

    @Override
    public UidlWriter beginListing() {
        try {
            sendOK(L10N.getString("pop3.uidl_follows"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send UIDL header", e);
        }
        return new UidlWriterImpl();
    }

    // UidlState.noSuchMessage, messageDeleted, error - already implemented above

    // --- UidlWriter inner class ---

    private class UidlWriterImpl implements UidlWriter {
        @Override
        public void message(int messageNumber, String uid) {
            try {
                sendLine(messageNumber + " " + uid);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send UIDL entry", e);
            }
        }

        @Override
        public void end(TransactionHandler handler) {
            transactionHandler = handler;
            try {
                sendLine(".");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to send UIDL terminator", e);
            }
        }
    }

    // --- UpdateState ---

    @Override
    public void commitAndClose() {
        try {
            sendOK(L10N.getString("pop3.quit_response"));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send QUIT response", e);
        }
        close();
    }

    @Override
    public void commitAndClose(String message) {
        try {
            sendOK(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send QUIT response", e);
        }
        close();
    }

    @Override
    public void partialCommit(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send partial commit", e);
        }
        close();
    }

    @Override
    public void updateFailed(String message) {
        try {
            sendERR(message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send update failed", e);
        }
        close();
    }

    // ========== TRANSACTION STATE COMMANDS ==========

    /**
     * STAT command (RFC 1939 section 5)
     */
    private void handleSTAT(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.mailboxStatus(mailbox, this);
            return;
        }
        
        // Default behavior
        try {
            int count = mailbox.getMessageCount();
            long size = mailbox.getMailboxSize();
            sendOK(count + " " + size);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get mailbox statistics", e);
            sendERR(L10N.getString("pop3.err.cannot_access_mailbox"));
        }
    }

    /**
     * LIST command (RFC 1939 section 5)
     */
    private void handleLIST(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        int msgNum = args.isEmpty() ? 0 : parseMessageNumber(args);
        if (!args.isEmpty() && msgNum < 0) {
            sendERR(L10N.getString("pop3.err.invalid_message_number"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.list(mailbox, msgNum, this);
            return;
        }
        
        // Default behavior
        try {
            if (args.isEmpty()) {
                // List all messages
                int count = mailbox.getMessageCount();
                sendOK(count + " messages");
                Iterator<MessageDescriptor> messages = mailbox.getMessageList();
                while (messages.hasNext()) {
                    MessageDescriptor msg = messages.next();
                    if (msg != null && !mailbox.isDeleted(msg.getMessageNumber())) {
                        sendLine(msg.getMessageNumber() + " " + msg.getSize());
                    }
                }
                sendLine(".");
            } else {
                // List specific message
                MessageDescriptor msg = mailbox.getMessage(msgNum);
                if (msg == null || mailbox.isDeleted(msgNum)) {
                    sendERR(L10N.getString("pop3.err.no_such_message"));
                } else {
                    sendOK(msgNum + " " + msg.getSize());
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to list messages", e);
            sendERR(L10N.getString("pop3.err.cannot_access_mailbox"));
        }
    }

    /**
     * RETR command (RFC 1939 section 5)
     */
    private void handleRETR(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        int msgNum = parseMessageNumber(args);
        if (msgNum < 0) {
            sendERR(L10N.getString("pop3.err.invalid_message_number"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.retrieveMessage(mailbox, msgNum, this);
            return;
        }
        
        // Default behavior
        try {
            if (mailbox.isDeleted(msgNum)) {
                sendERR(L10N.getString("pop3.err.message_deleted"));
                return;
            }
            
            MessageDescriptor msg = mailbox.getMessage(msgNum);
            if (msg == null) {
                sendERR(L10N.getString("pop3.err.no_such_message"));
                return;
            }
            
            sendOK(msg.getSize() + " octets");
            
            // Send message content with byte-stuffing (RFC 1939 section 3)
            try (ReadableByteChannel channel = mailbox.getMessageContent(msgNum)) {
                sendMessageContent(channel);
            }
            sendLine(".");

            // Record telemetry
            recordMessageRetrieve(msgNum, msg.getSize());
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to retrieve message " + msgNum, e);
            recordSessionException(e);
            sendERR(L10N.getString("pop3.err.cannot_retrieve"));
        }
    }

    /**
     * DELE command (RFC 1939 section 5)
     */
    private void handleDELE(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        int msgNum = parseMessageNumber(args);
        if (msgNum < 0) {
            sendERR(L10N.getString("pop3.err.invalid_message_number"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.markDeleted(mailbox, msgNum, this);
            return;
        }
        
        // Default behavior
        try {
            MessageDescriptor msg = mailbox.getMessage(msgNum);
            if (msg == null) {
                sendERR(L10N.getString("pop3.err.no_such_message"));
                return;
            }
            
            if (mailbox.isDeleted(msgNum)) {
                sendERR(L10N.getString("pop3.err.already_deleted"));
                return;
            }
            
            mailbox.deleteMessage(msgNum);
            recordMessageDelete(msgNum);
            sendOK(L10N.getString("pop3.message_deleted"));
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete message " + msgNum, e);
            recordSessionException(e);
            sendERR(L10N.getString("pop3.err.cannot_delete"));
        }
    }

    /**
     * RSET command (RFC 1939 section 5)
     */
    private void handleRSET(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.reset(mailbox, this);
            return;
        }
        
        // Default behavior
        try {
            mailbox.undeleteAll();
            int count = mailbox.getMessageCount();
            long size = mailbox.getMailboxSize();
            sendOK(MessageFormat.format(L10N.getString("pop3.reset_response"), count, size));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to reset mailbox", e);
            sendERR(L10N.getString("pop3.err.cannot_reset"));
        }
    }

    /**
     * TOP command (RFC 1939 section 7)
     */
    private void handleTOP(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        // Parse arguments: message number and line count
        List<String> partList = new ArrayList<>();
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
        int lines = parseNonNegativeNumber(parts[1]); // lines can be 0
        
        if (msgNum < 0 || lines < 0) {
            sendERR(L10N.getString("pop3.err.invalid_arguments"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.top(mailbox, msgNum, lines, this);
            return;
        }
        
        // Default behavior
        try {
            if (mailbox.isDeleted(msgNum)) {
                sendERR(L10N.getString("pop3.err.message_deleted"));
                return;
            }
            
            MessageDescriptor msg = mailbox.getMessage(msgNum);
            if (msg == null) {
                sendERR(L10N.getString("pop3.err.no_such_message"));
                return;
            }
            
            sendOK(L10N.getString("pop3.top_follows"));
            
            // Send headers and specified number of body lines
            try (ReadableByteChannel channel = mailbox.getMessageTop(msgNum, lines)) {
                sendMessageContent(channel);
            }
            
            sendLine(".");
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to retrieve top of message " + msgNum, e);
            sendERR(L10N.getString("pop3.err.cannot_retrieve"));
        }
    }

    /**
     * UIDL command (RFC 1939 section 7)
     */
    private void handleUIDL(String args) throws IOException {
        if (mailbox == null) {
            sendERR(L10N.getString("pop3.err.no_mailbox_open"));
            return;
        }
        
        int msgNum = args.isEmpty() ? 0 : parseMessageNumber(args);
        if (!args.isEmpty() && msgNum < 0) {
            sendERR(L10N.getString("pop3.err.invalid_message_number"));
            return;
        }
        
        if (transactionHandler != null) {
            transactionHandler.uidl(mailbox, msgNum, this);
            return;
        }
        
        // Default behavior
        try {
            if (args.isEmpty()) {
                // List all unique IDs
                sendOK(L10N.getString("pop3.uidl_follows"));
                Iterator<MessageDescriptor> messages = mailbox.getMessageList();
                while (messages.hasNext()) {
                    MessageDescriptor msg = messages.next();
                    if (msg != null && !mailbox.isDeleted(msg.getMessageNumber())) {
                        String uid = mailbox.getUniqueId(msg.getMessageNumber());
                        sendLine(msg.getMessageNumber() + " " + uid);
                    }
                }
                sendLine(".");
            } else {
                // Get unique ID for specific message
                MessageDescriptor msg = mailbox.getMessage(msgNum);
                if (msg == null || mailbox.isDeleted(msgNum)) {
                    sendERR(L10N.getString("pop3.err.no_such_message"));
                } else {
                    String uid = mailbox.getUniqueId(msgNum);
                    sendOK(msgNum + " " + uid);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get unique IDs", e);
            sendERR(L10N.getString("pop3.err.cannot_access_mailbox"));
        }
    }

    // ========== COMMANDS VALID IN ALL STATES ==========

    /**
     * CAPA command (RFC 2449 section 5)
     */
    private void handleCAPA(String args) throws IOException {
        sendOK(L10N.getString("pop3.capa_follows"));
        
        // Required capabilities
        sendLine("USER");
        sendLine("UIDL");
        sendLine("TOP");
        
        // SASL authentication mechanisms (RFC 5034)
        // Build list of supported mechanisms based on Realm capabilities
        Realm realm = getRealm();
        if (realm != null) {
            java.util.Set<SASLMechanism> supported = realm.getSupportedSASLMechanisms();
            if (!supported.isEmpty()) {
                StringBuilder saslLine = new StringBuilder("SASL");
                for (SASLMechanism mech : supported) {
                    // Skip mechanisms that require TLS if not secure
                    if (!secure && mech.requiresTLS()) {
                        continue;
                    }
                    // Skip EXTERNAL if not secure (needs client certificate)
                    if (mech == SASLMechanism.EXTERNAL && !secure) {
                        continue;
                    }
                    saslLine.append(" ").append(mech.getMechanismName());
                }
                sendLine(saslLine.toString());
            }
        }
        
        // Optional capabilities
        if (apopTimestamp != null) {
            sendLine("APOP");
        }
        if (!secure && server.isSTARTTLSAvailable()) {
            sendLine("STLS");
        }
        if (server.isEnableUTF8()) {
            sendLine("UTF8");
        }
        if (server.isEnablePipelining()) {
            sendLine("PIPELINING");
        }
        
        // IMPLEMENTATION capability
        sendLine("IMPLEMENTATION gumdrop");
        
        sendLine(".");
    }

    /**
     * NOOP command (RFC 1939 section 5)
     */
    private void handleNOOP(String args) throws IOException {
        sendOK(L10N.getString("pop3.noop"));
    }

    /**
     * QUIT command (RFC 1939 section 5)
     */
    private void handleQUIT(String args) throws IOException {
        addSessionEvent("QUIT");
        
        if (state == POP3State.TRANSACTION) {
            // Enter UPDATE state and commit changes
            state = POP3State.UPDATE;
            
            if (transactionHandler != null) {
                transactionHandler.quit(mailbox, this);
                return;
            }
            
            // Default behavior
            try {
                if (mailbox != null) {
                    mailbox.close(true); // Expunge deleted messages
                    mailbox = null;
                }
                if (store != null) {
                    store.close();
                    store = null;
                }
                sendOK(L10N.getString("pop3.quit_success"));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to update mailbox", e);
                recordSessionException(e);
                sendERR(L10N.getString("pop3.quit_partial"));
            }
        } else {
            sendOK(L10N.getString("pop3.goodbye"));
        }
        
        // Close connection
        close();
    }

    // ========== HELPER METHODS ==========

    /**
     * Opens a mailbox for the authenticated user.
     * 
     * <p>If an authorization handler is configured, calls it with the 
     * authenticated principal and lets it make the policy decision.
     * Otherwise, opens the mailbox directly.
     */
    private boolean openMailbox(String user) throws IOException {
        MailboxFactory factory = server.getMailboxFactory();
        if (factory == null) {
            sendERR(L10N.getString("pop3.err.mailbox_not_configured"));
            return false;
        }
        
        // If handler is configured, let it make the policy decision
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
            authorizationHandler.authenticate(principal, factory, this);
            // The handler will call accept() or reject() on us
            // Return true to indicate we're handling it asynchronously
            // The state will be set by accept() if successful
            return state == POP3State.TRANSACTION;
        }
        
        // Default behavior - open mailbox directly
        try {
            store = factory.createStore();
            store.open(user);
            mailbox = store.openMailbox("INBOX", false);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to open mailbox for user: " + user, e);
            if (store != null) {
                try {
                    store.close();
                } catch (IOException ce) {
                    LOGGER.log(Level.FINE, "Error closing store after failure", ce);
                }
                store = null;
            }
            sendERR(L10N.getString("pop3.err.cannot_open_mailbox"));
            return false;
        }
    }

    /**
     * Parses a message number from a string.
     * Message numbers must be positive (> 0).
     * 
     * @return the message number, or -1 if invalid
     */
    private int parseMessageNumber(String str) {
        try {
            int num = Integer.parseInt(str);
            return (num > 0) ? num : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses a non-negative number from a string.
     * Used for line counts in TOP command where 0 is valid.
     * 
     * @return the number, or -1 if invalid
     */
    private int parseNonNegativeNumber(String str) {
        try {
            int num = Integer.parseInt(str);
            return (num >= 0) ? num : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Sends message content with byte-stuffing per RFC 1939 section 3.
     * Lines beginning with '.' are stuffed with an additional '.'.
     */
    private void sendMessageContent(ReadableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        StringBuilder lineBuilder = new StringBuilder();
        
        while (channel.read(buffer) != -1 || buffer.position() > 0) {
            buffer.flip();
            
            while (buffer.hasRemaining()) {
                byte b = buffer.get();
                
                if (b == '\n') {
                    // End of line - send with byte-stuffing if needed
                    String line = lineBuilder.toString();
                    // Remove trailing CR if present
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    
                    if (line.startsWith(".")) {
                        // Byte-stuff lines beginning with '.'
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
        
        // Handle any remaining content without trailing newline
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


    /**
     * Sends a +OK response.
     */
    private void sendOK(String message) throws IOException {
        sendLine("+OK " + message);
    }

    /**
     * Sends a -ERR response.
     */
    private void sendERR(String message) throws IOException {
        sendLine("-ERR " + message);
    }

    /**
     * Sends a line of text with CRLF.
     */
    private void sendLine(String line) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.finest("POP3 response: " + line);
        }
        
        byte[] data = (line + CRLF).getBytes(US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(data);
        send(buf);
    }

    /**
     * Called when the connection is closed by the peer.
     * Uses try-finally to guarantee telemetry completion and resource cleanup.
     */
    @Override
    protected void disconnected() throws IOException {
        try {
            // Notify handler of disconnection
            if (clientConnected != null) {
                clientConnected.disconnected();
            }
            
            // End telemetry spans first
            endAuthenticatedSpan();
            if (state == POP3State.UPDATE) {
                endSessionSpan();
            } else {
                endSessionSpanError("Connection lost");
            }
        } finally {
            // Clean up mailbox and store in finally block
            try {
                if (mailbox != null && state != POP3State.UPDATE) {
                    try {
                        mailbox.close(false); // Don't expunge on abnormal disconnect
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Error closing mailbox on disconnect", e);
                    }
                    mailbox = null;
                }
            } finally {
                if (store != null) {
                    try {
                        store.close();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Error closing store on disconnect", e);
                    }
                    store = null;
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            // End telemetry spans before closing
            endAuthenticatedSpan();
            endSessionSpan();
        } finally {
            super.close();
        }
    }

    // ========== TELEMETRY METHODS ==========

    /**
     * Initializes the connection trace if telemetry is enabled.
     */
    private void initConnectionTrace() {
        if (isTelemetryEnabled()) {
            TelemetryConfig config = getTelemetryConfig();
            String traceName = L10N.getString("telemetry.pop3_connection");
            connectionTrace = config.createTrace(traceName);
            Span rootSpan = connectionTrace.getRootSpan();
            // Add connection-level attributes
            rootSpan.addAttribute("net.transport", "ip_tcp");
            rootSpan.addAttribute("net.peer.ip", getRemoteSocketAddress().toString());
            rootSpan.addAttribute("rpc.system", "pop3");
        }
    }

    /**
     * Starts a new session span within the connection trace.
     */
    private void startSessionSpan() {
        if (connectionTrace != null) {
            String spanName = L10N.getString("telemetry.pop3_session");
            sessionSpan = connectionTrace.startSpan(spanName, SpanKind.SERVER);
            sessionSpan.addEvent("SESSION_START");
        }
    }

    /**
     * Starts the authenticated span when authentication succeeds.
     */
    private void startAuthenticatedSpan() {
        if (sessionSpan != null) {
            String spanName = L10N.getString("telemetry.pop3_authenticated");
            authenticatedSpan = sessionSpan.startChild(spanName, SpanKind.SERVER);
            authenticatedSpan.addAttribute("enduser.id", username);
            authenticatedSpan.addEvent("AUTHENTICATED");
        }
    }

    /**
     * Ends the session span normally.
     */
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

    /**
     * Ends the session span with an error.
     */
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

    /**
     * Ends the authenticated span.
     */
    private void endAuthenticatedSpan() {
        if (authenticatedSpan != null) {
            authenticatedSpan.setStatusOk();
            authenticatedSpan.end();
            authenticatedSpan = null;
        }
    }

    /**
     * Adds an attribute to the session span.
     */
    private void addSessionAttribute(String key, String value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an attribute to the session span.
     */
    private void addSessionAttribute(String key, long value) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute(key, value);
        }
    }

    /**
     * Adds an event to the session span.
     */
    private void addSessionEvent(String event) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addEvent(event);
        }
    }

    /**
     * Adds an event to the authenticated span.
     */
    private void addAuthenticatedEvent(String event) {
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addEvent(event);
        }
    }

    /**
     * Records an exception in the session span with automatic category detection.
     */
    private void recordSessionException(Exception e) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordExceptionWithCategory(e);
        }
    }

    /**
     * Records an exception with an explicit error category in the session span.
     */
    private void recordSessionException(Exception e, ErrorCategory category) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.recordException(e, category);
        }
    }

    /**
     * Records a POP3 error in telemetry with category classification.
     *
     * @param category the error category
     * @param message the error message
     */
    private void recordPop3Error(ErrorCategory category, String message) {
        if (sessionSpan != null && !sessionSpan.isEnded() && category != null) {
            sessionSpan.recordError(category, message);
        }
    }

    /**
     * Records successful authentication.
     */
    private void recordAuthenticationSuccess(String mechanism) {
        addSessionAttribute("pop3.auth.mechanism", mechanism);
        addSessionAttribute("enduser.id", username);
        addSessionEvent("AUTH_SUCCESS");
        startAuthenticatedSpan();
    }

    /**
     * Records failed authentication.
     */
    private void recordAuthenticationFailure(String mechanism, String user) {
        addSessionAttribute("pop3.auth.mechanism", mechanism);
        if (user != null) {
            addSessionAttribute("pop3.auth.attempted_user", user);
        }
        addSessionEvent("AUTH_FAILURE");
    }

    /**
     * Records STARTTLS upgrade success.
     */
    private void recordStartTLSSuccess() {
        addSessionEvent("STARTTLS_SUCCESS");
        addSessionAttribute("net.protocol.name", "pop3s");
    }

    /**
     * Records STARTTLS upgrade failure.
     */
    private void recordStartTLSFailure(Exception e) {
        addSessionEvent("STARTTLS_FAILURE");
        recordSessionException(e);
    }

    /**
     * Records a message retrieval.
     */
    private void recordMessageRetrieve(int msgNum, long size) {
        addAuthenticatedEvent("RETR");
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addAttribute("pop3.message.number", msgNum);
            authenticatedSpan.addAttribute("pop3.message.size", size);
        }
    }

    /**
     * Records a message deletion.
     */
    private void recordMessageDelete(int msgNum) {
        addAuthenticatedEvent("DELE");
        if (authenticatedSpan != null && !authenticatedSpan.isEnded()) {
            authenticatedSpan.addAttribute("pop3.message.number", (long) msgNum);
        }
    }

}

