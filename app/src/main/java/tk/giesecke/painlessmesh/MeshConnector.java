package tk.giesecke.painlessmesh;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

class MeshConnector {
    private static final String DBG_TAG = "MeshConnector"; //For debugging, always a good idea to have defined

    /** Action for MESH data arrived */
    static final String MESH_DATA_RECVD = "DATA";
    /** Action for diconnect because of error */
    static final String MESH_DISCON_ERR = "DATA";

    private static boolean receiveThreadRunning = false;
    private static long startTime = 0L;

    private static Socket connectionSocket;

    //Runnables for sending and receiving data
    private static SendRunnable sendRunnable;
    //Threads to execute the Runnables above
    private static Thread sendThread;
    private static Thread receiveThread;

    private static String severIp =   "192.168.0.2";
    private static int serverPort = 1234;

    private static Context appContext;

    /**
     * Returns true if MeshConnector is connected, else false
     * @return Boolean
     */
    static boolean isConnected() {
        return connectionSocket != null && connectionSocket.isConnected() && !connectionSocket.isClosed();
    }

    /**
     * Open connection to server
     */
    static void Connect(String ip, int port, Context thisContext) {
        severIp = ip;
        serverPort = port;
        appContext = thisContext;
        new Thread(new ConnectRunnable()).start();
    }

    /**
     * Close connection to server
     */
    static void Disconnect() {
        stopThreads();

        try {
            connectionSocket.close();
            Log.d(DBG_TAG,"Disconnected!");
        } catch (IOException e) {
            Log.d(DBG_TAG, "Disconnect failed: " + e.getMessage());
        }

    }

    /**
     * Send data to server
     * @param data byte array to send
     */
    static void WriteData(byte[] data) {
        if (isConnected()) {
            startSending();
            sendRunnable.Send(data);
        }
    }

    private static void stopThreads() {
        if (receiveThread != null)
            receiveThread.interrupt();

        if (sendThread != null)
            sendThread.interrupt();
    }

    private static void startSending() {
        sendRunnable = new SendRunnable(connectionSocket);
        sendThread = new Thread(sendRunnable);
        sendThread.start();
    }

    private static void startReceiving() {
        ReceiveRunnable receiveRunnable = new ReceiveRunnable(connectionSocket);
        receiveThread = new Thread(receiveRunnable);
        receiveThread.start();
    }

    static class ReceiveRunnable implements Runnable {
        private final Socket sock;
        private InputStream input;

        ReceiveRunnable(Socket server) {
            sock = server;
            try {
                input = sock.getInputStream();
            } catch (Exception e) {
                Log.d(DBG_TAG, "ReceiveRunnable failed: " + e.getMessage());
            }
        }

        @Override
        public void run() {
            Log.d(DBG_TAG, "Receiving started");
            while (!Thread.currentThread().isInterrupted() && isConnected()) {
                if (!receiveThreadRunning)
                    receiveThreadRunning = true;

                startTime = System.currentTimeMillis();
                try {
                    byte[] buffer = new byte[4096];
                    //Read the first integer, it defines the length of the data to expect
                    int readLen = input.read(buffer,0,buffer.length);
                    if (readLen > 0) {
                        byte[] data = new byte[readLen];

                        System.arraycopy(buffer, 0, data, 0, readLen);
                        data[readLen-1] = 0;

                        String rcvdMsg = new String(data, StandardCharsets.UTF_8);
                        int realLen = rcvdMsg.lastIndexOf("}");
                        rcvdMsg = rcvdMsg.substring(0, realLen+1);
                        Log.i(DBG_TAG, "Received " + readLen + " bytes: " + rcvdMsg);

                        long time = System.currentTimeMillis() - startTime;
                        Log.i(DBG_TAG, "Data received! Took: " + time + "ms and got: " + (readLen-1) + "bytes");

                        sendMyBroadcast(MESH_DATA_RECVD, rcvdMsg);
                    }

                    // TODO we want to continue to receive all the time
                    //Stop listening so we don't have e thread using up CPU-cycles when we're not expecting data
//                    stopThreads();
                } catch (IOException e) {
                    sendMyBroadcast(MESH_DISCON_ERR, e.getMessage());
                    Disconnect(); //Gets stuck in a loop if we don't call this on error!
                }
            }
            receiveThreadRunning = false;
            Log.d(DBG_TAG, "Receiving stopped");
        }

    }

    static class SendRunnable implements Runnable {

        byte[] data;
        private OutputStream out;
        private boolean hasMessage = false;

        SendRunnable(Socket server) {
            try {
                this.out = server.getOutputStream();
            } catch (IOException e) {
                Log.d(DBG_TAG, "Sending failed: " + e.getMessage());
            }
        }

        /**
         * Send data as bytes to the server
         * @param bytes Data to send
         */
        void Send(byte[] bytes) {
            this.data = bytes;
            this.hasMessage = true;
        }

        @Override
        public void run() {
            Log.d(DBG_TAG, "Sending started");
//            while (!Thread.currentThread().isInterrupted() && isConnected()) {
                if (this.hasMessage) {
                    startTime = System.currentTimeMillis();
                    try {
                        //Send the data
                        this.out.write(data, 0, data.length);
                        this.out.write(0);
                        //Flush the stream to be sure all bytes has been written out
                        this.out.flush();
                    } catch (IOException e) {
                        Log.d(DBG_TAG, "Sending failed: " + e.getMessage());
                    }
                    this.hasMessage = false;
                    this.data =  null;
                    long time = System.currentTimeMillis() - startTime;
                    Log.i(DBG_TAG, "Command has been sent! Current duration: " + time + "ms");
//                    if (!receiveThreadRunning)
//                        startReceiving(); //Start the receiving thread if it's not already running
                }
//            }
            Log.i(DBG_TAG, "Sending stopped");
        }
    }

    static class ConnectRunnable implements Runnable {

        public void run() {
            try {

                Log.d(DBG_TAG, "C: Connecting...");
                InetAddress serverAddr = InetAddress.getByName(severIp);
                startTime = System.currentTimeMillis();
                //Create a new instance of Socket
                connectionSocket = new Socket();

                //Start connecting to the server with 5000ms timeout
                //This will block the thread until a connection is established
                connectionSocket.connect(new InetSocketAddress(serverAddr, serverPort), 5000);

                long time = System.currentTimeMillis() - startTime;
                Log.d(DBG_TAG, "Connected! Current duration: " + time + "ms");

                MeshHandler.sendNodeSyncRequest();
                startReceiving();

            } catch (Exception e) {
                Log.d(DBG_TAG, "Connecting failed: " + e.getMessage());
            }
            Log.i(DBG_TAG, "Connection thread finished");
        }
    }

    /**
     * Send received message to all listing threads
     *
     * @param msgReceived
     *            Received data or error message
     */
    private static void sendMyBroadcast(String action, String msgReceived) {
        /* Intent for activity internal broadcast messages */
        Intent broadCastIntent = new Intent();
        broadCastIntent.setAction(action);
        broadCastIntent.putExtra("msg", msgReceived);
//        LocalBroadcastManager.getInstance(appContext).sendBroadcast(broadCastIntent);
        appContext.sendBroadcast(broadCastIntent);
    }
}