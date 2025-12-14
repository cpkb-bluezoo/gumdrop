/*
 * DMARCCallback.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * Callback interface for asynchronous DMARC evaluation results.
 *
 * <p>This interface enables non-blocking DMARC evaluation. The callback is
 * invoked when the DMARC policy has been fetched and evaluated against
 * the SPF and DKIM results.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DMARCValidator
 * @see DMARCResult
 */
public interface DMARCCallback {

    /**
     * Called when a DMARC evaluation completes.
     *
     * @param result the DMARC evaluation result
     * @param policy the DMARC policy (none, quarantine, reject), or null if no policy
     * @param fromDomain the RFC5322.From domain that was evaluated
     * @param verdict the combined authentication verdict based on DMARC policy
     */
    void dmarcResult(DMARCResult result, DMARCPolicy policy, String fromDomain, AuthVerdict verdict);

}


