import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class DefaultServerSelectionTest {
    public static void main(String[] args) {
        Route route = new Route();
        route.path = "/";
        route.root = "www";
        route.methods = List.of("GET");

        ServerConfig defaultConfig = new ServerConfig("0.0.0.0", List.of(18082), "default", Map.of(), 1024L, List.of(route));
        ServerConfig specificConfig = new ServerConfig("127.0.0.1", List.of(18083), "special", Map.of(), 1024L, List.of(route));
        Server server = new Server(List.of(defaultConfig, specificConfig));

        ServerConfig selected = server.selectServerForConnection("0.0.0.0", 18082, null);
        if (selected != defaultConfig) {
            throw new AssertionError("Expected default server to be selected for matching port");
        }

        ServerConfig fallback = server.selectServerForConnection("127.0.0.1", 9999, null);
        if (fallback != defaultConfig) {
            throw new AssertionError("Expected default server to be selected when no specific match exists");
        }

        System.out.println("Default server selection tests passed");
    }
}
