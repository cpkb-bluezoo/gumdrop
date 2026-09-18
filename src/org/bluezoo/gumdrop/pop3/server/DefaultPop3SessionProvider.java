/*
 * DefaultPop3SessionProvider.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.pop3.server;

/**
 * @deprecated use {@link MailboxStorePop3SessionProvider} via
 *             {@link Pop3ServerSessionProviders#mailbox()}.
 */
@Deprecated
public final class DefaultPop3SessionProvider
        extends MailboxStorePop3SessionProvider {
}
