/*
 * Part.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.util;

import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.MessagingException;
import javax.mail.internet.ContentType;
import javax.mail.internet.ContentDisposition;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeUtility;

/**
 * A MIME part.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class Part {

    static final Logger LOGGER = Logger.getLogger(Part.class.getName());

    /**
     * The headers of this part.
     */
    protected InternetHeaders headers;

    private ContentType contentType;
    private ContentDisposition contentDisposition;

    /**
     * The content of this part.
     */
    protected Content content;

    /**
     * Constructor with headers and content.
     * @param headers the headers
     * @param content the content of this part
     */
    public Part(InternetHeaders headers, Content content) {
        this.headers = headers;
        this.content = content;
    }

    /**
     * Returns the size of the content of this body part in bytes.
     */
    public long getContentLength() {
        return content.length();
    }

    /**
     * Returns the value of the RFC 822 Content-Type header field, or
     * <code>null</code> if the header is not available.
     */
    public ContentType getContentType() throws MessagingException {
        if (contentType == null) {
            String[] ct = headers.getHeader("Content-Type");
            if (ct != null && ct.length > 0) {
                contentType = new ContentType(ct[0]);
            }
        }
        return contentType;
    }

    /**
     * Returns the value of the RFC 822 Content-Disposition header field, or
     * <code>null</code> if the header is not available.
     */
    public ContentDisposition getContentDisposition() throws MessagingException {
        if (contentDisposition == null) {
            String[] cd = headers.getHeader("Content-Disposition");
            if (cd != null && cd.length > 0) {
                contentDisposition = new ContentDisposition(cd[0]);
            }
        }
        return contentDisposition;
    }

    /**
     * Returns the filename associated with this body part.
     * This method returns the value of the "filename" parameter from the
     * Content-Disposition header field.
     * If the latter is not available, it returns the value of the "name"
     * parameter from the Content-Type header field.
     */
    public String getFileName() throws MessagingException {
        String filename = getContentDisposition().getParameter("filename");
        if (filename == null) {
            filename = getContentType().getParameter("name");
        }
        if (filename != null) {
            try {
                filename = MimeUtility.decodeText(filename);
            } catch (UnsupportedEncodingException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
            }
        }
        return filename;
    }

    /**
     * Returns the unencoded bytes of the content without applying any
     * content transfer decoding.
     */
    public InputStream getInputStream() throws IOException {
        return content.getInputStream();
    }

    /**
     * Returns all the values for the specified header name.
     * Note that headers may be encoded as per RFC 2047 if they contain
     * non-US-ASCII characters: these should be decoded.
     * @param name the header name
     */
    public String[] getHeader(String name) {
        return headers.getHeader(name);
    }

    /**
     * Returns the headers.
     */
    public InternetHeaders getHeaders() {
        return headers;
    }

}
