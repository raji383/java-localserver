import java.util.List;
import java.util.Map;

public class ServerConfig {
    public String host;
    public List<Integer> ports;
    public String server_name;
    public Map<String, String> error_pages;
    public long client_body_limit;
    public List<Route> routes;

    ServerConfig(String host, List<Integer> ports, String server_name, Map<String, String> error_pages, long client_body_limit, List<Route> routes) {
        this.host = host;
        this.ports = ports;
        this.server_name = server_name;
        this.error_pages = error_pages;
        this.client_body_limit = client_body_limit;
        this.routes = routes;

    }

}