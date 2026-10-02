/*
 * MimePart.java
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

package org.bluezoo.gumdrop.servlet;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentDispositionParser;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.ContentTypeParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Part;

/**
 * Implementation of {@link jakarta.servlet.http.Part} for multipart/form-data handling.
 * Stores part headers and body content, using memory for small parts and
 * temporary files for parts exceeding the configured threshold.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MimePart implements Part {

	private final MultipartConfigDef config;
	private final Map<String, List<String>> headers;
	private ContentType contentType;
	private ContentDisposition contentDisposition;

	// Content storage - either bytes (small) or file (large)
	private byte[] bytes;
	private Path tempFile;
	private long size;

	/**
	 * Creates a new MimePart with the given configuration.
	 * Use {@link #getOutputStream()} to write content.
	 */
	MimePart(MultipartConfigDef config) {
		this.config = config;
		this.headers = new LinkedHashMap<>();
	}

	/**
	 * Adds a header to this part.
	 */
	void addHeader(String name, String value) {
		String key = name.toLowerCase();
		List<String> values = headers.get(key);
		if (values == null) {
			values = new ArrayList<>();
			headers.put(key, values);
		}
		values.add(value);
	}

	/**
	 * Returns an output stream for writing the part body.
	 * Content will be stored in memory until it exceeds the threshold,
	 * then switched to a temporary file.
	 */
	OutputStream getOutputStream() throws IOException {
		return new ContentSink();
	}

	/**
	 * Called when writing is complete to finalize storage.
	 */
	void finishWriting(byte[] data, Path file, long length) {
		this.bytes = data;
		this.tempFile = file;
		this.size = length;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		if (tempFile != null) {
			return Files.newInputStream(tempFile);
		}
		return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
	}

	@Override
	public String getContentType() {
		if (contentType == null) {
			String value = getHeader("Content-Type");
			if (value != null) {
				contentType = ContentTypeParser.parse(value);
			}
		}
		return (contentType != null) ? contentType.toHeaderValue() : null;
	}

	@Override
	public String getName() {
		ContentDisposition cd = getContentDispositionParsed();
		return (cd != null) ? cd.getParameter("name") : null;
	}

	@Override
	public String getSubmittedFileName() {
		String filename = null;
		ContentDisposition cd = getContentDispositionParsed();
		if (cd != null) {
			// RFC 5987: filename* takes precedence over filename when present
			filename = cd.getParameter("filename*");
			if (filename == null) {
				filename = cd.getParameter("filename");
			}
		}
		if (filename == null) {
			// Fallback to Content-Type name parameter
			if (contentType == null) {
				String value = getHeader("Content-Type");
				if (value != null) {
					contentType = ContentTypeParser.parse(value);
				}
			}
			filename = (contentType != null) ? contentType.getParameter("name") : null;
		}
		return sanitizeFileName(filename);
	}

	private ContentDisposition getContentDispositionParsed() {
		if (contentDisposition == null) {
			String value = getHeader("Content-Disposition");
			if (value != null) {
				contentDisposition = ContentDispositionParser.parse(value);
			}
		}
		return contentDisposition;
	}

	@Override
	public long getSize() {
		return size;
	}

	@Override
	public void write(String fileName) throws IOException {
		String location = config.location;
		if (location == null) {
			throw new FileNotFoundException(Context.L10N.getString("err.no_multipart_config_location"));
		}
		// Sanitize filename
		String n = sanitizeFileName(fileName);
		if (n == null) {
			throw new IOException(Context.L10N.getString("err.bad_part_location"));
		}
		Path dir = locationDir();
		Path dest = dir.resolve(n);
		Path realDir = canonical(dir);
		Path realDest = canonical(dest);
		if (realDest.equals(realDir) || !realDest.startsWith(realDir)) {
			throw new IOException(Context.L10N.getString("err.bad_part_location"));
		}
		try (OutputStream out = Files.newOutputStream(dest);
			 InputStream in = getInputStream()) {
			byte[] buf = new byte[8192];
			int len;
			while ((len = in.read(buf)) != -1) {
				out.write(buf, 0, len);
			}
		}
	}

	/**
	 * Returns the configured upload directory.
	 */
	private Path locationDir() {
		if (config.locationPath != null) {
			return config.locationPath;
		}
		return Paths.get(config.location);
	}

	/**
	 * Resolves symbolic links in the longest existing prefix of the path
	 * and appends the remaining (not yet existing) names, normalised
	 * (equivalent to File.getCanonicalPath). A path that does not exist yet
	 * must be resolved through its existing parent, otherwise a directory
	 * reached through a symbolic link (for example a temporary directory
	 * under a symlinked /var) would compare unequal to its resolved parent.
	 */
	private static Path canonical(Path p) throws IOException {
		Path abs = p.toAbsolutePath().normalize();
		if (Files.exists(abs)) {
			return abs.toRealPath();
		}
		Path parent = abs.getParent();
		if (parent == null) {
			return abs;
		}
		Path name = abs.getFileName();
		return canonical(parent).resolve(name.toString());
	}

	private String sanitizeFileName(String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			return null;
		}
		// Normalize separators then take only the final path component so
		// that encoded traversal sequences (%2e%2e, backslashes, etc.) cannot
		// escape the upload directory.
		String n = fileName.replace('\\', '/');
		int slash = n.lastIndexOf('/');
		if (slash >= 0) {
			n = n.substring(slash + 1);
		}
		if (n.isEmpty() || n.equals(".") || n.equals("..")) {
			return null;
		}
		return n;
	}

	@Override
	public void delete() throws IOException {
		if (tempFile != null) {
			Files.deleteIfExists(tempFile);
			tempFile = null;
		}
		bytes = null;
	}

	@Override
	public String getHeader(String name) {
		List<String> values = headers.get(name.toLowerCase());
		return (values != null && !values.isEmpty()) ? values.get(0) : null;
	}

	@Override
	public Collection<String> getHeaders(String name) {
		List<String> values = headers.get(name.toLowerCase());
		return (values != null) ? Collections.unmodifiableList(values) : Collections.emptyList();
	}

	@Override
	public Collection<String> getHeaderNames() {
		return Collections.unmodifiableSet(headers.keySet());
	}

	/**
	 * Output stream that stores content in memory until threshold,
	 * then switches to a temporary file.
	 */
	private class ContentSink extends OutputStream {

		private ByteArrayOutputStream memoryBuffer;
		private OutputStream fileOut;
		private long length;

		ContentSink() {
			this.memoryBuffer = new ByteArrayOutputStream();
		}

		@Override
		public void write(int b) throws IOException {
			length++;
			checkThreshold();
			checkMaxSize();
			if (memoryBuffer != null) {
				memoryBuffer.write(b);
			} else {
				fileOut.write(b);
			}
		}

		@Override
		public void write(byte[] buf, int off, int len) throws IOException {
			length += len;
			checkThreshold();
			checkMaxSize();
			if (memoryBuffer != null) {
				memoryBuffer.write(buf, off, len);
			} else {
				fileOut.write(buf, off, len);
			}
		}

		private void checkThreshold() throws IOException {
			if (memoryBuffer != null && length > config.fileSizeThreshold) {
				switchToFile();
			}
		}

		private void checkMaxSize() throws IOException {
			if (config.maxFileSize >= 0L && length > config.maxFileSize) {
				throw new IOException(Context.L10N.getString("err.max_file_size_exceeded"));
			}
		}

		private void switchToFile() throws IOException {
			Path dir = locationDir();
			tempFile = Files.createTempFile(dir, "upload_", ".tmp");
			fileOut = Files.newOutputStream(tempFile);
			fileOut.write(memoryBuffer.toByteArray());
			memoryBuffer = null;
		}

		@Override
		public void flush() throws IOException {
			if (fileOut != null) {
				fileOut.flush();
			}
		}

		@Override
		public void close() throws IOException {
			if (fileOut != null) {
				fileOut.close();
				finishWriting(null, tempFile, length);
			} else {
				finishWriting(memoryBuffer.toByteArray(), null, length);
			}
		}
	}
}
