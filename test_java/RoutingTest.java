import java.util.List;
import java.util.Map;

public class RoutingTest {
    public static void main(String[] args) {
        testExactRoute();
        testSubpathRoute();
        testMethodNotAllowed();
        testRedirectRoute();
        System.out.println("Routing tests passed");
    }

    private static void testExactRoute() {
        Route r = new Route();
        r.path = "/";
        r.methods = List.of("GET");

        ServerConfig sc = new ServerConfig("127.0.0.1", List.of(8080), "localhost", Map.of(), 0L, List.of(r));

        HttpRequestParser.ParsedRequest req = HttpRequestParser.ParsedRequest.valid("GET", "/", "/", "", "HTTP/1.1", Map.of(), Map.of(), "", false, 0);
        Router.RouteMatch match = Router.matchRoute(sc, req);
        if (match == null || match.route == null || !match.allowed || match.statusCode != 200) throw new AssertionError("Exact route failed");
    }

    private static void testSubpathRoute() {
        Route r = new Route();
        r.path = "/list";
        r.methods = List.of("GET");

        ServerConfig sc = new ServerConfig("127.0.0.1", List.of(8080), "localhost", Map.of(), 0L, List.of(r));

        HttpRequestParser.ParsedRequest req = HttpRequestParser.ParsedRequest.valid("GET", "/list/item", "/list/item", "", "HTTP/1.1", Map.of(), Map.of(), "", false, 0);
        Router.RouteMatch match = Router.matchRoute(sc, req);
        if (match == null || match.route == null || !match.allowed || match.statusCode != 200) throw new AssertionError("Subpath route failed");
    }

    private static void testMethodNotAllowed() {
        Route r = new Route();
        r.path = "/onlypost";
        r.methods = List.of("POST");

        ServerConfig sc = new ServerConfig("127.0.0.1", List.of(8080), "localhost", Map.of(), 0L, List.of(r));

        HttpRequestParser.ParsedRequest req = HttpRequestParser.ParsedRequest.valid("GET", "/onlypost", "/onlypost", "", "HTTP/1.1", Map.of(), Map.of(), "", false, 0);
        Router.RouteMatch match = Router.matchRoute(sc, req);
        if (match == null || match.route == null || match.statusCode != 405) throw new AssertionError("Method not allowed failed");
    }

    private static void testRedirectRoute() {
        Route r = new Route();
        r.path = "/redirect";
        r.methods = List.of("GET");
        r.redirect = "/";

        ServerConfig sc = new ServerConfig("127.0.0.1", List.of(8080), "localhost", Map.of(), 0L, List.of(r));

        HttpRequestParser.ParsedRequest req = HttpRequestParser.ParsedRequest.valid("GET", "/redirect", "/redirect", "", "HTTP/1.1", Map.of(), Map.of(), "", false, 0);
        Router.RouteMatch match = Router.matchRoute(sc, req);
        if (match == null || match.route == null || match.statusCode != 302 || match.redirectTarget == null || !match.redirectTarget.equals("/")) throw new AssertionError("Redirect route failed");
    }
}
