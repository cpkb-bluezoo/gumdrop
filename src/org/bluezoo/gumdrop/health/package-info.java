/*
 * package-info.java
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

/**
 * A tiny built-in HTTP liveness/readiness endpoint for orchestrators
 * (Kubernetes probes, load-balancer health checks).
 *
 * <p>{@link org.bluezoo.gumdrop.health.HealthService} owns the
 * configuration; {@link org.bluezoo.gumdrop.health.HealthListener} is
 * the transport listener; {@link
 * org.bluezoo.gumdrop.health.HealthProtocolHandler} answers each
 * request with the current health status.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.health.HealthService
 */
package org.bluezoo.gumdrop.health;
