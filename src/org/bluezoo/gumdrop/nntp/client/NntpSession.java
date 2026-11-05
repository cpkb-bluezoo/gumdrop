/*
 * NntpSession.java
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

package org.bluezoo.gumdrop.nntp.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.bluezoo.gumdrop.nntp.client.NntpCommand.Block;

/**
 * A connection that is ready for commands (RFC 3977). Commands may be
 * issued from any thread; they are sent one at a time, in the order issued,
 * and the callbacks are called on the connection's event loop.
 *
 * <p>Results that arrive as a multi-line block are not collected: a
 * {@link NntpConsumer} is called for each part as it arrives. Article text is
 * dot-unstuffed and passed on in pieces of whatever size the connection
 * delivers, with line endings kept.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class NntpSession {

    private final NntpClientProtocolHandler connection;
    private final List<String> capabilities;
    private final boolean postingAllowed;

    NntpSession(NntpClientProtocolHandler connection, List<String> capabilities,
                boolean postingAllowed) {
        this.connection = connection;
        this.capabilities = capabilities;
        this.postingAllowed = postingAllowed;
    }

    /**
     * Returns whether the greeting said posting is allowed.
     *
     * @return true if it did
     */
    public boolean isPostingAllowed() {
        return postingAllowed;
    }

    /**
     * Returns the CAPABILITIES lines, as they stood after TLS and before
     * login.
     *
     * @return the lines, upper case
     */
    public List<String> getCapabilities() {
        return capabilities;
    }

    /**
     * Returns whether commands can still be issued: the connection is open
     * and QUIT has not been sent.
     *
     * @return true if the session is usable
     */
    public boolean isAlive() {
        return connection.isAlive();
    }

    /**
     * Issues any command.
     *
     * @param line the command line, without line ending
     * @param multiline whether to read the block that follows a 1xx or 2xx
     * response that announces one
     * @param content receives that block; may be null if {@code multiline} is
     * false
     * @param done receives the status, whatever its code; it fails only if
     * the connection does
     * @throws IllegalArgumentException if the line holds a line break
     */
    public void command(String line, boolean multiline, NntpConsumer<ByteBuffer> content,
                        NntpCallback<NntpStatus> done) {
        check(line);
        connection.submit(new NntpCommand(line, multiline ? Block.CONTENT : Block.NONE,
                content, null, null, done, 0, null, false));
    }

    /**
     * LIST ACTIVE (RFC 3977 section 7.6.3).
     *
     * @param wildmat which newsgroups, or null or empty for all
     * @param entries called for each newsgroup as its line is read
     * @param done called when the list is complete
     */
    public void listActive(String wildmat, NntpConsumer<NewsgroupEntry> entries,
                           NntpCallback<Void> done) {
        String wm = wildmat == null ? "" : wildmat.trim();
        check(wm);
        connection.submit(new NntpCommand(wm.isEmpty() ? "LIST ACTIVE" : "LIST ACTIVE " + wm,
                Block.NEWSGROUPS, null, entries, null, new Expect<Void>("LIST ACTIVE", 215, done) {
                    @Override
                    Void convert(NntpStatus status) {
                        return null;
                    }
                }, 0, null, false));
    }

    /**
     * GROUP (RFC 3977 section 6.1.1).
     *
     * @param name the newsgroup
     * @param done receives the group's range
     */
    public void group(String name, NntpCallback<GroupResult> done) {
        check(name);
        connection.submit(simple("GROUP " + name, new Expect<GroupResult>("GROUP", 211, done, true) {
            @Override
            GroupResult convert(NntpStatus status) {
                return GroupResult.parse(status.getText());
            }
        }));
    }

    /**
     * STAT (RFC 3977 section 6.2.4), by article number in the selected
     * group.
     *
     * @param number the article number
     * @param done receives the article's number and message-id
     */
    public void stat(long number, NntpCallback<ArticleRef> done) {
        connection.submit(simple("STAT " + number, new Expect<ArticleRef>("STAT", 223, done, true) {
            @Override
            ArticleRef convert(NntpStatus status) {
                return ArticleRef.parse(status.getText());
            }
        }));
    }

    /**
     * STAT by message-id.
     *
     * @param messageId the message-id, with angle brackets
     * @param done receives the article's reference (number 0)
     */
    public void stat(String messageId, NntpCallback<ArticleRef> done) {
        check(messageId);
        connection.submit(simple("STAT " + messageId, new Expect<ArticleRef>("STAT", 223, done, true) {
            @Override
            ArticleRef convert(NntpStatus status) {
                return ArticleRef.parse(status.getText());
            }
        }));
    }

    /**
     * OVER (RFC 3977 section 8.3) for a range of articles in the selected
     * group.
     *
     * @param first the first article number
     * @param last the last article number
     * @param entries called for each article as its line is read
     * @param done called when the overview is complete
     */
    public void over(long first, long last, NntpConsumer<OverviewEntry> entries,
                     NntpCallback<Void> done) {
        connection.submit(new NntpCommand("OVER " + first + "-" + last, Block.OVERVIEW, null,
                null, entries, new Expect<Void>("OVER", 224, done) {
                    @Override
                    Void convert(NntpStatus status) {
                        return null;
                    }
                }, 0, null, false));
    }

    /**
     * ARTICLE (RFC 3977 section 6.2.1) by number in the selected group.
     *
     * @param number the article number
     * @param content receives the headers, the empty line and the body
     * @param done called when the whole article has been delivered
     */
    public void article(long number, NntpConsumer<ByteBuffer> content, NntpCallback<Void> done) {
        retrieve("ARTICLE " + number, 220, content, done);
    }

    /**
     * ARTICLE by message-id.
     *
     * @param messageId the message-id, with angle brackets
     * @param content receives the article
     * @param done called when the whole article has been delivered
     */
    public void article(String messageId, NntpConsumer<ByteBuffer> content,
                        NntpCallback<Void> done) {
        check(messageId);
        retrieve("ARTICLE " + messageId, 220, content, done);
    }

    /**
     * HEAD (RFC 3977 section 6.2.2) by number in the selected group.
     *
     * @param number the article number
     * @param content receives the headers
     * @param done called when they have all been delivered
     */
    public void head(long number, NntpConsumer<ByteBuffer> content, NntpCallback<Void> done) {
        retrieve("HEAD " + number, 221, content, done);
    }

    /**
     * BODY (RFC 3977 section 6.2.3) by number in the selected group.
     *
     * @param number the article number
     * @param content receives the body
     * @param done called when it has all been delivered
     */
    public void body(long number, NntpConsumer<ByteBuffer> content, NntpCallback<Void> done) {
        retrieve("BODY " + number, 222, content, done);
    }

    /**
     * POST (RFC 3977 section 6.3.1). The article is given whole, with any
     * line endings; dot-stuffing and the terminator are added.
     *
     * @param article the headers, an empty line and the body
     * @param done called with the outcome
     */
    public void post(byte[] article, NntpCallback<Void> done) {
        connection.submit(new NntpCommand("POST", Block.NONE, null, null, null,
                new Expect<Void>("POST", 240, done) {
                    @Override
                    Void convert(NntpStatus status) {
                        return null;
                    }
                }, 340, dotStuff(article), false));
    }

    /**
     * QUIT. The session takes no commands after this, and the connection is
     * closed when the server has answered.
     */
    public void quit() {
        connection.submit(new NntpCommand("QUIT", Block.NONE, null, null, null,
                new NntpCallback<NntpStatus>() {
                    @Override
                    public void completed(NntpStatus result) {
                    }

                    @Override
                    public void failed(NntpException error) {
                    }
                }, 0, null, true));
    }

    // ── helpers ──

    private void retrieve(String line, int code, NntpConsumer<ByteBuffer> content,
                          NntpCallback<Void> done) {
        connection.submit(new NntpCommand(line, Block.CONTENT, content, null, null,
                new Expect<Void>(line.substring(0, line.indexOf(' ')), code, done) {
                    @Override
                    Void convert(NntpStatus status) {
                        return null;
                    }
                }, 0, null, false));
    }

    private static NntpCommand simple(String line, NntpCallback<NntpStatus> done) {
        return new NntpCommand(line, Block.NONE, null, null, null, done, 0, null, false);
    }

    private static void check(String s) {
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("line break in command");
        }
    }

    /** Succeeds only on the expected code, giving a result built from the status. */
    private abstract static class Expect<T> implements NntpCallback<NntpStatus> {

        private final String command;
        private final int expected;
        private final NntpCallback<T> out;

        private final boolean requiresResult;

        Expect(String command, int expected, NntpCallback<T> out) {
            this(command, expected, out, false);
        }

        Expect(String command, int expected, NntpCallback<T> out, boolean requiresResult) {
            this.command = command;
            this.expected = expected;
            this.out = out;
            this.requiresResult = requiresResult;
        }

        /** Builds the result; null for a command with none, or if the status text is malformed. */
        abstract T convert(NntpStatus status);

        @Override
        public void completed(NntpStatus status) {
            if (status.getCode() != expected) {
                out.failed(new NntpException(status.getCode(), command + ": " + status));
                return;
            }
            T result = convert(status);
            if (result == null && requiresResult) {
                out.failed(new NntpException(status.getCode(), "malformed " + command + " response: " + status));
                return;
            }
            out.completed(result);
        }

        @Override
        public void failed(NntpException error) {
            out.failed(error);
        }
    }

    /**
     * Prepares an article for the wire (RFC 3977 section 3.1.1): line endings
     * become CRLF, a line that starts with a dot gets another, the last line
     * is ended, and the terminating dot line is added.
     */
    static byte[] dotStuff(byte[] article) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(article.length + 16);
        boolean lineStart = true;
        int i = 0;
        int n = article.length;
        while (i < n) {
            byte b = article[i++];
            if (b == '\r') {
                if (i < n && article[i] == '\n') {
                    i++;
                }
                b = '\n';
            }
            if (b == '\n') {
                out.write('\r');
                out.write('\n');
                lineStart = true;
                continue;
            }
            if (lineStart && b == '.') {
                out.write('.');
            }
            out.write(b);
            lineStart = false;
        }
        if (!lineStart) {
            out.write('\r');
            out.write('\n');
        }
        out.write('.');
        out.write('\r');
        out.write('\n');
        return out.toByteArray();
    }

}
