/*
 * MailboxStoreNamespaceTest.java
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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import org.bluezoo.gumdrop.mailbox.Mailbox;
import org.bluezoo.gumdrop.mailbox.MailboxAttribute;
import org.bluezoo.gumdrop.mailbox.MailboxStore;

import static org.junit.Assert.*;

/**
 * Tests the default namespace methods on {@link MailboxStore} (RFC 2342).
 */
public class MailboxStoreNamespaceTest {

    /**
     * Minimal MailboxStore stub that relies on default methods.
     */
    private static class MinimalStore implements MailboxStore {
        @Override
        public void open(String username) {}

        @Override
        public void close() {}

        @Override
        public char getHierarchyDelimiter() {
            return '/';
        }

        @Override
        public List<String> listMailboxes(String reference,
                                          String pattern) {
            return Collections.emptyList();
        }

        @Override
        public List<String> listSubscribed(String reference,
                                           String pattern) {
            return Collections.emptyList();
        }

        @Override
        public void subscribe(String mailboxName) {}

        @Override
        public void unsubscribe(String mailboxName) {}

        @Override
        public Mailbox openMailbox(String mailboxName,
                                   boolean readOnly) {
            return null;
        }

        @Override
        public void createMailbox(String mailboxName) {}

        @Override
        public void deleteMailbox(String mailboxName) {}

        @Override
        public void renameMailbox(String oldName, String newName) {}

        @Override
        public Set<MailboxAttribute> getMailboxAttributes(
                String mailboxName) {
            return Collections.emptySet();
        }
    }

    @Test
    public void testDefaultPersonalNamespace() {
        MailboxStore store = new MinimalStore();
        assertEquals("", store.getPersonalNamespace());
    }

    @Test
    public void testDefaultSharedNamespaceIsNull() {
        MailboxStore store = new MinimalStore();
        assertNull("Shared namespace should default to null",
                store.getSharedNamespace());
    }

    @Test
    public void testDefaultOtherUsersNamespaceIsNull() {
        MailboxStore store = new MinimalStore();
        assertNull("Other-users namespace should default to null",
                store.getOtherUsersNamespace());
    }
}
