package org.jboss.xnio.core.nio;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import org.jboss.xnio.AbstractIoFuture;
import org.jboss.xnio.FailedIoFuture;
import org.jboss.xnio.FinishedIoFuture;
import org.jboss.xnio.IoFuture;
import org.jboss.xnio.IoHandler;
import org.jboss.xnio.channels.ConnectedStreamChannel;
import org.jboss.xnio.StreamIoConnector;
import org.jboss.xnio.spi.TcpConnector;
import org.jboss.xnio.spi.Lifecycle;

/**
 *
 */
public final class NioTcpConnector implements Lifecycle, StreamIoConnector<SocketAddress, ConnectedStreamChannel<SocketAddress>>, TcpConnector {

    private NioProvider nioProvider;
    private Executor executor;
    private boolean keepAlive = false;
    private boolean oobInline = false;
    private int receiveBufferSize = -1;
    private boolean reuseAddress = false;
    private int sendBufferSize = -1;
    private boolean tcpNoDelay = false;
    private int connectTimeout = -1;

    // accessors - configuration

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(final boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isOobInline() {
        return oobInline;
    }

    public void setOobInline(final boolean oobInline) {
        this.oobInline = oobInline;
    }

    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    public void setReceiveBufferSize(final int receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
    }

    public boolean isReuseAddress() {
        return reuseAddress;
    }

    public void setReuseAddress(final boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
    }

    public int getSendBufferSize() {
        return sendBufferSize;
    }

    public void setSendBufferSize(final int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
    }

    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    public void setTcpNoDelay(final boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(final int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    // accessors - dependencies

    public NioProvider getNioProvider() {
        return nioProvider;
    }

    public void setNioProvider(final NioProvider nioProvider) {
        this.nioProvider = nioProvider;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(final Executor executor) {
        this.executor = executor;
    }

    // lifecycle

    public void create() {
        if (nioProvider == null) {
            throw new NullPointerException("nioCore is null");
        }
    }

    public void start() {
        if (executor == null) {
            executor = nioProvider.getExecutor();
        }
    }

    public void stop() {
        executor = null;
    }

    public void destroy() {

    }

    private void configureStream(final Socket socket) throws SocketException {
        socket.setKeepAlive(keepAlive);
        socket.setOOBInline(oobInline);
        if (receiveBufferSize > 0) {
            socket.setReceiveBufferSize(receiveBufferSize);
        }
        socket.setReuseAddress(reuseAddress);
        if (sendBufferSize > 0) {
            socket.setSendBufferSize(sendBufferSize);
        }
        socket.setTcpNoDelay(tcpNoDelay);
    }

    public IoFuture<ConnectedStreamChannel<SocketAddress>> connectTo(final SocketAddress dest, final IoHandler<? super ConnectedStreamChannel<SocketAddress>> handler) {
        if (dest == null) {
            throw new NullPointerException("dest is null");
        }
        if (handler == null) {
            throw new NullPointerException("handler is null");
        }
        return doConnectTo(null, dest, handler);
    }

    public IoFuture<ConnectedStreamChannel<SocketAddress>> connectTo(final SocketAddress src, final SocketAddress dest, final IoHandler<? super ConnectedStreamChannel<SocketAddress>> handler) {
        if (src == null) {
            throw new NullPointerException("src is null");
        }
        if (dest == null) {
            throw new NullPointerException("dest is null");
        }
        if (handler == null) {
            throw new NullPointerException("handler is null");
        }
        return doConnectTo(src, dest, handler);
    }

    private IoFuture<ConnectedStreamChannel<SocketAddress>> doConnectTo(final SocketAddress src, final SocketAddress dest, final IoHandler<? super ConnectedStreamChannel<SocketAddress>> handler) {
        try {
            final SocketChannel socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            final Socket socket = socketChannel.socket();
            if (src != null) socket.bind(src);
            configureStream(socket);
            if (socketChannel.connect(dest)) {
                final NioSocketChannelImpl channel = new NioSocketChannelImpl(nioProvider, socketChannel, handler);
                executor.execute(new Runnable() {
                    public void run() {
                        handler.handleOpened(channel);
                    }
                });
                return new FinishedIoFuture<ConnectedStreamChannel<SocketAddress>>(channel);
            } else {
                final ConnectionHandler connectionHandler = new ConnectionHandler(executor, socketChannel, nioProvider, handler);
                connectionHandler.handle.getSelectionKey().interestOps(SelectionKey.OP_CONNECT).selector().wakeup();
                return connectionHandler.future;
            }
        } catch (IOException e) {
            return new FailedIoFuture<ConnectedStreamChannel<SocketAddress>>(e);
        }
    }

    /**
     *
     */
    private final class ConnectionHandler implements Runnable {
        private final FutureImpl future;
        private final SocketChannel socketChannel;
        private final NioHandle handle;
        private final IoHandler<? super ConnectedStreamChannel<SocketAddress>> handler;

        public ConnectionHandler(final Executor executor, final SocketChannel socketChannel, final NioProvider nioProvider, final IoHandler<? super ConnectedStreamChannel<SocketAddress>> handler) throws IOException {
            this.socketChannel = socketChannel;
            this.handler = handler;
            handle = nioProvider.addConnectHandler(socketChannel, this);
            future = new FutureImpl(executor);
        }

        public void run() {
            try {
                if (socketChannel.finishConnect()) {
                    final NioSocketChannelImpl channel = new NioSocketChannelImpl(nioProvider, socketChannel, handler);
                    future.setResult(channel);
                    handler.handleOpened(channel);
                    handle.cancelKey();
                } else {
                    handle.getSelectionKey().interestOps(SelectionKey.OP_CONNECT).selector().wakeup();
                    return;
                }
            } catch (IOException e) {
                future.setException(e);
                handle.cancelKey();
            } catch (Exception e) {
                final String message = e.getMessage();
                final IOException ioexception = new IOException("Connection failed unexpectedly: " + message);
                ioexception.setStackTrace(e.getStackTrace());
                future.setException(ioexception);
                handle.cancelKey();
            }
        }

        private final class FutureImpl extends AbstractIoFuture<ConnectedStreamChannel<SocketAddress>> {
            private final Executor executor;

            public FutureImpl(final Executor executor) {
                this.executor = executor;
            }

            protected boolean setException(final IOException exception) {
                return super.setException(exception);
            }

            protected boolean setResult(final ConnectedStreamChannel<SocketAddress> result) {
                return super.setResult(result);
            }

            protected boolean finishCancel() {
                return super.finishCancel();
            }

            protected void runNotifier(final Notifier<ConnectedStreamChannel<SocketAddress>> streamChannelNotifier) {
                executor.execute(new Runnable() {
                    public void run() {
                        try {
                            streamChannelNotifier.notify(FutureImpl.this);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            // todo log it
                        }
                    }
                });
            }

            public IoFuture<ConnectedStreamChannel<SocketAddress>> cancel() {
                if (finishCancel()) try {
                    socketChannel.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    // todo log it
                }
                return this;
            }
        }
    }
}