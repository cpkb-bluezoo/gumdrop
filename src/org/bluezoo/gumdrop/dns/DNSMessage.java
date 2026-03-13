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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * A DNS protocol message.
 * RFC 1035 section 4.1 defines the message format: header, question,
 * answer, authority, and additional sections.
 *
 * <p>DNS messages are used for both queries and responses. The message
 * consists of a header followed by question, answer, authority, and
 * additional sections.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSMessage {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    // RFC 1035 section 4.1.1 - Header section format.
    // Flags word layout (16 bits):
    //   QR(1) OPCODE(4) AA(1) TC(1) RD(1) RA(1) Z(3) RCODE(4)

    /** Query/Response flag (bit 15): 0 = query, 1 = response. RFC 1035 section 4.1.1. */
    public static final int FLAG_QR = 0x8000;

    /** Authoritative Answer flag (bit 10). RFC 1035 section 4.1.1. */
    public static final int FLAG_AA = 0x0400;

    /** Truncation flag (bit 9). RFC 1035 section 4.1.1. */
    public static final int FLAG_TC = 0x0200;

    /** Recursion Desired flag (bit 8). RFC 1035 section 4.1.1. */
    public static final int FLAG_RD = 0x0100;

    /** Recursion Available flag (bit 7). RFC 1035 section 4.1.1. */
    public static final int FLAG_RA = 0x0080;

    /** Authenticated Data flag (bit 5). RFC 4035 section 3.2.3. */
    public static final int FLAG_AD = 0x0020;

    /** Checking Disabled flag (bit 4). RFC 4035 section 3.2.2. */
    public static final int FLAG_CD = 0x0010;

    // RFC 1035 section 4.1.1 - OPCODE (bits 14-11)

    /** Standard query. RFC 1035 section 4.1.1. */
    public static final int OPCODE_QUERY = 0;

    /** Inverse query (obsolete). RFC 1035 section 6.4. */
    public static final int OPCODE_IQUERY = 1;

    /** Server status request. RFC 1035 section 4.1.1. */
    public static final int OPCODE_STATUS = 2;

    // RFC 1035 section 4.1.1 - RCODE (bits 3-0)

    /** No error. RFC 1035 section 4.1.1. */
    public static final int RCODE_NOERROR = 0;

    /** Format error. RFC 1035 section 4.1.1. */
    public static final int RCODE_FORMERR = 1;

    /** Server failure. RFC 1035 section 4.1.1. */
    public static final int RCODE_SERVFAIL = 2;

    /** Non-existent domain. RFC 1035 section 4.1.1. */
    public static final int RCODE_NXDOMAIN = 3;

    /** Not implemented. RFC 1035 section 4.1.1. */
    public static final int RCODE_NOTIMP = 4;

    /** Query refused. RFC 1035 section 4.1.1. */
    public static final int RCODE_REFUSED = 5;

    // RFC 1035 section 4.1.1: header is 12 octets (6 x 16-bit fields)
    private static final int HEADER_SIZE = 12;
    // RFC 4035 section 3.2: bits 5 (AD) and 4 (CD) are DNSSEC flags;
    // only bit 6 remains reserved as zero.
    private static final int Z_BITS_MASK = 0x0040;
    // RFC 1035 section 4.1.4: top 2 bits = 11 indicates a compression pointer
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
     * Returns true if the Authenticated Data flag is set.
     * RFC 4035 section 3.2.3: set by a validating resolver when all
     * answer and authority RRsets have been validated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticatedData() {
        return (flags & FLAG_AD) != 0;
    }

    /**
     * Returns true if the Checking Disabled flag is set.
     * RFC 4035 section 3.2.2: set by a stub resolver to indicate
     * that it will perform its own validation.
     *
     * @return true if checking is disabled
     */
    public boolean isCheckingDisabled() {
        return (flags & FLAG_CD) != 0;
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

    /**
     * Returns true if the DNSSEC OK (DO) bit is set in this message's
     * EDNS0 OPT record.
     * RFC 4035 section 3.2.1: the DO bit signals that the sender
     * wants DNSSEC resource records in the response.
     *
     * @return true if DO is set, false if no OPT record or DO is clear
     */
    public boolean hasDO() {
        for (int i = 0; i < additionals.size(); i++) {
            DNSResourceRecord rr = additionals.get(i);
            if (rr.getType() == DNSType.OPT) {
                return (rr.getEDNSFlags() & DNSResourceRecord.EDNS_FLAG_DO) != 0;
            }
        }
        return false;
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
            throw new DNSFormatException(L10N.getString("err.message_too_short"));
        }

        ByteBuffer original = data.duplicate();

        // RFC 1035 section 4.1.1: parse 12-octet header
        // ID(16) FLAGS(16) QDCOUNT(16) ANCOUNT(16) NSCOUNT(16) ARCOUNT(16)
        int id = data.getShort() & 0xFFFF;
        // RFC 1035 section 4.1.1: mask Z bits (must be zero, lenient handling)
        int flags = (data.getShort() & 0xFFFF) & ~Z_BITS_MASK;
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
            throw new DNSFormatException(L10N.getString("err.truncated_question"));
        }
        int typeValue = data.getShort() & 0xFFFF;
        int classValue = data.getShort() & 0xFFFF;

        DNSType type = DNSType.fromValue(typeValue);
        if (type == null) {
            String msg = MessageFormat.format(L10N.getString("err.unknown_type"), typeValue);
            throw new DNSFormatException(msg);
        }

        DNSClass dnsClass = DNSClass.fromValue(classValue);
        if (dnsClass == null) {
            String msg = MessageFormat.format(L10N.getString("err.unknown_class"), classValue);
            throw new DNSFormatException(msg);
        }

        return new DNSQuestion(name, type, dnsClass);
    }

    private static DNSResourceRecord parseResourceRecord(ByteBuffer data, ByteBuffer original)
            throws DNSFormatException {
        String name = decodeName(data, original);
        if (data.remaining() < 10) {
            throw new DNSFormatException(L10N.getString("err.truncated_resource_record"));
        }
        int typeValue = data.getShort() & 0xFFFF;
        int classValue = data.getShort() & 0xFFFF;
        int ttl = data.getInt();
        int rdLength = data.getShort() & 0xFFFF;

        if (data.remaining() < rdLength) {
            throw new DNSFormatException(L10N.getString("err.truncated_rdata"));
        }

        byte[] rdata = new byte[rdLength];
        data.get(rdata);

        // RFC 3597: preserve unknown types/classes as opaque data
        DNSType type = DNSType.fromValue(typeValue);
        DNSClass dnsClass = DNSClass.fromValue(classValue);

        return new DNSResourceRecord(name, type, typeValue,
                dnsClass, classValue, ttl, rdata);
    }

    /**
     * Decodes a DNS name from the buffer.
     * RFC 1035 section 3.1: names are sequences of labels, each a length
     * octet followed by that many octets, terminated by a zero-length label.
     * RFC 1035 section 4.1.4: compression pointers (top 2 bits = 11) refer
     * to a prior occurrence of the same name in the message.
     *
     * @param data the current read position
     * @param original the original message for pointer resolution
     * @return the decoded domain name
     */
    static String decodeName(ByteBuffer data, ByteBuffer original) {
        StringBuilder name = new StringBuilder();
        int maxJumps = 10;
        int jumps = 0;
        // RFC 1035 section 2.3.4: track total wire-format length
        int totalLength = 0;

        while (data.hasRemaining()) {
            int len = data.get() & 0xFF;

            if (len == 0) {
                break;
            }

            if ((len & COMPRESSION_MASK) == COMPRESSION_POINTER) {
                // Compression pointer (2 bytes, terminates this name)
                if (!data.hasRemaining()) {
                    break;
                }
                int offset = ((len & 0x3F) << 8) | (data.get() & 0xFF);

                if (++jumps > maxJumps) {
                    throw new IllegalStateException(L10N.getString("err.too_many_compression_pointers"));
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
                totalLength += 1 + len; // length octet + label data
                if (totalLength > MAX_NAME_LENGTH) {
                    throw new IllegalStateException(
                            L10N.getString("err.name_too_long_decode"));
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
     * RFC 1035 section 3.1: each label is a length octet followed by that
     * many octets, terminated by a zero-length label.
     * RFC 1035 section 2.3.4: labels are limited to 63 octets, total name
     * to 255 octets.
     *
     * @param name the domain name
     * @return the encoded bytes
     */
    // RFC 1035 section 2.3.4
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int MAX_NAME_LENGTH = 255;

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
            if (bytes.length > MAX_LABEL_LENGTH) {
                String msg = MessageFormat.format(L10N.getString("err.label_too_long"), label);
                throw new IllegalArgumentException(msg);
            }
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0); // Terminating zero

        // RFC 1035 section 2.3.4: total encoded name must not exceed 255 octets
        byte[] encoded = out.toByteArray();
        if (encoded.length > MAX_NAME_LENGTH) {
            String msg = MessageFormat.format(L10N.getString("err.name_too_long"), name);
            throw new IllegalArgumentException(msg);
        }

        return encoded;
    }

    // -- Serialization --

    /**
     * Serializes this message to DNS wire format with name compression.
     * RFC 1035 section 4.1: message consists of header, question, answer,
     * authority, and additional sections serialized sequentially.
     * RFC 1035 section 4.1.4: repeated domain name suffixes are replaced
     * with 2-byte compression pointers to reduce message size.
     *
     * @return the serialized message
     */
    public ByteBuffer serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // RFC 1035 section 4.1.4: compression table maps lowercase
        // domain name suffixes to their byte offsets in the output.
        Map<String, Integer> compressionTable = new HashMap<>();

        // RFC 1035 section 4.1.1: 12-octet header
        writeShort(out, id);
        // RFC 1035 section 4.1.1: ensure Z bits are cleared
        writeShort(out, flags & ~Z_BITS_MASK);
        writeShort(out, questions.size());
        writeShort(out, answers.size());
        writeShort(out, authorities.size());
        writeShort(out, additionals.size());

        // Questions
        for (DNSQuestion q : questions) {
            writeQuestion(out, q, compressionTable);
        }

        // Answers
        for (DNSResourceRecord rr : answers) {
            writeResourceRecord(out, rr, compressionTable);
        }

        // Authorities
        for (DNSResourceRecord rr : authorities) {
            writeResourceRecord(out, rr, compressionTable);
        }

        // Additionals
        for (DNSResourceRecord rr : additionals) {
            writeResourceRecord(out, rr, compressionTable);
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

    private static void writeQuestion(ByteArrayOutputStream out, DNSQuestion q,
                                       Map<String, Integer> compressionTable) {
        writeNameCompressed(out, q.getName(), compressionTable);
        writeShort(out, q.getType().getValue());
        writeShort(out, q.getDNSClass().getValue());
    }

    private static void writeResourceRecord(ByteArrayOutputStream out, DNSResourceRecord rr) {
        byte[] name = encodeName(rr.getName());
        out.write(name, 0, name.length);
        // RFC 3597: use raw values to preserve unknown types/classes
        writeShort(out, rr.getRawType());
        writeShort(out, rr.getRawClass());
        writeInt(out, rr.getTTL());
        byte[] rdata = rr.getRData();
        writeShort(out, rdata.length);
        out.write(rdata, 0, rdata.length);
    }

    private static void writeResourceRecord(ByteArrayOutputStream out, DNSResourceRecord rr,
                                              Map<String, Integer> compressionTable) {
        writeNameCompressed(out, rr.getName(), compressionTable);
        // RFC 3597: use raw values to preserve unknown types/classes
        writeShort(out, rr.getRawType());
        writeShort(out, rr.getRawClass());
        writeInt(out, rr.getTTL());
        byte[] rdata = rr.getRData();
        writeShort(out, rdata.length);
        out.write(rdata, 0, rdata.length);
    }

    /**
     * RFC 1035 section 4.1.4: writes a domain name with compression.
     * For each suffix of the name, checks whether it has already been
     * written. If so, emits a 2-byte pointer (top two bits = 11,
     * remaining 14 bits = offset). Otherwise writes labels and records
     * the offset of each new suffix.
     *
     * <p>Pointers are only valid for offsets &lt; 16384 (14-bit range).
     */
    private static void writeNameCompressed(ByteArrayOutputStream out,
                                             String name,
                                             Map<String, Integer> compressionTable) {
        if (name == null || name.isEmpty() || name.equals(".")) {
            out.write(0);
            return;
        }
        // Normalise: remove trailing dot, lowercase for matching
        String work = name;
        if (work.endsWith(".")) {
            work = work.substring(0, work.length() - 1);
        }
        String key = work.toLowerCase();

        Integer ptr = compressionTable.get(key);
        if (ptr != null && ptr < 0x3FFF) {
            // Emit compression pointer
            out.write(0xC0 | ((ptr >> 8) & 0x3F));
            out.write(ptr & 0xFF);
            return;
        }

        // Split into first label and remainder
        String[] labels = work.split("\\.", 2);
        String label = labels[0];

        // Record offset for this suffix before writing
        int offset = out.size();
        if (offset < 0x3FFF) {
            compressionTable.put(key, offset);
        }

        // Write the label
        byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(labelBytes.length);
        out.write(labelBytes, 0, labelBytes.length);

        if (labels.length > 1 && !labels[1].isEmpty()) {
            writeNameCompressed(out, labels[1], compressionTable);
        } else {
            out.write(0);
        }
    }

    // -- EDNS(0) padding (RFC 7830, RFC 9250 section 5.4) --

    // RFC 6891: OPT record overhead = name(1) + type(2) + class(2) + ttl(4) + rdlen(2) = 11
    private static final int OPT_RECORD_OVERHEAD = 11;
    // Padding option header: option-code(2) + option-length(2) = 4
    private static final int PADDING_OPTION_OVERHEAD = 4;

    /**
     * Adds EDNS(0) padding to a serialized DNS message so that the total
     * message size (excluding the 2-octet TCP/DoQ length prefix) is a
     * multiple of {@code blockSize}.
     * RFC 7830 defines the padding option; RFC 8467 and RFC 9250 section 5.4
     * recommend block-length padding.
     *
     * <p>An OPT pseudo-record with a padding option is appended to the
     * additional section and ARCOUNT is incremented.
     *
     * @param serialized the serialized DNS message
     * @param blockSize the block size to align to (e.g., 128)
     * @return a new buffer with the padded message
     */
    public static ByteBuffer padToBlockSize(ByteBuffer serialized, int blockSize) {
        int msgLen = serialized.remaining();
        int overhead = OPT_RECORD_OVERHEAD + PADDING_OPTION_OVERHEAD;
        int totalWithOpt = msgLen + overhead;
        int paddingNeeded = (blockSize - (totalWithOpt % blockSize)) % blockSize;

        byte[] original = new byte[msgLen];
        int pos = serialized.position();
        serialized.get(original);
        serialized.position(pos);

        byte[] padded = new byte[msgLen + overhead + paddingNeeded];
        System.arraycopy(original, 0, padded, 0, msgLen);

        // Increment ARCOUNT (bytes 10-11)
        int arCount = ((padded[10] & 0xFF) << 8) | (padded[11] & 0xFF);
        arCount++;
        padded[10] = (byte) ((arCount >> 8) & 0xFF);
        padded[11] = (byte) (arCount & 0xFF);

        // Append OPT record at end
        int off = msgLen;
        padded[off] = 0; off++; // root name
        padded[off] = 0; off++; padded[off] = 41; off++; // TYPE = OPT
        padded[off] = 0x10; off++; padded[off] = 0x00; off++; // CLASS = 4096 (UDP payload)
        off += 4; // TTL = 0 (already zero)
        int rdLen = PADDING_OPTION_OVERHEAD + paddingNeeded;
        padded[off] = (byte) ((rdLen >> 8) & 0xFF); off++;
        padded[off] = (byte) (rdLen & 0xFF); off++;
        // Padding option (RFC 7830): option-code 12
        off++; // high byte = 0
        padded[off] = 12; off++;
        padded[off] = (byte) ((paddingNeeded >> 8) & 0xFF); off++;
        padded[off] = (byte) (paddingNeeded & 0xFF);
        // remaining padding bytes are already zero from array initialization

        return ByteBuffer.wrap(padded);
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
     * RFC 1035 section 4.1.1: response copies the query ID and questions,
     * sets QR=1, preserves RD from the query, and sets RA if recursion
     * is available.
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
     * Default EDNS0 UDP payload size (4096 octets).
     * RFC 6891 section 6.2.5: values of less than 512 MUST be treated
     * as equal to 512. 4096 is the common practical default.
     */
    public static final int DEFAULT_EDNS_UDP_SIZE = 4096;

    /**
     * Creates a new query message.
     * RFC 1035 section 4.1.1: queries have QR=0. RD is set to request
     * recursive resolution from the server.
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

    /**
     * Creates a new query message with an EDNS0 OPT record.
     * RFC 6891 section 6.1.1: the OPT record is placed in the
     * additional section to signal extended capabilities.
     *
     * @param id the message ID
     * @param name the domain name to query
     * @param type the record type
     * @param additionals the additional section (typically containing an OPT record)
     * @return the query message
     */
    public static DNSMessage createQuery(int id, String name, DNSType type,
                                          List<DNSResourceRecord> additionals) {
        DNSQuestion question = new DNSQuestion(name, type, DNSClass.IN);
        List<DNSQuestion> questions = Collections.singletonList(question);
        List<DNSResourceRecord> emptyList = Collections.emptyList();
        return new DNSMessage(id, FLAG_RD, questions, emptyList, emptyList, additionals);
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
        if (isAuthenticatedData()) {
            sb.append("AD ");
        }
        if (isCheckingDisabled()) {
            sb.append("CD ");
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
