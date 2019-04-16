package tk.giesecke.painlessmesh;

import android.annotation.SuppressLint;
import android.util.Log;

import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class if handling functions to manage mesh network events and tasks
 */
class MeshHandler {

    /** Debug tag */
    private static final String DBG_TAG = "MeshHandler";

    // List of currently known nodes
    static ArrayList<Long> nodesList;

    /**
     * Returns mesh nodeID created from given MAC address.
     * @param macAddress mac address to create the nodeID
     * @return  nodeID or -1
     */
    static long createMeshID(String macAddress) {
        long calcNodeId = -1;
        String macAddressParts[] = macAddress.split(":");
        if (macAddressParts.length == 6) {
            try {
                long number = Long.valueOf(macAddressParts[2],16);
                if (number < 0) {number = number * -1;}
                calcNodeId = number * 256 * 256 * 256;
                number = Long.valueOf(macAddressParts[3],16);
                if (number < 0) {number = number * -1;}
                calcNodeId += number * 256 * 256;
                number = Long.valueOf(macAddressParts[4],16);
                if (number < 0) {number = number * -1;}
                calcNodeId += number * 256;
                number = Long.valueOf(macAddressParts[5],16);
                if (number < 0) {number = number * -1;}
                calcNodeId += number;
            } catch (NullPointerException ignore) {
                calcNodeId = -1;
            }
        }
        return calcNodeId;
    }

    /**
     * Returns MAC address of the given interface name.
     * @return  mac address or fake MAC address
     */
    static String getWifiMACAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.getName().equalsIgnoreCase("wlan0")) continue;
                byte[] mac = intf.getHardwareAddress();
                if (mac==null) return "";
                StringBuilder buf = new StringBuilder();
                for (byte aMac : mac) buf.append(String.format("%02X:",aMac));
                if (buf.length()>0) buf.deleteCharAt(buf.length()-1);
                return buf.toString();
            }
        } catch (Exception ignored) { } // for now eat exceptions
        // Couldn't get a MAC address, just imagine one
        return "01:02:03:04:05:06";
        /*try {
            // this is so Linux hack
            return loadFileAsString("/sys/class/net/" +interfaceName + "/address").toUpperCase().trim();
        } catch (IOException ex) {
            return null;
        }*/
    }

    /**
     * Send a message to the painlessMesh network
     * @param rcvNode Receiving node Id
     * @param msgToSend Message to send as "msg"
     */
    static void sendNodeMessage(long rcvNode, String msgToSend) {
        if (MeshConnector.isConnected()) {
            JSONObject meshMessage = new JSONObject();
            try {
                String dataSet = logTime();

                meshMessage.put("dest", rcvNode);
                meshMessage.put("from", MeshActivity.myNodeId);
                if (rcvNode == 0) {
                    meshMessage.put("type", 8);
                    dataSet += "Sending Broadcast:\n" + msgToSend + "\n";
                } else {
                    meshMessage.put("type", 9);
                    dataSet += "Sending Single Message to :" + String.valueOf(rcvNode) + "\n" + msgToSend + "\n";
                }
                meshMessage.put("msg", msgToSend);
                String msg = meshMessage.toString();
                byte[] data = msg.getBytes();
                MeshConnector.WriteData(data);
                if (MeshActivity.out != null) {
                    try {
                        MeshActivity.out.append(dataSet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(DBG_TAG, "Sending data " + msg);
            } catch (JSONException e) {
                Log.e(DBG_TAG, "Error sending data: " + e.getMessage());
            }
        }
    }

    /**
     * Send a node sync request to the painlessMesh network
     */
    static void sendNodeSyncRequest() {
        if (MeshConnector.isConnected()) {
            String dataSet = logTime();
            dataSet += "Sending NODE_SYNC_REQUEST\n";
            JSONObject nodeMessage = new JSONObject();
            JSONArray subsArray = new JSONArray();
            try {
                nodeMessage.put("dest", MeshActivity.apNodeId);
                nodeMessage.put("from", MeshActivity.myNodeId);
                nodeMessage.put("type", 5);
                nodeMessage.put("subs", subsArray);
                String msg = nodeMessage.toString();
                byte[] data = msg.getBytes();
                MeshConnector.WriteData(data);
                if (MeshActivity.out != null) {
                    try {
                        MeshActivity.out.append(dataSet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(DBG_TAG, "Sending node sync request" + msg);
            } catch (JSONException e) {
                Log.e(DBG_TAG, "Error sending node sync request: " + e.getMessage());
            }
        }
    }

    /**
     * Send a node sync request to the painlessMesh network
     */
    static void sendTimeSyncRequest() {
        if (MeshConnector.isConnected()) {
            String dataSet = logTime();
            dataSet += "Sending TIME_SYNC_REQUEST\n";
            JSONObject nodeMessage = new JSONObject();
            JSONObject typeObject = new JSONObject();
            try {
                nodeMessage.put("dest", MeshActivity.apNodeId);
                nodeMessage.put("from", MeshActivity.myNodeId);
                nodeMessage.put("type", 4);
                typeObject.put("type", 0);
                nodeMessage.put("msg", typeObject);
                String msg = nodeMessage.toString();
                byte[] data = msg.getBytes();
                MeshConnector.WriteData(data);
                if (MeshActivity.out != null) {
                    try {
                        MeshActivity.out.append(dataSet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(DBG_TAG, "Sending time sync request" + msg);
            } catch (JSONException e) {
                Log.e(DBG_TAG, "Error sending time sync request: " + e.getMessage());
            }
        }
    }

    /**
     * Build a list of known nodes from the received JSON data
     * @param routingInfo String with the JSON data
     */
    static void generateNodeList(String routingInfo) {
        // Creat list if necessary
        if (nodesList == null) {
            nodesList = new ArrayList<>();
        }
        ArrayList<Long> oldNodesList = new ArrayList<>(nodesList);
        Collections.sort(oldNodesList);

        nodesList.clear();
        // Start parsing the node list JSON
        try {
            JSONObject routingTop = new JSONObject(routingInfo);
            long from = routingTop.getLong("from");
            // TODO does it make sense to add our own nodeID? Cannot send to ourselfs!
//            nodesList.add(MeshActivity.myNodeId);
            nodesList.add(from);
            getSubsNodeId(routingTop);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        Log.d(DBG_TAG, "New nodes list: " + nodesList);
        Collections.sort(nodesList);
        boolean oldEqualNew = oldNodesList.containsAll(nodesList);
        boolean newEqualOld = nodesList.containsAll(oldNodesList);
        if (!oldEqualNew || !newEqualOld) {
            StringBuilder nodesListStr = new StringBuilder("Nodeslist changed\n");
            for (int idx=0; idx < nodesList.size(); idx++) {
                nodesListStr.append(String.valueOf(nodesList.get(idx))).append("\n");
            }
            MeshConnector.sendMyBroadcast(MeshConnector.MESH_NODES, nodesListStr.toString());
        }
    }

    /**
     * Extract "subs" entries from the JSON data
     * Get the nodeId from the "subs" entry
     * Check if there is another "subs" entry within
     * Call itself recursiv until all "subs" are parsed
     * @param test JSON object to work on
     */
    private static void getSubsNodeId(JSONObject test) {
        try {
            if (hasSubsNode(test)) {
                int idx = 0;
                long foundNode;
                JSONArray subs = test.getJSONArray("subs");
                // Go through all "subs" and get the node ids
                do {
                    foundNode = hasSubsNodeId(subs, idx);
                    if (foundNode != 0) {
                        nodesList.add(foundNode);
                    }
                    idx++;
                }
                while (foundNode != 0);

                // Go again through all "subs" and check if there is a "subs" within
                idx = 0;
                do {
                    try {
                        JSONObject subsub = subs.getJSONObject(idx);
                        getSubsNodeId(subsub);
                    } catch (JSONException ignore) {
                        return;
                    }
                    idx++;
                }
                while (idx <= 10);
            }
        } catch (JSONException e) {
            Log.d(DBG_TAG, "getSubsNodeId exception - should never happen");
        }
    }

    /**
     * Check if a JSON object has a "subs" entry
     * @param test JSON object to test
     * @return  true if "subs" was found, false if no "subs" was found
     */
    private static boolean hasSubsNode(JSONObject test) {
        try {
            test.getJSONArray("subs");
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Check if a JSON array has a "nodeId" entry
     * @param test JSON array to test
     * @param index Sub object to test
     * @return nodeID or 0 if no "nodeId" was found
     */
    private static long hasSubsNodeId(JSONArray test, int index) {
        try {
            return test.getJSONObject(index).getLong("nodeId");
        } catch (JSONException e) {
            return 0;
        }
    }

    /**
     * Get time for log output
     * @return String with date/time in format [hh:mm:ss:ms]
     */
    @SuppressLint("DefaultLocale")
    private static String logTime() {
        DateTime now = new DateTime();
        return String.format ("[%02d:%02d:%02d:%03d] ",
                now.getHourOfDay(),
                now.getMinuteOfHour(),
                now.getSecondOfMinute(),
                now.getMillisOfSecond());
    }
}
