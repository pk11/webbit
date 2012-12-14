package org.webbitserver.handler;

import static org.junit.Assert.assertTrue;
import static org.webbitserver.WebServers.createWebServer;
import static org.webbitserver.testutil.HttpClient.contents;
import static org.webbitserver.testutil.HttpClient.httpGet;
import org.webbitserver.handler.AbstractIOHandler;
import java.util.concurrent.ExecutionException;
import  org.webbitserver.*;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Test;
import org.webbitserver.WebServer;

public class AbstractIOHandlerTest {
    private WebServer webServer = createWebServer(59523);

    @After
    public void die() throws InterruptedException, ExecutionException {
        webServer.stop().get();
    }

    public static class MyHandler extends AbstractIOHandler {
         @Override
         public void handleHttpRequest(HttpRequest request, HttpResponse response, HttpControl control) throws Exception {
            response.write(io.toString());
         }

        public MyHandler(ExecutorService io) {
            super(io);
        }
    }

    @Test
    public void shouldHaveIOExecutor() throws Exception {
        webServer
                .add("/io", new MyHandler(AbstractIOHandler.executor()))
                .start()
                .get();
        assertTrue(contents(httpGet(webServer, "/io")).contains("java.util.concurrent.ScheduledThreadPoolExecutor"));
    }
}
