/*
 * package-info.java
 * Copyright (C) 2005, 2025 Chris Burdess
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

/**
 * General-purpose utilities used throughout gumdrop: I/O and SSL/TLS
 * helpers, and small standalone algorithms.
 *
 * <p>{@link org.bluezoo.gumdrop.util.CIDRNetwork} matches an address
 * against a CIDR block; {@link org.bluezoo.gumdrop.util.SNIKeyManager}
 * is an SSL {@code KeyManager} that selects a certificate by SNI
 * hostname, for virtual hosting; {@link
 * org.bluezoo.gumdrop.util.EmptyX509TrustManager} accepts any
 * certificate (test/dev use only); {@link
 * org.bluezoo.gumdrop.util.LaconicFormatter} is a compact {@code
 * java.util.logging} formatter.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
package org.bluezoo.gumdrop.util;
