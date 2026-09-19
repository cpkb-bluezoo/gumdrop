/*
 * ArcDmarcPolicy.java
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
 * Pluggable trust policy for ARC-aware DMARC evaluation (RFC 8617). The
 * implementation decides whether a validated chain ({@code cv=pass})
 * supplies authentication identifiers that {@link DmarcValidator} should
 * use instead of this hop's raw SPF/DKIM results.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DmarcValidator#setArcDmarcPolicy(ArcDmarcPolicy)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public interface ArcDmarcPolicy {

    /**
     * Returns authentication inputs for DMARC alignment, or {@code null} to
     * keep this hop's local SPF/DKIM results unchanged.
     *
     * @param chain validated ARC chain (never null)
     * @param fromDomain RFC5322.From domain
     * @param localSpf SPF result at this hop
     * @param localSpfDomain envelope domain checked by SPF
     * @param localDkim DKIM result at this hop
     * @param localDkimDomain DKIM {@code d=} domain at this hop
     * @return snapshot to use for alignment, or null
     */
    ArcAuthSnapshot authSnapshot(ArcValidationResult chain, String fromDomain,
                                 SpfResult localSpf, String localSpfDomain,
                                 DkimResult localDkim, String localDkimDomain);

}
