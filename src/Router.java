

import java.util.Locale;

public class Router {
    public static RouteMatch matchRoute(ServerConfig serverConfig, HttpRequestParser.ParsedRequest parsedRequest) {
        if (parsedRequest == null || !parsedRequest.valid) {
            return new RouteMatch(null, false, 400, null);
        }

        if (serverConfig == null || serverConfig.routes == null || serverConfig.routes.isEmpty()) {
            return new RouteMatch(null, false, 404, null);
        }

        String requestPath = normalizePath(parsedRequest.path);
        String method = parsedRequest.method == null ? "" : parsedRequest.method.toUpperCase(Locale.ROOT);

        Route bestRoute = null;
        int bestScore = -1;
        for (Route route : serverConfig.routes) {
            if (route == null || !matchesPath(requestPath, route.path)) {
                continue;
            }

            int routeScore = routeScore(requestPath, route.path);
            if (routeScore > bestScore) {
                bestRoute = route;
                bestScore = routeScore;
            }

        }

        if (bestRoute == null) {
            return new RouteMatch(null, false, 404, null);
        }

        if (!isMethodAllowed(bestRoute, method)) {
            return new RouteMatch(bestRoute, false, 405, null);
        }

        if (bestRoute.redirect != null && !bestRoute.redirect.isBlank()) {
            return new RouteMatch(bestRoute, true, 302, bestRoute.redirect);
        }

        return new RouteMatch(bestRoute, true, 200, null);
    }

    private static boolean matchesPath(String requestPath, String routePath) {
        if (requestPath == null || requestPath.isBlank()) {
            return false;
        }

        String normalizedRoutePath = normalizePath(routePath);
        if ("/".equals(normalizedRoutePath)) {
            return requestPath.equals("/") || requestPath.startsWith("/");
        }

        return requestPath.equals(normalizedRoutePath)
                || requestPath.startsWith(normalizedRoutePath + "/");
    }

    private static int routeScore(String requestPath, String routePath) {
        String normalizedRoutePath = normalizePath(routePath);
        if ("/".equals(normalizedRoutePath)) {
            return 0;
        }

        int score = normalizedRoutePath.length() * 10;
        if (requestPath.equals(normalizedRoutePath)) {
            score += 1000;
        }
        return score;
    }

    private static boolean isMethodAllowed(Route route, String method) {
        if (route == null || route.methods == null || route.methods.isEmpty()) {
            return true;
        }

        for (String configuredMethod : route.methods) {
            if (configuredMethod != null && method.equalsIgnoreCase(configuredMethod.trim())) {
                return true;
            }
        }

        return false;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        while (normalizedPath.contains("//")) {
            normalizedPath = normalizedPath.replace("//", "/");
        }
        if (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        return normalizedPath;
    }

    public static final class RouteMatch {
        public final Route route;
        public final boolean allowed;
        public final int statusCode;
        public final String redirectTarget;

        private RouteMatch(Route route, boolean allowed, int statusCode, String redirectTarget) {
            this.route = route;
            this.allowed = allowed;
            this.statusCode = statusCode;
            this.redirectTarget = redirectTarget;
        }
    }
}
