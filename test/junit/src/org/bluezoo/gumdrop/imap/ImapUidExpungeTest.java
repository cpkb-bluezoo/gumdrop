/*
 * ImapUidExpungeTest.java
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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * RFC 4315 / RFC 9051 section 6.4.9: {@code UID EXPUNGE} removes only the
 * {@code \Deleted} messages whose UID is in the given set, unlike plain
 * {@code EXPUNGE} which removes every {@code \Deleted} message.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapUidExpungeTest extends ImapSessionHarness {

    private List<String> uids() throws Exception {
        List<String> uids = new ArrayList<String>();
        int count = mock.lastBox.delegate.getMessageCount();
        for (int i = 1; i <= count; i++) {
            uids.add(mock.lastBox.delegate.getUniqueId(i));
        }
        return uids;
    }

    /**
     * Replays the untagged EXPUNGE responses of the last command against a
     * client-side model of the message list (RFC 9051 section 7.5.1: each
     * response renumbers the messages after it) and returns the result.
     */
    private List<String> replayExpunges(List<String> model) {
        List<String> lines = endpoint.getResponses();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("* ") && line.endsWith(" EXPUNGE")) {
                int n = Integer.parseInt(line.substring(2,
                        line.length() - " EXPUNGE".length()));
                assertTrue("EXPUNGE " + n + " beyond " + model.size()
                        + " messages", n >= 1 && n <= model.size());
                model.remove(n - 1);
            }
        }
        return model;
    }

    @Test(timeout = 30000)
    public void expungeResponsesRemoveTheRightMessagesForAdjacentRuns()
            throws Exception {
        selectedInbox();
        List<String> uids = uids();
        ok("STORE 1:2 +FLAGS (\\Deleted)");
        ok("EXPUNGE");
        List<String> left = replayExpunges(new ArrayList<String>(uids));
        assertEquals(uids.subList(2, 3), left);
    }

    @Test(timeout = 30000)
    public void expungeResponsesRemoveTheRightMessagesForSplitRuns()
            throws Exception {
        selectedInbox();
        List<String> uids = uids();
        ok("STORE 1 +FLAGS (\\Deleted)");
        ok("STORE 3 +FLAGS (\\Deleted)");
        ok("EXPUNGE");
        List<String> left = replayExpunges(new ArrayList<String>(uids));
        assertEquals(uids.subList(1, 2), left);
    }

    @Test(timeout = 30000)
    public void qresyncExpungeReportsTheVanishedUids() throws Exception {
        selectedInbox();
        List<String> uids = uids();
        ok("ENABLE QRESYNC");
        ok("STORE 1:2 +FLAGS (\\Deleted)");
        ok("EXPUNGE");
        assertSaw("* VANISHED " + uids.get(0) + "," + uids.get(1));
        assertNotSaw("EXPUNGE");
    }

    @Test(timeout = 30000)
    public void removesOnlyListedDeletedMessages() throws Exception {
        selectedInbox();
        List<String> uids = uids();
        ok("STORE 1:2 +FLAGS (\\Deleted)");
        ok("UID EXPUNGE " + uids.get(0));
        List<String> left = replayExpunges(new ArrayList<String>(uids));
        assertEquals(uids.subList(1, 3), left);
        ok("FETCH 1:* (UID FLAGS)");
        assertSaw("* 1 FETCH (UID " + uids.get(1));
        assertSaw("\\Deleted");
        assertSaw("* 2 FETCH (UID " + uids.get(2));
        assertEquals(2, mock.lastBox.delegate.getMessageCount());
    }

    @Test(timeout = 30000)
    public void leavesListedMessagesThatAreNotDeleted() throws Exception {
        selectedInbox();
        List<String> uids = uids();
        ok("STORE 1 +FLAGS (\\Deleted)");
        ok("UID EXPUNGE " + uids.get(1) + ":" + uids.get(2));
        assertNotSaw("EXPUNGE");
        assertEquals(3, mock.lastBox.delegate.getMessageCount());
    }

    @Test(timeout = 30000)
    public void reportsVanishedUidsWhenQresyncIsEnabled() throws Exception {
        selectedInbox();
        List<String> uids = uids();
        ok("ENABLE QRESYNC");
        ok("STORE 1:2 +FLAGS (\\Deleted)");
        ok("UID EXPUNGE " + uids.get(1));
        assertSaw("* VANISHED " + uids.get(1));
        assertNotSaw(uids.get(0) + ",");
        assertNotSaw("," + uids.get(0));
        assertEquals(2, mock.lastBox.delegate.getMessageCount());
    }

    @Test(timeout = 30000)
    public void plainExpungeStillRemovesEveryDeletedMessage() throws Exception {
        selectedInbox();
        ok("STORE 1:2 +FLAGS (\\Deleted)");
        ok("EXPUNGE");
        assertEquals(1, mock.lastBox.delegate.getMessageCount());
    }

    @Test(timeout = 30000)
    public void rejectsMalformedSetsAndReadOnlySelection() throws Exception {
        selectedInbox();
        bad("UID EXPUNGE");
        bad("UID EXPUNGE abc");
        ok("EXAMINE INBOX");
        no("UID EXPUNGE 1");
    }
}
