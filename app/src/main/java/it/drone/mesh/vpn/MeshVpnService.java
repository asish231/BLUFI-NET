package it.drone.mesh.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class MeshVpnService extends VpnService implements Runnable {
    private static final String TAG = MeshVpnService.class.getSimpleName();
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private boolean isRunning = false;

    private String gatewayAddress = "127.0.0.1";
    private int gatewayPort = 9090;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String addr = intent.getStringExtra("GATEWAY_ADDRESS");
            if (addr != null && !addr.isEmpty()) {
                gatewayAddress = addr;
            }
            int port = intent.getIntExtra("GATEWAY_PORT", 9090);
            if (port > 0) {
                gatewayPort = port;
            }
        }
        if (mThread == null || !mThread.isAlive()) {
            mThread = new Thread(this, "MeshVpnThread");
            mThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    @Override
    public void run() {
        try {
            Log.d(TAG, "Starting Mesh VPN Service with gateway " + gatewayAddress + ":" + gatewayPort);
            isRunning = true;
            setupVpn();

            FileInputStream in = new FileInputStream(mInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(mInterface.getFileDescriptor());
            byte[] packet = new byte[32767];

            while (isRunning) {
                int length = in.read(packet);
                if (length > 0) {
                    processTunPacket(packet, length, out);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in VPN service thread: " + e.getMessage());
        } finally {
            stopVpn();
        }
    }

    private void processTunPacket(byte[] packet, int length, FileOutputStream out) {
        if (length < 20) return; // Minimum IPv4 header size
        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) return; // Only process IPv4

        int protocol = packet[9] & 0xFF; // Protocol: 6 = TCP, 17 = UDP, 1 = ICMP
        String destIp = String.format("%d.%d.%d.%d", packet[16] & 0xFF, packet[17] & 0xFF, packet[18] & 0xFF, packet[19] & 0xFF);
        int destPort = 80;
        if (length >= 24) {
            destPort = ((packet[22] & 0xFF) << 8) | (packet[23] & 0xFF);
        }

        if (protocol == 6 || protocol == 17) {
            Log.d(TAG, "VPN TUN routing packet (protocol " + protocol + ") to gateway " + gatewayAddress + ":" + gatewayPort + " -> " + destIp + ":" + destPort);
        }
    }

    private void setupVpn() throws IOException {
        Builder builder = new Builder();
        builder.setSession("BLUFI-NET VPN Connection")
               .setMtu(1500)
               .addAddress("10.0.0.2", 24)
               .addRoute("0.0.0.0", 0)
               .addDnsServer("1.1.1.1")
               .addDnsServer("8.8.8.8");

        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Could not set disallowed application: " + e.getMessage());
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy(gatewayAddress, gatewayPort));
            } catch (Exception e) {
                Log.w(TAG, "Could not set HTTP proxy: " + e.getMessage());
            }
        }

        mInterface = builder.establish();
        Log.d(TAG, "VPN TUN interface established successfully for gateway " + gatewayAddress + ":" + gatewayPort);
    }

    private void stopVpn() {
        isRunning = false;
        if (mInterface != null) {
            try {
                mInterface.close();
            } catch (IOException e) {
                // Ignore
            }
            mInterface = null;
        }
        Log.d(TAG, "VPN Service stopped");
    }
}
