/*
 * XMLParseUtils.java
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

package org.bluezoo.gumdrop.util;

import org.bluezoo.gonzalez.Parser;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotSupportedException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * Utility methods for parsing XML using the Gonzalez streaming parser,
 * via its native {@code XMLHandler} vocabulary ({@link AbstractXMLHandler})
 * rather than the heavier SAX {@code ContentHandler} adaptation.
 *
 * <p>These methods provide non-blocking, NIO-based XML parsing using the
 * Gonzalez push-model parser. For file-based parsing, NIO FileChannel is
 * used. For InputStream-based parsing (e.g., resources from JAR files),
 * the stream is wrapped in a ReadableByteChannel.
 *
 * <p>Parser instances are reused via a thread-local cache to avoid the
 * overhead of creating new parsers for each document. The parser's
 * {@code reset()} method is called between documents to clear internal state.
 *
 * <h3>Entity Resolution</h3>
 *
 * <p>When using the non-blocking {@code receive()} interface, the systemId
 * and publicId must be set <em>before</em> the first {@code receive()} call.
 * This is necessary for documents that are not standalone and need to resolve
 * external entities (e.g., DTD references). The systemId provides the base URI
 * for relative entity resolution, while the publicId can be used for catalog-
 * based resolution.
 *
 * <h3>Error reporting</h3>
 *
 * <p>There is no separate {@code ErrorHandler} parameter: a handler
 * reports its own errors by overriding {@link AbstractXMLHandler#error}/
 * {@link AbstractXMLHandler#fatalError} directly, the same object that
 * receives the structural events.
 *
 * <p>Example usage:
 * <pre>
 * XMLParseUtils.parseFile(configFile, myHandler, null, null);
 * XMLParseUtils.parseStream(inputStream, myHandler, "config.xml", null);
 * </pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class XMLParseUtils {

    /** Default buffer size for reading XML data. */
    private static final int BUFFER_SIZE = 8192;

    /**
     * Thread-local parser instance for reuse.
     * Parser is not thread-safe, so each thread gets its own instance.
     */
    private static final ThreadLocal<Parser> PARSER_CACHE = new ThreadLocal<Parser>() {
        @Override
        protected Parser initialValue() {
            return new Parser();
        }
    };

    /**
     * EntityResolver that blocks all external entity resolution to
     * prevent XXE attacks. Returns an empty InputSource for any
     * external entity request.
     *
     * <p>This is exposed for reuse by callers that construct their own
     * {@link Parser} instances (e.g. the WebDAV request parsers) so they can
     * apply the same protection via {@link Parser#setEntityResolver}.
     */
    public static final EntityResolver DENY_EXTERNAL_ENTITIES =
            new EntityResolver() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new StringReader(""));
                }
            };

    private XMLParseUtils() {
        // Utility class
    }

    /**
     * Gets a parser instance for the current thread, reset and ready for use.
     * The parser is retrieved from a thread-local cache and reset to clear
     * any state from previous parsing operations.
     *
     * @return a reset Parser instance
     * @throws SAXException if reset fails
     */
    private static Parser getParser() throws SAXException {
        Parser parser = PARSER_CACHE.get();
        parser.reset();
        parser.setEntityResolver(DENY_EXTERNAL_ENTITIES);
        return parser;
    }

    /**
     * Clears the handler from the parser after use to avoid holding
     * references. This helps prevent memory leaks when handlers
     * reference large objects.
     *
     * <p>Unlike {@code setContentHandler}, {@link Parser#setXMLHandler}
     * refuses to change the handler at all -- even to null -- once a
     * scanner has been built for the document just parsed (it throws
     * {@link SAXNotSupportedException}), and {@link Parser#close} does
     * not itself tear the scanner back down. {@link Parser#reset()}
     * does (that's what makes it safe for {@link #getParser()} to reuse
     * a pooled instance for the next document), so it must run first --
     * a second, harmless {@code reset()} then happens at the start of
     * the parser's next use.
     */
    private static void clearParser(Parser parser) throws SAXException {
        parser.reset();
        parser.setXMLHandler(null);
    }

    /**
     * Wires {@code handler} onto {@code parser} as its native {@code
     * XMLHandler}. {@link Parser#setXMLHandler} only declares {@link
     * SAXNotSupportedException} for the case where a document is
     * already being parsed with a different handler -- a thread-local,
     * freshly-{@link Parser#reset() reset} parser never is, so this
     * turns that impossible case into an {@link IllegalStateException}
     * rather than pushing a checked exception onto every caller here.
     */
    private static void setHandler(Parser parser, AbstractXMLHandler handler) {
        try {
            parser.setXMLHandler(handler);
        } catch (SAXNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses an XML file using NIO FileChannel.
     * This is the most efficient method for parsing local files.
     *
     * @param file the XML file to parse
     * @param handler the handler to receive events (and, via {@link
     *                AbstractXMLHandler#error}/{@link
     *                AbstractXMLHandler#fatalError}, report errors)
     * @param systemId optional system ID for error reporting and entity resolution
     *                 (uses file URI if null)
     * @param publicId optional public ID for catalog-based entity resolution (may be null)
     * @throws IOException if an I/O error occurs
     * @throws SAXException if a parsing error occurs
     */
    public static void parseFile(File file, AbstractXMLHandler handler,
                                  String systemId, String publicId)
            throws IOException, SAXException {
        Parser parser = getParser();
        boolean success = false;
        try {
            setHandler(parser, handler);

            // Set identifiers BEFORE first receive() for entity resolution
            parser.setSystemId(systemId != null ? systemId : file.toURI().toString());
            if (publicId != null) {
                parser.setPublicId(publicId);
            }

            try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                parseChannel(parser, channel);
            }
            success = true;
        } finally {
            if (success) {
                clearParser(parser);
            } else {
                PARSER_CACHE.remove();
            }
        }
    }

    /**
     * Parses an XML document from a URL.
     * Opens a stream to the URL and parses using the stream method.
     * The URL string is used as the systemId for entity resolution.
     *
     * @param url the URL to parse
     * @param handler the handler to receive events (and report errors)
     * @throws IOException if an I/O error occurs
     * @throws SAXException if a parsing error occurs
     */
    public static void parseURL(URL url, AbstractXMLHandler handler)
            throws IOException, SAXException {
        try (InputStream in = url.openStream()) {
            parseStream(in, handler, url.toString(), null);
        }
    }

    /**
     * Parses an XML document from an InputStream.
     * The stream is wrapped in a ReadableByteChannel for efficient reading.
     *
     * @param in the InputStream to parse (will NOT be closed by this method)
     * @param handler the handler to receive events (and report errors)
     * @param systemId optional system ID for error reporting and entity resolution
     * @param publicId optional public ID for catalog-based entity resolution (may be null)
     * @throws IOException if an I/O error occurs
     * @throws SAXException if a parsing error occurs
     */
    public static void parseStream(InputStream in, AbstractXMLHandler handler,
                                    String systemId, String publicId)
            throws IOException, SAXException {
        Parser parser = getParser();
        boolean success = false;
        try {
            setHandler(parser, handler);

            // Set identifiers BEFORE first receive() for entity resolution
            if (systemId != null) {
                parser.setSystemId(systemId);
            }
            if (publicId != null) {
                parser.setPublicId(publicId);
            }

            ReadableByteChannel channel = Channels.newChannel(in);
            parseChannel(parser, channel);
            success = true;
        } finally {
            if (success) {
                clearParser(parser);
            } else {
                PARSER_CACHE.remove();
            }
        }
    }

    /**
     * Parses an XML document from an InputStream while computing a digest.
     * Useful for deployment descriptor parsing where digest is needed.
     *
     * @param in the InputStream to parse (will NOT be closed by this method)
     * @param handler the handler to receive events (and report errors)
     * @param systemId optional system ID for error reporting and entity resolution
     * @param digest the MessageDigest to update with read data
     * @throws IOException if an I/O error occurs
     * @throws SAXException if a parsing error occurs
     */
    public static void parseStreamWithDigest(InputStream in, AbstractXMLHandler handler,
                                              String systemId, MessageDigest digest)
            throws IOException, SAXException {
        // Wrap in DigestInputStream to compute hash while reading
        DigestInputStream digestIn = new DigestInputStream(in, digest);
        parseStream(digestIn, handler, systemId, null);
    }

    /**
     * Internal method to parse from a ReadableByteChannel.
     */
    private static void parseChannel(Parser parser, ReadableByteChannel channel)
            throws IOException, SAXException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

        while (channel.read(buffer) != -1 || buffer.position() > 0) {
            buffer.flip();
            parser.receive(buffer);
            buffer.compact();
        }

        parser.close();
    }
}

