/*
 * NntpProtocolHandler.java
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

package org.bluezoo.gumdrop.nntp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

import org.bluezoo.gumdrop.ByteStreamLexer;
import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.ProtocolHandler;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.TokenErrorRecovery;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SaslUtils;
import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxFactory;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.nntp.server.ArticlePart;
import org.bluezoo.gumdrop.nntp.server.ArticleRef;
import org.bluezoo.gumdrop.nntp.server.ArticleState;
import org.bluezoo.gumdrop.nntp.server.ClientConnected;
import org.bluezoo.gumdrop.nntp.server.ConnectedState;
import org.bluezoo.gumdrop.nntp.server.DefaultNntpHandler;
import org.bluezoo.gumdrop.nntp.server.GroupState;
import org.bluezoo.gumdrop.nntp.server.ListState;
import org.bluezoo.gumdrop.nntp.server.ListType;
import org.bluezoo.gumdrop.nntp.server.OverState;
import org.bluezoo.gumdrop.nntp.server.PostSink;
import org.bluezoo.gumdrop.nntp.server.PostState;
import org.bluezoo.gumdrop.nntp.server.ReaderHandler;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.Span;
import org.bluezoo.gumdrop.telemetry.SpanKind;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.Trace;
import org.bluezoo.gumdrop.util.ByteBufferPool;
import org.bluezoo.gumdrop.util.DotUnstuffer;

/**
 * Server side of an NNTP connection (RFC 3977), in reader mode.
 *
 * <p>Commands are read by a streaming {@link NntpServerLexer} and run one
 * at a time: a command that arrives while another is being answered waits.
 * Those that need the newsgroup store call a {@link ReaderHandler} off the
 * event loop, which answers through the state interfaces this class
 * implements. Articles are streamed to the client in chunks, dot-stuffed
 * and with CRLF line endings, so an article of any size is sent in
 * constant memory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NntpProtocolHandler
        implements ProtocolHandler, ByteStreamLexer.Handler<NntpServerLexer.Token>,
                   ConnectedState, GroupState, ListState, ArticleState, OverState, PostState {

    static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.nntp.L10N");

    private EventLogger events() {
        // before the handler is connected, events go to a configuration of its own
        TelemetryConfig telemetry = endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig();
        return telemetry.getLogger(NntpProtocolHandler.class, L10N);
    }

    /** RFC 3977 section 3.1: a command line is at most 512 octets, CRLF included. */
    private static final int MAX_LINE_LENGTH = 512;

    /** A response line starting with this is part of a multi-line block. */
    private static final int FLUSH_THRESHOLD = 8192;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** Where an AUTHINFO SASL exchange has got to. */
    private enum Sasl { NONE, PLAIN, LOGIN_USER, LOGIN_PASS, EXTERNAL, SCRAM_FIRST, SCRAM_FINAL }

    /** The commands this server implements. */
    enum Command {
        CAPABILITIES, MODE, QUIT, DATE, HELP,
        GROUP, LISTGROUP, LAST, NEXT, ARTICLE, HEAD, BODY, STAT,
        LIST, NEWGROUPS, OVER, HDR,
        POST, STARTTLS, AUTHINFO,
        UNKNOWN
    }

    /** A command line that has been read. */
    private static final class Line {
        final Command command;
        final String unknownText;
        final String args;
        final String error;
        /** The line as sent, for SASL data, which is case sensitive and not a command. */
        final String raw;

        Line(Command command, String unknownText, String args, String error, String raw) {
            this.command = command;
            this.unknownText = unknownText;
            this.args = args;
            this.error = error;
            this.raw = raw;
        }
    }

    private Endpoint endpoint;
    private final NntpListener server;

    // The application handlers
    private ClientConnected clientConnected;
    private ReaderHandler readerHandler;
    private boolean postingAllowed;

    // Authentication (RFC 4643); authUser is null until a client has logged in
    private Realm realm;
    private String authUser;
    private String pendingUser;
    private String authMechanism = "USER/PASS";
    private boolean starttlsPending;
    private boolean quitReceived;

    // Telemetry
    private Trace connectionTrace;
    private Span sessionSpan;
    private Span authenticatedSpan;
    private long connectedAt;
    private Sasl sasl = Sasl.NONE;
    private String saslUser;
    private String saslNonce;
    private String saslChallenge;

    // An article being posted (RFC 3977 section 6.3.1)
    private PostSink postSink;
    private DotUnstuffer unstuffer;
    private boolean posting;

    // The store, opened on first use
    private MailboxStore store;
    private boolean closed;

    // The selected group
    private Mailbox mailbox;
    private String groupName;
    private long currentArticle;

    // One command is answered at a time
    private boolean busy;
    private boolean readPaused;
    private final ArrayDeque<Line> queue = new ArrayDeque<Line>();

    // The command being answered
    private Command inflight = Command.UNKNOWN;
    private ArticleRef inflightRef;
    private ArticlePart inflightPart;
    private String inflightGroup;
    private ArticleRef inflightRange;
    private String inflightWildmat;
    private ListType inflightListType;
    private Instant inflightSince;
    private String inflightField;
    private long selectedCount;
    private long selectedLow;
    private long selectedHigh;

    // Streaming lexer and per-line parse state
    private final NntpServerLexer lexer;
    private final TokenErrorRecovery<NntpServerLexer.Token> lexerRecovery =
            new TokenErrorRecovery<NntpServerLexer.Token>(NntpServerLexer.Token.CRLF);
    private final CharsetDecoder argDecoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private Command pendingCommand = Command.UNKNOWN;
    private String pendingUnknownText = "";
    private String pendingKeyword = "";
    private final StringBuilder argsBuilder = new StringBuilder();
    private int lineByteCount;
    private String lineErrorMessage;

    /**
     * Creates a handler for a new connection.
     *
     * @param server the listener that accepted the connection
     */
    public NntpProtocolHandler(NntpListener server) {
        this.server = server;
        ByteStreamLexer.checkTokenCap(MAX_LINE_LENGTH, server.getMaxNetInSize());
        this.lexer = new NntpServerLexer(this, MAX_LINE_LENGTH);
    }

    // ── ProtocolHandler ──

    @Override
    public void connected(Endpoint endpoint) {
        this.endpoint = endpoint;
        connectedAt = System.currentTimeMillis();
        NntpServerMetrics m = metrics();
        if (m != null) {
            m.connectionOpened();
        }
        initConnectionTrace();
        startSessionSpan();
        // For implicit TLS (NNTPS), the greeting waits for the handshake
        if (endpoint.isSecure()) {
            return;
        }
        sendGreetingWithHandler();
    }

    @Override
    public void receive(ByteBuffer data) {
        if (posting) {
            if (unstuffer.process(data)) {
                return; // more of the article to come
            }
            // The article is complete; anything after it is the next command
            // and waits for the article to be stored.
        }
        lexer.feed(data);
    }

    @Override
    public void disconnected() {
        try {
            abortPost();
            if (clientConnected != null) {
                clientConnected.disconnected();
            }
            endAuthenticatedSpan();
            if (quitReceived) {
                endSessionSpan();
            } else {
                endSessionSpanError("Connection lost");
            }
            NntpServerMetrics m = metrics();
            if (m != null && connectedAt != 0) {
                m.connectionClosed(System.currentTimeMillis() - connectedAt);
            }
        } finally {
            offloadClose();
        }
    }

    @Override
    public void securityEstablished(SecurityInfo info) {
        if (starttlsPending) {
            starttlsPending = false;
            NntpServerMetrics m = metrics();
            if (m != null) {
                m.starttlsUpgraded();
            }
            addSessionEvent("STARTTLS_SUCCESS");
            if (sessionSpan != null && !sessionSpan.isEnded()) {
                sessionSpan.addAttribute("net.protocol.name", "nntps");
            }
        }
        if (clientConnected == null && readerHandler == null) {
            sendGreetingWithHandler();
        }
    }

    @Override
    public void error(Exception cause) {
        events().warn("warn.nntp_transport_error").thrown(cause).emit();
        closeEndpoint();
    }

    // ── Lexer handler ──

    @Override
    public boolean token(NntpServerLexer.Token type, ByteBuffer window) {
        if (lexerRecovery.handleToken(type)) {
            return false; // discarding the rest of a line already refused
        }
        switch (type) {
            case KEYWORD:
                lineByteCount = window.remaining();
                if (lineErrorMessage == null) {
                    try {
                        String keyword = decode(window);
                        pendingKeyword = keyword;
                        pendingCommand = matchCommand(keyword);
                        if (pendingCommand == Command.UNKNOWN) {
                            pendingUnknownText = keyword.toUpperCase(Locale.ENGLISH);
                        }
                    } catch (CharacterCodingException e) {
                        lineErrorMessage = L10N.getString("nntp.err.invalid_command_encoding");
                    }
                }
                return false;
            case SP:
                lineByteCount += 1;
                return true; // the rest of the line is text
            case TEXT:
                if (lineErrorMessage == null) {
                    int len = window.remaining();
                    if (lineByteCount + len > MAX_LINE_LENGTH) {
                        lineErrorMessage = L10N.getString("nntp.err.line_too_long");
                    } else {
                        try {
                            argsBuilder.append(decode(window));
                            lineByteCount += len;
                        } catch (CharacterCodingException e) {
                            lineErrorMessage = L10N.getString("nntp.err.invalid_command_encoding");
                        }
                    }
                }
                return false;
            case CRLF:
                lineComplete();
                return false;
            default:
                return false;
        }
    }

    @Override
    public void rawBytes(ByteBuffer slice) {
        // The command channel is line based; the lexer never enters raw mode.
        events().warn("warn.unexpected_raw_bytes_server").emit();
    }

    @Override
    public void tokenTooLong() {
        lexerRecovery.beginDiscard();
        resetLineState();
        sendStatus(501, L10N.getString("nntp.err.line_too_long"));
    }

    private String decode(ByteBuffer window) throws CharacterCodingException {
        argDecoder.reset();
        return argDecoder.decode(window).toString();
    }

    private void resetLineState() {
        pendingCommand = Command.UNKNOWN;
        pendingUnknownText = "";
        pendingKeyword = "";
        argsBuilder.setLength(0);
        lineByteCount = 0;
        lineErrorMessage = null;
    }

    private static Command matchCommand(String keyword) {
        String k = keyword.toUpperCase(Locale.ENGLISH);
        if (k.equals("XOVER")) {
            return Command.OVER;
        }
        if (k.equals("XHDR")) {
            return Command.HDR;
        }
        try {
            Command c = Command.valueOf(k);
            return c == Command.UNKNOWN ? Command.UNKNOWN : c;
        } catch (IllegalArgumentException e) {
            return Command.UNKNOWN;
        }
    }

    private void lineComplete() {
        String args = argsBuilder.toString().trim();
        Line line = new Line(pendingCommand, pendingUnknownText, args, lineErrorMessage,
                args.isEmpty() ? pendingKeyword : pendingKeyword + " " + args);
        resetLineState();
        if (busy) {
            queue.add(line);
            return;
        }
        execute(line);
    }

    // ── Transport helpers ──

    private void sendRaw(String text) {
        final ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        endpoint.send(buf);
    }

    /** Sends from any thread: the send is run on the event loop. */
    private void sendRawFromAnyThread(final String text) {
        endpoint.execute(new Runnable() {
            @Override
            public void run() {
                sendRaw(text);
            }
        });
    }

    private void sendStatus(int code, String text) {
        sendRaw(code + " " + text + "\r\n");
    }

    private void closeEndpoint() {
        if (endpoint != null) {
            endpoint.close();
        }
    }

    // ── Greeting ──

    private void sendGreetingWithHandler() {
        clientConnected = server.openApplicationSession();
        if (clientConnected == null) {
            clientConnected = new DefaultNntpHandler(
                    L10N.getString("nntp.greeting"), server.isPostingAllowed());
        }
        clientConnected.connected(this, endpoint);
    }

    // ── ConnectedState ──

    @Override
    public void acceptConnection(String greeting, boolean postingAllowed,
                                 ReaderHandler handler) {
        this.readerHandler = handler;
        this.postingAllowed = postingAllowed;
        sendStatus(postingAllowed ? 200 : 201, greeting);
    }

    @Override
    public void rejectConnection() {
        sendStatus(502, L10N.getString("nntp.err.connection_rejected"));
        closeEndpoint();
    }

    @Override
    public void rejectConnection(String message) {
        sendStatus(502, message);
        closeEndpoint();
    }

    @Override
    public void serverShuttingDown() {
        sendStatus(400, L10N.getString("nntp.err.server_shutting_down"));
        closeEndpoint();
    }

    // ── Command dispatch ──

    private void execute(Line line) {
        if (sasl != Sasl.NONE) {
            // inside an AUTHINFO SASL exchange the line is data, not a command
            if (line.error != null) {
                sasl = Sasl.NONE;
                sendStatus(501, line.error);
                return;
            }
            saslData(line.raw);
            return;
        }
        if (line.error != null) {
            sendStatus(501, line.error);
            return;
        }
        if (readerHandler == null) {
            sendStatus(400, L10N.getString("nntp.err.server_shutting_down"));
            return;
        }
        String args = line.args;
        if (!allowedBeforeAuth(line.command) && authenticationRequired()) {
            sendStatus(480, L10N.getString("nntp.err.auth_required"));
            return;
        }
        NntpServerMetrics cm = metrics();
        if (cm != null) {
            cm.commandExecuted(line.command.name());
        }
        switch (line.command) {
            case STARTTLS:
                handleStartTls();
                break;
            case AUTHINFO:
                handleAuthinfo(args);
                break;
            case POST:
                handlePost();
                break;
            case CAPABILITIES:
                handleCapabilities();
                break;
            case MODE:
                handleMode(args);
                break;
            case QUIT:
                handleQuit();
                break;
            case DATE:
                sendStatus(111, DATE_FORMAT.format(Instant.now()));
                break;
            case HELP:
                handleHelp();
                break;
            case GROUP:
                handleGroup(args);
                break;
            case LISTGROUP:
                handleListGroup(args);
                break;
            case LAST:
            case NEXT:
                handleNavigate(line.command);
                break;
            case ARTICLE:
            case HEAD:
            case BODY:
            case STAT:
                handleRetrieve(line.command, args);
                break;
            case LIST:
                handleList(args);
                break;
            case NEWGROUPS:
                handleNewGroups(args);
                break;
            case OVER:
                handleOver(args);
                break;
            case HDR:
                handleHdr(args);
                break;
            default:
                sendStatus(500, L10N.getString("nntp.err.unknown_command"));
                break;
        }
    }

    // ── Commands answered here ──

    // RFC 3977 section 5.2
    private void handleCapabilities() {
        StringBuilder sb = new StringBuilder();
        sb.append("101 ").append(L10N.getString("nntp.capabilities_follow")).append("\r\n");
        sb.append("VERSION 2\r\n");
        sb.append("IMPLEMENTATION Gumdrop\r\n");
        sb.append("READER\r\n");
        if (postingAllowed) {
            sb.append("POST\r\n");
        }
        if (!endpoint.isSecure() && server.isSTARTTLSAvailable()) {
            sb.append("STARTTLS\r\n");
        }
        if (authAvailable() && authUser == null) {
            String mechanisms = saslMechanisms();
            sb.append("AUTHINFO");
            if (authAllowedHere()) {
                sb.append(" USER");
            }
            if (!mechanisms.isEmpty()) {
                sb.append(" SASL");
            }
            sb.append("\r\n");
            if (!mechanisms.isEmpty()) {
                sb.append("SASL").append(mechanisms).append("\r\n");
            }
        }
        sb.append("LIST ACTIVE NEWSGROUPS OVERVIEW.FMT\r\n");
        sb.append("OVER\r\n");
        sb.append("HDR\r\n");
        sb.append(".\r\n");
        sendRaw(sb.toString());
    }

    // RFC 3977 section 5.3
    private void handleMode(String args) {
        if (args.equalsIgnoreCase("READER")) {
            sendStatus(postingAllowed ? 200 : 201, L10N.getString("nntp.reader_mode"));
        } else {
            sendStatus(501, L10N.getString("nntp.err.unknown_mode"));
        }
    }

    // RFC 3977 section 5.4
    private void handleQuit() {
        quitReceived = true;
        sendStatus(205, L10N.getString("nntp.goodbye"));
        closeEndpoint();
    }

    // RFC 3977 section 7.2
    private void handleHelp() {
        sendRaw("100 " + L10N.getString("nntp.help_follows") + "\r\n"
                + L10N.getString("nntp.help_text") + "\r\n.\r\n");
    }

    // ── Group selection (RFC 3977 sections 6.1.1 and 6.1.2) ──

    private void handleGroup(String args) {
        if (args.isEmpty() || args.indexOf(' ') >= 0) {
            sendStatus(501, L10N.getString("nntp.err.group_required"));
            return;
        }
        beginCommand(Command.GROUP);
        inflightGroup = args;
        selectGroupOffloaded(args);
    }

    private void handleListGroup(String args) {
        String group = null;
        ArticleRef range = null;
        if (!args.isEmpty()) {
            int sp = args.indexOf(' ');
            group = sp < 0 ? args : args.substring(0, sp);
            if (sp >= 0) {
                range = parseRange(args.substring(sp + 1).trim());
                if (range == null) {
                    sendStatus(501, L10N.getString("nntp.err.bad_range"));
                    return;
                }
            }
        }
        if (group == null && mailbox == null) {
            sendStatus(412, L10N.getString("nntp.err.no_group_selected"));
            return;
        }
        beginCommand(Command.LISTGROUP);
        inflightRange = range != null ? range : ArticleRef.range(1, Long.MAX_VALUE);
        if (group == null) {
            inflightGroup = groupName;
            listCurrentGroup();
        } else {
            inflightGroup = group;
            selectGroupOffloaded(group);
        }
    }

    private void selectGroupOffloaded(final String group) {
        final ReaderHandler handler = readerHandler;
        withStore(new Runnable() {
            @Override
            public void run() {
                final MailboxStore s = store;
                offload(new Runnable() {
                    @Override
                    public void run() {
                        handler.selectGroup(NntpProtocolHandler.this, s, group);
                    }
                });
            }
        });
    }

    private void listCurrentGroup() {
        final ReaderHandler handler = readerHandler;
        final Mailbox mb = mailbox;
        final ArticleRef range = inflightRange;
        sendStatus(211, selectedCount + " " + selectedLow + " " + selectedHigh + " "
                + groupName + " " + L10N.getString("nntp.list_follows"));
        offload(new Runnable() {
            @Override
            public void run() {
                handler.listArticles(NntpProtocolHandler.this, mb, range);
            }
        });
    }

    // GroupState
    @Override
    public void selected(final Mailbox mb, final String group, final long count,
                         final long low, final long high, final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                Mailbox previous = mailbox;
                mailbox = mb;
                groupName = group;
                selectedCount = count;
                selectedLow = low;
                selectedHigh = high;
                currentArticle = count > 0 && low > 0 ? low : 0;
                if (sessionSpan != null && !sessionSpan.isEnded()) {
                    sessionSpan.addAttribute("nntp.group", group);
                }
                if (previous != null && previous != mb) {
                    closeMailboxOffloaded(previous);
                }
                if (handler != null) {
                    readerHandler = handler;
                }
                if (inflight == Command.LISTGROUP) {
                    listCurrentGroup();
                    return;
                }
                sendStatus(211, count + " " + low + " " + high + " " + group);
                finishCommand();
            }
        });
    }

    @Override
    public void noSuchGroup(final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                sendStatus(411, L10N.getString("nntp.err.no_such_group"));
                finishCommand();
            }
        });
    }

    // ── Lists (RFC 3977 sections 7.3 and 7.6) ──

    private void handleList(String args) {
        String keyword = "ACTIVE";
        String wildmat = null;
        if (!args.isEmpty()) {
            int sp = args.indexOf(' ');
            keyword = (sp < 0 ? args : args.substring(0, sp)).toUpperCase(Locale.ENGLISH);
            if (sp >= 0) {
                wildmat = args.substring(sp + 1).trim();
            }
        }
        if (keyword.equals("OVERVIEW.FMT")) {
            sendRaw("215 " + L10N.getString("nntp.overview_fmt_follows") + "\r\n"
                    + "Subject:\r\nFrom:\r\nDate:\r\nMessage-ID:\r\nReferences:\r\n"
                    + ":bytes\r\n:lines\r\n.\r\n");
            return;
        }
        ListType type;
        if (keyword.equals("ACTIVE")) {
            type = ListType.ACTIVE;
        } else if (keyword.equals("NEWSGROUPS")) {
            type = ListType.NEWSGROUPS;
        } else {
            sendStatus(501, L10N.getString("nntp.err.unknown_list_keyword"));
            return;
        }
        startGroupList(Command.LIST, type, wildmat, null);
    }

    private void handleNewGroups(String args) {
        String[] parts = args.split("\\s+");
        Instant since = parts.length >= 2 ? parseDateTime(parts[0], parts[1]) : null;
        if (since == null || parts.length > 3
                || (parts.length == 3 && !parts[2].equalsIgnoreCase("GMT"))) {
            sendStatus(501, L10N.getString("nntp.err.bad_date"));
            return;
        }
        startGroupList(Command.NEWGROUPS, ListType.NEW_GROUPS, null, since);
    }

    private void startGroupList(Command command, final ListType type,
                                final String wildmat, final Instant since) {
        beginCommand(command);
        inflightListType = type;
        inflightWildmat = wildmat;
        inflightSince = since;
        final ReaderHandler handler = readerHandler;
        withStore(new Runnable() {
            @Override
            public void run() {
                final MailboxStore s = store;
                offload(new Runnable() {
                    @Override
                    public void run() {
                        handler.listGroups(NntpProtocolHandler.this, s, type, wildmat, since);
                    }
                });
            }
        });
    }

    // ListState
    @Override
    public ListWriter begin() {
        final Command command = inflight;
        final ListType type = inflightListType;
        String first;
        switch (command) {
            case NEWGROUPS:
                first = "231 " + L10N.getString("nntp.newgroups_follow");
                break;
            case LISTGROUP:
                first = null; // sent when the group was selected
                break;
            default:
                first = "215 " + L10N.getString(type == ListType.NEWSGROUPS
                        ? "nntp.descriptions_follow" : "nntp.list_information_follows");
                break;
        }
        ListOut out = new ListOut();
        if (first != null) {
            out.line(first);
        }
        return out;
    }

    // ── Articles (RFC 3977 sections 6.1.3 to 6.2.4) ──

    private void handleNavigate(Command command) {
        if (mailbox == null) {
            sendStatus(412, L10N.getString("nntp.err.no_group_selected"));
            return;
        }
        if (currentArticle == 0) {
            sendStatus(420, L10N.getString("nntp.err.no_current_article"));
            return;
        }
        ArticleRef ref = command == Command.LAST
                ? ArticleRef.previous(currentArticle) : ArticleRef.next(currentArticle);
        startRetrieve(command, ref, ArticlePart.STAT);
    }

    private void handleRetrieve(Command command, String args) {
        ArticlePart part = ArticlePart.valueOf(command.name());
        ArticleRef ref;
        if (args.isEmpty()) {
            if (mailbox == null) {
                sendStatus(412, L10N.getString("nntp.err.no_group_selected"));
                return;
            }
            if (currentArticle == 0) {
                sendStatus(420, L10N.getString("nntp.err.no_current_article"));
                return;
            }
            ref = ArticleRef.current(currentArticle);
        } else if (args.charAt(0) == '<') {
            if (!isMessageId(args)) {
                sendStatus(501, L10N.getString("nntp.err.bad_message_id"));
                return;
            }
            ref = ArticleRef.messageId(args);
        } else {
            long number = parseNumber(args);
            if (number < 0) {
                sendStatus(501, L10N.getString("nntp.err.bad_article_number"));
                return;
            }
            if (mailbox == null) {
                sendStatus(412, L10N.getString("nntp.err.no_group_selected"));
                return;
            }
            ref = ArticleRef.number(number);
        }
        startRetrieve(command, ref, part);
    }

    private void startRetrieve(Command command, final ArticleRef ref, final ArticlePart part) {
        beginCommand(command);
        inflightRef = ref;
        inflightPart = part;
        final ReaderHandler handler = readerHandler;
        final Mailbox mb = mailbox;
        withStore(new Runnable() {
            @Override
            public void run() {
                offload(new Runnable() {
                    @Override
                    public void run() {
                        handler.retrieve(NntpProtocolHandler.this, mb, ref, part);
                    }
                });
            }
        });
    }

    // ArticleState
    @Override
    public void found(final long number, final String messageId,
                      final AsyncMessageContent content, final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                ArticleRef ref = inflightRef;
                boolean byMessageId = ref.getKind() == ArticleRef.Kind.MESSAGE_ID;
                if (!byMessageId) {
                    currentArticle = number;
                }
                long shown = byMessageId ? 0 : number;
                ArticlePart part = inflightPart;
                if (part == ArticlePart.STAT || content == null) {
                    closeContent(content);
                    sendStatus(223, shown + " " + messageId);
                    finishCommand();
                    return;
                }
                int code = part == ArticlePart.ARTICLE ? 220 : part == ArticlePart.HEAD ? 221 : 222;
                String text = L10N.getString(part == ArticlePart.ARTICLE
                        ? "nntp.article_follows"
                        : part == ArticlePart.HEAD ? "nntp.head_follows" : "nntp.body_follows");
                sendStatus(code, shown + " " + messageId + " " + text);
                new ArticleWriter(content, part).start();
            }
        });
    }

    @Override
    public void notFound(final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                switch (inflight) {
                    case OVER:
                    case HDR:
                        if (inflightRef.getKind() == ArticleRef.Kind.MESSAGE_ID) {
                            sendStatus(430, L10N.getString("nntp.err.no_such_message_id"));
                        } else {
                            sendStatus(423, L10N.getString("nntp.err.no_articles_in_range"));
                        }
                        break;
                    default:
                        switch (inflightRef.getKind()) {
                            case MESSAGE_ID:
                                sendStatus(430, L10N.getString("nntp.err.no_such_message_id"));
                                break;
                            case NEXT:
                                sendStatus(421, L10N.getString("nntp.err.no_next_article"));
                                break;
                            case PREVIOUS:
                                sendStatus(422, L10N.getString("nntp.err.no_previous_article"));
                                break;
                            case CURRENT:
                                sendStatus(420, L10N.getString("nntp.err.no_current_article"));
                                break;
                            default:
                                sendStatus(423, L10N.getString("nntp.err.no_such_article_number"));
                                break;
                        }
                        break;
                }
                finishCommand();
            }
        });
    }

    // Shared by GroupState, ListState, ArticleState and OverState
    @Override
    public void error(final String message, final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                int code = inflight == Command.POST ? 441 : 403;
                sendStatus(code, message != null ? message : L10N.getString("nntp.err.internal_fault"));
                finishCommand();
            }
        });
    }

    // ── OVER and HDR (RFC 3977 sections 8.3 and 8.5) ──

    private void handleOver(String args) {
        ArticleRef ref = overRef(args);
        if (ref != null) {
            startOver(Command.OVER, ref, null);
        }
    }

    private void handleHdr(String args) {
        int sp = args.indexOf(' ');
        String field = sp < 0 ? args : args.substring(0, sp);
        if (field.isEmpty()) {
            sendStatus(501, L10N.getString("nntp.err.field_required"));
            return;
        }
        ArticleRef ref = overRef(sp < 0 ? "" : args.substring(sp + 1).trim());
        if (ref != null) {
            startOver(Command.HDR, ref, field);
        }
    }

    /** Resolves the range or message-id argument of OVER and HDR; answers and returns null if it is bad. */
    private ArticleRef overRef(String arg) {
        if (arg.isEmpty()) {
            if (mailbox == null) {
                sendStatus(412, L10N.getString("nntp.err.no_group_selected"));
                return null;
            }
            if (currentArticle == 0) {
                sendStatus(420, L10N.getString("nntp.err.no_current_article"));
                return null;
            }
            return ArticleRef.number(currentArticle);
        }
        if (arg.charAt(0) == '<') {
            if (!isMessageId(arg)) {
                sendStatus(501, L10N.getString("nntp.err.bad_message_id"));
                return null;
            }
            return ArticleRef.messageId(arg);
        }
        ArticleRef range = parseRange(arg);
        if (range == null) {
            sendStatus(501, L10N.getString("nntp.err.bad_range"));
            return null;
        }
        if (mailbox == null) {
            sendStatus(412, L10N.getString("nntp.err.no_group_selected"));
            return null;
        }
        return range;
    }

    private void startOver(Command command, final ArticleRef ref, final String field) {
        beginCommand(command);
        inflightRef = ref;
        inflightField = field;
        final ReaderHandler handler = readerHandler;
        final Mailbox mb = mailbox;
        final Command c = command;
        withStore(new Runnable() {
            @Override
            public void run() {
                offload(new Runnable() {
                    @Override
                    public void run() {
                        if (c == Command.OVER) {
                            handler.overview(NntpProtocolHandler.this, mb, ref);
                        } else {
                            handler.headers(NntpProtocolHandler.this, mb, ref, field);
                        }
                    }
                });
            }
        });
    }

    // OverState
    @Override
    public OverviewWriter beginOverview() {
        ListOut out = new ListOut();
        out.line("224 " + L10N.getString("nntp.overview_follows"));
        return out;
    }

    @Override
    public HeaderWriter beginHeaders() {
        ListOut out = new ListOut();
        out.line("225 " + L10N.getString("nntp.headers_follow"));
        return out;
    }

    /**
     * Writes a multi-line response. Entries come from the handler's thread, so
     * they are collected and sent to the event loop in blocks.
     */
    private final class ListOut implements ListWriter, OverviewWriter, HeaderWriter {

        private final StringBuilder sb = new StringBuilder();

        void line(String text) {
            if (text.length() > 0 && text.charAt(0) == '.') {
                sb.append('.');
            }
            sb.append(text).append("\r\n");
            if (sb.length() >= FLUSH_THRESHOLD) {
                flush();
            }
        }

        private void flush() {
            if (sb.length() > 0) {
                String s = sb.toString();
                sb.setLength(0);
                sendRawFromAnyThread(s);
            }
        }

        @Override
        public void group(String name, long high, long low, char status) {
            line(name + " " + high + " " + low + " " + status);
        }

        @Override
        public void description(String name, String text) {
            line(name + "\t" + clean(text));
        }

        @Override
        public void article(long number) {
            line(Long.toString(number));
        }

        @Override
        public void entry(long number, String subject, String from, String date,
                          String messageId, String references, long bytes, long lines) {
            line(number + "\t" + clean(subject) + "\t" + clean(from) + "\t" + clean(date)
                    + "\t" + clean(messageId) + "\t" + clean(references) + "\t" + bytes
                    + "\t" + (lines < 0 ? "" : Long.toString(lines)));
        }

        @Override
        public void entry(long number, String value) {
            line(number + " " + clean(value));
        }

        @Override
        public void end(final ReaderHandler handler) {
            sb.append(".\r\n");
            flush();
            onLoop(new Runnable() {
                @Override
                public void run() {
                    useHandler(handler);
                    finishCommand();
                }
            });
        }
    }

    /** RFC 3977 section 8.3.2: tabs and line breaks in a field become spaces. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        int n = value.length();
        StringBuilder sb = null;
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            if (c == '\t' || c == '\r' || c == '\n') {
                if (sb == null) {
                    sb = new StringBuilder(value);
                }
                sb.setCharAt(i, ' ');
            }
        }
        return sb == null ? value : sb.toString();
    }

    // ── Article content ──

    /**
     * Sends an article, its head or its body, a chunk at a time. Lines end
     * in CRLF whatever the stored ones end in, and lines starting with a dot
     * get another (RFC 3977 section 3.1.1). It holds only the chunk it is
     * working on, and waits for the connection to drain between chunks.
     */
    private final class ArticleWriter implements Runnable {

        private static final int CHUNK_SIZE = 16384;

        private final AsyncMessageContent content;
        private final ArticlePart part;
        private long sent;
        private long position;
        private boolean inHeader = true;
        private boolean lineEmpty = true;
        private boolean pendingCR;
        private boolean done;
        private byte[] out;
        private int outLen;

        ArticleWriter(AsyncMessageContent content, ArticlePart part) {
            this.content = content;
            this.part = part;
        }

        void start() {
            readNext();
        }

        @Override
        public void run() {
            readNext();
        }

        private void readNext() {
            if (done) {
                finish();
                return;
            }
            final ByteBuffer buf = ByteBufferPool.acquire(CHUNK_SIZE);
            buf.limit(CHUNK_SIZE);
            content.read(buf, position, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(final Integer result, final ByteBuffer attachment) {
                    endpoint.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (result == null || result.intValue() <= 0) {
                                ByteBufferPool.release(attachment);
                                done = true;
                                finish();
                                return;
                            }
                            position += result.intValue();
                            attachment.flip();
                            process(attachment);
                            ByteBufferPool.release(attachment);
                            if (done) {
                                finish();
                            } else {
                                endpoint.onWriteReady(ArticleWriter.this);
                            }
                        }
                    });
                }

                @Override
                public void failed(final Throwable exc, final ByteBuffer attachment) {
                    endpoint.execute(new Runnable() {
                        @Override
                        public void run() {
                            ByteBufferPool.release(attachment);
                            events().warn("warn.async_content_read_failed").thrown(exc).emit();
                            endpoint.onWriteReady(null);
                            closeContent(content);
                            closeEndpoint();
                        }
                    });
                }
            });
        }

        private boolean emitting() {
            return part == ArticlePart.ARTICLE
                    || (part == ArticlePart.HEAD && inHeader)
                    || (part == ArticlePart.BODY && !inHeader);
        }

        private void put(int b) {
            out[outLen++] = (byte) b;
        }

        private void putCRLF() {
            put('\r');
            put('\n');
        }

        private void character(int b) {
            boolean first = lineEmpty;
            lineEmpty = false;
            if (emitting()) {
                if (first && b == '.') {
                    put('.');
                }
                put(b);
            }
        }

        private void endLine() {
            boolean blank = lineEmpty;
            lineEmpty = true;
            if (inHeader && blank) {
                inHeader = false;
                if (part == ArticlePart.ARTICLE) {
                    putCRLF();
                } else if (part == ArticlePart.HEAD) {
                    done = true;
                }
                return;
            }
            if (emitting()) {
                putCRLF();
            }
        }

        private void process(ByteBuffer in) {
            // at most two bytes out for each one in
            out = new byte[in.remaining() * 2 + 8];
            outLen = 0;
            while (in.hasRemaining() && !done) {
                byte b = in.get();
                if (pendingCR) {
                    pendingCR = false;
                    if (b == '\n') {
                        endLine();
                        continue;
                    }
                    character('\r');
                }
                if (b == '\r') {
                    pendingCR = true;
                } else if (b == '\n') {
                    endLine();
                } else {
                    character(b & 0xff);
                }
            }
            send();
        }

        private void send() {
            if (outLen > 0) {
                sent += outLen;
                endpoint.send(ByteBuffer.wrap(out, 0, outLen));
            }
            out = null;
            outLen = 0;
        }

        private void finish() {
            // the last line may have no line ending
            out = new byte[8];
            outLen = 0;
            if (pendingCR) {
                pendingCR = false;
                character('\r');
            }
            if (!lineEmpty && emitting()) {
                putCRLF();
            }
            put('.');
            putCRLF();
            send();
            endpoint.onWriteReady(null);
            closeContent(content);
            NntpServerMetrics m = metrics();
            if (m != null) {
                m.articleRetrieved(sent);
            }
            addSessionEvent(part == ArticlePart.ARTICLE ? "ARTICLE_SENT"
                    : part == ArticlePart.HEAD ? "HEAD_SENT" : "BODY_SENT");
            finishCommand();
        }
    }

    private void closeContent(AsyncMessageContent content) {
        if (content != null) {
            try {
                content.close();
            } catch (IOException e) {
                events().warn("warn.error_closing_async_content").thrown(e).emit();
            }
        }
    }

    // ── STARTTLS (RFC 4642) ──

    private void handleStartTls() {
        if (endpoint.isSecure()) {
            sendStatus(502, L10N.getString("nntp.err.already_tls"));
            return;
        }
        if (!server.isSTARTTLSAvailable()) {
            sendStatus(580, L10N.getString("nntp.err.starttls_unavailable"));
            return;
        }
        sendStatus(382, L10N.getString("nntp.starttls_begin"));
        // Whatever the client sent after STARTTLS was sent in the clear and is dropped.
        queue.clear();
        try {
            endpoint.startTLS();
            starttlsPending = true;
        } catch (IOException e) {
            events().error("warn.failed_init_tls").thrown(e).emit();
            addSessionEvent("STARTTLS_FAILURE");
            recordSessionException(e);
            closeEndpoint();
            return;
        }
        // RFC 4642 section 2.2.2: knowledge from before the upgrade is discarded
        authUser = null;
        pendingUser = null;
        resetGroup();
    }

    // ── AUTHINFO USER/PASS (RFC 4643 section 2.3) ──

    private Realm getRealm() {
        if (realm == null) {
            Realm serverRealm = server.getRealm();
            if (serverRealm != null) {
                SelectorLoop loop = endpoint.getSelectorLoop();
                realm = loop != null ? serverRealm.forSelectorLoop(loop) : serverRealm;
            }
        }
        return realm;
    }

    private boolean authAvailable() {
        return server.getRealm() != null;
    }

    /** Passwords travel in the clear, so they are accepted only under TLS where TLS can be had. */
    private boolean authAllowedHere() {
        return endpoint.isSecure() || !server.isSTARTTLSAvailable();
    }

    private boolean authenticationRequired() {
        return server.isAuthenticationRequired() && authUser == null;
    }

    private static boolean allowedBeforeAuth(Command command) {
        switch (command) {
            case CAPABILITIES:
            case QUIT:
            case AUTHINFO:
            case STARTTLS:
            case MODE:
            case HELP:
            case UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    private void handleAuthinfo(String args) {
        if (!authAvailable()) {
            sendStatus(500, L10N.getString("nntp.err.unknown_command"));
            return;
        }
        if (authUser != null) {
            sendStatus(502, L10N.getString("nntp.err.already_authenticated"));
            return;
        }
        int space = args.indexOf(' ');
        String sub = (space < 0 ? args : args.substring(0, space)).toUpperCase(Locale.ENGLISH);
        String value = space < 0 ? "" : args.substring(space + 1).trim();
        if (sub.equals("USER")) {
            if (value.isEmpty()) {
                sendStatus(501, L10N.getString("nntp.err.username_required"));
                return;
            }
            if (!authAllowedHere()) {
                sendStatus(483, L10N.getString("nntp.err.encryption_required"));
                return;
            }
            pendingUser = value;
            sendStatus(381, L10N.getString("nntp.password_required"));
        } else if (sub.equals("PASS")) {
            if (pendingUser == null) {
                sendStatus(482, L10N.getString("nntp.err.user_required_first"));
                return;
            }
            if (value.isEmpty()) {
                sendStatus(501, L10N.getString("nntp.err.password_required"));
                return;
            }
            authMechanism = "USER/PASS";
            authenticate(pendingUser, value);
        } else if (sub.equals("SASL")) {
            startSasl(value);
        } else {
            sendStatus(501, L10N.getString("nntp.err.unknown_authinfo"));
        }
    }

    private void authenticate(final String user, String password) {
        if (server.isAuthLockedOut(endpoint.getRemoteAddress())) {
            sendStatus(481, L10N.getString("nntp.err.auth_locked"));
            return;
        }
        Realm r = getRealm();
        beginCommand(Command.AUTHINFO);
        pendingUser = null;
        endpoint.pauseRead();
        readPaused = true;
        // Realm.passwordMatch can wait on a directory or token server: the realm
        // calls back when it has an answer, and the event loop is not held.
        r.passwordMatch(user, password, new RealmCallback<Boolean>() {
            @Override
            public void completed(final Boolean ok) {
                onLoop(new Runnable() {
                    @Override
                    public void run() {
                        authenticated(user, ok != null && ok.booleanValue());
                    }
                });
            }

            @Override
            public void failed(final Throwable cause) {
                onLoop(new Runnable() {
                    @Override
                    public void run() {
                        events().error("warn.error_during_authentication").attr("username", user)
                                .thrown(cause).emit();
                        sendStatus(403, L10N.getString("nntp.err.internal_fault"));
                        finishCommand();
                    }
                });
            }
        });
    }

    private void authenticated(String user, boolean ok) {
        authenticated(user, ok, null);
    }

    /** @param serverFinal base64 data to send with the success (283), or null for a plain 281 */
    private void authenticated(String user, boolean ok, String serverFinal) {
        NntpServerMetrics m = metrics();
        if (m != null) {
            m.authAttempt(authMechanism);
            if (ok) {
                m.authSuccess(authMechanism);
            } else {
                m.authFailure(authMechanism);
            }
        }
        if (!ok) {
            recordAuthenticationFailure(user);
            server.recordAuthFailure(endpoint.getRemoteAddress(), user);
            events().warn("log.auth_failed").attr("username", user).emit();
            sendStatus(481, L10N.getString("nntp.err.auth_failed"));
            finishCommand();
            return;
        }
        server.recordAuthSuccess(endpoint.getRemoteAddress(), user);
        events().info("log.auth_successful").attr("username", user).emit();
        authUser = user;
        recordAuthenticationSuccess(user);
        // The store was opened for the previous user (RFC 4643 section 2.2)
        resetGroup();
        final MailboxStore previous = store;
        store = null;
        if (previous != null) {
            closeStoreOffloaded(previous);
        }
        if (serverFinal != null) {
            sendStatus(283, serverFinal);
        } else {
            sendStatus(281, L10N.getString("nntp.authenticated"));
        }
        finishCommand();
    }

    // ── AUTHINFO SASL (RFC 4643 section 2.4) ──

    /** The mechanisms offered, as a list each led by a space: PLAIN and SCRAM-SHA-256. */
    private String saslMechanisms() {
        Realm r = server.getRealm();
        if (r == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        java.util.Set<SaslMechanism> supported = r.getSupportedSASLMechanisms();
        SaslMechanism[] ours = {SaslMechanism.PLAIN, SaslMechanism.LOGIN, SaslMechanism.SCRAM_SHA_256,
                SaslMechanism.EXTERNAL};
        for (int i = 0; i < ours.length; i++) {
            SaslMechanism m = ours[i];
            // EXTERNAL rests on the client certificate of a TLS connection
            boolean allowed = endpoint.isSecure() || (!m.requiresTLS() && m != SaslMechanism.EXTERNAL);
            if (supported.contains(m) && allowed) {
                sb.append(' ').append(m.getMechanismName());
            }
        }
        return sb.toString();
    }

    private void startSasl(String value) {
        int space = value.indexOf(' ');
        String name = space < 0 ? value : value.substring(0, space);
        String initial = space < 0 ? null : value.substring(space + 1).trim();
        if (name.isEmpty()) {
            sendStatus(501, L10N.getString("nntp.err.mechanism_required"));
            return;
        }
        SaslMechanism mechanism = SaslMechanism.fromName(name);
        if (mechanism != null) {
            authMechanism = mechanism.getMechanismName();
        }
        if (mechanism == null || !(" " + saslMechanisms() + " ").contains(" " + mechanism.getMechanismName() + " ")) {
            SaslMechanism known = SaslMechanism.fromName(name);
            if (known != null && known.requiresTLS() && !endpoint.isSecure() && server.getRealm() != null
                    && server.getRealm().getSupportedSASLMechanisms().contains(known)) {
                sendStatus(483, L10N.getString("nntp.err.encryption_required"));
            } else {
                sendStatus(504, L10N.getString("nntp.err.unsupported_mechanism"));
            }
            return;
        }
        if (server.isAuthLockedOut(endpoint.getRemoteAddress())) {
            sendStatus(481, L10N.getString("nntp.err.auth_locked"));
            return;
        }
        switch (mechanism) {
            case PLAIN:
                sasl = Sasl.PLAIN;
                break;
            case LOGIN:
                sasl = Sasl.LOGIN_USER;
                break;
            case EXTERNAL:
                sasl = Sasl.EXTERNAL;
                break;
            default:
                sasl = Sasl.SCRAM_FIRST;
                break;
        }
        if (initial == null || initial.isEmpty()) {
            // LOGIN asks for the user name; the others have no data to ask for yet
            sendStatus(383, sasl == Sasl.LOGIN_USER ? SaslUtils.encodeBase64("Username:") : "=");
        } else {
            saslData(initial);
        }
    }

    /** A line of data from the client during an exchange. */
    private void saslData(String data) {
        if (data.equals("*")) {
            sasl = Sasl.NONE;
            sendStatus(481, L10N.getString("nntp.err.auth_cancelled"));
            return;
        }
        byte[] decoded;
        try {
            decoded = data.equals("=") ? new byte[0] : SaslUtils.decodeBase64(data);
        } catch (IllegalArgumentException e) {
            sasl = Sasl.NONE;
            sendStatus(501, L10N.getString("nntp.err.invalid_base64"));
            return;
        }
        Sasl stage = sasl;
        switch (stage) {
            case PLAIN:
                sasl = Sasl.NONE;
                String[] parts;
                try {
                    parts = SaslUtils.parsePlainCredentials(decoded);
                } catch (RuntimeException e) {
                    parts = null; // not authzid NUL authcid NUL password
                }
                if (parts == null) {
                    sendStatus(481, L10N.getString("nntp.err.auth_failed"));
                    return;
                }
                authenticate(parts[0].isEmpty() ? parts[1] : parts[0], parts[2]);
                break;
            case LOGIN_USER:
                saslUser = new String(decoded, StandardCharsets.UTF_8);
                sasl = Sasl.LOGIN_PASS;
                sendStatus(383, SaslUtils.encodeBase64("Password:"));
                break;
            case LOGIN_PASS:
                sasl = Sasl.NONE;
                authenticate(saslUser, new String(decoded, StandardCharsets.UTF_8));
                break;
            case EXTERNAL:
                sasl = Sasl.NONE;
                external(decoded.length == 0 ? null : new String(decoded, StandardCharsets.UTF_8));
                break;
            case SCRAM_FIRST:
                scramFirst(new String(decoded, StandardCharsets.UTF_8));
                break;
            case SCRAM_FINAL:
                scramFinal(new String(decoded, StandardCharsets.UTF_8));
                break;
            default:
                break;
        }
    }

    // RFC 4422 appendix A: the identity is the TLS client certificate; the
    // client may ask to act as another user the realm allows
    private void external(final String authzid) {
        final String label = authzid != null ? authzid : "external";
        saslUser = label;
        beginCommand(Command.AUTHINFO);
        endpoint.pauseRead();
        readPaused = true;
        SaslUtils.authenticateExternal(endpoint, getRealm(), authzid,
                new RealmCallback<Realm.CertificateAuthenticationResult>() {
            @Override
            public void completed(final Realm.CertificateAuthenticationResult result) {
                onLoop(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && result.valid) {
                            authenticated(result.username, true);
                        } else {
                            authenticated(label, false);
                        }
                    }
                });
            }

            @Override
            public void failed(final Throwable cause) {
                scramFailed(cause);
            }
        });
    }

    // RFC 5802 section 5: client-first-message is "n,,n=user,r=nonce"
    private void scramFirst(String clientFirst) {
        if (!clientFirst.startsWith("n,,")) {
            sasl = Sasl.NONE;
            sendStatus(501, L10N.getString("nntp.err.invalid_scram_message"));
            return;
        }
        String bare = clientFirst.substring(3);
        String user = null;
        String clientNonce = null;
        String[] attrs = bare.split(",");
        for (int i = 0; i < attrs.length; i++) {
            if (attrs[i].startsWith("n=")) {
                user = attrs[i].substring(2);
            } else if (attrs[i].startsWith("r=")) {
                clientNonce = attrs[i].substring(2);
            }
        }
        if (user == null || clientNonce == null || user.isEmpty()) {
            sasl = Sasl.NONE;
            sendStatus(501, L10N.getString("nntp.err.invalid_scram_message"));
            return;
        }
        final String name = user.replace("=2C", ",").replace("=3D", "=");
        saslUser = name;
        final String nonce = clientNonce;
        final String clientFirstBare = bare;
        beginCommand(Command.AUTHINFO);
        endpoint.pauseRead();
        readPaused = true;
        getRealm().getScramCredentials(name, new RealmCallback<Realm.ScramCredentials>() {
            @Override
            public void completed(final Realm.ScramCredentials creds) {
                onLoop(new Runnable() {
                    @Override
                    public void run() {
                        if (creds == null) {
                            sasl = Sasl.NONE;
                            authenticated(name, false);
                            return;
                        }
                        saslUser = name;
                        saslNonce = nonce + SaslUtils.generateNonce(16);
                        String serverFirst = SaslUtils.generateScramServerFirst(saslNonce, creds.salt,
                                creds.iterations);
                        saslChallenge = clientFirstBare + "," + serverFirst;
                        sasl = Sasl.SCRAM_FINAL;
                        sendStatus(383, SaslUtils.encodeBase64(serverFirst));
                        finishCommand();
                    }
                });
            }

            @Override
            public void failed(final Throwable cause) {
                scramFailed(cause);
            }
        });
    }

    private void scramFinal(final String clientFinal) {
        final String name = saslUser;
        final String challenge = saslChallenge;
        final String nonce = saslNonce;
        sasl = Sasl.NONE;
        beginCommand(Command.AUTHINFO);
        endpoint.pauseRead();
        readPaused = true;
        getRealm().getScramCredentials(name, new RealmCallback<Realm.ScramCredentials>() {
            @Override
            public void completed(final Realm.ScramCredentials creds) {
                onLoop(new Runnable() {
                    @Override
                    public void run() {
                        byte[] signature = creds == null ? null
                                : SaslUtils.verifyScramClientFinal(creds, challenge, clientFinal, nonce);
                        if (signature == null) {
                            authenticated(name, false);
                        } else {
                            authenticated(name, true, SaslUtils.encodeBase64(
                                    "v=" + java.util.Base64.getEncoder().encodeToString(signature)));
                        }
                    }
                });
            }

            @Override
            public void failed(final Throwable cause) {
                scramFailed(cause);
            }
        });
    }

    private void scramFailed(final Throwable cause) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                sasl = Sasl.NONE;
                if (cause instanceof UnsupportedOperationException) {
                    sendStatus(504, L10N.getString("nntp.err.unsupported_mechanism"));
                } else {
                    events().error("warn.error_during_authentication").attr("username", saslUser)
                            .thrown(cause).emit();
                    sendStatus(403, L10N.getString("nntp.err.internal_fault"));
                }
                finishCommand();
            }
        });
    }

    /** Forgets the selected group, closing its mailbox. */
    private void resetGroup() {
        Mailbox previous = mailbox;
        mailbox = null;
        groupName = null;
        currentArticle = 0;
        selectedCount = 0;
        selectedLow = 0;
        selectedHigh = 0;
        if (previous != null) {
            closeMailboxOffloaded(previous);
        }
    }

    // ── POST (RFC 3977 section 6.3.1) ──

    private void handlePost() {
        if (!postingAllowed) {
            recordPostRejected();
            sendStatus(440, L10N.getString("nntp.err.posting_not_permitted"));
            return;
        }
        beginCommand(Command.POST);
        final ReaderHandler handler = readerHandler;
        withStore(new Runnable() {
            @Override
            public void run() {
                final MailboxStore st = store;
                offload(new Runnable() {
                    @Override
                    public void run() {
                        handler.post(NntpProtocolHandler.this, st);
                    }
                });
            }
        });
    }

    // PostState
    @Override
    public void accept(final PostSink sink) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                postSink = sink;
                unstuffer = new DotUnstuffer(new PostReceiver());
                posting = true;
                sendStatus(340, L10N.getString("nntp.send_article"));
                // reads were held while the handler decided; the article may now arrive
                if (readPaused) {
                    readPaused = false;
                    endpoint.resumeRead();
                }
            }
        });
    }

    @Override
    public void refuse(final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                recordPostRejected();
                sendStatus(440, L10N.getString("nntp.err.posting_not_permitted"));
                finishCommand();
            }
        });
    }

    @Override
    public void posted(final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                NntpServerMetrics m = metrics();
                if (m != null) {
                    m.articlePosted();
                }
                addSessionEvent("ARTICLE_POSTED");
                sendStatus(240, L10N.getString("nntp.article_received"));
                finishCommand();
            }
        });
    }

    @Override
    public void notPosted(final String message, final ReaderHandler handler) {
        onLoop(new Runnable() {
            @Override
            public void run() {
                useHandler(handler);
                recordPostRejected();
                sendStatus(441, message != null ? message : L10N.getString("nntp.err.post_failed"));
                finishCommand();
            }
        });
    }

    /** Passes the unstuffed article to the sink, and ends the post. */
    private final class PostReceiver implements DotUnstuffer.Callback {

        @Override
        public void content(ByteBuffer chunk) {
            PostSink sink = postSink;
            if (sink != null) {
                sink.content(chunk);
            }
        }

        @Override
        public void complete() {
            final PostSink sink = postSink;
            postSink = null;
            posting = false;
            offload(new Runnable() {
                @Override
                public void run() {
                    sink.end(NntpProtocolHandler.this);
                }
            });
        }
    }

    /** The connection ended part way through an article. */
    private void abortPost() {
        PostSink sink = postSink;
        postSink = null;
        posting = false;
        if (sink != null) {
            sink.abort();
        }
    }

    // ── Telemetry ──

    private NntpServerMetrics metrics() {
        return server.getMetrics();
    }

    private void initConnectionTrace() {
        TelemetryConfig config = endpoint.getTelemetryConfig();
        if (config != null) {
            connectionTrace = config.createTrace(L10N.getString("telemetry.nntp_connection"));
            if (connectionTrace == null) {
                return; // tracing is off, as when only metrics are wanted
            }
            Span root = connectionTrace.getRootSpan();
            root.addAttribute("net.transport", "ip_tcp");
            root.addAttribute("net.peer.ip", String.valueOf(endpoint.getRemoteAddress()));
            root.addAttribute("rpc.system", "nntp");
        }
    }

    private void startSessionSpan() {
        if (connectionTrace != null) {
            sessionSpan = connectionTrace.startSpan(L10N.getString("telemetry.nntp_session"), SpanKind.SERVER);
            sessionSpan.addEvent("SESSION_START");
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

    private void recordAuthenticationSuccess(String user) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute("nntp.auth.mechanism", authMechanism);
            sessionSpan.addAttribute("enduser.id", user);
            addSessionEvent("AUTH_SUCCESS");
            endAuthenticatedSpan();
            authenticatedSpan = sessionSpan.startChild(L10N.getString("telemetry.nntp_authenticated"),
                    SpanKind.SERVER);
            authenticatedSpan.addAttribute("enduser.id", user);
            authenticatedSpan.addEvent("AUTHENTICATED");
        }
    }

    private void recordAuthenticationFailure(String user) {
        if (sessionSpan != null && !sessionSpan.isEnded()) {
            sessionSpan.addAttribute("nntp.auth.mechanism", authMechanism);
            if (user != null) {
                sessionSpan.addAttribute("nntp.auth.attempted_user", user);
            }
            addSessionEvent("AUTH_FAILURE");
        }
    }

    private void recordPostRejected() {
        NntpServerMetrics m = metrics();
        if (m != null) {
            m.articleRejected();
        }
        addSessionEvent("POST_REJECTED");
    }

    // ── Running handler calls ──

    private void beginCommand(Command command) {
        busy = true;
        inflight = command;
        inflightRef = null;
        inflightPart = null;
        inflightGroup = null;
        inflightRange = null;
        inflightWildmat = null;
        inflightListType = null;
        inflightSince = null;
        inflightField = null;
    }

    /** A command is complete: reads resume and queued commands run. */
    private void finishCommand() {
        busy = false;
        inflight = Command.UNKNOWN;
        if (readPaused) {
            readPaused = false;
            endpoint.resumeRead();
        }
        while (!busy && !queue.isEmpty() && endpoint.isOpen()) {
            execute(queue.poll());
        }
    }

    private void useHandler(ReaderHandler handler) {
        if (handler != null) {
            readerHandler = handler;
        }
    }

    /** Runs a state callback's work on the event loop. */
    private void onLoop(Runnable task) {
        endpoint.execute(task);
    }

    /** Runs the application handler off the event loop; reads wait until it has answered. */
    private void offload(final Runnable call) {
        if (!readPaused) {
            readPaused = true;
            endpoint.pauseRead();
        }
        submitStorage(new Callable<Void>() {
            @Override
            public Void call() {
                call.run();
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
                // the handler answers through its state
            }

            @Override
            public void failed(Throwable error) {
                events().warn("warn.reader_handler_failed").thrown(error).emit();
                sendStatus(403, L10N.getString("nntp.err.internal_fault"));
                finishCommand();
            }
        });
    }

    private <T> void submitStorage(final Callable<T> op,
                                   final StorageExecutor.Callback<T> callback) {
        SelectorLoop loop = (endpoint != null) ? endpoint.getSelectorLoop() : null;
        Gumdrop gumdrop = (loop != null) ? loop.getGumdrop() : null;
        StorageExecutor exec = (gumdrop != null) ? gumdrop.getStorageExecutor() : null;
        if (exec == null || endpoint == null) {
            T result;
            try {
                result = op.call();
            } catch (Throwable t) {
                callback.failed(t);
                return;
            }
            callback.completed(result);
            return;
        }
        exec.submit(endpoint, op, callback);
    }

    /**
     * Runs the continuation once the store is open. The store is opened on
     * the first command that needs it, for the anonymous user.
     */
    private void withStore(final Runnable continuation) {
        if (store != null) {
            continuation.run();
            return;
        }
        final MailboxFactory factory = server.getMailboxFactory();
        if (factory == null) {
            sendStatus(403, L10N.getString("nntp.err.store_not_configured"));
            finishCommand();
            return;
        }
        final String user = authUser != null ? authUser : server.getAnonymousUser();
        if (!readPaused) {
            readPaused = true;
            endpoint.pauseRead();
        }
        submitStorage(new Callable<MailboxStore>() {
            @Override
            public MailboxStore call() throws IOException {
                MailboxStore s = factory.createStore();
                try {
                    s.open(user);
                } catch (IOException e) {
                    try {
                        s.close();
                    } catch (IOException ce) {
                        // the open failure is the one to report
                    }
                    throw e;
                }
                return s;
            }
        }, new StorageExecutor.Callback<MailboxStore>() {
            @Override
            public void completed(MailboxStore s) {
                if (closed) {
                    closeStoreOffloaded(s);
                    return;
                }
                store = s;
                continuation.run();
            }

            @Override
            public void failed(Throwable error) {
                events().warn("warn.failed_open_store").attr("username", user).thrown(error).emit();
                sendStatus(403, L10N.getString("nntp.err.cannot_open_store"));
                finishCommand();
            }
        });
    }

    // ── Cleanup ──

    private void offloadClose() {
        closed = true;
        final Mailbox mb = mailbox;
        final MailboxStore st = store;
        mailbox = null;
        store = null;
        if (mb == null && st == null) {
            return;
        }
        submitStorage(new Callable<Void>() {
            @Override
            public Void call() {
                closeQuietly(mb, st);
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
            }

            @Override
            public void failed(Throwable error) {
                events().warn("warn.error_closing_mailbox_on_disconnect").thrown(error).emit();
            }
        });
    }

    private void closeMailboxOffloaded(final Mailbox mb) {
        submitStorage(new Callable<Void>() {
            @Override
            public Void call() {
                closeQuietly(mb, null);
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
            }

            @Override
            public void failed(Throwable error) {
                events().warn("warn.error_closing_mailbox").thrown(error).emit();
            }
        });
    }

    private void closeStoreOffloaded(final MailboxStore st) {
        submitStorage(new Callable<Void>() {
            @Override
            public Void call() {
                closeQuietly(null, st);
                return null;
            }
        }, new StorageExecutor.Callback<Void>() {
            @Override
            public void completed(Void result) {
            }

            @Override
            public void failed(Throwable error) {
                events().warn("warn.error_closing_store").thrown(error).emit();
            }
        });
    }

    private void closeQuietly(Mailbox mb, MailboxStore st) {
        if (mb != null) {
            try {
                mb.close(false);
            } catch (IOException e) {
                events().warn("warn.error_closing_mailbox").thrown(e).emit();
            }
        }
        if (st != null) {
            try {
                st.close();
            } catch (IOException e) {
                events().warn("warn.error_closing_store").thrown(e).emit();
            }
        }
    }

    // ── Argument parsing ──

    /** RFC 3977 section 3.6: a message-id is 3 to 250 octets, in angle brackets, without spaces. */
    static boolean isMessageId(String s) {
        int n = s.length();
        if (n < 3 || n > 250 || s.charAt(0) != '<' || s.charAt(n - 1) != '>') {
            return false;
        }
        for (int i = 1; i < n - 1; i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == '>' || c > '~') {
                return false;
            }
        }
        return true;
    }

    /** Parses an article number: 0 to 2147483647 (RFC 3977 section 6); returns -1 if it is not one. */
    static long parseNumber(String s) {
        int n = s.length();
        if (n == 0 || n > 10) {
            return -1;
        }
        long v = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v > 2147483647L ? -1 : v;
    }

    /** Parses {@code n}, {@code n-} or {@code n-m} (RFC 3977 section 8.3.1); returns null if it is not one. */
    static ArticleRef parseRange(String s) {
        int dash = s.indexOf('-');
        if (dash < 0) {
            long n = parseNumber(s);
            return n < 0 ? null : ArticleRef.range(n, n);
        }
        long first = parseNumber(s.substring(0, dash));
        if (first < 0) {
            return null;
        }
        String rest = s.substring(dash + 1);
        if (rest.isEmpty()) {
            return ArticleRef.range(first, Long.MAX_VALUE);
        }
        long last = parseNumber(rest);
        return last < 0 ? null : ArticleRef.range(first, last);
    }

    /**
     * Parses the date and time of NEWGROUPS (RFC 3977 section 7.3.2): date as
     * yyyymmdd or yymmdd, time as hhmmss, both UTC. A two digit year is in
     * the current century unless that would be in the future.
     */
    static Instant parseDateTime(String date, String time) {
        if (time.length() != 6 || (date.length() != 8 && date.length() != 6)) {
            return null;
        }
        for (int i = 0; i < date.length(); i++) {
            if (date.charAt(i) < '0' || date.charAt(i) > '9') {
                return null;
            }
        }
        for (int i = 0; i < time.length(); i++) {
            if (time.charAt(i) < '0' || time.charAt(i) > '9') {
                return null;
            }
        }
        try {
            int year;
            int offset;
            if (date.length() == 8) {
                year = Integer.parseInt(date.substring(0, 4));
                offset = 4;
            } else {
                int yy = Integer.parseInt(date.substring(0, 2));
                int thisYear = LocalDateTime.now(ZoneOffset.UTC).getYear();
                int century = thisYear - thisYear % 100;
                year = century + yy;
                if (year > thisYear) {
                    year -= 100;
                }
                offset = 2;
            }
            int month = Integer.parseInt(date.substring(offset, offset + 2));
            int day = Integer.parseInt(date.substring(offset + 2, offset + 4));
            int hour = Integer.parseInt(time.substring(0, 2));
            int minute = Integer.parseInt(time.substring(2, 4));
            int second = Integer.parseInt(time.substring(4, 6));
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

}
