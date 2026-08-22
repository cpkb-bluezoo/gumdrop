module org.bluezoo.gumdrop.http {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;

    exports org.bluezoo.gumdrop.http;
    exports org.bluezoo.gumdrop.http.client;
    exports org.bluezoo.gumdrop.http.h2;
    exports org.bluezoo.gumdrop.http.h3;
    exports org.bluezoo.gumdrop.http.hpack;
    exports org.bluezoo.gumdrop.http.qpack;
    exports org.bluezoo.gumdrop.websocket;
    exports org.bluezoo.gumdrop.websocket.client;
    exports org.bluezoo.gumdrop.health;
    exports org.bluezoo.gumdrop.auth.oauth;
    exports org.bluezoo.gumdrop.http.doh;
}
