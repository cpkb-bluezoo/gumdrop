/*
 * DNSResourceRecord.java
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.ResourceBundle;

/**
 * A DNS resource record.
 *
 * <p>Resource records appear in the answer, authority, and additional
 * sections of DNS responses.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSResourceRecord {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private final String name;
    private final DNSType type;
    private final DNSClass dnsClass;
    private final int ttl;
    private final byte[] rdata;

    /**
     * Creates a new DNS resource record.
     *
     * @param name the domain name
     * @param type the record type
     * @param dnsClass the record class
     * @param ttl time to live in seconds
     * @param rdata the record data
     */
    public DNSResourceRecord(String name, DNSType type, DNSClass dnsClass, int ttl, byte[] rdata) {
        this.name = name;
        this.type = type;
        this.dnsClass = dnsClass;
        this.ttl = ttl;
        this.rdata = rdata.clone();
    }

    /**
     * Returns the domain name.
     *
     * @return the domain name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the record type.
     *
     * @return the record type
     */
    public DNSType getType() {
        return type;
    }

    /**
     * Returns the record class.
     *
     * @return the record class
     */
    public DNSClass getDNSClass() {
        return dnsClass;
    }

    /**
     * Returns the time to live in seconds.
     *
     * @return the TTL
     */
    public int getTTL() {
        return ttl;
    }

    /**
     * Returns the raw record data.
     *
     * @return a copy of the record data
     */
    public byte[] getRData() {
        return rdata.clone();
    }

    // -- Convenience factory methods --

    /**
     * Creates an A record (IPv4 address).
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param address the IPv4 address
     * @return the resource record
     */
    public static DNSResourceRecord a(String name, int ttl, InetAddress address) {
        return new DNSResourceRecord(name, DNSType.A, DNSClass.IN, ttl, address.getAddress());
    }

    /**
     * Creates an AAAA record (IPv6 address).
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param address the IPv6 address
     * @return the resource record
     */
    public static DNSResourceRecord aaaa(String name, int ttl, InetAddress address) {
        return new DNSResourceRecord(name, DNSType.AAAA, DNSClass.IN, ttl, address.getAddress());
    }

    /**
     * Creates a CNAME record.
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param canonicalName the canonical name
     * @return the resource record
     */
    public static DNSResourceRecord cname(String name, int ttl, String canonicalName) {
        byte[] encoded = DNSMessage.encodeName(canonicalName);
        return new DNSResourceRecord(name, DNSType.CNAME, DNSClass.IN, ttl, encoded);
    }

    /**
     * Creates a PTR record.
     *
     * @param name the domain name (reverse lookup name)
     * @param ttl time to live in seconds
     * @param ptrName the target domain name
     * @return the resource record
     */
    public static DNSResourceRecord ptr(String name, int ttl, String ptrName) {
        byte[] encoded = DNSMessage.encodeName(ptrName);
        return new DNSResourceRecord(name, DNSType.PTR, DNSClass.IN, ttl, encoded);
    }

    /**
     * Creates an NS record.
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param nsName the name server hostname
     * @return the resource record
     */
    public static DNSResourceRecord ns(String name, int ttl, String nsName) {
        byte[] encoded = DNSMessage.encodeName(nsName);
        return new DNSResourceRecord(name, DNSType.NS, DNSClass.IN, ttl, encoded);
    }

    /**
     * Creates an MX record.
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param preference the preference value (lower = higher priority)
     * @param exchange the mail server hostname
     * @return the resource record
     */
    public static DNSResourceRecord mx(String name, int ttl, int preference, String exchange) {
        byte[] exchangeBytes = DNSMessage.encodeName(exchange);
        ByteBuffer buf = ByteBuffer.allocate(2 + exchangeBytes.length);
        buf.putShort((short) preference);
        buf.put(exchangeBytes);
        return new DNSResourceRecord(name, DNSType.MX, DNSClass.IN, ttl, buf.array());
    }

    /**
     * Creates a TXT record.
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param text the text content
     * @return the resource record
     */
    public static DNSResourceRecord txt(String name, int ttl, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        // TXT records use character-strings: length byte + data, max 255 bytes each
        ByteBuffer buf = ByteBuffer.allocate(textBytes.length + (textBytes.length / 255) + 1);
        int offset = 0;
        while (offset < textBytes.length) {
            int len = Math.min(255, textBytes.length - offset);
            buf.put((byte) len);
            buf.put(textBytes, offset, len);
            offset += len;
        }
        byte[] rdataBytes = new byte[buf.position()];
        buf.flip();
        buf.get(rdataBytes);
        return new DNSResourceRecord(name, DNSType.TXT, DNSClass.IN, ttl, rdataBytes);
    }

    /**
     * Creates an SOA record.
     *
     * @param name the domain name
     * @param ttl time to live in seconds
     * @param mname the primary name server
     * @param rname the responsible person email (@ replaced with .)
     * @param serial the serial number
     * @param refresh the refresh interval
     * @param retry the retry interval
     * @param expire the expire time
     * @param minimum the minimum TTL
     * @return the resource record
     */
    public static DNSResourceRecord soa(String name, int ttl, String mname, String rname,
                                         int serial, int refresh, int retry, int expire, int minimum) {
        byte[] mnameBytes = DNSMessage.encodeName(mname);
        byte[] rnameBytes = DNSMessage.encodeName(rname);
        ByteBuffer buf = ByteBuffer.allocate(mnameBytes.length + rnameBytes.length + 20);
        buf.put(mnameBytes);
        buf.put(rnameBytes);
        buf.putInt(serial);
        buf.putInt(refresh);
        buf.putInt(retry);
        buf.putInt(expire);
        buf.putInt(minimum);
        return new DNSResourceRecord(name, DNSType.SOA, DNSClass.IN, ttl, buf.array());
    }

    // -- RDATA interpretation methods --

    /**
     * Interprets the RDATA as an IP address (for A or AAAA records).
     *
     * @return the IP address
     * @throws IllegalStateException if this is not an A or AAAA record
     */
    public InetAddress getAddress() {
        if (type != DNSType.A && type != DNSType.AAAA) {
            String msg = MessageFormat.format(L10N.getString("err.not_address_record"), type);
            throw new IllegalStateException(msg);
        }
        try {
            return InetAddress.getByAddress(rdata);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(L10N.getString("err.invalid_address_data"), e);
        }
    }

    /**
     * Interprets the RDATA as a domain name (for CNAME, PTR, NS records).
     *
     * @return the domain name
     * @throws IllegalStateException if this is not a name-type record
     */
    public String getTargetName() {
        if (type != DNSType.CNAME && type != DNSType.PTR && type != DNSType.NS) {
            String msg = MessageFormat.format(L10N.getString("err.not_name_record"), type);
            throw new IllegalStateException(msg);
        }
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        return DNSMessage.decodeName(buf, buf);
    }

    /**
     * Interprets the RDATA as TXT record content.
     *
     * @return the text content
     * @throws IllegalStateException if this is not a TXT record
     */
    public String getText() {
        if (type != DNSType.TXT) {
            String msg = MessageFormat.format(L10N.getString("err.not_txt_record"), type);
            throw new IllegalStateException(msg);
        }
        StringBuilder sb = new StringBuilder();
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        while (buf.hasRemaining()) {
            int len = buf.get() & 0xFF;
            byte[] segment = new byte[len];
            buf.get(segment);
            sb.append(new String(segment, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Returns the MX preference (for MX records).
     *
     * @return the preference value
     * @throws IllegalStateException if this is not an MX record
     */
    public int getMXPreference() {
        if (type != DNSType.MX) {
            String msg = MessageFormat.format(L10N.getString("err.not_mx_record"), type);
            throw new IllegalStateException(msg);
        }
        return ((rdata[0] & 0xFF) << 8) | (rdata[1] & 0xFF);
    }

    /**
     * Returns the MX exchange hostname (for MX records).
     *
     * @return the mail server hostname
     * @throws IllegalStateException if this is not an MX record
     */
    public String getMXExchange() {
        if (type != DNSType.MX) {
            String msg = MessageFormat.format(L10N.getString("err.not_mx_record"), type);
            throw new IllegalStateException(msg);
        }
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.getShort(); // skip preference
        return DNSMessage.decodeName(buf, ByteBuffer.wrap(rdata));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DNSResourceRecord)) {
            return false;
        }
        DNSResourceRecord that = (DNSResourceRecord) o;
        return ttl == that.ttl &&
               name.equalsIgnoreCase(that.name) &&
               type == that.type &&
               dnsClass == that.dnsClass &&
               Arrays.equals(rdata, that.rdata);
    }

    @Override
    public int hashCode() {
        int result = name.toLowerCase().hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + dnsClass.hashCode();
        result = 31 * result + ttl;
        result = 31 * result + Arrays.hashCode(rdata);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        sb.append(" ");
        sb.append(ttl);
        sb.append(" ");
        sb.append(dnsClass);
        sb.append(" ");
        sb.append(type);

        // Add type-specific data representation
        try {
            switch (type) {
                case A:
                case AAAA:
                    sb.append(" ");
                    sb.append(getAddress().getHostAddress());
                    break;
                case CNAME:
                case PTR:
                case NS:
                    sb.append(" ");
                    sb.append(getTargetName());
                    break;
                case TXT:
                    sb.append(" \"");
                    sb.append(getText());
                    sb.append("\"");
                    break;
                case MX:
                    sb.append(" ");
                    sb.append(getMXPreference());
                    sb.append(" ");
                    sb.append(getMXExchange());
                    break;
                default:
                    sb.append(" [");
                    sb.append(rdata.length);
                    sb.append(" bytes]");
            }
        } catch (Exception e) {
            sb.append(" [invalid]");
        }

        return sb.toString();
    }

}
