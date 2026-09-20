/*
 * MemoryUserAttributeView.java
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

package org.bluezoo.gumdrop.testsupport.memfs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code user} extended attributes on a {@link MemoryNode}. A value larger
 * than {@link MemoryFileSystem#setMaxXattrValueSize} is refused as a real file
 * system refuses one that does not fit.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MemoryUserAttributeView implements UserDefinedFileAttributeView {

    private final MemoryFileSystem fs;
    private final MemoryPath path;
    private final boolean follow;

    MemoryUserAttributeView(MemoryFileSystem fs, MemoryPath path, boolean follow) {
        this.fs = fs;
        this.path = path;
        this.follow = follow;
    }

    @Override
    public String name() {
        return "user";
    }

    @Override
    public List<String> list() throws IOException {
        synchronized (fs.lock) {
            return new ArrayList<String>(fs.provider.require(path, follow).xattrs.keySet());
        }
    }

    private byte[] value(MemoryNode node, String name) throws IOException {
        byte[] value = node.xattrs.get(name);
        if (value == null) {
            throw new FileSystemException(path.toString(), null, "No data available");
        }
        return value;
    }

    @Override
    public int size(String name) throws IOException {
        synchronized (fs.lock) {
            return value(fs.provider.require(path, follow), name).length;
        }
    }

    @Override
    public int read(String name, ByteBuffer dst) throws IOException {
        synchronized (fs.lock) {
            byte[] value = value(fs.provider.require(path, follow), name);
            if (dst.remaining() < value.length) {
                throw new FileSystemException(path.toString(), null,
                        "Insufficient space in the destination buffer");
            }
            dst.put(value);
            return value.length;
        }
    }

    @Override
    public int write(String name, ByteBuffer src) throws IOException {
        synchronized (fs.lock) {
            MemoryNode node = fs.provider.require(path, follow);
            byte[] value = new byte[src.remaining()];
            src.get(value);
            if (value.length > fs.maxXattrValueSize()) {
                throw new FileSystemException(path.toString(), null,
                        "No space left on device");
            }
            node.xattrs.put(name, value);
            return value.length;
        }
    }

    @Override
    public void delete(String name) throws IOException {
        synchronized (fs.lock) {
            MemoryNode node = fs.provider.require(path, follow);
            value(node, name);
            node.xattrs.remove(name);
        }
    }
}
