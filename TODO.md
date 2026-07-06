# Project TODO

## Core Server
- [X] Build the non-blocking server loop with `java.nio` `Selector`, one process, one thread.
- [x] Support listening on multiple ports with one `ServerSocketChannel` per configured port.
- [x] Parse HTTP/1.1 requests manually: start line, headers, body, cookies, and chunked transfer encoding.
- [x] Implement HTTP response building: status line, headers, content length, keep-alive, and body.
- [ ] Add routing for `GET`, `POST`, and `DELETE` with per-route configuration matching.
- [ ] Serve static files safely with path normalization and directory traversal protection.

## Config and Routing
- [ ] Load and validate config at startup.
- [ ] Support host and multiple ports.
- [ ] Support default server selection.
- [ ] Support custom error page paths.
- [ ] Support client body size limits.
- [ ] Support routes with accepted methods, redirections, roots, default files, CGI extensions, and directory listing toggle.

## Uploads, Cookies, and Sessions
- [ ] Implement file uploads for `POST`.
- [ ] Enforce request body size limits for uploads and requests.
- [ ] Add cookie handling.
- [ ] Add session management.

## Errors and Reliability
- [ ] Add custom error pages for `400`, `403`, `404`, `405`, `413`, and `500`.
- [ ] Handle malformed requests without crashing.
- [ ] Add request timeouts.
- [ ] Prevent file descriptor leaks.
- [ ] Prevent memory leaks.

## CGI
- [ ] Implement CGI execution with `ProcessBuilder` for one extension such as `.py`.
- [ ] Pass the script path as the first argument.
- [ ] Set `PATH_INFO` correctly.
- [ ] Handle relative paths safely.
- [ ] Sanitize inputs for CGI.

## Testing
- [ ] Add automated tests for config parsing.
- [ ] Add tests for routing and redirects.
- [ ] Add tests for error pages.
- [ ] Add tests for uploads.
- [ ] Add tests for CGI execution.
- [ ] Stress test with `siege -b [IP]:[PORT]`.
- [ ] Check availability target of 99.5%.
- [ ] Verify memory behavior under load.

## Nice to Have
- [ ] Add a second CGI handler.
- [ ] Add an admin dashboard or server metrics endpoint.
- [ ] Improve README usage and config examples.
