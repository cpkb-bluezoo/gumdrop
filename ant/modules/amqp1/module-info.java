/*
 * The "1" in the name is the protocol version (AMQP 1.0), and the name
 * distinguishes this module from org.bluezoo.gumdrop.amqp (AMQP 0-9-1),
 * so javac's advice to avoid a terminal digit is intentionally not followed.
 */
@SuppressWarnings("module")
module org.bluezoo.gumdrop.amqp1 {
    requires org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.amqp1.client;
    exports org.bluezoo.gumdrop.amqp1.codec;
}
