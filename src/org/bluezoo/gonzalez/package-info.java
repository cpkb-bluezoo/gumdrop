/*
 * package-info.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of Gonzalez, a streaming XML parser.
 * For more information please visit https://www.nongnu.org/gonzalez/
 *
 * Gonzalez is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Gonzalez is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Gonzalez.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Gonzalez: A non-blocking, streaming XML parser and serializer for event-driven I/O.
 *
 * <h2>Overview</h2>
 *
 * <p>Gonzalez is a data-driven XML parser and serializer that uses a push model
 * instead of the traditional pull model used by SAX parsers. Unlike SAX parsers
 * that pull data from an {@link org.xml.sax.InputSource}, Gonzalez allows you to
 * push data to the parser as it arrives, making it ideal for integration with
 * non-blocking I/O frameworks such as Java NIO selectors, Gumdrop, Netty, or
 * other async pipelines. The {@link org.bluezoo.gonzalez.XMLWriter} provides the
 * inverse: streaming XML serialization to NIO channels.
 *
 * <h2>Key Features</h2>
 *
 * <ul>
 *   <li><b>Non-blocking:</b> Processes available data and returns control
 *       immediately; never waits for more data</li>
 *   <li><b>Streaming:</b> Handles documents of unlimited size with bounded
 *       memory usage</li>
 *   <li><b>SAX2-compatible:</b> Generates standard SAX events via
 *       {@link org.xml.sax.ContentHandler}</li>
 *   <li><b>ByteBuffer-based:</b> Uses Java NIO buffers for efficient I/O</li>
 *   <li><b>Automatic charset detection:</b> Handles BOM and XML declaration
 *       encoding</li>
 *   <li><b>Full XML 1.0/1.1 support:</b> Including DTD, namespaces, and
 *       entity expansion</li>
 *   <li><b>100% conformant</b> to W3C XML Conformance Test Suite</li>
 *   <li><b>Fast:</b> Can achieve 7× the speed of the default Java SAX parser
 *       for small documents</li>
 *   <li><b>Small:</b> only 200KB jar</li>
 *   <li><b>Zero dependency:</b> no external libraries used</li>
 * </ul>
 *
 * <h2>Streaming Usage (Non-blocking)</h2>
 *
 * <p>For non-blocking scenarios where data arrives in chunks (e.g., from a
 * network socket or other NIO channel), use the {@code receive()} and
 * {@code close()} methods.
 * This is the native and preferred method for which Gonzalez was designed.
 *
 * <pre>{@code
 * import org.bluezoo.gonzalez.Parser;
 * import java.nio.ByteBuffer;
 *
 * Parser parser = new Parser();
 * parser.setContentHandler(myContentHandler);
 *
 * // Optional: set document identifiers for error reporting
 * parser.setSystemId("http://example.com/document.xml");
 * parser.setPublicId("-//Example//DTD Example 1.0//EN");
 *
 * // Feed data as it arrives from network/channel
 * while (channel.read(buffer) > 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.clear();
 * }
 *
 * // Signal end of document
 * parser.close();
 * }</pre>
 *
 * <h2>NIO Server Integration</h2>
 *
 * <p>Gonzalez is designed to integrate seamlessly with NIO selector-based
 * servers. The parser processes whatever data is available, fires SAX events
 * for complete tokens, and returns control immediately. Incomplete tokens are
 * buffered internally until the next {@code receive()} call.
 *
 * <pre>{@code
 * // In your selector loop, when data is ready:
 * SocketChannel channel = (SocketChannel) key.channel();
 * Parser parser = (Parser) key.attachment();
 *
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * int bytesRead = channel.read(buffer);
 *
 * if (bytesRead > 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * } else if (bytesRead == -1) {
 *     parser.close();  // End of stream
 * }
 * }</pre>
 *
 * <h2>Handler Configuration</h2>
 *
 * <p>Gonzalez supports the standard SAX2 handlers:
 *
 * <ul>
 *   <li>{@link org.xml.sax.ContentHandler} - Element and character events</li>
 *   <li>{@link org.xml.sax.DTDHandler} - Notation and unparsed entity
 *       declarations</li>
 *   <li>{@link org.xml.sax.ErrorHandler} - Error and warning handling</li>
 *   <li>{@link org.xml.sax.EntityResolver} - External entity resolution</li>
 * </ul>
 *
 * <p>And SAX2 extension handlers:
 *
 * <ul>
 *   <li>{@link org.xml.sax.ext.LexicalHandler} - Comments, CDATA sections,
 *       entity boundaries</li>
 *   <li>{@link org.xml.sax.ext.DeclHandler} - DTD declaration events</li>
 *   <li>{@link org.xml.sax.ext.EntityResolver2} - Enhanced entity
 *       resolution</li>
 *   <li>{@link org.xml.sax.ext.Locator2} - Enhanced location information
 *       including encoding</li>
 * </ul>
 * 
 * <h2>SAX2 XMLReader interface</h2>
 * 
 * <p>Although designed to operate in non-blocking mode, Gonzalez additionally
 * supports a convenience {@code parse(InputSource)} method and implements SAX2
 * XMLReader for applications that cannot be adapted to an event-driven pipeline.
 * 
 * <p>Note that this will <b>block</b> the calling thread until the entire
 * InputStream is consumed.
 * 
 * <pre>{@code
 * Parser parser = new Parser();
 * parser.setContentHandler(handler);
 *
 * // Parse a legacy InputStream
 * parser.parse(new InputSource(stream));
 * }</pre>
 *
 * <h2>Parser Reuse</h2>
 *
 * <p>A single {@link org.bluezoo.gonzalez.Parser} instance can be reused for
 * multiple documents by calling {@code reset()} between parses:
 *
 * <pre>{@code
 * Parser parser = new Parser();
 * parser.setContentHandler(handler);
 *
 * // Parse first document stream
 * parser.receive(stream1chunk1);
 * parser.receive(stream1chunk2);
 * parser.receive(stream1chunk3);
 * parser.close();
 *
 * // Reset and parse another
 * parser.reset();
 * 
 * // Parse second documnent stream
 * parser.receive(stream2chunk1);
 * parser.receive(stream2chunk2);
 * parser.close();
 * }</pre>
 *
 * <h2>XML Serialization (XMLWriter)</h2>
 *
 * <p>The {@link org.bluezoo.gonzalez.XMLWriter} provides streaming XML serialization
 * to any {@link java.nio.channels.WritableByteChannel}. It uses an internal buffer
 * and automatically flushes to the channel when needed.
 *
 * <p>Key features:
 * <ul>
 *   <li><b>NIO-first:</b> Writes to WritableByteChannel with automatic buffering</li>
 *   <li><b>Namespace-aware:</b> Full support for prefixed and default namespaces</li>
 *   <li><b>Pretty-print:</b> Optional indentation via {@link org.bluezoo.gonzalez.IndentConfig}</li>
 *   <li><b>Empty element optimization:</b> Automatically emits {@code <foo/>} instead
 *       of {@code <foo></foo>} when appropriate</li>
 *   <li><b>UTF-8 output:</b> All output is UTF-8 encoded</li>
 * </ul>
 *
 * <pre>{@code
 * import org.bluezoo.gonzalez.XMLWriter;
 * import org.bluezoo.gonzalez.IndentConfig;
 *
 * // Write to a file with pretty-printing
 * try (FileOutputStream fos = new FileOutputStream("output.xml")) {
 *     XMLWriter writer = new XMLWriter(fos, IndentConfig.spaces2());
 *
 *     writer.writeStartElement("http://example.com/ns", "root");
 *     writer.writeDefaultNamespace("http://example.com/ns");
 *     writer.writeAttribute("version", "1.0");
 *
 *     writer.writeStartElement("item");
 *     writer.writeAttribute("id", "1");
 *     writer.writeCharacters("Hello, World!");
 *     writer.writeEndElement();
 *
 *     writer.writeStartElement("empty");
 *     writer.writeEndElement();  // Emits <empty/>
 *
 *     writer.writeEndElement();
 *     writer.close();
 * }
 * // Output:
 * // <root xmlns="http://example.com/ns" version="1.0">
 * //   <item id="1">Hello, World!</item>
 * //   <empty/>
 * // </root>
 * }</pre>
 *
 * <h2>Architecture</h2>
 *
 * <p>Internally, Gonzalez uses a pipeline architecture:
 *
 * <ol>
 *   <li>{@link org.bluezoo.gonzalez.ExternalEntityDecoder} - Converts bytes
 *       to characters with charset detection</li>
 *   <li>{@link org.bluezoo.gonzalez.Tokenizer} - State machine that produces
 *       XML tokens</li>
 *   <li>{@link org.bluezoo.gonzalez.ContentParser} - Converts tokens to SAX
 *       events</li>
 *   <li>{@link org.bluezoo.gonzalez.DTDParser} - Handles DTD declarations
 *       (loaded lazily)</li>
 * </ol>
 *
 * @author Chris Burdess
 * @see org.bluezoo.gonzalez.Parser
 * @see org.bluezoo.gonzalez.XMLWriter
 * @see org.bluezoo.gonzalez.IndentConfig
 * @see org.xml.sax.XMLReader
 * @see org.xml.sax.ContentHandler
 */
package org.bluezoo.gonzalez;

