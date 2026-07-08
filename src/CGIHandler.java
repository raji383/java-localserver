import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class CGIHandler {
    private static final long CGI_TIMEOUT_SECONDS = 5;
    private static final int MAX_ENV_VALUE = 4096;

    public static String handle(Route route, HttpRequestParser.ParsedRequest request) {
        ScriptResolveResult resolved = resolveScript(route, request);
        if (resolved == null || resolved.script == null) {
            return null;
        }
        Path script = resolved.script;
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandFor(script));
            builder.directory(script.getParent().toFile());
            builder.redirectErrorStream(false);
            builder.environment().put("REQUEST_METHOD", sanitizeEnv(request.method));
            builder.environment().put("QUERY_STRING", sanitizeEnv(request.query));
            builder.environment().put("CONTENT_LENGTH", sanitizeEnv(String.valueOf(request.body.getBytes(StandardCharsets.ISO_8859_1).length)));
            builder.environment().put("CONTENT_TYPE", sanitizeEnv(request.headers.getOrDefault("content-type", "")));
            // Set SCRIPT_NAME and PATH_INFO according to resolved result
            String scriptName = resolved.scriptName != null ? resolved.scriptName : "";
            String pathInfo = resolved.pathInfo != null ? resolved.pathInfo : "";
            builder.environment().put("SCRIPT_NAME", sanitizeEnv(scriptName));
            builder.environment().put("PATH_INFO", sanitizeEnv(pathInfo));
            builder.environment().put("SERVER_PROTOCOL", sanitizeEnv(request.version != null ? request.version : "HTTP/1.1"));
            builder.environment().put("SERVER_NAME", sanitizeEnv(request.headers.getOrDefault("host", "")));
            builder.environment().put("REMOTE_ADDR", "127.0.0.1");

            // Expose HTTP_ prefixed headers to CGI in a sanitized form
            try {
                for (java.util.Map.Entry<String, String> e : request.headers.entrySet()) {
                    String hn = e.getKey();
                    String hv = e.getValue();
                    if (hn == null || hn.isBlank()) continue;
                    String envName = headerNameToEnv(hn);
                    if (envName == null || envName.isBlank()) continue;
                    builder.environment().put(envName, sanitizeEnv(hv));
                }
            } catch (Exception ignored) {
            }

            process = builder.start();
            try (var out = process.getOutputStream()) {
                out.write(request.body.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }

            boolean finished = process.waitFor(CGI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
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
            if (process != null) process.destroyForcibly();
            return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
        } catch (IOException exception) {
            if (process != null) process.destroyForcibly();
            return HttpResponseBuilder.buildErrorResponse(request, 500, "Internal Server Error");
        }
    }

    // Sanitize values placed into environment variables for CGI scripts.
    // Removes NUL, CR, LF and other control characters, and truncates to a sane length.
    private static String sanitizeEnv(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(v.length(), MAX_ENV_VALUE));
        for (int i = 0; i < v.length() && sb.length() < MAX_ENV_VALUE; i++) {
            char c = v.charAt(i);
            if (c == '\u0000' || c == '\r' || c == '\n') continue;
            if (c < 0x20 && c != '\t') continue; // drop other control chars except tab
            if (c == 0x7f) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String headerNameToEnv(String headerName) {
        if (headerName == null) return null;
        String up = headerName.toUpperCase(java.util.Locale.ROOT);
        // Replace any non-alphanumeric char with underscore and prefix HTTP_
        StringBuilder sb = new StringBuilder("HTTP_");
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static class ScriptResolveResult {
        final Path script;
        final String scriptName; // portion of URL corresponding to script
        final String pathInfo;   // extra path after script

        ScriptResolveResult(Path script, String scriptName, String pathInfo) {
            this.script = script;
            this.scriptName = scriptName;
            this.pathInfo = pathInfo;
        }
    }

    private static ScriptResolveResult resolveScript(Route route, HttpRequestParser.ParsedRequest request) {
        if (route == null || route.root == null || route.cgi_extensions == null) {
            return null;
        }

        String normalizedRequestPath = request.path == null ? "/" : request.path;
        String remainder = normalizedRequestPath.startsWith(route.path)
                ? normalizedRequestPath.substring(route.path.length())
                : normalizedRequestPath;
        remainder = remainder.startsWith("/") ? remainder.substring(1) : remainder; // no leading slash

        Path root = Path.of(route.root).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return null;
        }

        // compute real path of root to prevent symlink escaping
        Path rootReal;
        try {
            rootReal = root.toRealPath();
        } catch (IOException e) {
            rootReal = root;
        }

        // URL-decode remainder then split
        String decodedRemainder;
        try {
            decodedRemainder = java.net.URLDecoder.decode(remainder, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            decodedRemainder = remainder;
        }

        String[] parts = decodedRemainder.isEmpty() ? new String[0] : decodedRemainder.split("/");
        for (int take = parts.length; take >= 1; take--) {
            StringBuilder candidateBuilder = new StringBuilder();
            boolean skip = false;
            for (int i = 0; i < take; i++) {
                String part = parts[i];
                if (part.equals(".") || part.equals("..")) { skip = true; break; }
                if (i > 0) candidateBuilder.append('/');
                candidateBuilder.append(part);
            }
            if (skip) continue;

            String candidate = candidateBuilder.toString();
            Path scriptPath = root.resolve(candidate).normalize();

            // ensure real path is within rootReal to prevent symlink escapes
            Path scriptReal;
            try {
                scriptReal = scriptPath.toRealPath();
            } catch (IOException e) {
                continue; // not a real file
            }
            if (!scriptReal.startsWith(rootReal) || !Files.isRegularFile(scriptReal)) {
                continue;
            }

            // verify extension allowed
            boolean allowed = false;
            for (String ext : route.cgi_extensions) {
                if (scriptReal.toString().endsWith(ext)) { allowed = true; break; }
            }
            if (!allowed) continue;

            // path info is remaining parts after 'take'
            String pathInfo = "";
            if (take < parts.length) {
                StringBuilder pi = new StringBuilder();
                for (int j = take; j < parts.length; j++) {
                    pi.append('/').append(parts[j]);
                }
                pathInfo = pi.toString();
            }

            // scriptName is the URL path up to and including script. Normalize route.path then append candidate
            String routePrefix = route.path == null ? "/" : (route.path.startsWith("/") ? route.path : "/" + route.path);
            if (routePrefix.length() > 1 && routePrefix.endsWith("/")) {
                routePrefix = routePrefix.substring(0, routePrefix.length() - 1);
            }
            String scriptName;
            if ("/".equals(routePrefix)) {
                scriptName = "/" + candidate;
            } else {
                scriptName = routePrefix + (candidate.isEmpty() ? "" : "/" + candidate);
            }

            // ensure pathInfo starts with '/' when non-empty
            if (pathInfo != null && !pathInfo.isEmpty() && !pathInfo.startsWith("/")) {
                pathInfo = "/" + pathInfo;
            }

            return new ScriptResolveResult(scriptReal, scriptName, pathInfo);
        }

        return null;
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
        return HttpResponseBuilder.buildResponse(request, status, reason, output.substring(split + separatorLength), contentType, extra);
    }
}
