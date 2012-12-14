package org.webbitserver.netty;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.webbitserver.HttpControl;
import org.webbitserver.HttpHandler;
import org.webbitserver.WebbitException;
import org.webbitserver.Callback;
import org.webbitserver.handler.PathMatchHandler;
import org.jboss.netty.channel.Channel;
import org.webbitserver.ChunkedRequestHandler;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import org.jboss.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import org.jboss.netty.handler.codec.http.multipart.HttpDataFactory;

public class NettyHttpChannelHandler extends SimpleChannelUpstreamHandler {
   
    private final Executor executor;
    private final List<HttpHandler> httpHandlers;
    private final Object id;
    private final long timestamp;
    private final Thread.UncaughtExceptionHandler exceptionHandler;
    private final Thread.UncaughtExceptionHandler ioExceptionHandler;
    private final ConnectionHelper connectionHelper;
    private final ChunkProcessor chunkProcessor;

    public NettyHttpChannelHandler(Executor executor,
                                   List<HttpHandler> httpHandlers,
                                   Object id,
                                   long timestamp,
                                   Thread.UncaughtExceptionHandler exceptionHandler,
                                   Thread.UncaughtExceptionHandler ioExceptionHandler) {
        this.executor = executor;
        this.httpHandlers = httpHandlers;
        this.id = id;
        this.timestamp = timestamp;
        this.exceptionHandler = exceptionHandler;
        this.ioExceptionHandler = ioExceptionHandler;
        this.chunkProcessor = new ChunkProcessor(executor, exceptionHandler); 
        connectionHelper = new ConnectionHelper(executor, exceptionHandler, ioExceptionHandler) {
            @Override
            protected void fireOnClose() throws Exception {
                throw new UnsupportedOperationException();
            }
        };
    }

   /**
    * Processing chunks on the web thread in order, using the user supplied callback.
    */
    private static class ChunkProcessor implements Runnable {
        private final AtomicInteger chunkControl = new AtomicInteger();
        private final ConcurrentLinkedQueue<HttpChunk> chunks = new ConcurrentLinkedQueue<HttpChunk>();
        private final Executor executor;
        private final Thread.UncaughtExceptionHandler exceptionHandler;
        private Callback callback = null;

        public ChunkProcessor(Executor executor, Thread.UncaughtExceptionHandler exceptionHandler) {
            this.executor = executor;
            this.exceptionHandler = exceptionHandler;
        }    

        public void registerCallback(Callback callback) {
            this.callback = callback;
        }
        public final void run() { 
            if(chunkControl.get() == 1) { 
                try {
                    HttpChunk httpchunk = chunks.poll();
                    callback.onMessage(httpchunk.getContent().array());
                    if (httpchunk.isLast()) callback.onStop();
                } finally { 
                    chunkControl.set(0); 
                    executeChunkProcessing(); 
                } 
            } 
        }

         public final void executeChunkProcessing() { 
            if ( !chunks.isEmpty() && chunkControl.compareAndSet(0, 1) && callback != null) { 
                try { 
                    executor.execute(this); 
                } catch(RuntimeException e) { 
                    chunkControl.set(0); 
                    
                }
            }
        }

        /**
        * chunks are processed on the web thread
        * @param chunk to be processed
        */
        public final void submitChunk(HttpChunk chunk) { 
            if (chunks.offer(chunk) && callback != null) executeChunkProcessing();
        }

    }
  

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, MessageEvent messageEvent) throws Exception {
        if (messageEvent.getMessage() instanceof HttpRequest) {
            handleHttpRequest(ctx, messageEvent, (HttpRequest) messageEvent.getMessage());
        } else if (messageEvent.getMessage() instanceof HttpChunk) {
            chunkProcessor.submitChunk((HttpChunk) messageEvent.getMessage());
        } else         
            super.messageReceived(ctx, messageEvent);
    }

    private void handleHttpRequest(final ChannelHandlerContext ctx, MessageEvent messageEvent, final HttpRequest httpRequest) {
        final NettyHttpRequest nettyHttpRequest = new NettyHttpRequest(messageEvent, httpRequest, id, timestamp);
        final NettyHttpResponse nettyHttpResponse = new NettyHttpResponse(
                ctx, new DefaultHttpResponse(HTTP_1_1, OK), isKeepAlive(httpRequest), exceptionHandler);
        final NettyHttpControl control = new NettyHttpControl(httpHandlers.iterator(), executor, ctx,
                nettyHttpRequest, nettyHttpResponse, httpRequest, new DefaultHttpResponse(HTTP_1_1, OK),
                exceptionHandler, ioExceptionHandler);
          
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (httpRequest.isChunked()) {
                    Callback callback = null;
                    try {
                        for (HttpHandler handler : httpHandlers) {
                            if (handler instanceof ChunkedRequestHandler && 
                                (handler instanceof PathMatchHandler == false || 
                                (handler instanceof PathMatchHandler && 
                                ((PathMatchHandler)handler).valid(nettyHttpRequest.uri()))
                                )) {
                                callback = ((ChunkedRequestHandler)handler).registerCallback(nettyHttpRequest, nettyHttpResponse);
                                break;
                            }
                        }
                        if (callback != null) {
                            chunkProcessor.registerCallback(callback);
                            callback.onStart();
                            chunkProcessor.executeChunkProcessing();
                        } else
                            nettyHttpResponse.status(404).end();
                    } catch (Exception exception) { 
                        exceptionHandler.uncaughtException(Thread.currentThread(), WebbitException.fromException(exception, ctx.getChannel()));
                        //end response, so we won't keep getting chunks
                        nettyHttpResponse.status(500).end();
                    }  
                } else     
                    try {
                        control.nextHandler();
                    } catch (Exception exception) {
                        exceptionHandler.uncaughtException(Thread.currentThread(), WebbitException.fromException(exception, ctx.getChannel()));
                    }
            }
        });
        }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, final ExceptionEvent e) {
        connectionHelper.fireConnectionException(e);
    }

}
