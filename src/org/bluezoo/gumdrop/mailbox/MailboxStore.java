/*
 * MailboxStore.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Interface for a mail store containing multiple mailboxes.
 * This provides hierarchical mailbox management as required by IMAP,
 * while remaining compatible with POP3's single-mailbox model.
 * 
 * <p>A mail store represents a user's complete mail storage, typically
 * containing multiple mailboxes (folders) such as INBOX, Sent, Drafts, etc.
 * 
 * <p>Mailbox names follow IMAP conventions:
 * <ul>
 *   <li>"INBOX" is the primary mailbox (case-insensitive)</li>
 *   <li>Hierarchy is represented using a delimiter (typically "/" or ".")</li>
 *   <li>Names are case-sensitive except for INBOX</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see Mailbox
 * @see MailboxFactory
 */
public interface MailboxStore {

    /**
     * Opens the mail store for the specified user.
     * This should be called after successful authentication.
     * 
     * @param username the user whose mail store to open
     * @throws IOException if the store cannot be opened
     */
    void open(String username) throws IOException;

    /**
     * Closes the mail store and releases any resources.
     * All open mailboxes should be closed before calling this.
     * 
     * @throws IOException if the store cannot be closed properly
     */
    void close() throws IOException;

    /**
     * Returns the hierarchy delimiter character used by this store.
     * This separates levels in mailbox names (e.g., "INBOX/Subfolder").
     * 
     * @return the hierarchy delimiter (typically "/" or ".")
     */
    char getHierarchyDelimiter();

    /**
     * Returns the personal namespace prefix, if any.
     * For IMAP NAMESPACE extension support.
     * 
     * @return the personal namespace prefix, or empty string if none
     */
    default String getPersonalNamespace() {
        return "";
    }

    /**
     * Lists mailboxes matching the given reference and pattern.
     * 
     * <p>The reference is a prefix that is prepended to the pattern.
     * The pattern may contain wildcards:
     * <ul>
     *   <li>"*" matches any characters including hierarchy delimiter</li>
     *   <li>"%" matches any characters except hierarchy delimiter</li>
     * </ul>
     * 
     * @param reference the reference name (prefix)
     * @param pattern the mailbox name pattern with optional wildcards
     * @return list of matching mailbox names
     * @throws IOException if the list cannot be retrieved
     */
    List<String> listMailboxes(String reference, String pattern) throws IOException;

    /**
     * Returns subscription status for mailboxes matching the pattern.
     * For IMAP LSUB command support.
     * 
     * @param reference the reference name (prefix)
     * @param pattern the mailbox name pattern with optional wildcards
     * @return list of subscribed mailbox names matching the pattern
     * @throws IOException if the list cannot be retrieved
     */
    List<String> listSubscribed(String reference, String pattern) throws IOException;

    /**
     * Subscribes to a mailbox.
     * 
     * @param mailboxName the name of the mailbox to subscribe to
     * @throws IOException if the subscription fails
     */
    void subscribe(String mailboxName) throws IOException;

    /**
     * Unsubscribes from a mailbox.
     * 
     * @param mailboxName the name of the mailbox to unsubscribe from
     * @throws IOException if the unsubscription fails
     */
    void unsubscribe(String mailboxName) throws IOException;

    /**
     * Opens a mailbox by name.
     * For POP3 compatibility, "INBOX" should always be available.
     * 
     * @param mailboxName the name of the mailbox to open
     * @param readOnly true to open in read-only mode
     * @return the opened mailbox
     * @throws IOException if the mailbox cannot be opened
     */
    Mailbox openMailbox(String mailboxName, boolean readOnly) throws IOException;

    /**
     * Creates a new mailbox.
     * 
     * @param mailboxName the name of the mailbox to create
     * @throws IOException if the mailbox cannot be created
     */
    void createMailbox(String mailboxName) throws IOException;

    /**
     * Deletes a mailbox.
     * The mailbox must be empty and not currently selected.
     * 
     * @param mailboxName the name of the mailbox to delete
     * @throws IOException if the mailbox cannot be deleted
     */
    void deleteMailbox(String mailboxName) throws IOException;

    /**
     * Renames a mailbox.
     * 
     * @param oldName the current mailbox name
     * @param newName the new mailbox name
     * @throws IOException if the mailbox cannot be renamed
     */
    void renameMailbox(String oldName, String newName) throws IOException;

    /**
     * Returns the mailbox attributes for the specified mailbox.
     * These are IMAP mailbox attributes like \Noselect, \Noinferiors, etc.
     * 
     * @param mailboxName the mailbox name
     * @return set of attribute strings (without leading backslash)
     * @throws IOException if attributes cannot be retrieved
     */
    Set<String> getMailboxAttributes(String mailboxName) throws IOException;

    /**
     * Returns the quota root for the specified mailbox.
     * For IMAP QUOTA extension support.
     * 
     * @param mailboxName the mailbox name
     * @return the quota root name, or null if quotas are not supported
     * @throws IOException if the quota root cannot be determined
     */
    default String getQuotaRoot(String mailboxName) throws IOException {
        return null;
    }

    /**
     * Returns the quota information for the specified quota root.
     * For IMAP QUOTA extension support.
     * 
     * @param quotaRoot the quota root name
     * @return the quota information, or null if not available
     * @throws IOException if quota information cannot be retrieved
     */
    default Quota getQuota(String quotaRoot) throws IOException {
        return null;
    }

    /**
     * Quota information for a quota root.
     */
    interface Quota {
        /**
         * Returns the quota root name.
         */
        String getRoot();

        /**
         * Returns the current storage usage in kilobytes.
         */
        long getStorageUsed();

        /**
         * Returns the storage limit in kilobytes, or -1 if unlimited.
         */
        long getStorageLimit();

        /**
         * Returns the current message count.
         */
        long getMessageCount();

        /**
         * Returns the message limit, or -1 if unlimited.
         */
        long getMessageLimit();
    }

}

