module org.bluezoo.gumdrop.mqtt {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.mqtt;
    exports org.bluezoo.gumdrop.mqtt.broker;
    exports org.bluezoo.gumdrop.mqtt.client;
    exports org.bluezoo.gumdrop.mqtt.codec;
    exports org.bluezoo.gumdrop.mqtt.handler;
    exports org.bluezoo.gumdrop.mqtt.store;
}
