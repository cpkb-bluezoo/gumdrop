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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;

/**
 * A DNS resource record.
 * RFC 1035 section 3.2.1 defines the RR format: NAME, TYPE, CLASS, TTL, RDLENGTH, RDATA.
 * RFC 1035 section 4.1.3 defines the wire format for resource records.
 *
 * <p>Resource records appear in the answer, authority, and additional
 * sections of DNS responses. Name comparison is case-insensitive
 * per RFC 1035 section 2.3.3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSResourceRecord {

    static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.dns.L10N");

    private final String name;
    private final DNSType type;
    private final int rawType;
    private final DNSClass dnsClass;
    private final int rawClass;
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
        this.rawType = type != null ? type.getValue() : 0;
        this.dnsClass = dnsClass;
        this.rawClass = dnsClass != null ? dnsClass.getValue() : 0;
        this.ttl = ttl;
        this.rdata = rdata.clone();
    }

    /**
     * Creates a DNS resource record preserving raw type/class values.
     * RFC 3597: unknown RR types must be preserved as opaque data.
     *
     * @param name the domain name
     * @param type the record type (may be null for unknown types)
     * @param rawType the raw numeric type value from the wire
     * @param dnsClass the record class (may be null for unknown classes)
     * @param rawClass the raw numeric class value from the wire
     * @param ttl time to live in seconds
     * @param rdata the record data
     */
    public DNSResourceRecord(String name, DNSType type, int rawType,
                             DNSClass dnsClass, int rawClass,
                             int ttl, byte[] rdata) {
        this.name = name;
        this.type = type;
        this.rawType = rawType;
        this.dnsClass = dnsClass;
        this.rawClass = rawClass;
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
     * Returns the raw numeric type value from the wire.
     * RFC 3597: this preserves the original value for unknown types.
     *
     * @return the raw type value (0-65535)
     */
    public int getRawType() {
        return rawType;
    }

    /**
     * Returns the record class.
     *
     * @return the record class, or null if the class is unknown
     */
    public DNSClass getDNSClass() {
        return dnsClass;
    }

    /**
     * Returns the raw numeric class value from the wire.
     * RFC 3597: this preserves the original value for unknown classes.
     *
     * @return the raw class value (0-65535)
     */
    public int getRawClass() {
        return rawClass;
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

    /**
     * RFC 4035 section 3.2.1: the DNSSEC OK (DO) bit in the EDNS0
     * flags field of the OPT record TTL. Bit 15 of the 16-bit flags
     * portion (lower half of the 32-bit TTL).
     */
    public static final int EDNS_FLAG_DO = 0x8000;

    // -- Convenience factory methods --

    /**
     * Creates an A record (IPv4 address).
     * RFC 1035 section 3.4.1: A RDATA is a 4-octet Internet address.
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
     * RFC 3596 section 2.1: AAAA RDATA is a 128-bit IPv6 address.
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
     * RFC 1035 section 3.3.1: CNAME RDATA contains a single domain name.
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
     * RFC 1035 section 3.3.12: PTR RDATA contains a single domain name.
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
     * RFC 1035 section 3.3.11: NS RDATA contains a single domain name.
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
     * RFC 1035 section 3.3.9: MX RDATA is a 16-bit preference followed by
     * a domain name (exchange). Lower preference values are preferred.
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
     * Creates an SRV record.
     * RFC 2782: SRV RDATA is priority(2) + weight(2) + port(2) + target
     * (encoded domain name).
     *
     * @param name the service name (e.g. _sip._tcp.example.com)
     * @param ttl time to live in seconds
     * @param priority the target host priority (lower = preferred)
     * @param weight relative weight for entries with equal priority
     * @param port the port on the target host
     * @param target the domain name of the target host
     * @return the resource record
     */
    public static DNSResourceRecord srv(String name, int ttl,
                                         int priority, int weight,
                                         int port, String target) {
        byte[] targetBytes = DNSMessage.encodeName(target);
        ByteBuffer buf = ByteBuffer.allocate(6 + targetBytes.length);
        buf.putShort((short) priority);
        buf.putShort((short) weight);
        buf.putShort((short) port);
        buf.put(targetBytes);
        return new DNSResourceRecord(name, DNSType.SRV, DNSClass.IN,
                ttl, buf.array());
    }

    /**
     * Creates a TXT record.
     * RFC 1035 section 3.3.14: TXT RDATA is one or more character-strings.
     * Each character-string is a length octet (max 255) followed by that
     * many octets of data.
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
     * RFC 1035 section 3.3.13: SOA RDATA contains MNAME, RNAME (domain names),
     * followed by SERIAL, REFRESH, RETRY, EXPIRE, MINIMUM (all 32-bit unsigned).
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

    /**
     * Creates an OPT pseudo-record for EDNS0.
     * RFC 6891 section 6.1.1: the OPT record signals extended DNS
     * capabilities. The record name is root (empty), TYPE is OPT(41),
     * CLASS encodes the sender's UDP payload size, TTL encodes the
     * extended RCODE and flags, and RDATA contains zero or more options.
     *
     * @param udpPayloadSize the maximum UDP payload size the sender
     *                       can reassemble (e.g. 4096)
     * @return the OPT pseudo-record
     */
    public static DNSResourceRecord opt(int udpPayloadSize) {
        return opt(udpPayloadSize, new byte[0]);
    }

    /**
     * Creates an OPT pseudo-record for EDNS0 with option data.
     * RFC 6891 section 6.1.1: RDATA contains options encoded as
     * option-code(2) + option-length(2) + option-data.
     *
     * @param udpPayloadSize the maximum UDP payload size
     * @param optionData the EDNS0 option data (may be empty)
     * @return the OPT pseudo-record
     */
    public static DNSResourceRecord opt(int udpPayloadSize,
                                         byte[] optionData) {
        return opt(udpPayloadSize, 0, optionData);
    }

    /**
     * Creates an OPT pseudo-record for EDNS0 with flags and option data.
     * RFC 6891 section 6.1.3: the TTL field encodes
     * EXTENDED-RCODE(8) | VERSION(8) | FLAGS(16). The DO bit
     * ({@link #EDNS_FLAG_DO}) lives in the FLAGS portion.
     *
     * @param udpPayloadSize the maximum UDP payload size
     * @param ednsFlags the EDNS flags (e.g. {@link #EDNS_FLAG_DO})
     * @param optionData the EDNS0 option data (may be empty)
     * @return the OPT pseudo-record
     */
    public static DNSResourceRecord opt(int udpPayloadSize,
                                         int ednsFlags,
                                         byte[] optionData) {
        // RFC 6891 section 6.1.3: TTL = extRCODE(8) | version(8) | flags(16)
        int ttl = (ednsFlags & 0xFFFF);
        return new DNSResourceRecord("", DNSType.OPT,
                DNSType.OPT.getValue(),
                null, udpPayloadSize & 0xFFFF,
                ttl, optionData);
    }

    /**
     * Returns the UDP payload size from an OPT record.
     * RFC 6891 section 6.1.2: the CLASS field of the OPT record
     * encodes the sender's UDP payload size.
     *
     * @return the UDP payload size
     * @throws IllegalStateException if not an OPT record
     */
    public int getUdpPayloadSize() {
        if (type != DNSType.OPT) {
            throw new IllegalStateException("Not an OPT record: " + type);
        }
        return rawClass;
    }

    /**
     * Returns the EDNS flags from an OPT record.
     * RFC 6891 section 6.1.3: the lower 16 bits of the TTL field
     * encode the EDNS flags (DO bit is bit 15).
     *
     * @return the EDNS flags
     * @throws IllegalStateException if not an OPT record
     */
    public int getEDNSFlags() {
        if (type != DNSType.OPT) {
            throw new IllegalStateException("Not an OPT record: " + type);
        }
        return ttl & 0xFFFF;
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
            throw new IllegalStateException("Not an address record: " + type);
        }
        try {
            return InetAddress.getByAddress(rdata);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Invalid address data", e);
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
            throw new IllegalStateException("Not a name record: " + type);
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
            throw new IllegalStateException("Not a TXT record: " + type);
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
            throw new IllegalStateException("Not an MX record: " + type);
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
            throw new IllegalStateException("Not an MX record: " + type);
        }
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.getShort(); // skip preference
        return DNSMessage.decodeName(buf, ByteBuffer.wrap(rdata));
    }

    /**
     * Returns the SRV priority. RFC 2782: lower values are preferred.
     *
     * @return the priority value
     * @throws IllegalStateException if this is not an SRV record
     */
    public int getSRVPriority() {
        if (type != DNSType.SRV) {
            throw new IllegalStateException("Not an SRV record: " + type);
        }
        return ((rdata[0] & 0xFF) << 8) | (rdata[1] & 0xFF);
    }

    /**
     * Returns the SRV weight. RFC 2782: used for load balancing among
     * entries with equal priority. Higher values receive proportionally
     * more traffic.
     *
     * @return the weight value
     * @throws IllegalStateException if this is not an SRV record
     */
    public int getSRVWeight() {
        if (type != DNSType.SRV) {
            throw new IllegalStateException("Not an SRV record: " + type);
        }
        return ((rdata[2] & 0xFF) << 8) | (rdata[3] & 0xFF);
    }

    /**
     * Returns the SRV port. RFC 2782: the port on the target host.
     *
     * @return the port number
     * @throws IllegalStateException if this is not an SRV record
     */
    public int getSRVPort() {
        if (type != DNSType.SRV) {
            throw new IllegalStateException("Not an SRV record: " + type);
        }
        return ((rdata[4] & 0xFF) << 8) | (rdata[5] & 0xFF);
    }

    /**
     * Returns the SRV target hostname. RFC 2782: the domain name of the
     * host providing the service.
     *
     * @return the target hostname
     * @throws IllegalStateException if this is not an SRV record
     */
    public String getSRVTarget() {
        if (type != DNSType.SRV) {
            throw new IllegalStateException("Not an SRV record: " + type);
        }
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.position(6); // skip priority + weight + port
        return DNSMessage.decodeName(buf, ByteBuffer.wrap(rdata));
    }

    // -- RRSIG RDATA accessors (RFC 4034 section 3.1) --

    private void checkRRSIG() {
        if (type != DNSType.RRSIG) {
            throw new IllegalStateException("Not an RRSIG record: " + type);
        }
    }

    /**
     * Returns the type covered by this RRSIG.
     * RFC 4034 section 3.1: first 2 octets of RRSIG RDATA.
     *
     * @return the covered type value
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public int getRRSIGTypeCovered() {
        checkRRSIG();
        return ((rdata[0] & 0xFF) << 8) | (rdata[1] & 0xFF);
    }

    /**
     * Returns the DNSSEC algorithm number.
     * RFC 4034 section 3.1: octet 2 of RRSIG RDATA.
     *
     * @return the algorithm number
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public int getRRSIGAlgorithm() {
        checkRRSIG();
        return rdata[2] & 0xFF;
    }

    /**
     * Returns the number of labels in the original owner name.
     * RFC 4034 section 3.1: octet 3 of RRSIG RDATA.
     *
     * @return the label count
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public int getRRSIGLabels() {
        checkRRSIG();
        return rdata[3] & 0xFF;
    }

    /**
     * Returns the original TTL of the covered RRset.
     * RFC 4034 section 3.1: octets 4-7 of RRSIG RDATA.
     *
     * @return the original TTL in seconds
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public int getRRSIGOriginalTTL() {
        checkRRSIG();
        return ((rdata[4] & 0xFF) << 24) | ((rdata[5] & 0xFF) << 16)
                | ((rdata[6] & 0xFF) << 8) | (rdata[7] & 0xFF);
    }

    /**
     * Returns the signature expiration time as seconds since epoch.
     * RFC 4034 section 3.1: octets 8-11 of RRSIG RDATA.
     *
     * @return the expiration time (unsigned 32-bit)
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public long getRRSIGExpiration() {
        checkRRSIG();
        return ((long) (rdata[8] & 0xFF) << 24) | ((rdata[9] & 0xFF) << 16)
                | ((rdata[10] & 0xFF) << 8) | (rdata[11] & 0xFF);
    }

    /**
     * Returns the signature inception time as seconds since epoch.
     * RFC 4034 section 3.1: octets 12-15 of RRSIG RDATA.
     *
     * @return the inception time (unsigned 32-bit)
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public long getRRSIGInception() {
        checkRRSIG();
        return ((long) (rdata[12] & 0xFF) << 24) | ((rdata[13] & 0xFF) << 16)
                | ((rdata[14] & 0xFF) << 8) | (rdata[15] & 0xFF);
    }

    /**
     * Returns the key tag identifying the DNSKEY used to generate
     * this signature.
     * RFC 4034 section 3.1: octets 16-17 of RRSIG RDATA.
     *
     * @return the key tag
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public int getRRSIGKeyTag() {
        checkRRSIG();
        return ((rdata[16] & 0xFF) << 8) | (rdata[17] & 0xFF);
    }

    /**
     * Returns the signer's domain name.
     * RFC 4034 section 3.1: wire-format name starting at octet 18.
     *
     * @return the signer name
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public String getRRSIGSignerName() {
        checkRRSIG();
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.position(18);
        return DNSMessage.decodeName(buf, buf);
    }

    /**
     * Returns the cryptographic signature bytes.
     * RFC 4034 section 3.1: everything after the signer name.
     *
     * @return the signature bytes
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public byte[] getRRSIGSignature() {
        checkRRSIG();
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.position(18);
        DNSMessage.decodeName(buf, buf);
        byte[] sig = new byte[buf.remaining()];
        buf.get(sig);
        return sig;
    }

    /**
     * Returns the RRSIG RDATA bytes before the signature
     * (type covered through signer name), used for signature
     * verification input.
     * RFC 4034 section 3.1.8.1.
     *
     * @return the RRSIG header bytes (octets 0 through end of signer name)
     * @throws IllegalStateException if this is not an RRSIG record
     */
    public byte[] getRRSIGHeaderBytes() {
        checkRRSIG();
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.position(18);
        DNSMessage.decodeName(buf, buf);
        int headerLen = buf.position();
        byte[] header = new byte[headerLen];
        System.arraycopy(rdata, 0, header, 0, headerLen);
        return header;
    }

    // -- DNSKEY RDATA accessors (RFC 4034 section 2.1) --

    private void checkDNSKEY() {
        if (type != DNSType.DNSKEY) {
            throw new IllegalStateException("Not a DNSKEY record: " + type);
        }
    }

    /**
     * Returns the DNSKEY flags.
     * RFC 4034 section 2.1.1: first 2 octets.
     *
     * @return the flags value
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public int getDNSKEYFlags() {
        checkDNSKEY();
        return ((rdata[0] & 0xFF) << 8) | (rdata[1] & 0xFF);
    }

    /**
     * Returns the DNSKEY protocol field.
     * RFC 4034 section 2.1.2: octet 2 (must be 3).
     *
     * @return the protocol value
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public int getDNSKEYProtocol() {
        checkDNSKEY();
        return rdata[2] & 0xFF;
    }

    /**
     * Returns the DNSKEY algorithm number.
     * RFC 4034 section 2.1.3: octet 3.
     *
     * @return the algorithm number
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public int getDNSKEYAlgorithm() {
        checkDNSKEY();
        return rdata[3] & 0xFF;
    }

    /**
     * Returns the public key material.
     * RFC 4034 section 2.1.4: octets 4 onward.
     *
     * @return the raw public key bytes
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public byte[] getDNSKEYPublicKey() {
        checkDNSKEY();
        byte[] key = new byte[rdata.length - 4];
        System.arraycopy(rdata, 4, key, 0, key.length);
        return key;
    }

    /**
     * Returns true if this is a zone key (bit 7 of flags).
     * RFC 4034 section 2.1.1.
     *
     * @return true if zone key flag is set
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public boolean isDNSKEYZoneKey() {
        return (getDNSKEYFlags() & 0x0100) != 0;
    }

    /**
     * Returns true if this is a secure entry point (bit 15 of flags).
     * RFC 4034 section 2.1.1: SEP flag indicates a KSK.
     *
     * @return true if SEP flag is set
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public boolean isDNSKEYSecureEntryPoint() {
        return (getDNSKEYFlags() & 0x0001) != 0;
    }

    /**
     * Computes the key tag for this DNSKEY record.
     * RFC 4034 Appendix B: running sum over the DNSKEY RDATA.
     *
     * @return the key tag (0-65535)
     * @throws IllegalStateException if this is not a DNSKEY record
     */
    public int computeKeyTag() {
        checkDNSKEY();
        long ac = 0;
        for (int i = 0; i < rdata.length; i++) {
            if ((i & 1) == 0) {
                ac += (rdata[i] & 0xFFL) << 8;
            } else {
                ac += rdata[i] & 0xFFL;
            }
        }
        ac += (ac >> 16) & 0xFFFF;
        return (int) (ac & 0xFFFF);
    }

    // -- DS RDATA accessors (RFC 4034 section 5.1) --

    private void checkDS() {
        if (type != DNSType.DS) {
            throw new IllegalStateException("Not a DS record: " + type);
        }
    }

    /**
     * Returns the DS key tag.
     * RFC 4034 section 5.1: first 2 octets.
     *
     * @return the key tag
     * @throws IllegalStateException if this is not a DS record
     */
    public int getDSKeyTag() {
        checkDS();
        return ((rdata[0] & 0xFF) << 8) | (rdata[1] & 0xFF);
    }

    /**
     * Returns the DS algorithm number.
     * RFC 4034 section 5.1: octet 2.
     *
     * @return the algorithm number
     * @throws IllegalStateException if this is not a DS record
     */
    public int getDSAlgorithm() {
        checkDS();
        return rdata[2] & 0xFF;
    }

    /**
     * Returns the DS digest type.
     * RFC 4034 section 5.1: octet 3.
     *
     * @return the digest type (1=SHA-1, 2=SHA-256, 4=SHA-384)
     * @throws IllegalStateException if this is not a DS record
     */
    public int getDSDigestType() {
        checkDS();
        return rdata[3] & 0xFF;
    }

    /**
     * Returns the DS digest bytes.
     * RFC 4034 section 5.1: octets 4 onward.
     *
     * @return the digest
     * @throws IllegalStateException if this is not a DS record
     */
    public byte[] getDSDigest() {
        checkDS();
        byte[] digest = new byte[rdata.length - 4];
        System.arraycopy(rdata, 4, digest, 0, digest.length);
        return digest;
    }

    // -- NSEC RDATA accessors (RFC 4034 section 4.1) --

    /**
     * Returns the next domain name from an NSEC record.
     * RFC 4034 section 4.1: wire-format name at the start of RDATA.
     *
     * @return the next domain name
     * @throws IllegalStateException if this is not an NSEC record
     */
    public String getNSECNextDomainName() {
        if (type != DNSType.NSEC) {
            throw new IllegalStateException("Not an NSEC record: " + type);
        }
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        return DNSMessage.decodeName(buf, buf);
    }

    /**
     * Returns the type bit maps from an NSEC record as a list of
     * present RR type values.
     * RFC 4034 section 4.1.2: window block + bitmap encoding.
     *
     * @return list of type values present in this NSEC
     * @throws IllegalStateException if this is not an NSEC record
     */
    public List<Integer> getNSECTypeBitMaps() {
        if (type != DNSType.NSEC) {
            throw new IllegalStateException("Not an NSEC record: " + type);
        }
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        DNSMessage.decodeName(buf, buf);
        return parseTypeBitMaps(buf);
    }

    // -- NSEC3 RDATA accessors (RFC 5155 section 3.2) --

    private void checkNSEC3() {
        if (type != DNSType.NSEC3) {
            throw new IllegalStateException("Not an NSEC3 record: " + type);
        }
    }

    /**
     * Returns the NSEC3 hash algorithm.
     * RFC 5155 section 3.2: octet 0 (1 = SHA-1).
     *
     * @return the hash algorithm number
     * @throws IllegalStateException if this is not an NSEC3 record
     */
    public int getNSEC3HashAlgorithm() {
        checkNSEC3();
        return rdata[0] & 0xFF;
    }

    /**
     * Returns the NSEC3 flags.
     * RFC 5155 section 3.2: octet 1 (bit 0 = Opt-Out).
     *
     * @return the flags byte
     * @throws IllegalStateException if this is not an NSEC3 record
     */
    public int getNSEC3Flags() {
        checkNSEC3();
        return rdata[1] & 0xFF;
    }

    /**
     * Returns the NSEC3 iteration count.
     * RFC 5155 section 3.2: octets 2-3.
     *
     * @return the iteration count
     * @throws IllegalStateException if this is not an NSEC3 record
     */
    public int getNSEC3Iterations() {
        checkNSEC3();
        return ((rdata[2] & 0xFF) << 8) | (rdata[3] & 0xFF);
    }

    /**
     * Returns the NSEC3 salt.
     * RFC 5155 section 3.2: length at octet 4, data follows.
     *
     * @return the salt bytes (may be empty)
     * @throws IllegalStateException if this is not an NSEC3 record
     */
    public byte[] getNSEC3Salt() {
        checkNSEC3();
        int saltLen = rdata[4] & 0xFF;
        byte[] salt = new byte[saltLen];
        if (saltLen > 0) {
            System.arraycopy(rdata, 5, salt, 0, saltLen);
        }
        return salt;
    }

    /**
     * Returns the next hashed owner name in binary form.
     * RFC 5155 section 3.2: length at octet 5+saltLen,
     * followed by the hash value.
     *
     * @return the next hashed owner name bytes
     * @throws IllegalStateException if this is not an NSEC3 record
     */
    public byte[] getNSEC3NextHashedOwner() {
        checkNSEC3();
        int saltLen = rdata[4] & 0xFF;
        int hashOffset = 5 + saltLen;
        int hashLen = rdata[hashOffset] & 0xFF;
        byte[] hash = new byte[hashLen];
        System.arraycopy(rdata, hashOffset + 1, hash, 0, hashLen);
        return hash;
    }

    /**
     * Returns the type bit maps from an NSEC3 record.
     * RFC 5155 section 3.2: follows the next hashed owner name.
     *
     * @return list of type values present in this NSEC3
     * @throws IllegalStateException if this is not an NSEC3 record
     */
    public List<Integer> getNSEC3TypeBitMaps() {
        checkNSEC3();
        int saltLen = rdata[4] & 0xFF;
        int hashOffset = 5 + saltLen;
        int hashLen = rdata[hashOffset] & 0xFF;
        int bitmapOffset = hashOffset + 1 + hashLen;
        ByteBuffer buf = ByteBuffer.wrap(rdata);
        buf.position(bitmapOffset);
        return parseTypeBitMaps(buf);
    }

    // -- NSEC3PARAM RDATA accessors (RFC 5155 section 4.2) --

    private void checkNSEC3PARAM() {
        if (type != DNSType.NSEC3PARAM) {
            throw new IllegalStateException("Not an NSEC3PARAM record: " + type);
        }
    }

    /**
     * Returns the NSEC3PARAM hash algorithm.
     * RFC 5155 section 4.2: octet 0.
     *
     * @return the hash algorithm number
     * @throws IllegalStateException if this is not an NSEC3PARAM record
     */
    public int getNSEC3PARAMHashAlgorithm() {
        checkNSEC3PARAM();
        return rdata[0] & 0xFF;
    }

    /**
     * Returns the NSEC3PARAM flags.
     * RFC 5155 section 4.2: octet 1.
     *
     * @return the flags byte
     * @throws IllegalStateException if this is not an NSEC3PARAM record
     */
    public int getNSEC3PARAMFlags() {
        checkNSEC3PARAM();
        return rdata[1] & 0xFF;
    }

    /**
     * Returns the NSEC3PARAM iteration count.
     * RFC 5155 section 4.2: octets 2-3.
     *
     * @return the iteration count
     * @throws IllegalStateException if this is not an NSEC3PARAM record
     */
    public int getNSEC3PARAMIterations() {
        checkNSEC3PARAM();
        return ((rdata[2] & 0xFF) << 8) | (rdata[3] & 0xFF);
    }

    /**
     * Returns the NSEC3PARAM salt.
     * RFC 5155 section 4.2: length at octet 4, data follows.
     *
     * @return the salt bytes (may be empty)
     * @throws IllegalStateException if this is not an NSEC3PARAM record
     */
    public byte[] getNSEC3PARAMSalt() {
        checkNSEC3PARAM();
        int saltLen = rdata[4] & 0xFF;
        byte[] salt = new byte[saltLen];
        if (saltLen > 0) {
            System.arraycopy(rdata, 5, salt, 0, saltLen);
        }
        return salt;
    }

    // -- Type bit map parser (shared by NSEC and NSEC3) --

    /**
     * Parses the type bit maps format used by NSEC and NSEC3 records.
     * RFC 4034 section 4.1.2: each window is a window number (1 byte),
     * bitmap length (1 byte), then bitmap bytes. Bit N of window W
     * indicates type (W * 256 + N) is present.
     */
    static List<Integer> parseTypeBitMaps(ByteBuffer buf) {
        List<Integer> types = new ArrayList<>();
        while (buf.hasRemaining()) {
            int window = buf.get() & 0xFF;
            if (!buf.hasRemaining()) {
                break;
            }
            int bitmapLen = buf.get() & 0xFF;
            if (buf.remaining() < bitmapLen) {
                break;
            }
            for (int i = 0; i < bitmapLen; i++) {
                int octet = buf.get() & 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    if ((octet & (0x80 >> bit)) != 0) {
                        types.add(window * 256 + i * 8 + bit);
                    }
                }
            }
        }
        return types;
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
               rawType == that.rawType &&
               rawClass == that.rawClass &&
               name.equalsIgnoreCase(that.name) &&
               Arrays.equals(rdata, that.rdata);
    }

    @Override
    public int hashCode() {
        int result = name.toLowerCase().hashCode();
        result = 31 * result + rawType;
        result = 31 * result + rawClass;
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
        sb.append(dnsClass != null ? dnsClass : ("CLASS" + rawClass));
        sb.append(" ");
        sb.append(type != null ? type : ("TYPE" + rawType));

        if (type == null) {
            sb.append(" [");
            sb.append(rdata.length);
            sb.append(" bytes]");
            return sb.toString();
        }

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
                case SRV:
                    sb.append(" ");
                    sb.append(getSRVPriority());
                    sb.append(" ");
                    sb.append(getSRVWeight());
                    sb.append(" ");
                    sb.append(getSRVPort());
                    sb.append(" ");
                    sb.append(getSRVTarget());
                    break;
                case RRSIG:
                    DNSType covered = DNSType.fromValue(getRRSIGTypeCovered());
                    sb.append(" ");
                    sb.append(covered != null ? covered : ("TYPE" + getRRSIGTypeCovered()));
                    sb.append(" ");
                    sb.append(getRRSIGAlgorithm());
                    sb.append(" ");
                    sb.append(getRRSIGLabels());
                    sb.append(" ");
                    sb.append(getRRSIGOriginalTTL());
                    sb.append(" tag=");
                    sb.append(getRRSIGKeyTag());
                    sb.append(" ");
                    sb.append(getRRSIGSignerName());
                    break;
                case DNSKEY:
                    sb.append(" ");
                    sb.append(getDNSKEYFlags());
                    sb.append(" ");
                    sb.append(getDNSKEYProtocol());
                    sb.append(" ");
                    sb.append(getDNSKEYAlgorithm());
                    sb.append(" tag=");
                    sb.append(computeKeyTag());
                    break;
                case DS:
                    sb.append(" ");
                    sb.append(getDSKeyTag());
                    sb.append(" ");
                    sb.append(getDSAlgorithm());
                    sb.append(" ");
                    sb.append(getDSDigestType());
                    break;
                case NSEC:
                    sb.append(" ");
                    sb.append(getNSECNextDomainName());
                    break;
                case NSEC3:
                    sb.append(" ");
                    sb.append(getNSEC3HashAlgorithm());
                    sb.append(" ");
                    sb.append(getNSEC3Flags());
                    sb.append(" ");
                    sb.append(getNSEC3Iterations());
                    break;
                case NSEC3PARAM:
                    sb.append(" ");
                    sb.append(getNSEC3PARAMHashAlgorithm());
                    sb.append(" ");
                    sb.append(getNSEC3PARAMFlags());
                    sb.append(" ");
                    sb.append(getNSEC3PARAMIterations());
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
