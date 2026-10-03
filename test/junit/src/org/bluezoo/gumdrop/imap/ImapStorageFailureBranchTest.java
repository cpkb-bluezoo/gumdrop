/*
 * ImapStorageFailureBranchTest.java
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

import static org.junit.Assert.*;

/**
 * Drives every storage-backed IMAP command with the mock mailbox store
 * failing the underlying operation, checking each command reports a tagged
 * NO and the session stays usable afterwards.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapStorageFailureBranchTest extends ImapSessionHarness {

    /** Runs the command with the operation failing; expects a tagged NO. */
    private void failsWhen(String operation, String command) throws Exception {
        mock.failing.add(operation);
        String line = cmd(command);
        mock.failing.clear();
        assertTrue(operation + " / " + command + " -> " + line,
                line.contains(" NO"));
    }

    @Test(timeout = 30000)
    public void mailboxManagementFailures() throws Exception {
        login();
        failsWhen("listMailboxes", "LIST \"\" \"*\"");
        failsWhen("listSubscribed", "LSUB \"\" \"*\"");
        failsWhen("subscribe", "SUBSCRIBE INBOX");
        failsWhen("unsubscribe", "UNSUBSCRIBE INBOX");
        failsWhen("createMailbox", "CREATE Fresh");
        failsWhen("deleteMailbox", "DELETE Fresh");
        failsWhen("renameMailbox", "RENAME Fresh Other");
        failsWhen("openMailbox", "STATUS INBOX (MESSAGES)");
        failsWhen("openMailbox", "SELECT INBOX");
        failsWhen("openMailbox", "EXAMINE INBOX");
        ok("NOOP");
        ok("SELECT INBOX");
    }

    @Test(timeout = 30000)
    public void selectedMailboxFailures() throws Exception {
        selectedInbox();
        failsWhen("search", "SEARCH ALL");
        failsWhen("search", "UID SEARCH ALL");
        failsWhen("getFlags", "FETCH 1:3 (FLAGS)");
        failsWhen("getMessageContent", "FETCH 1 (BODY.PEEK[HEADER.FIELDS (Subject)])");
        failsWhen("getMessageContent", "FETCH 1:2 (RFC822.HEADER)");
        failsWhen("setFlags", "STORE 1 +FLAGS (\\Seen)");
        failsWhen("replaceFlags", "STORE 1 FLAGS (\\Seen)");
        failsWhen("copyMessages", "COPY 1 INBOX");
        failsWhen("moveMessages", "MOVE 1 INBOX");
        ok("STORE 1 +FLAGS (\\Deleted)");
        failsWhen("expungeMessages", "UID EXPUNGE 1");
        failsWhen("search", "SORT (SUBJECT) UTF-8 ALL");
        failsWhen("search", "THREAD REFERENCES UTF-8 ALL");
        ok("NOOP");
    }

    @Test(timeout = 30000)
    public void unsupportedSearchIsReportedAsSuch() throws Exception {
        selectedInbox();
        mock.unsupported.add("search");
        String[] commands = new String[] {
            "SEARCH ALL", "UID SEARCH ALL", "SORT (SUBJECT) UTF-8 ALL",
            "UID SORT (SUBJECT) UTF-8 ALL", "THREAD REFERENCES UTF-8 ALL"
        };
        for (int i = 0; i < commands.length; i++) {
            String line = cmd(commands[i]);
            assertContains(line, " NO");
            String lower = line.toLowerCase();
            assertContains(lower, "not supported");
        }
        mock.unsupported.clear();
        ok("SEARCH ALL");
    }

    @Test(timeout = 30000)
    public void expungeAndCloseFailures() throws Exception {
        selectedInbox();
        ok("STORE 1 +FLAGS (\\Deleted)");
        failsWhen("expunge", "EXPUNGE");
        mock.failMailboxClose = true;
        String line = cmd("CLOSE");
        mock.failMailboxClose = false;
        assertContains(line, " NO");
        ok("SELECT INBOX");
        mock.failMailboxClose = true;
        line = cmd("UNSELECT");
        mock.failMailboxClose = false;
        assertContains(line, " NO");
    }

    @Test(timeout = 30000)
    public void noopFailureIsReportedAsInternalError() throws Exception {
        selectedInbox();
        mock.failing.add("getMessageCount");
        String line = cmd("NOOP");
        mock.failing.clear();
        assertContains(line, " NO");
    }

    @Test(timeout = 30000)
    public void disconnectClosesMailboxAndStoreEvenWhenTheyFail()
            throws Exception {
        selectedInbox();
        mock.failMailboxClose = true;
        mock.failStoreClose = true;
        int boxes = mock.mailboxCloses;
        int stores = mock.storeCloses;
        handler.disconnected();
        assertEquals(boxes + 1, mock.mailboxCloses);
        assertEquals(stores + 1, mock.storeCloses);
    }

    @Test(timeout = 30000)
    public void storeOpenFailureRefusesLogin() throws Exception {
        mock.failStoreOpen = true;
        String line = cmd("LOGIN editor editor");
        mock.failStoreOpen = false;
        assertContains(line, " NO");
        ok("LOGIN editor editor");
    }
}
