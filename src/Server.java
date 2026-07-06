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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
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

    ServerConfig selectServerForConnection(String host, int port, ServerConfig fallback) {
        if (host == null || host.isBlank()) {
            host = "0.0.0.0";
        }

        String normalizedHost = normalizeHost(host);
        for (ServerConfig serverConfig : serverConfigs) {
            if (serverConfig == null || serverConfig.ports == null || serverConfig.ports.isEmpty()) {
                continue;
            }
            if (matchesServer(serverConfig, normalizedHost, port)) {
                return serverConfig;
            }
        }
        return fallback != null ? fallback : serverConfigs.isEmpty() ? null : serverConfigs.get(0);
    }

    private boolean matchesServer(ServerConfig serverConfig, String host, int port) {
        if (serverConfig == null || serverConfig.ports == null || serverConfig.ports.isEmpty()) {
            return false;
        }
        String normalizedHost = normalizeHost(serverConfig.host);
        if (!normalizedHost.equals("0.0.0.0") && !normalizedHost.equals(host)) {
            return false;
        }
        for (Integer configuredPort : serverConfig.ports) {
            if (configuredPort != null && configuredPort == port) {
                return true;
            }
        }
        return false;
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
        Endpoint endpoint = (Endpoint) key.attachment();
        ServerConfig selectedServerConfig = selectServerForConnection(endpoint.host, endpoint.port, endpoint.serverConfig);
        ConnectionState connectionState = new ConnectionState(new Endpoint(endpoint.host, endpoint.port, selectedServerConfig));
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

        HttpRequestParser.ParsedRequest parsedRequest = tryParseRequest(connectionState.requestBuffer.toByteArray());
        if (parsedRequest == null) {
            return;
        }

        connectionState.responseBuffer = ByteBuffer.wrap(buildResponse(connectionState.endpoint.serverConfig, parsedRequest).getBytes(StandardCharsets.UTF_8));
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

    private HttpRequestParser.ParsedRequest tryParseRequest(byte[] requestBytes) {
        return HttpRequestParser.parseRequest(requestBytes);
    }

    String buildResponse(ServerConfig serverConfig, HttpRequestParser.ParsedRequest parsedRequest) {
        if (!parsedRequest.valid) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 400, "Bad Request");
        }

        if (!isSupportedMethod(parsedRequest.method)) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 405, "Method Not Allowed");
        }

        Router.RouteMatch routeMatch = Router.matchRoute(serverConfig, parsedRequest);
        if (routeMatch == null) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 404, "Not Found");
        }

        if (isBodyTooLarge(routeMatch.route, parsedRequest)) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 413, "Request Entity Too Large");
        }

        if (routeMatch == null) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 404, "Not Found");
        }

        if (routeMatch.statusCode == 405) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 405, "Method Not Allowed");
        }

        if (routeMatch.statusCode == 404) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 404, "Not Found");
        }

        if (routeMatch.statusCode == 302) {
            return HttpResponseBuilder.buildRedirectResponse(parsedRequest, routeMatch.redirectTarget);
        }

        if ("GET".equals(parsedRequest.method)) {
            String staticResponse = serveStaticFile(routeMatch.route, parsedRequest);
            if (staticResponse != null) {
                return staticResponse;
            }
        }

        String responseBody = "Request received: " + parsedRequest.method + " " + parsedRequest.target;
        return HttpResponseBuilder.buildResponse(parsedRequest, 200, "OK", responseBody, "text/plain; charset=utf-8");
    }

    private boolean isBodyTooLarge(Route route, HttpRequestParser.ParsedRequest parsedRequest) {
        if (parsedRequest == null || parsedRequest.contentLength <= 0) {
            return false;
        }

        long limit = Long.MAX_VALUE;
        if (route != null && route.client_body_limit != null) {
            limit = route.client_body_limit;
        }

        return parsedRequest.contentLength > limit;
    }

    private String serveStaticFile(Route route, HttpRequestParser.ParsedRequest parsedRequest) {
        if (route == null || route.root == null || route.root.isBlank()) {
            return null;
        }

        Path rootPath = Path.of(route.root).toAbsolutePath().normalize();
        if (!Files.exists(rootPath)) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 404, "Not Found");
        }

        Path requestedPath = resolveRequestedPath(route, parsedRequest.path);
        if (requestedPath == null) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 403, "Forbidden");
        }

        Path resolvedPath = rootPath.resolve(requestedPath).normalize();
        if (!isPathWithinRoot(rootPath, resolvedPath)) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 403, "Forbidden");
        }

        if (Files.isDirectory(resolvedPath)) {
            if (route.default_file != null && !route.default_file.isBlank()) {
                Path defaultFile = resolvedPath.resolve(route.default_file).normalize();
                if (Files.isRegularFile(defaultFile) && isPathWithinRoot(rootPath, defaultFile)) {
                    return serveFile(parsedRequest, defaultFile);
                }
            }

            if (Boolean.TRUE.equals(route.directory_listing)) {
                return buildDirectoryListing(parsedRequest, resolvedPath);
            }
            
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 403, "Forbidden");
        }

        if (!Files.isRegularFile(resolvedPath)) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 404, "Not Found");
        }

        return serveFile(parsedRequest, resolvedPath);
    }

    private Path resolveRequestedPath(Route route, String requestPath) {
        String normalizedPath = normalizePath(requestPath);
        if (route == null || route.path == null || route.path.isBlank() || "/".equals(route.path)) {
            return Path.of(normalizedPath.equals("/") ? "" : normalizedPath.substring(1));
        }

        String routePrefix = normalizePath(route.path);
        if (normalizedPath.equals(routePrefix)) {
            return Path.of("");
        }
        if (!normalizedPath.startsWith(routePrefix + "/")) {
            return null;
        }
        String remainder = normalizedPath.substring(routePrefix.length());
        return Path.of(remainder.startsWith("/") ? remainder.substring(1) : remainder);
    }

    private String serveFile(HttpRequestParser.ParsedRequest parsedRequest, Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String contentType = determineContentType(filePath);
            return HttpResponseBuilder.buildResponse(parsedRequest, 200, "OK", content, contentType);
        } catch (IOException exception) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 404, "Not Found");
        }
    }

    private String buildDirectoryListing(HttpRequestParser.ParsedRequest parsedRequest, Path directoryPath) {
        try {
            StringBuilder builder = new StringBuilder();
            builder.append("<html><body><h1>Directory listing</h1><ul>");
            for (Path entry : Files.list(directoryPath).sorted().toList()) {
                String entryName = entry.getFileName().toString();
                builder.append("<li><a href=\"").append(entryName).append("\">")
                        .append(entryName)
                        .append("</a></li>");
            }
            builder.append("</ul></body></html>");
            return HttpResponseBuilder.buildResponse(parsedRequest, 200, "OK", builder.toString(), "text/html; charset=utf-8");
        } catch (IOException exception) {
            return HttpResponseBuilder.buildErrorResponse(parsedRequest, 500, "Internal Server Error");
        }
    }

    private boolean isPathWithinRoot(Path rootPath, Path resolvedPath) {
        try {
            return resolvedPath.toAbsolutePath().normalize().startsWith(rootPath.toAbsolutePath().normalize());
        } catch (Exception exception) {
            return false;
        }
    }

    private String determineContentType(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            return "text/plain; charset=utf-8";
        }
        if (fileName.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        while (normalizedPath.contains("//")) {
            normalizedPath = normalizedPath.replace("//", "/");
        }
        if (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath;
    }

    private boolean isSupportedMethod(String method) {
        return "GET".equals(method) || "POST".equals(method) || "DELETE".equals(method);
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

}
