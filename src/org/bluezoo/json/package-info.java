/*
 * package-info.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jsonparser, a JSON parsing library for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jsonparser/
 *
 * jsonparser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * jsonparser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jsonparser.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * A compact, efficient JSON parsing and serialization library for Java.
 * 
 * <h2>Overview</h2>
 * <p>
 * This library provides fast, standards-compliant JSON parsing and writing
 * with minimal memory overhead (26KB). Unlike object-mapping libraries such
 * as GSON (290KB) or Jackson (600KB), this library uses an event-driven,
 * streaming approach that enables:
 * </p>
 * <ul>
 *   <li><b>Non-blocking I/O</b> - Parse JSON as bytes arrive without blocking</li>
 *   <li><b>Low memory footprint</b> - Process large documents without loading them entirely into memory</li>
 *   <li><b>High performance</b> - Optimized tokenization with direct buffer parsing</li>
 *   <li><b>Standards compliance</b> - Fully conformant with RFC 8259 and ECMA-404</li>
 * </ul>
 * 
 * <h2>JSON Parsing</h2>
 * <p>
 * The parser follows an event-driven design similar to SAX for XML. The
 * {@link org.bluezoo.json.JSONParser} is the main entry point and supports
 * two usage models:
 * </p>
 * 
 * <h3>Asynchronous Streaming (Primary)</h3>
 * <p>
 * The parser is designed for non-blocking, data-driven architectures where
 * JSON arrives incrementally (e.g., from network sockets, async file I/O,
 * or message queues).
 * </p>
 * <pre><code>
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(new MyHandler());
 * 
 * // Feed bytes as they arrive
 * parser.receive(chunk1);
 * parser.receive(chunk2);
 * parser.receive(chunk3);
 * 
 * // Signal completion
 * parser.close();
 * </code></pre>
 * 
 * <h3>Blocking (Convenience)</h3>
 * <p>
 * For simpler use cases, a blocking {@code parse(InputStream)} method is
 * provided that internally delegates to the streaming API.
 * </p>
 * <pre><code>
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(new MyHandler());
 * parser.parse(inputStream);
 * </code></pre>
 * 
 * <h3>Content Handlers</h3>
 * <p>
 * Applications implement {@link org.bluezoo.json.JSONContentHandler} to
 * receive parsing events. The {@link org.bluezoo.json.JSONDefaultHandler}
 * provides a convenient base class with no-op implementations of all methods.
 * </p>
 * <pre><code>
 * public class MyHandler extends JSONDefaultHandler {
 *     &#64;Override
 *     public void key(String key) {
 *         System.out.println("Key: " + key);
 *     }
 *     
 *     &#64;Override
 *     public void stringValue(String value) {
 *         System.out.println("String: " + value);
 *     }
 * }
 * </code></pre>
 * 
 * <h2>JSON Writing</h2>
 * <p>
 * The {@link org.bluezoo.json.JSONWriter} provides an NIO-first streaming
 * API for generating JSON output. It writes directly to a
 * {@link java.nio.channels.WritableByteChannel} or {@link java.io.OutputStream}
 * with automatic buffering and optional pretty-printing.
 * </p>
 * <pre><code>
 * JSONWriter writer = new JSONWriter(outputStream, IndentConfig.spaces(2));
 * 
 * writer.writeStartObject();
 *   writer.writeKey("name");
 *   writer.writeString("Alice");
 *   writer.writeKey("age");
 *   writer.writeNumber(30);
 * writer.writeEndObject();
 * 
 * writer.close();
 * </code></pre>
 * 
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><b>Streaming tokens</b> - Tokens are emitted as soon as recognized,
 *       enabling near-zero latency</li>
 *   <li><b>Direct parsing</b> - Numbers and strings are parsed directly from
 *       buffers without intermediate allocations</li>
 *   <li><b>Configurable buffering</b> - Buffer sizes can be tuned for different
 *       workloads (default 8KB)</li>
 *   <li><b>Optional whitespace</b> - Handlers can opt out of whitespace events
 *       to avoid string extraction overhead</li>
 *   <li><b>BOM detection</b> - Automatic UTF-8 BOM detection with fast path
 *       optimization</li>
 * </ul>
 * 
 * <h2>Standards Compliance</h2>
 * <p>
 * This library is fully conformant with the JSON specification as defined by
 * RFC 8259 and ECMA-404. It has been validated against the comprehensive
 * <a href="https://github.com/nst/JSONTestSuite">JSONTestSuite</a> test corpus.
 * </p>
 * 
 * <h2>Thread Safety</h2>
 * <p>
 * Parser and writer instances are <b>not thread-safe</b>. Each thread should
 * use its own instance. The parser can be reused for multiple documents by
 * calling {@link org.bluezoo.json.JSONParser#reset()} between documents.
 * </p>
 * 
 * <h2>Error Handling</h2>
 * <p>
 * All parsing and writing errors are reported via
 * {@link org.bluezoo.json.JSONException}, a checked exception that provides
 * detailed error messages including line and column numbers (when available
 * via {@link org.bluezoo.json.JSONLocator}).
 * </p>
 * 
 * <h2>Main Classes</h2>
 * <dl>
 *   <dt>{@link org.bluezoo.json.JSONParser}</dt>
 *   <dd>Streaming JSON parser with async-first design</dd>
 *   
 *   <dt>{@link org.bluezoo.json.JSONWriter}</dt>
 *   <dd>NIO-based JSON writer with optional indentation</dd>
 *   
 *   <dt>{@link org.bluezoo.json.JSONContentHandler}</dt>
 *   <dd>Callback interface for receiving parsing events</dd>
 *   
 *   <dt>{@link org.bluezoo.json.JSONDefaultHandler}</dt>
 *   <dd>Convenient base class for content handlers</dd>
 *   
 *   <dt>{@link org.bluezoo.json.JSONException}</dt>
 *   <dd>Exception thrown for parsing and writing errors</dd>
 *   
 *   <dt>{@link org.bluezoo.json.IndentConfig}</dt>
 *   <dd>Configuration for JSON pretty-printing</dd>
 * </dl>
 * 
 * @author <a href="mailto:dog@gnu.org">Chris Burdess</a>
 * @see <a href="https://tools.ietf.org/html/rfc8259">RFC 8259 - The JavaScript Object Notation (JSON) Data Interchange Format</a>
 * @see <a href="https://www.ecma-international.org/publications-and-standards/standards/ecma-404/">ECMA-404 - The JSON Data Interchange Syntax</a>
 */
package org.bluezoo.json;

