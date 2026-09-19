/*
 * EnableReplyHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.imap.client;

import java.util.List;

/**
 * Handler for ENABLE command replies (RFC 5161, RFC 6855, RFC 7162).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public interface EnableReplyHandler extends ReplyHandler {

    /**
     * Called when the server accepts one or more extensions.
     *
     * @param session authenticated (or selected) session
     * @param enabled extension names from untagged ENABLED responses
     */
    void handleEnabled(ClientAuthenticatedState session,
            List<String> enabled);

    /**
     * Called when ENABLE fails with a tagged NO or BAD.
     *
     * @param session session in the pre-failure state
     * @param message server response text
     */
    void handleError(ClientAuthenticatedState session, String message);
}
