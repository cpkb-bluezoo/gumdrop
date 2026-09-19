/*
 * BaseSubjectTest.java
 * Copyright (C) 2026 Chris Burdess
 */

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import static org.junit.Assert.*;

public class BaseSubjectTest {

    @Test
    public void testStripRePrefix() {
        assertEquals("Hello",
                BaseSubject.extract("Re: Hello"));
    }

    @Test
    public void testStripFwdPrefix() {
        assertEquals("Report",
                BaseSubject.extract("Fwd: Report"));
    }

    @Test
    public void testNestedReSubject() {
        assertEquals("Meeting",
                BaseSubject.extract("Re: Re: Meeting"));
    }

    @Test
    public void testEmptySubject() {
        assertEquals("", BaseSubject.extract(""));
    }
}
