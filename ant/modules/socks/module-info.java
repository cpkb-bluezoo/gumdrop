module org.bluezoo.gumdrop.socks {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.socks;
    exports org.bluezoo.gumdrop.socks.client;
    exports org.bluezoo.gumdrop.socks.server;
}
