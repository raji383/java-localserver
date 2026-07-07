import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpResponseBuilder {
    public static String buildResponse(HttpRequestParser.ParsedRequest parsedRequest, int statusCode, String reasonPhrase, String body, String contentType) {
        return buildResponse(parsedRequest, statusCode, reasonPhrase, body, contentType, Map.of());
    }

    public static String buildResponse(HttpRequestParser.ParsedRequest parsedRequest, int statusCode, String reasonPhrase,
                                       String body, String contentType, Map<String, String> extraHeaders) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String responseVersion = responseVersion(parsedRequest);

        StringBuilder responseBuilder = new StringBuilder();
        responseBuilder.append(responseVersion).append(' ').append(statusCode).append(' ').append(reasonPhrase).append("\r\n");
        responseBuilder.append("Content-Type: ").append(contentType).append("\r\n");
        responseBuilder.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        responseBuilder.append("Connection: close\r\n");
        for (Map.Entry<String, String> header : responseHeaders(parsedRequest, extraHeaders).entrySet()) {
            responseBuilder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        responseBuilder.append("\r\n");
        responseBuilder.append(body);
        return responseBuilder.toString();
    }

    public static String buildErrorResponse(HttpRequestParser.ParsedRequest parsedRequest, int statusCode, String reasonPhrase) {
        String body = statusCode + " " + reasonPhrase;
        return buildResponse(parsedRequest, statusCode, reasonPhrase, body, "text/plain; charset=utf-8");
    }

    public static String buildRedirectResponse(HttpRequestParser.ParsedRequest parsedRequest, String location) {
        String body = "Redirecting to " + location;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String responseVersion = responseVersion(parsedRequest);

        StringBuilder responseBuilder = new StringBuilder();
        responseBuilder.append(responseVersion).append(' ').append(302).append(' ').append("Found").append("\r\n");
        responseBuilder.append("Content-Type: text/plain; charset=utf-8\r\n");
        responseBuilder.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        responseBuilder.append("Connection: close\r\n");
        responseBuilder.append("Location: ").append(location).append("\r\n");
        for (Map.Entry<String, String> header : responseHeaders(parsedRequest, Map.of()).entrySet()) {
            responseBuilder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        responseBuilder.append("\r\n");
        responseBuilder.append(body);
        return responseBuilder.toString();
    }

    private static Map<String, String> responseHeaders(HttpRequestParser.ParsedRequest parsedRequest, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (extraHeaders != null) {
            headers.putAll(extraHeaders);
        }
        if (parsedRequest != null && !parsedRequest.cookies.containsKey("SESSION_ID")) {
            headers.put("Set-Cookie", new Cookie("SESSION_ID", new Session().getId()).toHeader());
        }
        return headers;
    }

    private static String responseVersion(HttpRequestParser.ParsedRequest parsedRequest) {
        if (parsedRequest != null && parsedRequest.version != null && parsedRequest.version.startsWith("HTTP/1.")) {
            return parsedRequest.version;
        }
        return "HTTP/1.1";
    }
}
