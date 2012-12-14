package org.webbitserver;

public interface ChunkedRequestHandler extends HttpHandler {
    Callback registerCallback(HttpRequest request, HttpResponse response) throws Exception;
}
