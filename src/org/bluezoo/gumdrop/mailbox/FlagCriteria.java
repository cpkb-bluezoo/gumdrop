/*
 * FlagCriteria.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;

/**
 * A {@link SearchCriteria} matching messages that have a specific system
 * flag set, as produced by {@link SearchCriteria#hasFlag}.
 *
 * <p>A named, inspectable class rather than an anonymous one (issue
 * #304): {@code org.bluezoo.gumdrop.mailbox.index.MessageIndex} needs to
 * recognise this specific shape of criteria via {@code instanceof} to
 * answer it from its flag sub-index instead of scanning every message.
 * Constructed only by {@link SearchCriteria}'s own factory methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class FlagCriteria implements SearchCriteria {

    private final Flag flag;

    FlagCriteria(Flag flag) {
        this.flag = flag;
    }

    /**
     * Returns the flag this criteria matches.
     *
     * @return the flag
     */
    public Flag getFlag() {
        return flag;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        return context.getFlags().contains(flag);
    }

    @Override
    public String toString() {
        return "FlagCriteria[" + flag + "]";
    }
}
