/*
 * ThreadReplyHandler.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap.client;

/**
 * Receives the RFC 5256 untagged THREAD response body (parenthesized lists).
 */
public interface ThreadReplyHandler {

    void handleThread(ClientSelectedState session, String threadData);

    void handleError(ClientSelectedState session, String message);
}
