/*
 * DNSMultiQTypeTest.java
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DNSMultiQType}.
 * RFC 10029: DNS Multiple QTYPEs.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DNSMultiQTypeTest {

    @Test
    public void testQueryOptionRoundTrip() throws DNSFormatException {
        List<DNSType> requested = Arrays.asList(DNSType.AAAA, DNSType.HTTPS);
        byte[] option = DNSMultiQType.buildMQTypeQueryOption(requested);

        byte[] data = DNSCookie.findEdnsOption(option, DNSMultiQType.EDNS_OPTION_MQTYPE_QUERY);
        assertNotNull(data);
        assertEquals(requested, DNSMultiQType.parseMQTypeQueryOption(data));
    }

    @Test
    public void testResponseOptionRoundTrip() throws DNSFormatException {
        List<DNSType> included = Collections.singletonList(DNSType.AAAA);
        byte[] option = DNSMultiQType.buildMQTypeResponseOption(included);

        byte[] data = DNSCookie.findEdnsOption(option, DNSMultiQType.EDNS_OPTION_MQTYPE_RESPONSE);
        assertNotNull(data);
        assertEquals(included, DNSMultiQType.parseMQTypeResponseOption(data));
    }

    @Test
    public void testOptionCodeAndLength() {
        byte[] option = DNSMultiQType.buildMQTypeQueryOption(
                Arrays.asList(DNSType.A, DNSType.AAAA, DNSType.HTTPS));
        // option-code(2) + option-length(2) + 3 types * 2 octets
        assertEquals(4 + 6, option.length);
        int code = ((option[0] & 0xFF) << 8) | (option[1] & 0xFF);
        int length = ((option[2] & 0xFF) << 8) | (option[3] & 0xFF);
        assertEquals(DNSMultiQType.EDNS_OPTION_MQTYPE_QUERY, code);
        assertEquals(6, length);
    }

    @Test
    public void testEmptyListRoundTrips() throws DNSFormatException {
        byte[] option = DNSMultiQType.buildMQTypeQueryOption(Collections.<DNSType>emptyList());
        byte[] data = DNSCookie.findEdnsOption(option, DNSMultiQType.EDNS_OPTION_MQTYPE_QUERY);
        assertNotNull(data);
        assertTrue(DNSMultiQType.parseMQTypeQueryOption(data).isEmpty());
    }

    @Test
    public void testPreservesOrder() throws DNSFormatException {
        List<DNSType> requested = Arrays.asList(DNSType.HTTPS, DNSType.A, DNSType.AAAA);
        byte[] option = DNSMultiQType.buildMQTypeQueryOption(requested);
        byte[] data = DNSCookie.findEdnsOption(option, DNSMultiQType.EDNS_OPTION_MQTYPE_QUERY);
        assertEquals(requested, DNSMultiQType.parseMQTypeQueryOption(data));
    }

    @Test
    public void testUnrecognizedTypeValueIsSkippedNotRejected() throws DNSFormatException {
        // 65280 is not a value any DNSType enum constant uses.
        byte[] data = new byte[] {
                (byte) 0, (byte) DNSType.A.getValue(),
                (byte) 0xFF, (byte) 0x00,
                (byte) 0, (byte) DNSType.AAAA.getValue()
        };
        List<DNSType> parsed = DNSMultiQType.parseMQTypeQueryOption(data);
        assertEquals(Arrays.asList(DNSType.A, DNSType.AAAA), parsed);
    }

    @Test(expected = DNSFormatException.class)
    public void testOddLengthDataIsRejected() throws DNSFormatException {
        DNSMultiQType.parseMQTypeQueryOption(new byte[] {0, 1, 0});
    }
}
