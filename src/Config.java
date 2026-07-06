import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Config {
    public List<ServerConfig> servers;

    public List<ServerConfig> getServers() {
        return servers;
    }

    public static Map<String, Object> parse(String file) {
        try {
            String json = Files.readString(Path.of(file));
            System.out.println("Config file content: " + json);
        } catch (Exception exception) {
            return null;
        }
        return null;
    }

}
