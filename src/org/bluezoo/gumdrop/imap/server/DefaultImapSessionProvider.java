/*
 * DefaultImapSessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.server;

/**
 * @deprecated use {@link MailboxStoreImapSessionProvider} via
 *             {@link ImapServerSessionProviders#mailbox()}.
 */
@Deprecated
public final class DefaultImapSessionProvider
        extends MailboxStoreImapSessionProvider {
}
