import java.util.List;

public class Main {
    public static void main(String[] args) {
        String configFile = args.length > 0 ? args[0] : "config.json";
        try {
            Config config = new Config(configFile);
            List<ServerConfig> serverConfigs = config.getServers();
            if (serverConfigs == null || serverConfigs.isEmpty()) {
                throw new IllegalArgumentException("No servers configured");
            }
            new Server(serverConfigs).start();
        } catch (Exception exception) {
            System.err.println("Error starting server: " + exception.getMessage());
            System.exit(1);
        }

    }
}
