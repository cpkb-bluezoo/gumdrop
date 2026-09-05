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
 * Rate limiting shared by any {@link org.bluezoo.gumdrop.TCPListener},
 * protecting against connection flooding, brute-force authentication,
 * and general resource exhaustion.
 *
 * <p>{@link org.bluezoo.gumdrop.ratelimit.RateLimiter} is the core
 * thread-safe sliding-window limiter. {@link
 * org.bluezoo.gumdrop.ratelimit.ConnectionRateLimiter} applies it
 * per-IP, for both concurrent-connection caps and a connections-per-window
 * rate. {@link org.bluezoo.gumdrop.ratelimit.AuthenticationRateLimiter}
 * tracks failed authentication attempts (by IP, username, or both) with
 * exponential backoff and a configurable lockout threshold. Rate-limit
 * and lockout events integrate with {@link
 * org.bluezoo.gumdrop.telemetry.ErrorCategory#RATE_LIMITED} and {@code
 * AUTHENTICATION_FAILED} when telemetry is configured.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.TCPListener
 * @see org.bluezoo.gumdrop.telemetry.ErrorCategory#RATE_LIMITED
 */
package org.bluezoo.gumdrop.ratelimit;
