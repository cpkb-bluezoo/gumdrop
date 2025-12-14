/*
 * SPFCallback.java
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
 * Callback interface for asynchronous SPF check results.
 *
 * <p>This interface enables non-blocking SPF validation. The callback is
 * invoked when the SPF check completes, which may involve multiple DNS
 * lookups for mechanisms like "include" or "redirect".
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see SPFValidator
 * @see SPFResult
 */
public interface SPFCallback {

    /**
     * Called when an SPF check completes.
     *
     * @param result the SPF check result
     * @param explanation optional explanation text from the SPF record, or null
     */
    void spfResult(SPFResult result, String explanation);

}


