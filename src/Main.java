import java.util.List;

public class Main {
    public static void main(String[] args) {
        String configFile = args.length > 0 ? args[0] : "config.json";
        Config config = new Config(configFile);
        List<ServerConfig> serverConfigs = config.getServers();
        if (serverConfigs == null || serverConfigs.isEmpty()) {
            System.err.println("No servers configured");
            System.exit(1);
        }
        try {
            new Server(serverConfigs).start();
        } catch (Exception exception) {
            System.err.println("Error starting server: " + exception.getMessage());
            System.exit(1);
        }

    }
}
