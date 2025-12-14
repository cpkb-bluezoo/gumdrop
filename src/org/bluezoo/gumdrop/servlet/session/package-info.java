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
 * HTTP session management and cluster replication.
 *
 * <p>This package provides session management for Gumdrop's servlet container,
 * including support for distributed sessions across a cluster of nodes.
 *
 * <h2>Key Components</h2>
 *
 * <h3>{@link org.bluezoo.gumdrop.servlet.session.SessionManager}</h3>
 * <p>The main entry point for session management. Each servlet context creates
 * a SessionManager to handle session lifecycle, including creation, retrieval,
 * invalidation, and optional cluster replication.</p>
 *
 * <h3>{@link org.bluezoo.gumdrop.servlet.session.SessionContext}</h3>
 * <p>Interface implemented by the servlet context to provide session configuration
 * and listener access. This allows the session package to work with contexts
 * without a direct dependency on the Context class.</p>
 *
 * <h3>{@link org.bluezoo.gumdrop.servlet.session.ClusterContainer}</h3>
 * <p>Interface implemented by the servlet container to provide cluster
 * configuration and context lookup for distributed session replication.</p>
 *
 * <h2>Cluster Session Replication</h2>
 *
 * <p>When clustering is enabled, sessions marked as distributable are
 * replicated across all nodes using UDP multicast. Features include:</p>
 * <ul>
 *   <li>Delta replication: Only changed attributes are transmitted</li>
 *   <li>Message fragmentation: Large sessions are split across packets</li>
 *   <li>Replay protection: Sequence numbers and timestamps prevent attacks</li>
 *   <li>AES-256-GCM encryption: All cluster traffic is encrypted</li>
 *   <li>OpenTelemetry metrics: Counters and histograms for monitoring</li>
 * </ul>
 *
 * <h3>{@link org.bluezoo.gumdrop.servlet.session.ClusterMetrics}</h3>
 * <p>When telemetry is configured, the cluster provides metrics for session
 * replication, message traffic, fragmentation, and security events. Metrics
 * include node counts, session replication rates, bytes transferred, and
 * error counters for decryption failures, replay attacks, and timestamp
 * drift.</p>
 *
 * <h2>Usage</h2>
 *
 * <p>The session package is used internally by the servlet container. Web
 * applications interact with sessions through the standard {@code HttpSession}
 * interface obtained via {@code HttpServletRequest.getSession()}.</p>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see javax.servlet.http.HttpSession
 * @see org.bluezoo.gumdrop.servlet.session.SessionManager
 */
package org.bluezoo.gumdrop.servlet.session;


