module org.bluezoo.gumdrop.imap {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.imap;
    exports org.bluezoo.gumdrop.imap.server;
    exports org.bluezoo.gumdrop.imap.client;
}
