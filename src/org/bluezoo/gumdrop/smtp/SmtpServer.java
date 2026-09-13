/*
 * SmtpServer.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.smtp;

/**
 * Protocol-root re-export of {@link org.bluezoo.gumdrop.smtp.server.SmtpServer} (Gumdrop 3 §C.2 Option 2).
 *
 * @see org.bluezoo.gumdrop.smtp.server.SmtpServer
 * @see docs/NAMING-TAXONOMY.md
 */
public abstract class SmtpServer extends org.bluezoo.gumdrop.smtp.server.SmtpServer {

    /**
     * @see org.bluezoo.gumdrop.smtp.server.SmtpServer#compose()
     */
    public static org.bluezoo.gumdrop.smtp.server.SmtpServer.Composer compose() {
        return org.bluezoo.gumdrop.smtp.server.SmtpServer.compose();
    }

    /**
     * @deprecated use {@link #compose()}.
     * @see org.bluezoo.gumdrop.smtp.server.SmtpServer#builder()
     */
    @Deprecated
    public static org.bluezoo.gumdrop.smtp.server.SmtpServer.Composer builder() {
        return compose();
    }

}
