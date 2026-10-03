module org.bluezoo.gumdrop.amqp {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.amqp;
    exports org.bluezoo.gumdrop.amqp.client;
}
