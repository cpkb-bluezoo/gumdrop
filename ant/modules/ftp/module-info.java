module org.bluezoo.gumdrop.ftp {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.core;

    exports org.bluezoo.gumdrop.ftp;
    exports org.bluezoo.gumdrop.ftp.client;
    exports org.bluezoo.gumdrop.ftp.server;
    exports org.bluezoo.gumdrop.ftp.file;
}
