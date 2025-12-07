/*
 * DNSMessage.java
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

package org.bluezoo.gumdrop.dns;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A DNS protocol message.
 *
 * <p>DNS messages are used for both queries and responses. The message
 * consists of a header followed by question, answer, authority, and
 * additional sections.
 *
 * <p>See RFC 1035 for the DNS protocol specification.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSMessage {

    // -- Header flags --

    /** Query/Response flag: 0 = query, 1 = response */
    public static final int FLAG_QR = 0x8000;

    /** Authoritative Answer flag */
    public static final int FLAG_AA = 0x0400;

    /** Truncation flag */
    public static final int FLAG_TC = 0x0200;

    /** Recursion Desired flag */
    public static final int FLAG_RD = 0x0100;

    /** Recursion Available flag */
    public static final int FLAG_RA = 0x0080;

    // -- OPCODE values (bits 11-14) --

    /** Standard query */
    public static final int OPCODE_QUERY = 0;

    /** Inverse query (obsolete) */
    public static final int OPCODE_IQUERY = 1;

    /** Server status request */
    public static final int OPCODE_STATUS = 2;

    // -- RCODE values (bits 0-3 of flags) --

    /** No error */
    public static final int RCODE_NOERROR = 0;

    /** Format error */
    public static final int RCODE_FORMERR = 1;

    /** Server failure */
    public static final int RCODE_SERVFAIL = 2;

    /** Non-existent domain */
    public static final int RCODE_NXDOMAIN = 3;

    /** Not implemented */
    public static final int RCODE_NOTIMP = 4;

    /** Query refused */
    public static final int RCODE_REFUSED = 5;

    private static final int HEADER_SIZE = 12;
    private static final int COMPRESSION_MASK = 0xC0;
    private static final int COMPRESSION_POINTER = 0xC0;

    private final int id;
    private final int flags;
    private final List<DNSQuestion> questions;
    private final List<DNSResourceRecord> answers;
    private final List<DNSResourceRecord> authorities;
    private final List<DNSResourceRecord> additionals;

    /**
     * Creates a new DNS message.
     *
     * @param id the message ID
     * @param flags the header flags
     * @param questions the question section
     * @param answers the answer section
     * @param authorities the authority section
     * @param additionals the additional section
     */
    public DNSMessage(int id, int flags,
                      List<DNSQuestion> questions,
                      List<DNSResourceRecord> answers,
                      List<DNSResourceRecord> authorities,
                      List<DNSResourceRecord> additionals) {
        this.id = id & 0xFFFF;
        this.flags = flags & 0xFFFF;
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
        this.answers = Collections.unmodifiableList(new ArrayList<>(answers));
        this.authorities = Collections.unmodifiableList(new ArrayList<>(authorities));
        this.additionals = Collections.unmodifiableList(new ArrayList<>(additionals));
    }

    /**
     * Returns the message ID.
     *
     * @return the message ID (0-65535)
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the header flags.
     *
     * @return the flags
     */
    public int getFlags() {
        return flags;
    }

    /**
     * Returns true if this is a response message.
     *
     * @return true if response, false if query
     */
    public boolean isResponse() {
        return (flags & FLAG_QR) != 0;
    }

    /**
     * Returns true if this is a query message.
     *
     * @return true if query, false if response
     */
    public boolean isQuery() {
        return (flags & FLAG_QR) == 0;
    }

    /**
     * Returns the OPCODE.
     *
     * @return the opcode (0-15)
     */
    public int getOpcode() {
        return (flags >> 11) & 0x0F;
    }

    /**
     * Returns true if the Authoritative Answer flag is set.
     *
     * @return true if authoritative
     */
    public boolean isAuthoritative() {
        return (flags & FLAG_AA) != 0;
    }

    /**
     * Returns true if the message was truncated.
     *
     * @return true if truncated
     */
    public boolean isTruncated() {
        return (flags & FLAG_TC) != 0;
    }

    /**
     * Returns true if Recursion Desired is set.
     *
     * @return true if recursion is desired
     */
    public boolean isRecursionDesired() {
        return (flags & FLAG_RD) != 0;
    }

    /**
     * Returns true if Recursion Available is set.
     *
     * @return true if recursion is available
     */
    public boolean isRecursionAvailable() {
        return (flags & FLAG_RA) != 0;
    }

    /**
     * Returns the RCODE (response code).
     *
     * @return the response code (0-15)
     */
    public int getRcode() {
        return flags & 0x0F;
    }

    /**
     * Returns the question section.
     *
     * @return unmodifiable list of questions
     */
    public List<DNSQuestion> getQuestions() {
        return questions;
    }

    /**
     * Returns the answer section.
     *
     * @return unmodifiable list of answers
     */
    public List<DNSResourceRecord> getAnswers() {
        return answers;
    }

    /**
     * Returns the authority section.
     *
     * @return unmodifiable list of authority records
     */
    public List<DNSResourceRecord> getAuthorities() {
        return authorities;
    }

    /**
     * Returns the additional section.
     *
     * @return unmodifiable list of additional records
     */
    public List<DNSResourceRecord> getAdditionals() {
        return additionals;
    }

    // -- Parsing --

    /**
     * Parses a DNS message from a byte buffer.
     *
     * @param data the buffer containing the DNS message
     * @return the parsed message
     * @throws DNSFormatException if the message is malformed
     */
    public static DNSMessage parse(ByteBuffer data) throws DNSFormatException {
        if (data.remaining() < HEADER_SIZE) {
            throw new DNSFormatException("Message too short for header");
        }

        // Save original position for name compression
        ByteBuffer original = data.duplicate();

        // Parse header
        int id = data.getShort() & 0xFFFF;
        int flags = data.getShort() & 0xFFFF;
        int qdCount = data.getShort() & 0xFFFF;
        int anCount = data.getShort() & 0xFFFF;
        int nsCount = data.getShort() & 0xFFFF;
        int arCount = data.getShort() & 0xFFFF;

        // Parse questions
        List<DNSQuestion> questions = new ArrayList<>(qdCount);
        for (int i = 0; i < qdCount; i++) {
            DNSQuestion question = parseQuestion(data, original);
            questions.add(question);
        }

        // Parse answers
        List<DNSResourceRecord> answers = new ArrayList<>(anCount);
        for (int i = 0; i < anCount; i++) {
            DNSResourceRecord record = parseResourceRecord(data, original);
            answers.add(record);
        }

        // Parse authority
        List<DNSResourceRecord> authorities = new ArrayList<>(nsCount);
        for (int i = 0; i < nsCount; i++) {
            DNSResourceRecord record = parseResourceRecord(data, original);
            authorities.add(record);
        }

        // Parse additional
        List<DNSResourceRecord> additionals = new ArrayList<>(arCount);
        for (int i = 0; i < arCount; i++) {
            DNSResourceRecord record = parseResourceRecord(data, original);
            additionals.add(record);
        }

        return new DNSMessage(id, flags, questions, answers, authorities, additionals);
    }

    private static DNSQuestion parseQuestion(ByteBuffer data, ByteBuffer original) throws DNSFormatException {
        String name = decodeName(data, original);
        if (data.remaining() < 4) {
            throw new DNSFormatException("Truncated question");
        }
        int typeValue = data.getShort() & 0xFFFF;
        int classValue = data.getShort() & 0xFFFF;

        DNSType type = DNSType.fromValue(typeValue);
        if (type == null) {
            throw new DNSFormatException("Unknown type: " + typeValue);
        }

        DNSClass dnsClass = DNSClass.fromValue(classValue);
        if (dnsClass == null) {
            throw new DNSFormatException("Unknown class: " + classValue);
        }

        return new DNSQuestion(name, type, dnsClass);
    }

    private static DNSResourceRecord parseResourceRecord(ByteBuffer data, ByteBuffer original)
            throws DNSFormatException {
        String name = decodeName(data, original);
        if (data.remaining() < 10) {
            throw new DNSFormatException("Truncated resource record");
        }
        int typeValue = data.getShort() & 0xFFFF;
        int classValue = data.getShort() & 0xFFFF;
        int ttl = data.getInt();
        int rdLength = data.getShort() & 0xFFFF;

        if (data.remaining() < rdLength) {
            throw new DNSFormatException("Truncated RDATA");
        }

        byte[] rdata = new byte[rdLength];
        data.get(rdata);

        DNSType type = DNSType.fromValue(typeValue);
        if (type == null) {
            // Allow unknown types, store as raw data
            type = DNSType.A;
        }

        DNSClass dnsClass = DNSClass.fromValue(classValue);
        if (dnsClass == null) {
            dnsClass = DNSClass.IN;
        }

        return new DNSResourceRecord(name, type, dnsClass, ttl, rdata);
    }

    /**
     * Decodes a DNS name from the buffer.
     * Handles compression pointers.
     *
     * @param data the current read position
     * @param original the original message for pointer resolution
     * @return the decoded domain name
     */
    static String decodeName(ByteBuffer data, ByteBuffer original) {
        StringBuilder name = new StringBuilder();
        int maxJumps = 10;
        int jumps = 0;

        while (data.hasRemaining()) {
            int len = data.get() & 0xFF;

            if (len == 0) {
                break;
            }

            if ((len & COMPRESSION_MASK) == COMPRESSION_POINTER) {
                // Compression pointer
                if (!data.hasRemaining()) {
                    break;
                }
                int offset = ((len & 0x3F) << 8) | (data.get() & 0xFF);

                if (++jumps > maxJumps) {
                    throw new IllegalStateException("Too many compression pointers");
                }

                // Follow pointer in original message
                ByteBuffer pointer = original.duplicate();
                pointer.position(offset);
                String rest = decodeName(pointer, original);
                if (name.length() > 0) {
                    name.append('.');
                }
                name.append(rest);
                break;
            } else {
                // Regular label
                if (data.remaining() < len) {
                    break;
                }
                byte[] label = new byte[len];
                data.get(label);
                if (name.length() > 0) {
                    name.append('.');
                }
                name.append(new String(label, StandardCharsets.US_ASCII));
            }
        }

        return name.toString();
    }

    /**
     * Encodes a domain name to DNS wire format.
     *
     * @param name the domain name
     * @return the encoded bytes
     */
    static byte[] encodeName(String name) {
        if (name == null || name.isEmpty()) {
            return new byte[] { 0 };
        }

        // Remove trailing dot if present
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Parse labels manually without regex
        int start = 0;
        int len = name.length();
        while (start < len) {
            int dotIndex = name.indexOf('.', start);
            String label;
            if (dotIndex < 0) {
                label = name.substring(start);
                start = len;
            } else {
                label = name.substring(start, dotIndex);
                start = dotIndex + 1;
            }

            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length > 63) {
                throw new IllegalArgumentException("Label too long: " + label);
            }
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0); // Terminating zero

        return out.toByteArray();
    }

    // -- Serialization --

    /**
     * Serializes this message to a byte buffer.
     *
     * @return the serialized message
     */
    public ByteBuffer serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Header
        writeShort(out, id);
        writeShort(out, flags);
        writeShort(out, questions.size());
        writeShort(out, answers.size());
        writeShort(out, authorities.size());
        writeShort(out, additionals.size());

        // Questions
        for (DNSQuestion q : questions) {
            writeQuestion(out, q);
        }

        // Answers
        for (DNSResourceRecord rr : answers) {
            writeResourceRecord(out, rr);
        }

        // Authorities
        for (DNSResourceRecord rr : authorities) {
            writeResourceRecord(out, rr);
        }

        // Additionals
        for (DNSResourceRecord rr : additionals) {
            writeResourceRecord(out, rr);
        }

        byte[] bytes = out.toByteArray();
        return ByteBuffer.wrap(bytes);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeQuestion(ByteArrayOutputStream out, DNSQuestion q) {
        byte[] name = encodeName(q.getName());
        out.write(name, 0, name.length);
        writeShort(out, q.getType().getValue());
        writeShort(out, q.getDNSClass().getValue());
    }

    private static void writeResourceRecord(ByteArrayOutputStream out, DNSResourceRecord rr) {
        byte[] name = encodeName(rr.getName());
        out.write(name, 0, name.length);
        writeShort(out, rr.getType().getValue());
        writeShort(out, rr.getDNSClass().getValue());
        writeInt(out, rr.getTTL());
        byte[] rdata = rr.getRData();
        writeShort(out, rdata.length);
        out.write(rdata, 0, rdata.length);
    }

    // -- Factory methods for creating responses --

    /**
     * Creates a response message for this query.
     *
     * @param answers the answer records
     * @return the response message
     */
    public DNSMessage createResponse(List<DNSResourceRecord> answers) {
        List<DNSResourceRecord> emptyList = Collections.emptyList();
        return createResponse(answers, emptyList, emptyList);
    }

    /**
     * Creates a response message for this query.
     *
     * @param answers the answer records
     * @param authorities the authority records
     * @param additionals the additional records
     * @return the response message
     */
    public DNSMessage createResponse(List<DNSResourceRecord> answers,
                                      List<DNSResourceRecord> authorities,
                                      List<DNSResourceRecord> additionals) {
        int responseFlags = FLAG_QR | FLAG_RA | (flags & FLAG_RD);
        return new DNSMessage(id, responseFlags, questions, answers, authorities, additionals);
    }

    /**
     * Creates an error response for this query.
     *
     * @param rcode the response code (e.g., RCODE_NXDOMAIN)
     * @return the error response message
     */
    public DNSMessage createErrorResponse(int rcode) {
        int responseFlags = FLAG_QR | FLAG_RA | (flags & FLAG_RD) | (rcode & 0x0F);
        List<DNSResourceRecord> emptyList = Collections.emptyList();
        return new DNSMessage(id, responseFlags, questions, emptyList, emptyList, emptyList);
    }

    /**
     * Creates a new query message.
     *
     * @param id the message ID
     * @param name the domain name to query
     * @param type the record type
     * @return the query message
     */
    public static DNSMessage createQuery(int id, String name, DNSType type) {
        DNSQuestion question = new DNSQuestion(name, type, DNSClass.IN);
        List<DNSQuestion> questions = Collections.singletonList(question);
        List<DNSResourceRecord> emptyList = Collections.emptyList();
        return new DNSMessage(id, FLAG_RD, questions, emptyList, emptyList, emptyList);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DNSMessage{id=");
        sb.append(id);
        sb.append(", ");
        sb.append(isQuery() ? "QUERY" : "RESPONSE");
        sb.append(", opcode=");
        sb.append(getOpcode());
        if (isResponse()) {
            sb.append(", rcode=");
            sb.append(getRcode());
        }
        sb.append(", flags=");
        if (isAuthoritative()) {
            sb.append("AA ");
        }
        if (isTruncated()) {
            sb.append("TC ");
        }
        if (isRecursionDesired()) {
            sb.append("RD ");
        }
        if (isRecursionAvailable()) {
            sb.append("RA ");
        }

        sb.append(", questions=");
        sb.append(questions.size());
        sb.append(", answers=");
        sb.append(answers.size());
        sb.append(", authorities=");
        sb.append(authorities.size());
        sb.append(", additionals=");
        sb.append(additionals.size());
        sb.append("}");

        return sb.toString();
    }

}
