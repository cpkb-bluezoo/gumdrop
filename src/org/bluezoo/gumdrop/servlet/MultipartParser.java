/*
 * MultipartParser.java
 * Copyright (C) 2025, 2026 Chris Burdess
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

import org.bluezoo.gumdrop.mime.HeaderValueBuilder;
import org.bluezoo.gumdrop.mime.MimeHandler;
import org.bluezoo.gumdrop.mime.MimeLocator;
import org.bluezoo.gumdrop.mime.MimeParseException;
import org.bluezoo.gumdrop.mime.MimeParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.http.Part;

/**
 * Parses multipart/form-data request bodies using {@link MimeParser}.
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
		MimeParser parser = new MimeParser();
		parser.setHandler(handler);

		try {
			// Inject a synthetic Content-Type header to tell the parser about the boundary
			String header = "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n\r\n";
			parser.parse(new SequenceInputStream(
				new ByteArrayInputStream(header.getBytes(StandardCharsets.US_ASCII)), inputStream));
		} catch (MimeParseException e) {
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
	private class PartsHandler implements MimeHandler {

		private final List<Part> parts = new ArrayList<>();
		private int depth = 0;
		private boolean complete = false;

		// Current part being built
		private MimePart currentPart;
		private OutputStream currentOutput;
		private final HeaderValueBuilder headerValue = new HeaderValueBuilder();
		private String headerName; // the structured header being built, or null

		@Override
		public boolean wantsLegacyEvents() {
			return false;
		}

		@Override
		public void setLocator(MimeLocator locator) {
			// Not needed
		}

		@Override
		public void startEntity(String boundary) throws MimeParseException {
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
				throw new MimeParseException("Failed to create part output", e);
			}
		}

		@Override
		public void startContentType(String primary, String sub) throws MimeParseException {
			headerName = "Content-Type";
			headerValue.start(primary + "/" + sub);
		}

		@Override
		public void endContentType() throws MimeParseException {
			endStructured();
		}

		@Override
		public void startContentDisposition(String kind) throws MimeParseException {
			headerName = "Content-Disposition";
			headerValue.start(kind);
		}

		@Override
		public void endContentDisposition() throws MimeParseException {
			endStructured();
		}

		@Override
		public void startParameter(String name, String language) throws MimeParseException {
			headerValue.startParameter(name);
		}

		@Override
		public void parameterContent(String text) throws MimeParseException {
			headerValue.parameterContent(text);
		}

		@Override
		public void endParameter() throws MimeParseException {
			headerValue.endParameter();
		}

		private void endStructured() {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader(headerName, headerValue.value());
			}
			headerName = null;
		}

		@Override
		public void contentTransferEncoding(String encoding) throws MimeParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-Transfer-Encoding", encoding);
			}
		}

		@Override
		public void contentID(String localPart, String domain) throws MimeParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-ID", "<" + localPart + "@" + domain + ">");
			}
		}

		@Override
		public void contentDescription(String description) throws MimeParseException {
			if (depth > 1 && currentPart != null) {
				currentPart.addHeader("Content-Description", description);
			}
		}

		@Override
		public void endHeaders() throws MimeParseException {
			// Headers complete - ready for body content
		}

		@Override
		public void bodyContent(ByteBuffer data) throws MimeParseException {
			if (depth > 1 && currentOutput != null) {
				try {
					byte[] bytes = new byte[data.remaining()];
					data.get(bytes);
					currentOutput.write(bytes);
				} catch (IOException e) {
					throw new MimeParseException("Failed to write part content", e);
				}
			}
		}

		@Override
		public void unexpectedContent(ByteBuffer data) throws MimeParseException {
			// Preamble/epilogue - ignore for form-data
		}

		@Override
		public void endEntity(String boundary) throws MimeParseException {
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
					throw new MimeParseException("Failed to finalize part", e);
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

