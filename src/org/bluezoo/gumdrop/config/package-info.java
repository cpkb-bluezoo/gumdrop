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
 * Default XML configuration reader and dependency-injection container,
 * behind the {@link org.bluezoo.gumdrop.GumdropConfigurator} SPI.
 *
 * <p>{@link org.bluezoo.gumdrop.config.ConfigurationParser} reads a
 * {@code gumdroprc} file into {@link
 * org.bluezoo.gumdrop.config.ComponentDefinition}s (each backed by
 * {@link org.bluezoo.gumdrop.config.PropertyDefinition}s, including
 * {@link org.bluezoo.gumdrop.config.ListValue}/{@link
 * org.bluezoo.gumdrop.config.MapValue} for structured properties and
 * {@link org.bluezoo.gumdrop.config.ComponentReference} for
 * cross-component {@code ref} links) into a {@link
 * org.bluezoo.gumdrop.config.ParseResult}. {@link
 * org.bluezoo.gumdrop.config.DefaultConfigurator} then instantiates and
 * wires those definitions using a {@link
 * org.bluezoo.gumdrop.config.ComponentRegistry} as its built-in DI
 * container. Applications wanting a different DI framework (Guice,
 * Spring, CDI) implement {@code GumdropConfigurator} instead of using
 * this package.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.GumdropConfigurator
 */
package org.bluezoo.gumdrop.config;
