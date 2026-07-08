import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, String> headers = responseHeaders(parsedRequest, extraHeaders);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            responseBuilder.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        List<String> setCookies = buildSetCookieList(parsedRequest, extraHeaders);
        for (String sc : setCookies) {
            responseBuilder.append("Set-Cookie: ").append(sc).append("\r\n");
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
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                headers.put(e.getKey(), e.getValue());
            }
        }
        return headers;
    }

    private static final SessionManager SESSION_MANAGER = SessionManager.INSTANCE;

    private static List<String> buildSetCookieList(HttpRequestParser.ParsedRequest parsedRequest, Map<String, String> extraHeaders) {
        List<String> cookies = new ArrayList<>();
        if (extraHeaders != null) {
            String existing = null;
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Set-Cookie")) {
                    existing = e.getValue();
                    break;
                }
            }
            if (existing != null && !existing.isBlank()) {
                for (String part : existing.split("\n")) {
                    if (!part.isBlank()) cookies.add(part);
                }
            }
        }

        // Ensure a session exists. If client provided a SESSION_ID, try to use it; otherwise create a new one.
        String incoming = parsedRequest != null ? parsedRequest.cookies.get("SESSION_ID") : null;
        SessionManager.SessionResult res = SESSION_MANAGER.getOrCreate(incoming);
        if (res.created || incoming == null || incoming.isBlank()) {
            cookies.add(new Cookie("SESSION_ID", res.session.getId()).toHeader());
        }
        return cookies;
    }

    private static String responseVersion(HttpRequestParser.ParsedRequest parsedRequest) {
        if (parsedRequest != null && parsedRequest.version != null && parsedRequest.version.startsWith("HTTP/1.")) {
            return parsedRequest.version;
        }
        return "HTTP/1.1";
    }
}
