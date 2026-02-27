/*
 * IMAPClientProtocolHandler.java
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

package org.bluezoo.gumdrop.imap.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.LineParser;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.imap.client.handler.ClientAppendState;
import org.bluezoo.gumdrop.imap.client.handler.ClientAuthExchange;
import org.bluezoo.gumdrop.imap.client.handler.ClientAuthenticatedState;
import org.bluezoo.gumdrop.imap.client.handler.ClientIdleState;
import org.bluezoo.gumdrop.imap.client.handler.ClientNotAuthenticatedState;
import org.bluezoo.gumdrop.imap.client.handler.ClientPostStarttls;
import org.bluezoo.gumdrop.imap.client.handler.ClientSelectedState;
import org.bluezoo.gumdrop.imap.client.handler.MailboxEventListener;
import org.bluezoo.gumdrop.imap.client.handler.ServerAppendReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerAuthAbortHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerAuthReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerCapabilityReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerCloseReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerCopyReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerExpungeReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerFetchReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerGreeting;
import org.bluezoo.gumdrop.imap.client.handler.ServerIdleEventHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerListReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerLoginReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerMailboxReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerNamespaceReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerNoopReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerSearchReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerSelectReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerStarttlsReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerStatusReplyHandler;
import org.bluezoo.gumdrop.imap.client.handler.ServerStoreReplyHandler;

/**
 * IMAP client protocol handler implementing {@link ProtocolHandler}.
 *
 * <p>Implements a type-safe IMAP client state machine with staged
 * callback interfaces constraining which operations are valid at
 * each protocol stage. Tagged command tracking ensures exactly one
 * command is in-flight at a time.
 *
 * <p>Line parsing is handled by the composable {@link LineParser} utility.
 * Incoming literal data (FETCH body sections) is tracked by
 * {@link LiteralTracker} and streamed as ByteBuffer chunks.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see ServerGreeting
 */
public class IMAPClientProtocolHandler
        implements ProtocolHandler, LineParser.Callback,
        LiteralTracker.Callback,
        ClientNotAuthenticatedState, ClientPostStarttls,
        ClientAuthExchange, ClientAuthenticatedState,
        ClientSelectedState, ClientIdleState,
        ClientAppendState {

    private static final Logger LOGGER =
            Logger.getLogger(
                    IMAPClientProtocolHandler.class.getName());

    private static final String CRLF = "\r\n";

    private final ServerGreeting handler;
    private final IMAPTagGenerator tagGenerator;

    private Endpoint endpoint;
    private IMAPState state = IMAPState.DISCONNECTED;
    private boolean secure;

    private String currentTag;
    private Object currentCallback;

    // Whether we were in SELECTED state before issuing a command
    private boolean wasSelected;

    // Unsolicited event listener
    private MailboxEventListener mailboxEventListener;

    // Capabilities from CAPABILITY response or greeting
    private List<String> capabilities;

    // SELECT/EXAMINE accumulation
    private MailboxInfo pendingMailboxInfo;

    // LIST/LSUB streaming
    // (entries delivered as they arrive)

    // SEARCH result accumulation
    private List<Long> searchResults;

    // FETCH state
    private int fetchMessageNumber;
    private FetchData fetchData;
    private String fetchLiteralSection;
    private LiteralTracker literalTracker;

    // IDLE event handler (separate from currentCallback for clarity)
    private ServerIdleEventHandler idleEventHandler;

    // STORE accumulation
    // (responses streamed as they arrive)

    // EXPUNGE accumulation
    // (responses streamed as they arrive)

    // NAMESPACE/STATUS/COPY response data
    private String namespacePersonal;
    private String namespacePersonalDelimiter;

    private String statusMailbox;
    private int statusMessages;
    private int statusRecent;
    private long statusUidNext;
    private long statusUidValidity;
    private int statusUnseen;

    private long copyUidValidity;
    private String copySourceUids;
    private String copyDestUids;

    /**
     * Creates an IMAP client protocol handler.
     *
     * @param handler the server greeting handler
     */
    public IMAPClientProtocolHandler(ServerGreeting handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.tagGenerator = new IMAPTagGenerator();
        this.capabilities = new ArrayList<String>();
        this.searchResults = new ArrayList<Long>();
    }

    /**
     * Sets whether this connection started in secure mode.
     *
     * @param secure true for implicit TLS (IMAPS port 993)
     */
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets the listener for unsolicited mailbox events.
     *
     * @param listener the event listener, or null to clear
     */
    public void setMailboxEventListener(MailboxEventListener listener) {
        this.mailboxEventListener = listener;
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint ep) {
        this.endpoint = ep;
        state = IMAPState.CONNECTING;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("IMAP client connected to "
                    + ep.getRemoteAddress());
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        while (data.hasRemaining()) {
            if (literalTracker != null
                    && !literalTracker.isComplete()) {
                literalTracker.process(data);
                continue;
            }
            int posBefore = data.position();
            LineParser.parse(data, this);
            if (data.position() == posBefore) {
                break;
            }
        }
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("IMAP client disconnected");
        }
        state = IMAPState.CLOSED;
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("TLS established: " + info.getCipherSuite());
        }

        handler.onSecurityEstablished(info);

        if (currentCallback instanceof ServerStarttlsReplyHandler) {
            ServerStarttlsReplyHandler callback =
                    (ServerStarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = IMAPState.NOT_AUTHENTICATED;
            callback.handleTlsEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "IMAP transport error", cause);
        }
        state = IMAPState.ERROR;
        handler.onError(cause);
    }

    // ── LineParser.Callback ──

    @Override
    public void lineReceived(ByteBuffer line) {
        int len = line.remaining();
        if (len < 2) {
            return;
        }
        byte[] bytes = new byte[len - 2];
        line.get(bytes);
        line.get(); // CR
        line.get(); // LF
        String text = new String(bytes, StandardCharsets.US_ASCII);
        handleResponseLine(text);
    }

    @Override
    public boolean continueLineProcessing() {
        return literalTracker == null || literalTracker.isComplete();
    }

    // ── LiteralTracker.Callback ──

    @Override
    public void literalContent(ByteBuffer data) {
        if (currentCallback instanceof ServerFetchReplyHandler) {
            ((ServerFetchReplyHandler) currentCallback)
                    .handleFetchLiteralContent(data);
        }
    }

    @Override
    public void literalComplete() {
        if (currentCallback instanceof ServerFetchReplyHandler) {
            ((ServerFetchReplyHandler) currentCallback)
                    .handleFetchLiteralEnd(fetchMessageNumber);
        }
        state = IMAPState.FETCH_SENT;
        literalTracker = null;
    }

    // ── Connection state ──

    public boolean isOpen() {
        return endpoint != null && endpoint.isOpen()
                && state != IMAPState.DISCONNECTED
                && state != IMAPState.CLOSED
                && state != IMAPState.ERROR;
    }

    public void close() {
        if (state == IMAPState.CLOSED) {
            return;
        }
        state = IMAPState.CLOSED;
        literalTracker = null;
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ClientNotAuthenticatedState ──

    @Override
    public void capability(ServerCapabilityReplyHandler callback) {
        this.currentCallback = callback;
        capabilities.clear();
        sendTaggedCommand("CAPABILITY", IMAPState.CAPABILITY_SENT);
    }

    @Override
    public void login(String username, String password,
                      ServerLoginReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("LOGIN " + quoteString(username) + " "
                + quoteString(password), IMAPState.LOGIN_SENT);
    }

    @Override
    public void authenticate(String mechanism, byte[] initialResponse,
                             ServerAuthReplyHandler callback) {
        this.currentCallback = callback;
        StringBuilder cmd = new StringBuilder("AUTHENTICATE ");
        cmd.append(mechanism);
        if (initialResponse != null) {
            cmd.append(" ");
            cmd.append(Base64.getEncoder()
                    .encodeToString(initialResponse));
        }
        sendTaggedCommand(cmd.toString(), IMAPState.AUTHENTICATE_SENT);
    }

    @Override
    public void starttls(ServerStarttlsReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("STARTTLS", IMAPState.STARTTLS_SENT);
    }

    @Override
    public void logout() {
        sendTaggedCommand("LOGOUT", IMAPState.LOGOUT_SENT);
    }

    // ── ClientPostStarttls ──
    // capability, login, authenticate, logout already defined above

    // ── ClientAuthExchange ──

    @Override
    public void respond(byte[] response,
                        ServerAuthReplyHandler callback) {
        this.currentCallback = callback;
        String encoded = Base64.getEncoder().encodeToString(response);
        sendRawLine(encoded, IMAPState.AUTHENTICATE_SENT);
    }

    @Override
    public void abort(ServerAuthAbortHandler callback) {
        this.currentCallback = callback;
        sendRawLine("*", IMAPState.AUTH_ABORT_SENT);
    }

    // ── ClientAuthenticatedState ──

    @Override
    public void select(String mailbox,
                       ServerSelectReplyHandler callback) {
        this.currentCallback = callback;
        pendingMailboxInfo = new MailboxInfo();
        sendTaggedCommand("SELECT " + quoteString(mailbox),
                IMAPState.SELECT_SENT);
    }

    @Override
    public void examine(String mailbox,
                        ServerSelectReplyHandler callback) {
        this.currentCallback = callback;
        pendingMailboxInfo = new MailboxInfo();
        sendTaggedCommand("EXAMINE " + quoteString(mailbox),
                IMAPState.EXAMINE_SENT);
    }

    @Override
    public void create(String mailbox,
                       ServerMailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("CREATE " + quoteString(mailbox),
                IMAPState.CREATE_SENT);
    }

    @Override
    public void delete(String mailbox,
                       ServerMailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("DELETE " + quoteString(mailbox),
                IMAPState.DELETE_SENT);
    }

    @Override
    public void rename(String from, String to,
                       ServerMailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("RENAME " + quoteString(from) + " "
                + quoteString(to), IMAPState.RENAME_SENT);
    }

    @Override
    public void subscribe(String mailbox,
                          ServerMailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("SUBSCRIBE " + quoteString(mailbox),
                IMAPState.SUBSCRIBE_SENT);
    }

    @Override
    public void unsubscribe(String mailbox,
                            ServerMailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("UNSUBSCRIBE " + quoteString(mailbox),
                IMAPState.UNSUBSCRIBE_SENT);
    }

    @Override
    public void list(String reference, String pattern,
                     ServerListReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("LIST " + quoteString(reference) + " "
                + quoteString(pattern), IMAPState.LIST_SENT);
    }

    @Override
    public void lsub(String reference, String pattern,
                     ServerListReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("LSUB " + quoteString(reference) + " "
                + quoteString(pattern), IMAPState.LSUB_SENT);
    }

    @Override
    public void status(String mailbox, String[] items,
                       ServerStatusReplyHandler callback) {
        this.currentCallback = callback;
        resetStatusData();
        StringBuilder cmd = new StringBuilder("STATUS ");
        cmd.append(quoteString(mailbox));
        cmd.append(" (");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                cmd.append(' ');
            }
            cmd.append(items[i]);
        }
        cmd.append(')');
        sendTaggedCommand(cmd.toString(), IMAPState.STATUS_SENT);
    }

    @Override
    public void namespace(ServerNamespaceReplyHandler callback) {
        this.currentCallback = callback;
        namespacePersonal = null;
        namespacePersonalDelimiter = null;
        sendTaggedCommand("NAMESPACE", IMAPState.NAMESPACE_SENT);
    }

    @Override
    public void append(String mailbox, String[] flags, String date,
                       long size, ServerAppendReplyHandler callback) {
        this.currentCallback = callback;
        StringBuilder cmd = new StringBuilder("APPEND ");
        cmd.append(quoteString(mailbox));
        if (flags != null && flags.length > 0) {
            cmd.append(" (");
            for (int i = 0; i < flags.length; i++) {
                if (i > 0) {
                    cmd.append(' ');
                }
                cmd.append(flags[i]);
            }
            cmd.append(')');
        }
        if (date != null) {
            cmd.append(" \"").append(date).append('"');
        }
        cmd.append(" {").append(size).append('}');
        sendTaggedCommand(cmd.toString(), IMAPState.APPEND_SENT);
    }

    @Override
    public void idle(ServerIdleEventHandler callback) {
        this.currentCallback = callback;
        this.idleEventHandler = callback;
        sendTaggedCommand("IDLE", IMAPState.IDLE_SENT);
    }

    @Override
    public void noop(ServerNoopReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("NOOP", IMAPState.NOOP_SENT);
    }

    // ── ClientSelectedState ──

    @Override
    public void close(ServerCloseReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("CLOSE", IMAPState.CLOSE_SENT);
    }

    @Override
    public void unselect(ServerCloseReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("UNSELECT", IMAPState.UNSELECT_SENT);
    }

    @Override
    public void expunge(ServerExpungeReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("EXPUNGE", IMAPState.EXPUNGE_SENT);
    }

    @Override
    public void search(String criteria,
                       ServerSearchReplyHandler callback) {
        this.currentCallback = callback;
        searchResults.clear();
        sendTaggedCommand("SEARCH " + criteria,
                IMAPState.SEARCH_SENT);
    }

    @Override
    public void uidSearch(String criteria,
                          ServerSearchReplyHandler callback) {
        this.currentCallback = callback;
        searchResults.clear();
        sendTaggedCommand("UID SEARCH " + criteria,
                IMAPState.SEARCH_SENT);
    }

    @Override
    public void fetch(String sequenceSet, String dataItems,
                      ServerFetchReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("FETCH " + sequenceSet + " " + dataItems,
                IMAPState.FETCH_SENT);
    }

    @Override
    public void uidFetch(String sequenceSet, String dataItems,
                         ServerFetchReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("UID FETCH " + sequenceSet + " " + dataItems,
                IMAPState.FETCH_SENT);
    }

    @Override
    public void store(String sequenceSet, String action,
                      String[] flags,
                      ServerStoreReplyHandler callback) {
        this.currentCallback = callback;
        StringBuilder cmd = new StringBuilder("STORE ");
        cmd.append(sequenceSet).append(' ').append(action).append(" (");
        for (int i = 0; i < flags.length; i++) {
            if (i > 0) {
                cmd.append(' ');
            }
            cmd.append(flags[i]);
        }
        cmd.append(')');
        sendTaggedCommand(cmd.toString(), IMAPState.STORE_SENT);
    }

    @Override
    public void uidStore(String sequenceSet, String action,
                         String[] flags,
                         ServerStoreReplyHandler callback) {
        this.currentCallback = callback;
        StringBuilder cmd = new StringBuilder("UID STORE ");
        cmd.append(sequenceSet).append(' ').append(action).append(" (");
        for (int i = 0; i < flags.length; i++) {
            if (i > 0) {
                cmd.append(' ');
            }
            cmd.append(flags[i]);
        }
        cmd.append(')');
        sendTaggedCommand(cmd.toString(), IMAPState.STORE_SENT);
    }

    @Override
    public void copy(String sequenceSet, String mailbox,
                     ServerCopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("COPY " + sequenceSet + " "
                + quoteString(mailbox), IMAPState.COPY_SENT);
    }

    @Override
    public void uidCopy(String sequenceSet, String mailbox,
                        ServerCopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("UID COPY " + sequenceSet + " "
                + quoteString(mailbox), IMAPState.COPY_SENT);
    }

    @Override
    public void move(String sequenceSet, String mailbox,
                     ServerCopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("MOVE " + sequenceSet + " "
                + quoteString(mailbox), IMAPState.MOVE_SENT);
    }

    @Override
    public void uidMove(String sequenceSet, String mailbox,
                        ServerCopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("UID MOVE " + sequenceSet + " "
                + quoteString(mailbox), IMAPState.MOVE_SENT);
    }

    // ── ClientIdleState ──

    @Override
    public void done() {
        sendRawLine("DONE", state);
    }

    // ── ClientAppendState ──

    @Override
    public void writeContent(ByteBuffer data) {
        if (state != IMAPState.APPEND_DATA || !isOpen()) {
            return;
        }
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        endpoint.send(copy);
    }

    @Override
    public void endAppend() {
        if (state != IMAPState.APPEND_DATA || !isOpen()) {
            return;
        }
        byte[] crlf = CRLF.getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(crlf));
    }

    // ── Command sending ──

    private void sendTaggedCommand(String command,
                                   IMAPState newState) {
        if (!isOpen()) {
            handler.onError(new IOException("Not connected"));
            return;
        }

        // Track whether we were in SELECTED before sending
        wasSelected = (state == IMAPState.SELECTED);

        currentTag = tagGenerator.next();
        state = newState;

        String line = currentTag + " " + command + CRLF;
        byte[] data = line.getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(data));

        if (LOGGER.isLoggable(Level.FINE)) {
            if (command.startsWith("LOGIN ")
                    || command.startsWith("AUTHENTICATE ")) {
                int sp = command.indexOf(' ');
                LOGGER.fine("Sent IMAP command: " + currentTag + " "
                        + command.substring(0, sp + 1) + "***");
            } else {
                LOGGER.fine("Sent IMAP command: " + currentTag + " "
                        + command);
            }
        }
    }

    private void sendRawLine(String line, IMAPState newState) {
        if (!isOpen()) {
            handler.onError(new IOException("Not connected"));
            return;
        }

        state = newState;

        byte[] data = (line + CRLF).getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(data));

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Sent IMAP raw line: ***");
        }
    }

    // ── Response handling ──

    private void handleResponseLine(String line) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Received IMAP response: " + line);
        }

        try {
            IMAPResponse response = IMAPResponse.parse(line);
            if (response == null) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(
                            "Unparseable IMAP response: " + line);
                }
                return;
            }

            if (response.isContinuation()) {
                dispatchContinuation(response);
            } else if (response.isUntagged()) {
                dispatchUntagged(response, line);
            } else if (response.isTagged()) {
                dispatchTagged(response);
            }
        } catch (Exception e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING,
                        "Error handling IMAP response: " + line, e);
            }
            handler.onError(e);
        }
    }

    // ── Continuation dispatch ──

    private void dispatchContinuation(IMAPResponse response) {
        switch (state) {
            case AUTHENTICATE_SENT: {
                ServerAuthReplyHandler callback =
                        (ServerAuthReplyHandler) currentCallback;
                String challengeData = response.getMessage();
                byte[] challenge;
                if (challengeData == null
                        || challengeData.isEmpty()) {
                    challenge = new byte[0];
                } else {
                    challenge = Base64.getDecoder()
                            .decode(challengeData);
                }
                callback.handleChallenge(challenge, this);
                break;
            }
            case APPEND_SENT: {
                state = IMAPState.APPEND_DATA;
                ServerAppendReplyHandler callback =
                        (ServerAppendReplyHandler) currentCallback;
                callback.handleReadyForData(this);
                break;
            }
            case IDLE_SENT: {
                state = IMAPState.IDLE_ACTIVE;
                ServerIdleEventHandler callback =
                        (ServerIdleEventHandler) currentCallback;
                callback.handleIdleStarted(this);
                break;
            }
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(
                            "Unexpected continuation in state "
                            + state + ": " + response);
                }
        }
    }

    // ── Untagged dispatch ──

    private void dispatchUntagged(IMAPResponse response,
                                  String rawLine) {
        String msg = response.getMessage();
        if (msg == null) {
            return;
        }

        if (state == IMAPState.CONNECTING) {
            dispatchGreeting(response);
            return;
        }

        if (response.getStatus() != null) {
            dispatchUntaggedStatus(response);
            return;
        }

        String upper = msg.toUpperCase();

        if (upper.startsWith("CAPABILITY ")) {
            parseCapabilities(msg.substring(11));
            return;
        }

        if (upper.startsWith("BYE ")) {
            dispatchBye(msg.substring(4));
            return;
        }

        // Numeric untagged responses: * n TYPE ...
        int sp = msg.indexOf(' ');
        if (sp > 0) {
            try {
                int number = Integer.parseInt(msg.substring(0, sp));
                String rest = msg.substring(sp + 1);
                dispatchNumericUntagged(number, rest, rawLine);
                return;
            } catch (NumberFormatException e) {
                // Not numeric, fall through
            }
        }

        // Command-specific untagged data
        if (upper.startsWith("LIST ") || upper.startsWith("LSUB ")) {
            dispatchListLine(msg);
            return;
        }

        if (upper.startsWith("STATUS ")) {
            dispatchStatusLine(msg.substring(7));
            return;
        }

        if (upper.startsWith("SEARCH")) {
            dispatchSearchLine(msg);
            return;
        }

        if (upper.startsWith("NAMESPACE ")) {
            dispatchNamespaceLine(msg.substring(10));
            return;
        }

        if (upper.startsWith("FLAGS ")) {
            if (pendingMailboxInfo != null) {
                pendingMailboxInfo.setFlags(
                        parseFlags(msg.substring(6)));
            }
            return;
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Unhandled untagged response: " + msg);
        }
    }

    private void dispatchGreeting(IMAPResponse response) {
        String msg = response.getMessage();

        if (response.getStatus() == IMAPResponse.Status.OK) {
            state = IMAPState.NOT_AUTHENTICATED;
            List<String> preAuthCapabilities =
                    new ArrayList<String>();
            String code = response.getResponseCode();
            if (code != null
                    && code.toUpperCase().startsWith("CAPABILITY ")) {
                parseCapabilities(code.substring(11));
                preAuthCapabilities.addAll(capabilities);
            }
            handler.onConnected(endpoint);
            handler.handleGreeting(this, msg != null ? msg : "",
                    preAuthCapabilities);
        } else if (response.getStatus() == null
                && msg != null
                && msg.toUpperCase().startsWith("PREAUTH ")) {
            state = IMAPState.AUTHENTICATED;
            handler.onConnected(endpoint);
            handler.handlePreAuthenticated(this,
                    msg.substring(8));
        } else {
            state = IMAPState.ERROR;
            handler.handleServiceUnavailable(
                    msg != null ? msg : "");
            close();
        }
    }

    private void dispatchUntaggedStatus(IMAPResponse response) {
        String code = response.getResponseCode();
        if (code == null) {
            return;
        }
        String upperCode = code.toUpperCase();

        if (pendingMailboxInfo != null) {
            if (upperCode.startsWith("PERMANENTFLAGS ")) {
                pendingMailboxInfo.setPermanentFlags(
                        parseFlags(code.substring(15)));
            } else if (upperCode.startsWith("UIDVALIDITY ")) {
                try {
                    pendingMailboxInfo.setUidValidity(
                            Long.parseLong(code.substring(12).trim()));
                } catch (NumberFormatException e) {
                    // ignore
                }
            } else if (upperCode.startsWith("UIDNEXT ")) {
                try {
                    pendingMailboxInfo.setUidNext(
                            Long.parseLong(code.substring(8).trim()));
                } catch (NumberFormatException e) {
                    // ignore
                }
            } else if (upperCode.startsWith("UNSEEN ")) {
                try {
                    pendingMailboxInfo.setUnseen(
                            Integer.parseInt(code.substring(7).trim()));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (upperCode.startsWith("READ-WRITE")
                && pendingMailboxInfo != null) {
            pendingMailboxInfo.setReadWrite(true);
        } else if (upperCode.startsWith("READ-ONLY")
                && pendingMailboxInfo != null) {
            pendingMailboxInfo.setReadWrite(false);
        }
    }

    private void dispatchBye(String message) {
        if (state != IMAPState.LOGOUT_SENT
                && state != IMAPState.CLOSED) {
            if (currentCallback != null) {
                fireServiceClosing(message);
            }
            close();
        }
    }

    private void dispatchNumericUntagged(int number, String rest,
                                         String rawLine) {
        String upper = rest.toUpperCase();

        if (upper.startsWith("EXISTS")) {
            if (pendingMailboxInfo != null) {
                pendingMailboxInfo.setExists(number);
            } else if (state == IMAPState.IDLE_ACTIVE
                    && idleEventHandler != null) {
                idleEventHandler.handleExists(number);
            } else if (mailboxEventListener != null) {
                mailboxEventListener.onExists(number);
            }
            return;
        }

        if (upper.startsWith("RECENT")) {
            if (pendingMailboxInfo != null) {
                pendingMailboxInfo.setRecent(number);
            } else if (state == IMAPState.IDLE_ACTIVE
                    && idleEventHandler != null) {
                idleEventHandler.handleRecent(number);
            } else if (mailboxEventListener != null) {
                mailboxEventListener.onRecent(number);
            }
            return;
        }

        if (upper.startsWith("EXPUNGE")) {
            if (state == IMAPState.EXPUNGE_SENT
                    && currentCallback
                    instanceof ServerExpungeReplyHandler) {
                ((ServerExpungeReplyHandler) currentCallback)
                        .handleExpunged(number);
            } else if (state == IMAPState.IDLE_ACTIVE
                    && idleEventHandler != null) {
                idleEventHandler.handleExpunge(number);
            } else if (mailboxEventListener != null) {
                mailboxEventListener.onExpunge(number);
            }
            return;
        }

        if (upper.startsWith("FETCH ")) {
            dispatchFetchLine(number, rest.substring(6), rawLine);
            return;
        }
    }

    // ── FETCH parsing ──

    private void dispatchFetchLine(int messageNumber, String data,
                                   String rawLine) {
        if (state == IMAPState.STORE_SENT
                && currentCallback
                instanceof ServerStoreReplyHandler) {
            String[] flags = parseFetchFlags(data);
            if (flags != null) {
                ((ServerStoreReplyHandler) currentCallback)
                        .handleStoreResponse(messageNumber, flags);
            }
            return;
        }

        if (state == IMAPState.IDLE_ACTIVE
                && idleEventHandler != null) {
            String[] flags = parseFetchFlags(data);
            if (flags != null) {
                idleEventHandler.handleFlagsUpdate(
                        messageNumber, flags);
            }
            return;
        }

        if (!(currentCallback instanceof ServerFetchReplyHandler)) {
            if (mailboxEventListener != null) {
                String[] flags = parseFetchFlags(data);
                if (flags != null) {
                    mailboxEventListener.onFlagsUpdate(
                            messageNumber, flags);
                }
            }
            return;
        }

        ServerFetchReplyHandler callback =
                (ServerFetchReplyHandler) currentCallback;
        fetchMessageNumber = messageNumber;
        fetchData = new FetchData();
        parseFetchData(data, fetchData);
        callback.handleFetchResponse(messageNumber, fetchData);

        long literalSize = IMAPResponse.parseLiteralSize(rawLine);
        if (literalSize > 0) {
            fetchLiteralSection = parseFetchBodySection(data);
            callback.handleFetchLiteralBegin(
                    messageNumber, fetchLiteralSection, literalSize);
            state = IMAPState.FETCH_LITERAL;
            literalTracker = new LiteralTracker(literalSize, this);
        }
    }

    private String[] parseFetchFlags(String data) {
        String upper = data.toUpperCase();
        int fi = upper.indexOf("FLAGS ");
        if (fi < 0) {
            return null;
        }
        int start = data.indexOf('(', fi);
        int end = data.indexOf(')', start);
        if (start >= 0 && end > start) {
            return parseFlags(data.substring(start, end + 1));
        }
        return null;
    }

    private void parseFetchData(String data, FetchData fd) {
        String upper = data.toUpperCase();

        String[] flags = parseFetchFlags(data);
        if (flags != null) {
            fd.setFlags(flags);
        }

        int uidIdx = upper.indexOf("UID ");
        if (uidIdx >= 0) {
            String uidStr = extractToken(data, uidIdx + 4);
            try {
                fd.setUid(Long.parseLong(uidStr));
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        int sizeIdx = upper.indexOf("RFC822.SIZE ");
        if (sizeIdx >= 0) {
            String sizeStr = extractToken(data, sizeIdx + 12);
            try {
                fd.setSize(Long.parseLong(sizeStr));
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        int dateIdx = upper.indexOf("INTERNALDATE ");
        if (dateIdx >= 0) {
            int q1 = data.indexOf('"', dateIdx + 13);
            int q2 = data.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) {
                fd.setInternalDate(data.substring(q1 + 1, q2));
            }
        }

        int envIdx = upper.indexOf("ENVELOPE ");
        if (envIdx >= 0) {
            fd.setEnvelope(parseEnvelope(data, envIdx + 9));
        }
    }

    private String parseFetchBodySection(String data) {
        String upper = data.toUpperCase();
        int bodyIdx = upper.indexOf("BODY[");
        if (bodyIdx >= 0) {
            int end = data.indexOf(']', bodyIdx + 5);
            if (end >= 0) {
                return data.substring(bodyIdx + 5, end);
            }
        }
        return "";
    }

    private FetchData.Envelope parseEnvelope(String data, int start) {
        FetchData.Envelope env = new FetchData.Envelope();
        int pos = data.indexOf('(', start);
        if (pos < 0) {
            return env;
        }
        pos++;

        String[] fields = extractQuotedFields(data, pos, 10);
        if (fields.length > 0) {
            env.setDate(nilToNull(fields[0]));
        }
        if (fields.length > 1) {
            env.setSubject(nilToNull(fields[1]));
        }
        if (fields.length > 2) {
            env.setFrom(nilToNull(fields[2]));
        }
        if (fields.length > 3) {
            env.setSender(nilToNull(fields[3]));
        }
        if (fields.length > 4) {
            env.setReplyTo(nilToNull(fields[4]));
        }
        if (fields.length > 5) {
            env.setTo(nilToNull(fields[5]));
        }
        if (fields.length > 6) {
            env.setCc(nilToNull(fields[6]));
        }
        if (fields.length > 7) {
            env.setBcc(nilToNull(fields[7]));
        }
        if (fields.length > 8) {
            env.setInReplyTo(nilToNull(fields[8]));
        }
        if (fields.length > 9) {
            env.setMessageId(nilToNull(fields[9]));
        }
        return env;
    }

    private String nilToNull(String value) {
        if (value == null || "NIL".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    // ── LIST/LSUB parsing ──

    private void dispatchListLine(String msg) {
        if (!(currentCallback instanceof ServerListReplyHandler)) {
            return;
        }
        ServerListReplyHandler callback =
                (ServerListReplyHandler) currentCallback;

        // Format: LIST (\Attributes) "delimiter" "name"
        String data = msg.substring(msg.indexOf(' ') + 1);

        String attributes = "";
        int attrStart = data.indexOf('(');
        int attrEnd = data.indexOf(')', attrStart);
        if (attrStart >= 0 && attrEnd > attrStart) {
            attributes = data.substring(attrStart + 1, attrEnd);
            data = data.substring(attrEnd + 1).trim();
        }

        // delimiter and name
        String delimiter = "";
        String name = "";
        int sp = data.indexOf(' ');
        if (sp >= 0) {
            delimiter = unquote(data.substring(0, sp).trim());
            name = unquote(data.substring(sp + 1).trim());
        }

        callback.handleListEntry(attributes, delimiter, name);
    }

    // ── STATUS parsing ──

    private void dispatchStatusLine(String msg) {
        // Format: "mailbox" (MESSAGES n RECENT n ...)
        int parenStart = msg.indexOf('(');
        if (parenStart < 0) {
            return;
        }
        statusMailbox = unquote(msg.substring(0, parenStart).trim());
        String items = msg.substring(parenStart + 1,
                msg.lastIndexOf(')'));
        parseStatusItems(items);
    }

    private void parseStatusItems(String items) {
        String[] tokens = items.trim().split("\\s+");
        for (int i = 0; i < tokens.length - 1; i += 2) {
            String key = tokens[i].toUpperCase();
            String val = tokens[i + 1];
            try {
                switch (key) {
                    case "MESSAGES":
                        statusMessages = Integer.parseInt(val);
                        break;
                    case "RECENT":
                        statusRecent = Integer.parseInt(val);
                        break;
                    case "UIDNEXT":
                        statusUidNext = Long.parseLong(val);
                        break;
                    case "UIDVALIDITY":
                        statusUidValidity = Long.parseLong(val);
                        break;
                    case "UNSEEN":
                        statusUnseen = Integer.parseInt(val);
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException e) {
                // ignore
            }
        }
    }

    private void resetStatusData() {
        statusMailbox = null;
        statusMessages = 0;
        statusRecent = 0;
        statusUidNext = 0;
        statusUidValidity = 0;
        statusUnseen = 0;
    }

    // ── SEARCH parsing ──

    private void dispatchSearchLine(String msg) {
        // Format: SEARCH 1 2 3 4 or just SEARCH (empty result)
        String data = msg.length() > 6 ? msg.substring(7).trim() : "";
        if (!data.isEmpty()) {
            String[] tokens = data.split("\\s+");
            for (String token : tokens) {
                try {
                    searchResults.add(Long.parseLong(token));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
    }

    // ── NAMESPACE parsing ──

    private void dispatchNamespaceLine(String data) {
        // Simplified: extract first namespace entry
        // Format: (("prefix" "delimiter")) NIL NIL
        int pos = data.indexOf("((");
        if (pos >= 0) {
            int start = pos + 2;
            String[] fields = extractQuotedFields(data, start, 2);
            if (fields.length > 0) {
                namespacePersonal = nilToNull(fields[0]);
            }
            if (fields.length > 1) {
                namespacePersonalDelimiter = nilToNull(fields[1]);
            }
        }
    }

    // ── Tagged dispatch ──

    private void dispatchTagged(IMAPResponse response) {
        if (currentTag == null
                || !currentTag.equals(response.getTag())) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning("Tag mismatch: expected "
                        + currentTag + ", got " + response.getTag());
            }
            return;
        }

        if (state == IMAPState.CLOSED
                || state == IMAPState.DISCONNECTED) {
            return;
        }

        switch (state) {
            case CAPABILITY_SENT:
                dispatchCapabilityComplete(response);
                break;
            case LOGIN_SENT:
                dispatchLoginComplete(response);
                break;
            case AUTHENTICATE_SENT:
                dispatchAuthenticateComplete(response);
                break;
            case AUTH_ABORT_SENT:
                dispatchAuthAbortComplete(response);
                break;
            case STARTTLS_SENT:
                dispatchStarttlsComplete(response);
                break;
            case SELECT_SENT:
            case EXAMINE_SENT:
                dispatchSelectComplete(response);
                break;
            case CREATE_SENT:
            case DELETE_SENT:
            case RENAME_SENT:
            case SUBSCRIBE_SENT:
            case UNSUBSCRIBE_SENT:
                dispatchMailboxComplete(response);
                break;
            case LIST_SENT:
            case LSUB_SENT:
                dispatchListComplete(response);
                break;
            case STATUS_SENT:
                dispatchStatusComplete(response);
                break;
            case NAMESPACE_SENT:
                dispatchNamespaceComplete(response);
                break;
            case APPEND_DATA:
                dispatchAppendComplete(response);
                break;
            case IDLE_ACTIVE:
                dispatchIdleComplete(response);
                break;
            case CLOSE_SENT:
            case UNSELECT_SENT:
                dispatchCloseComplete(response);
                break;
            case EXPUNGE_SENT:
                dispatchExpungeComplete(response);
                break;
            case SEARCH_SENT:
                dispatchSearchComplete(response);
                break;
            case FETCH_SENT:
                dispatchFetchComplete(response);
                break;
            case STORE_SENT:
                dispatchStoreComplete(response);
                break;
            case COPY_SENT:
            case MOVE_SENT:
                dispatchCopyComplete(response);
                break;
            case NOOP_SENT:
                dispatchNoopComplete(response);
                break;
            case LOGOUT_SENT:
                state = IMAPState.CLOSED;
                close();
                break;
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning("Unexpected tagged response "
                            + "in state " + state + ": " + response);
                }
        }
    }

    // ── Tagged completion handlers ──

    private void dispatchCapabilityComplete(IMAPResponse response) {
        ServerCapabilityReplyHandler callback =
                (ServerCapabilityReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleCapabilities(this,
                    new ArrayList<String>(capabilities));
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchLoginComplete(IMAPResponse response) {
        ServerLoginReplyHandler callback =
                (ServerLoginReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = IMAPState.AUTHENTICATED;
            List<String> loginCapabilities =
                    new ArrayList<String>();
            String code = response.getResponseCode();
            if (code != null
                    && code.toUpperCase()
                            .startsWith("CAPABILITY ")) {
                parseCapabilities(code.substring(11));
                loginCapabilities.addAll(capabilities);
            }
            callback.handleAuthenticated(this, loginCapabilities);
        } else {
            state = IMAPState.NOT_AUTHENTICATED;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    private void dispatchAuthenticateComplete(
            IMAPResponse response) {
        ServerAuthReplyHandler callback =
                (ServerAuthReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = IMAPState.AUTHENTICATED;
            List<String> authCapabilities =
                    new ArrayList<String>();
            String code = response.getResponseCode();
            if (code != null
                    && code.toUpperCase()
                            .startsWith("CAPABILITY ")) {
                parseCapabilities(code.substring(11));
                authCapabilities.addAll(capabilities);
            }
            callback.handleAuthSuccess(this, authCapabilities);
        } else {
            state = IMAPState.NOT_AUTHENTICATED;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    private void dispatchAuthAbortComplete(IMAPResponse response) {
        ServerAuthAbortHandler callback =
                (ServerAuthAbortHandler) currentCallback;
        currentCallback = null;
        state = IMAPState.NOT_AUTHENTICATED;
        callback.handleAborted(this);
    }

    private void dispatchStarttlsComplete(IMAPResponse response) {
        if (response.isOk()) {
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                ServerStarttlsReplyHandler callback =
                        (ServerStarttlsReplyHandler) currentCallback;
                currentCallback = null;
                state = IMAPState.NOT_AUTHENTICATED;
                callback.handlePermanentFailure(e.getMessage());
            }
        } else if (response.isBad()) {
            ServerStarttlsReplyHandler callback =
                    (ServerStarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = IMAPState.ERROR;
            callback.handlePermanentFailure(response.getMessage());
        } else {
            ServerStarttlsReplyHandler callback =
                    (ServerStarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = IMAPState.NOT_AUTHENTICATED;
            callback.handleTlsUnavailable(this);
        }
    }

    private void dispatchSelectComplete(IMAPResponse response) {
        ServerSelectReplyHandler callback =
                (ServerSelectReplyHandler) currentCallback;
        currentCallback = null;
        MailboxInfo info = pendingMailboxInfo;
        pendingMailboxInfo = null;

        if (response.isOk()) {
            String code = response.getResponseCode();
            if (code != null && info != null) {
                if (code.toUpperCase().startsWith("READ-WRITE")) {
                    info.setReadWrite(true);
                } else if (code.toUpperCase()
                        .startsWith("READ-ONLY")) {
                    info.setReadWrite(false);
                }
            }
            state = IMAPState.SELECTED;
            callback.handleSelected(this, info);
        } else {
            state = IMAPState.AUTHENTICATED;
            callback.handleFailed(this, response.getMessage());
        }
    }

    private void dispatchMailboxComplete(IMAPResponse response) {
        ServerMailboxReplyHandler callback =
                (ServerMailboxReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleOk(this);
        } else {
            callback.handleNo(this, response.getMessage());
        }
    }

    private void dispatchListComplete(IMAPResponse response) {
        ServerListReplyHandler callback =
                (ServerListReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleListComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchStatusComplete(IMAPResponse response) {
        ServerStatusReplyHandler callback =
                (ServerStatusReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleStatus(this, statusMailbox,
                    statusMessages, statusRecent, statusUidNext,
                    statusUidValidity, statusUnseen);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchNamespaceComplete(IMAPResponse response) {
        ServerNamespaceReplyHandler callback =
                (ServerNamespaceReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleNamespace(this, namespacePersonal,
                    namespacePersonalDelimiter);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchAppendComplete(IMAPResponse response) {
        ServerAppendReplyHandler callback =
                (ServerAppendReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            long uidValidity = 0;
            long uid = 0;
            String code = response.getResponseCode();
            if (code != null
                    && code.toUpperCase()
                            .startsWith("APPENDUID ")) {
                String[] parts = code.substring(10).split("\\s+");
                if (parts.length >= 2) {
                    try {
                        uidValidity = Long.parseLong(parts[0]);
                        uid = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }
            callback.handleAppendComplete(this, uidValidity, uid);
        } else {
            callback.handleFailed(this, response.getMessage());
        }
    }

    private void dispatchIdleComplete(IMAPResponse response) {
        ServerIdleEventHandler callback = idleEventHandler;
        currentCallback = null;
        idleEventHandler = null;
        state = restoreBaseState();

        if (callback != null) {
            callback.handleIdleComplete(this);
        }
    }

    private void dispatchCloseComplete(IMAPResponse response) {
        ServerCloseReplyHandler callback =
                (ServerCloseReplyHandler) currentCallback;
        currentCallback = null;
        state = IMAPState.AUTHENTICATED;
        callback.handleClosed(this);
    }

    private void dispatchExpungeComplete(IMAPResponse response) {
        ServerExpungeReplyHandler callback =
                (ServerExpungeReplyHandler) currentCallback;
        currentCallback = null;
        state = IMAPState.SELECTED;

        if (response.isOk()) {
            callback.handleExpungeComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchSearchComplete(IMAPResponse response) {
        ServerSearchReplyHandler callback =
                (ServerSearchReplyHandler) currentCallback;
        currentCallback = null;
        state = IMAPState.SELECTED;

        if (response.isOk()) {
            long[] results = new long[searchResults.size()];
            for (int i = 0; i < results.length; i++) {
                results[i] = searchResults.get(i);
            }
            searchResults.clear();
            callback.handleSearchResults(this, results);
        } else {
            searchResults.clear();
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchFetchComplete(IMAPResponse response) {
        ServerFetchReplyHandler callback =
                (ServerFetchReplyHandler) currentCallback;
        currentCallback = null;
        state = IMAPState.SELECTED;

        if (response.isOk()) {
            callback.handleFetchComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchStoreComplete(IMAPResponse response) {
        ServerStoreReplyHandler callback =
                (ServerStoreReplyHandler) currentCallback;
        currentCallback = null;
        state = IMAPState.SELECTED;

        if (response.isOk()) {
            callback.handleStoreComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchCopyComplete(IMAPResponse response) {
        ServerCopyReplyHandler callback =
                (ServerCopyReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            String code = response.getResponseCode();
            if (code != null
                    && code.toUpperCase()
                            .startsWith("COPYUID ")) {
                String[] parts = code.substring(8).split("\\s+");
                if (parts.length >= 3) {
                    try {
                        copyUidValidity = Long.parseLong(parts[0]);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                    copySourceUids = parts[1];
                    copyDestUids = parts[2];
                }
            }
            callback.handleCopyComplete(this, copyUidValidity,
                    copySourceUids, copyDestUids);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchNoopComplete(IMAPResponse response) {
        ServerNoopReplyHandler callback =
                (ServerNoopReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();
        callback.handleOk(this);
    }

    // ── Helpers ──

    private IMAPState restoreBaseState() {
        return wasSelected ? IMAPState.SELECTED
                : IMAPState.AUTHENTICATED;
    }

    private void fireServiceClosing(String message) {
        if (currentCallback
                instanceof org.bluezoo.gumdrop.imap.client.handler
                .ServerReplyHandler) {
            ((org.bluezoo.gumdrop.imap.client.handler
                    .ServerReplyHandler) currentCallback)
                    .handleServiceClosing(message);
        }
    }

    private void parseCapabilities(String data) {
        capabilities.clear();
        String[] tokens = data.trim().split("\\s+");
        for (String token : tokens) {
            if (!token.isEmpty()) {
                capabilities.add(token);
            }
        }
    }

    private void resetCopyData() {
        copyUidValidity = 0;
        copySourceUids = null;
        copyDestUids = null;
    }

    static String quoteString(String value) {
        if (value == null) {
            return "\"\"";
        }
        boolean needsEscape = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20 || c == 0x7f) {
                needsEscape = true;
                break;
            }
        }
        if (needsEscape) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7f) {
                    continue;
                }
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            sb.append('"');
            return sb.toString();
        }
        return "\"" + value + "\"";
    }

    private static String unquote(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("\"") && value.endsWith("\"")
                && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static String[] parseFlags(String flagsData) {
        String data = flagsData.trim();
        if (data.startsWith("(") && data.endsWith(")")) {
            data = data.substring(1, data.length() - 1).trim();
        }
        if (data.isEmpty()) {
            return new String[0];
        }
        return data.split("\\s+");
    }

    private static String extractToken(String data, int start) {
        int end = start;
        while (end < data.length()
                && data.charAt(end) != ' '
                && data.charAt(end) != ')'
                && data.charAt(end) != ']') {
            end++;
        }
        return data.substring(start, end);
    }

    private static String[] extractQuotedFields(String data,
                                                 int start,
                                                 int maxFields) {
        List<String> fields = new ArrayList<String>();
        int pos = start;
        while (pos < data.length() && fields.size() < maxFields) {
            while (pos < data.length() && data.charAt(pos) == ' ') {
                pos++;
            }
            if (pos >= data.length() || data.charAt(pos) == ')') {
                break;
            }
            if (data.charAt(pos) == '"') {
                int end = data.indexOf('"', pos + 1);
                if (end < 0) {
                    break;
                }
                fields.add(data.substring(pos + 1, end));
                pos = end + 1;
            } else if (data.charAt(pos) == '(') {
                int depth = 1;
                int end = pos + 1;
                while (end < data.length() && depth > 0) {
                    if (data.charAt(end) == '(') {
                        depth++;
                    } else if (data.charAt(end) == ')') {
                        depth--;
                    }
                    end++;
                }
                fields.add(data.substring(pos, end));
                pos = end;
            } else {
                int end = pos;
                while (end < data.length()
                        && data.charAt(end) != ' '
                        && data.charAt(end) != ')') {
                    end++;
                }
                fields.add(data.substring(pos, end));
                pos = end;
            }
        }
        return fields.toArray(new String[0]);
    }
}
