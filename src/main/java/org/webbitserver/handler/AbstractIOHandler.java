package org.webbitserver.handler;

import org.webbitserver.HttpHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.webbitserver.helpers.NamingThreadFactory;

/**
 * Provides an abstract handler that comes with an executor for IO heavy tasks.
 * The default is to create a {@link java.util.concurrent.ScheduledExecutorService} with number of threads defined by {@link Runtime#getRuntime()#availableProcessors()}
 */
public abstract class AbstractIOHandler implements HttpHandler {

    protected final ExecutorService io;

    public AbstractIOHandler(ExecutorService io) {
        this.io = io;
    }
    public AbstractIOHandler() {
        this.io = executor();
    }

    public static ExecutorService executor() {
        return Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(), new NamingThreadFactory("WEBBIT-IO-THREAD"));
    }
}
