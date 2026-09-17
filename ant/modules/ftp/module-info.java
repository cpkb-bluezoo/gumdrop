module org.bluezoo.gumdrop.ftp {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.ftp;
    exports org.bluezoo.gumdrop.ftp.client;
    exports org.bluezoo.gumdrop.ftp.server;
    exports org.bluezoo.gumdrop.ftp.file;
}
