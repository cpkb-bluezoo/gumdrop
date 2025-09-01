/*
 * MultipartConfigContent.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.util.Content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Implementation of Content that uses a MultipartConfigDef to decide when
 * to transfer byte data to disk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MultipartConfigContent implements Content {

    private final MultipartConfigDef multipartConfig;
    private final String fileName;
    private long length;
    private File file;
    private byte[] bytes;

    MultipartConfigContent(MultipartConfigDef multipartConfig, String fileName) {
        this.multipartConfig = multipartConfig;
        this.fileName = fileName;
    }

    @Override public InputStream getInputStream() throws IOException {
        return (file != null) ? new FileInputStream(file) : new ByteArrayInputStream(bytes);
    }

    @Override public OutputStream getOutputStream() throws IOException {
        length = 0L;
        return this.new Sink();
    }

    @Override public long length() {
        return length;
    }

    @Override public Content create(String fileName) {
        return new MultipartConfigContent(multipartConfig, fileName);
    }

    class Sink extends OutputStream {

        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        FileOutputStream fileOut;

        @Override public void write(int c) throws IOException {
            length += 1L;
            if (file == null && length > multipartConfig.fileSizeThreshold) {
                useFile();
            } else if (file != null && length > multipartConfig.maxFileSize) {
                throw new IOException(Context.L10N.getString("err.max_file_size_exceeded"));
            }
            if (bytesOut != null) {
                bytesOut.write(c);
            } else {
                fileOut.write(c);
            }
        }

        @Override public void write(byte[] buf) throws IOException {
            write(buf, 0, buf.length);
        }

        @Override public void write(byte[] buf, int off, int len) throws IOException {
            length += (long) len;
            if (file == null && length > multipartConfig.fileSizeThreshold) {
                useFile();
            } else if (file != null && length > multipartConfig.maxFileSize) {
                throw new IOException(Context.L10N.getString("err.max_file_size_exceeded"));
            }
            if (bytesOut != null) {
                bytesOut.write(buf, off, len);
            } else {
                fileOut.write(buf, off, len);
            }
        }

        @Override public void flush() throws IOException {
            if (bytesOut != null) {
                bytesOut.flush();
            } else {
                fileOut.flush();
            }
        }

        @Override public void close() throws IOException {
            if (bytesOut != null) {
                bytes = bytesOut.toByteArray();
            } else {
                fileOut.close();
            }
        }

        void useFile() throws IOException {
            // convert to using file
            File dir = new File(multipartConfig.location);
            if (fileName != null) {
                file = new File(dir, fileName);
            }
            if (file == null || file.exists()) {
                file = File.createTempFile("", "", dir);
            }
            fileOut = new FileOutputStream(file);
            fileOut.write(bytesOut.toByteArray());
            bytesOut = null;
        }

    }

}
