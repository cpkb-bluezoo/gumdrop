/*
 * ContentTypeParser.java
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

package org.bluezoo.gumdrop.mime;

import org.bluezoo.gumdrop.mime.rfc2047.RFC2047Decoder;
import org.bluezoo.gumdrop.mime.rfc2231.RFC2231Decoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parser for MIME Content-Type header values.
 * @see <a href='https://www.rfc-editor.org/rfc/rfc2045#section-5'>RFC 2045</a>
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class ContentTypeParser {

	private ContentTypeParser() {
		// Static utility class
	}

	/**
	 * Parses a Content-Type header value from bytes, decoding only the particles required.
	 * Type, subtype, and parameter names/values are decoded with the given decoder (reused).
	 *
	 * @param value the header value bytes (position to limit)
	 * @param decoder charset decoder for decoding slices (e.g. ISO-8859-1); will be reset per use
	 * @return the parsed ContentType, or null if the value is invalid
	 */
	public static ContentType parse(ByteBuffer value, CharsetDecoder decoder) {
		if (value == null || !value.hasRemaining()) {
			return null;
		}
		int start = value.position();
		int end = value.limit();
		int len = end - start;
		if (len < 3) {
			return null;
		}
		value.position(start + 3);
		int semicolonIndex = MIMEParser.indexOf(value, (byte) ';');
		int typeEnd = semicolonIndex < 0 ? end : semicolonIndex;
		value.position(start + 1);
		int slashIndex = MIMEParser.indexOf(value, (byte) '/');
		if (slashIndex < 0 || slashIndex >= typeEnd) {
			value.position(start);
			value.limit(end);
			return null;
		}
		value.position(start);
		value.limit(slashIndex);
		String primaryType = MIMEParser.decodeSlice(value, decoder);
		value.limit(end);
		value.position(slashIndex + 1);
		value.limit(typeEnd);
		String subType = MIMEParser.decodeSlice(value, decoder);
		value.limit(end);
		if (primaryType == null || subType == null || !MIMEUtils.isToken(primaryType) || !MIMEUtils.isToken(subType)) {
			value.position(start);
			return null;
		}
		int paramsStart = semicolonIndex < 0 ? end : semicolonIndex + 1;
		value.position(paramsStart);
		value.limit(end);
		List<Parameter> parameters = parseParameterList(value, decoder);
		value.position(end);
		return new ContentType(primaryType, subType, parameters);
	}

	/**
	 * Parses a Content-Type header value from a string (convenience).
	 * Wraps the string in a ByteBuffer and uses a default decoder.
	 *
	 * @param value the header value string
	 * @return the parsed ContentType, or null if the value is invalid
	 */
	public static ContentType parse(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		ByteBuffer buf = ByteBuffer.wrap(value.getBytes(StandardCharsets.ISO_8859_1));
		CharsetDecoder decoder = StandardCharsets.ISO_8859_1.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);
		return parse(buf, decoder);
	}

	/**
	 * Parses a semicolon-separated list of parameters from a byte buffer.
	 * Uses buf.position() and buf.limit(); advances position as parameters are consumed.
	 * Handles RFC 2231 extended parameters and continuations.
	 *
	 * @param buf the buffer (position at params start, limit at params end); position advanced to limit
	 * @param decoder charset decoder for decoding slices
	 * @return list of parameters, or null if none
	 */
	static List<Parameter> parseParameterList(ByteBuffer buf, CharsetDecoder decoder) {
		int paramsEnd = buf.limit();
		if (!buf.hasRemaining()) {
			return null;
		}
		List<RawParamSlice> rawParams = new ArrayList<>();
		while (buf.position() < paramsEnd) {
			skipOws(buf, paramsEnd);
			if (buf.position() >= paramsEnd) {
				break;
			}
			int pos = buf.position();
			int equalsIndex = MIMEParser.indexOf(buf, (byte) '=');
			if (equalsIndex < 0 || equalsIndex < pos + 1) {
				int nextSemi = MIMEParser.indexOf(buf, (byte) ';');
				buf.position(nextSemi >= 0 ? nextSemi : paramsEnd);
				continue;
			}
			buf.limit(equalsIndex);
			String name = MIMEParser.decodeSlice(buf, decoder);
			buf.limit(paramsEnd);
			if (name == null || !MIMEUtils.isToken(name)) {
				buf.position(equalsIndex + 1);
				int nextSemi = MIMEParser.indexOf(buf, (byte) ';');
				buf.position(nextSemi >= 0 ? nextSemi : paramsEnd);
				continue;
			}
			buf.position(equalsIndex + 1);
			int valueStart = buf.position();
			int valueEnd;
			boolean quoted = false;
			if (buf.position() < paramsEnd && buf.get(buf.position()) == '"') {
				quoted = true;
				int quoteEnd = findQuotedValueEnd(buf, buf.position(), paramsEnd);
				if (quoteEnd < 0) {
					break;
				}
				valueEnd = quoteEnd;
				buf.position(quoteEnd);
			} else {
				int semicolonIdx = MIMEParser.indexOf(buf, (byte) ';');
				if (semicolonIdx < 0) {
					semicolonIdx = paramsEnd;
				}
				valueEnd = semicolonIdx;
				buf.position(semicolonIdx);
				valueStart = skipOwsForward(buf, valueStart, valueEnd);
				valueEnd = skipOwsBackward(buf, valueStart, valueEnd);
			}
			rawParams.add(new RawParamSlice(name, valueStart, valueEnd, quoted));
		}
		buf.position(paramsEnd);
		return processRawParamsFromSlices(buf, rawParams, decoder);
	}

	private static int skipOwsForward(ByteBuffer buf, int start, int end) {
		while (start < end) {
			byte b = buf.get(start);
			if (b != ' ' && b != '\t') {
				break;
			}
			start++;
		}
		return start;
	}

	private static int skipOwsBackward(ByteBuffer buf, int start, int end) {
		while (end > start) {
			byte b = buf.get(end - 1);
			if (b != ' ' && b != '\t') {
				break;
			}
			end--;
		}
		return end;
	}

	/** Advances buf.position() past ';' and OWS until position >= end or non-OWS byte. */
	private static void skipOws(ByteBuffer buf, int end) {
		while (buf.position() < end) {
			byte b = buf.get(buf.position());
			if (b != ';' && b != ' ' && b != '\t') {
				break;
			}
			buf.position(buf.position() + 1);
		}
	}

	private static int findQuotedValueEnd(ByteBuffer buf, int start, int end) {
		int p = start + 1;
		while (p < end) {
			byte b = buf.get(p);
			if (b == '\\' && p + 1 < end) {
				p += 2;
				continue;
			}
			if (b == '"') {
				return p + 1;
			}
			p++;
		}
		return -1;
	}

	private static String decodeQuotedContent(ByteBuffer buf, int start, int end, CharsetDecoder decoder) {
		if (start >= end) {
			return "";
		}
		java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream(end - start);
		int p = start;
		while (p < end) {
			byte b = buf.get(p);
			if (b == '\\' && p + 1 < end) {
				content.write(buf.get(p + 1));
				p += 2;
			} else {
				content.write(b);
				p++;
			}
		}
		byte[] bytes = content.toByteArray();
		ByteBuffer slice = ByteBuffer.wrap(bytes);
		try {
			decoder.reset();
			CharBuffer out = CharBuffer.allocate(slice.remaining() * 2);
			decoder.decode(slice, out, true);
			decoder.flush(out);
			out.flip();
			return out.toString();
		} catch (Exception e) {
			return new String(bytes, decoder.charset());
		}
	}

	/**
	 * Parses a semicolon-separated list of parameters from a string.
	 * Handles RFC 2231 extended parameters (name*=charset''value) and continuations (name*0, name*1).
	 * Parameter values are returned in canonical form (no surrounding quotes).
	 */
	static List<Parameter> parseParameterList(String paramsPart) {
		if (paramsPart == null || paramsPart.isEmpty()) {
			return null;
		}

		List<RawParam> rawParams = new ArrayList<>();
		int pos = 0;
		int len = paramsPart.length();

		while (pos < len) {
			// Skip whitespace and semicolons
			while (pos < len && (paramsPart.charAt(pos) == ';' ||
								  Character.isWhitespace(paramsPart.charAt(pos)))) {
				pos++;
			}
			if (pos >= len) {
				break;
			}

			int equalsIndex = paramsPart.indexOf('=', pos);
			if (equalsIndex < 1) {
				int nextSemi = paramsPart.indexOf(';', pos);
				if (nextSemi >= 0) {
					pos = nextSemi;
					continue;
				} else {
					break;
				}
			}

			String name = paramsPart.substring(pos, equalsIndex).trim();
			if (!MIMEUtils.isToken(name)) {
				int nextSemi = paramsPart.indexOf(';', pos);
				if (nextSemi >= 0) {
					pos = nextSemi;
					continue;
				} else {
					break;
				}
			}

			pos = equalsIndex + 1;
			String paramValue;

			if (pos < len && paramsPart.charAt(pos) == '"') {
				pos++;
				StringBuilder sb = new StringBuilder();
				while (pos < len) {
					char c = paramsPart.charAt(pos);
					if (c == '\\' && pos + 1 < len) {
						sb.append(paramsPart.charAt(pos + 1));
						pos += 2;
					} else if (c == '"') {
						pos++;
						break;
					} else {
						sb.append(c);
						pos++;
					}
				}
				paramValue = sb.toString();
			} else {
				int semicolonIdx = paramsPart.indexOf(';', pos);
				if (semicolonIdx < 0) {
					semicolonIdx = len;
				}
				paramValue = paramsPart.substring(pos, semicolonIdx).trim();
				if (!MIMEUtils.isToken(paramValue)) {
					pos = semicolonIdx;
					continue;
				}
				pos = semicolonIdx;
			}

			rawParams.add(new RawParam(name, paramValue));
		}

		return processRawParams(rawParams);
	}

	/**
	 * Processes raw parameter slices from ByteBuffer using RFC2231Decoder and RFC2047Decoder
	 * (ByteBuffer-in). Merges name*0/name*1 continuations into one ByteBuffer before decode.
	 */
	private static List<Parameter> processRawParamsFromSlices(ByteBuffer buf, List<RawParamSlice> rawParams, CharsetDecoder decoder) {
		if (rawParams == null || rawParams.isEmpty()) {
			return null;
		}
		Map<String, String> rfc2231Decoded = new LinkedHashMap<>();
		Map<String, TreeMap<Integer, int[]>> continuationRanges = new LinkedHashMap<>();

		for (RawParamSlice r : rawParams) {
			String name = r.name;
			int starIdx = name.indexOf('*');
			if (starIdx >= 0 && starIdx < name.length() - 1) {
				String after = name.substring(starIdx + 1);
				if (isAllDigits(after)) {
					String baseName = name.substring(0, starIdx);
					int index = Integer.parseInt(after);
					continuationRanges.computeIfAbsent(baseName, k -> new TreeMap<>()).put(index, new int[] { r.valueStart, r.valueEnd });
					continue;
				}
			}
			if (name.endsWith("*") && name.length() > 1) {
				String baseName = name.substring(0, name.length() - 1);
				ByteBuffer slice = buf.duplicate();
				slice.position(r.valueStart).limit(r.valueEnd);
				String decoded = RFC2231Decoder.decodeParameterValue(slice, decoder);
				if (decoded != null) {
					rfc2231Decoded.put(baseName, decoded);
				}
				continue;
			}
		}

		for (Map.Entry<String, TreeMap<Integer, int[]>> e : continuationRanges.entrySet()) {
			String baseName = e.getKey();
			if (rfc2231Decoded.containsKey(baseName)) {
				continue;
			}
			TreeMap<Integer, int[]> parts = e.getValue();
			int totalLen = 0;
			for (int[] range : parts.values()) {
				totalLen += range[1] - range[0];
			}
			byte[] combined = new byte[totalLen];
			int off = 0;
			for (Integer idx : parts.keySet()) {
				int[] range = parts.get(idx);
				int len = range[1] - range[0];
				for (int i = 0; i < len; i++) {
					combined[off++] = buf.get(range[0] + i);
				}
			}
			ByteBuffer combinedBuf = ByteBuffer.wrap(combined);
			String decoded = RFC2231Decoder.decodeParameterValue(combinedBuf, decoder);
			if (decoded != null) {
				rfc2231Decoded.put(baseName, decoded);
			}
		}

		List<Parameter> parameters = new ArrayList<>();
		Map<String, String> seen = new LinkedHashMap<>();

		for (RawParamSlice r : rawParams) {
			String name = r.name;
			String baseName = getBaseParamName(name);
			if (seen.containsKey(baseName)) {
				continue;
			}
			String finalValue;
			if (rfc2231Decoded.containsKey(baseName)) {
				finalValue = rfc2231Decoded.get(baseName);
			} else {
				if (r.quoted && r.valueEnd - r.valueStart >= 2) {
					byte[] unescaped = unescapeQuotedValue(buf, r.valueStart + 1, r.valueEnd - 1);
					ByteBuffer slice = ByteBuffer.wrap(unescaped);
					String raw = MIMEParser.decodeSlice(slice, decoder);
					finalValue = RFC2047Decoder.decodeEncodedWords(raw);
				} else {
					ByteBuffer slice = buf.duplicate();
					slice.position(r.valueStart).limit(r.valueEnd);
					finalValue = RFC2047Decoder.decodeParameterValue(slice, decoder, false);
				}
			}
			seen.put(baseName, finalValue);
			parameters.add(new Parameter(baseName, finalValue));
		}

		return parameters.isEmpty() ? null : parameters;
	}

	private static String getBaseParamName(String name) {
		int starIdx = name.indexOf('*');
		if (name.endsWith("*") && name.length() > 1 && (starIdx < 0 || starIdx == name.length() - 1)) {
			return name.substring(0, name.length() - 1);
		}
		if (starIdx >= 0 && starIdx < name.length() - 1) {
			String after = name.substring(starIdx + 1);
			if (isAllDigits(after)) {
				return name.substring(0, starIdx);
			}
		}
		return name;
	}

	private static List<Parameter> processRawParams(List<RawParam> rawParams) {
		if (rawParams == null || rawParams.isEmpty()) {
			return null;
		}
		Map<String, String> rfc2231Decoded = new LinkedHashMap<>();
		Map<String, TreeMap<Integer, String>> continuations = new LinkedHashMap<>();

		for (RawParam r : rawParams) {
			String name = r.name;
			String value = r.value;
			int starIdx = name.indexOf('*');
			if (starIdx >= 0 && starIdx < name.length() - 1) {
				String after = name.substring(starIdx + 1);
				if (isAllDigits(after)) {
					String baseName = name.substring(0, starIdx);
					int index = Integer.parseInt(after);
					continuations.computeIfAbsent(baseName, k -> new TreeMap<>()).put(index, value);
					continue;
				}
			}
			if (name.endsWith("*") && name.length() > 1) {
				String baseName = name.substring(0, name.length() - 1);
				String decoded = RFC2047Decoder.decodeRFC2231Parameter(name + "=" + value);
				rfc2231Decoded.put(baseName, decoded);
				continue;
			}
		}

		for (Map.Entry<String, TreeMap<Integer, String>> e : continuations.entrySet()) {
			String baseName = e.getKey();
			if (rfc2231Decoded.containsKey(baseName)) {
				continue;
			}
			TreeMap<Integer, String> parts = e.getValue();
			String firstValue = parts.get(parts.firstKey());
			int quote2 = firstValue.indexOf("''");
			StringBuilder encodedParts = new StringBuilder();
			for (Integer idx : parts.keySet()) {
				String part = parts.get(idx);
				if (quote2 >= 0 && idx.equals(parts.firstKey())) {
					encodedParts.append(part.substring(quote2 + 2));
				} else {
					encodedParts.append(part);
				}
			}
			String toDecode = quote2 >= 0
				? baseName + "*=" + firstValue.substring(0, quote2 + 2) + encodedParts.toString()
				: baseName + "*=" + encodedParts.toString();
			String decoded = RFC2047Decoder.decodeRFC2231Parameter(toDecode);
			if (decoded != null) {
				rfc2231Decoded.put(baseName, decoded);
			}
		}

		List<Parameter> parameters = new ArrayList<>();
		Map<String, String> seen = new LinkedHashMap<>();

		for (RawParam r : rawParams) {
			String name = r.name;
			String baseName = getBaseParamName(name);
			if (seen.containsKey(baseName)) {
				continue;
			}
			String finalValue;
			if (rfc2231Decoded.containsKey(baseName)) {
				finalValue = rfc2231Decoded.get(baseName);
			} else {
				finalValue = RFC2047Decoder.decodeEncodedWords(r.value);
			}
			seen.put(baseName, finalValue);
			parameters.add(new Parameter(baseName, finalValue));
		}

		return parameters.isEmpty() ? null : parameters;
	}

	private static boolean isAllDigits(String s) {
		if (s == null || s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static class RawParam {
		final String name;
		final String value;

		RawParam(String name, String value) {
			this.name = name;
			this.value = value;
		}
	}

	private static byte[] unescapeQuotedValue(ByteBuffer buf, int start, int end) {
		byte[] out = new byte[end - start];
		int w = 0;
		int i = start;
		while (i < end) {
			byte b = buf.get(i);
			if (b == '\\' && i + 1 < end) {
				out[w++] = buf.get(i + 1);
				i += 2;
			} else {
				out[w++] = b;
				i++;
			}
		}
		if (w == out.length) {
			return out;
		}
		byte[] trimmed = new byte[w];
		System.arraycopy(out, 0, trimmed, 0, w);
		return trimmed;
	}

	private static class RawParamSlice {
		final String name;
		final int valueStart;
		final int valueEnd;
		final boolean quoted;

		RawParamSlice(String name, int valueStart, int valueEnd, boolean quoted) {
			this.name = name;
			this.valueStart = valueStart;
			this.valueEnd = valueEnd;
			this.quoted = quoted;
		}
	}

}

