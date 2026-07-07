import java.nio.file.Files;
import java.nio.file.Path;

public class error {
    public static String build(ServerConfig serverConfig, HttpRequestParser.ParsedRequest request, int statusCode, String reasonPhrase) {
        String body = loadErrorPage(serverConfig, statusCode);
        String contentType = "text/html; charset=utf-8";

        if (body == null) {
            body = statusCode + " " + reasonPhrase;
            contentType = "text/plain; charset=utf-8";
        }

        return HttpResponseBuilder.buildResponse(request, statusCode, reasonPhrase, body, contentType);
    }

    private static String loadErrorPage(ServerConfig serverConfig, int statusCode) {
        if (serverConfig == null || serverConfig.error_pages == null) {
            return null;
        }

        String path = serverConfig.error_pages.get(String.valueOf(statusCode));
        if (path == null || path.isBlank()) {
            return null;
        }

        try {
            return Files.readString(Path.of(path));
        } catch (Exception exception) {
            return null;
        }
    }
}
