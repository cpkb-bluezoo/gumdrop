module org.bluezoo.gumdrop.pop3 {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.pop3;
    exports org.bluezoo.gumdrop.pop3.handler;
    exports org.bluezoo.gumdrop.pop3.client;
    exports org.bluezoo.gumdrop.pop3.client.handler;
}
