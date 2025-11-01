/*
 * FTPConnection.java
 * Copyright (C) 2006 Chris Burdess
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

package org.bluezoo.gumdrop.ftp;

import org.bluezoo.gumdrop.Connection;
import org.bluezoo.gumdrop.Server;
import org.bluezoo.gumdrop.util.LineInput;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Connection handler for the FTP protocol.
 * This manages one TCP control connection.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see https://www.rfc-editor.org/rfc/rfc959
 */
public class FTPConnection extends Connection {

    private static final Logger LOGGER = Logger.getLogger(FTPConnection.class.getName());

    static final Charset US_ASCII = Charset.forName("US-ASCII");
    static final CharsetDecoder US_ASCII_DECODER = US_ASCII.newDecoder();

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }

    private final SocketChannel channel;
    private final Map<String,IOConsumer<String>> commands;

    private String user;
    private String password;
    private String account;
    private String dataHost; // IPv4 address of peer
    private int dataPort;
    private String renameFrom;

    private FTPDataConnection dataConnection; // XXX

    private ByteBuffer in; // input buffer
    private LineReader lineReader;

    class LineReader implements LineInput {

        private CharBuffer sink; // character buffer to receive decoded characters

        @Override public ByteBuffer getLineInputBuffer() {
            return in;
        }

        @Override public CharBuffer getOrCreateLineInputCharacterSink(int capacity) {
            if (sink == null || sink.capacity() < capacity) {
                sink = CharBuffer.allocate(capacity);
            }
            return sink;
        }

    }

    protected FTPConnection(SocketChannel channel, SSLEngine engine, boolean secure) {
        super(engine, secure);
        this.channel = channel;
        commands = new HashMap<>();
        commands.put("USER", this::doUser);
        commands.put("PASS", this::doPass);
        commands.put("ACCT", this::doAcct);
        commands.put("CWD", this::doCwd);
        commands.put("CDUP", this::doCdup);
        commands.put("SMNT", this::doSmnt);
        commands.put("REIN", this::doRein);
        commands.put("QUIT", this::doQuit);
        commands.put("PORT", this::doPort);
        commands.put("PASV", this::doPasv);
        commands.put("TYPE", this::doType);
        commands.put("STRU", this::doStru);
        commands.put("MODE", this::doMode);
        commands.put("RETR", this::doRetr);
        commands.put("STOR", this::doStor);
        commands.put("STOU", this::doStou);
        commands.put("APPE", this::doAppe);
        commands.put("ALLO", this::doAllo);
        commands.put("REST", this::doRest);
        commands.put("RNFR", this::doRnfr);
        commands.put("RNTO", this::doRnto);
        commands.put("ABOR", this::doAbor);
        commands.put("DELE", this::doDele);
        commands.put("RMD", this::doRmd);
        commands.put("MKD", this::doMkd);
        commands.put("PWD", this::doPwd);
        commands.put("LIST", this::doList);
        commands.put("NLST", this::doNlst);
        commands.put("SITE", this::doSite);
        commands.put("SYST", this::doSyst);
        commands.put("STAT", this::doStat);
        commands.put("HELP", this::doHelp);
        commands.put("NOOP", this::doNoop);
        lineReader = this.new LineReader();
    }

    protected synchronized void received(ByteBuffer buf) {
        try {
            String line = lineReader.readLine(US_ASCII_DECODER);
            while (line != null) {
                lineRead(line);
                line = lineReader.readLine(US_ASCII_DECODER);
            }
        } catch (IOException e) {
            try {
                reply(500, "Illegal characters in command"); // Syntax error
            } catch (IOException e2) {
                LOGGER.log(Level.SEVERE, "Cannot write error reply", e);
            }
        }
    }

    protected void disconnected() throws IOException {
    }

    /**
     * Invoked when a complete CRLF-terminated string has been read from the
     * connection. Does not include the CRLF.
     *
     * @param line the line read
     */
    void lineRead(String line) throws IOException {
        int si = line.indexOf(' ');
        String command = (si > 0) ? line.substring(0, si) : line;
        String args = (si > 0) ? line.substring(si + 1) : null;
        IOConsumer<String> function = commands.get(command);
        if (function == null) {
            reply(500, "Command unrecognized: " + command);
        } else {
            function.accept(args);
        }
    }

    protected void reply(int code, String description) throws IOException {
        String message = String.format("%d %s\r\n", code, description);
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes("US-ASCII"));
        send(buffer);
    }

    /**
     * USER NAME
     */
    protected void doUser(String args) throws IOException {
        user = args;
    }

    /**
     * PASSWORD
     */
    protected void doPass(String args) throws IOException {
        password = args;
    }

    /**
     * ACCOUNT
     */
    protected void doAcct(String args) throws IOException {
        account = args;
    }

    /**
     * CHANGE WORKING DIRECTORY
     */
    protected void doCwd(String args) throws IOException {
        // TODO
    }

    /**
     * CHANGE TO PARENT DIRECTORY
     */
    protected void doCdup(String args) throws IOException {
        // TODO
    }

    /**
     * STRUCTURE MOUNT
     */
    protected void doSmnt(String args) throws IOException {
        // TODO
    }

    /**
     * REINITIALIZE
     */
    protected void doRein(String args) throws IOException {
        user = null;
        password = null;
        account = null;
    }

    /**
     * LOGOUT
     */
    protected void doQuit(String args) throws IOException {
        user = null;
        password = null;
        account = null;
        // TODO terminate connection
    }

    /**
     * DATA PORT
     */
    protected void doPort(String args) throws IOException {
        String[] fields = args.split(",");
        try {
            if (fields.length != 6) {
                reply(501, "Invalid arguments for PORT command: "+args);
                return;
            }
            // Build IPv4 host string
            StringBuilder hostBuf = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int f = Integer.parseInt(fields[i]);
                if (f < 0 || f > 255) {
                    reply(501, "Invalid arguments for PORT command: "+args);
                    return;
                }
                if (i > 0) {
                    hostBuf.append('.');
                }
                hostBuf.append(f);
            }
            dataHost = hostBuf.toString();
            // Extract port
            dataPort = 0;
            for (int i = 0; i < 2; i++) {
                int f = Integer.parseInt(fields[i + 4]);
                if (f < 0 || f > 255) {
                    reply(501, "Invalid arguments for PORT command: "+args);
                    return;
                }
                if (i == 0) {
                    f = f << 8;
                }
                dataPort |= f;
            }
        } catch (NumberFormatException e) {
            reply(501, "Invalid arguments for PORT command: "+args);
        }
    }

    /**
     * PASSIVE
     */
    protected void doPasv(String args) throws IOException {
        try {
            int passivePort = Integer.parseInt(args);
            FTPDataConnector connector = new FTPDataConnector(this, passivePort);
            Server.getInstance().registerConnector(connector);
        } catch (NumberFormatException e) {
            reply(501, "Invalid arguments for PASV command: "+args);
        }
    }

    /**
     * REPRESENTATION TYPE
     */
    protected void doType(String args) throws IOException {
        // TODO
    }

    /**
     * FILE STRUCTURE
     */
    protected void doStru(String args) throws IOException {
        // TODO
    }

    /**
     * TRANSFER MODE
     */
    protected void doMode(String args) throws IOException {
        // TODO
    }

    /**
     * RETRIEVE
     */
    protected void doRetr(String args) throws IOException {
        // TODO
    }

    /**
     * STORE
     */
    protected void doStor(String args) throws IOException {
        // TODO
    }

    /**
     * STORE UNIQUE
     */
    protected void doStou(String args) throws IOException {
        // TODO
    }

    /**
     * APPEND (with create)
     */
    protected void doAppe(String args) throws IOException {
        // TODO
    }

    /**
     * ALLOCATE
     */
    protected void doAllo(String args) throws IOException {
        // TODO
    }

    /**
     * RESTART
     */
    protected void doRest(String args) throws IOException {
        // TODO
    }

    /**
     * RENAME FROM
     */
    protected void doRnfr(String args) throws IOException {
        renameFrom = args;
    }

    /**
     * RENAME TO
     */
    protected void doRnto(String args) throws IOException {
        // TODO
    }

    /**
     * ABORT
     */
    protected void doAbor(String args) throws IOException {
        // TODO
    }

    /**
     * DELETE
     */
    protected void doDele(String args) throws IOException {
        // TODO
    }

    /**
     * REMOVE DIRECTORY
     */
    protected void doRmd(String args) throws IOException {
        // TODO
    }

    /**
     * MAKE DIRECTORY
     */
    protected void doMkd(String args) throws IOException {
        // TODO
    }

    /**
     * PRINT WORKING DIRECTORY
     */
    protected void doPwd(String args) throws IOException {
        // TODO
    }

    /**
     * LIST
     */
    protected void doList(String args) throws IOException {
        // TODO
    }

    /**
     * NAME LIST
     */
    protected void doNlst(String args) throws IOException {
        // TODO
    }

    /**
     * SITE PARAMETERS
     */
    protected void doSite(String args) throws IOException {
        // TODO
    }

    /**
     * SYSTEM
     */
    protected void doSyst(String args) throws IOException {
        // TODO
    }

    /**
     * STATUS
     */
    protected void doStat(String args) throws IOException {
        // TODO
    }

    /**
     * HELP
     */
    protected void doHelp(String args) throws IOException {
        // TODO
    }

    /**
     * NOOP
     */
    protected void doNoop(String args) throws IOException {
        // TODO
    }

}
