import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigValidationTest {
    public static void main(String[] args) throws Exception {
        Path validConfig = Files.createTempFile("valid-config", ".json");
        Files.writeString(validConfig, """
                {
                  "servers": [
                    {
                      "host": "127.0.0.1",
                      "ports": [8080],
                      "routes": [
                        {"path": "/", "root": "www"}
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        Config valid = new Config(validConfig.toString());
        if (valid.getServers() == null || valid.getServers().isEmpty()) {
            throw new AssertionError("Expected valid config to load");
        }

        Path invalidConfig = Files.createTempFile("invalid-config", ".json");
        Files.writeString(invalidConfig, """
                {
                  "servers": [
                    {
                      "host": "127.0.0.1",
                      "ports": [70000],
                      "routes": [
                        {"path": "bad-path", "root": "www"}
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        try {
            new Config(invalidConfig.toString());
            throw new AssertionError("Expected invalid config to be rejected");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("invalid") && !expected.getMessage().contains("must")) {
                throw new AssertionError("Expected validation message, got: " + expected.getMessage());
            }
        }

        System.out.println("Config validation tests passed");
    }
}
