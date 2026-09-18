module org.bluezoo.gumdrop.pop3 {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.pop3;
    exports org.bluezoo.gumdrop.pop3.server;
    exports org.bluezoo.gumdrop.pop3.client;
}
