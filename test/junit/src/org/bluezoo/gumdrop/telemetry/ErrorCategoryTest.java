/*
 * ErrorCategoryTest.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.telemetry;

import org.junit.Test;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ErrorCategory} mappings.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ErrorCategoryTest {

    private static class CustomAuthFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static class BadParseError extends Exception {
        private static final long serialVersionUID = 1L;
    }

    @Test
    public void accessors() {
        assertEquals("timeout", ErrorCategory.TIMEOUT.getCode());
        assertEquals("timeout", ErrorCategory.TIMEOUT.getAttributeValue());
        assertEquals("error.type.timeout", ErrorCategory.TIMEOUT.getAttributeKey());
    }

    @Test
    public void httpStatusMapping() {
        assertNull(ErrorCategory.fromHttpStatus(200));
        assertNull(ErrorCategory.fromHttpStatus(399));
        assertEquals(ErrorCategory.CLIENT_ERROR, ErrorCategory.fromHttpStatus(400));
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED, ErrorCategory.fromHttpStatus(401));
        assertEquals(ErrorCategory.AUTHORIZATION_DENIED, ErrorCategory.fromHttpStatus(403));
        assertEquals(ErrorCategory.NOT_FOUND, ErrorCategory.fromHttpStatus(404));
        assertEquals(ErrorCategory.NOT_SUPPORTED, ErrorCategory.fromHttpStatus(405));
        assertEquals(ErrorCategory.NOT_SUPPORTED, ErrorCategory.fromHttpStatus(501));
        assertEquals(ErrorCategory.TIMEOUT, ErrorCategory.fromHttpStatus(408));
        assertEquals(ErrorCategory.ALREADY_EXISTS, ErrorCategory.fromHttpStatus(409));
        assertEquals(ErrorCategory.LIMIT_EXCEEDED, ErrorCategory.fromHttpStatus(413));
        assertEquals(ErrorCategory.LIMIT_EXCEEDED, ErrorCategory.fromHttpStatus(429));
        assertEquals(ErrorCategory.INTERNAL_ERROR, ErrorCategory.fromHttpStatus(500));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromHttpStatus(503));
        assertEquals(ErrorCategory.CLIENT_ERROR, ErrorCategory.fromHttpStatus(418));
        assertEquals(ErrorCategory.INTERNAL_ERROR, ErrorCategory.fromHttpStatus(599));
    }

    @Test
    public void smtpReplyMapping() {
        assertNull(ErrorCategory.fromSmtpReplyCode(250));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromSmtpReplyCode(421));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromSmtpReplyCode(451));
        assertEquals(ErrorCategory.LIMIT_EXCEEDED, ErrorCategory.fromSmtpReplyCode(452));
        assertEquals(ErrorCategory.PROTOCOL_ERROR, ErrorCategory.fromSmtpReplyCode(503));
        assertEquals(ErrorCategory.NOT_SUPPORTED, ErrorCategory.fromSmtpReplyCode(504));
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED, ErrorCategory.fromSmtpReplyCode(535));
        assertEquals(ErrorCategory.NOT_FOUND, ErrorCategory.fromSmtpReplyCode(550));
        assertEquals(ErrorCategory.LIMIT_EXCEEDED, ErrorCategory.fromSmtpReplyCode(552));
        assertEquals(ErrorCategory.CLIENT_ERROR, ErrorCategory.fromSmtpReplyCode(553));
        assertEquals(ErrorCategory.POLICY_VIOLATION, ErrorCategory.fromSmtpReplyCode(554));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromSmtpReplyCode(499));
        assertEquals(ErrorCategory.INTERNAL_ERROR, ErrorCategory.fromSmtpReplyCode(599));
    }

    @Test
    public void ftpReplyMapping() {
        assertNull(ErrorCategory.fromFtpReplyCode(226));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromFtpReplyCode(421));
        assertEquals(ErrorCategory.CONNECTION_ERROR, ErrorCategory.fromFtpReplyCode(425));
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED, ErrorCategory.fromFtpReplyCode(530));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromFtpReplyCode(450));
        assertEquals(ErrorCategory.STORAGE_ERROR, ErrorCategory.fromFtpReplyCode(452));
        assertEquals(ErrorCategory.PROTOCOL_ERROR, ErrorCategory.fromFtpReplyCode(500));
        assertEquals(ErrorCategory.NOT_SUPPORTED, ErrorCategory.fromFtpReplyCode(504));
        assertEquals(ErrorCategory.NOT_FOUND, ErrorCategory.fromFtpReplyCode(550));
        assertEquals(ErrorCategory.STORAGE_ERROR, ErrorCategory.fromFtpReplyCode(553));
        assertEquals(ErrorCategory.TEMPORARILY_UNAVAILABLE, ErrorCategory.fromFtpReplyCode(499));
        assertEquals(ErrorCategory.INTERNAL_ERROR, ErrorCategory.fromFtpReplyCode(599));
    }

    @Test
    public void exceptionMapping() {
        assertEquals(ErrorCategory.UNKNOWN, ErrorCategory.fromException(null));
        assertEquals(ErrorCategory.CONNECTION_ERROR,
            ErrorCategory.fromException(new ConnectException("refused")));
        assertEquals(ErrorCategory.CONNECTION_ERROR,
            ErrorCategory.fromException(new UnknownHostException("h")));
        assertEquals(ErrorCategory.CONNECTION_LOST,
            ErrorCategory.fromException(new SocketException("Connection reset")));
        assertEquals(ErrorCategory.CONNECTION_LOST,
            ErrorCategory.fromException(new SocketException("Broken pipe")));
        assertEquals(ErrorCategory.IO_ERROR,
            ErrorCategory.fromException(new SocketException("other")));
        assertEquals(ErrorCategory.IO_ERROR,
            ErrorCategory.fromException(new EOFException()));
        assertEquals(ErrorCategory.TLS_ERROR,
            ErrorCategory.fromException(new SSLException("x")));
        assertEquals(ErrorCategory.TLS_ERROR,
            ErrorCategory.fromException(new CertificateException("x")));
        assertEquals(ErrorCategory.TIMEOUT,
            ErrorCategory.fromException(new SocketTimeoutException("slow")));
        assertEquals(ErrorCategory.TIMEOUT,
            ErrorCategory.fromException(new RuntimeException("Read TimeOut hit")));
        assertEquals(ErrorCategory.NOT_FOUND,
            ErrorCategory.fromException(new FileNotFoundException("f")));
        assertEquals(ErrorCategory.NOT_FOUND,
            ErrorCategory.fromException(new NoSuchFileException("f")));
        assertEquals(ErrorCategory.ALREADY_EXISTS,
            ErrorCategory.fromException(new FileAlreadyExistsException("f")));
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED,
            ErrorCategory.fromException(new CustomAuthFailure()));
        assertEquals(ErrorCategory.PROTOCOL_ERROR,
            ErrorCategory.fromException(new BadParseError()));
        assertEquals(ErrorCategory.INTERNAL_ERROR,
            ErrorCategory.fromException(new IllegalStateException()));
    }
}
