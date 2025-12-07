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
 *
 * <p>Each question specifies a domain name, record type, and class
 * to look up.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSQuestion {

    private final String name;
    private final DNSType type;
    private final DNSClass dnsClass;

    /**
     * Creates a new DNS question.
     *
     * @param name the domain name to query
     * @param type the record type
     * @param dnsClass the record class
     */
    public DNSQuestion(String name, DNSType type, DNSClass dnsClass) {
        this.name = name;
        this.type = type;
        this.dnsClass = dnsClass;
    }

    /**
     * Creates a new DNS question with IN class.
     *
     * @param name the domain name to query
     * @param type the record type
     */
    public DNSQuestion(String name, DNSType type) {
        this(name, type, DNSClass.IN);
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
