import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ServerFeatureTest {
    public static void main(String[] args) throws Exception {
        Config config = new Config("test_configs/basic.json");
        ServerConfig serverConfig = config.getServers().get(0);
        Server server = new Server(List.of(serverConfig));

        testCustomErrorPage(server, serverConfig);
        testHostHeaderValidation(server);
        testRawUploadsUseUniqueFiles(server, serverConfig);
        testCgiExecution(server, serverConfig);

        System.out.println("Server feature tests passed");
    }

    private static void testCustomErrorPage(Server server, ServerConfig serverConfig) {
        HttpRequestParser.ParsedRequest request = request("GET", "/missing", Map.of("host", "localhost"), "");
        String response = server.buildResponse(serverConfig, request);

        if (!response.startsWith("HTTP/1.1 404 Not Found")) {
            throw new AssertionError("Expected 404 response");
        }
        if (!response.contains("<h1>404 Not Found</h1>")) {
            throw new AssertionError("Expected custom 404 page body");
        }
    }

    private static void testHostHeaderValidation(Server server) {
        if (!server.isHostAcceptedForPort(18080, "localhost")) {
            throw new AssertionError("Expected configured server_name to be accepted");
        }
        if (!server.isHostAcceptedForPort(18080, "127.0.0.1")) {
            throw new AssertionError("Expected configured listen host to be accepted");
        }
        if (server.isHostAcceptedForPort(18080, "localho")) {
            throw new AssertionError("Expected unknown host to be rejected");
        }
    }

    private static void testRawUploadsUseUniqueFiles(Server server, ServerConfig serverConfig) throws Exception {
        String baseName = "upload-test-" + System.nanoTime() + ".txt";
        Path first = Path.of("test_uploads", baseName);
        Path second = siblingWithSuffix(first, 1);

        try {
            String firstResponse = server.buildResponse(serverConfig,
                    request("POST", "/upload/" + baseName, Map.of("host", "localhost", "content-type", "text/plain"), "first"));
            String secondResponse = server.buildResponse(serverConfig,
                    request("POST", "/upload/" + baseName, Map.of("host", "localhost", "content-type", "text/plain"), "second"));

            if (!firstResponse.contains("Uploaded " + first.getFileName())) {
                throw new AssertionError("Expected first upload to keep requested filename");
            }
            if (!secondResponse.contains("Uploaded " + second.getFileName())) {
                throw new AssertionError("Expected second upload to use unique filename");
            }
            if (!"first".equals(Files.readString(first))) {
                throw new AssertionError("First upload content changed");
            }
            if (!"second".equals(Files.readString(second))) {
                throw new AssertionError("Second upload content changed");
            }
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    private static void testCgiExecution(Server server, ServerConfig serverConfig) {
        HttpRequestParser.ParsedRequest request = request("POST", "/cgi/echo.py",
                Map.of("host", "localhost", "content-type", "text/plain"), "hello");
        String response = server.buildResponse(serverConfig, request);

        if (!response.startsWith("HTTP/1.1 200 OK")) {
            throw new AssertionError("Expected CGI 200 response");
        }
        if (!response.contains("method=POST") || !response.contains("body=hello")) {
            throw new AssertionError("Expected CGI to receive method and body");
        }
    }

    private static HttpRequestParser.ParsedRequest request(String method, String target, Map<String, String> headers, String body) {
        String path = target;
        String query = "";
        int queryIndex = target.indexOf('?');
        if (queryIndex >= 0) {
            path = target.substring(0, queryIndex);
            query = target.substring(queryIndex + 1);
        }
        return HttpRequestParser.ParsedRequest.valid(method, target, path, query, "HTTP/1.1",
                headers, Map.of(), body, false, body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1).length);
    }

    private static Path siblingWithSuffix(Path path, int suffix) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        return path.getParent().resolve(baseName + "-" + suffix + extension);
    }
}
