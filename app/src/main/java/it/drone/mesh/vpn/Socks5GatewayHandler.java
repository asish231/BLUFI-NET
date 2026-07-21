package it.drone.mesh.vpn;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.drone.mesh.wifi.PublicInternetAddressPolicy;
import it.drone.mesh.wifi.Socks5ConnectRequest;
import it.drone.mesh.wifi.Socks5Protocol;

public final class Socks5GatewayHandler {
    private static final String TAG = Socks5GatewayHandler.class.getSimpleName();
    public static final int GATEWAY_PORT = 9090;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_CONNECTION_THREADS = 16;
    private final String bindAddress;
    private final Listener listener;
    private volatile ServerSocket serverSocket;
    private volatile boolean startRequested;
    private volatile boolean running;
    private ExecutorService acceptExecutor;
    private ExecutorService connectionPool;

    public interface Listener {
        default void onStarted() {
        }

        default void onError(String reason) {
        }

        default void onStopped() {
        }
    }

    public Socks5GatewayHandler(String bindAddress) {
        this(bindAddress, null);
    }

    public Socks5GatewayHandler(String bindAddress, Listener listener) {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("P2P bind address is required");
        }
        this.bindAddress = bindAddress;
        this.listener = listener;
    }

    public synchronized void start() {
        if (startRequested) {
            return;
        }
        startRequested = true;
        acceptExecutor = Executors.newSingleThreadExecutor();
        connectionPool = Executors.newFixedThreadPool(MAX_CONNECTION_THREADS);
        acceptExecutor.execute(this::runServer);
    }

    public synchronized void stop() {
        boolean notifyStopped = startRequested || running;
        startRequested = false;
        running = false;
        closeServerSocket();
        if (acceptExecutor != null) {
            acceptExecutor.shutdownNow();
            acceptExecutor = null;
        }
        if (connectionPool != null) {
            connectionPool.shutdownNow();
            connectionPool = null;
        }
        if (notifyStopped && listener != null) {
            listener.onStopped();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void runServer() {
        try (ServerSocket gatewayServer = new ServerSocket()) {
            InetAddress localAddress = InetAddress.getByName(bindAddress);
            if (localAddress.isAnyLocalAddress() || localAddress.isLoopbackAddress()) {
                throw new IOException("Gateway must bind to the Wi-Fi Direct interface");
            }
            gatewayServer.setReuseAddress(true);
            gatewayServer.bind(new InetSocketAddress(localAddress, GATEWAY_PORT), 16);
            synchronized (this) {
                if (!startRequested) {
                    return;
                }
                serverSocket = gatewayServer;
                running = true;
            }
            Log.d(TAG, "Wi-Fi Direct SOCKS5 gateway started");
            if (listener != null) {
                listener.onStarted();
            }
            while (startRequested) {
                try {
                    Socket incomingSocket = gatewayServer.accept();
                    ExecutorService pool = connectionPool;
                    if (pool != null && !pool.isShutdown()) {
                        pool.execute(() -> handleIncoming(incomingSocket));
                    } else {
                        closeSocket(incomingSocket);
                    }
                } catch (SocketException exception) {
                    if (startRequested) {
                        throw exception;
                    }
                }
            }
        } catch (IOException exception) {
            if (startRequested) {
                Log.e(TAG, "Wi-Fi Direct SOCKS5 gateway failed", exception);
                if (listener != null) {
                    listener.onError(exception.getMessage() == null ? "Unable to start gateway" : exception.getMessage());
                }
            }
        } finally {
            running = false;
            serverSocket = null;
        }
    }

    private void handleIncoming(Socket clientSocket) {
        try (Socket meshSocket = clientSocket) {
            InputStream meshInput = meshSocket.getInputStream();
            OutputStream meshOutput = meshSocket.getOutputStream();
            Socks5ConnectRequest request;
            try {
                request = Socks5Protocol.readConnectRequest(meshInput, meshOutput);
            } catch (ProtocolException exception) {
                Log.w(TAG, "Rejected malformed SOCKS5 request: " + exception.getMessage());
                return;
            }

            InetAddress[] addresses;
            try {
                addresses = PublicInternetAddressPolicy.resolvePublic(request.getHost());
            } catch (UnknownHostException exception) {
                Socks5Protocol.writeReply(meshOutput, Socks5Protocol.REPLY_CONNECTION_NOT_ALLOWED);
                Log.w(TAG, "Blocked non-public SOCKS5 destination");
                return;
            }

            try (Socket internetSocket = connect(addresses, request.getPort())) {
                Socks5Protocol.writeReply(meshOutput, Socks5Protocol.REPLY_SUCCEEDED);
                InputStream internetInput = internetSocket.getInputStream();
                OutputStream internetOutput = internetSocket.getOutputStream();
                ExecutorService pool = connectionPool;
                if (pool == null || pool.isShutdown()) {
                    return;
                }
                pool.execute(() -> pipe(meshInput, internetOutput, meshSocket, internetSocket));
                pipe(internetInput, meshOutput, internetSocket, meshSocket);
            } catch (IOException exception) {
                Socks5Protocol.writeReply(meshOutput, replyFor(exception));
                Log.w(TAG, "SOCKS5 destination connection failed: " + exception.getClass().getSimpleName());
            }
        } catch (IOException exception) {
            Log.w(TAG, "Wi-Fi Direct SOCKS5 client failed: " + exception.getMessage());
        }
    }

    private Socket connect(InetAddress[] addresses, int port) throws IOException {
        IOException lastException = null;
        for (InetAddress address : addresses) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                return socket;
            } catch (IOException exception) {
                lastException = exception;
                closeSocket(socket);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new NoRouteToHostException("No public address is available");
    }

    private int replyFor(IOException exception) {
        if (exception instanceof ConnectException) {
            return Socks5Protocol.REPLY_CONNECTION_REFUSED;
        }
        if (exception instanceof NoRouteToHostException || exception instanceof SocketTimeoutException) {
            return Socks5Protocol.REPLY_HOST_UNREACHABLE;
        }
        return Socks5Protocol.REPLY_GENERAL_FAILURE;
    }

    private void pipe(InputStream input, OutputStream output, Socket inputSocket, Socket outputSocket) {
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException exception) {
            Log.d(TAG, "Gateway SOCKS5 stream closed: " + exception.getMessage());
        } finally {
            closeSocket(inputSocket);
            closeSocket(outputSocket);
        }
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException exception) {
                Log.d(TAG, "Gateway server socket was already closed", exception);
            }
        }
    }

    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            Log.d(TAG, "Gateway socket was already closed", exception);
        }
    }
}
