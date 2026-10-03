/*
 * ImapSessionBranchTest.java
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import org.bluezoo.gumdrop.TcpListener;
import org.bluezoo.gumdrop.imap.server.ClientConnected;
import org.bluezoo.gumdrop.imap.server.ImapServer;
import org.bluezoo.gumdrop.imap.server.ImapServerSessionProvider;

import static org.junit.Assert.*;

/**
 * Exercises command parsing, ENABLE, SELECT options (CONDSTORE, QRESYNC,
 * OBJECTID), unsolicited mailbox updates (EXPUNGE, VANISHED, EXISTS), tag
 * validation and ID in {@link ImapProtocolHandler}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapSessionBranchTest extends ImapSessionHarness {

    /** The real unique ids of the selected mailbox, in message order. */
    private List<String> realUids() throws Exception {
        List<String> uids = new ArrayList<String>();
        int count = mock.lastBox.delegate.getMessageCount();
        for (int i = 1; i <= count; i++) {
            uids.add(mock.lastBox.delegate.getUniqueId(i));
        }
        return uids;
    }

    @Test(timeout = 30000)
    public void noopReportsExpungedMessages() throws Exception {
        selectedInbox();
        List<String> uids = realUids();
        List<String> remaining = new ArrayList<String>();
        remaining.add(uids.get(0));
        remaining.add(uids.get(2));
        mock.uidsOverride = remaining;
        ok("NOOP");
        assertSaw("* 2 EXPUNGE");
        assertSaw("* 2 EXISTS");
        ok("NOOP");
        assertNotSaw("EXPUNGE");
    }

    @Test(timeout = 30000)
    public void noopReportsSeveralExpungesNewestFirst() throws Exception {
        selectedInbox();
        List<String> uids = realUids();
        List<String> remaining = new ArrayList<String>();
        remaining.add(uids.get(2));
        mock.uidsOverride = remaining;
        ok("NOOP");
        assertSaw("* 2 EXPUNGE");
        assertSaw("* 1 EXPUNGE");
        assertSaw("* 1 EXISTS");
    }

    @Test(timeout = 30000)
    public void noopReportsNewArrivals() throws Exception {
        selectedInbox();
        List<String> uids = realUids();
        uids.add("9999");
        mock.uidsOverride = uids;
        ok("NOOP");
        assertSaw("* 4 EXISTS");
        assertNotSaw("EXPUNGE");
    }

    @Test(timeout = 30000)
    public void noopReportsVanishedWhenQresyncEnabled() throws Exception {
        selectedInbox();
        ok("ENABLE QRESYNC");
        assertSaw("* ENABLED QRESYNC");
        List<String> uids = realUids();
        List<String> remaining = new ArrayList<String>();
        remaining.add(uids.get(2));
        mock.uidsOverride = remaining;
        ok("NOOP");
        assertSaw("* VANISHED " + uids.get(1) + "," + uids.get(0));
        assertNotSaw("EXPUNGE");
    }

    @Test(timeout = 30000)
    public void enableReportsOnlyNewlyEnabledExtensions() throws Exception {
        login();
        ok("ENABLE CONDSTORE");
        assertSaw("* ENABLED CONDSTORE");
        ok("ENABLE CONDSTORE");
        assertNotSaw("ENABLED");
        ok("ENABLE QRESYNC UTF8=ACCEPT NOTIFY BOGUS");
        assertSaw("* ENABLED QRESYNC UTF8=ACCEPT NOTIFY");
        ok("ENABLE QRESYNC NOTIFY UTF8=ACCEPT");
        assertNotSaw("ENABLED");
        bad("ENABLE");
    }

    @Test(timeout = 30000)
    public void enableHonoursDisabledExtensions() throws Exception {
        listener.setEnableCONDSTORE(false);
        listener.setEnableQRESYNC(false);
        listener.setEnableUTF8ACCEPT(false);
        listener.setEnableNOTIFY(false);
        reconnect();
        login();
        ok("ENABLE CONDSTORE QRESYNC UTF8=ACCEPT NOTIFY");
        assertNotSaw("ENABLED");
    }

    @Test(timeout = 30000)
    public void selectParsesModifiersAndRejectsMalformedNames() throws Exception {
        selectedInbox();
        bad("SELECT \"unterminated");
        bad("SELECT \"\"");
        bad("SELECT");
        ok("SELECT \"INBOX\" (CONDSTORE)");
        assertSaw("[READ-WRITE]");
        ok("EXAMINE INBOX (CONDSTORE)");
        assertSaw("[READ-ONLY]");
        no("SELECT INBOX extra");
        bad("SELECT INBOX (QRESYNC (1 1))");
        ok("ENABLE QRESYNC");
        bad("SELECT INBOX (QRESYNC (x y))");
    }

    @Test(timeout = 30000)
    public void selectReportsMailboxIdAndHighestModseq() throws Exception {
        login();
        mock.mailboxId = "mbox-1";
        mock.highestModSeq = 42;
        ok("ENABLE CONDSTORE");
        ok("SELECT INBOX");
        assertSaw("OK [MAILBOXID (mbox-1)]");
        assertSaw("OK [HIGHESTMODSEQ 42]");
    }

    @Test(timeout = 30000)
    public void selectWithQresyncSendsVanishedAndChanges() throws Exception {
        selectedInbox();
        long validity = mock.lastBox.delegate.getUidValidity();
        List<String> uids = realUids();
        ok("ENABLE QRESYNC");
        mock.expungedSince.add(Long.valueOf(7));
        mock.expungedSince.add(Long.valueOf(8));
        mock.changedSince.add(Long.valueOf(uids.get(1)));
        mock.modSeq = 11;
        ok("SELECT INBOX (QRESYNC (" + validity + " 3))");
        assertSaw("* VANISHED (EARLIER) 7,8");
        assertSaw("* 2 FETCH (UID " + uids.get(1) + " FLAGS (");
        assertSaw("MODSEQ (11))");
        ok("SELECT INBOX (QRESYNC (" + validity + " 3 7))");
        assertSaw("* VANISHED (EARLIER) 7");
        assertNotSaw("(EARLIER) 7,8");
        ok("SELECT INBOX (QRESYNC (" + validity + " 3 not-a-set))");
        assertSaw("* VANISHED (EARLIER) 7,8");
        ok("SELECT INBOX (QRESYNC (" + validity + " 3 1000))");
        assertNotSaw("VANISHED");
        ok("SELECT INBOX (QRESYNC (" + (validity + 1) + " 3))");
        assertNotSaw("VANISHED");
        ok("SELECT INBOX (QRESYNC 5)");
        ok("SELECT INBOX (QRESYNC (" + validity + "))");
    }

    @Test(timeout = 30000)
    public void tagsAreValidated() throws Exception {
        endpoint.clearResponses();
        send("NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("a%b NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("a(b NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("a{b NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("a\\b NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("a\"b NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("* NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("+ NOOP\r\n");
        assertSaw("* BAD");
        endpoint.clearResponses();
        send("ok.tag-1 NOOP\r\n");
        assertSaw("ok.tag-1 OK");
    }

    @Test(timeout = 30000)
    public void idReportsConfiguredServerFields() throws Exception {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("name", "mock");
        fields.put("support-url", null);
        fields.put("version", "9");
        listener.setServerIdFields(fields);
        reconnect();
        ok("ID (\"name\" \"client\")");
        assertSaw("* ID (\"name\" \"mock\" \"support-url\" NIL \"version\" \"9\")");
        listener.setServerIdFields(new LinkedHashMap<String, String>());
        reconnect();
        ok("ID NIL");
        assertSaw("* ID (\"name\" \"gumdrop\" \"version\" \"");
    }

    @Test(timeout = 30000)
    public void sortPutsMessagesWithoutContextFirst() throws Exception {
        selectedInbox();
        mock.nullContexts.add(Integer.valueOf(2));
        String[] keys = new String[] {"DATE", "SIZE", "SUBJECT", "FROM", "TO"};
        for (int i = 0; i < keys.length; i++) {
            ok("SORT (" + keys[i] + ") UTF-8 ALL");
            String line = lineStarting("* SORT");
            assertTrue(keys[i] + ": " + line, line.startsWith("* SORT 2"));
        }
        ok("SORT (ARRIVAL) UTF-8 ALL");
        assertSaw("* SORT 1 2 3");
        ok("SORT (CC) UTF-8 ALL");
        assertSaw("* SORT 1 2 3");
        ok("SORT (REVERSE SUBJECT) UTF-8 ALL");
        String reversed = lineStarting("* SORT");
        assertTrue(reversed, reversed.endsWith(" 2"));
    }

    @Test(timeout = 30000)
    public void failingSessionProvidersFallBackToDefaultBehaviour()
            throws Exception {
        listener.setSessionProvider(new ImapServerSessionProvider() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                throw new IllegalStateException("provider down");
            }
        });
        ImapServer server = new ImapServer() {
            @Override
            public ClientConnected openSession(TcpListener l) {
                throw new IllegalStateException("server provider down");
            }
        };
        listener.setServer(server);
        reconnect();
        ok("LOGIN editor editor");
        ok("NOOP");
    }
}
