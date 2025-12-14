/*
 * package-info.java
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

/**
 * ASN.1 BER (Basic Encoding Rules) codec for LDAP protocol encoding.
 *
 * <p>This package provides the low-level ASN.1 encoding and decoding
 * required by the LDAP protocol. LDAP messages are encoded using BER
 * (Basic Encoding Rules) as defined in ITU-T X.690.
 *
 * <h2>Core Components</h2>
 *
 * <ul>
 *   <li>{@link org.bluezoo.gumdrop.ldap.asn1.BERDecoder} - Decodes BER-encoded data from ByteBuffers</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.asn1.BEREncoder} - Encodes data to BER format</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.asn1.ASN1Type} - ASN.1 universal type tags</li>
 *   <li>{@link org.bluezoo.gumdrop.ldap.asn1.ASN1Element} - Decoded ASN.1 element representation</li>
 * </ul>
 *
 * <h2>BER Encoding Overview</h2>
 *
 * <p>BER uses a TLV (Type-Length-Value) structure:</p>
 *
 * <pre>
 * +-------+--------+-------+
 * | Tag   | Length | Value |
 * +-------+--------+-------+
 * </pre>
 *
 * <h3>Tag Byte</h3>
 * <p>The tag byte encodes:</p>
 * <ul>
 *   <li>Bits 7-6: Class (Universal, Application, Context-specific, Private)</li>
 *   <li>Bit 5: Primitive (0) or Constructed (1)</li>
 *   <li>Bits 4-0: Tag number (0-30, or 31 for multi-byte tags)</li>
 * </ul>
 *
 * <h3>Length Encoding</h3>
 * <ul>
 *   <li>Short form: Single byte for lengths 0-127</li>
 *   <li>Long form: First byte indicates number of length bytes</li>
 *   <li>Indefinite: 0x80 followed by contents and end-of-contents marker</li>
 * </ul>
 *
 * <h2>ASN.1 Universal Types Used by LDAP</h2>
 *
 * <table border="1" cellpadding="5">
 *   <caption>Common ASN.1 Types in LDAP</caption>
 *   <tr><th>Type</th><th>Tag</th><th>LDAP Usage</th></tr>
 *   <tr><td>BOOLEAN</td><td>0x01</td><td>Filter assertions, controls</td></tr>
 *   <tr><td>INTEGER</td><td>0x02</td><td>Message IDs, result codes, enumerations</td></tr>
 *   <tr><td>OCTET STRING</td><td>0x04</td><td>DNs, attribute values, passwords</td></tr>
 *   <tr><td>NULL</td><td>0x05</td><td>Empty values</td></tr>
 *   <tr><td>ENUMERATED</td><td>0x0A</td><td>Scope, deref aliases, result codes</td></tr>
 *   <tr><td>SEQUENCE</td><td>0x30</td><td>Messages, entries, attribute lists</td></tr>
 *   <tr><td>SET</td><td>0x31</td><td>Attribute value sets</td></tr>
 * </table>
 *
 * <h2>LDAP Context-Specific Tags</h2>
 *
 * <p>LDAP uses context-specific tags (class bits = 10) extensively
 * for protocol operations:</p>
 *
 * <table border="1" cellpadding="5">
 *   <caption>LDAP Protocol Tags</caption>
 *   <tr><th>Operation</th><th>Tag</th><th>Description</th></tr>
 *   <tr><td>BindRequest</td><td>0x60</td><td>Authentication request</td></tr>
 *   <tr><td>BindResponse</td><td>0x61</td><td>Authentication response</td></tr>
 *   <tr><td>UnbindRequest</td><td>0x42</td><td>Disconnect</td></tr>
 *   <tr><td>SearchRequest</td><td>0x63</td><td>Search operation</td></tr>
 *   <tr><td>SearchResultEntry</td><td>0x64</td><td>Search result entry</td></tr>
 *   <tr><td>SearchResultDone</td><td>0x65</td><td>Search complete</td></tr>
 *   <tr><td>ModifyRequest</td><td>0x66</td><td>Modify entry</td></tr>
 *   <tr><td>ModifyResponse</td><td>0x67</td><td>Modify result</td></tr>
 *   <tr><td>AddRequest</td><td>0x68</td><td>Add entry</td></tr>
 *   <tr><td>AddResponse</td><td>0x69</td><td>Add result</td></tr>
 *   <tr><td>DelRequest</td><td>0x4A</td><td>Delete entry</td></tr>
 *   <tr><td>DelResponse</td><td>0x6B</td><td>Delete result</td></tr>
 * </table>
 *
 * <h2>Streaming Decoder</h2>
 *
 * <p>The {@link org.bluezoo.gumdrop.ldap.asn1.BERDecoder} is designed for
 * streaming use with non-blocking I/O. It can handle partial reads and
 * will return when more data is needed:</p>
 *
 * <pre>{@code
 * BERDecoder decoder = new BERDecoder();
 *
 * // In receive callback
 * decoder.receive(buffer);
 *
 * ASN1Element element;
 * while ((element = decoder.next()) != null) {
 *     // Process complete element
 *     processLDAPMessage(element);
 * }
 * }</pre>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.ldap.client
 * @see <a href="https://www.itu.int/rec/T-REC-X.690">ITU-T X.690 - ASN.1 Encoding Rules</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc4511">RFC 4511 - LDAP Protocol</a>
 */
package org.bluezoo.gumdrop.ldap.asn1;


