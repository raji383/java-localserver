import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class CGIHandler {
    private static final long CGI_TIMEOUT_SECONDS = 3;
    private static final int MAX_ENV_VALUE = 4096;

    public static String handle(Route route, HttpRequestParser.ParsedRequest request) {
        Path script = resolveScript(route, request);
        if (script == null) {
            return null;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandFor(script));
            builder.directory(script.getParent().toFile());
            builder.environment().put("REQUEST_METHOD", sanitizeEnv(request.method));
            builder.environment().put("QUERY_STRING", sanitizeEnv(request.query));
            builder.environment().put("CONTENT_LENGTH",
                    sanitizeEnv(String.valueOf(request.body.getBytes(StandardCharsets.ISO_8859_1).length)));
            builder.environment().put("CONTENT_TYPE", sanitizeEnv(request.headers.getOrDefault("content-type", "")));

            process = builder.start();
            try (var out = process.getOutputStream()) {
                out.write(request.body.getBytes(StandardCharsets.ISO_8859_1));
            }

            if (!process.waitFor(CGI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
            }

            try (var in = process.getInputStream()) {
                String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
                }
                return buildCgiResponse(request, output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null)
                process.destroyForcibly();
            return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
        } catch (IOException exception) {
            if (process != null)
                process.destroyForcibly();
            return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
        }
    }


    private static String sanitizeEnv(String v) {
        if (v == null)
            return "";
        StringBuilder sb = new StringBuilder(Math.min(v.length(), MAX_ENV_VALUE));
        for (int i = 0; i < v.length() && sb.length() < MAX_ENV_VALUE; i++) {
            char c = v.charAt(i);
            if (c == '\u0000' || c == '\r' || c == '\n')
                continue;
            if (c < 0x20 && c != '\t')
                continue;
            if (c == 0x7f)
                continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static Path resolveScript(Route route, HttpRequestParser.ParsedRequest request) {
        Path root = Path.of(route.root != null ? route.root : "").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return null;

        String reqPath = request.path == null ? "/" : request.path;
        String rPrefix = (route.path == null ? "/" : route.path).replaceAll("^/?|/?$", "");
        String remainder = reqPath.startsWith("/" + rPrefix) ? reqPath.substring(rPrefix.length() + 1) : reqPath;
        remainder = remainder.replaceAll("^/+", "");

        // System.out.println("request path : " +  reqPath);
        // System.out.println("rPrefix path : " +  rPrefix);
        // System.out.println("remainder path : " +  remainder);
        Path script = root.resolve(remainder).normalize();
        if (!script.startsWith(root) || !Files.isRegularFile(script)) return null;
        if (route.cgi_extensions.stream().noneMatch(script.toString()::endsWith)) return null;
        return script;
    }

    private static String[] commandFor(Path script) {
        if (script.toString().endsWith(".py")) {
            return new String[] { "python3", script.toAbsolutePath().toString() };
        }
        return new String[] { script.toAbsolutePath().toString() };
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
        java.util.Map<String, String> extra = new java.util.LinkedHashMap<>();
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
            } else if (name.equalsIgnoreCase("Set-Cookie")) {
                String existing = extra.get("Set-Cookie");
                extra.put("Set-Cookie", existing == null || existing.isEmpty() ? value : existing + "\n" + value);
            } else {
                extra.put(name, value);
            }
        }
        return HttpResponseBuilder.buildResponse(request, status, reason, output.substring(split + separatorLength),
                contentType, extra);
    }
}
