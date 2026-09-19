/*
 * ArcValidationResult.java
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

import java.util.Collections;
import java.util.List;

/**
 * Outcome of validating an ARC header chain on a message (RFC 8617).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcValidator
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public class ArcValidationResult {

    private final ArcCvResult chainCv;
    private final boolean malformed;
    private final List<ArcSet> sets;

    /**
     * Creates a validation result.
     *
     * @param chainCv overall chain validation
     * @param malformed true if headers were not well-formed
     * @param sets parsed sets (may be empty)
     */
    public ArcValidationResult(ArcCvResult chainCv, boolean malformed,
                             List<ArcSet> sets) {
        this.chainCv = chainCv;
        this.malformed = malformed;
        if (sets == null) {
            this.sets = Collections.emptyList();
        } else {
            this.sets = Collections.unmodifiableList(sets);
        }
    }

    /**
     * Returns the overall chain validation outcome after cryptographic checks.
     *
     * @return {@link ArcCvResult#NONE} if no ARC headers were present
     */
    public ArcCvResult getChainCv() {
        return chainCv;
    }

    /**
     * Returns whether ARC headers were present but not well-formed (for example
     * missing instance numbers or incomplete triplets).
     *
     * @return true if the header set could not be grouped into valid instances
     */
    public boolean isMalformed() {
        return malformed;
    }

    /**
     * Returns the parsed ARC instance sets in order ({@code i=1}, {@code i=2}, …).
     *
     * @return unmodifiable list of sets; empty if none were found
     */
    public List<ArcSet> getSets() {
        return sets;
    }
}
