/*
 * DNSMultiQType.java
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

package org.bluezoo.gumdrop.dns;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * DNS Multiple QTYPEs support.
 * RFC 10029: a client can ask for additional RRTYPEs for the same
 * (QNAME, QCLASS) alongside its primary question, without adding a
 * second question to the Question section. The client lists the extra
 * types in an {@code MQTYPE-Query} EDNS0 option; a supporting server
 * merges answers for those types into the single response and echoes
 * back which ones it managed to include via an {@code MQTYPE-Response}
 * option. Anything not echoed back must still be queried individually.
 *
 * <p>Each option's data is a sequence of 2-octet RRTYPE values in
 * network byte order -- no count field, since the option length
 * (option-code(2) + option-length(2) + data, RFC 6891 section 6.1.2)
 * already determines how many types are present.
 *
 * <p>This class is a pure codec, matching {@link DNSCookie}'s division
 * of responsibility: it does not validate the semantic constraints RFC
 * 10029 places on the type list (no meta-types such as {@link
 * DNSType#ANY}, no duplicates, non-empty, within the configured cap) --
 * callers building or accepting an option enforce those, since the
 * right response to a violation (silently not building the option vs.
 * FORMERR) differs between client and server.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc10029">RFC 10029</a>
 */
public final class DNSMultiQType {

    /** RFC 10029: EDNS0 option code for the client's MQTYPE-Query. */
    public static final int EDNS_OPTION_MQTYPE_QUERY = 20;

    /** RFC 10029: EDNS0 option code for the server's MQTYPE-Response. */
    public static final int EDNS_OPTION_MQTYPE_RESPONSE = 21;

    /**
     * RFC 10029 security considerations: recommended cap on the number
     * of additional QTYPEs a public resolver should accept.
     */
    public static final int DEFAULT_MAX_MQTYPES = 4;

    private DNSMultiQType() {
    }

    /**
     * Builds a complete {@code MQTYPE-Query} EDNS0 option (option-code +
     * option-length + data) for inclusion in a query's OPT record RDATA.
     *
     * @param additionalTypes the additional RRTYPEs being requested
     * @return the encoded EDNS0 option bytes
     */
    public static byte[] buildMQTypeQueryOption(List<DNSType> additionalTypes) {
        return buildOption(EDNS_OPTION_MQTYPE_QUERY, additionalTypes);
    }

    /**
     * Builds a complete {@code MQTYPE-Response} EDNS0 option (option-code
     * + option-length + data) for inclusion in a response's OPT record
     * RDATA.
     *
     * @param includedTypes the additional RRTYPEs successfully merged
     *                      into this response
     * @return the encoded EDNS0 option bytes
     */
    public static byte[] buildMQTypeResponseOption(List<DNSType> includedTypes) {
        return buildOption(EDNS_OPTION_MQTYPE_RESPONSE, includedTypes);
    }

    private static byte[] buildOption(int optionCode, List<DNSType> types) {
        ByteBuffer buf = ByteBuffer.allocate(4 + types.size() * 2);
        buf.putShort((short) optionCode);
        buf.putShort((short) (types.size() * 2));
        for (DNSType type : types) {
            buf.putShort((short) type.getValue());
        }
        return buf.array();
    }

    /**
     * Parses an {@code MQTYPE-Query} option's data (as returned by
     * {@link DNSCookie#findEdnsOption(byte[], int)} for {@link
     * #EDNS_OPTION_MQTYPE_QUERY}) into the requested RRTYPEs.
     *
     * @param optionData the option data (excluding option-code/-length)
     * @return the requested types, in the order they appeared
     * @throws DNSFormatException if the option data length isn't a
     *                            multiple of 2 octets
     */
    public static List<DNSType> parseMQTypeQueryOption(byte[] optionData)
            throws DNSFormatException {
        return parseOption(optionData);
    }

    /**
     * Parses an {@code MQTYPE-Response} option's data (as returned by
     * {@link DNSCookie#findEdnsOption(byte[], int)} for {@link
     * #EDNS_OPTION_MQTYPE_RESPONSE}) into the RRTYPEs the server
     * included in the response.
     *
     * @param optionData the option data (excluding option-code/-length)
     * @return the included types, in the order they appeared
     * @throws DNSFormatException if the option data length isn't a
     *                            multiple of 2 octets
     */
    public static List<DNSType> parseMQTypeResponseOption(byte[] optionData)
            throws DNSFormatException {
        return parseOption(optionData);
    }

    // RRTYPE values this implementation doesn't recognize (DNSType.fromValue
    // returns null) are silently skipped rather than rejected: a type this
    // codebase has no DNSResourceRecord support for can't be fulfilled or
    // matched against anyway, so it's equivalent to the server simply not
    // including it -- not a malformed request.
    private static List<DNSType> parseOption(byte[] optionData) throws DNSFormatException {
        if (optionData.length % 2 != 0) {
            throw new DNSFormatException(
                    "MQTYPE option data length must be a multiple of 2 octets, was "
                            + optionData.length);
        }
        List<DNSType> types = new ArrayList<>(optionData.length / 2);
        ByteBuffer buf = ByteBuffer.wrap(optionData);
        while (buf.remaining() >= 2) {
            int value = buf.getShort() & 0xFFFF;
            DNSType type = DNSType.fromValue(value);
            if (type != null) {
                types.add(type);
            }
        }
        return types;
    }

}
