package it.drone.mesh.vpn;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Socks5Server {
    private static final String TAG = Socks5Server.class.getSimpleName();
    public static final int SOCKS_PORT = 1080;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_CONNECTION_THREADS = 16;
    private final String gatewayAddress;
    private final int gatewayPort;
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

    public Socks5Server(String gatewayAddress, int gatewayPort) {
        this(gatewayAddress, gatewayPort, null);
    }

    public Socks5Server(String gatewayAddress, int gatewayPort, Listener listener) {
        if (gatewayAddress == null || gatewayAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("Gateway address is required");
        }
        if (gatewayPort < 1 || gatewayPort > 65535) {
            throw new IllegalArgumentException("Gateway port is invalid");
        }
        this.gatewayAddress = gatewayAddress;
        this.gatewayPort = gatewayPort;
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
        try (ServerSocket localServer = new ServerSocket()) {
            localServer.setReuseAddress(true);
            localServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), SOCKS_PORT), 16);
            synchronized (this) {
                if (!startRequested) {
                    return;
                }
                serverSocket = localServer;
                running = true;
            }
            Log.d(TAG, "Local SOCKS5 forwarder started");
            if (listener != null) {
                listener.onStarted();
            }
            while (startRequested) {
                try {
                    Socket clientSocket = localServer.accept();
                    ExecutorService pool = connectionPool;
                    if (pool != null && !pool.isShutdown()) {
                        pool.execute(() -> handleClient(clientSocket));
                    } else {
                        closeSocket(clientSocket);
                    }
                } catch (SocketException exception) {
                    if (startRequested) {
                        throw exception;
                    }
                }
            }
        } catch (IOException exception) {
            if (startRequested) {
                Log.e(TAG, "Local SOCKS5 forwarder failed", exception);
                if (listener != null) {
                    listener.onError(exception.getMessage() == null ? "Unable to start local proxy" : exception.getMessage());
                }
            }
        } finally {
            running = false;
            serverSocket = null;
        }
    }

    private void handleClient(Socket clientSocket) {
        try (Socket localSocket = clientSocket; Socket gatewaySocket = new Socket()) {
            gatewaySocket.connect(new InetSocketAddress(gatewayAddress, gatewayPort), CONNECT_TIMEOUT_MILLIS);
            InputStream localInput = localSocket.getInputStream();
            OutputStream localOutput = localSocket.getOutputStream();
            InputStream gatewayInput = gatewaySocket.getInputStream();
            OutputStream gatewayOutput = gatewaySocket.getOutputStream();
            ExecutorService pool = connectionPool;
            if (pool == null || pool.isShutdown()) {
                return;
            }
            pool.execute(() -> pipe(localInput, gatewayOutput, localSocket, gatewaySocket));
            pipe(gatewayInput, localOutput, gatewaySocket, localSocket);
        } catch (IOException exception) {
            Log.w(TAG, "Local SOCKS5 connection failed: " + exception.getMessage());
        }
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
            Log.d(TAG, "SOCKS5 stream closed: " + exception.getMessage());
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
                Log.d(TAG, "Local SOCKS5 server socket was already closed", exception);
            }
        }
    }

    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            Log.d(TAG, "SOCKS5 socket was already closed", exception);
        }
    }
}
