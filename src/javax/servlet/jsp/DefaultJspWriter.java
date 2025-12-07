/*
 * DefaultJspWriter.java
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
import java.io.PrintWriter;

/**
 * Default implementation of JspWriter for Gumdrop JSP support.
 * 
 * <p>This implementation wraps a standard PrintWriter and provides
 * the JSP-specific output methods required by generated JSP servlets.</p>
 * 
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultJspWriter extends JspWriter {

    private final PrintWriter writer;

    /**
     * Package-private constructor.
     *
     * @param writer the underlying PrintWriter
     * @param bufferSize the buffer size
     * @param autoFlush whether to auto-flush
     */
    DefaultJspWriter(PrintWriter writer, int bufferSize, boolean autoFlush) {
        super(bufferSize, autoFlush);
        this.writer = writer;
    }

    @Override
    public void newLine() throws IOException {
        writer.println();
    }

    @Override
    public void print(boolean b) throws IOException {
        writer.print(b);
    }

    @Override
    public void print(char c) throws IOException {
        writer.print(c);
    }

    @Override
    public void print(int i) throws IOException {
        writer.print(i);
    }

    @Override
    public void print(long l) throws IOException {
        writer.print(l);
    }

    @Override
    public void print(float f) throws IOException {
        writer.print(f);
    }

    @Override
    public void print(double d) throws IOException {
        writer.print(d);
    }

    @Override
    public void print(char[] s) throws IOException {
        writer.print(s);
    }

    @Override
    public void print(String s) throws IOException {
        writer.print(s != null ? s : "null");
    }

    @Override
    public void print(Object obj) throws IOException {
        writer.print(obj);
    }

    @Override
    public void println() throws IOException {
        writer.println();
    }

    @Override
    public void println(boolean x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(char x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(int x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(long x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(float x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(double x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(char[] x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(String x) throws IOException {
        writer.println(x);
    }

    @Override
    public void println(Object x) throws IOException {
        writer.println(x);
    }

    @Override
    public void clear() throws IOException {
        // PrintWriter doesn't support clearing, so this is a no-op
        // In a real buffered implementation, this would clear the buffer
    }

    @Override
    public void clearBuffer() throws IOException {
        // PrintWriter doesn't support clearing, so this is a no-op
        // In a real buffered implementation, this would clear the buffer
    }

    @Override
    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    @Override
    public int getRemaining() {
        // Since we're wrapping a PrintWriter directly, we don't have buffer info
        // Return a reasonable default
        return (bufferSize > 0) ? bufferSize : Integer.MAX_VALUE;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        writer.write(cbuf, off, len);
    }

    @Override
    public void write(int c) throws IOException {
        writer.write(c);
    }

    @Override
    public void write(char[] cbuf) throws IOException {
        writer.write(cbuf);
    }

    @Override
    public void write(String str) throws IOException {
        writer.write(str);
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        writer.write(str, off, len);
    }
}
