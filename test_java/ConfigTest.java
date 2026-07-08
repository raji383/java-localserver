import java.util.List;

public class ConfigTest {
    public static void main(String[] args) {
        testBasicConfig();
        testInvalidConfig();
        System.out.println("All tests passed");
    }

    private static void testBasicConfig() {
        Config cfg = new Config("test_configs/basic.json");
        List<ServerConfig> servers = cfg.getServers();
        if (servers == null || servers.size() != 1) throw new AssertionError("Expected 1 server");
        ServerConfig s = servers.get(0);
        if (!"127.0.0.1".equals(s.host)) throw new AssertionError("host mismatch");
        if (s.ports == null || s.ports.size() != 1 || s.ports.get(0) != 18080) throw new AssertionError("ports mismatch");
        if (s.routes == null || s.routes.size() < 1) throw new AssertionError("routes missing");
        // check a route
        boolean found = false;
        for (Route r : s.routes) {
            if ("/cgi".equals(r.path)) {
                found = true;
                if (r.cgi_extensions == null || r.cgi_extensions.size() != 1 || !".py".equals(r.cgi_extensions.get(0))) throw new AssertionError("cgi ext mismatch");
            }
        }
        if (!found) throw new AssertionError("/cgi route not found");
    }

    private static void testInvalidConfig() {
        boolean threw = false;
        try {
            new Config("test_configs/duplicate_port.json");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        if (!threw) throw new AssertionError("Expected duplicate port config to fail");
    }
}
