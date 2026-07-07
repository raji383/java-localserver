import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class CGIHandler {
    private static final long CGI_TIMEOUT_SECONDS = 5;

    public static String handle(Route route, HttpRequestParser.ParsedRequest request) {
        Path script = resolveScript(route, request);
        if (script == null) {
            return null;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(commandFor(script));
            builder.directory(script.getParent().toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("REQUEST_METHOD", request.method);
            builder.environment().put("QUERY_STRING", request.query);
            builder.environment().put("CONTENT_LENGTH", String.valueOf(request.body.getBytes(StandardCharsets.ISO_8859_1).length));
            builder.environment().put("CONTENT_TYPE", request.headers.getOrDefault("content-type", ""));
            builder.environment().put("PATH_INFO", script.toAbsolutePath().toString());

            Process process = builder.start();
            process.getOutputStream().write(request.body.getBytes(StandardCharsets.ISO_8859_1));
            process.getOutputStream().close();

            if (!process.waitFor(CGI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
            }
            return buildCgiResponse(request, output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
        } catch (IOException exception) {
            return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
        }
    }

    private static Path resolveScript(Route route, HttpRequestParser.ParsedRequest request) {
        if (route == null || route.root == null || route.cgi_extensions == null) {
            return null;
        }

        String path = request.path.startsWith(route.path)
                ? request.path.substring(route.path.length())
                : request.path;
        path = path.startsWith("/") ? path.substring(1) : path;

        boolean allowedExtension = false;
        for (String extension : route.cgi_extensions) {
            allowedExtension = allowedExtension || path.endsWith(extension);
        }
        if (!allowedExtension) {
            return null;
        }

        Path root = Path.of(route.root).toAbsolutePath().normalize();
        Path script = root.resolve(path).normalize();
        if (!script.startsWith(root) || !Files.isRegularFile(script)) {
            return null;
        }
        return script;
    }

    private static String[] commandFor(Path script) {
        if (script.toString().endsWith(".py")) {
            return new String[]{"python3", script.toAbsolutePath().toString()};
        }
        return new String[]{script.toAbsolutePath().toString()};
    }

    private static String buildCgiResponse(HttpRequestParser.ParsedRequest request, String output) {
        int split = output.indexOf("\r\n\r\n");
        int separatorLength = 4;
        if (split < 0) {
            split = output.indexOf("\n\n");
            separatorLength = 2;
        }
        if (split < 0) {
            return HttpResponseBuilder.buildResponse(request, 200, "OK", output, "text/plain; charset=utf-8");
        }

        int status = 200;
        String reason = "OK";
        String contentType = "text/html; charset=utf-8";
        for (String header : output.substring(0, split).split("\\r?\\n")) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Type")) {
                contentType = value;
            } else if (name.equalsIgnoreCase("Status")) {
                String[] parts = value.split("\\s+", 2);
                try {
                    status = Integer.parseInt(parts[0]);
                    reason = parts.length > 1 ? parts[1] : reason;
                } catch (NumberFormatException ignored) {
                    status = 200;
                    reason = "OK";
                }
            }
        }
        return HttpResponseBuilder.buildResponse(request, status, reason, output.substring(split + separatorLength), contentType);
    }
}
