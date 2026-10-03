module org.bluezoo.gumdrop.imap {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.mailbox;

    exports org.bluezoo.gumdrop.imap;
    exports org.bluezoo.gumdrop.imap.server;
    exports org.bluezoo.gumdrop.imap.client;
}
