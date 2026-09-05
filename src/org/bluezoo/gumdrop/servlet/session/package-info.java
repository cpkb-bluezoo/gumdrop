/*
 * package-info.java
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

/**
 * HTTP session management, with optional cluster replication.
 *
 * <p>{@link org.bluezoo.gumdrop.servlet.session.SessionManager} owns
 * session lifecycle (creation, retrieval, invalidation) for one context;
 * {@link org.bluezoo.gumdrop.servlet.session.SessionContext} and {@link
 * org.bluezoo.gumdrop.servlet.session.ClusterContainer} are the
 * interfaces the servlet {@code Context}/{@code Container} implement so
 * this package needs no direct dependency on those classes. Applications
 * interact with sessions only through the standard {@code HttpSession}
 * interface.
 *
 * <p>When clustering is enabled, sessions marked distributable replicate
 * across nodes over UDP multicast: delta replication (only changed
 * attributes are sent), fragmentation for large sessions, AES-256-GCM
 * encryption of all cluster traffic, and replay protection via sequence
 * numbers and timestamps. Primitive attributes are encoded directly in
 * protobuf; complex objects use Java serialization against a strict
 * class allowlist (the container property {@code
 * replication-allowed-classes}) rather than accepting arbitrary webapp
 * types. {@link org.bluezoo.gumdrop.servlet.session.ClusterMetrics}
 * reports replication, traffic, and security-event metrics when
 * telemetry is configured.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see javax.servlet.http.HttpSession
 * @see org.bluezoo.gumdrop.servlet.session.SessionManager
 */
package org.bluezoo.gumdrop.servlet.session;
