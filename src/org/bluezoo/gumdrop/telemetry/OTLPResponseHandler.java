/*
 * OTLPResponseHandler.java
 * Copyright (C) 2025 Chris Burdess
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
 * HTTP response handler for OTLP telemetry export requests.
 *
 * <p>Handles both successful and error responses from the OTLP collector,
 * logging appropriate messages. The response body is discarded as OTLP
 * responses typically don't contain useful data for the client.
 *
 * <p>This handler tracks completion state to support synchronous flush
 * operations that need to wait for all pending exports to complete.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class OTLPResponseHandler extends DefaultHTTPResponseHandler {

    private static final ResourceBundle L10N = 
        ResourceBundle.getBundle("org.bluezoo.gumdrop.telemetry.L10N");
    private static final Logger logger = Logger.getLogger(OTLPResponseHandler.class.getName());

    private final String endpointName;
    private final OTLPExporter exporter;

    private volatile boolean complete;
    private volatile boolean success;
    private HTTPStatus status;

    /**
     * Creates an OTLP response handler.
     *
     * @param endpointName the endpoint name (traces, logs, metrics) for logging
     * @param exporter the exporter to notify on completion
     */
    OTLPResponseHandler(String endpointName, OTLPExporter exporter) {
        this.endpointName = endpointName;
        this.exporter = exporter;
    }

    @Override
    public void ok(HTTPResponse response) {
        this.status = response.getStatus();
        this.success = true;

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("OTLP " + endpointName + " export successful: " + status);
        }
    }

    @Override
    public void error(HTTPResponse response) {
        this.status = response.getStatus();
        this.success = false;

        if (logger.isLoggable(Level.WARNING)) {
            logger.warning(MessageFormat.format(L10N.getString("warn.export_failed"), endpointName, status));
        }

        // Check for retryable errors
        if (isRetryable(status)) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("OTLP " + endpointName + " error is retryable, will retry on next batch");
            }
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
            logger.log(Level.WARNING, "OTLP " + endpointName + " export failed with exception", ex);
        }

        exporter.onExportComplete(this);
    }

    /**
     * Returns whether the export completed (success or failure).
     *
     * @return true if complete
     */
    boolean isComplete() {
        return complete;
    }

    /**
     * Returns whether the export was successful.
     *
     * @return true if successful
     */
    boolean isSuccess() {
        return success;
    }

    /**
     * Returns the HTTP status from the response.
     *
     * @return the status, or null if no response was received
     */
    HTTPStatus getStatus() {
        return status;
    }

    /**
     * Determines if the given status indicates a retryable error.
     *
     * <p>The following status codes are considered retryable:
     * <ul>
     * <li>408 Request Timeout</li>
     * <li>429 Too Many Requests</li>
     * <li>502 Bad Gateway</li>
     * <li>503 Service Unavailable</li>
     * <li>504 Gateway Timeout</li>
     * </ul>
     *
     * @param status the HTTP status
     * @return true if the error is retryable
     */
    private boolean isRetryable(HTTPStatus status) {
        if (status == null) {
            return false;
        }
        switch (status) {
            case REQUEST_TIMEOUT:
            case TOO_MANY_REQUESTS:
            case BAD_GATEWAY:
            case SERVICE_UNAVAILABLE:
            case GATEWAY_TIMEOUT:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return "OTLPResponseHandler[" + endpointName + ", complete=" + complete + ", success=" + success + "]";
    }
}

