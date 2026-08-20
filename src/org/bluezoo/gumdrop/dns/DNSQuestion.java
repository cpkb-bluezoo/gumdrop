/*
 * DNSQuestion.java
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

/**
 * A question in a DNS query.
 * RFC 1035 section 4.1.2 defines the question section format:
 * QNAME (variable), QTYPE (16-bit), QCLASS (16-bit).
 *
 * <p>Each question specifies a domain name, record type, and class
 * to look up. Name comparison is case-insensitive per RFC 1035 section 2.3.3.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSQuestion {

    private final String name;
    private final DNSType type;
    private final DNSClass dnsClass;
    private final boolean unicastResponseRequested;

    /**
     * Creates a new DNS question.
     *
     * @param name the domain name to query
     * @param type the record type
     * @param dnsClass the record class
     */
    public DNSQuestion(String name, DNSType type, DNSClass dnsClass) {
        this(name, type, dnsClass, false);
    }

    /**
     * Creates a new DNS question, optionally requesting a unicast
     * response. RFC 6762 section 5.4: in multicast DNS, the top bit of
     * the QCLASS field ("QU bit") asks the responder to reply via
     * unicast rather than to the multicast group. Meaningless outside
     * mDNS.
     *
     * @param name the domain name to query
     * @param type the record type
     * @param dnsClass the record class
     * @param unicastResponseRequested whether the QU bit is set
     */
    public DNSQuestion(String name, DNSType type, DNSClass dnsClass,
                        boolean unicastResponseRequested) {
        this.name = name;
        this.type = type;
        this.dnsClass = dnsClass;
        this.unicastResponseRequested = unicastResponseRequested;
    }

    /**
     * Creates a new DNS question with IN class.
     *
     * @param name the domain name to query
     * @param type the record type
     */
    public DNSQuestion(String name, DNSType type) {
        this(name, type, DNSClass.IN, false);
    }

    /**
     * Returns whether this question requested a unicast response
     * (the mDNS "QU bit", RFC 6762 section 5.4).
     *
     * @return true if a unicast response was requested
     */
    public boolean isUnicastResponseRequested() {
        return unicastResponseRequested;
    }

    /**
     * Returns the domain name being queried.
     *
     * @return the domain name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the record type being queried.
     *
     * @return the record type
     */
    public DNSType getType() {
        return type;
    }

    /**
     * Returns the record class being queried.
     *
     * @return the record class
     */
    public DNSClass getDNSClass() {
        return dnsClass;
    }

    // unicastResponseRequested is a per-query transport hint, not part
    // of question identity, so it is deliberately excluded here: two
    // otherwise-identical questions must still compare equal regardless
    // of the QU bit (e.g. for cache lookups and known-answer matching).
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DNSQuestion)) {
            return false;
        }
        DNSQuestion that = (DNSQuestion) o;
        return name.equalsIgnoreCase(that.name) &&
               type == that.type &&
               dnsClass == that.dnsClass;
    }

    @Override
    public int hashCode() {
        int result = name.toLowerCase().hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + dnsClass.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return name + " " + dnsClass + " " + type;
    }

}
