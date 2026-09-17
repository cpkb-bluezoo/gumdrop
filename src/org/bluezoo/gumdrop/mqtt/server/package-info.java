/*
 * package-info.java
 * Copyright (C) 2026 Chris Burdess
 */

/**
 * MQTT server: listeners, composition, and handler/state interfaces for the
 * broker's policy decisions.
 *
 * <p>{@link org.bluezoo.gumdrop.mqtt.server.MqttServer} is the canonical
 * server type — configure it with {@link
 * org.bluezoo.gumdrop.mqtt.server.MqttServer#compose()}; do not subclass it
 * for application logic.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see org.bluezoo.gumdrop.mqtt.MqttProtocolHandler
 */
package org.bluezoo.gumdrop.mqtt.server;
