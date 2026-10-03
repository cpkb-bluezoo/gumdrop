/*
 * ImapThreadScenarioTest.java
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
 * RFC 5256 THREAD REFERENCES and ORDEREDSUBJECT scenarios run through a
 * session: duplicate Message-IDs, reference loops, missing parents,
 * subject-based merging of unrelated threads.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapThreadScenarioTest extends ImapSessionHarness {

    private int clock;

    private void msg(String id, String subject, String references,
            String inReplyTo) throws Exception {
        clock++;
        StringBuilder sb = new StringBuilder();
        sb.append("From: a@example.com\r\n");
        sb.append("To: b@example.com\r\n");
        sb.append("Subject: ");
        sb.append(subject);
        sb.append("\r\n");
        sb.append("Date: Mon, 05 May 2025 10:");
        sb.append(clock < 10 ? "0" : "");
        sb.append(clock);
        sb.append(":00 +0000\r\n");
        if (id != null) {
            sb.append("Message-ID: <");
            sb.append(id);
            sb.append(">\r\n");
        }
        if (references != null) {
            sb.append("References: ");
            sb.append(references);
            sb.append("\r\n");
        }
        if (inReplyTo != null) {
            sb.append("In-Reply-To: ");
            sb.append(inReplyTo);
            sb.append("\r\n");
        }
        sb.append("\r\nbody\r\n");
        appendRaw("INBOX", null, sb.toString());
    }

    private String threads(String algorithm) throws Exception {
        ok("SELECT INBOX");
        ok("THREAD " + algorithm + " UTF-8 ALL");
        String line = endpoint.findLineStartingWith("* THREAD");
        assertNotNull(transcript(), line);
        return line;
    }

    @Test(timeout = 30000)
    public void repliesNestUnderTheirReferences() throws Exception {
        login();
        msg("a@t", "Plan", null, null);
        msg("b@t", "Re: Plan", "<a@t>", "<a@t>");
        msg("c@t", "Re: Re: Plan", "<a@t> <b@t>", "<b@t>");
        assertEquals("* THREAD (1 2 3)", threads("REFERENCES"));
    }

    @Test(timeout = 30000)
    public void duplicateMessageIdsStaySeparateThreads() throws Exception {
        login();
        msg("dup@t", "One", null, null);
        msg("dup@t", "Two", null, null);
        String line = threads("REFERENCES");
        assertContains(line, "(1)");
        assertContains(line, "(2)");
    }

    @Test(timeout = 30000)
    public void referenceLoopsAreBroken() throws Exception {
        login();
        msg("a@t", "Loop A", "<b@t>", null);
        msg("b@t", "Loop B", "<a@t>", null);
        String line = threads("REFERENCES");
        assertContains(line, "1");
        assertContains(line, "2");
    }

    @Test(timeout = 30000)
    public void missingParentBecomesADummyOfSiblings() throws Exception {
        login();
        msg("a@t", "First", "<gone@t>", null);
        msg("b@t", "Second", "<gone@t>", null);
        assertEquals("* THREAD ((1)(2))", threads("REFERENCES"));
    }

    @Test(timeout = 30000)
    public void unseenMessageFirstReferencedThenArrives() throws Exception {
        login();
        msg("c@t", "Child", "<p@t>", null);
        msg("p@t", "Parent", "<c@t>", null);
        msg("q@t", "Other", null, null);
        String line = threads("REFERENCES");
        assertContains(line, "3");
    }

    @Test(timeout = 30000)
    public void sameSubjectWithoutReferencesMerges() throws Exception {
        login();
        msg("a@t", "Topic", null, null);
        msg("b@t", "Re: Topic", null, null);
        assertEquals("* THREAD (1 2)", threads("REFERENCES"));
    }

    @Test(timeout = 30000)
    public void replyArrivingBeforeItsOriginalIsAdoptedByIt() throws Exception {
        login();
        msg("b@t", "Re: Topic", null, null);
        msg("a@t", "Topic", null, null);
        assertEquals("* THREAD (2 1)", threads("REFERENCES"));
    }

    @Test(timeout = 30000)
    public void unrelatedThreadsWithTheSameSubjectGetADummyParent()
            throws Exception {
        login();
        msg("a@t", "Same", null, null);
        msg("b@t", "Same", null, null);
        assertEquals("* THREAD ((1)(2))", threads("REFERENCES"));
    }

    @Test(timeout = 30000)
    public void dummyRootsWithTheSameSubjectAreCombined() throws Exception {
        login();
        msg("a@t", "Shared", "<x1@t>", null);
        msg("b@t", "Shared", "<x2@t>", null);
        msg("c@t", "Distinct", null, null);
        String line = threads("REFERENCES");
        assertContains(line, "3");
        assertContains(line, "1");
        assertContains(line, "2");
    }

    @Test(timeout = 30000)
    public void replyJoinsADummyRootWithTheSameSubject() throws Exception {
        login();
        msg("a@t", "Gap", "<x1@t>", null);
        msg("b@t", "Re: Gap", null, null);
        String line = threads("REFERENCES");
        assertContains(line, "1");
        assertContains(line, "2");
    }

    @Test(timeout = 30000)
    public void messagesWithoutIdsOrSubjectsStillThread() throws Exception {
        login();
        msg(null, "", null, null);
        msg(null, "", null, null);
        msg("z@t", "Present", null, null);
        String line = threads("REFERENCES");
        assertContains(line, "3");
    }

    @Test(timeout = 30000)
    public void orderedSubjectGroupsBySubject() throws Exception {
        login();
        msg("a@t", "Alpha", null, null);
        msg("b@t", "Beta", null, null);
        msg("c@t", "Re: Alpha", null, null);
        msg("d@t", "[list] Beta", null, null);
        assertEquals("* THREAD (1 3)(2 4)", threads("ORDEREDSUBJECT"));
    }
}
