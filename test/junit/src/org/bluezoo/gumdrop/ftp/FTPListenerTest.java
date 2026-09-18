/*
 * FTPListenerTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.ftp;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FtpListener}, focusing on RFC 4217 implicit FTPS
 * port defaulting.
 */
public class FTPListenerTest {

    @Test
    public void testDefaultPortIsFTP() {
        FtpListener listener = new FtpListener();
        assertEquals("Default port should be 21",
                21, listener.getPort());
    }

    @Test
    public void testSecureDefaultPortIsFTPS() {
        // RFC 4217: implicit FTPS uses port 990
        FtpListener listener = new FtpListener();
        listener.setSecure(true);
        assertEquals("Secure listener should default to port 990",
                990, listener.getPort());
    }

    @Test
    public void testExplicitPortOverridesSecureDefault() {
        FtpListener listener = new FtpListener();
        listener.setSecure(true);
        listener.setPort(2121);
        assertEquals("Explicitly set port should override FTPS default",
                2121, listener.getPort());
    }

    @Test
    public void testExplicitPortNotOverriddenBySecure() {
        FtpListener listener = new FtpListener();
        listener.setPort(8021);
        listener.setSecure(true);
        assertEquals("Port set before setSecure should be preserved",
                8021, listener.getPort());
    }

    @Test
    public void testNonSecureExplicitPort() {
        FtpListener listener = new FtpListener();
        listener.setPort(2100);
        assertEquals(2100, listener.getPort());
    }

    @Test
    public void testDescriptionFTP() {
        FtpListener listener = new FtpListener();
        assertEquals("ftp", listener.getDescription());
    }

    @Test
    public void testDescriptionFTPS() {
        FtpListener listener = new FtpListener();
        listener.setSecure(true);
        assertEquals("ftps", listener.getDescription());
    }
}
