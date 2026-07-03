/*
 * DNSBailiwick.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.dns;

import java.util.ArrayList;
import java.util.List;

/**
 * Bailiwick checks for DNS responses.
 *
 * <p>Prevents trusting records from a child zone when resolving a parent
 * name (cache poisoning class attacks).
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
