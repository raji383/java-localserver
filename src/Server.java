import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Server {
    private static final int READ_BUFFER_SIZE = 8192;
    private static final long IDLE_TIMEOUT_MILLIS = 30_000L;

    private final List<ServerConfig> serverConfigs;

    Server(List<ServerConfig> serverConfigs) {
        this.serverConfigs = serverConfigs;
    }

    public void start() throws IOException {
        try (Selector selector = Selector.open()) {
            bindServers(selector);
            System.out.println("Server started");
            while (true) {
                selector.select(1000);
                processSelectedKeys(selector);
                closeIdleConnections(selector);
            }
        }
    }

    private void bindServers(Selector selector) throws IOException {
        Set<String> boundEndpoints = new HashSet<>();
        for (ServerConfig serverConfig : serverConfigs) {
            if (serverConfig == null || serverConfig.ports == null || serverConfig.ports.isEmpty()) {
                continue;
            }

            String host = normalizeHost(serverConfig.host);
            for (Integer port : serverConfig.ports) {
                if (port == null) {
                    continue;
                }

                String endpointKey = host + ":" + port;
                if (!boundEndpoints.add(endpointKey)) {
                    continue;
                }

                bindServerPort(selector, host, port, serverConfig);
                System.out.println("Listening on " + endpointKey);
            }
        }
    }

    private void bindServerPort(Selector selector, String host, int port, ServerConfig serverConfig) throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverSocketChannel.bind(new InetSocketAddress(host, port));
        Endpoint endpoint = new Endpoint(host, port, serverConfig);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, endpoint);
    }

    private String normalizeHost(String host) {
        return host == null || host.isBlank() ? "0.0.0.0" : host;
    }

    private void processSelectedKeys(Selector selector) {
        for (var keyIterator = selector.selectedKeys().iterator(); keyIterator.hasNext(); ) {
            SelectionKey key = keyIterator.next();
            keyIterator.remove();

            try {
                if (!key.isValid()) {
                    continue;
                }
                if (key.isAcceptable()) {
                    acceptConnection(selector, key);
                } else if (key.isReadable()) {
                    readConnection(key);
                } else if (key.isWritable()) {
                    writeConnection(key);
                }
            } catch (Exception exception) {
                closeKey(key);
            }
        }
    }

    private void acceptConnection(Selector selector, SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        if (socketChannel == null) {
            return;
        }

        socketChannel.configureBlocking(false);
        ConnectionState connectionState = new ConnectionState((Endpoint) key.attachment());
        socketChannel.register(selector, SelectionKey.OP_READ, connectionState);
    }

    private void readConnection(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ConnectionState connectionState = (ConnectionState) key.attachment();
        connectionState.lastActivityMillis = System.currentTimeMillis();

        ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        int bytesRead = socketChannel.read(buffer);
        if (bytesRead == -1) {
            closeKey(key);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        buffer.flip();
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        connectionState.requestBuffer.write(chunk);

        ParsedRequest parsedRequest = tryParseRequest(connectionState.requestBuffer.toByteArray());
        if (parsedRequest == null) {
            return;
        }

        connectionState.responseBuffer = ByteBuffer.wrap(buildResponse(parsedRequest).getBytes(StandardCharsets.UTF_8));
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void writeConnection(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        ConnectionState connectionState = (ConnectionState) key.attachment();
        connectionState.lastActivityMillis = System.currentTimeMillis();

        socketChannel.write(connectionState.responseBuffer);
        if (!connectionState.responseBuffer.hasRemaining()) {
            closeKey(key);
        }
    }

    private ParsedRequest tryParseRequest(byte[] requestBytes) {
        return parseRequest(requestBytes);
    }

    static ParsedRequest parseRequest(byte[] requestBytes) {
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

    private String buildResponse(ParsedRequest parsedRequest) {
        if (!parsedRequest.valid) {
            return buildErrorResponse(400, "Bad Request");
        }

        if (!isSupportedMethod(parsedRequest.method)) {
            return buildErrorResponse(405, "Method Not Allowed");
        }

        String responseBody = "Request received: " + parsedRequest.method + " " + parsedRequest.target;
        byte[] responseBodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);

        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + responseBodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + responseBody;
    }

    private boolean isSupportedMethod(String method) {
        return "GET".equals(method) || "POST".equals(method) || "DELETE".equals(method);
    }

    private String buildErrorResponse(int statusCode, String reasonPhrase) {
        String body = statusCode + " " + reasonPhrase;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return "HTTP/1.1 " + statusCode + " " + reasonPhrase + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + body;
    }

    private void closeIdleConnections(Selector selector) {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid() || !(key.channel() instanceof SocketChannel)) {
                continue;
            }

            ConnectionState connectionState = (ConnectionState) key.attachment();
            if (connectionState != null && now - connectionState.lastActivityMillis > IDLE_TIMEOUT_MILLIS) {
                closeKey(key);
            }
        }
    }

    private void closeKey(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
        }
        key.cancel();
    }

    private static final class Endpoint {
        final String host;
        final int port;
        final ServerConfig serverConfig;

        Endpoint(String host, int port, ServerConfig serverConfig) {
            this.host = host;
            this.port = port;
            this.serverConfig = serverConfig;
        }
    }

    private static final class ConnectionState {
        final Endpoint endpoint;
        final ByteArrayOutputStream requestBuffer;
        ByteBuffer responseBuffer;
        long lastActivityMillis;

        ConnectionState(Endpoint endpoint) {
            this.endpoint = endpoint;
            this.requestBuffer = new ByteArrayOutputStream();
            this.lastActivityMillis = System.currentTimeMillis();
        }
    }

    static final class ParsedRequest {
        final boolean valid;
        final String method;
        final String target;
        final String path;
        final String query;
        final String version;
        final Map<String, String> headers;
        final Map<String, String> cookies;
        final String body;
        final boolean chunked;
        final int contentLength;

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
