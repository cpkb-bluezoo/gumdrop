module org.bluezoo.gumdrop.redis {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.redis.client;
    exports org.bluezoo.gumdrop.redis.codec;
}
