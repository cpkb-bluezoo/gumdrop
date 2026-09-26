/*
 * ImapNotifyParserTest.java
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

import java.text.ParseException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ImapNotifyParser}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ImapNotifyParserTest {

    @Test
    public void testParseNone() throws Exception {
        ImapNotifyRequest req = new ImapNotifyParser("NONE").parse();
        assertEquals(ImapNotifyRequest.Form.NONE, req.getForm());
    }

    @Test
    public void testParseSetWithStatus() throws Exception {
        String args = "SET STATUS (selected (MessageNew MessageExpunge)) "
                + "(mailboxes Other (MessageNew MessageExpunge))";
        ImapNotifyRequest req = new ImapNotifyParser(args).parse();
        assertEquals(ImapNotifyRequest.Form.SET, req.getForm());
        assertTrue(req.isStatusIndicator());
        assertEquals(2, req.getEventGroups().size());
        assertTrue(req.getEventGroups().get(0).getMailboxFilter()
                .affectsSelectedMailbox());
    }

    @Test
    public void testParseMessageNewFetchAtts() throws Exception {
        String args = "SET (selected (MessageNew (uid flags) MessageExpunge))";
        ImapNotifyRequest req = new ImapNotifyParser(args).parse();
        ImapNotifyEventSpec spec = req.getEventGroups().get(0).getEvents().get(0);
        assertEquals(ImapNotifyEventType.MESSAGE_NEW, spec.getType());
        assertEquals(2, spec.getMessageNewFetchAtts().size());
        assertEquals("uid", spec.getMessageNewFetchAtts().get(0));
    }

    @Test
    public void testRejectUnknownEvent() throws Exception {
        try {
            new ImapNotifyParser("SET (personal (FutureEvent))").parse();
            fail("expected ParseException");
        } catch (ParseException e) {
            assertTrue(e.getMessage().contains("FutureEvent")
                    || e.getMessage().contains("Unknown"));
        }
    }
}
