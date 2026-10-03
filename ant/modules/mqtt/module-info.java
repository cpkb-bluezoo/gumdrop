module org.bluezoo.gumdrop.mqtt {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.mqtt;
    exports org.bluezoo.gumdrop.mqtt.client;
    exports org.bluezoo.gumdrop.mqtt.codec;
    exports org.bluezoo.gumdrop.mqtt.server;
    exports org.bluezoo.gumdrop.mqtt.store;
}
