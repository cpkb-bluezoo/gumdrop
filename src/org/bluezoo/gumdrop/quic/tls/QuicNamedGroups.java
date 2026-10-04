/*
 * QuicNamedGroups.java
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


package org.bluezoo.gumdrop.quic.tls;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bluezoo.gumdrop.crypto.NamedGroup;

/**
 * Resolves {@code QuicTransportFactory#setNamedGroups} against the
 * groups {@link NamedGroup} defines -- shared by {@link
 * QuicTlsClientEngine} and {@link QuicTlsServerEngine}, since the list
 * means the same thing to both: the groups this endpoint supports, in
 * its own preference order.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class QuicNamedGroups {

    private static final Logger LOGGER = Logger.getLogger(QuicNamedGroups.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.quic.L10N");

    private QuicNamedGroups() {
    }

    /**
     * Resolves a colon-separated {@code namedGroups} configuration
     * string of IANA TLS Supported Groups registry names, preserving the
     * configured order. An unrecognised name is logged and skipped; if
     * nothing configured is recognised at all, that is logged too and
     * the engine's default order applies.
     *
     * @param namedGroups the raw {@code QuicTransportFactory
     *                    #getNamedGroups()} value, or null
     * @return the groups to support, in order, or null for the
     *         engine's own default order
     */
    static List<NamedGroup> resolve(String namedGroups) {
        if (namedGroups == null || namedGroups.isEmpty()) {
            return null;
        }
        List<NamedGroup> resolved = new ArrayList<NamedGroup>();
        String[] names = namedGroups.split(":");
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (name.isEmpty()) {
                continue;
            }
            NamedGroup group = NamedGroup.fromName(name);
            if (group != null) {
                resolved.add(group);
            } else if (LOGGER.isLoggable(Level.WARNING)) {
                String message = MessageFormat.format(
                        L10N.getString("warn.unrecognized_named_group"), name);
                LOGGER.warning(message);
            }
        }
        if (resolved.isEmpty()) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                String message = MessageFormat.format(
                        L10N.getString("warn.named_groups_fallback"), namedGroups);
                LOGGER.warning(message);
            }
            return null;
        }
        return resolved;
    }

}
