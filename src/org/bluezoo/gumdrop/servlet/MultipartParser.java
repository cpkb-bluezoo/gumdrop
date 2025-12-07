/*
 * MultipartParser.java
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
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MIMEHandler;
import org.bluezoo.gumdrop.mime.MIMELocator;
import org.bluezoo.gumdrop.mime.MIMEParseException;
import org.bluezoo.gumdrop.mime.MIMEParser;
import org.bluezoo.gumdrop.mime.MIMEVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.Part;

/**
 * Parses multipart/form-data request bodies using {@link MIMEParser}.
 * Produces a collection of {@link MimePart} instances.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MultipartParser {

	private final MultipartConfigDef config;
	private final String boundary;

	/**
	 * Creates a parser for the given boundary.
	 * @param config the multipart configuration
	 * @param boundary the multipart boundary from Content-Type header
	 */
	MultipartParser(MultipartConfigDef config, String boundary) {
		this.config = config;
		this.boundary = boundary;
	}

	/**
	 * Parses the multipart body from the input stream.
	 * @param inputStream the request body
	 * @return collection of parsed parts
	 * @throws IOException if parsing fails
	 */
	Collection<Part> parse(InputStream inputStream) throws IOException {
		PartsHandler handler = new PartsHandler();
		MIMEParser parser = new MIMEParser();
		parser.setHandler(handler);

		try {
			// Inject a synthetic Content-Type header to tell the parser about the boundary
			String header = "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n\r\n";
			parser.receive(ByteBuffer.wrap(header.getBytes(StandardCharsets.US_ASCII)));

			// Feed the request body
			byte[] buf = new byte[8192];
			int len;
			while ((len = inputStream.read(buf)) != -1) {
				parser.receive(ByteBuffer.wrap(buf, 0, len));
			}
			parser.close();
		} catch (MIMEParseException e) {
			throw new IOException("Failed to parse multipart: " + e.getMessage(), e);
		}

		if (!handler.isComplete()) {
			throw new IOException(Context.L10N.getString("err.no_final_boundary"));
		}

		return handler.getParts();
	}

	/**
	 * Handler that collects MimePart instances from parser events.
	 */
	private class PartsHandler implements MIMEHandler {

		private final List<Part> parts = new ArrayList<>();
		private int depth = 0;
		private boolean complete = false;

		// Current part being built
		private MimePart currentPart;
		private OutputStream currentOutput;

		@Override
		public void setLocator(MIMELocator locator) {
			// Not needed
		}

		@Override
		public void startEntity(String boundary) throws MIMEParseException {
			depth++;
			if (depth == 1) {
				// Root multipart entity - nothing to create
				return;
			}
			// New body part
			currentPart = new MimePart(config);
			try {
				currentOutput = currentPart.getOutputStream();
			} catch (IOException e) {
				throw new MIMEParseException("Failed to create part output", e);
			}
		}

		@Override
		public void contentType(ContentType contentType) throws MIMEParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-Type", contentType.toHeaderValue());
			}
		}

		@Override
		public void contentDisposition(ContentDisposition contentDisposition) throws MIMEParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-Disposition", contentDisposition.toHeaderValue());
			}
		}

		@Override
		public void contentTransferEncoding(String encoding) throws MIMEParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-Transfer-Encoding", encoding);
			}
		}

		@Override
		public void contentID(ContentID contentID) throws MIMEParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-ID", "<" + contentID.getLocalPart() + "@" + contentID.getDomain() + ">");
			}
		}

		@Override
		public void contentDescription(String description) throws MIMEParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-Description", description);
			}
		}

		@Override
		public void mimeVersion(MIMEVersion version) throws MIMEParseException {
			// Ignore for form-data parts
		}

		@Override
		public void endHeaders() throws MIMEParseException {
			// Headers complete - ready for body content
		}

		@Override
		public void bodyContent(ByteBuffer data) throws MIMEParseException {
			if (depth > 1 && currentOutput != null) {
				try {
					byte[] bytes = new byte[data.remaining()];
					data.get(bytes);
					currentOutput.write(bytes);
				} catch (IOException e) {
					throw new MIMEParseException("Failed to write part content", e);
				}
			}
		}

		@Override
		public void unexpectedContent(ByteBuffer data) throws MIMEParseException {
			// Preamble/epilogue - ignore for form-data
		}

		@Override
		public void endEntity(String boundary) throws MIMEParseException {
			if (depth == 1) {
				// Root entity complete
				complete = true;
			} else if (depth > 1 && currentPart != null) {
				// Body part complete
				try {
					if (currentOutput != null) {
						currentOutput.close();
					}
					parts.add(currentPart);
				} catch (IOException e) {
					throw new MIMEParseException("Failed to finalize part", e);
				}
				currentPart = null;
				currentOutput = null;
			}
			depth--;
		}

		boolean isComplete() {
			return complete;
		}

		List<Part> getParts() {
			return parts;
		}
	}
    
}

