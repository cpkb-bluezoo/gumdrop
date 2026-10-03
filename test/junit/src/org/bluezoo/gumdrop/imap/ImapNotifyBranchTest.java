/*
 * ImapNotifyBranchTest.java
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
 * Exercises {@link ImapNotifySupport} (RFC 5465 NOTIFY) through a full
 * session: request validation, initial STATUS responses, timer driven
 * polling of watched mailboxes, CONDSTORE flag-change reports and the
 * overflow and failure branches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapNotifyBranchTest extends ImapSessionHarness {

    private void prepare() throws Exception {
        login();
        ok("CREATE Other");
        ok("CREATE Other/Child");
        ok("SUBSCRIBE Other");
        ok("ENABLE NOTIFY");
    }

    @Test(timeout = 30000)
    public void disabledNotifyIsUnknown() throws Exception {
        listener.setEnableNOTIFY(false);
        reconnect();
        login();
        bad("NOTIFY NONE");
        ok("ENABLE NOTIFY");
        assertNotSaw("ENABLED");
    }

    @Test(timeout = 30000)
    public void notifyRequiresEnableAndWellFormedArguments() throws Exception {
        login();
        no("NOTIFY NONE");
        ok("ENABLE NOTIFY");
        bad("NOTIFY");
        bad("NOTIFY BOGUS");
        bad("NOTIFY SET");
        ok("NOTIFY NONE");
    }

    @Test(timeout = 30000)
    public void validationRejectsInconsistentGroups() throws Exception {
        prepare();
        bad("NOTIFY SET (selected (MessageNew MessageExpunge))"
                + " (selected-delayed (MessageNew MessageExpunge))");
        bad("NOTIFY SET (selected (MailboxName))");
        bad("NOTIFY SET (personal (MessageNew))");
        bad("NOTIFY SET (personal (MessageExpunge))");
        bad("NOTIFY SET (personal (FlagChange))");
        bad("NOTIFY SET (personal (FlagChange MessageNew))");
        String line = no("NOTIFY SET (personal (AnnotationChange))");
        assertContains(line, "BADEVENT");
        no("NOTIFY SET (personal (MailboxMetadataChange))");
        no("NOTIFY SET (personal (ServerMetadataChange))");
        ok("NOTIFY SET (personal (NONE))");
        ok("NOTIFY SET (selected (NONE)) (personal (MailboxName))");
    }

    @Test(timeout = 30000)
    public void tooManyWatchedMailboxesOverflow() throws Exception {
        prepare();
        for (int i = 0; i < 130; i++) {
            ok("CREATE bulk" + i);
        }
        String line = no("NOTIFY SET (personal (MessageNew MessageExpunge))");
        assertContains(line, "NOTIFICATIONOVERFLOW");
        ok("NOTIFY SET (mailboxes (bulk1 bulk2) (MessageNew MessageExpunge))");
    }

    @Test(timeout = 30000)
    public void storeFailureWhileValidatingIsReported() throws Exception {
        prepare();
        mock.failing.add("listMailboxes");
        String line = cmd("NOTIFY SET (personal (MessageNew MessageExpunge))");
        mock.failing.clear();
        assertContains(line, " NO");
        ok("NOTIFY SET (personal (MessageNew MessageExpunge))");
    }

    @Test(timeout = 30000)
    public void initialStatusSkipsTheSelectedMailboxAndUnreadableOnes()
            throws Exception {
        prepare();
        appendSimple("Other", null, "seed");
        ok("SELECT INBOX");
        ok("NOTIFY SET STATUS (personal (MessageNew MessageExpunge))");
        assertSaw("* STATUS Other (MESSAGES 1 UIDNEXT 2 UIDVALIDITY");
        assertSaw("* STATUS Other/Child (MESSAGES 0");
        assertNotSaw("STATUS INBOX");
        mock.failing.add("openMailbox");
        ok("NOTIFY SET STATUS (personal (MessageNew MessageExpunge))");
        mock.failing.clear();
        assertNotSaw("* STATUS");
    }

    @Test(timeout = 30000)
    public void pollingReportsNewMessagesInWatchedMailboxes() throws Exception {
        prepare();
        ok("SELECT INBOX");
        ok("NOTIFY SET STATUS (mailboxes (Other Missing) (MessageNew MessageExpunge))");
        assertSaw("* STATUS Other (MESSAGES 0");
        endpoint.clearResponses();
        assertEquals(1, endpoint.fireTimers());
        assertNotSaw("* STATUS");
        appendSimple("Other", null, "fresh");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertSaw("* STATUS Other (MESSAGES 1 UIDNEXT 2 UIDVALIDITY");
        assertNotSaw("HIGHESTMODSEQ");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertNotSaw("* STATUS");
        ok("NOTIFY NONE");
        assertEquals(0, endpoint.fireTimers());
    }

    @Test(timeout = 30000)
    public void pollingWithoutInitialStatusBaselinesFirst() throws Exception {
        prepare();
        ok("NOTIFY SET (subtree Other (MessageNew MessageExpunge))");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertNotSaw("* STATUS");
        appendSimple("Other/Child", null, "child");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertSaw("* STATUS Other/Child (MESSAGES 1");
    }

    @Test(timeout = 30000)
    public void flagChangesAreReportedAsModseqWithCondstore() throws Exception {
        prepare();
        ok("ENABLE CONDSTORE");
        mock.highestModSeq = 5;
        ok("NOTIFY SET STATUS (mailboxes Other (MessageNew MessageExpunge FlagChange))");
        assertSaw("* STATUS Other (MESSAGES 0 UIDNEXT 1 UIDVALIDITY");
        assertSaw("HIGHESTMODSEQ 5)");
        endpoint.fireTimers();
        mock.highestModSeq = 9;
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertSaw("* STATUS Other (HIGHESTMODSEQ 9)");
        assertNotSaw("MESSAGES");
        appendSimple("Other", null, "both");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertSaw("* STATUS Other (MESSAGES 1");
        assertSaw("HIGHESTMODSEQ 9)");
    }

    @Test(timeout = 30000)
    public void flagChangesAreIgnoredWithoutTheEvent() throws Exception {
        prepare();
        ok("ENABLE CONDSTORE");
        mock.highestModSeq = 5;
        ok("NOTIFY SET (mailboxes Other (MessageNew MessageExpunge))");
        endpoint.fireTimers();
        mock.highestModSeq = 9;
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertNotSaw("* STATUS");
    }

    @Test(timeout = 30000)
    public void selectedMailboxUpdatesAreDeliveredDuringIdle() throws Exception {
        prepare();
        appendSimple("INBOX", null, "one");
        appendSimple("INBOX", null, "two");
        ok("SELECT INBOX");
        ok("NOTIFY SET (selected (MessageNew MessageExpunge FlagChange))");
        tagCounter++;
        String tag = "t" + tagCounter;
        send(tag + " IDLE\r\n");
        endpoint.awaitLineStartingWith("+");
        java.util.List<String> uids = new java.util.ArrayList<String>();
        uids.add(mock.lastBox.delegate.getUniqueId(1));
        mock.uidsOverride = uids;
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertSaw("* 2 EXPUNGE");
        assertSaw("* 1 EXISTS");
        send("DONE\r\n");
        String done = lineStarting(tag + " ");
        assertContains(done, " OK");
    }

    @Test(timeout = 30000)
    public void pollingWaitsWhileACommandLiteralIsPending() throws Exception {
        prepare();
        ok("NOTIFY SET (mailboxes Other (MessageNew MessageExpunge))");
        endpoint.fireTimers();
        appendSimple("Other", null, "x");
        endpoint.clearResponses();
        send("t90 LOGIN {6}\r\n");
        endpoint.fireTimers();
        assertNotSaw("* STATUS");
    }

    @Test(timeout = 30000)
    public void disconnectStopsPollingAndFailuresAreSwallowed() throws Exception {
        prepare();
        ok("NOTIFY SET (mailboxes Other (MessageNew MessageExpunge))");
        endpoint.fireTimers();
        mock.failing.add("openMailbox");
        endpoint.clearResponses();
        endpoint.fireTimers();
        assertNotSaw("* STATUS");
        mock.failing.clear();
        handler.disconnected();
        assertEquals(0, endpoint.fireTimers());
    }
}
