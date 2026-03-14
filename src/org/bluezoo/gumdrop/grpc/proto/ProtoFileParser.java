/*
 * ProtoFileParser.java
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

package org.bluezoo.gumdrop.grpc.proto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Push-based parser for .proto files.
 *
 * <p>Parses the Protocol Buffers language (proto3) and builds a {@link ProtoFile}
 * model. Uses the same push model as other Gumdrop parsers: bytes are supplied
 * via {@link #receive(ByteBuffer)}, and {@link #close()} finalizes the parse.
 *
 * <h3>Usage</h3>
 * <pre>
 * ProtoFileParser parser = new ProtoFileParser();
 *
 * while (channel.read(buffer) &gt; 0) {
 *     buffer.flip();
 *     parser.receive(buffer);
 *     buffer.compact();
 * }
 *
 * ProtoFile proto = parser.close();
 * </pre>
 *
 * <p>For parsing from a string (e.g. at startup), use {@link #parse(CharSequence)}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://protobuf.dev/reference/protobuf/proto3-spec/">Proto3 Language Specification</a>
 */
public class ProtoFileParser {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.grpc.proto.L10N");

    private static final Set<String> SCALAR_TYPES = Set.of(
            "double", "float", "int32", "int64", "uint32", "uint64",
            "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64",
            "bool", "string", "bytes");

    private final StringBuilder input = new StringBuilder();
    private int pos;
    private int line = 1;
    private int column = 1;
    private boolean closed;
    private final Set<String> enumFullNames = new HashSet<>();

    /**
     * Pushes bytes into the parser.
     *
     * @param data the buffer (read mode)
     */
    public void receive(ByteBuffer data) {
        if (closed) {
            throw new IllegalStateException("Parser already closed");
        }
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        input.append(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Parses the accumulated input and returns the Proto model.
     *
     * @return the parsed ProtoFile
     * @throws ProtoParseException if the input is malformed
     */
    public ProtoFile close() throws ProtoParseException {
        if (closed) {
            throw new IllegalStateException("Parser already closed");
        }
        closed = true;
        return parseInternal();
    }

    /**
     * Parses a .proto file from a character sequence.
     *
     * @param source the .proto file content
     * @return the parsed ProtoFile
     * @throws ProtoParseException if the input is malformed
     */
    public static ProtoFile parse(CharSequence source) throws ProtoParseException {
        ProtoFileParser parser = new ProtoFileParser();
        parser.input.append(source);
        parser.closed = true;
        return parser.parseInternal();
    }

    private ProtoFile parseInternal() throws ProtoParseException {
        pos = 0;
        line = 1;
        column = 1;

        ProtoFile.Builder fileBuilder = ProtoFile.builder();
        String pkg = "";
        enumFullNames.clear();

        skipWhitespaceAndComments();

        while (pos < input.length()) {
            if (peek() == ';') {
                consume();
                skipWhitespaceAndComments();
                continue;
            }

            String tok = nextIdentifier();
            if (tok == null) {
                break;
            }

            switch (tok) {
                case "syntax":
                    parseSyntax();
                    break;
                case "package":
                    pkg = parsePackage();
                    fileBuilder.packageName(pkg);
                    break;
                case "import":
                    parseImport();
                    break;
                case "option":
                    parseOption();
                    break;
                case "message":
                    MessageDescriptor msg = parseMessage(pkg);
                    fileBuilder.addMessage(msg);
                    break;
                case "enum":
                    EnumDescriptor enm = parseEnum(pkg);
                    enumFullNames.add(enm.getFullName());
                    fileBuilder.addEnum(enm);
                    break;
                case "service":
                    ServiceDescriptor svc = parseService(pkg);
                    fileBuilder.addService(svc);
                    break;
                default:
                    throw parseError(L10N.getString("err.unexpected_char"),
                            String.valueOf(peek()), line);
            }

            skipWhitespaceAndComments();
        }

        return fileBuilder.build();
    }

    private void parseSyntax() throws ProtoParseException {
        expect('=');
        String val = nextString();
        if (val == null || (!val.equals("proto3") && !val.equals("proto2"))) {
            throw parseError(L10N.getString("err.invalid_syntax"));
        }
        expect(';');
    }

    private String parsePackage() throws ProtoParseException {
        String fullIdent = nextFullIdent();
        if (fullIdent == null) {
            throw parseError(L10N.getString("err.expected_ident"));
        }
        expect(';');
        return fullIdent;
    }

    private void parseImport() throws ProtoParseException {
        nextString();
        expect(';');
    }

    private void parseOption() throws ProtoParseException {
        nextFullIdent();
        expect('=');
        parseConstant();
        expect(';');
    }

    private Object parseConstant() throws ProtoParseException {
        skipWhitespaceAndComments();
        if (peek() == '"' || peek() == '\'') {
            return nextString();
        }
        if (peek() == '-' || peek() == '+' || Character.isDigit(peek())) {
            return nextNumber();
        }
        String ident = nextFullIdent();
        if (ident != null) {
            if (ident.equals("true")) return true;
            if (ident.equals("false")) return false;
            return ident;
        }
        throw parseError(L10N.getString("err.expected_string"));
    }

    private MessageDescriptor parseMessage(String pkg) throws ProtoParseException {
        String name = nextIdentifier();
        if (name == null) {
            throw parseError(L10N.getString("err.expected_ident"));
        }
        String fullName = pkg.isEmpty() ? name : pkg + "." + name;
        expect('{');

        MessageDescriptor.Builder msgBuilder = MessageDescriptor.builder()
                .name(name)
                .fullName(fullName);

        Set<Integer> fieldNumbers = new HashSet<>();

        skipWhitespaceAndComments();
        while (pos < input.length() && peek() != '}') {
            if (peek() == ';') {
                consume();
                skipWhitespaceAndComments();
                continue;
            }

            String tok = nextIdentifier();
            if (tok == null) break;

            switch (tok) {
                case "option":
                    parseOption();
                    break;
                case "reserved":
                    parseReserved();
                    break;
                case "message":
                    MessageDescriptor nested = parseMessage(fullName);
                    msgBuilder.addNestedMessage(nested);
                    break;
                case "enum":
                    EnumDescriptor nestedEnum = parseEnum(fullName);
                    msgBuilder.addNestedEnum(nestedEnum);
                    break;
                case "oneof":
                    parseOneof(msgBuilder, fullName, fieldNumbers);
                    break;
                case "map":
                    FieldDescriptor mapField = parseMapField(fullName, fieldNumbers);
                    if (mapField != null) msgBuilder.addField(mapField);
                    break;
                case "repeated":
                case "optional":
                    FieldDescriptor optField = parseField(tok, fullName, fieldNumbers);
                    if (optField != null) msgBuilder.addField(optField);
                    break;
                default:
                    FieldDescriptor field = parseField(null, fullName, fieldNumbers, tok);
                    if (field != null) msgBuilder.addField(field);
                    break;
            }
            skipWhitespaceAndComments();
        }

        expect('}');
        return msgBuilder.build();
    }

    private void parseReserved() throws ProtoParseException {
        skipWhitespaceAndComments();
        while (peek() != ';') {
            if (peek() == '"' || peek() == '\'') {
                nextString();
            } else {
                nextNumber();
                if (peek() == 't') {
                    nextIdentifier();
                    nextIdentifier();
                }
            }
            skipWhitespaceAndComments();
            if (peek() == ',') {
                consume();
                skipWhitespaceAndComments();
            }
        }
        expect(';');
    }

    private void parseOneof(MessageDescriptor.Builder msgBuilder, String parentFullName,
                           Set<Integer> fieldNumbers) throws ProtoParseException {
        String oneofName = nextIdentifier();
        expect('{');
        skipWhitespaceAndComments();
        while (peek() != '}') {
            FieldDescriptor f = parseField(null, parentFullName, fieldNumbers);
            if (f != null) {
                msgBuilder.addField(f);
            }
            skipWhitespaceAndComments();
        }
        expect('}');
    }

    private FieldDescriptor parseMapField(String parentFullName, Set<Integer> fieldNumbers)
            throws ProtoParseException {
        expect('<');
        String keyType = nextIdentifier();
        expect(',');
        String valueType = nextTypeName();
        expect('>');
        String name = nextIdentifier();
        expect('=');
        int num = nextInt();
        if (fieldNumbers.contains(num)) {
            throw parseError(L10N.getString("err.duplicate_field_number"),
                    String.valueOf(num), parentFullName);
        }
        fieldNumbers.add(num);
        parseFieldOptions();
        expect(';');

        FieldType keyFt = scalarTypeFromName(keyType);
        FieldType valueFt = valueType.startsWith(".") || Character.isUpperCase(valueType.charAt(0))
                ? FieldType.MESSAGE : scalarTypeFromName(valueType);

        return FieldDescriptor.builder()
                .number(num)
                .name(name)
                .type(FieldType.MAP)
                .keyTypeName(keyType)
                .valueTypeName(valueType)
                .build();
    }

    private FieldDescriptor parseField(String modifier, String parentFullName,
                                       Set<Integer> fieldNumbers) throws ProtoParseException {
        return parseField(modifier, parentFullName, fieldNumbers, null);
    }

    private FieldDescriptor parseField(String modifier, String parentFullName,
                                       Set<Integer> fieldNumbers, String typeOverride)
            throws ProtoParseException {
        boolean repeated = "repeated".equals(modifier);
        boolean optional = "optional".equals(modifier);

        String typeName = typeOverride != null ? typeOverride : nextTypeName();
        String name = nextIdentifier();
        expect('=');
        int num = nextInt();
        if (fieldNumbers.contains(num)) {
            throw parseError(L10N.getString("err.duplicate_field_number"),
                    String.valueOf(num), parentFullName);
        }
        fieldNumbers.add(num);
        parseFieldOptions();
        expect(';');

        FieldType type;
        String messageTypeName = null;
        String enumTypeName = null;

        if (SCALAR_TYPES.contains(typeName)) {
            type = scalarTypeFromName(typeName);
        } else {
            String fullTypeName = typeName.startsWith(".") ? typeName.substring(1)
                    : parentFullName.isEmpty() ? typeName : resolveType(parentFullName, typeName);
            if (enumFullNames.contains(fullTypeName)) {
                type = FieldType.ENUM;
                enumTypeName = fullTypeName;
            } else {
                type = typeName.equals("map") ? FieldType.MAP : FieldType.MESSAGE;
                if (type == FieldType.MESSAGE) {
                    messageTypeName = fullTypeName;
                }
            }
        }

        return FieldDescriptor.builder()
                .number(num)
                .name(name)
                .type(type)
                .repeated(repeated)
                .optional(optional)
                .messageTypeName(messageTypeName)
                .enumTypeName(enumTypeName)
                .build();
    }

    private void parseFieldOptions() throws ProtoParseException {
        skipWhitespaceAndComments();
        if (peek() == '[') {
            consume();
            do {
                nextFullIdent();
                expect('=');
                parseConstant();
                skipWhitespaceAndComments();
            } while (peek() == ',');
            expect(']');
        }
    }

    private String resolveType(String parentFullName, String typeName) {
        int dot = parentFullName.lastIndexOf('.');
        String parentPkg = dot >= 0 ? parentFullName.substring(0, dot) : "";
        return parentPkg.isEmpty() ? typeName : parentPkg + "." + typeName;
    }

    private EnumDescriptor parseEnum(String pkg) throws ProtoParseException {
        String name = nextIdentifier();
        if (name == null) {
            throw parseError(L10N.getString("err.expected_ident"));
        }
        String fullName = pkg.isEmpty() ? name : pkg + "." + name;
        expect('{');

        EnumDescriptor.Builder enumBuilder = EnumDescriptor.builder()
                .name(name)
                .fullName(fullName);

        skipWhitespaceAndComments();
        while (pos < input.length() && peek() != '}') {
            if (peek() == ';') {
                consume();
                skipWhitespaceAndComments();
                continue;
            }

            String tok = nextIdentifier();
            if (tok == null) break;

            if (tok.equals("option") || tok.equals("reserved")) {
                if (tok.equals("option")) parseOption();
                else parseReserved();
            } else {
                String valueName = tok;
                expect('=');
                int num = nextInt();
                parseFieldOptions();
                expect(';');
                enumBuilder.addValue(num, valueName);
            }
            skipWhitespaceAndComments();
        }

        expect('}');
        return enumBuilder.build();
    }

    private ServiceDescriptor parseService(String pkg) throws ProtoParseException {
        String name = nextIdentifier();
        if (name == null) {
            throw parseError(L10N.getString("err.expected_ident"));
        }
        String fullName = pkg.isEmpty() ? name : pkg + "." + name;
        expect('{');

        ServiceDescriptor.Builder svcBuilder = ServiceDescriptor.builder()
                .name(name)
                .fullName(fullName);

        skipWhitespaceAndComments();
        while (pos < input.length() && peek() != '}') {
            if (peek() == ';') {
                consume();
                skipWhitespaceAndComments();
                continue;
            }

            String tok = nextIdentifier();
            if (tok == null) break;

            if (tok.equals("option")) {
                parseOption();
            } else if (tok.equals("rpc")) {
                RpcDescriptor rpc = parseRpc(pkg);
                svcBuilder.addRpc(rpc);
            }
            skipWhitespaceAndComments();
        }

        expect('}');
        return svcBuilder.build();
    }

    private RpcDescriptor parseRpc(String pkg) throws ProtoParseException {
        String name = nextIdentifier();
        expect('(');
        String tok = nextIdentifier();
        boolean clientStreaming = "stream".equals(tok);
        String inputType = clientStreaming ? nextTypeName() : (tok != null ? tok : nextTypeName());
        if (!clientStreaming && tok != null && peek() == '.') {
            consume();
            inputType = inputType + "." + nextFullIdent();
        }
        expect(')');
        String returns = nextIdentifier();
        if (!"returns".equals(returns)) {
            throw parseError(L10N.getString("err.invalid_rpc"));
        }
        expect('(');
        tok = nextIdentifier();
        boolean serverStreaming = "stream".equals(tok);
        String outputType = serverStreaming ? nextTypeName() : (tok != null ? tok : nextTypeName());
        if (!serverStreaming && tok != null && peek() == '.') {
            consume();
            outputType = outputType + "." + nextFullIdent();
        }
        expect(')');
        if (peek() == '{') {
            expect('{');
            while (peek() != '}') {
                parseOption();
            }
            expect('}');
        } else {
            expect(';');
        }

        String inFull = inputType.startsWith(".") ? inputType.substring(1)
                : pkg.isEmpty() ? inputType : pkg + "." + inputType;
        String outFull = outputType.startsWith(".") ? outputType.substring(1)
                : pkg.isEmpty() ? outputType : pkg + "." + outputType;

        return RpcDescriptor.builder()
                .name(name)
                .inputTypeName(inFull)
                .outputTypeName(outFull)
                .clientStreaming(clientStreaming)
                .serverStreaming(serverStreaming)
                .build();
    }

    private char peek() {
        return pos < input.length() ? input.charAt(pos) : (char) -1;
    }

    private char consume() throws ProtoParseException {
        if (pos >= input.length()) {
            throw new ProtoParseException(L10N.getString("err.unexpected_eof"));
        }
        char c = input.charAt(pos++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private void expect(char expected) throws ProtoParseException {
        skipWhitespaceAndComments();
        char c = peek();
        if (c != expected) {
            throw parseError(L10N.getString("err.unexpected_char"),
                    String.valueOf(c), String.valueOf(line));
        }
        consume();
    }

    private void skipWhitespaceAndComments() throws ProtoParseException {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                consume();
            } else if (c == '/' && pos + 1 < input.length()) {
                char next = input.charAt(pos + 1);
                if (next == '/') {
                    pos += 2;
                    while (pos < input.length() && input.charAt(pos) != '\n') pos++;
                    if (pos < input.length()) pos++;
                    line++;
                    column = 1;
                } else if (next == '*') {
                    pos += 2;
                    while (pos + 1 < input.length()) {
                        if (input.charAt(pos) == '*' && input.charAt(pos + 1) == '/') {
                            pos += 2;
                            break;
                        }
                        if (input.charAt(pos) == '\n') line++;
                        pos++;
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private String nextIdentifier() throws ProtoParseException {
        skipWhitespaceAndComments();
        if (pos >= input.length()) return null;
        char c = input.charAt(pos);
        if (!Character.isLetter(c) && c != '_') return null;
        int start = pos;
        while (pos < input.length()) {
            c = input.charAt(pos);
            if (!Character.isLetterOrDigit(c) && c != '_') break;
            pos++;
            column++;
        }
        return input.substring(start, pos);
    }

    private String nextFullIdent() throws ProtoParseException {
        StringBuilder sb = new StringBuilder();
        String part = nextIdentifier();
        if (part == null) return null;
        sb.append(part);
        skipWhitespaceAndComments();
        while (pos < input.length() && peek() == '.') {
            consume();
            part = nextIdentifier();
            if (part == null) break;
            sb.append('.').append(part);
            skipWhitespaceAndComments();
        }
        return sb.toString();
    }

    private String nextTypeName() throws ProtoParseException {
        skipWhitespaceAndComments();
        if (peek() == '.') {
            consume();
            return "." + nextFullIdent();
        }
        return nextFullIdent();
    }

    private String nextString() throws ProtoParseException {
        skipWhitespaceAndComments();
        char quote = peek();
        if (quote != '"' && quote != '\'') return null;
        consume();
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == quote) {
                pos++;
                column++;
                break;
            }
            if (c == '\\') {
                consume();
                if (pos >= input.length()) throw parseError(L10N.getString("err.unexpected_eof"));
                c = input.charAt(pos);
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    case '"': sb.append('"'); break;
                    default: sb.append(c); break;
                }
                consume();
            } else {
                sb.append(c);
                consume();
            }
        }
        return sb.toString();
    }

    private Number nextNumber() throws ProtoParseException {
        skipWhitespaceAndComments();
        int start = pos;
        if (peek() == '-' || peek() == '+') consume();
        while (pos < input.length() && Character.isDigit(peek())) consume();
        if (pos == start || (pos == start + 1 && (input.charAt(start) == '-' || input.charAt(start) == '+'))) {
            throw parseError(L10N.getString("err.expected_number"));
        }
        String s = input.substring(start, pos);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e2) {
                throw parseError(L10N.getString("err.expected_number"));
            }
        }
    }

    private int nextInt() throws ProtoParseException {
        Number n = nextNumber();
        return n.intValue();
    }

    private FieldType scalarTypeFromName(String name) throws ProtoParseException {
        return switch (name) {
            case "double" -> FieldType.DOUBLE;
            case "float" -> FieldType.FLOAT;
            case "int32" -> FieldType.INT32;
            case "int64" -> FieldType.INT64;
            case "uint32" -> FieldType.UINT32;
            case "uint64" -> FieldType.UINT64;
            case "sint32" -> FieldType.SINT32;
            case "sint64" -> FieldType.SINT64;
            case "fixed32" -> FieldType.FIXED32;
            case "fixed64" -> FieldType.FIXED64;
            case "sfixed32" -> FieldType.SFIXED32;
            case "sfixed64" -> FieldType.SFIXED64;
            case "bool" -> FieldType.BOOL;
            case "string" -> FieldType.STRING;
            case "bytes" -> FieldType.BYTES;
            default -> throw parseError(L10N.getString("err.unknown_type"), name);
        };
    }

    private ProtoParseException parseError(String key, Object... args) {
        String msg = args.length > 0 ? MessageFormat.format(key, args) : key;
        return new ProtoParseException(msg + " (line " + line + ")");
    }
}
