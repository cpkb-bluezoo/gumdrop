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
 * Rate limiting framework for protecting Gumdrop servers from abuse.
 *
 * <p>This package provides reusable rate limiting components that can be
 * integrated into any Gumdrop server to protect against:
 * <ul>
 * <li>Connection flooding and DoS attacks</li>
 * <li>Brute-force authentication attempts</li>
 * <li>Resource exhaustion from abusive clients</li>
 * </ul>
 *
 * <h2>Components</h2>
 *
 * <h3>{@link org.bluezoo.gumdrop.ratelimit.RateLimiter}</h3>
 * <p>Core sliding window rate limiter that tracks events over a configurable
 * time window. Thread-safe and memory-efficient.</p>
 *
 * <h3>{@link org.bluezoo.gumdrop.ratelimit.ConnectionRateLimiter}</h3>
 * <p>IP-based connection rate limiting with support for:
 * <ul>
 * <li>Maximum concurrent connections per IP</li>
 * <li>Connection rate limiting (connections per time window)</li>
 * <li>Automatic cleanup of expired tracking data</li>
 * </ul>
 *
 * <h3>{@link org.bluezoo.gumdrop.ratelimit.AuthenticationRateLimiter}</h3>
 * <p>Failed authentication attempt tracking with:
 * <ul>
 * <li>Configurable failure threshold before lockout</li>
 * <li>Exponential backoff for repeat offenders</li>
 * <li>IP-based, username-based, or combined tracking</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <h3>Server Configuration</h3>
 * <pre>
 * &lt;server id="smtp" class="org.bluezoo.gumdrop.smtp.SMTPServer"&gt;
 *     &lt;!-- Connection rate limiting --&gt;
 *     &lt;property name="max-connections-per-ip"&gt;10&lt;/property&gt;
 *     &lt;property name="rate-limit"&gt;100/60s&lt;/property&gt;
 *     
 *     &lt;!-- Authentication rate limiting --&gt;
 *     &lt;property name="max-auth-failures"&gt;5&lt;/property&gt;
 *     &lt;property name="auth-lockout-time"&gt;5m&lt;/property&gt;
 * &lt;/server&gt;
 * </pre>
 *
 * <h3>Programmatic Usage</h3>
 * <pre>
 * // Connection rate limiting
 * ConnectionRateLimiter connLimiter = new ConnectionRateLimiter();
 * connLimiter.setMaxConcurrentPerIP(10);
 * connLimiter.setRateLimit("100/60s");
 * 
 * // In acceptConnection():
 * if (!connLimiter.allowConnection(clientIP)) {
 *     return false; // Reject connection
 * }
 * connLimiter.connectionOpened(clientIP);
 * 
 * // Authentication rate limiting
 * AuthenticationRateLimiter authLimiter = new AuthenticationRateLimiter();
 * authLimiter.setMaxFailures(5);
 * authLimiter.setLockoutTime("5m");
 * 
 * // In authentication handler:
 * if (authLimiter.isLocked(clientIP)) {
 *     return "Account temporarily locked";
 * }
 * 
 * if (authenticate(username, password)) {
 *     authLimiter.recordSuccess(clientIP);
 * } else {
 *     authLimiter.recordFailure(clientIP);
 * }
 * </pre>
 *
 * <h2>Telemetry Integration</h2>
 *
 * <p>Rate limiting events integrate with Gumdrop's telemetry system:
 * <ul>
 * <li>{@code ErrorCategory.RATE_LIMITED} - for connection rate limiting</li>
 * <li>{@code ErrorCategory.AUTHENTICATION_FAILED} - for auth lockouts</li>
 * </ul>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.Server#acceptConnection
 * @see org.bluezoo.gumdrop.telemetry.ErrorCategory#RATE_LIMITED
 */
package org.bluezoo.gumdrop.ratelimit;

