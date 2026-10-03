/*
 * StreamH2WebSocketUpgradeTest.java
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
package org.bluezoo.gumdrop.mailbox.index;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link IndexedMessageContext#isIndexedHeader}, which tells the
 * mailbox search wrappers which headers the index can answer and which need
 * the message to be parsed.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IndexedHeaderPredicateTest {

    @Test
    public void indexedHeaderPredicateMatchesTheIndexedNames() {
        String[] indexed = {"From", "SENDER", "to", "Cc", "bcc", "Subject", "Message-ID",
            "References", "In-Reply-To"};
        for (int i = 0; i < indexed.length; i++) {
            assertTrue(indexed[i], IndexedMessageContext.isIndexedHeader(indexed[i]));
        }
        assertFalse(IndexedMessageContext.isIndexedHeader("X-Mailer"));
        assertFalse(IndexedMessageContext.isIndexedHeader("Date"));
    }
}
