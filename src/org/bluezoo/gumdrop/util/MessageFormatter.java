/*
 * MessageFormatter.java
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

import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * A simple logging formatter that prints the log message followed by EOL.
 *
 * @author <a href='mailto:dog@gnu.og'>Chris Burdess</a>
 */
public class MessageFormatter extends Formatter {

    static final String EOL = System.getProperty("line.separator");

    public String format(LogRecord record) {
        return record.getMessage() + EOL;
    }

}
