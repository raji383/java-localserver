import java.util.Map;

public class Main {
    public static void main(String[] args) {
        String configFile = args.length > 0 ? args[0] : "config.json";
        Map<String, Object> config = Config.parse(configFile);
        if (config == null) {
            System.err.println("Failed to load config: " + configFile);
            System.exit(1);
        }
    }
}
