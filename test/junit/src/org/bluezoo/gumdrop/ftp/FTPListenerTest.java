/*
 * FTPListenerTest.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for {@link FtpListener}, focusing on RFC 4217 implicit FTPS
 * port defaulting.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
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
