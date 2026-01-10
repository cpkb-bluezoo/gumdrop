package org.bluezoo.json;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

/**
 * A streaming JSON parser using the push (receive) model.
 * <p>
 * Unlike traditional parsers that take an InputStream and block during
 * parsing, this parser uses a non-blocking push model:
 * <ul>
 * <li>{@link #receive(ByteBuffer)} - push bytes as they arrive</li>
 * <li>{@link #close()} - signal end of data</li>
 * </ul>
 * <p>
 * For convenience, a traditional blocking {@link #parse(InputStream)} method
 * is also provided which internally delegates to the streaming API.
 * <p>
 * Parsing events are delivered via {@link JSONContentHandler} as tokens
 * are recognized. The parser handles byte-to-character decoding and BOM detection,
 * then delegates tokenization to {@link JSONTokenizer}.
 *
 * <h3>Buffer Management Contract</h3>
 * <p>This parser follows the standard non-blocking streaming contract:
 * <ul>
 *   <li>The caller provides a buffer in read mode (ready for get operations)</li>
 *   <li>The parser consumes as many complete tokens as possible</li>
 *   <li>After {@link #receive(ByteBuffer)} returns, the buffer's position
 *       indicates where unconsumed data begins</li>
 *   <li>If there is unconsumed data (partial token), the caller MUST call
 *       {@link ByteBuffer#compact()} before reading more data into the buffer</li>
 *   <li>The next {@code receive()} call will continue from where parsing left off</li>
 * </ul>
 *
 * <h3>Usage (Streaming)</h3>
 * <pre>{@code
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(myHandler);
 * 
 * ByteBuffer buffer = ByteBuffer.allocate(8192);
 * while (channel.read(buffer) > 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 * parser.close();
 * }</pre>
 *
 * <h3>Usage (InputStream)</h3>
 * <pre>{@code
 * JSONParser parser = new JSONParser();
 * parser.setContentHandler(myHandler);
 * parser.parse(inputStream);
 * }</pre>
 *
 * <h3>Character Encoding</h3>
 * The parser assumes UTF-8 encoding by default. BOM detection is performed
 * on the first chunk.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class JSONParser {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private JSONContentHandler handler;
    private JSONTokenizer tokenizer;
    private CharsetDecoder decoder;
    private CharBuffer charBuffer;
    private final int bufferSize;
    
    // Stream state
    private boolean checkedBom;
    private boolean closed;

    /**
     * Creates a new JSON parser with a default buffer size of 8KB.
     */
    public JSONParser() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a new JSON parser with the specified buffer size.
     *
     * @param bufferSize the initial size of the character buffer in bytes
     */
    public JSONParser(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        this.bufferSize = bufferSize;
        this.decoder = StandardCharsets.UTF_8.newDecoder();
    }

    /**
     * Register a content handler to be notified of parsing events.
     *
     * @param handler the content handler
     */
    public void setContentHandler(JSONContentHandler handler) {
        this.handler = handler;
    }

    /**
     * Parse a JSON document from an InputStream.
     * <p>
     * This is a convenience method that reads the input stream in chunks
     * and delegates to the streaming {@link #receive(ByteBuffer)} API.
     * The stream is read until EOF, then {@link #close()} is called.
     * <p>
     * The parser is automatically reset before parsing, allowing this
     * method to be called multiple times to parse different documents.
     * <p>
     * Note: This method does not close the InputStream.
     *
     * @param in the input stream to parse
     * @throws JSONException if there is a parsing error
     */
    public void parse(InputStream in) throws JSONException {
        // Reset for new document
        reset();
        
        try {
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            
            // Read into the buffer's backing array
            while (true) {
                // Read into available space (after any unconsumed data)
                int bytesRead = in.read(buffer.array(), buffer.position(), 
                                        buffer.remaining());
                if (bytesRead == -1) {
                    break;
                }
                
                // Advance position to reflect bytes read
                buffer.position(buffer.position() + bytesRead);
                
                // Switch to read mode and parse
                buffer.flip();
                receive(buffer);
                
                // Preserve any unconsumed bytes for next iteration
                buffer.compact();
            }
            
            // Process any remaining data in the buffer
            buffer.flip();
            if (buffer.hasRemaining()) {
                receive(buffer);
            }
            
            close();
        } catch (IOException e) {
            throw new JSONException("I/O error reading stream", e);
        }
    }

    /**
     * Receive bytes into the parser.
     * <p>
     * Bytes are processed immediately. Parsing events are fired to the
     * content handler as tokens are recognized. Incomplete tokens are
     * left in the buffer - the position is set to the start of the
     * incomplete token.
     * <p>
     * <strong>Buffer Contract:</strong> The byte buffer must be in read mode
     * (after {@code flip()}). After this method returns:
     * <ul>
     *   <li>The buffer's position indicates where unconsumed data begins</li>
     *   <li>If {@code position() < limit()}, there is a partial token that needs
     *       more data to complete</li>
     *   <li>The caller MUST call {@code buffer.compact()} before reading more
     *       data into the buffer</li>
     * </ul>
     *
     * @param data the byte buffer to process (must be in read mode)
     * @throws JSONException if there is a parsing error or stream is closed
     */
    public void receive(ByteBuffer data) throws JSONException {
        if (closed) {
            throw new JSONException("Cannot receive data after close()");
        }
        
        if (!data.hasRemaining()) {
            return;
        }
        
        // Check for BOM on first chunk
        if (!checkedBom) {
            if (!checkBom(data)) {
                // Need more data to determine BOM - leave buffer as-is
                return;
            }
            checkedBom = true;
        }
        
        // Create tokenizer if needed
        if (tokenizer == null) {
            tokenizer = new JSONTokenizer(handler);
        }
        
        // Decode bytes to characters and process tokens
        decodeAndProcess(data);
    }

    /**
     * Close the parser, signaling end of input.
     * <p>
     * This validates that the JSON document is complete. The closed state
     * is signaled to the tokenizer, which will treat end-of-buffer as 
     * end-of-token (e.g., for numbers without delimiters like "42").
     * <p>
     * After closing, further calls to receive() will throw an exception.
     * Use {@link #reset()} to prepare the parser for a new document.
     *
     * @throws JSONException if the document is incomplete or malformed
     */
    public void close() throws JSONException {
        if (closed) {
            return; // Already closed
        }
        
        closed = true;
        
        // Check if we have any data at all
        if (tokenizer == null) {
            throw new JSONException("No data");
        }
        
        tokenizer.setClosed(true);
        
        // Process any remaining characters in charBuffer with closed flag set
        if (charBuffer != null && charBuffer.position() > 0) {
            charBuffer.flip();
            tokenizer.receive(charBuffer);
            
            // After processing, check if anything is still left unparsed
            if (charBuffer.hasRemaining()) {
                throw new JSONException("Unclosed string or incomplete token at end of input");
            }
        }
        
        // Check tokenizer state for structural issues
        if (!tokenizer.seenAnyToken) {
            throw new JSONException("No data");
        }
        
        if (!tokenizer.depthStack.isEmpty()) {
            Token unclosed = tokenizer.depthStack.peek();
            if (unclosed == Token.START_OBJECT) {
                throw new JSONException("Unclosed object");
            } else {
                throw new JSONException("Unclosed array");
            }
        }
    }

    /**
     * Reset the parser to parse a new document.
     * <p>
     * Clears all internal state, allowing the parser to be reused.
     * This is called automatically by {@link #parse(InputStream)}.
     */
    public void reset() {
        checkedBom = false;
        closed = false;
        
        // Clear char buffer
        if (charBuffer != null) {
            charBuffer.clear();
        }
        
        // Clear tokenizer
        tokenizer = null;
        
        // Reset decoder
        decoder.reset();
    }

    /**
     * Check for and skip BOM if present.
     * Optimized for UTF-8 (fast path), also detects UTF-16/32 to reject them.
     * Returns false if we need more data to determine if BOM is present.
     * If BOM is found, it is consumed from the buffer.
     */
    private boolean checkBom(ByteBuffer data) throws JSONException {
        int remaining = data.remaining();
        
        if (remaining == 0) {
            return false; // Need at least 1 byte
        }
        
        // Use get(int) to peek without consuming
        int startPos = data.position();
        byte b1 = data.get(startPos);
        
        // Fast path: UTF-8 BOM (EF BB BF) - most common case
        if (b1 == (byte) 0xEF) {
            if (remaining < 3) {
                // Might be UTF-8 BOM, need more data
                if (remaining >= 2 && data.get(startPos + 1) == (byte) 0xBB) {
                    return false; // Partial UTF-8 BOM
                }
                if (remaining == 1) {
                    return false; // Could be UTF-8 BOM
                }
            } else {
                byte b2 = data.get(startPos + 1);
                byte b3 = data.get(startPos + 2);
                
                if (b2 == (byte) 0xBB && b3 == (byte) 0xBF) {
                    // UTF-8 BOM found, skip it
                    data.position(startPos + 3);
                    return true;
                }
            }
            // Not a UTF-8 BOM, proceed with parsing
            return true;
        }
        
        // Check for UTF-16/32 BOMs (rare, error path)
        // UTF-16 BE: FE FF (2 bytes)
        if (b1 == (byte) 0xFE) {
            if (remaining < 2) {
                return false; // Need more data
            }
            if (data.get(startPos + 1) == (byte) 0xFF) {
                throw new JSONException("UTF-16 BE encoding not supported");
            }
            return true; // Not a BOM
        }
        
        // UTF-16 LE or UTF-32 LE: FF FE ... (need 4 bytes to distinguish)
        if (b1 == (byte) 0xFF) {
            if (remaining < 2) {
                return false; // Need more data
            }
            if (data.get(startPos + 1) == (byte) 0xFE) {
                if (remaining < 4) {
                    return false; // Need 4 bytes to distinguish UTF-16 LE from UTF-32 LE
                }
                // Check for UTF-32 LE: FF FE 00 00
                if (data.get(startPos + 2) == (byte) 0x00 && 
                    data.get(startPos + 3) == (byte) 0x00) {
                    throw new JSONException("UTF-32 LE encoding not supported");
                }
                // UTF-16 LE
                throw new JSONException("UTF-16 LE encoding not supported");
            }
            return true; // Not a BOM
        }
        
        // UTF-32 BE: 00 00 FE FF (4 bytes)
        if (b1 == (byte) 0x00) {
            if (remaining < 2) {
                return false; // Need more data
            }
            if (data.get(startPos + 1) == (byte) 0x00) {
                if (remaining < 4) {
                    return false; // Might be UTF-32 BE BOM
                }
                if (data.get(startPos + 2) == (byte) 0xFE && 
                    data.get(startPos + 3) == (byte) 0xFF) {
                    throw new JSONException("UTF-32 BE encoding not supported");
                }
            }
            return true; // Not a BOM
        }
        
        // No BOM detected, proceed with parsing
        return true;
    }

    /**
     * Maximum CharBuffer size. Processing is done incrementally to avoid
     * allocating a buffer sized for the entire input.
     */
    private static final int MAX_CHAR_BUFFER = 8192;

    /**
     * Decode bytes from the input buffer to characters and process tokens.
     * 
     * <p>This method follows the pattern from gonzalez XML parser:
     * <ul>
     *   <li>ByteBuffer tracks only incomplete multi-byte sequences (handled by CharsetDecoder)</li>
     *   <li>CharBuffer tracks incomplete tokens (managed via compact/flip)</li>
     *   <li>No need to map character positions back to byte positions</li>
     * </ul>
     */
    private void decodeAndProcess(ByteBuffer data) throws JSONException {
        if (!data.hasRemaining()) {
            return;
        }
        
        // Allocate CharBuffer if needed (capped at MAX_CHAR_BUFFER)
        if (charBuffer == null) {
            charBuffer = CharBuffer.allocate(Math.min(bufferSize, MAX_CHAR_BUFFER));
        }
        
        // Ensure charBuffer has space for decoding
        // charBuffer is in write mode with any unconsumed chars from previous call
        int unconsumedChars = charBuffer.position();
        if (charBuffer.remaining() < data.remaining()) {
            // Need to grow charBuffer
            int needed = unconsumedChars + data.remaining();
            int newCapacity = Math.max(needed + 256, charBuffer.capacity() * 2);
            CharBuffer newBuffer = CharBuffer.allocate(newCapacity);
            charBuffer.flip();
            newBuffer.put(charBuffer);
            charBuffer = newBuffer;
        }
        
        // Decode all available bytes into charBuffer
        // The decoder leaves incomplete multi-byte sequences in data
        CoderResult result = decoder.decode(data, charBuffer, closed);
        
        if (result.isError()) {
            try {
                result.throwException();
            } catch (Exception e) {
                throw new JSONException("Character decoding error", e);
            }
        }
        
        // Flip charBuffer to read mode for tokenizer
        charBuffer.flip();
        
        // Tokenize - tokenizer consumes what it can
        tokenizer.receive(charBuffer);
        
        // Compact to preserve unconsumed characters for next call
        charBuffer.compact();
        
        // Note: If decoder had UNDERFLOW (incomplete multi-byte sequence),
        // those bytes remain in data for the caller to preserve via compact().
        // If tokenizer had underflow (incomplete token), those chars are
        // preserved in charBuffer via the compact() above.
    }
}
