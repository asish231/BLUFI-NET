package it.drone.mesh.wifi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WpsInfo;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("MissingPermission")
public final class WifiDirectManager {
    private static final String TAG = WifiDirectManager.class.getSimpleName();
    private static WifiDirectManager instance;
    private final Context context;
    private final WifiP2pManager p2pManager;
    private final IntentFilter intentFilter;
    private final BroadcastReceiver receiver;
    private final List<WifiP2pDevice> peerList = new ArrayList<>();
    private WifiP2pManager.Channel channel;
    private WifiDirectConnectionListener connectionListener;
    private boolean receiverRegistered;
    private boolean channelRecoveryAttempted;
    private boolean p2pEnabled = true;
    private boolean connecting;
    private boolean isGroupOwner;
    private String groupOwnerAddress = "";
    private boolean isConnected;

    public interface WifiDirectConnectionListener {
        void onConnected(String goAddress, boolean isGroupOwner);

        void onDisconnected();

        default void onError(String operation, String reason) {
        }

        default void onUnavailable(String reason) {
        }
    }

    private WifiDirectManager(Context context) {
        this.context = context.getApplicationContext();
        p2pManager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        initializeChannel();
        intentFilter = createIntentFilter();
        receiver = createReceiver();
    }

    public static synchronized WifiDirectManager getInstance(Context context) {
        if (instance == null) {
            instance = new WifiDirectManager(context);
        }
        return instance;
    }

    public void setConnectionListener(WifiDirectConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void clearConnectionListener(WifiDirectConnectionListener listener) {
        if (connectionListener == listener) {
            connectionListener = null;
        }
    }

    public void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        if (!isSupported()) {
            notifyUnavailable("Wi-Fi Direct is not supported on this device");
            return;
        }
        ContextCompat.registerReceiver(
                context,
                receiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
        );
        receiverRegistered = true;
        refreshConnectionInfo();
    }

    public void unregisterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Wi-Fi Direct receiver was already unregistered", exception);
        } finally {
            receiverRegistered = false;
        }
    }

    public void startGroup() {
        if (!ensureReady("create group", false)) {
            return;
        }
        p2pManager.requestGroupInfo(channel, group -> {
            if (group == null) {
                createGroup();
            } else if (group.isGroupOwner()) {
                refreshConnectionInfo();
            } else {
                p2pManager.removeGroup(channel, actionListener("remove existing group", this::createGroup));
            }
        });
    }

    private void createGroup() {
        p2pManager.createGroup(channel, actionListener("create group", this::refreshConnectionInfo));
    }

    public void discoverPeers() {
        if (!ensureReady("discover peers", true)) {
            return;
        }
        p2pManager.discoverPeers(channel, actionListener("discover peers", null));
    }

    private void autoConnectToMeshPeer() {
        if (isConnected || isGroupOwner || connecting) {
            return;
        }
        List<WifiDirectPeer> candidates = new ArrayList<>();
        for (WifiP2pDevice device : peerList) {
            candidates.add(new WifiDirectPeer(
                    device.deviceAddress,
                    device.isGroupOwner(),
                    mapPeerState(device.status)
            ));
        }
        String address = WifiDirectPeerSelector.selectGroupOwner(candidates);
        if (address != null) {
            connectToDevice(address);
        }
    }

    public void connectToDevice(String deviceAddress) {
        if (!ensureReady("connect to peer", false)) {
            return;
        }
        if (deviceAddress == null || !deviceAddress.matches("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) {
            notifyError("connect to peer", "Invalid peer address");
            return;
        }
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0;
        connecting = true;
        p2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Wi-Fi Direct connection initiated");
            }

            @Override
            public void onFailure(int reason) {
                connecting = false;
                notifyError("connect to peer", reasonToString(reason));
            }
        });
    }

    public void disconnect() {
        if (!isSupported() || channel == null || !hasRuntimePermission()) {
            resetConnectionState();
            return;
        }
        p2pManager.stopPeerDiscovery(channel, quietActionListener("stop peer discovery"));
        p2pManager.cancelConnect(channel, quietActionListener("cancel connection"));
        p2pManager.requestGroupInfo(channel, group -> {
            if (group == null) {
                resetConnectionState();
            } else {
                p2pManager.removeGroup(channel, actionListener("disconnect", this::resetConnectionState));
            }
        });
    }

    public boolean isSupported() {
        return p2pManager != null
                && channel != null
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isGroupOwner() {
        return isGroupOwner;
    }

    public String getGroupOwnerAddress() {
        return groupOwnerAddress;
    }

    private void initializeChannel() {
        if (p2pManager == null) {
            channel = null;
            return;
        }
        channel = p2pManager.initialize(context, Looper.getMainLooper(), () -> {
            if (!channelRecoveryAttempted) {
                channelRecoveryAttempted = true;
                initializeChannel();
                notifyError("Wi-Fi Direct", "Framework channel recovered; retry the operation");
            } else {
                channel = null;
                resetConnectionState();
                notifyUnavailable("Wi-Fi Direct framework channel is unavailable");
            }
        });
    }

    private IntentFilter createIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        return filter;
    }

    private BroadcastReceiver createReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, @NonNull Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    p2pEnabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                    if (!p2pEnabled) {
                        resetConnectionState();
                        notifyUnavailable("Wi-Fi Direct is disabled");
                    }
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    refreshConnectionInfo();
                }
            }
        };
    }

    private void requestPeers() {
        if (!ensureReady("request peers", true)) {
            return;
        }
        p2pManager.requestPeers(channel, peers -> {
            peerList.clear();
            peerList.addAll(peers.getDeviceList());
            Log.d(TAG, "Wi-Fi Direct peers discovered: " + peerList.size());
            autoConnectToMeshPeer();
        });
    }

    private void refreshConnectionInfo() {
        if (!isSupported() || !hasRuntimePermission()) {
            return;
        }
        p2pManager.requestConnectionInfo(channel, this::handleConnectionInfo);
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (info == null || !info.groupFormed || info.groupOwnerAddress == null) {
            resetConnectionState();
            return;
        }
        String address = info.groupOwnerAddress.getHostAddress();
        if (address == null || address.isEmpty()) {
            resetConnectionState();
            return;
        }
        boolean stateChanged = !isConnected
                || isGroupOwner != info.isGroupOwner
                || !address.equals(groupOwnerAddress);
        isConnected = true;
        isGroupOwner = info.isGroupOwner;
        groupOwnerAddress = address;
        connecting = false;
        channelRecoveryAttempted = false;
        if (stateChanged && connectionListener != null) {
            connectionListener.onConnected(groupOwnerAddress, isGroupOwner);
        }
    }

    private boolean ensureReady(String operation, boolean requireLocationMode) {
        if (!isSupported()) {
            notifyUnavailable("Wi-Fi Direct is not supported on this device");
            return false;
        }
        if (!p2pEnabled) {
            notifyUnavailable("Wi-Fi Direct is disabled");
            return false;
        }
        if (!hasRuntimePermission()) {
            notifyError(operation, "Nearby Wi-Fi permission is missing");
            return false;
        }
        if (requireLocationMode && !isLocationModeEnabled()) {
            notifyError(operation, "Location services must be enabled for peer discovery");
            return false;
        }
        return true;
    }

    private boolean hasRuntimePermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationModeEnabled() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private WifiDirectPeerState mapPeerState(int status) {
        switch (status) {
            case WifiP2pDevice.CONNECTED:
                return WifiDirectPeerState.CONNECTED;
            case WifiP2pDevice.INVITED:
                return WifiDirectPeerState.INVITED;
            case WifiP2pDevice.AVAILABLE:
                return WifiDirectPeerState.AVAILABLE;
            case WifiP2pDevice.FAILED:
                return WifiDirectPeerState.FAILED;
            default:
                return WifiDirectPeerState.UNAVAILABLE;
        }
    }

    private WifiP2pManager.ActionListener actionListener(String operation, Runnable successAction) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Wi-Fi Direct operation succeeded: " + operation);
                if (successAction != null) {
                    successAction.run();
                }
            }

            @Override
            public void onFailure(int reason) {
                notifyError(operation, reasonToString(reason));
            }
        };
    }

    private WifiP2pManager.ActionListener quietActionListener(String operation) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Wi-Fi Direct operation succeeded: " + operation);
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Wi-Fi Direct operation did not complete: " + operation + " (" + reasonToString(reason) + ")");
            }
        };
    }

    private void resetConnectionState() {
        boolean wasConnected = isConnected;
        isConnected = false;
        isGroupOwner = false;
        connecting = false;
        groupOwnerAddress = "";
        peerList.clear();
        if (wasConnected && connectionListener != null) {
            connectionListener.onDisconnected();
        }
    }

    private void notifyError(String operation, String reason) {
        Log.e(TAG, "Wi-Fi Direct " + operation + " failed: " + reason);
        if (connectionListener != null) {
            connectionListener.onError(operation, reason);
        }
    }

    private void notifyUnavailable(String reason) {
        Log.w(TAG, reason);
        if (connectionListener != null) {
            connectionListener.onUnavailable(reason);
        }
    }

    private String reasonToString(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                return "framework is busy";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "Wi-Fi Direct is unsupported";
            case WifiP2pManager.ERROR:
            default:
                return "framework error (" + reason + ")";
        }
    }
}
