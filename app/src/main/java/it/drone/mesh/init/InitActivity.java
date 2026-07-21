package it.drone.mesh.init;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.text.InputFilter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import it.drone.mesh.R;
import it.drone.mesh.client.BLEClient;
import it.drone.mesh.common.Constants;
import it.drone.mesh.common.MeshPermissions;
import it.drone.mesh.common.RoutingTable;
import it.drone.mesh.common.Utility;
import it.drone.mesh.databinding.ActivityInitBinding;
import it.drone.mesh.listeners.Listeners;
import it.drone.mesh.models.Device;
import it.drone.mesh.security.AndroidMeshSecurity;
import it.drone.mesh.security.SecureMeshMessageType;
import it.drone.mesh.security.SecureMeshProtocol;
import it.drone.mesh.server.BLEServer;
import it.drone.mesh.tasks.AcceptBLETask;
import it.drone.mesh.vpn.MeshVpnService;
import it.drone.mesh.vpn.Socks5GatewayHandler;
import it.drone.mesh.wifi.Socks5ClientProtocol;
import it.drone.mesh.wifi.WifiDirectManager;
import it.drone.mesh.wifi.WifiDirectSocketFactory;

import static it.drone.mesh.common.ByteUtility.clearBit;
import static it.drone.mesh.common.Constants.DEMO_RUN;
import static it.drone.mesh.common.Constants.SIZE_OF_NETWORK;
import static it.drone.mesh.common.Constants.TEST_TIME_OF_CONVERGENCE;

public class InitActivity extends AppCompatActivity {

    private static final String TAG = InitActivity.class.getSimpleName();

    private static final long HANDLER_PERIOD = 5000;

    private static final String EMAIL_REQUEST = "email";
    private static final String TWITTER_REQUEST = "twitter";
    private static final String HTTP_REQUEST = "http";
    private static final String HTTP_RESPONSE = "http_response";
    private static final String STATUS_RESPONSE = "status_response";
    private static final int MAX_HTTP_BODY_BYTES = 480 * 1024;

    private long totalRelayedBytes = 0;
    private static final long RELAY_DATA_LIMIT = 50 * 1024 * 1024; // 50MB limit

    private ActivityInitBinding binding;
    private TextView debugger, whoAmI, myId, peerCount, noPeersHint;
    private Switch canIBeServerSwitch;
    private DeviceAdapter deviceAdapter;

    // Inline email fields
    private EditText emailTo, emailSubject, emailBody;
    // Inline HTTP/share input field
    private EditText httpInput;

    private BluetoothAdapter mBluetoothAdapter;
    private boolean canIBeServer = false;

    private boolean isServiceStarted = false;
    private boolean isVpnStarted = false;

    private BLEClient client;
    private BLEServer server;

    private AcceptBLETask.OnConnectionRejectedListener connectionRejectedListener;
    private Button startServices, sendTweet, sendEmail, startVpn;
    private WifiDirectManager wifiDirectManager;
    private Socks5GatewayHandler socks5GatewayHandler;
    private volatile String wifiDirectGatewayAddress;
    private WifiDirectManager.WifiDirectConnectionListener wifiDirectConnectionListener;
    private SecureMeshProtocol secureMeshProtocol;
    private final Map<String, String> pendingHttpUrls = new ConcurrentHashMap<>();

    private long startTime; // Per fare test su tempo convergenza rete;
    private boolean alreadyInizialized;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                if (hasRequiredMeshPermissions()) {
                    checkBluetoothAvailability();
                } else {
                    startServices.setEnabled(false);
                    writeErrorDebug(getString(R.string.mesh_permissions_denied));
                }
            }
    );

    private final ActivityResultLauncher<Intent> bluetoothEnableLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    checkBluetoothAvailability();
                } else {
                    writeErrorDebug(getString(R.string.bt_not_enabled));
                }
            }
    );

    private final ActivityResultLauncher<Intent> locationSettingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> checkLocationServices()
    );

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    writeErrorDebug("VPN permission denied by user");
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long offset = Constants.NO_OFFSET;
        canIBeServer = false;
        alreadyInizialized = false;
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityInitBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return windowInsets;
        });
        startServices = binding.startServices;
        debugger = binding.debugger;
        whoAmI = binding.whoAmi;
        myId = binding.myId;
        sendTweet = binding.tweetSomething;
        sendEmail = binding.sendMail;
        canIBeServerSwitch = binding.canIBeServerSwitch;

        // New inline input views
        emailTo = binding.emailTo;
        emailSubject = binding.emailSubject;
        emailBody = binding.emailBody;
        httpInput = binding.httpInput;
        peerCount = binding.peerCount;
        noPeersHint = binding.noPeersHint;

        try {
            secureMeshProtocol = AndroidMeshSecurity.create(getApplicationContext());
        } catch (GeneralSecurityException exception) {
            secureMeshProtocol = null;
            writeErrorDebug("Secure mesh is unavailable: " + exception.getMessage());
        }

        sendEmail.setEnabled(false);
        sendTweet.setEnabled(false);
        binding.browserGo.setEnabled(false);

        // Guide toggle
        binding.guideToggle.setOnClickListener(v -> {
            android.view.View content = binding.guideContent;
            if (content.getVisibility() == View.VISIBLE) {
                content.setVisibility(View.GONE);
                binding.guideToggle.setText("▼ Show");
            } else {
                content.setVisibility(View.VISIBLE);
                binding.guideToggle.setText("▲ Hide");
            }
        });

        // WebView configuration
        binding.browserWebview.getSettings().setJavaScriptEnabled(true);
        binding.browserWebview.setWebViewClient(new android.webkit.WebViewClient());
        binding.browserWebview.loadData("<html><body style='font-family:sans-serif;color:#64748B;background-color:#F8FAFC;padding:16px;'><h3 style='color:#3730A3'>Mesh Browser</h3><p>Enter a URL and tap <b>Go</b> to browse via the peer-to-peer Bluetooth mesh.</p><p style='color:#94A3B8;font-size:13px'>Works even without internet — your request hops through BLE peers to reach a gateway node.</p></body></html>", "text/html", "UTF-8");

        binding.browserGo.setOnClickListener(view -> {
            String url = binding.browserUrl.getText().toString().trim();
            if (url.isEmpty()) {
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            final String finalUrl = url;
            if (Utility.isDeviceOnline(getApplicationContext())) {
                writeDebug("Browser: loading over this device's internet connection");
                binding.browserWebview.loadUrl(finalUrl);
            } else if (wifiDirectManager != null
                    && wifiDirectManager.isConnected()
                    && !wifiDirectManager.isGroupOwner()) {
                loadUrlThroughWifiDirect(finalUrl);
            } else if (mBluetoothAdapter == null) {
                loadUrlForEmulator(finalUrl);
            } else {
                sendSecureGatewayRequest(HTTP_REQUEST + ";;" + finalUrl, finalUrl);
            }
        });

        canIBeServerSwitch.setChecked(canIBeServer);
        canIBeServerSwitch.setOnClickListener(view -> {
            if (canIBeServer) {
                canIBeServer = false;
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "I cannot be a server anymore", Toast.LENGTH_LONG).show());
            } else {
                canIBeServer = true;
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "I can be a server now", Toast.LENGTH_LONG).show());
            }
        });

        startVpn = binding.startVpn;
        startVpn.setEnabled(false);
        startVpn.setText(R.string.wifi_direct_tunnel_disconnected);
        startVpn.setOnClickListener(view -> {
            if (isVpnStarted) {
                stopVpnService();
            } else if (wifiDirectGatewayAddress == null && !Utility.isDeviceOnline(this)) {
                Toast.makeText(this, "Connect to Wi-Fi Direct gateway first to enable system VPN", Toast.LENGTH_LONG).show();
            } else {
                Intent vpnIntent = android.net.VpnService.prepare(InitActivity.this);
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent);
                } else {
                    startVpnService();
                }
            }
        });

        wifiDirectManager = WifiDirectManager.getInstance(this);
        wifiDirectConnectionListener = new WifiDirectManager.WifiDirectConnectionListener() {
            @Override
            public void onConnected(String goAddress, boolean isGroupOwner) {
                runOnUiThread(() -> {
                    writeDebug("Wi-Fi Direct connected");
                    if (!isGroupOwner) {
                        wifiDirectGatewayAddress = goAddress;
                        stopWifiGateway();
                        startVpn.setText(R.string.wifi_direct_tunnel_active);
                        startVpn.setEnabled(true);
                        binding.browserGo.setEnabled(true);
                    } else {
                        wifiDirectGatewayAddress = null;
                        startWifiGateway(goAddress);
                    }
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    writeDebug("Wi-Fi Direct disconnected");
                    wifiDirectGatewayAddress = null;
                    stopWifiGateway();
                    startVpn.setText(R.string.wifi_direct_tunnel_disconnected);
                });
            }

            @Override
            public void onError(String operation, String reason) {
                runOnUiThread(() -> writeErrorDebug("Wi-Fi Direct " + operation + ": " + reason));
            }

            @Override
            public void onUnavailable(String reason) {
                runOnUiThread(() -> {
                    startVpn.setText(R.string.wifi_direct_tunnel_unavailable);
                    writeErrorDebug(reason);
                });
            }
        };
        wifiDirectManager.setConnectionListener(wifiDirectConnectionListener);

        askPermissions();

        RecyclerView recyclerDeviceList = binding.recyScanResults;
        deviceAdapter = new DeviceAdapter(this);
        deviceAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updatePeerCount();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                updatePeerCount();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                updatePeerCount();
            }
        });
        recyclerDeviceList.setAdapter(deviceAdapter);
        recyclerDeviceList.setVisibility(View.VISIBLE);

        connectionRejectedListener = () -> {
            writeErrorDebug("Connection Rejected, stopping service");
            startServices.performClick();
        };


        startServices.setOnClickListener(view -> {
            if (isServiceStarted) {
                startServices.setText(R.string.start_service);
                isServiceStarted = false;
                if (mBluetoothAdapter == null) {
                    whoAmI.setText(R.string.whoami);
                    myId.setText(R.string.myid);
                    writeDebug("Mock Service stopped");
                    sendTweet.setEnabled(false);
                    sendEmail.setEnabled(false);
                    binding.browserGo.setEnabled(false);
                    startVpn.setEnabled(false);
                    deviceAdapter.cleanView();
                    return;
                }
                if (server != null) {
                    server.stopServer();
                    server = null;
                } else if (client != null) {
                    client.stopClient();
                    client = null;
                }
                if (wifiDirectManager != null) {
                    wifiDirectManager.disconnect();
                }
                stopVpnService();
                whoAmI.setText(R.string.whoami);
                myId.setText(R.string.myid);
                writeDebug("Service stopped");
                sendTweet.setEnabled(false);
                sendEmail.setEnabled(false);
                binding.browserGo.setEnabled(false);
                deviceAdapter.cleanView();
            } else {
                if (TEST_TIME_OF_CONVERGENCE)
                    initializeConvergenceNetworkTimeTest();

                startServices.setText(R.string.stop_service);
                isServiceStarted = true;
                cleanDebug();
                writeDebug("Service started");
                if (mBluetoothAdapter == null) {
                    writeErrorDebug("Bluetooth hardware is not available on this device.");
                    return;
                }
                if (Utility.isDeviceOnline(this))
                    Log.d(TAG, "OUD: " + "Ho internet");
                if (canIBeServer) {
                    server = BLEServer.getInstance(getApplicationContext());
                    server.setOnDebugMessageListener(new Listeners.OnDebugMessageListener() {
                        @Override
                        public void OnDebugMessage(String message) {
                            writeDebug(message);
                        }

                        @Override
                        public void OnDebugErrorMessage(String message) {
                            writeErrorDebug(message);
                        }
                    });

                    //probably useless
                    //if (lastServerIdFound[0] != (byte) 0) {
                    //    server.setLastServerIdFound(lastServerIdFound);
                    //}
                    server.startServer();
                    if (DEMO_RUN) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (server != null && isServiceStarted) {
                                writeDebug("[Demo] Simulating server initialized state...");
                                runOnUiThread(() -> {
                                    myId.setText("ID: 00");
                                    whoAmI.setText("Online (Server)");
                                    sendEmail.setEnabled(true);
                                    sendTweet.setEnabled(true);
                                    binding.browserGo.setEnabled(true);
                                });
                            }
                        }, 3000);
                    }
                    server.addServerInitializedListener(() -> new Handler(Looper.getMainLooper()).post(() -> {
                        myId.setText(server.getId());
                        whoAmI.setText(R.string.server);
                        sendEmail.setEnabled(true);
                        sendTweet.setEnabled(true);
                        binding.browserGo.setEnabled(true);
                        if (wifiDirectManager != null) {
                            writeDebug("Server initialized. Starting Wi-Fi Direct Group...");
                            wifiDirectManager.startGroup();
                        }
                    }));
                    server.addOnMessageReceivedListener(
                            (idMitt, message, hop, sendTimeStamp) -> handleSecureAddressedMessage(idMitt, message, hop)
                    );
                    server.addOnMessageReceivedWithInternet(this::handleSecureInternetMessage);
                    server.setEnoughServerListener((newServer) -> {
                        Log.d(TAG, "OUD: Stop server");
                        server.stopServer();
                        server = null;
                        client = BLEClient.getInstance(getApplicationContext());
                        //if (lastServerIdFound[0] != (byte) 0) {
                        //    client.setLastServerIdFound(lastServerIdFound);
                        //    lastServerIdFound[0] = (byte) 0;
                        //}
                        client.setOnConnectionLostListener(() -> {
                            new Handler(getMainLooper()).post(() -> startServices.performClick());
                            new Handler(getMainLooper()).postDelayed(() -> {
                                Toast.makeText(getApplicationContext(), "Problem with Your server, restart service in 5 seconds", Toast.LENGTH_SHORT).show();
                                startServices.performClick();
                            }, 5000);
                        });
                        client.startClient(newServer);

                        client.addOnClientOnlineListener(() -> {
                            if (client != null) {
                                deviceAdapter.setClient(getApplicationContext());
                                myId.setText(client.getId());
                                whoAmI.setText(R.string.client);
                                sendTweet.setEnabled(true);
                                sendEmail.setEnabled(true);
                                binding.browserGo.setEnabled(true);
                                if (wifiDirectManager != null) {
                                    writeDebug("Client connected. Scanning for Wi-Fi Direct Gateway...");
                                    wifiDirectManager.discoverPeers();
                                }
                                client.addReceivedListener(
                                        (idMitt, message, hop, sendTimeStamp) -> handleSecureAddressedMessage(idMitt, message, hop)
                                );
                                client.addReceivedWithInternetListener(this::handleSecureInternetMessage);
                                client.addDisconnectedServerListener((serverId, flags) -> {
                                    new Handler(getMainLooper()).post(() -> startServices.performClick());
                                    new Handler(getMainLooper()).postDelayed(() -> {
                                        Toast.makeText(getApplicationContext(), "Your server is offline, restart service in 5 seconds", Toast.LENGTH_SHORT).show();
                                        startServices.performClick();
                                    }, 5000);
                                });
                            }
                        });

                    });
                    deviceAdapter.setServer(getApplicationContext());
                } else {
                    client = BLEClient.getInstance(getApplicationContext());
                    //if (lastServerIdFound[0] != (byte) 0) {
                    //    client.setLastServerIdFound(lastServerIdFound);
                    //    lastServerIdFound[0] = (byte) 0;
                    //    lastServerIdFound[1] = (byte) 0;
                    //}
                    client.setOnConnectionLostListener(() -> {
                        new Handler(getMainLooper()).post(() -> startServices.performClick());
                        new Handler(getMainLooper()).postDelayed(() -> {
                            Toast.makeText(getApplicationContext(), "Problem with Your server, restart service in 5 seconds", Toast.LENGTH_SHORT).show();
                            startServices.performClick();
                        }, 5000);
                    });
                    client.startClient();
                    if (DEMO_RUN) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (client != null && isServiceStarted) {
                                writeDebug("[Demo] Simulating client connection to mock server gateway...");
                                runOnUiThread(() -> {
                                    deviceAdapter.setClient(getApplicationContext());
                                    myId.setText("ID: 01");
                                    whoAmI.setText("Online (Client)");
                                    sendTweet.setEnabled(true);
                                    sendEmail.setEnabled(true);
                                    binding.browserGo.setEnabled(true);
                                    writeDebug("[Demo] Client mock connection successful. Browser ready!");
                                });
                            }
                        }, 3000);
                    }

                    client.addOnClientOnlineListener(() -> {
                        deviceAdapter.setClient(getApplicationContext());
                        if (client != null) {
                            myId.setText(client.getId());
                            whoAmI.setText(R.string.client);
                            sendTweet.setEnabled(true);
                            sendEmail.setEnabled(true);
                            binding.browserGo.setEnabled(true);
                            if (wifiDirectManager != null) {
                                writeDebug("Client connected. Scanning for Wi-Fi Direct Gateway...");
                                wifiDirectManager.discoverPeers();
                            }
                            client.addReceivedListener(
                                    (idMitt, message, hop, sendTimeStamp) -> handleSecureAddressedMessage(idMitt, message, hop)
                            );
                            client.addReceivedWithInternetListener(this::handleSecureInternetMessage);
                            client.addDisconnectedServerListener((serverId, flags) -> {
                                new Handler(getMainLooper()).post(() -> startServices.performClick());
                                new Handler(getMainLooper()).postDelayed(() -> {
                                    Toast.makeText(getApplicationContext(), "Your server is offline, restart service in 5 seconds", Toast.LENGTH_SHORT).show();
                                    startServices.performClick();
                                }, 5000);
                            });
                        }

                    });
                }
            }
        });

        // HTTP / Share button now reads from the inline httpInput field
        sendTweet.setOnClickListener(view -> {
            String text = httpInput != null ? httpInput.getText().toString().trim() : "";
            if (text.isEmpty()) {
                Toast.makeText(this, "Please enter a URL or text in the field above.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (text.startsWith("http://") || text.startsWith("https://")) {
                if (Utility.isDeviceOnline(getApplicationContext())) {
                    writeDebug("HTTP GET using this device's internet connection");
                    new Thread(() -> {
                        String responseBody;
                        try {
                            okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient();
                            okhttp3.Request request = new okhttp3.Request.Builder().url(text).build();
                            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                                if (response.isSuccessful() && response.body() != null) {
                                    responseBody = response.body().string();
                                } else {
                                    responseBody = "HTTP Error: " + response.code();
                                }
                            }
                        } catch (Exception e) {
                            responseBody = "Network Error: " + e.getMessage();
                        }
                        final String result = responseBody;
                        runOnUiThread(() -> writeDebug("HTTP Response:\n" + result.substring(0, Math.min(result.length(), 300))));
                    }).start();
                } else {
                    sendSecureGatewayRequest(HTTP_REQUEST + ";;" + text, text);
                }
            } else {
                // Plain text → share/tweet
                if (Utility.isDeviceOnline(getApplicationContext())) {
                    tweetSomething(text);
                } else {
                    sendSecureGatewayRequest(TWITTER_REQUEST + ";;" + text, null);
                }
            }
        });

        // Email button now reads from inline fields in the Email card
        sendEmail.setOnClickListener(view -> {
            String to = emailTo != null ? emailTo.getText().toString().trim() : "";
            String subject = emailSubject != null ? emailSubject.getText().toString().trim() : "";
            String body = emailBody != null ? emailBody.getText().toString().trim() : "";

            if (to.isEmpty()) {
                Toast.makeText(this, "Please enter a recipient email address.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (body.isEmpty()) {
                Toast.makeText(this, "Please write a message.", Toast.LENGTH_SHORT).show();
                return;
            }

            String id = "00";
            if (client != null && client.getId() != null) id = client.getId();
            else if (server != null && server.getId() != null) id = server.getId();

            if (Utility.isDeviceOnline(getApplicationContext())) {
                writeDebug("Sending email directly (device has internet)...");
                try {
                    sendAMail(to, subject + "\n" + body, id);
                    runOnUiThread(() -> {
                        Toast.makeText(getApplicationContext(), "Email sent ✓", Toast.LENGTH_LONG).show();
                        emailTo.setText("");
                        emailSubject.setText("");
                        emailBody.setText("");
                    });
                } catch (IOException e) {
                    writeErrorDebug("Email error: " + e.getMessage());
                }
            } else {
                sendSecureGatewayRequest(
                        EMAIL_REQUEST + ";;" + to + ";;" + subject + ";;" + body,
                        null
                );
            }
        });

    }

    /**
     * Subscribe the activity to routing table updates and when the number of devices reaches Costants.SIZE_OF_NETWORK takes the time
     */
    private void initializeConvergenceNetworkTimeTest() {
        if (alreadyInizialized) return;
        else alreadyInizialized = true;

        startTime = System.nanoTime();

        RoutingTable.getInstance().subscribeToUpdates(new RoutingTable.OnRoutingTableUpdateListener() {
            @Override
            public void OnDeviceAdded(Device device) {
                if (SIZE_OF_NETWORK == RoutingTable.getInstance().getDeviceList().size()) {
                    long endTime = System.nanoTime();
                    long convergenceTime = (endTime - startTime) / 1000000;
                    cleanDebug();
                    writeDebug("Network convergence reached! Number of devices: " + SIZE_OF_NETWORK + ", Time (millis): " + convergenceTime);
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(InitActivity.this, "Network convergence reached! Number of devices: " + SIZE_OF_NETWORK + ", Time (millis): " + convergenceTime, Toast.LENGTH_SHORT).show());
                }

            }

            @Override
            public void OnDeviceRemoved(Device device) {

            }
        });
    }


    /**
     * Clean the field debugger
     */
    private void cleanDebug() {
        runOnUiThread(() -> debugger.setText(""));
    }

    /**
     * Write a message debug into log and text debugger. The message will be logged into the debug logger.
     *
     * @param message message to be written
     */
    private void writeDebug(final String message) {
        runOnUiThread(() -> {
            if (debugger.getLineCount() == debugger.getMaxLines())
                debugger.setText(String.format("%s\n", message));
            else
                debugger.setText(String.format("%s%s\n", String.valueOf(debugger.getText()), message));
        });
        Log.d(TAG, "OUD: " + message);
    }

    /**
     * Write a message debug into log and text debugger. The message will be logged into the error logger.
     *
     * @param message message to be written
     */
    private void writeErrorDebug(final String message) {
        runOnUiThread(() -> {
            if (debugger.getLineCount() == debugger.getMaxLines())
                debugger.setText(String.format("%s\n", message));
            else
                debugger.setText(String.format("%s%s\n", String.valueOf(debugger.getText()), message));
        });
        Log.e(TAG, message);
    }


    private void askPermissions() {
        List<String> missingPermissions = new ArrayList<>();
        addMissingPermissions(missingPermissions, MeshPermissions.requiredFor(Build.VERSION.SDK_INT));
        addMissingPermissions(missingPermissions, MeshPermissions.optionalFor(Build.VERSION.SDK_INT));

        if (missingPermissions.isEmpty()) {
            checkBluetoothAvailability();
        } else {
            permissionLauncher.launch(missingPermissions.toArray(new String[0]));
        }
    }

    private void addMissingPermissions(List<String> missingPermissions, String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
    }

    private boolean hasRequiredMeshPermissions() {
        for (String permission : MeshPermissions.requiredFor(Build.VERSION.SDK_INT)) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Controlla che il cellulare supporti l'app e il multiple advertisement. Maschera per onActivityResult e onRequestPermissionsResult
     */
    @SuppressLint("MissingPermission")
    private void checkBluetoothAvailability() {
        BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager != null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
        }

        if (mBluetoothAdapter == null) {
            startServices.setEnabled(true);
            writeDebug("Bluetooth not supported (Emulator). Running in Emulator Mock Mode.");
            return;
        }

        if (!mBluetoothAdapter.isEnabled()) {
            bluetoothEnableLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        startServices.setEnabled(true);
        if (mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            writeDebug("Everything is supported and enabled");
            if (!DEMO_RUN) {
                canIBeServer = true;
                canIBeServerSwitch.setVisibility(View.VISIBLE);
                canIBeServerSwitch.setChecked(true);
            }
        } else {
            writeDebug("Your device does not support multiple advertisement, you can be only client");
        }
        checkLocationServices();
    }


    /**
     * Makes request to enable GPS
     */
    protected void setGPSOn() {
        checkLocationServices();
    }

    private void checkLocationServices() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return;
        }

        boolean enabled;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            enabled = locationManager.isLocationEnabled();
        } else {
            enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        }

        if (!enabled) {
            try {
                locationSettingsLauncher.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (ActivityNotFoundException exception) {
                writeErrorDebug("Location settings are unavailable: " + exception.getMessage());
            }
        }
    }

    /**
     * Makes a tweet into the feed of the account corresponding the parameters passed to the application
     *
     * @param tweetToUpdate body of the tweet
     */
    private void tweetSomething(String tweetToUpdate) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, tweetToUpdate);
        runOnUiThread(() -> {
            try {
                startActivity(Intent.createChooser(shareIntent, getString(R.string.tweet_something)));
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Send an email
     *
     * @param destEmail destination email address
     * @param body      body field of the email
     * @param idMitt    id of the sender
     * @throws IOException if configuration files were not passed
     */
    private void sendAMail(final String destEmail, String body, final String idMitt) throws IOException {
        if (destEmail.trim().isEmpty()) {
            throw new IOException("Destination email address is empty");
        }

        Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
        emailIntent.setData(Uri.parse("mailto:" + Uri.encode(destEmail)));
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "A message from BLUFI-NET network");
        emailIntent.putExtra(Intent.EXTRA_TEXT, body + "\n\nRelayed for node " + idMitt);
        runOnUiThread(() -> {
            try {
                startActivity(emailIntent);
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(this, exception.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Secure mesh gateway (BLE) -------------------------------------------------

    /**
     * Returns this node's numeric mesh id, or null when the mesh is not yet initialised.
     */
    private String currentMeshId() {
        if (client != null && client.getId() != null) return client.getId();
        if (server != null && server.getId() != null) return server.getId();
        return null;
    }

    private void sendOverMesh(String payload, String dest, boolean internet) {
        Listeners.OnMessageSentListener listener = new Listeners.OnMessageSentListener() {
            @Override
            public void OnMessageSent(String msg) {
            }

            @Override
            public void OnCommunicationError(String error) {
                runOnUiThread(() -> writeErrorDebug("Mesh send error: " + error));
            }
        };
        if (server != null) {
            server.sendMessage(payload, dest, internet, listener);
        } else if (client != null) {
            client.sendMessage(payload, dest, internet, listener);
        } else {
            writeErrorDebug("Mesh not connected; cannot send request");
        }
    }

    /**
     * Client side: start an authenticated, end-to-end encrypted request towards an internet gateway.
     * Fails closed (no plaintext fallback) when secure mesh is unavailable.
     */
    private void sendSecureGatewayRequest(String payload, String url) {
        if (secureMeshProtocol == null) {
            writeErrorDebug("Secure mesh unavailable \u2014 request blocked (no plaintext fallback)");
            return;
        }
        String myId = currentMeshId();
        if (myId == null) {
            writeErrorDebug("Mesh identity not ready; cannot send secure request");
            return;
        }
        try {
            SecureMeshProtocol.OutboundMessage hello = secureMeshProtocol.beginRequest(myId, payload);
            if (url != null) {
                pendingHttpUrls.put(hello.getRequestId(), url);
            }
            sendOverMesh(hello.getPayload(), "00", true);
            writeDebug("Secure gateway request started (id " + hello.getRequestId() + ")");
        } catch (GeneralSecurityException exception) {
            writeErrorDebug("Unable to start secure request: " + exception.getMessage());
        }
    }

    /**
     * Gateway side: internet-bound messages arrive here. Only the secure HELLO is accepted; the
     * gateway replies with a signed acknowledgement so the client can verify its identity.
     */
    private void handleSecureInternetMessage(String idMitt, String message) {
        if (secureMeshProtocol == null) {
            writeErrorDebug("Secure mesh unavailable \u2014 dropping internet request from " + idMitt);
            return;
        }
        String myId = currentMeshId();
        if (myId == null) {
            return;
        }
        if (!SecureMeshProtocol.isSecureMessage(message)) {
            Log.w(TAG, "Ignoring non-secure internet message from " + idMitt);
            return;
        }
        try {
            if (SecureMeshProtocol.messageType(message) == SecureMeshMessageType.HELLO) {
                SecureMeshProtocol.OutboundMessage ack = secureMeshProtocol.acceptHello(message, idMitt, myId);
                sendOverMesh(ack.getPayload(), idMitt, false);
                writeDebug("Secure handshake acknowledged for client " + idMitt);
            }
        } catch (GeneralSecurityException exception) {
            writeErrorDebug("Rejected secure handshake from " + idMitt + ": " + exception.getMessage());
        }
    }

    /**
     * Addressed messages arrive here. Secure-protocol messages drive the handshake / request /
     * response state machine; anything else is treated as a plaintext status line.
     */
    private void handleSecureAddressedMessage(String idMitt, String message, int hop) {
        if (secureMeshProtocol == null || !SecureMeshProtocol.isSecureMessage(message)) {
            writeDebug("Message from " + idMitt + " (hop " + hop + "): " + message);
            return;
        }
        String myId = currentMeshId();
        if (myId == null) {
            return;
        }
        try {
            switch (SecureMeshProtocol.messageType(message)) {
                case HELLO_ACKNOWLEDGEMENT: {
                    SecureMeshProtocol.OutboundMessage request =
                            secureMeshProtocol.acceptHelloAcknowledgement(message, idMitt, myId);
                    sendOverMesh(request.getPayload(), idMitt, false);
                    break;
                }
                case REQUEST:
                    processSecureGatewayRequest(idMitt, myId, message);
                    break;
                case RESPONSE: {
                    SecureMeshProtocol.DecryptedMessage response =
                            secureMeshProtocol.decryptResponse(message, idMitt, myId);
                    displaySecureResponse(response);
                    break;
                }
                default:
                    break;
            }
        } catch (GeneralSecurityException exception) {
            writeErrorDebug("Secure message rejected from " + idMitt + ": " + exception.getMessage());
        }
    }

    private void processSecureGatewayRequest(String clientId, String myId, String requestPayload)
            throws GeneralSecurityException {
        SecureMeshProtocol.DecryptedMessage request =
                secureMeshProtocol.decryptRequest(requestPayload, clientId, myId);
        String requestId = request.getRequestId();
        String plaintext = request.getPlaintext();
        writeDebug("Gateway handling secure request " + requestId + " for client " + clientId);
        new Thread(() -> {
            String responseBody = executeGatewayAction(plaintext);
            try {
                SecureMeshProtocol.OutboundMessage response =
                        secureMeshProtocol.encryptResponse(requestId, clientId, myId, responseBody);
                sendOverMesh(response.getPayload(), clientId, false);
            } catch (GeneralSecurityException exception) {
                runOnUiThread(() -> writeErrorDebug("Unable to encrypt gateway response: " + exception.getMessage()));
            }
        }).start();
    }

    private String executeGatewayAction(String plaintext) {
        if (totalRelayedBytes > RELAY_DATA_LIMIT) {
            return "Error: gateway data cap reached (" + (RELAY_DATA_LIMIT / (1024 * 1024)) + "MB).";
        }
        String[] info = plaintext.split(";;");
        String action = info.length > 0 ? info[0] : "";
        switch (action) {
            case HTTP_REQUEST:
                if (info.length < 2) return "Error: malformed request";
                return executeGatewayHttpGet(info[1]);
            case EMAIL_REQUEST:
                if (info.length < 4) return "Error: malformed email request";
                try {
                    sendAMail(info[1], info[2] + "\n" + info[3], "relayed");
                    return "Email composer opened on the gateway device.";
                } catch (IOException exception) {
                    return "Email error: " + exception.getMessage();
                }
            case TWITTER_REQUEST:
                if (info.length < 2) return "Error: malformed share request";
                tweetSomething(info[1]);
                return "Share sheet opened on the gateway device.";
            default:
                return "Error: unsupported gateway action";
        }
    }

    /**
     * Executes an outbound HTTP(S) GET on behalf of a mesh peer with SSRF protection, a streamed and
     * capped response body, and no full-URL logging.
     */
    private String executeGatewayHttpGet(String url) {
        final java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException exception) {
            return "Error: invalid URL";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "Blocked: only http/https destinations are allowed";
        }
        String host = uri.getHost();
        if (host == null) {
            return "Error: invalid host";
        }
        try {
            it.drone.mesh.wifi.PublicInternetAddressPolicy.resolvePublic(host);
        } catch (java.net.UnknownHostException exception) {
            Log.w(TAG, "Blocked gateway request to a non-public host");
            return "Blocked: destination is not a public internet host";
        }
        Log.d(TAG, "Gateway relaying request to host: " + host);
        okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
        okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return "HTTP Error: " + response.code();
            }
            String body = readBoundedBody(response.body().byteStream());
            totalRelayedBytes += body.getBytes(StandardCharsets.UTF_8).length;
            runOnUiThread(() -> writeDebug("Relayed data: " + totalRelayedBytes + " / " + RELAY_DATA_LIMIT + " bytes"));
            return body;
        } catch (Exception exception) {
            return "Network Error: " + exception.getMessage();
        }
    }

    private String readBoundedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        while (total < MAX_HTTP_BODY_BYTES) {
            int read = in.read(chunk, 0, Math.min(chunk.length, MAX_HTTP_BODY_BYTES - total));
            if (read == -1) break;
            out.write(chunk, 0, read);
            total += read;
        }
        return out.toString("UTF-8");
    }

    private void displaySecureResponse(SecureMeshProtocol.DecryptedMessage response) {
        String url = pendingHttpUrls.remove(response.getRequestId());
        String body = response.getPlaintext();
        runOnUiThread(() -> {
            if (url != null) {
                binding.browserWebview.loadDataWithBaseURL(url, body, "text/html", "UTF-8", null);
                writeDebug("Gateway response received (id " + response.getRequestId() + ")");
            } else {
                writeDebug("[Gateway response " + response.getRequestId() + "]\n"
                        + body.substring(0, Math.min(body.length(), 500)));
            }
        });
    }

    // ---- Wi-Fi Direct internet tunnel ----------------------------------------------

    private void loadUrlForEmulator(String url) {
        writeDebug("Browser: emulator mode, loading directly");
        binding.browserWebview.loadUrl(url);
    }

    /**
     * Group-owner side: expose this device's internet connection to Wi-Fi Direct peers through a
     * local SOCKS5 gateway.
     */
    private void startWifiGateway(String goAddress) {
        stopWifiGateway();
        if (goAddress == null || goAddress.trim().isEmpty()) {
            writeErrorDebug("Wi-Fi Direct gateway address unavailable");
            return;
        }
        try {
            socks5GatewayHandler = new Socks5GatewayHandler(goAddress);
            socks5GatewayHandler.start();
            writeDebug("Sharing internet over Wi-Fi Direct (SOCKS5 port "
                    + Socks5GatewayHandler.GATEWAY_PORT + ")");
        } catch (RuntimeException exception) {
            writeErrorDebug("Unable to start Wi-Fi Direct gateway: " + exception.getMessage());
        }
    }

    private void stopWifiGateway() {
        if (socks5GatewayHandler != null) {
            socks5GatewayHandler.stop();
            socks5GatewayHandler = null;
        }
    }

    /**
     * Client side: fetch a page through the group owner's SOCKS5 gateway. DNS is resolved remotely by
     * the gateway, so it works even though this device has no direct internet.
     */
    private void loadUrlThroughWifiDirect(String url) {
        final String gatewayAddress = wifiDirectGatewayAddress;
        if (gatewayAddress == null || gatewayAddress.trim().isEmpty()) {
            writeErrorDebug("Wi-Fi Direct gateway is not ready yet");
            return;
        }
        writeDebug("Browser: routing through the Wi-Fi Direct gateway");
        new Thread(() -> {
            String body;
            try {
                body = fetchThroughSocks(gatewayAddress, Socks5GatewayHandler.GATEWAY_PORT, url);
            } catch (Exception exception) {
                body = "<html><body style='font-family:sans-serif;padding:16px'>"
                        + "<h3>Tunnel error</h3><p>" + exception.getMessage() + "</p></body></html>";
            }
            final String finalBody = body;
            runOnUiThread(() ->
                    binding.browserWebview.loadDataWithBaseURL(url, finalBody, "text/html", "UTF-8", null));
        }).start();
    }

    private String fetchThroughSocks(String proxyHost, int proxyPort, String url) throws IOException {
        java.net.URI uri = java.net.URI.create(url);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("Invalid URL host");
        }
        boolean https = scheme.equals("https");
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }

        java.net.Socket socket = new java.net.Socket();
        socket.connect(new java.net.InetSocketAddress(proxyHost, proxyPort), 15000);
        socket.setSoTimeout(20000);
        try {
            Socks5ClientProtocol.connect(socket.getInputStream(), socket.getOutputStream(), host, port);
            java.net.Socket streamSocket = socket;
            if (https) {
                javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket)
                        ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                                .createSocket(socket, host, port, true);
                sslSocket.startHandshake();
                streamSocket = sslSocket;
            }
            java.io.OutputStream output = streamSocket.getOutputStream();
            String httpRequest = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: BLUFI-NET\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            output.write(httpRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String full = readBoundedBody(streamSocket.getInputStream());
            int split = full.indexOf("\r\n\r\n");
            return split >= 0 ? full.substring(split + 4) : full;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wifiDirectManager != null) {
            wifiDirectManager.registerReceiver();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wifiDirectManager != null) {
            wifiDirectManager.unregisterReceiver();
        }
    }

    private void startVpnService() {
        isVpnStarted = true;
        startVpn.setText("Stop Mesh VPN (System-wide)");
        Intent intent = new Intent(this, MeshVpnService.class);
        intent.putExtra("GATEWAY_ADDRESS", wifiDirectGatewayAddress != null ? wifiDirectGatewayAddress : "127.0.0.1");
        intent.putExtra("GATEWAY_PORT", 9090);
        startService(intent);
        writeDebug("Mesh VPN Interface running (routing system traffic to SOCKS5 gateway " + (wifiDirectGatewayAddress != null ? wifiDirectGatewayAddress : "127.0.0.1") + ":9090)");
    }

    private void updatePeerCount() {
        runOnUiThread(() -> {
            int count = 0;
            if (deviceAdapter != null) {
                count = deviceAdapter.getItemCount();
            }
            if (count == 0) {
                RoutingTable rt = RoutingTable.getInstance();
                if (rt != null && rt.getDeviceList() != null) {
                    count = Math.max(0, rt.getDeviceList().size() - 1);
                }
            }
            if (peerCount != null) {
                peerCount.setText(String.valueOf(count));
            }
            if (noPeersHint != null) {
                if (count > 0) {
                    noPeersHint.setVisibility(View.GONE);
                } else {
                    noPeersHint.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void stopVpnService() {
        isVpnStarted = false;
        startVpn.setText("Start Mesh VPN (System-wide)");
        Intent intent = new Intent(this, MeshVpnService.class);
        stopService(intent);
        writeDebug("Mesh VPN Interface stopped");
    }

    @Override
    protected void onDestroy() {
        // Unregister Wi-Fi Direct receiver to avoid window leaks, but keep mesh service running in background
        if (wifiDirectManager != null) {
            wifiDirectManager.unregisterReceiver();
        }
        if (isServiceStarted) {
            writeDebug("InitActivity closed. Mesh services continue running in background.");
        }
        super.onDestroy();
    }
}
