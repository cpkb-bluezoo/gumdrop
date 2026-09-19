/*
 * ArcAuthenticationResults.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * Builds an {@code ARC-Authentication-Results} header value (RFC 8617
 * section 5.1.1, Authentication-Results format per RFC 8601).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcSealer
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8601">RFC 8601 - Authentication-Results</a>
 */
public class ArcAuthenticationResults {

    private int instance = 1;
    private String authservId = "localhost";
    private SpfResult spfResult;
    private String spfSmtpMailfrom;
    private DkimResult dkimResult;
    private String dkimHeaderD;
    private DmarcResult dmarcResult;
    private DmarcPolicy dmarcPolicy;

    /**
     * Sets the ARC instance number ({@code i=}) for the header being built.
     *
     * @param instance instance number, starting at 1 for the first ARC hop
     */
    public void setInstance(int instance) {
        this.instance = instance;
    }

    /**
     * Sets the authserv-id token (RFC 8601) identifying this MTA in the AAR.
     *
     * @param authservId host name or similar identifier of the sealing server
     */
    public void setAuthservId(String authservId) {
        this.authservId = authservId;
    }

    /**
     * Records the SPF verdict observed at this hop for the AAR payload.
     *
     * @param result SPF result
     * @param smtpMailfromDomain domain used in {@code smtp.mailfrom}, if any
     */
    public void setSpf(SpfResult result, String smtpMailfromDomain) {
        this.spfResult = result;
        this.spfSmtpMailfrom = smtpMailfromDomain;
    }

    /**
     * Records the DKIM verdict observed at this hop for the AAR payload.
     *
     * @param result DKIM result
     * @param headerD value of the {@code header.d} token (signing domain), if any
     */
    public void setDkim(DkimResult result, String headerD) {
        this.dkimResult = result;
        this.dkimHeaderD = headerD;
    }

    /**
     * Records the DMARC verdict observed at this hop for the AAR payload.
     *
     * @param result DMARC evaluation result
     * @param policy effective DMARC policy, if known
     */
    public void setDmarc(DmarcResult result, DmarcPolicy policy) {
        this.dmarcResult = result;
        this.dmarcPolicy = policy;
    }

    /**
     * Returns a complete {@code ARC-Authentication-Results: ...} header line.
     *
     * @return header line including name, value, and CRLF
     */
    public String formatHeaderLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("ARC-Authentication-Results: i=");
        sb.append(instance);
        sb.append("; ");
        sb.append(authservId);
        if (spfResult != null) {
            sb.append("; spf=");
            sb.append(spfResult.name().toLowerCase());
            if (spfSmtpMailfrom != null && spfSmtpMailfrom.length() > 0) {
                sb.append(" smtp.mailfrom=");
                sb.append(spfSmtpMailfrom);
            }
        }
        if (dkimResult != null) {
            sb.append("; dkim=");
            sb.append(dkimResult.name().toLowerCase());
            if (dkimHeaderD != null && dkimHeaderD.length() > 0) {
                sb.append(" header.d=");
                sb.append(dkimHeaderD);
            }
        }
        if (dmarcResult != null) {
            sb.append("; dmarc=");
            sb.append(dmarcResult.name().toLowerCase());
            if (dmarcPolicy != null) {
                sb.append(" (policy=");
                sb.append(dmarcPolicy.name().toLowerCase());
                sb.append(")");
            }
        }
        sb.append("\r\n");
        return sb.toString();
    }
}
