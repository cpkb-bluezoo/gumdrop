/*
 * ArcAuthSnapshot.java
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

/**
 * SPF/DKIM inputs for DMARC alignment after an {@link ArcDmarcPolicy}
 * has applied trust rules to a validated ARC chain.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcDmarcPolicy
 */
public class ArcAuthSnapshot {

    private SpfResult spfResult;
    private String spfDomain;
    private DkimResult dkimResult;
    private String dkimDomain;

    /**
     * Returns the SPF result to use for DMARC alignment, if set.
     *
     * @return SPF result, or null if this snapshot does not override SPF
     */
    public SpfResult getSpfResult() {
        return spfResult;
    }

    /**
     * Sets the SPF result for DMARC alignment.
     *
     * @param spfResult SPF result from the trusted ARC hop, or null to omit
     */
    public void setSpfResult(SpfResult spfResult) {
        this.spfResult = spfResult;
    }

    /**
     * Returns the domain SPF validated for alignment.
     *
     * @return envelope or HELO domain checked by SPF, or null
     */
    public String getSpfDomain() {
        return spfDomain;
    }

    /**
     * Sets the domain SPF validated for alignment.
     *
     * @param spfDomain domain to compare against the From domain, or null
     */
    public void setSpfDomain(String spfDomain) {
        this.spfDomain = spfDomain;
    }

    /**
     * Returns the DKIM result to use for DMARC alignment, if set.
     *
     * @return DKIM result, or null if this snapshot does not override DKIM
     */
    public DkimResult getDkimResult() {
        return dkimResult;
    }

    /**
     * Sets the DKIM result for DMARC alignment.
     *
     * @param dkimResult DKIM result from the trusted ARC hop, or null to omit
     */
    public void setDkimResult(DkimResult dkimResult) {
        this.dkimResult = dkimResult;
    }

    /**
     * Returns the DKIM signing domain ({@code d=}) for alignment.
     *
     * @return signing domain, or null
     */
    public String getDkimDomain() {
        return dkimDomain;
    }

    /**
     * Sets the DKIM signing domain ({@code d=}) for alignment.
     *
     * @param dkimDomain domain to compare against the From domain, or null
     */
    public void setDkimDomain(String dkimDomain) {
        this.dkimDomain = dkimDomain;
    }
}
