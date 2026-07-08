import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpRequestParser {
    public static ParsedRequest parseRequest(byte[] requestBytes) {
        try {
            if (requestBytes == null || requestBytes.length == 0) {
                return ParsedRequest.invalid();
            }

            String requestText = new String(requestBytes, StandardCharsets.ISO_8859_1);
        HeaderBoundary headerBoundary = findHeaderBoundary(requestText);
        if (headerBoundary == null) {
            return null;
        }

        String headerSection = requestText.substring(0, headerBoundary.index);
        String[] lines = headerSection.split("\\r?\\n");
        if (lines.length == 0) {
            return ParsedRequest.invalid();
        }

        String[] requestLineParts = lines[0].trim().split("\\s+");
        if (requestLineParts.length != 3) {
            return ParsedRequest.invalid();
        }

        String method = requestLineParts[0];
        String target = requestLineParts[1];
        String version = requestLineParts[2];
        if (!version.startsWith("HTTP/")) {
            return ParsedRequest.invalid();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            String headerLine = lines[lineIndex];
            if (headerLine.isBlank()) {
                continue;
            }
            int separatorIndex = headerLine.indexOf(':');
            if (separatorIndex <= 0) {
                return ParsedRequest.invalid();
            }
            String headerName = headerLine.substring(0, separatorIndex).trim().toLowerCase();
            String headerValue = headerLine.substring(separatorIndex + 1).trim();
            headers.put(headerName, headerValue);
        }

        int bodyStartIndex = headerBoundary.index + headerBoundary.separatorLength;
        int contentLength = parseContentLength(headers.get("content-length"));
        if (contentLength < 0) {
            return ParsedRequest.invalid();
        }
        boolean chunked = headers.containsKey("transfer-encoding")
                && headers.get("transfer-encoding").toLowerCase().contains("chunked");

        String body = "";
        if (chunked) {
            ChunkedParseResult chunkedParseResult = parseChunkedBody(requestText, bodyStartIndex);
            if (!chunkedParseResult.complete) {
                return null;
            }
            body = chunkedParseResult.body;
        } else if (contentLength >= 0) {
            if (requestBytes.length < bodyStartIndex + contentLength) {
                return null;
            }
            body = readBody(requestBytes, bodyStartIndex, contentLength);
        } else if (requestBytes.length > bodyStartIndex) {
            body = readBody(requestBytes, bodyStartIndex, requestBytes.length - bodyStartIndex);
        }

        Map<String, String> cookies = parseCookies(headers.get("cookie"));
        String path = target;
        String query = "";
        int querySeparatorIndex = target.indexOf('?');
        if (querySeparatorIndex >= 0) {
            path = target.substring(0, querySeparatorIndex);
            query = target.substring(querySeparatorIndex + 1);
        }

        return ParsedRequest.valid(method, target, path, query, version, headers, cookies, body, chunked, contentLength);
        } catch (Exception exception) {
            return ParsedRequest.invalid();
        }
    }

    private static HeaderBoundary findHeaderBoundary(String requestText) {
        int headerEndIndex = requestText.indexOf("\r\n\r\n");
        if (headerEndIndex >= 0) {
            return new HeaderBoundary(headerEndIndex, 4);
        }

        headerEndIndex = requestText.indexOf("\n\n");
        if (headerEndIndex >= 0) {
            return new HeaderBoundary(headerEndIndex, 2);
        }

        return null;
    }

    private static String readBody(byte[] requestBytes, int bodyStartIndex, int bodyLength) {
        if (bodyLength <= 0) {
            return "";
        }
        return new String(requestBytes, bodyStartIndex, bodyLength, StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return cookies;
        }

        for (String cookiePart : cookieHeader.split(";")) {
            String trimmedCookie = cookiePart.trim();
            if (trimmedCookie.isEmpty()) {
                continue;
            }
            int separatorIndex = trimmedCookie.indexOf('=');
            if (separatorIndex <= 0) {
                cookies.put(trimmedCookie, "");
                continue;
            }
            String name = trimmedCookie.substring(0, separatorIndex).trim();
            String value = trimmedCookie.substring(separatorIndex + 1).trim();
            cookies.put(name, value);
        }
        return cookies;
    }

    private static ChunkedParseResult parseChunkedBody(String requestText, int bodyStartIndex) {
        int offset = bodyStartIndex;
        StringBuilder bodyBuilder = new StringBuilder();

        while (offset < requestText.length()) {
            int lineEndIndex = requestText.indexOf("\r\n", offset);
            if (lineEndIndex == -1) {
                lineEndIndex = requestText.indexOf("\n", offset);
            }
            if (lineEndIndex == -1) {
                return new ChunkedParseResult(false, "");
            }

            String sizeLine = requestText.substring(offset, lineEndIndex).trim();
            int chunkSize = 0;
            int semicolonIndex = sizeLine.indexOf(';');
            if (semicolonIndex >= 0) {
                sizeLine = sizeLine.substring(0, semicolonIndex).trim();
            }

            try {
                chunkSize = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException exception) {
                return new ChunkedParseResult(false, "");
            }

            offset = lineEndIndex + (requestText.startsWith("\r\n", lineEndIndex) ? 2 : 1);
            if (chunkSize == 0) {
                while (offset < requestText.length()) {
                    if (requestText.startsWith("\r\n", offset) || requestText.startsWith("\n", offset)) {
                        return new ChunkedParseResult(true, bodyBuilder.toString());
                    }
                    if (requestText.charAt(offset) == '\r' || requestText.charAt(offset) == '\n') {
                        return new ChunkedParseResult(true, bodyBuilder.toString());
                    }
                    offset++;
                }
                return new ChunkedParseResult(false, "");
            }

            if (offset + chunkSize > requestText.length()) {
                return new ChunkedParseResult(false, "");
            }
            bodyBuilder.append(requestText, offset, offset + chunkSize);
            offset += chunkSize;
            if (offset < requestText.length() && (requestText.startsWith("\r\n", offset) || requestText.startsWith("\n", offset))) {
                offset += requestText.startsWith("\r\n", offset) ? 2 : 1;
            }
        }

        return new ChunkedParseResult(false, "");
    }

    private static int parseContentLength(String contentLengthHeader) {
        if (contentLengthHeader == null || contentLengthHeader.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(contentLengthHeader.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    public static final class ParsedRequest {
        public final boolean valid;
        public final String method;
        public final String target;
        public final String path;
        public final String query;
        public final String version;
        public final Map<String, String> headers;
        public final Map<String, String> cookies;
        public final String body;
        public final boolean chunked;
        public final int contentLength;

        private ParsedRequest(boolean valid, String method, String target, String path, String query, String version,
                              Map<String, String> headers, Map<String, String> cookies, String body,
                              boolean chunked, int contentLength) {
            this.valid = valid;
            this.method = method;
            this.target = target;
            this.path = path;
            this.query = query;
            this.version = version;
            this.headers = headers;
            this.cookies = cookies;
            this.body = body;
            this.chunked = chunked;
            this.contentLength = contentLength;
        }

        static ParsedRequest valid(String method, String target, String path, String query, String version,
                                   Map<String, String> headers, Map<String, String> cookies, String body,
                                   boolean chunked, int contentLength) {
            return new ParsedRequest(true, method, target, path, query, version, headers, cookies, body, chunked, contentLength);
        }

        static ParsedRequest invalid() {
            return new ParsedRequest(false, null, null, null, null, null, Map.of(), Map.of(), "", false, 0);
        }
    }

    private static final class HeaderBoundary {
        final int index;
        final int separatorLength;

        private HeaderBoundary(int index, int separatorLength) {
            this.index = index;
            this.separatorLength = separatorLength;
        }
    }

    private static final class ChunkedParseResult {
        final boolean complete;
        final String body;

        private ChunkedParseResult(boolean complete, String body) {
            this.complete = complete;
            this.body = body;
        }
    }
}
