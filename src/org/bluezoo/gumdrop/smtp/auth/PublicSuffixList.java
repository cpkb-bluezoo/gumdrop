/*
 * PublicSuffixList.java
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

package org.bluezoo.gumdrop.smtp.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Mozilla Public Suffix List (SEC-048), used to compute the DMARC
 * "organizational domain" (RFC 7489 section 3.2) correctly for any TLD,
 * not just a hardcoded handful of common ones.
 *
 * <p>Bundles {@code public_suffix_list.dat} as a classpath resource,
 * fetched from <a href="https://publicsuffix.org/list/public_suffix_list.dat">
 * publicsuffix.org</a> — the same authoritative source browsers and other
 * DMARC implementations use. Run {@code ant update-psl} to refresh it.
 *
 * <p>Both the ICANN and PRIVATE sections of the list are loaded. DMARC
 * alignment cares about which organization actually controls a domain's
 * DNS delegation, not just ICANN-delegated TLDs — a domain under a
 * PRIVATE-section entry like {@code github.io} or {@code herokuapp.com}
 * is just as much a distinct registrable entity as one under a
 * ICANN-section ccTLD like {@code co.uk}, so excluding PRIVATE entries
 * (as browsers do for cookie-scoping security) would silently
 * mis-align real-world DMARC checks for those domains.
 *
 * <p>Implements the standard PSL matching algorithm
 * (<a href="https://publicsuffix.org/list/">publicsuffix.org/list</a>):
 * find the longest matching rule (by label count) against a domain's
 * labels compared right-to-left, where a wildcard rule ({@code *.example})
 * matches any single label in that position, and an exception rule
 * ({@code !city.example}) overrides a wildcard rule at the same position.
 * If no rule matches at all, the implicit {@code *} rule applies (the
 * public suffix is just the last label).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://publicsuffix.org/list/">Public Suffix List</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7489#section-3.2">RFC 7489 section 3.2</a>
 */
class PublicSuffixList {

    private static final Logger LOGGER = Logger.getLogger(PublicSuffixList.class.getName());
    private static final String RESOURCE = "public_suffix_list.dat";

    /** Lazily-initialized singleton; resource parsing happens once per JVM. */
    private static final PublicSuffixList INSTANCE = new PublicSuffixList();

    static PublicSuffixList getInstance() {
        return INSTANCE;
    }

    /** Exact-match rules, e.g. "co.uk" -> matches domains ending in ".co.uk" or exactly "co.uk". */
    private final Set<String> rules = new HashSet<String>();
    /** Wildcard rules' parent suffix, e.g. "*.ck" stored as "ck". */
    private final Set<String> wildcardRules = new HashSet<String>();
    /** Exception rules, e.g. "!www.ck" stored as "www.ck". */
    private final Set<String> exceptionRules = new HashSet<String>();

    private PublicSuffixList() {
        try (InputStream in = PublicSuffixList.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warning("public_suffix_list.dat resource not found on classpath; "
                        + "DMARC organizational domain computation will fall back to the "
                        + "last-two-labels heuristic for every domain");
                return;
            }
            parse(in);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load public_suffix_list.dat", e);
        }
    }

    private void parse(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            // Comments/annotations after whitespace on a rule line are not
            // part of the PSL format; every non-comment, non-blank line is
            // a bare rule.
            if (line.startsWith("!")) {
                addBothForms(exceptionRules, line.substring(1));
            } else if (line.startsWith("*.")) {
                addBothForms(wildcardRules, line.substring(2));
            } else {
                addBothForms(rules, line);
            }
        }
    }

    /**
     * IDN TLD rules in the .dat file are written in native Unicode (e.g.
     * {@code 公司.cn}), but domains extracted from RFC 5322 message
     * headers are ASCII/punycode (e.g. {@code xn--55qx5d.cn}) — email
     * header field values aren't Unicode. Store both forms so matching
     * works regardless of which one a given domain arrives in.
     */
    private static void addBothForms(Set<String> set, String rule) {
        String lower = rule.toLowerCase();
        set.add(lower);
        try {
            set.add(java.net.IDN.toASCII(lower).toLowerCase());
        } catch (IllegalArgumentException e) {
            // Not a valid IDN label (or already ASCII with nothing to
            // convert) - the Unicode form added above is enough.
        }
    }

    /**
     * Returns the registrable domain (DMARC "organizational domain") for
     * the given domain, per RFC 7489 section 3.2 — the public suffix
     * plus exactly one additional label.
     *
     * @param domain a lowercase, non-null domain name
     * @return the registrable domain, or {@code domain} itself if it has
     *         two or fewer labels (nothing more specific to strip)
     */
    String getRegistrableDomain(String domain) {
        String[] labels = domain.split("\\.");
        if (labels.length <= 1) {
            return domain;
        }

        int publicSuffixLabels = countPublicSuffixLabels(labels);
        int registrableLabels = publicSuffixLabels + 1;
        if (registrableLabels >= labels.length) {
            // Domain is already at or below the organizational level
            // (e.g. "example.co.uk" itself, or malformed input with fewer
            // labels than the matched suffix implies).
            return domain;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = labels.length - registrableLabels; i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    /**
     * Implements the PSL prevailing-rule algorithm and returns how many
     * trailing labels of {@code labels} the public suffix consists of.
     */
    private int countPublicSuffixLabels(String[] labels) {
        int best = 1; // implicit "*" rule: public suffix is the last label
        int bestCount = 1;
        boolean exceptionMatched = false;

        for (int labelCount = 1; labelCount <= labels.length; labelCount++) {
            String candidate = join(labels, labels.length - labelCount);
            if (exceptionRules.contains(candidate)) {
                // Per the PSL algorithm, an exception rule always prevails
                // over any other matching rule, regardless of label count
                // — it's not a "longest wins" contest once one matches.
                // The public suffix is the exception's labels minus the
                // leftmost one (the exception carves the labels it names
                // out of the wildcard's coverage, e.g. "!city.kawasaki.jp"
                // against wildcard "*.kawasaki.jp" -> public suffix is
                // "kawasaki.jp").
                best = labelCount - 1;
                exceptionMatched = true;
                continue;
            }
            if (exceptionMatched) {
                continue;
            }
            if (labelCount >= 2) {
                String parent = join(labels, labels.length - labelCount + 1);
                if (wildcardRules.contains(parent) && labelCount >= bestCount) {
                    best = labelCount;
                    bestCount = labelCount;
                }
            }
            if (rules.contains(candidate) && labelCount >= bestCount) {
                best = labelCount;
                bestCount = labelCount;
            }
        }
        return best;
    }

    private static String join(String[] labels, int fromIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }
}
