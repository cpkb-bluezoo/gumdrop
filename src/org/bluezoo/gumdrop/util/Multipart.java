/*
 * Multipart.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.util;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javax.mail.MessagingException;
import javax.mail.internet.ContentType;
import javax.mail.internet.ContentDisposition;
import javax.mail.internet.InternetHeaders;

/**
 * A MIME multipart container.
 * We can use this to parse multipart entities without the overhead of the
 * entire JavaMail or activation APIs.
 *
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 */
public class Multipart extends Part {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.util.L10N");

    private static final String BOUNDARY_SPECIALS = "'()+_,-./:=?";

    enum BoundaryTest { NONE, BOUNDARY, FINAL_BOUNDARY; }

    private Content preamble;
    private List<Part> parts;

    public Multipart(InternetHeaders headers, Content content) throws IOException {
        super(headers, content);
        parts = new ArrayList<>();
        try {
            ContentType contentType = getContentType();
            String boundary = contentType.getParameter("boundary");
            if (boundary == null) {
                throw new IOException(L10N.getString("err.no_boundary"));
            }
            int boundaryLength = boundary.length();
            if (boundaryLength < 1 || boundaryLength > 70) {
                throw new IOException(L10N.getString("err.boundary_length"));
            }
            InputStream in = content.getInputStream();
            if (!in.markSupported()) {
                in = new BufferedInputStream(in); // ensure we can mark and reset
            }
            // Total amount we need to mark includes CRLF '--' boundary '--' CRLF
            int markLength = boundaryLength + 8;
            // Test just up to end of boundary, following CRLF or '--' CRLF
            // tested separately
            // NB the initial CRLF is considered part of the boundary
            byte[] boundaryTest = new byte[markLength - 4];
            boundaryTest[0] = '\r';
            boundaryTest[1] = '\n';
            boundaryTest[2] = '-';
            boundaryTest[3] = '-';
            for (int i = 0; i < boundaryLength; i++) {
                // verify at the same time
                char c = boundary.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || BOUNDARY_SPECIALS.indexOf(c) >= 0) {
                    boundaryTest[i + 4] = (byte) c;
                } else {
                    String message = L10N.getString("err.boundary_char");
                    message = MessageFormat.format(message, c, i);
                    throw new IOException(message);
                } 
            }
            // The first boundary will not be preceded by CRLF if it is at
            // the start of the content.
            byte[] firstBoundaryTest = new byte[boundaryTest.length - 2];
            System.arraycopy(boundaryTest, 2, firstBoundaryTest, 0, firstBoundaryTest.length);

            Content partContent = content.create(null); // first will be preamble, if any
            OutputStream out = partContent.getOutputStream(); // sink for writing part content to
            byte[] buf = new byte[Math.max(4096, in.available())];
            in.mark(markLength);
            int len = in.read(buf);
            boolean seenFinalBoundary = false;
            InternetHeaders partHeaders = null;
            boolean first = true;
            while (len != -1) {
                int advance = 0;
                // we could check as well that the Content-Length matches if
                // it is specified
                byte[] test = first ? firstBoundaryTest : boundaryTest;
                switch (testBoundary(buf, len, test)) { // are we at the start of a boundary
                    case FINAL_BOUNDARY:
                        // end of part and no more parts
                        if (seenFinalBoundary) {
                            throw new IOException(L10N.getString("err.duplicate_final_boundary"));
                        }
                        seenFinalBoundary = true;
                        out.close();
                        advance = test.length + 4; // account for 2 extra hyphens
                        endPart(partHeaders, partContent);
                        in.reset();
                        in.skip(advance);
                        partContent = content.create(null); // can store any trailing gubbins
                        out = partContent.getOutputStream();
                        break;
                    case BOUNDARY:
                        // end of part
                        out.close();
                        advance += test.length + 2; // advance to after CRLF
                        endPart(partHeaders, partContent);
                        in.reset();
                        in.skip(advance);
                        partHeaders = new InternetHeaders(in);
                        String fileName = getFileName(partHeaders);
                        partContent = content.create(fileName);
                        out = partContent.getOutputStream();
                        break;
                    case NONE:
                        int start = indexOf(buf, len, (byte) '\r'); // find the first occurrence of CR
                        if (start == 0) {
                            // start of buffer is CR but didn't match
                            // boundary. So consume the CR and loop
                            out.write(buf, 0, 1);
                            in.reset();
                            in.skip(1);
                        } else if (start != -1) {
                            // flush part up to CR
                            out.write(buf, 0, start);
                            // reset and reposition stream at CR
                            in.reset();
                            in.skip(start);
                        } else {
                            // flush entire buffer to partContent
                            out.write(buf, 0, len);
                        }
                }

                in.mark(markLength);
                len = in.read(buf);
                first = false;
            }
            out.close();
            if (!seenFinalBoundary) {
                String message = L10N.getString("err.no_final_boundary");
                message = MessageFormat.format(message, boundary);
                throw new IOException(message);
            }
        } catch (MessagingException e) {
            IOException e2 = new IOException(L10N.getString("err.read_headers"));
            e2.initCause(e);
            throw e2;
        }
    }

    private void endPart(InternetHeaders partHeaders, Content partContent) throws MessagingException, IOException {
        //System.err.println("endPart headers="+toString(partHeaders));
        if (partHeaders == null) { // preamble
            preamble = partContent;
        } else {
            Part part;
            String contentType = getHeader(partHeaders, "Content-Type");
            if (contentType != null && contentType.startsWith("multipart/")) {
                part = new Multipart(partHeaders, partContent);
            } else {
                part = new Part(partHeaders, partContent);
            }
            //System.err.println("created part: "+contentType+" filename="+getFileName(partHeaders));
            parts.add(part);
        }
    }

    /*private String toString(InternetHeaders headers) {
        if (headers == null) { return "null"; }
        StringBuilder buf = new StringBuilder("{");
        for (java.util.Enumeration i = headers.getAllHeaders(); i.hasMoreElements(); ) {
            javax.mail.Header h = (javax.mail.Header) i.nextElement();
            buf.append(h.getName()).append("=").append(h.getValue()).append(" ");
        }
        buf.append("}");
        return buf.toString();
    }*/

    private String getHeader(InternetHeaders headers, String name) throws MessagingException {
        String[] vals = headers.getHeader(name);
        return (vals != null && vals.length > 0) ? vals[0] : null;
    }

    private String getFileName(InternetHeaders headers) throws MessagingException {
        String fileName = null;
        String disposition = getHeader(headers, "Content-Disposition");
        if (disposition != null) {
            ContentDisposition cd = new ContentDisposition(disposition);
            fileName = cd.getParameter("filename");
        }
        if (fileName == null) {
            String contentType = getHeader(headers, "Content-Type");
            if (contentType != null) {
                ContentType ct = new ContentType(contentType);
                fileName = ct.getParameter("name");
            }
        }
        return fileName;
    }

    private static int indexOf(byte[] buf, int len, byte test) {
        for (int i = 0; i < len; i++) {
            if (buf[i] == test) {
                return i;
            }
        }
        return -1;
    }

    private static BoundaryTest testBoundary(byte[] buf, int len, byte[] test) {
        if (len < test.length) {
            return BoundaryTest.NONE;
        }
        int i = 0;
        for (; i < test.length; i++) {
            if (buf[i] != test[i]) {
                return BoundaryTest.NONE;
            }
        }
        if (buf[i] == '\r' && buf[i + 1] == '\n') {
            return BoundaryTest.BOUNDARY;
        } else if (buf[i] == '-' && buf[i + 1] == '-' &&
            ((i + 2 == len) || (len > i + 3 && buf[i + 2] == '\r' && buf[i + 3] == '\n'))) {
            return BoundaryTest.FINAL_BOUNDARY;
        } else {
            return BoundaryTest.NONE;
        }
    }

    /**
     * Returns the parts in this multipart.
     */
    public List<Part> getParts() {
        return parts;
    }

}
