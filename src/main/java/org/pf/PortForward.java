package org.pf;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;


import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.*;
import java.util.Set;
import java.io.IOException;
import java.util.Iterator;
import java.lang.Runnable;


public class PortForward extends Service implements Runnable {

    private static final String TAG = "Port Forward";


    // 512KB buffers
    private final ByteBuffer outgoingBuffer = ByteBuffer.allocate(512000);
    private final ByteBuffer incomingBuffer = ByteBuffer.allocate(512000);
    private int localPort;
    private int remotePort;
    private String remoteHost;
    private boolean running = false;

    private int lastUp = -1;
    private int lastDown = -1;
    private int bUp = 0;
    private int bDown = 0;

    private Thread t;

    public Handler sendBroadcastHandler  = new Handler() {
        public void handleMessage(Message msg) {
            Intent i = new Intent().setAction(MainActivity.USAGE_UPDATE);
            i.putExtra("bUp", bUp);
            i.putExtra("bDown", bDown);
            sendBroadcast(i);
        }
    };


    private void updateCounts(boolean force) {
        if (!force && (bUp - lastUp < 10000 && bDown - lastDown < 10000)) {
            return;
        }

        lastUp = bUp;
        lastDown = bDown;

        Message msg = sendBroadcastHandler.obtainMessage();
        sendBroadcastHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");

        if (t != null) {
            t.interrupt();
            try {
                t.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "couldn't join forwarder-thread");
                System.exit(1);
            }
        }
        Log.d(TAG, "Killed it");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStart");


        if (running) return START_REDELIVER_INTENT;
        running = true;

        this.localPort = intent.getIntExtra("localPort", -1);
        this.remotePort = intent.getIntExtra("remotePort", -1);
        this.remoteHost = intent.getStringExtra("remoteHost");


        t = new Thread(this);
        t.start();

        Log.d(TAG, "launching a thread");


        Notification note = new Notification.Builder(this)
                .setContentTitle("Forwarding TCP Port")
                .setContentText(String.format(
                        "localhost:%s -> %s:%s", localPort, remoteHost, remotePort))
                .setSmallIcon(R.drawable.ic_launcher)
                .build();

        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);
        note.contentIntent = pi;
        note.flags |= Notification.FLAG_NO_CLEAR;
        startForeground(1337, note);
        Log.d(TAG, "doing startForeground");

        updateCounts(true);

        return START_REDELIVER_INTENT;
    }

    @Override
    public void run() {
        try {
            System.out.println("Server online");
            while (true) {
                SocketChannel incomingChannel;
                SocketChannel outgoingChannel;

                ServerSocketChannel serverSocketChannel;
                serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.socket().bind(new InetSocketAddress(localPort));

                Selector selector = Selector.open();

                incomingChannel = serverSocketChannel.accept();
                outgoingChannel = SocketChannel.open();

                outgoingChannel.configureBlocking(false);
                SelectionKey outgoingKey = outgoingChannel.register(selector, SelectionKey.OP_CONNECT, outgoingBuffer);
                outgoingChannel.connect(new InetSocketAddress(remoteHost, remotePort));

                incomingChannel.configureBlocking(false);

                System.out.println("Waiting for conn");

                int scount = 0;
                while (true) {

                    boolean connOver = false;
                    scount++;
                    int readyChannels = selector.select();
                    if (Thread.currentThread().isInterrupted()) {
                        incomingChannel.close();
                        outgoingChannel.close();
                        serverSocketChannel.close();
                        return;
                    }

                    if (readyChannels == 0) {
                        continue;
                    }

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        System.out.println("Ready on " + readyChannels);

                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (!key.isValid()) {
                            continue;
                        } else if (key.isConnectable()) {
                            System.out.println("connectable!");
                            SocketChannel c = (SocketChannel) key.channel();
                            if (!c.finishConnect()) {
                                System.out.println("coudnl't finish conencting");
                                continue;
                            }
                            incomingChannel.register(selector, SelectionKey.OP_READ, incomingBuffer);
                            outgoingChannel.register(selector, SelectionKey.OP_READ, outgoingBuffer);
                        } else if (key.isReadable()) {
                            int i = ((SocketChannel) key.channel()).read((ByteBuffer) key.attachment());
                            if (key.channel() == outgoingChannel) {
                                outgoingBuffer.flip();
                                while (outgoingBuffer.hasRemaining()) {
                                    bDown += incomingChannel.write(outgoingBuffer);
                                }
                                outgoingBuffer.clear();
                            }
                            if (key.channel() == incomingChannel) {
                                incomingBuffer.flip();
                                while (incomingBuffer.hasRemaining()) {
                                    bUp += outgoingChannel.write(incomingBuffer);
                                }
                                incomingBuffer.clear();
                            }

                            if (i == -1) {
                                System.out.println("Done, closing keys");
                                incomingChannel.close();
                                outgoingChannel.close();
                                connOver = true;
                            }
                        }

                    }

                    updateCounts(false);

                    if (connOver) {
                        serverSocketChannel.close();
                        break;
                    }

                }

                System.out.println("Done");
            }
        } catch (IOException e) {

            System.out.println("ioexception");
            e.printStackTrace();

        }
    }
}
