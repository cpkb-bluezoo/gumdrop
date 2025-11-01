/*
 * MimePart.java
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

package org.bluezoo.gumdrop.servlet;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.bluezoo.gumdrop.util.Part;
import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.ContentDisposition;
import javax.mail.internet.InternetHeaders;

/**
 * Simple wrapper around a org.bluezoo.gumdrop.util.Part to provide
 * javax.servlet.http.Part
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MimePart implements javax.servlet.http.Part {

    private final Request request;
    private final Part part;
    private File file;

    MimePart(Request request, Part part) {
        this.request = request;
        this.part = part;
    }

    @Override public InputStream getInputStream() throws IOException {
        return part.getInputStream();
    }

    @Override public String getContentType() {
        try {
            return part.getContentType().toString();
        } catch (MessagingException e) {
            RuntimeException e2 = new RuntimeException(Context.L10N.getString("err.body_part_headers"));
            e2.initCause(e);
            throw e2;
        }
    }

    @Override public String getName() {
        try {
            ContentDisposition contentDisposition = part.getContentDisposition();
            return (contentDisposition == null) ? null : contentDisposition.getParameter("name");
        } catch (MessagingException e) {
            RuntimeException e2 = new RuntimeException(Context.L10N.getString("err.body_part_headers"));
            e2.initCause(e);
            throw e2;
        }
    }

    @Override public String getSubmittedFileName() {
        try {
            return part.getFileName();
        } catch (MessagingException e) {
            RuntimeException e2 = new RuntimeException(Context.L10N.getString("err.body_part_headers"));
            e2.initCause(e);
            throw e2;
        }
    }

    @Override public long getSize() {
        return part.getContentLength();
    }

    @Override public void write(String fileName) throws IOException {
        ServletDef servletDef = request.match.servletDef;
        MultipartConfigDef multipartConfig = servletDef.multipartConfig;
        String multipartConfigLocation = multipartConfig.location;
        if (multipartConfigLocation == null) {
            throw new FileNotFoundException(Context.L10N.getString("err.no_multipart_config_location"));
        }
        // Sanitize filename to avoid security exploits
        String n = fileName;
        n = n.replaceAll("..", "");
        n = n.replaceAll("\\", "/");
        n = n.replaceAll("//", "/");
        if (n.startsWith("/")) {
            n = n.substring(1);
        }
        if (!n.equals(fileName)) {
            // Just throw a generic exception
            throw new IOException(Context.L10N.getString("err.bad_part_location"));
        }
        File dir = new File(multipartConfigLocation);
        file = new File(dir, n);
        dir = file.getParentFile();
        /* if (!dir.mkdirs()) {
            throw new IOException(Context.L10N.getString("err.bad_part_location"));
        }*/
        try (OutputStream out = new FileOutputStream(file); InputStream in = getInputStream()) {
            byte[] buf = new byte[Math.max(4096, in.available())];
            for (int len = in.read(buf); len != -1; len = in.read(buf)) {
                out.write(buf, 0, len);
            }
        }
    }

    @Override public void delete() throws IOException {
        if (file != null) {
            file.delete();
        }
    }

    @Override public String getHeader(String name) {
        String[] headers = part.getHeader(name);
        if (headers == null) {
            return null;
        }
        return headers[0];
    }

    @Override public Collection<String> getHeaders(String name) {
        String[] headers = part.getHeader(name);
        if (headers == null) {
            return Collections.emptyList();
        } else {
            return Collections.unmodifiableList(Arrays.asList(headers));
        }
    }

    @Override public Collection<String> getHeaderNames() {
        InternetHeaders headers = part.getHeaders();
        if (headers == null) {
            return Collections.emptyList();
        }
        Enumeration headersEnumeration = headers.getAllHeaders();
        if (headersEnumeration == null) {
            return Collections.emptyList();
        }
        List<String> acc = new ArrayList<>();
        while (headersEnumeration.hasMoreElements()) {
            Header header = (Header) headersEnumeration.nextElement();
            acc.add(header.getName());
        }
        return acc;
    }

}

