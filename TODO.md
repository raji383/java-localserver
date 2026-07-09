# Project TODO

## Core Server
- [X] Build the non-blocking server loop with `java.nio` `Selector`, one process, one thread.
- [x] Support listening on multiple ports with one `ServerSocketChannel` per configured port.
- [x] Parse HTTP/1.1 requests manually: start line, headers, body, cookies, and chunked transfer encoding.
- [x] Implement HTTP response building: status line, headers, content length, connection close, and body.
- [x] Add routing for `GET`, `POST`, and `DELETE` with per-route configuration matching.
- [x] Serve static files safely with path normalization and directory traversal protection.

## Config and Routing
- [x] Load and validate config at startup.
- [x] Support host and multiple ports.
- [x] Support default server selection.
- [x] Support custom error page paths.
- [x] Support client body size limits.
- [x] Support routes with accepted methods, redirections, roots, default files, CGI extensions, and directory listing toggle.

## Uploads, Cookies, and Sessions
- [x] Implement file uploads for `POST`.
- [x] Enforce request body size limits for uploads and requests.
- [x] Add cookie handling.
- [x] Add session management.

## Errors and Reliability
- [x] Add custom error pages for `400`, `403`, `404`, `405`, `413`, and `500`.
- [x] Handle malformed requests without crashing.
- [x] Add request timeouts.
- [x] Prevent file descriptor leaks.
- [x] Prevent memory leaks.

## CGI
- [x] Implement CGI execution with `ProcessBuilder` for one extension such as `.py`.
- [x] Pass the script path as the first argument.
- [x] Set `PATH_INFO` correctly.
- [x] Handle relative paths safely.
- [x] Sanitize inputs for CGI.

## Testing
- [x] Add automated tests for config parsing.
- [x] Add tests for routing and redirects.
- [x] Add tests for error pages.
- [x] Add tests for uploads.
- [x] Add tests for CGI execution.
- [ ] Stress test with `siege -b [IP]:[PORT]`.
- [x] Check availability target of 99.5%.
- [ ] Verify memory behavior under load.
