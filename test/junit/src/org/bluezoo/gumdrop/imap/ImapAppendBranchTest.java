/*
 * ImapAppendBranchTest.java
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

import org.junit.Test;

import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;

import static org.junit.Assert.*;

/**
 * Exercises APPEND argument parsing and the streamed, buffered and failing
 * append paths of {@link ImapProtocolHandler} through a mock mailbox whose
 * async writer can be removed or made to fail.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapAppendBranchTest extends ImapSessionHarness {

    private static final String MSG = "Subject: appended\r\n\r\nhello\r\n";

    private static final int LEN = MSG.length();
    private static final String HEAD = MSG.substring(0, 6);
    private static final String TAIL = MSG.substring(6);
    private static final String PART_A = MSG.substring(0, 5);
    private static final String PART_B = MSG.substring(5, 12);
    private static final String PART_C = MSG.substring(12);

    @Test(timeout = 30000)
    public void appendRequiresAuthentication() throws Exception {
        bad("APPEND INBOX {1+}");
    }

    @Test(timeout = 30000)
    public void appendArgumentErrors() throws Exception {
        login();
        bad("APPEND \"unterminated");
        bad("APPEND INBOX");
        bad("APPEND INBOX (\\Seen");
        bad("APPEND INBOX \"05-May-2025");
        bad("APPEND INBOX notaliteral");
        bad("APPEND INBOX {abc}");
        bad("APPEND INBOX (\\Seen) \"05-May-2025 10:00:00 +0000\" notaliteral");
        no("APPEND INBOX {99999999999}");
        no("APPEND INBOX {-1}");
    }

    @Test(timeout = 30000)
    public void appendQuotedMailboxFlagsAndBadDate() throws Exception {
        login();
        ok("CREATE \"Out Box\"");
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND \"Out Box\" (\\Seen \\Draft bogus) \"not a date\""
                + " {" + LEN + "+}\r\n" + MSG + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, "OK [APPENDUID ");
        ok("SELECT \"Out Box\"");
        assertSaw("1 EXISTS");
        ok("FETCH 1 FLAGS");
        assertSaw("\\Seen");
        assertSaw("\\Draft");
    }

    @Test(timeout = 30000)
    public void appendWithSynchronisingLiteralInChunks() throws Exception {
        login();
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "}\r\n");
        endpoint.awaitLineStartingWith("+");
        send(PART_A);
        send(PART_B);
        send(PART_C);
        send("\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, "OK [APPENDUID ");
        ok("SELECT INBOX");
        assertSaw("1 EXISTS");
    }

    @Test(timeout = 30000)
    public void appendIntoMissingMailboxIsRefused() throws Exception {
        login();
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND Nowhere {" + LEN + "+}\r\n" + MSG + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO [TRYCREATE]");
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void appendOpenFailureDrainsTheLiteralThenRefuses() throws Exception {
        login();
        mock.failing.add("openMailbox");
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "}\r\n");
        send(HEAD);
        send(TAIL);
        send("\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO [TRYCREATE]");
        mock.failing.clear();
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void appendBufferedWhenNoAsyncWriter() throws Exception {
        login();
        mock.appendMode = ImapMockMailboxFactory.AppendMode.NULL_WRITER;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX (\\Seen) {" + LEN + "+}\r\n"
                + HEAD);
        send(TAIL + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, "OK [APPENDUID ");
        ok("SELECT INBOX");
        assertSaw("1 EXISTS");
    }

    @Test(timeout = 30000)
    public void appendBufferedFailureIsReported() throws Exception {
        login();
        mock.appendMode = ImapMockMailboxFactory.AppendMode.NULL_WRITER;
        mock.failing.add("endAppendMessage");
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "+}\r\n" + MSG + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO ");
        assertNotContains(line, "APPENDUID");
    }

    @Test(timeout = 30000)
    public void appendThroughStreamingWriterCompletes() throws Exception {
        login();
        mock.appendMode = ImapMockMailboxFactory.AppendMode.STREAM;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "}\r\n");
        endpoint.awaitLineStartingWith("+");
        send(HEAD);
        send(TAIL);
        send("\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, "OK [APPENDUID ");
        ok("SELECT INBOX");
        assertSaw("1 EXISTS");
    }

    @Test(timeout = 30000)
    public void appendWriterWriteFailureRefusesAfterDraining() throws Exception {
        login();
        mock.appendMode = ImapMockMailboxFactory.AppendMode.WRITE_FAIL;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "}\r\n");
        send(HEAD);
        send(TAIL);
        send("\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO [TRYCREATE]");
    }

    @Test(timeout = 30000)
    public void appendWriterFinishFailureIsReported() throws Exception {
        login();
        mock.appendMode = ImapMockMailboxFactory.AppendMode.FINISH_FAIL;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "+}\r\n" + MSG + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO ");
        assertNotContains(line, "APPENDUID");
    }

    @Test(timeout = 30000)
    public void appendWriterFinishExceptionIsReported() throws Exception {
        login();
        mock.appendMode = ImapMockMailboxFactory.AppendMode.FINISH_THROW;
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "+}\r\n" + MSG + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO ");
    }

    @Test(timeout = 30000)
    public void appendWithCompleteLiteralAlongsideTheCommand() throws Exception {
        login();
        appendSimple("INBOX", null, "inline");
        ok("SELECT INBOX");
        assertSaw("1 EXISTS");
    }

    @Test(timeout = 30000)
    public void appendBeyondQuotaIsRefused() throws Exception {
        login();
        RoleBasedQuotaManager quota = new RoleBasedQuotaManager();
        quota.defaultQuota("10");
        listener.quotaManager(quota);
        tagCounter++;
        String tag = "t" + tagCounter;
        endpoint.clearResponses();
        send(tag + " APPEND INBOX {" + LEN + "+}\r\n" + MSG + "\r\n");
        String line = endpoint.awaitLineStartingWith(tag + " ");
        assertContains(line, " NO [OVERQUOTA]");
    }
}
