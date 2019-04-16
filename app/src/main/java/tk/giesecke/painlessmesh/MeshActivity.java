package tk.giesecke.painlessmesh;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import android.os.Environment;
import android.os.Handler;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mesh activity, handles the UI
 */
public class MeshActivity extends AppCompatActivity {

    /** Tag for debug messages of service*/
    private static final String DBG_TAG = "MeshActivity";

    /** Flag if we try to connect to Mesh */
    private static boolean tryToConnect = false;
    /** Flag if connection to Mesh was started */
    private static boolean isConnected = false;
    /** Flag when user stops connection */
    private static boolean userDisConRequest = false;

    /** WiFi manager to connect to Mesh network */
    private WifiManager wifiMgr;

    /** Mesh name == Mesh SSID */
    private String meshName;
    /** Mesh password == Mesh network password */
    private String meshPw;
    /** Mesh port == TCP port number */
    private static int meshPort;

    /** WiFi AP to which device was connected before connecting to mesh */
    private String oldAPName = "";
    /** Mesh network entry IP */
    private static String meshIP;

    /** My Mesh node id */
    public static long myNodeId = 0;
    /** The node id we connected to */
    public static long apNodeId = 0;

    /** Filter for incoming messages */
    private long filterId = 0;

    /** View for connection status */
    private TextView tv_mesh_conn;
    /** View for errors */
    private TextView tv_mesh_err;
    /** View for received MESH messages */
    private TextView tv_mesh_msgs;
    /** View for connect button */
    private MenuItem mi_mesh_conn_bt;

    /** Predefined message 1 */
    private String predMsg1;
    /** Predefined message 2 */
    private String predMsg2;
    /** Predefined message 3 */
    private String predMsg3;
    /** Predefined message 4 */
    private String predMsg4;
    /** Predefined message 5 */
    private String predMsg5;

    /** Flag if log file should be written */
    private static boolean doLogging = true;
    /** For log file of data */
    static BufferedWriter out = null;
    /** Path to storage folder */
    private static String sdcardPath;
    /** Log file URI */
    private String logFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Start handler to send node sync request and time sync request every 5 seconds
        // Keeps socket connection more stable
        Handler handler = new Handler();
        handler.postDelayed(new Runnable(){
            boolean timeForNodeReq = true;
            public void run(){
                if (MeshConnector.isConnected()) {
                    if (timeForNodeReq) {
                        MeshHandler.sendNodeSyncRequest();
                        timeForNodeReq = false;
                    } else {
                        MeshHandler.sendTimeSyncRequest();
                        timeForNodeReq = true;
                    }
                }
                handler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    @SuppressLint({"DefaultLocale", "ClickableViewAccessibility"})
    @Override
    public void onResume() {
        // Get pointer to shared preferences
        SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Get previous mesh network credentials
        meshName = mPrefs.getString("pm_ssid", getResources().getString(R.string.prefs_name_hint));
        meshPw = mPrefs.getString("pm_pw", getResources().getString(R.string.prefs_pw_hint));
        meshPort = Integer.valueOf(mPrefs.getString("pm_port", "5555"));

        // Get predefined messages
        predMsg1 = mPrefs.getString("msg_1", "");
        predMsg2 = mPrefs.getString("msg_2", "");
        predMsg3 = mPrefs.getString("msg_3", "");
        predMsg4 = mPrefs.getString("msg_4", "");
        predMsg5 = mPrefs.getString("msg_5", "");

        // Ask for permissions if necessary
        ArrayList<String> arrPerm = new ArrayList<>();
        // On newer Android versions it is required to get the permission of the user to
        // get the location of the device. This is necessary to do a WiFi scan for APs.
        // I am not sure at all what that has to be with
        // the permission to use Bluetooth or BLE, but you need to get it anyway
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            arrPerm.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            arrPerm.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if(!arrPerm.isEmpty()) {
            String[] permissions = new String[arrPerm.size()];
            permissions = arrPerm.toArray(permissions);
            ActivityCompat.requestPermissions(this, permissions, 0);
        }

        // Enable access to connectivity
        // ThreadPolicy to get permission to access connectivity
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // Enable sharing of files
        // VmPolicy to get permission to share files
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());

        // Get path to SDCard
        sdcardPath = Environment.getExternalStorageDirectory().getPath() + "/painlessMesh/";

        // Get the wifi manager
        wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // Get views for the messages
        tv_mesh_msgs = findViewById(R.id.mesh_msgs);
        tv_mesh_conn = findViewById(R.id.tv_mesh_conn_status);
        tv_mesh_err = findViewById(R.id.tv_mesh_last_event);
        // View for filter button
        ImageButton ib_to_set = findViewById(R.id.bt_filter);
        // Set onClickListener
        ib_to_set.setOnClickListener(v12 -> handleFilterRequest());
        // View for clean button
        ib_to_set = findViewById(R.id.bt_clean);
        // Set onClickListener
        ib_to_set.setOnClickListener(v12 -> tv_mesh_msgs.setText(""));
        // View for share button
        ib_to_set = findViewById(R.id.bt_share);
        // Set onClickListener
        ib_to_set.setOnClickListener(v12 -> handleShareRequest());

        // View for predefined message 1 button
        Button bt_to_set = findViewById(R.id.bt_bc_pred_msg_1);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendNodeMessage(0,predMsg1);
            }
        });
        // View for predefined message 2 button
        bt_to_set = findViewById(R.id.bt_bc_pred_msg_2);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendNodeMessage(0,predMsg2);
            }
        });
        // View for predefined message 2 button
        bt_to_set = findViewById(R.id.bt_bc_pred_msg_3);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendNodeMessage(0,predMsg3);
            }
        });
        // View for predefined message 2 button
        bt_to_set = findViewById(R.id.bt_bc_pred_msg_4);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendNodeMessage(0,predMsg4);
            }
        });
        // View for predefined message 2 button
        bt_to_set = findViewById(R.id.bt_bc_pred_msg_5);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendNodeMessage(0,predMsg5);
            }
        });
        // View for time sync request button
        bt_to_set = findViewById(R.id.bt_time_sync);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendTimeSyncRequest();
            }
        });
        // View for node sync request button
        bt_to_set = findViewById(R.id.bt_node_sync);
        // Set onClickListener
        bt_to_set.setOnClickListener(v12 -> {
            if (MeshConnector.isConnected()) {
                MeshHandler.sendNodeSyncRequest();
            }
        });

        // Register Mesh events
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MeshConnector.MESH_DATA_RECVD);
        intentFilter.addAction(MeshConnector.MESH_SOCKET_ERR);
        intentFilter.addAction(MeshConnector.MESH_CONNECTED);
        intentFilter.addAction(MeshConnector.MESH_NODES);
        // Register network change events
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        // Register receiver
        registerReceiver(localBroadcastReceiver, intentFilter);
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (MeshConnector.isConnected()) {
            MeshConnector.Disconnect();
        }
        stopLogging();
        // unregister the broadcast receiver
        unregisterReceiver(localBroadcastReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        mi_mesh_conn_bt = menu.getItem(1);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int selItem = item.getItemId();

        switch (selItem) {
            case R.id.action_settings:
                if (MeshConnector.isConnected()) {
                    stopConnection();
                }
                final Intent intent = new Intent(this, MeshSettings.class);
                startActivity(intent);
                break;
            case R.id.home:
                if (MeshConnector.isConnected()) {
                    MeshConnector.Disconnect();
                }
                stopLogging();
                finish();
                break;
            case R.id.action_connect:
                mi_mesh_conn_bt = item;
                handleConnection();
                break;
            case R.id.action_send:
                if (MeshConnector.isConnected()) {
                    selectNodesForSending();
                } else {
                    showToast(getString(R.string.mesh_no_connection), Toast.LENGTH_SHORT);
                }
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Apply or remove filter to show only messages from a specific node
     */
    private void handleFilterRequest(){
        if (MeshConnector.isConnected()) {
            ArrayList<String> nodesListStr = new ArrayList<>();

            ArrayList<Long> tempNodesList = new ArrayList<>(MeshHandler.nodesList);

            tempNodesList.add(0L);
            Collections.sort(tempNodesList);

            for (int idx=0; idx<tempNodesList.size(); idx++) {
                nodesListStr.add(String.valueOf(tempNodesList.get(idx)));
            }
            nodesListStr.set(0,getString(R.string.mesh_filter_clear));

            ArrayAdapter<String> nodeListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nodesListStr);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.mesh_list_header))
                    .setInverseBackgroundForced(true)
                    .setNegativeButton(getString(android.R.string.cancel),
                            (dialog, which) -> {
                                // Do something here if you want
                                dialog.dismiss();
                            })
                    .setAdapter(nodeListAdapter,
                            (dialog, which) -> {
                                filterId = tempNodesList.get(which);
                                dialog.dismiss();
                            });
            builder.create();
            builder.show();
        } else {
            showToast(getString(R.string.mesh_no_connection), Toast.LENGTH_SHORT);
        }
    }

    /**
     * Handle connect action events.
     * Depending on current status
     * - Start connection request
     * - Cancel connection request if pending
     * - Stop connection to the mesh network
     */
    private void handleConnection() {
        if (!isConnected) {
            if (tryToConnect) {
                stopConnection();
            } else {
                startConnectionRequest();
            }
        } else {
            stopConnection();
        }
    }

    /**
     * Add mesh network AP to the devices list of Wifi APs
     * Enable mesh network AP to initiate connection to the mesh AP
     */
    private void startConnectionRequest() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        mi_mesh_conn_bt.setIcon(R.drawable.ic_menu_disconnect);
        tryToConnect = true;
        userDisConRequest = false;

        tv_mesh_conn.setText(getResources().getString(R.string.mesh_connecting));

        // Get current active WiFi AP
        oldAPName = "";

        // Get current WiFi connection
        ConnectivityManager connManager = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connManager != null) {
            NetworkInfo networkInfo = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (networkInfo.isConnected()) {
                final WifiInfo connectionInfo = wifiMgr.getConnectionInfo();
                if (connectionInfo != null && !connectionInfo.getSSID().isEmpty()) {
                    oldAPName = connectionInfo.getSSID();
                }
            }
        }

        // Add device AP to network list and enable it
        WifiConfiguration meshAPConfig = new WifiConfiguration();
        meshAPConfig.SSID = "\""+meshName+"\"";
        meshAPConfig.preSharedKey="\""+meshPw+"\"";
        int newId = wifiMgr.addNetwork(meshAPConfig);
        if (BuildConfig.DEBUG) Log.i(DBG_TAG, "Result of addNetwork: " + newId);
        wifiMgr.disconnect();
        wifiMgr.enableNetwork(newId, true);
        wifiMgr.reconnect();
    }

    /**
     * Stop connection to the mesh network
     * Disable the mesh AP in the device Wifi AP list so that
     * the device reconnects to its default AP
     */
    private void stopConnection() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        mi_mesh_conn_bt.setIcon(R.drawable.ic_menu_connect);

        if (MeshConnector.isConnected()) {
            MeshConnector.Disconnect();
        }
        isConnected = false;
        tryToConnect = false;
        userDisConRequest = true;
        List<WifiConfiguration> availAPs = wifiMgr.getConfiguredNetworks();

        if (oldAPName.isEmpty()) {
            for (int index = 0; index < availAPs.size(); index++) {
                if (availAPs.get(index).SSID.equalsIgnoreCase("\""+meshName+"\"")) {
                    wifiMgr.disconnect();
                    wifiMgr.disableNetwork(availAPs.get(index).networkId);
                    if (BuildConfig.DEBUG) Log.d(DBG_TAG, "Disabled: " + availAPs.get(index).SSID);
                    wifiMgr.reconnect();
                    break;
                }
            }
        } else {
            for (int index = 0; index < availAPs.size(); index++) {
                if (availAPs.get(index).SSID.equalsIgnoreCase(oldAPName)) {
                    wifiMgr.disconnect();
                    wifiMgr.enableNetwork(availAPs.get(index).networkId, true);
                    if (BuildConfig.DEBUG) Log.d(DBG_TAG, "Re-enabled: " + availAPs.get(index).SSID);
                    wifiMgr.reconnect();
                    break;
                }
            }
        }
        tv_mesh_conn.setText(getResources().getString(R.string.mesh_disconnected));
        stopLogging();
    }

    /**
     * Show known mesh nodes as preparation of sending a message
     * Uses a temporary nodes list in case the nodes list is refreshed
     * while this dialog is still open
     * Adds a BROADCAST node to enable sending broadcast messages to
     * the mesh network
     */
    private void selectNodesForSending() {
        ArrayList<String> nodesListStr = new ArrayList<>();

        ArrayList<Long> tempNodesList = new ArrayList<>(MeshHandler.nodesList);

        tempNodesList.add(0L);
        Collections.sort(tempNodesList);

        for (int idx=0; idx<tempNodesList.size(); idx++) {
            nodesListStr.add(String.valueOf(tempNodesList.get(idx)));
        }
        nodesListStr.set(0,getString(R.string.mesh_send_broadcast));

        ArrayAdapter<String> nodeListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, nodesListStr);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.mesh_list_header))
                .setInverseBackgroundForced(true)
                .setNegativeButton(getString(android.R.string.cancel),
                        (dialog, which) -> {
                            // Do something here if you want
                            dialog.dismiss();
                        })
                .setAdapter(nodeListAdapter,
                        (dialog, which) -> showSendDialog(tempNodesList.get(which)));
        builder.create();
        builder.show();
    }

    /**
     * Show the dialog to send a message to the mesh network
     * Options
     * - Send a time sync request
     * - Send a node sync request
     * - Send a user message
     * @param selectedNode nodeID that the message should be sent to
     */
    private void showSendDialog(long selectedNode) {
        // Get dialog layout
        LayoutInflater li = LayoutInflater.from(this);
        @SuppressLint("InflateParams") View sendDialogView = li.inflate(R.layout.send_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                this);

        TextView nodeToSendTo  = sendDialogView.findViewById(R.id.send_node_id);
        if (selectedNode == 0) {
            nodeToSendTo.setText(getString(R.string.mesh_send_broadcast));
        } else {
            nodeToSendTo.setText(String.valueOf(selectedNode));
        }

        final EditText msgToSend = sendDialogView.findViewById(R.id.send_node_msg);
        final long rcvNodeId = selectedNode;
        // set prompts.xml to alert dialog builder
        alertDialogBuilder.setView(sendDialogView)
                .setNegativeButton(getString(android.R.string.cancel),
                        (dialog, which) -> {
                            // Do something here if you want
                            dialog.dismiss();
                        })
                .setPositiveButton(getString(R.string.mesh_send_button),
                        (dialog, which) -> {
                            MeshHandler.sendNodeMessage(rcvNodeId, msgToSend.getText().toString());
                            // Do something here if you want
                            dialog.dismiss();
                        });
        // create alert dialog
        final AlertDialog alertDialog = alertDialogBuilder.create();

        // show it
        alertDialog.show();

        // Set the button functions
        final Button msg1Sel = sendDialogView.findViewById(R.id.bt_pred_msg_1);
        msg1Sel.setOnClickListener(v12 -> msgToSend.setText(predMsg1));
        final Button msg2Sel = sendDialogView.findViewById(R.id.bt_pred_msg_2);
        msg2Sel.setOnClickListener(v12 -> msgToSend.setText(predMsg2));
        final Button msg3Sel = sendDialogView.findViewById(R.id.bt_pred_msg_3);
        msg3Sel.setOnClickListener(v12 -> msgToSend.setText(predMsg3));
        final Button msg4Sel = sendDialogView.findViewById(R.id.bt_pred_msg_4);
        msg4Sel.setOnClickListener(v12 -> msgToSend.setText(predMsg4));
        final Button msg5Sel = sendDialogView.findViewById(R.id.bt_pred_msg_5);
        msg5Sel.setOnClickListener(v12 -> msgToSend.setText(predMsg5));
    }

    /**
     * Share current content of the message list through available
     * share resources (email/gmail/sms/facebook/...)
     */
    private void handleShareRequest() {
        if (doLogging) {
            // Still logging, cannot share logs yet
            showToast(getString(R.string.error_share), Toast.LENGTH_SHORT);
        } else {
            Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
            sharingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);

            MimeTypeMap map = MimeTypeMap.getSingleton();
            String ext = MimeTypeMap.getFileExtensionFromUrl(logFilePath);
            String type = map.getMimeTypeFromExtension(ext);

            File logFile = new File(logFilePath);
            Uri data = Uri.fromFile(logFile);
//            String AUTHORITY=
//                    BuildConfig.APPLICATION_ID+".provider";
//
//            Uri data= FileProvider.getUriForFile(this, AUTHORITY, logFile);

            sharingIntent.setDataAndType(data, type);
            sharingIntent.putExtra(Intent.EXTRA_STREAM, data);
            // TODO find out why the email address would be the filename if we do not set it here to ""
            sharingIntent.putExtra(Intent.EXTRA_EMAIL, "receiver@google.com");
//        sharingIntent.setType("text/plain");// Plain format text

            sharingIntent.putExtra( android.content.Intent.EXTRA_SUBJECT, meshName);
//            StringBuilder sharedMessage = new StringBuilder(meshName + "\nKnown nodes:\n");
//            for (int idx=0; idx < MeshHandler.nodesList.size(); idx++) {
//                sharedMessage.append(String.valueOf(MeshHandler.nodesList.get(idx))).append("\n");
//            }
//        sharedMessage.append(tv_mesh_msgs.getText().toString());
//            sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, sharedMessage.toString());
            startActivity(Intent.createChooser(sharingIntent, "Share collected messages using"));
        }
    }

    /**
     * Scroll the text view with the received messages to the bottom
     */
    private void scrollViewDown() {
        final ScrollView scrollview = findViewById(R.id.sv_msgs);
        scrollview.post(() -> scrollview.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /**
     * Show a custom toast (different colors, located in the center of the screen
     * @param msg Text to be displayed in the toast
     */
    private void showToast(String msg, int length) {
        LayoutInflater inflater = getLayoutInflater();
        View layouttoast = inflater.inflate(R.layout.custom_toast, findViewById(R.id.toastcustom));
        TextView toastText = layouttoast.findViewById(R.id.texttoast);
        toastText.setText(msg);
        toastText.setGravity(Gravity.CENTER);

        Toast toast = new Toast(getBaseContext());
        toast.setView(layouttoast);

        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.setDuration(length);

        toast.show();
    }

    /**
     * Local broadcast receiver
     * Registered for
     * - WiFi connection change events
     * - Mesh network data events
     * - Mesh network error events
     */
    private final BroadcastReceiver localBroadcastReceiver = new BroadcastReceiver() {
        @SuppressLint("DefaultLocale")
        @Override
        public void onReceive(final Context context, Intent intent) {
            // Connection change
            String intentAction = intent.getAction();
            Log.d(DBG_TAG, "Received broadcast: " + intentAction);
            // WiFi events
            if (tryToConnect && (intentAction != null) && (intentAction.equals(android.net.ConnectivityManager.CONNECTIVITY_ACTION))) {
                /* Access to connectivity manager */
                ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                /* WiFi connection information  */
                NetworkInfo wifiOn;
                if (cm != null) {
                    wifiOn = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                    if (wifiOn.isConnected()) {
                        if (tryToConnect) {
                            WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
                            if (wifiInfo.getSSID().equalsIgnoreCase("\"" + meshName + "\"")) {
                                Log.d(DBG_TAG, "Connected to Mesh network " + wifiOn.getExtraInfo());
                                // Get the gateway IP address
                                DhcpInfo dhcpInfo;
                                if (wifiMgr != null) {
                                    // Create the mesh AP node ID from the AP MAC address
                                    apNodeId = MeshHandler.createMeshID(wifiInfo.getBSSID());

                                    dhcpInfo = wifiMgr.getDhcpInfo();
                                    // Get the mesh AP IP
                                    int meshIPasNumber = dhcpInfo.gateway;
                                    meshIP = ((meshIPasNumber & 0xFF) + "." +
                                            ((meshIPasNumber >>>= 8) & 0xFF) + "." +
                                            ((meshIPasNumber >>>= 8) & 0xFF) + "." +
                                            (meshIPasNumber >>> 8 & 0xFF));

                                    // Create our node ID
                                    myNodeId = MeshHandler.createMeshID(MeshHandler.getWifiMACAddress());
                                } else {
                                    // We are screwed. Tell user about the problem
                                    Log.e(DBG_TAG, "Critical Error -- cannot get WifiManager access");
                                }
                                // Rest has to be done on UI thread
                                runOnUiThread(() -> {
                                    tryToConnect = false;

                                    String connMsg = "ID: " + String.valueOf(myNodeId) + " on " + meshName;
                                    tv_mesh_conn.setText(connMsg);

                                    // Set flag that we are connected
                                    isConnected = true;

                                    startLogging();

                                    // Connected to the Mesh network, start network task now
                                    MeshConnector.Connect(meshIP, meshPort, getApplicationContext());

                                });
                            } else {
                                List<WifiConfiguration> availAPs = wifiMgr.getConfiguredNetworks();

                                for (int index = 0; index < availAPs.size(); index++) {
                                    if (availAPs.get(index).SSID.equalsIgnoreCase("\""+meshName+"\"")) {
                                        wifiMgr.disconnect();
                                        wifiMgr.enableNetwork(availAPs.get(index).networkId, true);
                                        if (BuildConfig.DEBUG) Log.d(DBG_TAG, "Retry to enable: " + availAPs.get(index).SSID);
                                        wifiMgr.reconnect();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            String dataSet;
            DateTime now = new DateTime();
            dataSet = String.format ("[%02d:%02d:%02d:%03d] ",
                    now.getHourOfDay(),
                    now.getMinuteOfHour(),
                    now.getSecondOfMinute(),
                    now.getMillisOfSecond());

            // Mesh events
            if (MeshConnector.MESH_DATA_RECVD.equals(intentAction)) {
                String rcvdMsg = intent.getStringExtra("msg");
                String oldText;
                try {
                    JSONObject rcvdJSON = new JSONObject(rcvdMsg);
                    int msgType = rcvdJSON.getInt("type");
                    long fromNode = rcvdJSON.getLong("from");
                    switch (msgType) {
                        case 3: // TIME_DELAY
                            tv_mesh_err.setText(getString(R.string.mesh_event_time_delay));
                            dataSet += "Received TIME_DELAY\n";
                            break;
                        case 4: // TIME_SYNC
                            tv_mesh_err.setText(getString(R.string.mesh_event_time_sync));
                            dataSet += "Received TIME_SYNC\n";
                            break;
                        case 5: // NODE_SYNC_REQUEST
                        case 6: // NODE_SYNC_REPY
                            if (msgType != 5) {
                                tv_mesh_err.setText(getString(R.string.mesh_event_node_reply));
                                dataSet += "Received NODE_SYNC_REPLY\n";
                            } else {
                                tv_mesh_err.setText(getString(R.string.mesh_event_node_req));
                                dataSet += "Received NODE_SYNC_REQUEST\n";
                            }
                            // Generate known nodes list
                            final String nodesListString = rcvdMsg;
                            final Handler handler = new Handler();
                            handler.post(() -> MeshHandler.generateNodeList(nodesListString));
                            break;
                        case 7: // CONTROL ==> deprecated
                            dataSet += "Received CONTROL\n";
                            break;
                        case 8: // BROADCAST
                            dataSet += "Broadcast:\n" + rcvdJSON.getString("msg") + "\n";
                            if (filterId != 0) {
                                if (fromNode != filterId) {
                                    return;
                                }
                            }
                            oldText = "BC from " + String.valueOf(fromNode) + "\n\t" + rcvdJSON.getString("msg") + "\n";
                            tv_mesh_msgs.append(oldText);
                            break;
                        case 9: // SINGLE
                            dataSet += "Single Msg:\n" + rcvdJSON.getString("msg") + "\n";
                            if (filterId != 0) {
                                if (fromNode != filterId) {
                                    return;
                                }
                            }
                            oldText = "SM from " + String.valueOf(fromNode) + "\n\t" + rcvdJSON.getString("msg") + "\n";
                            tv_mesh_msgs.append(oldText);
                            break;
                    }
                } catch (JSONException e) {
                    Log.d(DBG_TAG, "Received message is not a JSON Object!");
                    oldText = "E: " + intent.getStringExtra("msg") + "\n";
                    tv_mesh_msgs.append(oldText);
                    dataSet += "ERROR INVALID DATA:\n" + intent.getStringExtra("msg") + "\n";
                }
                if (out != null) {
                    try {
                        out.append(dataSet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                scrollViewDown();
            } else if (MeshConnector.MESH_SOCKET_ERR.equals(intentAction)) {
                if (MeshHandler.nodesList != null) {
                    MeshHandler.nodesList.clear();
                }
                if (!userDisConRequest) {
                    showToast(getString(R.string.mesh_lost_connection), Toast.LENGTH_LONG);
                    MeshConnector.Connect(meshIP, meshPort, getApplicationContext());
                    tv_mesh_err.setText(intent.getStringExtra("msg"));
                }
            } else if (MeshConnector.MESH_CONNECTED.equals(intentAction)) {
                userDisConRequest = false;
            } else if (MeshConnector.MESH_NODES.equals(intentAction)) {
                String oldText = intent.getStringExtra("msg") + "\n";
                tv_mesh_msgs.append(oldText);
                scrollViewDown();
                dataSet += intent.getStringExtra("msg") + "\n";
                if (out != null) {
                    try {
                        out.append(dataSet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            String oldText = tv_mesh_msgs.getText().toString();
            // Check if the text is getting too long
            if (oldText.length() > 16535) {
                // Quite long, remove the first 20 lines  from the text
                int indexOfCr = 0;
                for (int lines=0; lines < 20; lines++) {
                    indexOfCr = oldText.indexOf("\n", indexOfCr+1);
                }
                oldText = oldText.substring(indexOfCr+1);
                tv_mesh_msgs.setText(oldText);
            }
        }
    };

    @SuppressLint("DefaultLocale")
    private void startLogging() {
        if (doLogging) {
            stopLogging();
        }

        DateTime now = new DateTime();
        String logTitle = String.format ("Log created: %02d/%02d/%02d %02d:%02d\n\n", now.getYear()-2000, now.getMonthOfYear(),
                now.getDayOfMonth(), now.getHourOfDay(), now.getMinuteOfHour());
        /* Name of the log */
        String logName = "meshLogFile.txt";

        // Create folder for this data set
        try {
            File appDir = new File(sdcardPath);
            boolean exists = appDir.exists();
            if (!exists) {
                boolean result = appDir.mkdirs();
                if (!result) {
                    Log.d(DBG_TAG, "Failed to create log folder");
                }
            }
        } catch (Exception exc) {
            Log.e(DBG_TAG, "Failed to create log folder: " + exc);
        }

        // If file is still open for writing, close it first
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (IOException exc) {
                Log.e(DBG_TAG, "Failed to close log file: " + exc);
            }
        }

        // TODO find a better solution to handle the log files
        // For now delete the old log file to avoid getting a too large file
        boolean result = new File(sdcardPath + logName).exists();
        if (result) {
            result = new File(sdcardPath + logName).delete();
            if (!result) {
                Log.d(DBG_TAG,"Failed to delete the old logfile");
            }
        }

        try {
            logFilePath = sdcardPath + logName;
            FileWriter newFile = new FileWriter(logFilePath);
            out = new BufferedWriter(newFile);
            out.append(logTitle);
        } catch (IOException exc) {
            Log.e(DBG_TAG, "Failed to open log file for writing: " + exc);
        }

        doLogging = true;
    }

    private void stopLogging() {
        if (doLogging) {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException exc) {
                    Log.e(DBG_TAG, "Failed to close log file: " + exc);
                }
                out = null;
            }
            doLogging = false;
            String[] toBeScannedStr = new String[1];
            toBeScannedStr[0] = sdcardPath + "*";
            MediaScannerConnection.scanFile(this, toBeScannedStr, null, (path, uri) -> System.out.println("SCAN COMPLETED: " + path));
        }
    }
}
