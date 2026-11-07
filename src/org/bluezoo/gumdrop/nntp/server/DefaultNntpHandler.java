/*
 * DefaultNntpHandler.java
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

package org.bluezoo.gumdrop.nntp.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.mailbox.AsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.AsyncMessageWriter;
import org.bluezoo.gumdrop.mailbox.BufferedAsyncMessageContent;
import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxStore;
import org.bluezoo.gumdrop.mailbox.MessageDescriptor;
import org.bluezoo.gumdrop.nntp.Wildmat;
import org.bluezoo.gumdrop.telemetry.EventLogger;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;

/**
 * The stock handler, which serves the mailboxes of a
 * {@link MailboxStore} as newsgroups.
 *
 * <ul>
 * <li>A newsgroup is a mailbox. The store's hierarchy delimiter is shown as
 * {@code .} in group names (so the mailbox {@code comp/lang/java} of a store
 * with delimiter {@code /} is the group {@code comp.lang.java}).
 * <li>An article is a message, and its article number is its message
 * number. Messages marked deleted do not exist as articles.
 * <li>An article's message-id is its {@code Message-ID} header. Looking an
 * article up by message-id searches the current group only, since a
 * mailbox store has no index of message-ids.
 * <li>Mailboxes carry no creation time, so NEWGROUPS lists nothing.
 * <li>A posted article is written to each newsgroup it names as it arrives,
 * in constant memory, through the mailbox's asynchronous writer; a store
 * that has none (mbox) refuses the post.
 * <li>Whatever the backend, LIST ACTIVE opens each mailbox to count its
 * messages.
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultNntpHandler implements ClientConnected, ReaderHandler {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.nntp.L10N");

    private Endpoint endpoint;
    private final String greeting;
    private final boolean postingAllowed;

    private EventLogger events() {
        return (endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig())
                .getLogger(DefaultNntpHandler.class, L10N);
    }

    /**
     * Creates a handler.
     *
     * @param greeting the greeting text
     * @param postingAllowed whether to tell readers they may post
     */
    public DefaultNntpHandler(String greeting, boolean postingAllowed) {
        this.greeting = greeting;
        this.postingAllowed = postingAllowed;
    }

    // ── ClientConnected ──

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        this.endpoint = endpoint;
        state.acceptConnection(greeting, postingAllowed, this);
    }

    @Override
    public void disconnected() {
        // nothing to release
    }

    // ── Posting (RFC 3977 section 6.3.1) ──

    @Override
    public void post(PostState state, MailboxStore store) {
        if (!postingAllowed) {
            state.refuse(this);
            return;
        }
        state.accept(new PostWriter(state, store));
    }

    /** A reason a post cannot go ahead, for the client. */
    private static final class Refusal extends IOException {
        private static final long serialVersionUID = 1L;

        Refusal(String message) {
            super(message);
        }
    }

    /** A newsgroup being posted to. */
    private static final class Target {
        final Mailbox mailbox;
        final AsyncMessageWriter writer;

        Target(Mailbox mailbox, AsyncMessageWriter writer) {
            this.mailbox = mailbox;
            this.writer = writer;
        }
    }

    /**
     * Stores an article as it arrives, in constant memory.
     *
     * <p>Only the header block is held (up to 64 KiB) until it is complete,
     * which says where the article goes. The newsgroups' mailboxes are then
     * opened off the event loop, each giving an {@link AsyncMessageWriter},
     * and what arrived since is written to all of them, chunk by chunk, one
     * chunk at a time. The connection is told to stop reading when more than
     * 256 KiB waits to be written and to go on when it falls below 64 KiB, so
     * the memory in use is the header block, that bound and one network read,
     * whatever the size of the article. A store that cannot write a message
     * as it arrives (no asynchronous append) refuses the post rather than
     * buffering it.
     *
     * <p>Everything here runs on the event loop; the writers' completions
     * are brought back to it.
     */
    private final class PostWriter implements PostSink {

        private static final int MAX_HEADER_BYTES = 65536;
        private static final int HIGH_WATER = 256 * 1024;
        private static final int LOW_WATER = 64 * 1024;
        private static final int CHUNK = 16 * 1024;

        private final PostState state;
        private final MailboxStore store;

        private final ByteArrayOutputStream header = new ByteArrayOutputStream();
        private boolean afterLineEnd;

        // what has arrived and is not yet written: whole chunks, then a partly filled one
        private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<ByteBuffer>();
        private byte[] current = new byte[CHUNK];
        private int currentLength;
        private long queuedBytes;
        private boolean inputPaused;

        private List<Target> targets = new ArrayList<Target>();
        private boolean opening;
        private boolean streaming;
        private boolean writing;
        private int outstanding;
        private boolean pumping;
        private boolean again;

        private PostState ended;
        private boolean finishing;
        private int unfinished;
        private String failure;
        private boolean replied;

        PostWriter(PostState state, MailboxStore store) {
            this.state = state;
            this.store = store;
        }

        // ── input, on the event loop ──

        @Override
        public void content(ByteBuffer chunk) {
            if (failure != null || replied) {
                return; // refused already: the rest of the article is read and dropped
            }
            if (!opening && !streaming) {
                chunk = headerBytes(chunk);
                if (chunk == null) {
                    return;
                }
            }
            if (chunk.hasRemaining()) {
                // the connection hands over a slice per line; gather them into chunks
                while (chunk.hasRemaining()) {
                    int n = Math.min(chunk.remaining(), CHUNK - currentLength);
                    chunk.get(current, currentLength, n);
                    currentLength += n;
                    queuedBytes += n;
                    if (currentLength == CHUNK) {
                        seal();
                    }
                }
                if (queuedBytes >= HIGH_WATER && !inputPaused) {
                    inputPaused = true;
                    state.pauseInput();
                }
                if (streaming) {
                    pump();
                }
            }
        }

        /** The chunk being gathered is complete (or is all there is to write now). */
        private void seal() {
            queue.add(ByteBuffer.wrap(current, 0, currentLength));
            current = new byte[CHUNK];
            currentLength = 0;
        }

        /**
         * Takes header bytes until the blank line. Returns what follows it in
         * this chunk, or null if the header block goes on or the post failed.
         */
        private ByteBuffer headerBytes(ByteBuffer chunk) {
            while (chunk.hasRemaining()) {
                byte b = chunk.get();
                header.write(b);
                if (b == '\n') {
                    if (afterLineEnd) {
                        beginPost();
                        return failure == null ? chunk : null;
                    }
                    afterLineEnd = true;
                } else if (b != '\r') {
                    afterLineEnd = false;
                }
                if (header.size() > MAX_HEADER_BYTES) {
                    fail(L10N.getString("nntp.err.headers_too_large"));
                    return null;
                }
            }
            return null;
        }

        /** The header block is complete: find the newsgroups and open them. */
        private void beginPost() {
            Map<String, String> headers = parseHeaders(header.toByteArray());
            String newsgroups = headers.get("newsgroups");
            if (newsgroups == null || newsgroups.isEmpty() || !headers.containsKey("from")
                    || !headers.containsKey("subject")) {
                fail(L10N.getString("nntp.err.article_incomplete"));
                return;
            }
            final java.util.Set<String> groups = new java.util.LinkedHashSet<String>();
            for (String group : newsgroups.split(",")) {
                group = group.trim();
                if (!group.isEmpty() && group.indexOf('*') < 0 && group.indexOf('%') < 0) {
                    groups.add(group);
                }
            }
            opening = true;
            // what follows the header waits, a read at most, while the mailboxes open
            inputPaused = true;
            state.pauseInput();
            state.offload(new Callable<List<Target>>() {
                @Override
                public List<Target> call() throws IOException {
                    return open(groups);
                }
            }, new StorageExecutor.Callback<List<Target>>() {
                @Override
                public void completed(List<Target> opened) {
                    opened(opened);
                }

                @Override
                public void failed(Throwable error) {
                    if (!(error instanceof Refusal)) {
                        events().warn("warn.failed_post_article").thrown(error).emit();
                    }
                    fail(error instanceof Refusal ? error.getMessage()
                            : L10N.getString("nntp.err.post_failed"));
                }
            });
        }

        /** Opens a writer on each newsgroup that exists. Runs off the event loop. */
        private List<Target> open(java.util.Set<String> groups) throws IOException {
            List<Target> opened = new ArrayList<Target>();
            try {
                for (String group : groups) {
                    String name = toMailboxName(store, group);
                    if (!store.listMailboxes("", name).contains(name)) {
                        continue;
                    }
                    Mailbox mailbox = store.openMailbox(name, false);
                    AsyncMessageWriter writer = null;
                    try {
                        writer = mailbox.openAsyncAppend(null, null);
                    } finally {
                        if (writer == null) {
                            mailbox.close(false);
                        }
                    }
                    if (writer == null) {
                        throw new Refusal(L10N.getString("nntp.err.store_cannot_stream"));
                    }
                    opened.add(new Target(mailbox, writer));
                }
                if (opened.isEmpty()) {
                    throw new Refusal(L10N.getString("nntp.err.no_such_newsgroup"));
                }
            } catch (IOException e) {
                // already off the event loop: undo what was opened
                for (int i = 0; i < opened.size(); i++) {
                    opened.get(i).writer.abort();
                }
                close(opened);
                throw e;
            }
            return opened;
        }

        /** The writers are open. */
        private void opened(List<Target> opened) {
            if (failure != null || replied) {
                // the connection went, or the post failed, while they were opening
                for (int i = 0; i < opened.size(); i++) {
                    opened.get(i).writer.abort();
                }
                discard(opened);
                return;
            }
            targets = opened;
            opening = false;
            streaming = true;
            byte[] head = header.toByteArray();
            queue.addFirst(ByteBuffer.wrap(head));
            queuedBytes += head.length;
            pump();
            relieve();
            finishIfReady();
        }

        // ── writing ──

        private void pump() {
            if (pumping) {
                again = true;
                return;
            }
            pumping = true;
            do {
                again = false;
                writeNext();
            } while (again);
            pumping = false;
        }

        private void writeNext() {
            if (writing || failure != null) {
                return;
            }
            if (queue.isEmpty() && currentLength > 0) {
                seal(); // nothing is being written: do not hold what has arrived
            }
            if (queue.isEmpty()) {
                return;
            }
            final ByteBuffer chunk = queue.poll();
            final int size = chunk.remaining();
            writing = true;
            outstanding = targets.size();
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).writer.write(chunk.duplicate(), new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment) {
                        endpoint.execute(new Runnable() {
                            @Override
                            public void run() {
                                written(size);
                            }
                        });
                    }

                    @Override
                    public void failed(final Throwable error, ByteBuffer attachment) {
                        endpoint.execute(new Runnable() {
                            @Override
                            public void run() {
                                events().warn("warn.failed_post_article").thrown(error).emit();
                                fail(L10N.getString("nntp.err.post_failed"));
                            }
                        });
                    }
                });
            }
        }

        private void written(int size) {
            if (failure != null || replied) {
                return;
            }
            if (--outstanding > 0) {
                return;
            }
            writing = false;
            queuedBytes -= size;
            relieve();
            pump();
            finishIfReady();
        }

        /** Lets the connection go on once the writers have caught up. */
        private void relieve() {
            if (inputPaused && !opening && queuedBytes < LOW_WATER) {
                inputPaused = false;
                state.resumeInput();
            }
        }

        // ── the end ──

        @Override
        public void end(final PostState done) {
            endpoint.execute(new Runnable() {
                @Override
                public void run() {
                    ended = done;
                    if (failure != null) {
                        reply();
                    } else if (!opening && !streaming) {
                        beginPost(); // headers with no body: there was no blank line
                    } else {
                        finishIfReady();
                    }
                }
            });
        }

        private void finishIfReady() {
            if (ended == null || !streaming || finishing || failure != null) {
                return;
            }
            if (writing || !queue.isEmpty() || currentLength > 0) {
                pump(); // all that has arrived must be written first
                return;
            }
            finishing = true;
            unfinished = targets.size();
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).writer.finish(new CompletionHandler<Long, Void>() {
                    @Override
                    public void completed(Long uid, Void attachment) {
                        endpoint.execute(new Runnable() {
                            @Override
                            public void run() {
                                if (--unfinished == 0 && failure == null) {
                                    reply();
                                }
                            }
                        });
                    }

                    @Override
                    public void failed(final Throwable error, Void attachment) {
                        endpoint.execute(new Runnable() {
                            @Override
                            public void run() {
                                events().warn("warn.failed_post_article").thrown(error).emit();
                                fail(L10N.getString("nntp.err.post_failed"));
                            }
                        });
                    }
                });
            }
        }

        /** The connection ended part way through the article. */
        @Override
        public void abort() {
            if (!replied) {
                fail("connection lost");
            }
        }

        /** The post cannot succeed; what is left of the article is dropped. */
        private void fail(String reason) {
            if (failure == null) {
                failure = reason;
            }
            queue.clear();
            currentLength = 0;
            queuedBytes = 0;
            List<Target> open = targets;
            targets = new ArrayList<Target>();
            for (int i = 0; i < open.size(); i++) {
                open.get(i).writer.abort();
            }
            discard(open);
            if (inputPaused) {
                inputPaused = false; // the rest of the article is read, and dropped
                state.resumeInput();
            }
            if (ended != null) {
                reply();
            }
        }

        /** Answers the client, closing the mailboxes first. */
        private void reply() {
            if (replied) {
                return;
            }
            replied = true;
            final List<Target> done = targets;
            targets = new ArrayList<Target>();
            final PostState answer = ended;
            final String why = failure;
            state.offload(new Callable<Void>() {
                @Override
                public Void call() {
                    close(done);
                    return null;
                }
            }, new StorageExecutor.Callback<Void>() {
                @Override
                public void completed(Void result) {
                    answer(answer, why);
                }

                @Override
                public void failed(Throwable error) {
                    events().warn("warn.failed_post_article").thrown(error).emit();
                    answer(answer, why);
                }
            });
        }

        private void answer(PostState answer, String why) {
            if (answer == null) {
                return;
            }
            if (why == null) {
                answer.posted(DefaultNntpHandler.this);
            } else {
                answer.notPosted(why, DefaultNntpHandler.this);
            }
        }

        /** Closes mailboxes whose writers have been aborted or have finished. */
        private void discard(final List<Target> open) {
            if (open.isEmpty()) {
                return;
            }
            state.offload(new Callable<Void>() {
                @Override
                public Void call() {
                    close(open);
                    return null;
                }
            }, new StorageExecutor.Callback<Void>() {
                @Override
                public void completed(Void result) {
                }

                @Override
                public void failed(Throwable error) {
                    events().warn("warn.failed_post_article").thrown(error).emit();
                }
            });
        }

        private void close(List<Target> open) {
            for (int i = 0; i < open.size(); i++) {
                try {
                    open.get(i).mailbox.close(false);
                } catch (IOException e) {
                    events().warn("warn.failed_post_article").thrown(e).emit();
                }
            }
        }
    }

    // ── Group names ──

    private static String toMailboxName(MailboxStore store, String group) {
        char delimiter = store.getHierarchyDelimiter();
        return delimiter == '.' ? group : group.replace('.', delimiter);
    }

    private static String toGroupName(MailboxStore store, String mailboxName) {
        char delimiter = store.getHierarchyDelimiter();
        return delimiter == '.' ? mailboxName : mailboxName.replace(delimiter, '.');
    }

    // ── ReaderHandler ──

    @Override
    public void selectGroup(GroupState state, MailboxStore store, String group) {
        try {
            if (group.indexOf('*') >= 0 || group.indexOf('%') >= 0) {
                state.noSuchGroup(this);
                return;
            }
            String name = toMailboxName(store, group);
            List<String> found = store.listMailboxes("", name);
            if (!found.contains(name)) {
                state.noSuchGroup(this);
                return;
            }
            Mailbox mailbox = store.openMailbox(name, true);
            int count = mailbox.getMessageCount();
            state.selected(mailbox, group, count, count > 0 ? 1 : 0, count, this);
        } catch (IOException e) {
            events().warn("warn.failed_select_group").attr("group", group).thrown(e).emit();
            state.error(L10N.getString("nntp.err.cannot_select_group"), this);
        }
    }

    @Override
    public void listGroups(ListState state, MailboxStore store, ListType type,
                           String wildmat, Instant since) {
        try {
            // read the store before answering, so that a failure is a plain 403
            List<String> names = type != ListType.NEW_GROUPS
                    ? store.listMailboxes("", "*") : java.util.Collections.<String>emptyList();
            ListState.ListWriter writer = state.begin();
            if (type != ListType.NEW_GROUPS) {
                for (int i = 0; i < names.size(); i++) {
                    String mailboxName = names.get(i);
                    String group = toGroupName(store, mailboxName);
                    if (!Wildmat.matches(wildmat, group)) {
                        continue;
                    }
                    if (type == ListType.NEWSGROUPS) {
                        writer.description(group, "");
                    } else {
                        writeActive(writer, store, mailboxName, group);
                    }
                }
            }
            writer.end(this);
        } catch (IOException e) {
            events().warn("warn.failed_list_groups").thrown(e).emit();
            state.error(L10N.getString("nntp.err.cannot_list_groups"), this);
        }
    }

    private void writeActive(ListState.ListWriter writer, MailboxStore store,
                             String mailboxName, String group) {
        long count = 0;
        try {
            Mailbox mailbox = store.openMailbox(mailboxName, true);
            try {
                count = mailbox.getMessageCount();
            } finally {
                mailbox.close(false);
            }
        } catch (IOException e) {
            events().warn("warn.failed_count_group").attr("group", group).thrown(e).emit();
        }
        writer.group(group, count, count > 0 ? 1 : 0, postingAllowed ? 'y' : 'n');
    }

    @Override
    public void listArticles(ListState state, Mailbox mailbox, ArticleRef range) {
        try {
            int count = mailbox.getMessageCount();
            ListState.ListWriter writer = state.begin();
            long last = Math.min(range.getLast(), count);
            for (long n = Math.max(1, range.getNumber()); n <= last; n++) {
                if (!mailbox.isDeleted((int) n)) {
                    writer.article(n);
                }
            }
            writer.end(this);
        } catch (IOException e) {
            events().warn("warn.failed_list_articles").thrown(e).emit();
            state.error(L10N.getString("nntp.err.cannot_list_articles"), this);
        }
    }

    @Override
    public void retrieve(ArticleState state, Mailbox mailbox, ArticleRef ref, ArticlePart part) {
        try {
            long number = resolve(mailbox, ref);
            if (number <= 0) {
                state.notFound(this);
                return;
            }
            String messageId = messageId(mailbox, (int) number);
            AsyncMessageContent content = part == ArticlePart.STAT
                    ? null : openContent(mailbox, (int) number);
            state.found(number, messageId, content, this);
        } catch (IOException e) {
            events().warn("warn.failed_retrieve_article").attr("ref", String.valueOf(ref))
                    .thrown(e).emit();
            state.error(L10N.getString("nntp.err.cannot_retrieve"), this);
        }
    }

    /** The number of the article a reference means, or 0 if there is none. */
    private long resolve(Mailbox mailbox, ArticleRef ref) throws IOException {
        if (mailbox == null) {
            return 0;
        }
        int count = mailbox.getMessageCount();
        switch (ref.getKind()) {
            case NUMBER:
            case CURRENT:
                return exists(mailbox, count, ref.getNumber()) ? ref.getNumber() : 0;
            case NEXT:
                for (long n = ref.getNumber() + 1; n <= count; n++) {
                    if (exists(mailbox, count, n)) {
                        return n;
                    }
                }
                return 0;
            case PREVIOUS:
                for (long n = Math.min(ref.getNumber() - 1, count); n >= 1; n--) {
                    if (exists(mailbox, count, n)) {
                        return n;
                    }
                }
                return 0;
            case MESSAGE_ID:
                for (int n = 1; n <= count; n++) {
                    if (exists(mailbox, count, n)
                            && ref.getMessageId().equals(messageId(mailbox, n))) {
                        return n;
                    }
                }
                return 0;
            default:
                return 0;
        }
    }

    private static boolean exists(Mailbox mailbox, int count, long n) throws IOException {
        return n >= 1 && n <= count && !mailbox.isDeleted((int) n);
    }

    private static String messageId(Mailbox mailbox, int number) throws IOException {
        return messageId(mailbox, number, headers(mailbox, number));
    }

    private static String messageId(Mailbox mailbox, int number, Map<String, String> headers)
            throws IOException {
        String id = headers.get("message-id");
        if (id != null && id.length() >= 3 && id.charAt(0) == '<'
                && id.charAt(id.length() - 1) == '>') {
            return id;
        }
        // an article must have a message-id
        return "<" + mailbox.getUniqueId(number) + "@gumdrop.invalid>";
    }

    /** The most header text read from one article; the rest is ignored. */
    private static final int MAX_HEADER_BYTES = 65536;

    /**
     * The headers of an article as written, unfolded, keyed by lower case
     * name. They are read from the start of the message only (the message
     * context's own accessors give parsed values, such as addresses
     * reformatted and dates in another format, and parse the whole
     * message, which is not what a news reader is shown or worth doing
     * for every article of a listing).
     */
    private static Map<String, String> headers(Mailbox mailbox, int number) throws IOException {
        return parseHeaders(readTop(mailbox, number));
    }

    /** Parses the header section of an article; names are lower case. */
    static Map<String, String> parseHeaders(byte[] bytes) {
        Map<String, String> result = new HashMap<String, String>();
        String name = null;
        StringBuilder value = new StringBuilder();
        int n = bytes.length;
        int i = 0;
        while (i < n) {
            int end = i;
            while (end < n && bytes[end] != '\n') {
                end++;
            }
            int lineEnd = end;
            if (lineEnd > i && bytes[lineEnd - 1] == '\r') {
                lineEnd--;
            }
            if (lineEnd == i) {
                break; // the blank line that ends the headers
            }
            String line = new String(bytes, i, lineEnd - i, StandardCharsets.UTF_8);
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (name != null) {
                    value.append(' ').append(line.trim());
                }
            } else {
                if (name != null && !result.containsKey(name)) {
                    result.put(name, value.toString().trim());
                }
                name = null;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    name = line.substring(0, colon).trim().toLowerCase(Locale.ENGLISH);
                    value.setLength(0);
                    value.append(line.substring(colon + 1).trim());
                }
            }
            i = end + 1;
        }
        if (name != null && !result.containsKey(name)) {
            result.put(name, value.toString().trim());
        }
        return result;
    }

    private static byte[] readTop(Mailbox mailbox, int number) throws IOException {
        ReadableByteChannel channel = mailbox.getMessageTop(number, 0);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(4096);
            while (out.size() < MAX_HEADER_BYTES && channel.read(buf) >= 0) {
                buf.flip();
                out.write(buf.array(), 0, buf.limit());
                buf.clear();
            }
            return out.toByteArray();
        } finally {
            channel.close();
        }
    }

    @Override
    public void overview(OverState state, Mailbox mailbox, ArticleRef ref) {
        try {
            long[] span = span(mailbox, ref);
            if (span == null) {
                state.notFound(this);
                return;
            }
            OverState.OverviewWriter writer = state.beginOverview();
            boolean byId = ref.getKind() == ArticleRef.Kind.MESSAGE_ID;
            int count = mailbox.getMessageCount();
            for (long n = span[0]; n <= span[1]; n++) {
                if (!exists(mailbox, count, n)) {
                    continue;
                }
                Map<String, String> h = headers(mailbox, (int) n);
                MessageDescriptor d = mailbox.getMessage((int) n);
                writer.entry(byId ? 0 : n,
                        h.get("subject"), h.get("from"), h.get("date"),
                        messageId(mailbox, (int) n, h), h.get("references"),
                        d != null ? d.getSize() : 0, -1);
            }
            writer.end(this);
        } catch (IOException e) {
            events().warn("warn.failed_overview").thrown(e).emit();
            state.error(L10N.getString("nntp.err.cannot_list_articles"), this);
        }
    }

    @Override
    public void headers(OverState state, Mailbox mailbox, ArticleRef ref, String field) {
        try {
            long[] span = span(mailbox, ref);
            if (span == null) {
                state.notFound(this);
                return;
            }
            OverState.HeaderWriter writer = state.beginHeaders();
            boolean byId = ref.getKind() == ArticleRef.Kind.MESSAGE_ID;
            int count = mailbox.getMessageCount();
            for (long n = span[0]; n <= span[1]; n++) {
                if (!exists(mailbox, count, n)) {
                    continue;
                }
                String value;
                if (field.equalsIgnoreCase(":bytes")) {
                    MessageDescriptor d = mailbox.getMessage((int) n);
                    value = d == null ? null : Long.toString(d.getSize());
                } else if (field.equalsIgnoreCase(":lines")) {
                    value = null;
                } else if (field.equalsIgnoreCase("Message-ID")) {
                    value = messageId(mailbox, (int) n);
                } else {
                    value = headers(mailbox, (int) n).get(field.toLowerCase(Locale.ENGLISH));
                }
                writer.entry(byId ? 0 : n, value);
            }
            writer.end(this);
        } catch (IOException e) {
            events().warn("warn.failed_headers").thrown(e).emit();
            state.error(L10N.getString("nntp.err.cannot_list_articles"), this);
        }
    }

    /**
     * The first and last article numbers to examine for OVER and HDR, or
     * null if no article could be in the reference.
     */
    private long[] span(Mailbox mailbox, ArticleRef ref) throws IOException {
        if (mailbox == null) {
            return null;
        }
        int count = mailbox.getMessageCount();
        long first;
        long last;
        if (ref.getKind() == ArticleRef.Kind.MESSAGE_ID) {
            long n = resolve(mailbox, ref);
            if (n <= 0) {
                return null;
            }
            first = n;
            last = n;
        } else {
            first = Math.max(1, ref.getNumber());
            last = Math.min(ref.getLast(), count);
        }
        for (long n = first; n <= last; n++) {
            if (exists(mailbox, count, n)) {
                return new long[] { n, last };
            }
        }
        return null;
    }

    // ── Content ──

    private static AsyncMessageContent openContent(Mailbox mailbox, int number)
            throws IOException {
        AsyncMessageContent async = null;
        try {
            async = mailbox.openAsyncContent(number);
        } catch (IOException e) {
            // fall through to loading the message
        }
        if (async != null) {
            return async;
        }
        // mbox and other backends without asynchronous reads: load the
        // message here, off the event loop
        ReadableByteChannel channel = mailbox.getMessageContent(number);
        try {
            return new BufferedAsyncMessageContent(drain(channel));
        } finally {
            channel.close();
        }
    }

    private static byte[] drain(ReadableByteChannel channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        while (channel.read(buf) >= 0) {
            buf.flip();
            out.write(buf.array(), 0, buf.limit());
            buf.clear();
        }
        return out.toByteArray();
    }

}
