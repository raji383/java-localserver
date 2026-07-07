import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public class Config {
    public List<ServerConfig> servers;

    private final String json;
    private int index;

    public Config(String file) {
        String loadedJson;
        try {
            loadedJson = Files.readString(Path.of(file));
        } catch (Exception exception) {
            loadedJson = "";
            this.servers = List.of();
            this.json = loadedJson;
            return;
        }
        this.json = loadedJson;
        this.servers = parse();
    }

    public List<ServerConfig> getServers() {
        return servers;
    }

    private List<ServerConfig> parse() {
        index = 0;
        Object root = parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Config must be a JSON object");
        }

        Object serversNode = ((Map<?, ?>) root).get("servers");
        if (!(serversNode instanceof List)) {
            throw new IllegalArgumentException("Config must contain a 'servers' array");
        }

        List<ServerConfig> parsedServers = new ArrayList<>();
        for (Object serverNode : (List<?>) serversNode) {
            if (serverNode instanceof Map) {
                parsedServers.add(parseServer((Map<?, ?>) serverNode));
            }
        }
        if (parsedServers.isEmpty()) {
            throw new IllegalArgumentException("Config must define at least one server");
        }
        return parsedServers;
    }

    private ServerConfig parseServer(Map<?, ?> serverNode) {
        String host = asString(serverNode.get("host"));
        List<Integer> ports = toIntegerList(serverNode.get("ports"));
        String serverName = asString(serverNode.get("server_name"));
        Map<String, String> errorPages = toStringMap(serverNode.get("error_pages"));
        long clientBodyLimit = asLong(serverNode.get("client_body_limit"));
        List<Route> routes = toRouteList(serverNode.get("routes"));

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Server host must be provided");
        }
        if (ports == null || ports.isEmpty()) {
            throw new IllegalArgumentException("Server must define at least one port");
        }
        Set<Integer> seenPorts = new HashSet<>();
        for (Integer port : ports) {
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Server port must be between 1 and 65535");
            }
            if (!seenPorts.add(port)) {
                throw new IllegalArgumentException("Duplicate port in server: " + port);
            }
        }
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("Server must define at least one route");
        }
        if (clientBodyLimit < 0) {
            throw new IllegalArgumentException("client_body_limit must be non-negative");
        }

        return new ServerConfig(host, ports, serverName, errorPages, clientBodyLimit, routes);
    }

    private List<Route> toRouteList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }

        List<Route> routes = new ArrayList<>();
        for (Object routeNode : (List<?>) value) {
            if (routeNode instanceof Map) {
                routes.add(parseRoute((Map<?, ?>) routeNode));
            }
        }
        return routes;
    }

    private Route parseRoute(Map<?, ?> routeNode) {
        Route route = new Route();
        route.path = asString(routeNode.get("path"));
        route.root = asString(routeNode.get("root"));
        route.default_file = asString(routeNode.get("default_file"));
        route.methods = toStringList(routeNode.get("methods"));
        route.directory_listing = asBoolean(routeNode.get("directory_listing"));
        route.client_body_limit = asLongObject(routeNode.get("client_body_limit"));
        route.cgi_extensions = toStringList(routeNode.get("cgi_extensions"));
        route.redirect = asString(routeNode.get("redirect"));

        if (route.path == null || route.path.isBlank()) {
            throw new IllegalArgumentException("Route path must be provided");
        }
        if (!route.path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/'");
        }
        if (route.redirect != null && !route.redirect.isBlank() && !route.redirect.startsWith("/")) {
            throw new IllegalArgumentException("Redirect target must start with '/'");
        }
        if (route.client_body_limit != null && route.client_body_limit < 0) {
            throw new IllegalArgumentException("Route client_body_limit must be non-negative");
        }
        if (route.methods != null && !route.methods.isEmpty()) {
            for (String method : route.methods) {
                if (method == null || method.isBlank()) {
                    continue;
                }
                String upperMethod = method.trim().toUpperCase(Locale.ROOT);
                if (!upperMethod.equals("GET") && !upperMethod.equals("POST") && !upperMethod.equals("DELETE")) {
                    throw new IllegalArgumentException("Unsupported HTTP method in route: " + method);
                }
            }
        }
        return route;
    }

    private List<Integer> toIntegerList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }

        List<Integer> integers = new ArrayList<>();
        for (Object entry : (List<?>) value) {
            if (entry instanceof Number) {
                integers.add(((Number) entry).intValue());
            }
        }
        return integers;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }

        List<String> strings = new ArrayList<>();
        for (Object entry : (List<?>) value) {
            if (entry != null) {
                strings.add(entry.toString());
            }
        }
        return strings;
    }

    private Map<String, String> toStringMap(Object value) {
        if (!(value instanceof Map)) {
            return Map.of();
        }

        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                strings.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return strings;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.FALSE;
    }

    private long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Long.parseLong((String) value);
        }
        return 0L;
    }

    private Long asLongObject(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            return Long.parseLong((String) value);
        }
        return null;
    }

    private Object parseValue() {
        skipWhitespace();
        if (index >= json.length()) {
            return null;
        }

        char current = json.charAt(index);
        if (current == '{') {
            return parseObject();
        }
        if (current == '[') {
            return parseArray();
        }
        if (current == '"') {
            return parseString();
        }
        if (current == 't' || current == 'f') {
            return parseBoolean();
        }
        if (current == 'n') {
            parseNull();
            return null;
        }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();

        if (peek('}')) {
            index++;
            return object;
        }

        while (index < json.length()) {
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            object.put(key, value);
            skipWhitespace();

            if (peek('}')) {
                index++;
                break;
            }
            expect(',');
        }
        return object;
    }

    private List<Object> parseArray() {
        List<Object> array = new ArrayList<>();
        expect('[');
        skipWhitespace();

        if (peek(']')) {
            index++;
            return array;
        }

        while (index < json.length()) {
            array.add(parseValue());
            skipWhitespace();

            if (peek(']')) {
                index++;
                break;
            }
            expect(',');
        }
        return array;
    }

    private String parseString() {
        expect('"');
        StringBuilder builder = new StringBuilder();

        while (index < json.length()) {
            char current = json.charAt(index++);
            if (current == '"') {
                return builder.toString();
            }
            if (current == '\\') {
                if (index >= json.length()) {
                    break;
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escaped);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        builder.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        builder.append(escaped);
                        break;
                }
            } else {
                builder.append(current);
            }
        }

        throw new IllegalStateException("Unterminated string literal");
    }

    private Boolean parseBoolean() {
        if (json.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (json.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        throw new IllegalStateException("Invalid boolean value at index " + index);
    }

    private void parseNull() {
        if (json.startsWith("null", index)) {
            index += 4;
            return;
        }
        throw new IllegalStateException("Invalid null value at index " + index);
    }

    private Number parseNumber() {
        int start = index;
        if (json.charAt(index) == '-') {
            index++;
        }
        while (index < json.length() && Character.isDigit(json.charAt(index))) {
            index++;
        }

        boolean isFractional = false;
        if (index < json.length() && json.charAt(index) == '.') {
            isFractional = true;
            index++;
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
        }

        if (index < json.length() && (json.charAt(index) == 'e' || json.charAt(index) == 'E')) {
            isFractional = true;
            index++;
            if (index < json.length() && (json.charAt(index) == '+' || json.charAt(index) == '-')) {
                index++;
            }
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
        }

        String number = json.substring(start, index);
        return isFractional ? Double.parseDouble(number) : Long.parseLong(number);
    }

    private void expect(char expected) {
        skipWhitespace();
        if (index >= json.length() || json.charAt(index) != expected) {
            throw new IllegalStateException("Expected '" + expected + "' at index " + index);
        }
        index++;
    }

    private boolean peek(char expected) {
        skipWhitespace();
        return index < json.length() && json.charAt(index) == expected;
    }

    private void skipWhitespace() {
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
    }
}
