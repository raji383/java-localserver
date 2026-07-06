import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            return List.of();
        }

        Object serversNode = ((Map<?, ?>) root).get("servers");
        if (!(serversNode instanceof List)) {
            return List.of();
        }

        List<ServerConfig> parsedServers = new ArrayList<>();
        for (Object serverNode : (List<?>) serversNode) {
            if (serverNode instanceof Map) {
                parsedServers.add(parseServer((Map<?, ?>) serverNode));
            }
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
