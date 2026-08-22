module org.bluezoo.gumdrop.grpc {
    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.grpc;
    exports org.bluezoo.gumdrop.grpc.client;
    exports org.bluezoo.gumdrop.grpc.server;
    exports org.bluezoo.gumdrop.grpc.proto;
}
