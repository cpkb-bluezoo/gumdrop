/*
 * SMTPResponse.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.smtp.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an SMTP server response.
 * 
 * <p>SMTP responses consist of a 3-digit code and optional text message.
 * Multi-line responses are supported where intermediate lines end with a hyphen.
 * 
 * <p>Response code ranges:
 * <ul>
 * <li>2xx - Success</li>
 * <li>3xx - Intermediate (like 354 for DATA)</li>
 * <li>4xx - Temporary failure (retry later)</li>
 * <li>5xx - Permanent failure (don't retry)</li>
 * </ul>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class SMTPResponse {
    
    private final int code;
    private final String message;
    private final List<String> lines;
    private final boolean multiLine;
    
    /**
     * Creates a single-line response.
     * 
     * @param code 3-digit response code
     * @param message response message
     */
    public SMTPResponse(int code, String message) {
        this.code = code;
        this.message = message;
        this.lines = Collections.singletonList(message);
        this.multiLine = false;
    }
    
    /**
     * Creates a multi-line response.
     * 
     * @param code 3-digit response code  
     * @param lines all response lines
     */
    public SMTPResponse(int code, List<String> lines) {
        this.code = code;
        this.lines = new ArrayList<String>(lines);
        this.message = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        this.multiLine = lines.size() > 1;
    }
    
    /**
     * Gets the 3-digit response code.
     * 
     * @return response code (e.g., 220, 250, 354, 550)
     */
    public int getCode() {
        return code;
    }
    
    /**
     * Gets the response message text.
     * For multi-line responses, this is the last line.
     * 
     * @return response message
     */
    public String getMessage() {
        return message;
    }
    
    /**
     * Gets all response lines.
     * For single-line responses, contains one element.
     * 
     * @return unmodifiable list of response lines
     */
    public List<String> getLines() {
        return Collections.unmodifiableList(lines);
    }
    
    /**
     * Checks if this is a multi-line response.
     * 
     * @return true if response has multiple lines
     */
    public boolean isMultiLine() {
        return multiLine;
    }
    
    /**
     * Checks if response indicates success (2xx codes).
     * 
     * @return true for success responses
     */
    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }
    
    /**
     * Checks if response is intermediate (3xx codes).
     * Used for responses like 354 "Start mail input".
     * 
     * @return true for intermediate responses
     */
    public boolean isIntermediate() {
        return code >= 300 && code < 400;
    }
    
    /**
     * Checks if response indicates temporary failure (4xx codes).
     * These errors suggest the operation should be retried later.
     * 
     * @return true for temporary failure responses
     */
    public boolean isTemporaryFailure() {
        return code >= 400 && code < 500;
    }
    
    /**
     * Checks if response indicates permanent failure (5xx codes).
     * These errors suggest the operation should not be retried.
     * 
     * @return true for permanent failure responses
     */
    public boolean isPermanentFailure() {
        return code >= 500;
    }
    
    @Override
    public String toString() {
        if (multiLine) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                sb.append(code);
                if (i < lines.size() - 1) {
                    sb.append('-');
                } else {
                    sb.append(' ');
                }
                sb.append(lines.get(i));
                if (i < lines.size() - 1) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        } else {
            return code + " " + message;
        }
    }
}

