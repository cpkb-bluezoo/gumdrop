/*
 * MessageFormatter.java
 * Copyright (C) 2005 Chris Burdess
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
