/*
 * DSNEnvelopeParameters.java
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

package org.bluezoo.gumdrop.smtp;

/**
 * DSN envelope parameters from the MAIL FROM command.
 * 
 * <p>This class holds the DSN-related parameters specified in the
 * MAIL FROM command as defined in RFC 3461:
 * 
 * <ul>
 *   <li><b>RET</b> - What to return in DSN (FULL or HDRS)</li>
 *   <li><b>ENVID</b> - Envelope identifier for correlation</li>
 * </ul>
 * 
 * <p>Example MAIL FROM with DSN parameters:
 * <pre>
 * MAIL FROM:&lt;sender@example.com&gt; RET=HDRS ENVID=QQ314159
 * </pre>
 * 
 * <p>The ENVID is transmitted as xtext (RFC 3461 Section 4) and is
 * automatically decoded when parsed.
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see DSNReturn
 * @see <a href="https://www.rfc-editor.org/rfc/rfc3461">RFC 3461 - SMTP DSN</a>
 */
public class DSNEnvelopeParameters {

    private final DSNReturn ret;
    private final String envid;

    /**
     * Creates new DSN envelope parameters.
     * 
     * @param ret the return type (FULL or HDRS), may be null
     * @param envid the envelope ID, may be null
     */
    public DSNEnvelopeParameters(DSNReturn ret, String envid) {
        this.ret = ret;
        this.envid = envid;
    }

    /**
     * Returns the RET parameter value.
     * 
     * <p>This indicates how much of the original message should be
     * included in any DSN that is generated.
     * 
     * @return the return type, or null if not specified
     */
    public DSNReturn getRet() {
        return ret;
    }

    /**
     * Returns the ENVID parameter value.
     * 
     * <p>This is an opaque identifier assigned by the sender that can
     * be used to correlate DSN responses with the original message.
     * 
     * @return the envelope ID, or null if not specified
     */
    public String getEnvid() {
        return envid;
    }

    /**
     * Returns true if any DSN parameters were specified.
     * 
     * @return true if RET or ENVID is set
     */
    public boolean hasParameters() {
        return ret != null || envid != null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DSNEnvelopeParameters[");
        if (ret != null) {
            sb.append("RET=").append(ret);
        }
        if (envid != null) {
            if (ret != null) {
                sb.append(", ");
            }
            sb.append("ENVID=").append(envid);
        }
        sb.append("]");
        return sb.toString();
    }
}

