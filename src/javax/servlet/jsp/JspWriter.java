/*
 * JspWriter.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package javax.servlet.jsp;

import java.io.IOException;
import java.io.Writer;

/**
 * Minimal JSP Writer implementation for Gumdrop JSP support.
 * 
 * <p>This is a basic implementation of the JSP API's JspWriter class,
 * providing the essential functionality needed for JSP page compilation
 * and execution in Gumdrop.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class JspWriter extends Writer {

    /**
     * Constant indicating that the Writer is not buffering output.
     */
    public static final int NO_BUFFER = 0;

    /**
     * Constant indicating that the Writer is buffered and is unbounded.
     */
    public static final int UNBOUNDED_BUFFER = -1;

    protected int bufferSize;
    protected boolean autoFlush;

    /**
     * Protected constructor.
     *
     * @param bufferSize the size of the buffer to be used by the JspWriter
     * @param autoFlush whether the JspWriter should be autoflushing
     */
    protected JspWriter(int bufferSize, boolean autoFlush) {
        this.bufferSize = bufferSize;
        this.autoFlush = autoFlush;
    }

    /**
     * Write a line separator.
     */
    public abstract void newLine() throws IOException;

    /**
     * Print a boolean value.
     */
    public abstract void print(boolean b) throws IOException;

    /**
     * Print a character.
     */
    public abstract void print(char c) throws IOException;

    /**
     * Print an integer.
     */
    public abstract void print(int i) throws IOException;

    /**
     * Print a long integer.
     */
    public abstract void print(long l) throws IOException;

    /**
     * Print a floating-point number.
     */
    public abstract void print(float f) throws IOException;

    /**
     * Print a double-precision floating-point number.
     */
    public abstract void print(double d) throws IOException;

    /**
     * Print an array of characters.
     */
    public abstract void print(char[] s) throws IOException;

    /**
     * Print a string.
     */
    public abstract void print(String s) throws IOException;

    /**
     * Print an object.
     */
    public abstract void print(Object obj) throws IOException;

    /**
     * Terminate the current line by writing the line separator string.
     */
    public abstract void println() throws IOException;

    /**
     * Print a boolean value and then terminate the line.
     */
    public abstract void println(boolean x) throws IOException;

    /**
     * Print a character and then terminate the line.
     */
    public abstract void println(char x) throws IOException;

    /**
     * Print an integer and then terminate the line.
     */
    public abstract void println(int x) throws IOException;

    /**
     * Print a long integer and then terminate the line.
     */
    public abstract void println(long x) throws IOException;

    /**
     * Print a floating-point number and then terminate the line.
     */
    public abstract void println(float x) throws IOException;

    /**
     * Print a double-precision floating-point number and then terminate the line.
     */
    public abstract void println(double x) throws IOException;

    /**
     * Print an array of characters and then terminate the line.
     */
    public abstract void println(char[] x) throws IOException;

    /**
     * Print a String and then terminate the line.
     */
    public abstract void println(String x) throws IOException;

    /**
     * Print an Object and then terminate the line.
     */
    public abstract void println(Object x) throws IOException;

    /**
     * Clear the contents of the buffer.
     */
    public abstract void clear() throws IOException;

    /**
     * Clears the current contents of the buffer.
     */
    public abstract void clearBuffer() throws IOException;

    /**
     * Flush the stream.
     */
    public abstract void flush() throws IOException;

    /**
     * Close the stream.
     */
    public abstract void close() throws IOException;

    /**
     * @return the size of the buffer used by the JspWriter
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * @return the number of unused bytes in the buffer
     */
    public abstract int getRemaining();

    /**
     * @return whether the JspWriter is autoFlushing
     */
    public boolean isAutoFlush() {
        return autoFlush;
    }
}
