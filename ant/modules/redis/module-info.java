module org.bluezoo.gumdrop.redis {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.redis.client;
    exports org.bluezoo.gumdrop.redis.codec;
}
