/*
 * LaconicFormatter.java
 * Copyright (C) 2005 Chris Burdess
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

package org.bluezoo.gumdrop.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * A simple logging formatter that prints the minimum of information.
 *
 * @author <a href='mailto:dog@gnu.og'>Chris Burdess</a>
 */
public class LaconicFormatter extends Formatter {

    public String format(LogRecord record) {
        StringBuffer buf = new StringBuffer();
        buf.append(record.getLevel().getLocalizedName());
        buf.append(": ");
        String message = record.getMessage();
        if (message != null) {
            buf.append(message);
        }
        buf.append(System.getProperty("line.separator"));
        Throwable t = record.getThrown();
        if (t != null) {
            StringWriter sink = new StringWriter();
            PrintWriter filter = new PrintWriter(sink);
            t.printStackTrace(filter);
            filter.flush();
            buf.append(sink.toString());
        }
        return buf.toString();
    }

}
