/*
 * DNSBailiwick.java
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

import java.util.ArrayList;
import java.util.List;

/**
 * Bailiwick checks for DNS responses.
 *
 * <p>Prevents trusting records from a child zone when resolving a parent
 * name (cache poisoning class attacks).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DNSBailiwick {

    private DNSBailiwick() {
    }

    /**
     * Normalizes a domain name for comparison (lowercase, no trailing dot).
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String n = name.toLowerCase();
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /**
     * Case-insensitive domain name equality.
     */
    public static boolean namesEqual(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    /**
     * Returns true if {@code recordOwner} is the same as {@code qname} or a
     * subdomain of it (record owner is within the bailiwick of the query).
     */
    public static boolean isWithinBailiwick(String recordOwner, String qname) {
        String owner = normalize(recordOwner);
        String query = normalize(qname);
        if (owner.isEmpty() || query.isEmpty()) {
            return false;
        }
        if (owner.equals(query)) {
            return true;
        }
        return owner.endsWith("." + query);
    }

    /**
     * Filters answer records to those owned within the query name's bailiwick.
     */
    public static List<DNSResourceRecord> filterAnswersInBailiwick(
            String qname, List<DNSResourceRecord> answers) {
        if (answers == null || answers.isEmpty()) {
            return answers;
        }
        List<DNSResourceRecord> filtered = new ArrayList<>();
        for (DNSResourceRecord rr : answers) {
            if (isWithinBailiwick(rr.getName(), qname)) {
                filtered.add(rr);
            }
        }
        return filtered;
    }

    /**
     * Filters authority records to those owned within the query name's bailiwick.
     */
    public static List<DNSResourceRecord> filterAuthoritiesInBailiwick(
            String qname, List<DNSResourceRecord> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            return authorities;
        }
        List<DNSResourceRecord> filtered = new ArrayList<>();
        for (DNSResourceRecord rr : authorities) {
            if (isWithinBailiwick(rr.getName(), qname)) {
                filtered.add(rr);
            }
        }
        return filtered;
    }
}
