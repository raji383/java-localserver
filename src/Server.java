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
        String requestText = new String(requestBytes, StandardCharsets.ISO_8859_1);
        int headerEndIndex = requestText.indexOf("\r\n\r\n");
        if (headerEndIndex == -1) {
            return null;
        }

        String headerSection = requestText.substring(0, headerEndIndex);
        String[] lines = headerSection.split("\r\n");
        if (lines.length == 0) {
            return ParsedRequest.invalid();
        }

        String[] requestLineParts = lines[0].split("\\s+");
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
            int separatorIndex = headerLine.indexOf(':');
            if (separatorIndex <= 0) {
                return ParsedRequest.invalid();
            }
            String headerName = headerLine.substring(0, separatorIndex).trim().toLowerCase();
            String headerValue = headerLine.substring(separatorIndex + 1).trim();
            headers.put(headerName, headerValue);
        }

        int bodyStartIndex = headerEndIndex + 4;
        int contentLength = parseContentLength(headers.get("content-length"));
        boolean chunked = headers.containsKey("transfer-encoding")
                && headers.get("transfer-encoding").toLowerCase().contains("chunked");

        if (chunked) {
            int chunkEndIndex = requestText.indexOf("\r\n0\r\n\r\n", bodyStartIndex);
            if (chunkEndIndex == -1) {
                chunkEndIndex = requestText.indexOf("0\r\n\r\n", bodyStartIndex);
            }
            if (chunkEndIndex == -1) {
                return null;
            }
            String body = requestText.substring(bodyStartIndex, chunkEndIndex);
            return ParsedRequest.valid(method, target, version, headers, body);
        }

        if (contentLength > 0 && requestBytes.length < bodyStartIndex + contentLength) {
            return null;
        }

        String body = "";
        if (requestBytes.length > bodyStartIndex) {
            int bodyEndIndex = contentLength > 0
                    ? Math.min(requestBytes.length, bodyStartIndex + contentLength)
                    : requestBytes.length;
            body = new String(requestBytes, bodyStartIndex, bodyEndIndex - bodyStartIndex, StandardCharsets.ISO_8859_1);
        }

        return ParsedRequest.valid(method, target, version, headers, body);
    }

    private int parseContentLength(String contentLengthHeader) {
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

    private static final class ParsedRequest {
        final boolean valid;
        final String method;
        final String target;
        final String version;
        final Map<String, String> headers;
        final String body;

        private ParsedRequest(boolean valid, String method, String target, String version, Map<String, String> headers, String body) {
            this.valid = valid;
            this.method = method;
            this.target = target;
            this.version = version;
            this.headers = headers;
            this.body = body;
        }

        static ParsedRequest valid(String method, String target, String version, Map<String, String> headers, String body) {
            return new ParsedRequest(true, method, target, version, headers, body);
        }

        static ParsedRequest invalid() {
            return new ParsedRequest(false, null, null, null, Map.of(), "");
        }
    }
}
