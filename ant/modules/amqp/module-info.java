module org.bluezoo.gumdrop.amqp {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.amqp.client;
    exports org.bluezoo.gumdrop.amqp.client.handler;
}
