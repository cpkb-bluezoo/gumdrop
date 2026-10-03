module org.bluezoo.gumdrop.mdns {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.mdns;
    exports org.bluezoo.gumdrop.mdns.server;
}
