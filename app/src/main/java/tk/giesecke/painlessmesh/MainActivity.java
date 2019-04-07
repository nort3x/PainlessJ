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
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import android.os.Handler;
import android.os.StrictMode;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    /** Tag for debug messages of service*/
    private static final String DBG_TAG = "MeshActivity";

    /** Access to activities shared preferences */
    private static SharedPreferences mPrefs;
    /* Name of shared preferences */
    private static final String sharedPrefName = "PainlessMesh";

    /** Flag if we try to connect to Mesh */
    private static boolean tryToConnect = false;
    /** Flag if connection to Mesh was started */
    private static boolean isConnected = false;

    /** WiFi manager to connect to Mesh network */
    private WifiManager wifiMgr;

    /** Mesh name == Mesh SSID */
    private String meshName;
    /** Mesh password == Mesh network password */
    private String meshPw;
    /** Mesh port == TCP port number */
    private static int meshPort;

    /** Mesh network entry IP */
    private static String meshIP;

    /** My Mesh node id */
    public static long myNodeId = 0;
    /** The node id we connected to */
    public static long apNodeId = 0;

    /** View for connection status */
    private TextView tv_mesh_conn;
    /** View for errors */
    private TextView tv_mesh_err;
    /** View for received MESH messages */
    private TextView tv_mesh_msgs;
    /** View for menu */
    private Menu thisMenu;
    /** View for connect button */
    private MenuItem mi_mesh_conn_bt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    @Override
    public void onResume() {
        // Get pointer to shared preferences
        mPrefs = getSharedPreferences(sharedPrefName,0);

        meshName = mPrefs.getString("MESH_NAME", getResources().getString(R.string.mesh_sett_name_hint));
        meshPw = mPrefs.getString("MESH_PW", getResources().getString(R.string.mesh_sett_pw_hint));
        meshPort = mPrefs.getInt("MESH_PORT", 5555);

        ArrayList<String> arrPerm = new ArrayList<>();
        // On newer Android versions it is required to get the permission of the user to
        // get the location of the device. This is necessary to do a WiFi scan for APs.
        // I am not sure at all what that has to be with
        // the permission to use Bluetooth or BLE, but you need to get it anyway
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            arrPerm.add(Manifest.permission.ACCESS_COARSE_LOCATION);
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

        // Get the wifi manager
        wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        tv_mesh_msgs = findViewById(R.id.mesh_msgs);
        tv_mesh_conn = findViewById(R.id.tv_mesh_conn_status);
        tv_mesh_err = findViewById(R.id.tv_mesh_last_event);

        // Register Mesh events
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MeshConnector.MESH_DATA_RECVD);
        intentFilter.addAction(MeshConnector.MESH_DISCON_ERR);
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
        // unregister the broadcast receiver
        unregisterReceiver(localBroadcastReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        thisMenu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int selItem = item.getItemId();

        switch (selItem) {
            case R.id.action_settings:
                showSettingsDialog();
                break;
            case R.id.home:
                if (MeshConnector.isConnected()) {
                    MeshConnector.Disconnect();
                }
                finish();
                break;
            case R.id.action_clean:
                tv_mesh_msgs.setText("");
                break;
            case R.id.action_connect:
                mi_mesh_conn_bt = item;
                handleConnection();
                break;
            case R.id.action_send:
                if ((MeshHandler.nodesList == null) || (MeshHandler.nodesList.size() == 0)) {
                    Toast.makeText(getApplicationContext(), getString(R.string.mesh_list_empty), Toast.LENGTH_SHORT).show();
                } else {
                    showNodesList();
                }
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showSettingsDialog() {
        LayoutInflater settInflater = LayoutInflater.from(this);
        @SuppressLint("InflateParams") View settView = settInflater.inflate(R.layout.settings, null);
        AlertDialog.Builder settDialogBuilder = new AlertDialog.Builder(this);
        settDialogBuilder.setView(settView);
        final EditText meshSettName = settView.findViewById(R.id.et_mesh_name);
        final EditText meshSettPW = settView.findViewById(R.id.et_mesh_pw);
        final EditText meshSettPort = settView.findViewById(R.id.et_mesh_port);

        meshSettName.setText(meshName);
        meshSettPW.setText(meshPw);
        meshSettPort.setText(String.valueOf(meshPort));

        settDialogBuilder
                .setCancelable(false)
                .setPositiveButton(getResources().getString(R.string.mesh_sett_save),
                        (dialog, id) -> {
                            meshName = meshSettName.getText().toString();
                            meshPw = meshSettPW.getText().toString();
                            meshPort = Integer.parseInt(meshSettPort.getText().toString());
                            mPrefs.edit().putString("MESH_NAME", meshName).apply();
                            mPrefs.edit().putString("MESH_PW", meshPw).apply();
                            mPrefs.edit().putInt("MESH_PORT", meshPort).apply();

                            dialog.dismiss();
                        })
                .setNegativeButton(getResources().getString(R.string.mesh_sett_cancel),
                        (dialog, id) -> dialog.cancel());
        AlertDialog settDialog = settDialogBuilder.create();
        settDialog.show();
    }

    private void handleConnection() {
        if (!isConnected) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            mi_mesh_conn_bt.setIcon(R.drawable.ic_menu_disconnect);
            tryToConnect = true;

            tv_mesh_conn.setText(getResources().getString(R.string.mesh_connecting));

            // Get Wifi manager
            wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            // Add device AP to network list and enable it
            WifiConfiguration meshAPConfig = new WifiConfiguration();
            meshAPConfig.SSID = "\""+meshName+"\"";
            meshAPConfig.preSharedKey="\""+meshPw+"\"";
            int newId = wifiMgr.addNetwork(meshAPConfig);
            if (BuildConfig.DEBUG) Log.i(DBG_TAG, "Result of addNetwork: " + newId);
            wifiMgr.disconnect();
            wifiMgr.enableNetwork(newId, true);
            wifiMgr.reconnect();

        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            mi_mesh_conn_bt.setIcon(R.drawable.ic_menu_connect);
            if (MeshConnector.isConnected()) {
                MeshConnector.Disconnect();
            }
            final Handler handler = new Handler();
            handler.postDelayed(() -> {
                isConnected = false;
                List<WifiConfiguration> availAPs = wifiMgr.getConfiguredNetworks();

                for (int index = 0; index < availAPs.size(); index++) {
                    if (availAPs.get(index).SSID.equalsIgnoreCase("\""+meshName+"\"")) {
                        wifiMgr.disconnect();
                        wifiMgr.disableNetwork(availAPs.get(index).networkId);
                        if (BuildConfig.DEBUG) Log.d(DBG_TAG, "Disabled: " + availAPs.get(index).SSID);
                        wifiMgr.reconnect();
                        break;
                    }
                }
                tv_mesh_conn.setText(getResources().getString(R.string.mesh_disconnected));
            },500);
        }
    }

    private void showNodesList() {
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

    private void showSendDialog(long selectedNode) {
        // Get dialog layout
        LayoutInflater li = LayoutInflater.from(this);
        @SuppressLint("InflateParams") View promptsView = li.inflate(R.layout.send_dialog, null);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                this);

        TextView nodeToSendTo  = promptsView.findViewById(R.id.send_node_id);
        if (selectedNode == 0) {
            nodeToSendTo.setText(getString(R.string.mesh_send_broadcast));
        } else {
            nodeToSendTo.setText(String.valueOf(selectedNode));
        }

        final EditText msgToSend = promptsView.findViewById(R.id.send_node_msg);
        final long rcvNodeId = selectedNode;

        // set prompts.xml to alert dialog builder
        alertDialogBuilder.setView(promptsView)
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
    }

    private final BroadcastReceiver localBroadcastReceiver = new BroadcastReceiver() {
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
                            if (wifiOn.getExtraInfo().equalsIgnoreCase("\"" + meshName + "\""))
                            {
                                Log.d(DBG_TAG, "Connected to Mesh network");
                                // Get the gateway IP address
                                WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                                DhcpInfo dhcpInfo;
                                if (wifiMgr != null) {
                                    WifiInfo wifiInfo = wifiMgr.getConnectionInfo();

                                    // Get AP node ID
                                    apNodeId = MeshHandler.createMeshID(wifiInfo.getBSSID());

                                    dhcpInfo = wifiMgr.getDhcpInfo();
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

                                    String connMsg = getResources().getString(R.string.mesh_connected) + " as: " + String.valueOf(myNodeId) + " to " + meshName;
                                    tv_mesh_conn.setText(connMsg);

                                    // Set flag that we are connected
                                    isConnected = true;

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
            // Mesh events
            if (MeshConnector.MESH_DATA_RECVD.equals(intentAction)) {
                String rcvdMsg = intent.getStringExtra("msg");
                String oldText;
                try {
                    JSONObject rcvdJSON = new JSONObject(rcvdMsg);
                    int msgType = rcvdJSON.getInt("type");
                    switch (msgType) {
                        case 3: // TIME_DELAY
                            tv_mesh_err.setText(getString(R.string.mesh_event_time_delay));
                            break;
                        case 4: // TIME_SYNC
                            tv_mesh_err.setText(getString(R.string.mesh_event_time_sync));
                            break;
                        case 5: // NODE_SYNC_REQUEST
                            tv_mesh_err.setText(getString(R.string.mesh_event_node_req));
                        case 6: // NODE_SYNC_REPY
                            if (msgType != 5) {
                                tv_mesh_err.setText(getString(R.string.mesh_event_node_reply));
                            }
                            // Generate known nodes list
                            final String nodesListString = rcvdMsg;
                            final Handler handler = new Handler();
                            handler.post(() -> MeshHandler.generateNodeList(nodesListString));
                            break;
                        case 7: // CONTROL ==> deprecated
                            break;
                        case 8: // BROADCAST
                            oldText = tv_mesh_msgs.getText().toString();
                            oldText += "B: " + rcvdJSON.getString("msg") + "\n";
                            tv_mesh_msgs.setText(oldText);
                            break;
                        case 9: // SINGLE
                            oldText = tv_mesh_msgs.getText().toString();
                            oldText += "S: " + rcvdJSON.getString("msg") + "\n";
                            tv_mesh_msgs.setText(oldText);
                            break;
                    }
                } catch (JSONException e) {
                    Log.d(DBG_TAG, "Received message is not a JSON Object!");
                    oldText = tv_mesh_msgs.getText().toString();
                    oldText += "E: " + intent.getStringExtra("msg") + "\n";
                    tv_mesh_msgs.setText(oldText);
                }
            } else if (MeshConnector.MESH_DISCON_ERR.equals(intentAction)) {
                tv_mesh_err.setText(intent.getStringExtra("msg"));
                MenuItem connectItem = thisMenu.findItem(R.id.action_connect);
                connectItem.setIcon(R.drawable.ic_menu_connect);
                tv_mesh_conn.setText(getResources().getString(R.string.mesh_disconnected));
                // TODO what shall we do in this case?
                // We got disconnected, try to reconnect
                isConnected = false;
                handleConnection();
//                MeshConnector.Connect(meshIP, meshPort, getApplicationContext());
            }
        }
    };
}
