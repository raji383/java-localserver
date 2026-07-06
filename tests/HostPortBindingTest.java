import java.net.Socket;
import java.util.List;
import java.util.Map;

public class HostPortBindingTest {
    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig("127.0.0.1", List.of(18080, 18081), "localhost", Map.of(), 1024L, List.of(createRoute("/")));
        Server server = new Server(List.of(config));

        java.lang.reflect.Method bindMethod = Server.class.getDeclaredMethod("bindServers", java.nio.channels.Selector.class);
        bindMethod.setAccessible(true);
        try (java.nio.channels.Selector selector = java.nio.channels.Selector.open()) {
            bindMethod.invoke(server, selector);
            assertPortReachable(18080);
            assertPortReachable(18081);
        }

        System.out.println("Host and port binding tests passed");
    }

    private static void assertPortReachable(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            if (!socket.isConnected()) {
                throw new AssertionError("Expected port " + port + " to accept a connection");
            }
        }
    }

    private static Route createRoute(String path) {
        Route route = new Route();
        route.path = path;
        route.root = "www";
        route.methods = List.of("GET");
        return route;
    }
}
