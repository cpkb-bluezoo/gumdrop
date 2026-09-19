/*
 * SortRequest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.bluezoo.gumdrop.mailbox.SearchCriteria;

import java.util.Collections;
import java.util.List;

/**
 * Parsed RFC 5256 SORT / UID SORT command arguments.
 */
public final class SortRequest {

    private final List<SortCriterion> sortProgram;
    private final String charset;
    private final SearchCriteria searchCriteria;

    public SortRequest(List<SortCriterion> sortProgram, String charset,
            SearchCriteria searchCriteria) {
        this.sortProgram = Collections.unmodifiableList(sortProgram);
        this.charset = charset;
        this.searchCriteria = searchCriteria;
    }

    public List<SortCriterion> getSortProgram() {
        return sortProgram;
    }

    public String getCharset() {
        return charset;
    }

    public SearchCriteria getSearchCriteria() {
        return searchCriteria;
    }
}
