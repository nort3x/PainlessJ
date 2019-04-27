package tk.giesecke.painlessmesh;

import android.annotation.SuppressLint;
import android.util.Base64;
import android.util.Log;

import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class with handling functions to manage mesh network events and tasks
 * Includes some general utilities as well
 */
public class MeshHandler extends MeshActivity {

    /** Debug tag */
    private static final String DBG_TAG = "MeshHandler";

    // List of currently known nodes
    static ArrayList<Long> nodesList;

    /** Action for onActivityResult selecting update file */
    static final int SELECT_FILE_REQ = 1;

    /** Path to OTA file as String */
    static String otaPath;
    /** Name of file for OTA */
    static File otaFile = null;
    /** md5 Checksum of the OTA file */
    static String otaMD5;
    /** File size */
    static long otaFileSize;
    /** Size of one block */
    private static final int otaBlockSize = 1024;
    /** Number of blocks for the update */
    private static long numOfBlocks = 0;
    /** Selected HW type */
    private static String otaHWtype;
    /** Selected node type */
    private static String otaNodeType;

    /**
     * Returns mesh nodeID created from given MAC address.
     * @param macAddress mac address to create the nodeID
     * @return  nodeID or -1
     */
    static long createMeshID(String macAddress) {
        long calcNodeId = -1;
        String[] macAddressParts = macAddress.split(":");
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
        if (MeshCommunicator.isConnected()) {
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
                    dataSet += "Sending Single Message to :" + rcvNode + "\n" + msgToSend + "\n";
                }
                meshMessage.put("msg", msgToSend);
                String msg = meshMessage.toString();
                byte[] data = msg.getBytes();
                MeshCommunicator.WriteData(data);
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
        if (MeshCommunicator.isConnected()) {
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
                MeshCommunicator.WriteData(data);
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
        if (MeshCommunicator.isConnected()) {
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
                MeshCommunicator.WriteData(data);
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
     * Prepare OTA file advertisment and send it as broadcast
     * @param hwType 0 == ESP32, 1 == ESP8266
     * @param nodeType String indicating the target node type
     */
    static void sendOTAAdvertise(int hwType, String nodeType, boolean forcedUpdate) {
        numOfBlocks = (otaFileSize/otaBlockSize);
        long lastBlockSize = otaFileSize - (numOfBlocks * otaBlockSize);
        // If last block size is not 0, then we need to report 1 more block!
        if (lastBlockSize != 0) {
            numOfBlocks += 1;
        }
        Log.d(DBG_TAG, "Filesize = " + otaFileSize
                + " # of blocks = " + numOfBlocks
                + " last block size = " + lastBlockSize);
        JSONObject otaAdvert = new JSONObject();
        try {
            otaHWtype = hwType == 0 ? "ESP32" : "ESP8266";
            otaNodeType = nodeType;
            otaAdvert.put("plugin", "ota");
            otaAdvert.put("type", "version");
            otaAdvert.put("md5", otaMD5);
            otaAdvert.put("hardware", otaHWtype);
            otaAdvert.put("nodeType", otaNodeType);
            otaAdvert.put("noPart", numOfBlocks);
            otaAdvert.put("forced", forcedUpdate);
            // Send OTA advertisment
            sendNodeMessage(0, otaAdvert.toString());
        } catch (JSONException e) {
            Log.e(DBG_TAG, "Error sending OTA advertise: " + e.getMessage());
        }
    }

    /**
     * Send the requested block of the OTA file
     * @param rcvNode ID of the requesting node
     * @param partNo Requested block of the OTA file
     */
    static void sendOtaBlock(long rcvNode, long partNo) {
        // TODO do we need to queue update requests? Network might get very busy if we update several nodes at the same time

        JSONObject otaBlock = new JSONObject();
        RandomAccessFile otaFile;
        try {
            otaFile = new RandomAccessFile(otaPath, "r");
            otaFile.seek(partNo * otaBlockSize);
            int index = (int)(partNo * otaBlockSize);
            if (partNo != 0) {
                Log.d(DBG_TAG, "Request for part No " + partNo);
            }
            otaFile.seek(index);
            byte[] buffer = new byte[otaBlockSize];
            int size = otaFile.read(buffer, 0, otaBlockSize);
            String b64Buffer = Base64.encodeToString(buffer, 0, size, Base64.NO_WRAP);
            Log.d(DBG_TAG, "Sending block " + partNo + " with decoded size " + size + " encoded size " + b64Buffer.length());

            otaBlock.put("plugin", "ota");
            otaBlock.put("type", "data");
            otaBlock.put("md5", otaMD5);
            otaBlock.put("hardware", otaHWtype);
            otaBlock.put("nodeType", otaNodeType);
            otaBlock.put("noPart", numOfBlocks);
            otaBlock.put("partNo", partNo);
            otaBlock.put("data", b64Buffer);
            otaBlock.put("dataLength", b64Buffer.length());
            // Send OTA data block
            sendNodeMessage(rcvNode, otaBlock.toString());
        } catch (IOException | JSONException e) {
            Log.e(DBG_TAG, "Error sending OTA block " + partNo + " => " + e.getMessage());
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
                nodesListStr.append(nodesList.get(idx)).append("\n");
            }
            MeshCommunicator.sendMyBroadcast(MeshCommunicator.MESH_NODES, nodesListStr.toString());
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

    /**
     * Calculate the md5 checksum of a file
     * @param updateFile File the checksum should be calculated from
     */
    public static String calculateMD5(File updateFile) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(DBG_TAG, "Exception while getting digest", e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(updateFile);
        } catch (FileNotFoundException e) {
            Log.e(DBG_TAG, "Exception while getting FileInputStream", e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(DBG_TAG, "Exception on closing MD5 input stream", e);
            }
        }
    }
}
