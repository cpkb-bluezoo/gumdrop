/*
 * OTLPGrpcResponseHandler.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.telemetry;

import org.bluezoo.gumdrop.http.HTTPStatus;
import org.bluezoo.gumdrop.http.client.DefaultHTTPResponseHandler;
import org.bluezoo.gumdrop.http.client.HTTPResponse;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP response handler for OTLP gRPC telemetry export requests.
 *
 * <p>Handles successful and error responses from the OTLP collector over gRPC.
 * gRPC uses HTTP/2; success is typically HTTP 200 with grpc-status 0 in trailers.
 * This handler treats HTTP 200 as success for simplicity.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class OTLPGrpcResponseHandler extends DefaultHTTPResponseHandler {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger logger = Logger.getLogger(OTLPGrpcResponseHandler.class.getName());

    private final String endpointName;
    private final OTLPGrpcExporter exporter;

    private volatile boolean complete;
    private volatile boolean success;
    private HTTPStatus status;

    OTLPGrpcResponseHandler(String endpointName, OTLPGrpcExporter exporter) {
        this.endpointName = endpointName;
        this.exporter = exporter;
    }

    @Override
    public void ok(HTTPResponse response) {
        this.status = response.getStatus();
        this.success = true;

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("OTLP gRPC " + endpointName + " export successful: " + status);
        }
    }

    @Override
    public void error(HTTPResponse response) {
        this.status = response.getStatus();
        this.success = false;

        if (logger.isLoggable(Level.WARNING)) {
            logger.warning(MessageFormat.format(L10N.getString("warn.export_failed"), endpointName, status));
        }
    }

    @Override
    public void close() {
        complete = true;
        exporter.onExportComplete(this);
    }

    @Override
    public void failed(Exception ex) {
        this.success = false;
        this.complete = true;

        if (logger.isLoggable(Level.WARNING)) {
            logger.log(Level.WARNING, "OTLP gRPC " + endpointName + " export failed with exception", ex);
        }

        exporter.onExportComplete(this);
    }

    boolean isComplete() {
        return complete;
    }

    boolean isSuccess() {
        return success;
    }

    HTTPStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "OTLPGrpcResponseHandler[" + endpointName + ", complete=" + complete + ", success=" + success + "]";
    }
}
