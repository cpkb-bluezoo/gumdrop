module org.bluezoo.gumdrop.webdav {
    requires java.logging;
    requires java.xml;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mime;

    requires org.bluezoo.gonzalez;

    exports org.bluezoo.gumdrop.webdav;
    exports org.bluezoo.gumdrop.webdav.server;
}
