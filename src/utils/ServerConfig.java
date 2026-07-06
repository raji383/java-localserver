import java.util.List;
import java.util.Map;

public class ServerConfig {
    public String host;
    public List<Integer> ports;
    public String server_name;
    public Map<String, String> error_pages;
    public long client_body_limit;
    public List<Route> routes;
}