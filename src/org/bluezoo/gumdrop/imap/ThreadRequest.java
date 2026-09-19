/*
 * ThreadRequest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

/**
 * Parsed RFC 5256 THREAD / UID THREAD command arguments.
 */
public final class ThreadRequest {

    private final ThreadAlgorithm algorithm;
    private final String charset;
    private final SearchCriteria searchCriteria;

    public ThreadRequest(ThreadAlgorithm algorithm, String charset,
            SearchCriteria searchCriteria) {
        this.algorithm = algorithm;
        this.charset = charset;
        this.searchCriteria = searchCriteria;
    }

    public ThreadAlgorithm getAlgorithm() {
        return algorithm;
    }

    public String getCharset() {
        return charset;
    }

    public SearchCriteria getSearchCriteria() {
        return searchCriteria;
    }
}
