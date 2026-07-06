import java.nio.charset.StandardCharsets;

public class HttpResponseBuilder {
    public static String buildResponse(HttpRequestParser.ParsedRequest parsedRequest, int statusCode, String reasonPhrase, String body, String contentType) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String responseVersion = responseVersion(parsedRequest);
        String connectionHeader = shouldKeepAlive(parsedRequest) ? "keep-alive" : "close";

        StringBuilder responseBuilder = new StringBuilder();
        responseBuilder.append(responseVersion).append(' ').append(statusCode).append(' ').append(reasonPhrase).append("\r\n");
        responseBuilder.append("Content-Type: ").append(contentType).append("\r\n");
        responseBuilder.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        responseBuilder.append("Connection: ").append(connectionHeader).append("\r\n");
       // responseBuilder.append();
        responseBuilder.append("\r\n");
        responseBuilder.append(body);
        return responseBuilder.toString();
    }

    public static String buildErrorResponse(HttpRequestParser.ParsedRequest parsedRequest, int statusCode, String reasonPhrase) {
        String body = statusCode + " " + reasonPhrase;
        return buildResponse(parsedRequest, statusCode, reasonPhrase, body, "text/plain; charset=utf-8");
    }

    private static boolean shouldKeepAlive(HttpRequestParser.ParsedRequest parsedRequest) {
        if (parsedRequest == null || parsedRequest.version == null) {
            return false;
        }
        String connectionHeader = parsedRequest.headers.getOrDefault("connection", "").trim();
        if (!connectionHeader.isBlank() && connectionHeader.equalsIgnoreCase("close")) {
            return false;
        }
        return parsedRequest.version.equalsIgnoreCase("HTTP/1.1");
    }

    private static String responseVersion(HttpRequestParser.ParsedRequest parsedRequest) {
        if (parsedRequest != null && parsedRequest.version != null && parsedRequest.version.startsWith("HTTP/1.")) {
            return parsedRequest.version;
        }
        return "HTTP/1.1";
    }
}
