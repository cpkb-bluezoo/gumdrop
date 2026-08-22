module org.bluezoo.gumdrop.socks {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.socks;
    exports org.bluezoo.gumdrop.socks.client;
    exports org.bluezoo.gumdrop.socks.handler;
}
