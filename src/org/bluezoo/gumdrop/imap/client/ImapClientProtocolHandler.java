/*
 * ImapClientProtocolHandler.java
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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;

/**
 * IMAP4rev2 client protocol handler (RFC 9051).
 *
 * <p>Implements a type-safe IMAP client state machine with staged
 * callback interfaces constraining which operations are valid at
 * each protocol stage. Tagged command tracking ensures exactly one
 * command is in-flight at a time (RFC 9051 section 5.5).
 *
 * <p>Line parsing uses a streaming {@link ImapClientLexer} (issue #85):
 * bytes are tokenised as they arrive rather than buffered into whole
 * lines — see {@link ByteStreamLexer}. Incoming literal data (FETCH body
 * sections) is delivered via {@link ByteStreamLexer#enterRaw(long)} and
 * still tracked by the pre-existing, unchanged {@link LiteralTracker},
 * which is now driven from {@link #rawBytes(ByteBuffer)} instead of a
 * hand-rolled loop in {@code receive()}.
 *
 * <p>Supported features:
 * <ul>
 *   <li>STARTTLS — RFC 9051 section 6.2.1</li>
 *   <li>SASL AUTHENTICATE with initial response — RFC 4959</li>
 *   <li>IDLE — RFC 2177</li>
 *   <li>NAMESPACE — RFC 2342</li>
 *   <li>MOVE — RFC 6851</li>
 *   <li>APPEND with literal streaming</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ProtocolHandler
 * @see RemoteGreeting
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9051">RFC 9051 — IMAP4rev2</a>
 */
public final class ImapClientProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<ImapClientLexer.Token>,
        LiteralTracker.Callback,
        ClientNotAuthenticatedState, ClientPostStarttls,
        ClientAuthExchange, ClientAuthenticatedState,
        ClientSelectedState, ClientIdleState,
        ClientAppendState {

    private static final Logger LOGGER =
            Logger.getLogger(
                    ImapClientProtocolHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.imap.L10N");

    private static final String CRLF = "\r\n";

    private final RemoteGreeting handler;
    private final ImapTagGenerator tagGenerator;

    private Endpoint endpoint;
    private ImapState state = ImapState.DISCONNECTED;
    private boolean secure;

    private String currentTag;
    private Object currentCallback;

    // Streaming lexer (issue #85). No cap on structured tokens (this
    // client trusts the remote server, same principle as
    // Pop3ClientLexer/SmtpClientLexer) and no tag/args split is needed at
    // this layer at all — KEYWORD and TEXT are just concatenated back into
    // one reconstructed line for the existing ImapResponse.parse(String).
    private final ImapClientLexer lexer = new ImapClientLexer(this, Integer.MAX_VALUE);
    private final StringBuilder lineBuilder = new StringBuilder();

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
    /** True between the end of a FETCH literal and the line that follows it. */
    private boolean fetchTailPending;

    // IDLE event handler (separate from currentCallback for clarity)
    private IdleEventHandler idleEventHandler;

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
    public ImapClientProtocolHandler(RemoteGreeting handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        this.handler = handler;
        this.tagGenerator = new ImapTagGenerator();
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
        state = ImapState.CONNECTING;

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.client_connected"), ep.getRemoteAddress()));
        }
    }

    @Override
    public void receive(ByteBuffer data) {
        lexer.feed(data);
    }

    @Override
    public void disconnected() {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(L10N.getString("info.imap_client_disconnected"));
        }
        state = ImapState.CLOSED;
        handler.onDisconnected();
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.tls_established"), info.getCipherSuite()));
        }

        handler.onSecurityEstablished(info);

        if (currentCallback instanceof StarttlsReplyHandler) {
            StarttlsReplyHandler callback =
                    (StarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = ImapState.NOT_AUTHENTICATED;
            callback.handleTlsEstablished(this);
        }
    }

    @Override
    public void error(Exception cause) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.imap_transport_error"), cause);
        }
        state = ImapState.ERROR;
        handler.onError(cause);
    }

    // ── ByteStreamLexer.Handler implementation (issue #85) ──

    // RFC 9051 section 7: KEYWORD [SP TEXT] CRLF. Unlike the server side,
    // no tag/args split is needed here — KEYWORD and TEXT are just
    // concatenated back into one reconstructed line (identical to what
    // the old whole-line CharBuffer-free byte copy produced) and handed
    // to the existing, unchanged handleResponseLine()/ImapResponse.parse().
    @Override
    public boolean token(ImapClientLexer.Token type, ByteBuffer window) {
        switch (type) {
            case KEYWORD:
            case TEXT:
                byte[] bytes = new byte[window.remaining()];
                window.get(bytes);
                lineBuilder.append(new String(bytes, StandardCharsets.US_ASCII));
                return false;
            case SP:
                lineBuilder.append(' ');
                return true; // latch text mode for the rest of the line
            case CRLF:
                dispatchLine();
                return false;
            default:
                return false;
        }
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        literalTracker.process(slice);
    }

    @Override
    public void tokenTooLong() {
        // ImapClientLexer is constructed with an unbounded per-token cap
        // (Integer.MAX_VALUE) — this client trusts the remote server, same
        // as Pop3ClientLexer/SmtpClientLexer — so this is structurally
        // unreachable.
        LOGGER.warning(L10N.getString("warn.imap_unexpected_token_too_long"));
    }

    private void dispatchLine() {
        String line = lineBuilder.toString();
        lineBuilder.setLength(0);
        if (line.isEmpty()) {
            return;
        }
        if (fetchTailPending) {
            fetchTailPending = false;
            handleFetchTail(line);
        } else {
            handleResponseLine(line);
        }
        if (literalTracker != null) {
            lexer.enterLiteral(literalTracker.getRemaining());
        }
    }

    // ── LiteralTracker.Callback ──

    @Override
    public void literalContent(ByteBuffer data) {
        if (currentCallback instanceof FetchReplyHandler) {
            FetchReplyHandler fetchHandler =
                    (FetchReplyHandler) currentCallback;
            fetchHandler.handleFetchLiteralContent(data);
            if (fetchHandler.wantsPause()) {
                fetchHandler.setResumeCallback(
                        new FetchResumeTask());
                endpoint.pauseRead();
            }
        }
    }

    @Override
    public void literalComplete() {
        if (currentCallback instanceof FetchReplyHandler) {
            ((FetchReplyHandler) currentCallback)
                    .handleFetchLiteralEnd(fetchMessageNumber);
        }
        state = ImapState.FETCH_SENT;
        literalTracker = null;
        fetchTailPending = true;
    }

    /**
     * Handles the remainder of a FETCH response after a literal
     * (RFC 9051 section 7.5.2): further data items, possibly another
     * literal, and the closing parenthesis. The items are merged into the
     * {@link FetchData} already delivered for this message.
     */
    private void handleFetchTail(String line) {
        if (fetchData != null) {
            parseFetchData(line, fetchData);
        }
        long literalSize = ImapResponse.parseLiteralSize(line);
        if (literalSize > 0
                && currentCallback instanceof FetchReplyHandler) {
            FetchReplyHandler callback =
                    (FetchReplyHandler) currentCallback;
            fetchLiteralSection = parseFetchBodySection(line);
            callback.handleFetchLiteralBegin(
                    fetchMessageNumber, fetchLiteralSection, literalSize);
            state = ImapState.FETCH_LITERAL;
            literalTracker = new LiteralTracker(literalSize, this);
        }
    }

    // ── Connection state ──

    public boolean isOpen() {
        return endpoint != null && endpoint.isOpen()
                && state != ImapState.DISCONNECTED
                && state != ImapState.CLOSED
                && state != ImapState.ERROR;
    }

    public void close() {
        if (state == ImapState.CLOSED) {
            return;
        }
        state = ImapState.CLOSED;
        literalTracker = null;
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── ClientNotAuthenticatedState (RFC 9051 section 6.1–6.2) ──

    // RFC 9051 section 6.1.1 — CAPABILITY command
    @Override
    public void capability(CapabilityReplyHandler callback) {
        this.currentCallback = callback;
        capabilities.clear();
        sendTaggedCommand("CAPABILITY", ImapState.CAPABILITY_SENT);
    }

    // RFC 9051 section 6.2.3 — LOGIN command
    @Override
    public void login(String username, String password,
                      LoginReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("LOGIN " + quoteString(username) + " "
                + quoteString(password), ImapState.LOGIN_SENT);
    }

    // RFC 9051 section 6.2.2 — AUTHENTICATE, RFC 4959 initial response
    @Override
    public void authenticate(String mechanism, byte[] initialResponse,
                             AuthReplyHandler callback) {
        this.currentCallback = callback;
        StringBuilder cmd = new StringBuilder("AUTHENTICATE ");
        cmd.append(mechanism);
        if (initialResponse != null) {
            cmd.append(" ");
            cmd.append(Base64.getEncoder()
                    .encodeToString(initialResponse));
        }
        sendTaggedCommand(cmd.toString(), ImapState.AUTHENTICATE_SENT);
    }

    // RFC 9051 section 6.2.1 — STARTTLS command
    @Override
    public void starttls(StarttlsReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("STARTTLS", ImapState.STARTTLS_SENT);
    }

    // RFC 9051 section 6.1.3 — LOGOUT command
    @Override
    public void logout() {
        sendTaggedCommand("LOGOUT", ImapState.LOGOUT_SENT);
    }

    // ── ClientPostStarttls ──
    // capability, login, authenticate, logout already defined above

    // ── ClientAuthExchange ──

    @Override
    public void respond(byte[] response,
                        AuthReplyHandler callback) {
        this.currentCallback = callback;
        String encoded = Base64.getEncoder().encodeToString(response);
        sendRawLine(encoded, ImapState.AUTHENTICATE_SENT);
    }

    @Override
    public void abort(AuthAbortHandler callback) {
        this.currentCallback = callback;
        sendRawLine("*", ImapState.AUTH_ABORT_SENT);
    }

    // ── ClientAuthenticatedState (RFC 9051 section 6.3) ──

    // RFC 9051 section 6.3.1 — SELECT command
    @Override
    public void select(String mailbox,
                       SelectReplyHandler callback) {
        this.currentCallback = callback;
        pendingMailboxInfo = new MailboxInfo();
        sendTaggedCommand("SELECT " + quoteString(mailbox),
                ImapState.SELECT_SENT);
    }

    // RFC 9051 section 6.3.2 — EXAMINE command
    @Override
    public void examine(String mailbox,
                        SelectReplyHandler callback) {
        this.currentCallback = callback;
        pendingMailboxInfo = new MailboxInfo();
        sendTaggedCommand("EXAMINE " + quoteString(mailbox),
                ImapState.EXAMINE_SENT);
    }

    // RFC 9051 section 6.3.3 — CREATE command
    @Override
    public void create(String mailbox,
                       MailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("CREATE " + quoteString(mailbox),
                ImapState.CREATE_SENT);
    }

    // RFC 9051 section 6.3.4 — DELETE command
    @Override
    public void delete(String mailbox,
                       MailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("DELETE " + quoteString(mailbox),
                ImapState.DELETE_SENT);
    }

    // RFC 9051 section 6.3.5 — RENAME command
    @Override
    public void rename(String from, String to,
                       MailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("RENAME " + quoteString(from) + " "
                + quoteString(to), ImapState.RENAME_SENT);
    }

    // RFC 9051 section 6.3.6 — SUBSCRIBE command
    @Override
    public void subscribe(String mailbox,
                          MailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("SUBSCRIBE " + quoteString(mailbox),
                ImapState.SUBSCRIBE_SENT);
    }

    // RFC 9051 section 6.3.7 — UNSUBSCRIBE command
    @Override
    public void unsubscribe(String mailbox,
                            MailboxReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("UNSUBSCRIBE " + quoteString(mailbox),
                ImapState.UNSUBSCRIBE_SENT);
    }

    // RFC 9051 section 6.3.8 — LIST command
    @Override
    public void list(String reference, String pattern,
                     ListReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("LIST " + quoteString(reference) + " "
                + quoteString(pattern), ImapState.LIST_SENT);
    }

    // RFC 9051 section 6.3.9 — LSUB command (deprecated)
    @Override
    public void lsub(String reference, String pattern,
                     ListReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("LSUB " + quoteString(reference) + " "
                + quoteString(pattern), ImapState.LSUB_SENT);
    }

    // RFC 9051 section 6.3.11 — STATUS command
    @Override
    public void status(String mailbox, String[] items,
                       StatusReplyHandler callback) {
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
        sendTaggedCommand(cmd.toString(), ImapState.STATUS_SENT);
    }

    // RFC 2342 — NAMESPACE command
    @Override
    public void namespace(NamespaceReplyHandler callback) {
        this.currentCallback = callback;
        namespacePersonal = null;
        namespacePersonalDelimiter = null;
        sendTaggedCommand("NAMESPACE", ImapState.NAMESPACE_SENT);
    }

    // RFC 9208 — GETQUOTA command
    @Override
    public void getQuota(String quotaRoot,
                         QuotaReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("GETQUOTA " + quoteString(quotaRoot),
                ImapState.GETQUOTA_SENT);
    }

    // RFC 9208 — GETQUOTAROOT command
    @Override
    public void getQuotaRoot(String mailbox,
                             QuotaReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("GETQUOTAROOT " + quoteString(mailbox),
                ImapState.GETQUOTAROOT_SENT);
    }

    // RFC 9051 section 6.3.12 — APPEND command
    @Override
    public void append(String mailbox, String[] flags, String date,
                       long size, AppendReplyHandler callback) {
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
        sendTaggedCommand(cmd.toString(), ImapState.APPEND_SENT);
    }

    // RFC 2177 — IDLE command
    @Override
    public void idle(IdleEventHandler callback) {
        this.currentCallback = callback;
        this.idleEventHandler = callback;
        sendTaggedCommand("IDLE", ImapState.IDLE_SENT);
    }

    // RFC 9051 section 6.1.2 — NOOP command
    @Override
    public void noop(NoopReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("NOOP", ImapState.NOOP_SENT);
    }

    // ── ClientSelectedState (RFC 9051 section 6.4) ──

    // RFC 9051 section 6.4.1 — CLOSE command
    @Override
    public void close(CloseReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("CLOSE", ImapState.CLOSE_SENT);
    }

    // RFC 9051 section 6.4.2 — UNSELECT command
    @Override
    public void unselect(CloseReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("UNSELECT", ImapState.UNSELECT_SENT);
    }

    // RFC 9051 section 6.4.3 — EXPUNGE command
    @Override
    public void expunge(ExpungeReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("EXPUNGE", ImapState.EXPUNGE_SENT);
    }

    // RFC 9051 section 6.4.4 — SEARCH command
    @Override
    public void search(String criteria,
                       SearchReplyHandler callback) {
        this.currentCallback = callback;
        searchResults.clear();
        sendTaggedCommand("SEARCH " + criteria,
                ImapState.SEARCH_SENT);
    }

    @Override
    public void uidSearch(String criteria,
                          SearchReplyHandler callback) {
        this.currentCallback = callback;
        searchResults.clear();
        sendTaggedCommand("UID SEARCH " + criteria,
                ImapState.SEARCH_SENT);
    }

    // RFC 9051 section 6.4.5 — FETCH command
    @Override
    public void fetch(String sequenceSet, String dataItems,
                      FetchReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("FETCH " + sequenceSet + " " + dataItems,
                ImapState.FETCH_SENT);
    }

    @Override
    public void uidFetch(String sequenceSet, String dataItems,
                         FetchReplyHandler callback) {
        this.currentCallback = callback;
        sendTaggedCommand("UID FETCH " + sequenceSet + " " + dataItems,
                ImapState.FETCH_SENT);
    }

    // RFC 9051 section 6.4.6 — STORE command
    @Override
    public void store(String sequenceSet, String action,
                      String[] flags,
                      StoreReplyHandler callback) {
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
        sendTaggedCommand(cmd.toString(), ImapState.STORE_SENT);
    }

    @Override
    public void uidStore(String sequenceSet, String action,
                         String[] flags,
                         StoreReplyHandler callback) {
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
        sendTaggedCommand(cmd.toString(), ImapState.STORE_SENT);
    }

    // RFC 9051 section 6.4.7 — COPY command
    @Override
    public void copy(String sequenceSet, String mailbox,
                     CopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("COPY " + sequenceSet + " "
                + quoteString(mailbox), ImapState.COPY_SENT);
    }

    @Override
    public void uidCopy(String sequenceSet, String mailbox,
                        CopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("UID COPY " + sequenceSet + " "
                + quoteString(mailbox), ImapState.COPY_SENT);
    }

    // RFC 6851 — MOVE command
    @Override
    public void move(String sequenceSet, String mailbox,
                     CopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("MOVE " + sequenceSet + " "
                + quoteString(mailbox), ImapState.MOVE_SENT);
    }

    @Override
    public void uidMove(String sequenceSet, String mailbox,
                        CopyReplyHandler callback) {
        this.currentCallback = callback;
        resetCopyData();
        sendTaggedCommand("UID MOVE " + sequenceSet + " "
                + quoteString(mailbox), ImapState.MOVE_SENT);
    }

    // ── ClientIdleState ──

    @Override
    public void done() {
        sendRawLine("DONE", state);
    }

    // ── ClientAppendState ──

    @Override
    public void writeContent(ByteBuffer data) {
        if (state != ImapState.APPEND_DATA || !isOpen()) {
            return;
        }
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data);
        copy.flip();
        endpoint.send(copy);
    }

    @Override
    public void onWriteReady(Runnable callback) {
        endpoint.onWriteReady(callback);
    }

    @Override
    public void endAppend() {
        if (state != ImapState.APPEND_DATA || !isOpen()) {
            return;
        }
        byte[] crlf = CRLF.getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(crlf));
    }

    // ── Command sending ──

    private void sendTaggedCommand(String command,
                                   ImapState newState) {
        if (!isOpen()) {
            handler.onError(new IOException("Not connected"));
            return;
        }

        // Track whether we were in SELECTED before sending
        wasSelected = (state == ImapState.SELECTED);

        currentTag = tagGenerator.next();
        state = newState;

        String line = currentTag + " " + command + CRLF;
        byte[] data = line.getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(data));

        if (LOGGER.isLoggable(Level.FINE)) {
            if (command.startsWith("LOGIN ")
                    || command.startsWith("AUTHENTICATE ")) {
                int sp = command.indexOf(' ');
                LOGGER.fine(MessageFormat.format(L10N.getString("debug.sent_imap_command_redacted"), currentTag, command.substring(0, sp + 1)));
            } else {
                LOGGER.fine(MessageFormat.format(L10N.getString("debug.sent_imap_command"), currentTag, command));
            }
        }
    }

    private void sendRawLine(String line, ImapState newState) {
        if (!isOpen()) {
            handler.onError(new IOException("Not connected"));
            return;
        }

        state = newState;

        byte[] data = (line + CRLF).getBytes(StandardCharsets.US_ASCII);
        endpoint.send(ByteBuffer.wrap(data));

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(L10N.getString("debug.sent_imap_raw_line_redacted"));
        }
    }

    // ── Response handling (RFC 9051 section 7) ──

    // RFC 9051 section 7 — server responses: tagged, untagged, continuation
    private void handleResponseLine(String line) {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.received_imap_response"), line));
        }

        try {
            ImapResponse response = ImapResponse.parse(line);
            if (response == null) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(
                            MessageFormat.format(L10N.getString("warn.imap_unparseable_response"), line));
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
                        MessageFormat.format(L10N.getString("warn.imap_error_handling_response"), line), e);
            }
            handler.onError(e);
        }
    }

    // ── Continuation dispatch ──

    private void dispatchContinuation(ImapResponse response) {
        switch (state) {
            case AUTHENTICATE_SENT: {
                AuthReplyHandler callback =
                        (AuthReplyHandler) currentCallback;
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
                state = ImapState.APPEND_DATA;
                AppendReplyHandler callback =
                        (AppendReplyHandler) currentCallback;
                callback.handleReadyForData(this);
                break;
            }
            case IDLE_SENT: {
                state = ImapState.IDLE_ACTIVE;
                IdleEventHandler callback =
                        (IdleEventHandler) currentCallback;
                callback.handleIdleStarted(this);
                break;
            }
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(
                            MessageFormat.format(L10N.getString("warn.imap_unexpected_continuation"), state, response));
                }
        }
    }

    // ── Untagged dispatch ──

    private void dispatchUntagged(ImapResponse response,
                                  String rawLine) {
        String msg = response.getMessage();
        if (msg == null) {
            return;
        }

        if (state == ImapState.CONNECTING) {
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

        // RFC 9208 — QUOTA / QUOTAROOT untagged responses
        if (upper.startsWith("QUOTA ")) {
            dispatchQuotaLine(msg.substring(6));
            return;
        }
        if (upper.startsWith("QUOTAROOT ")) {
            dispatchQuotaRootLine(msg.substring(10));
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
            LOGGER.fine(MessageFormat.format(L10N.getString("debug.unhandled_untagged_response"), msg));
        }
    }

    // RFC 9051 section 7.1 — server greeting (OK, PREAUTH, BYE)
    private void dispatchGreeting(ImapResponse response) {
        String msg = response.getMessage();

        if (response.getStatus() == ImapResponse.Status.OK) {
            state = ImapState.NOT_AUTHENTICATED;
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
            state = ImapState.AUTHENTICATED;
            handler.onConnected(endpoint);
            handler.handlePreAuthenticated(this,
                    msg.substring(8));
        } else {
            state = ImapState.ERROR;
            handler.handleServiceUnavailable(
                    msg != null ? msg : "");
            close();
        }
    }

    private void dispatchUntaggedStatus(ImapResponse response) {
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
        if (state != ImapState.LOGOUT_SENT
                && state != ImapState.CLOSED) {
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
            } else if (state == ImapState.IDLE_ACTIVE
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
            } else if (state == ImapState.IDLE_ACTIVE
                    && idleEventHandler != null) {
                idleEventHandler.handleRecent(number);
            } else if (mailboxEventListener != null) {
                mailboxEventListener.onRecent(number);
            }
            return;
        }

        if (upper.startsWith("EXPUNGE")) {
            if (state == ImapState.EXPUNGE_SENT
                    && currentCallback
                    instanceof ExpungeReplyHandler) {
                ((ExpungeReplyHandler) currentCallback)
                        .handleExpunged(number);
            } else if (state == ImapState.IDLE_ACTIVE
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
        if (state == ImapState.STORE_SENT
                && currentCallback
                instanceof StoreReplyHandler) {
            String[] flags = parseFetchFlags(data);
            if (flags != null) {
                ((StoreReplyHandler) currentCallback)
                        .handleStoreResponse(messageNumber, flags);
            }
            return;
        }

        if (state == ImapState.IDLE_ACTIVE
                && idleEventHandler != null) {
            String[] flags = parseFetchFlags(data);
            if (flags != null) {
                idleEventHandler.handleFlagsUpdate(
                        messageNumber, flags);
            }
            return;
        }

        if (!(currentCallback instanceof FetchReplyHandler)) {
            if (mailboxEventListener != null) {
                String[] flags = parseFetchFlags(data);
                if (flags != null) {
                    mailboxEventListener.onFlagsUpdate(
                            messageNumber, flags);
                }
            }
            return;
        }

        FetchReplyHandler callback =
                (FetchReplyHandler) currentCallback;
        fetchMessageNumber = messageNumber;
        fetchData = new FetchData();
        parseFetchData(data, fetchData);
        callback.handleFetchResponse(messageNumber, fetchData);

        long literalSize = ImapResponse.parseLiteralSize(rawLine);
        if (literalSize > 0) {
            fetchLiteralSection = parseFetchBodySection(data);
            callback.handleFetchLiteralBegin(
                    messageNumber, fetchLiteralSection, literalSize);
            state = ImapState.FETCH_LITERAL;
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
        if (!(currentCallback instanceof ListReplyHandler)) {
            return;
        }
        ListReplyHandler callback =
                (ListReplyHandler) currentCallback;

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

    // ── QUOTA / QUOTAROOT parsing (RFC 9208) ──

    private void dispatchQuotaLine(String data) {
        // Format: quotaroot (resource usage limit ...)
        // e.g.  "" (STORAGE 10 512)
        if (!(currentCallback instanceof QuotaReplyHandler)) {
            return;
        }
        QuotaReplyHandler cb =
                (QuotaReplyHandler) currentCallback;
        int parenStart = data.indexOf('(');
        if (parenStart < 0) {
            return;
        }
        String quotaRoot = unquote(data.substring(0, parenStart).trim());
        String resources = data.substring(parenStart + 1,
                data.lastIndexOf(')'));
        String[] tokens = resources.trim().split("\\s+");
        // triplets: resourceName usage limit
        int max = tokens.length - 2;
        for (int i = 0; i < max; i += 3) {
            String resourceName = tokens[i];
            long usage = Long.parseLong(tokens[i + 1]);
            long limit = Long.parseLong(tokens[i + 2]);
            cb.handleQuota(quotaRoot, resourceName, usage, limit);
        }
    }

    private void dispatchQuotaRootLine(String data) {
        // Format: mailbox quotaroot1 quotaroot2 ...
        // e.g.  INBOX ""
        if (!(currentCallback instanceof QuotaReplyHandler)) {
            return;
        }
        QuotaReplyHandler cb =
                (QuotaReplyHandler) currentCallback;
        String[] parts = splitQuotedArgs(data);
        if (parts.length >= 1) {
            String mailbox = unquote(parts[0]);
            String[] roots = new String[parts.length - 1];
            for (int i = 1; i < parts.length; i++) {
                roots[i - 1] = unquote(parts[i]);
            }
            cb.handleQuotaRoot(mailbox, roots);
        }
    }

    private static String[] splitQuotedArgs(String s) {
        List<String> parts = new ArrayList<>();
        int len = s.length();
        int i = 0;
        while (i < len) {
            while (i < len && s.charAt(i) == ' ') {
                i++;
            }
            if (i >= len) {
                break;
            }
            if (s.charAt(i) == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) {
                    end = len;
                }
                parts.add(s.substring(i, end + 1));
                i = end + 1;
            } else {
                int end = s.indexOf(' ', i);
                if (end < 0) {
                    end = len;
                }
                parts.add(s.substring(i, end));
                i = end;
            }
        }
        return parts.toArray(new String[0]);
    }

    // ── Tagged dispatch ──

    private void dispatchTagged(ImapResponse response) {
        if (currentTag == null
                || !currentTag.equals(response.getTag())) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.warning(MessageFormat.format(
                        L10N.getString("warn.imap_tag_mismatch"), currentTag, response.getTag()));
            }
            return;
        }

        if (state == ImapState.CLOSED
                || state == ImapState.DISCONNECTED) {
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
            case GETQUOTA_SENT:
            case GETQUOTAROOT_SENT:
                dispatchQuotaComplete(response);
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
                state = ImapState.CLOSED;
                close();
                break;
            default:
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.warning(MessageFormat.format(
                            L10N.getString("warn.imap_unexpected_tagged_response"), state, response));
                }
        }
    }

    // ── Tagged completion handlers ──

    private void dispatchCapabilityComplete(ImapResponse response) {
        CapabilityReplyHandler callback =
                (CapabilityReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleCapabilities(this,
                    new ArrayList<String>(capabilities));
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchLoginComplete(ImapResponse response) {
        LoginReplyHandler callback =
                (LoginReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = ImapState.AUTHENTICATED;
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
            state = ImapState.NOT_AUTHENTICATED;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    private void dispatchAuthenticateComplete(
            ImapResponse response) {
        AuthReplyHandler callback =
                (AuthReplyHandler) currentCallback;
        currentCallback = null;

        if (response.isOk()) {
            state = ImapState.AUTHENTICATED;
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
            state = ImapState.NOT_AUTHENTICATED;
            callback.handleAuthFailed(this, response.getMessage());
        }
    }

    private void dispatchAuthAbortComplete(ImapResponse response) {
        AuthAbortHandler callback =
                (AuthAbortHandler) currentCallback;
        currentCallback = null;
        state = ImapState.NOT_AUTHENTICATED;
        callback.handleAborted(this);
    }

    private void dispatchStarttlsComplete(ImapResponse response) {
        if (response.isOk()) {
            try {
                endpoint.startTLS();
            } catch (IOException e) {
                StarttlsReplyHandler callback =
                        (StarttlsReplyHandler) currentCallback;
                currentCallback = null;
                state = ImapState.NOT_AUTHENTICATED;
                callback.handlePermanentFailure(e.getMessage());
            }
        } else if (response.isBad()) {
            StarttlsReplyHandler callback =
                    (StarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = ImapState.ERROR;
            callback.handlePermanentFailure(response.getMessage());
        } else {
            StarttlsReplyHandler callback =
                    (StarttlsReplyHandler) currentCallback;
            currentCallback = null;
            state = ImapState.NOT_AUTHENTICATED;
            callback.handleTlsUnavailable(this);
        }
    }

    private void dispatchSelectComplete(ImapResponse response) {
        SelectReplyHandler callback =
                (SelectReplyHandler) currentCallback;
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
            state = ImapState.SELECTED;
            callback.handleSelected(this, info);
        } else {
            state = ImapState.AUTHENTICATED;
            callback.handleFailed(this, response.getMessage());
        }
    }

    private void dispatchMailboxComplete(ImapResponse response) {
        MailboxReplyHandler callback =
                (MailboxReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleOk(this);
        } else {
            callback.handleNo(this, response.getMessage());
        }
    }

    private void dispatchListComplete(ImapResponse response) {
        ListReplyHandler callback =
                (ListReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleListComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchStatusComplete(ImapResponse response) {
        StatusReplyHandler callback =
                (StatusReplyHandler) currentCallback;
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

    private void dispatchNamespaceComplete(ImapResponse response) {
        NamespaceReplyHandler callback =
                (NamespaceReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleNamespace(this, namespacePersonal,
                    namespacePersonalDelimiter);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    // RFC 9208 — GETQUOTA / GETQUOTAROOT completion
    private void dispatchQuotaComplete(ImapResponse response) {
        QuotaReplyHandler callback =
                (QuotaReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();

        if (response.isOk()) {
            callback.handleQuotaComplete();
        } else {
            callback.handleQuotaError(response.getMessage());
        }
    }

    private void dispatchAppendComplete(ImapResponse response) {
        AppendReplyHandler callback =
                (AppendReplyHandler) currentCallback;
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

    private void dispatchIdleComplete(ImapResponse response) {
        IdleEventHandler callback = idleEventHandler;
        currentCallback = null;
        idleEventHandler = null;
        state = restoreBaseState();

        if (callback != null) {
            callback.handleIdleComplete(this);
        }
    }

    private void dispatchCloseComplete(ImapResponse response) {
        CloseReplyHandler callback =
                (CloseReplyHandler) currentCallback;
        currentCallback = null;
        state = ImapState.AUTHENTICATED;
        callback.handleClosed(this);
    }

    private void dispatchExpungeComplete(ImapResponse response) {
        ExpungeReplyHandler callback =
                (ExpungeReplyHandler) currentCallback;
        currentCallback = null;
        state = ImapState.SELECTED;

        if (response.isOk()) {
            callback.handleExpungeComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchSearchComplete(ImapResponse response) {
        SearchReplyHandler callback =
                (SearchReplyHandler) currentCallback;
        currentCallback = null;
        state = ImapState.SELECTED;

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

    private void dispatchFetchComplete(ImapResponse response) {
        FetchReplyHandler callback =
                (FetchReplyHandler) currentCallback;
        currentCallback = null;
        state = ImapState.SELECTED;

        if (response.isOk()) {
            callback.handleFetchComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchStoreComplete(ImapResponse response) {
        StoreReplyHandler callback =
                (StoreReplyHandler) currentCallback;
        currentCallback = null;
        state = ImapState.SELECTED;

        if (response.isOk()) {
            callback.handleStoreComplete(this);
        } else {
            callback.handleError(this, response.getMessage());
        }
    }

    private void dispatchCopyComplete(ImapResponse response) {
        CopyReplyHandler callback =
                (CopyReplyHandler) currentCallback;
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

    private void dispatchNoopComplete(ImapResponse response) {
        NoopReplyHandler callback =
                (NoopReplyHandler) currentCallback;
        currentCallback = null;
        state = restoreBaseState();
        callback.handleOk(this);
    }

    // ── Helpers ──

    private ImapState restoreBaseState() {
        return wasSelected ? ImapState.SELECTED
                : ImapState.AUTHENTICATED;
    }

    private void fireServiceClosing(String message) {
        if (currentCallback instanceof ReplyHandler) {
            ((ReplyHandler) currentCallback).handleServiceClosing(message);
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

    /**
     * Task to resume reading after a FETCH literal handler signals
     * readiness for more data.
     */
    private class FetchResumeTask implements Runnable {
        @Override
        public void run() {
            endpoint.resumeRead();
        }
    }
}
