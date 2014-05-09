package org.pf;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;


import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.*;
import java.util.Set;
import java.io.IOException;
import java.util.Iterator;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.lang.Runnable;


public class PortForward extends Service implements Runnable {

  private static final String TAG = "Port Forward";

  private int localPort;
  private int remotePort;
  private String remoteHost;
  private boolean running = false;

  private int lastUp = -1;
  private int lastDown = -1;
  private int bUp = 0;
  private int bDown = 0;
  LocalBroadcastManager bm;
  private Thread t;

  ServerSocketChannel serverSocketChannel = null;

  public Handler sendBroadcastHandler  = new Handler() {
    public void handleMessage(Message msg) {
      Intent i = new Intent().setAction(MainActivity.USAGE_UPDATE);
      i.putExtra("bUp", bUp);
      i.putExtra("bDown", bDown);
      bm.sendBroadcast(i);
    }
  };

  public Handler sendDeathHandler  = new Handler() {
    public void handleMessage(Message msg) {
      Bundle b = msg.getData();
      String causeOfDeath = b.getString("causeOfDeath", "unknown");

      Notification note = new Notification.Builder(PortForward.this)
        .setContentTitle("TCP forwarding thread dead")
        .setContentText("Cause of death: " + causeOfDeath)
        .setSmallIcon(R.drawable.ic_launcher).build();
      NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

      mNotificationManager.notify(1338, note);
    }
  };


  private void updateCounts() {
    updateCounts(false);
  }

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

    if (running){
      updateCounts(true);
      return START_REDELIVER_INTENT;
    }
    running = true;

    bm = LocalBroadcastManager.getInstance(this);
    localPort = intent.getIntExtra("localPort", -1);
    remotePort = intent.getIntExtra("remotePort", -1);
    remoteHost = intent.getStringExtra("remoteHost");


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

  private void reportException(Exception e){
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    Message msg = sendDeathHandler.obtainMessage();
    Bundle b = msg.getData();
    b.putString("causeOfDeath", sw.toString());
    sendDeathHandler.sendMessage(msg);
  }

  private void finish(Selector s){
    try {
      serverSocketChannel.close();
    } catch (IOException e){ }

    Set<SelectionKey> selectedKeys = s.keys();
    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
    while (keyIterator.hasNext()) {
      closeConnectionForKey(keyIterator.next());
    }
  }


  private void closeChannel(SocketChannel c){
    if (c != null){
      try {
        if (c != null){
          c.close();
        }
      } catch (IOException e){ }

    }
  }

  private void closeConnectionForKey(SelectionKey key){
    PFGroup g = null;
    try { 
      g = (PFGroup)key.attachment();
    } catch (Exception e){
      return;
    }
    if (g == null) {return;}
    closeChannel(g.iChannel);
    closeChannel(g.oChannel);
  }

  @Override
  public void run() {
    String causeOfDeath = null;
    System.out.println("Server online");
    Selector selector = null;

    try {
      selector = Selector.open();
      serverSocketChannel = ServerSocketChannel.open();
      serverSocketChannel.socket().bind(new InetSocketAddress(localPort));
      serverSocketChannel.configureBlocking(false);
      serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    } catch (IOException e) {
      reportException(e);
      return;
    }


    System.out.println("Server socket bound.");

    while (true) {
      System.out.println("Waiting for conn");

      updateCounts();
      int readyChannels = 0;

      try {
        readyChannels = selector.select();
      } catch (IOException e) {
        reportException(e);
        continue;
      }

      if (Thread.currentThread().isInterrupted()) {
        finish(selector);
        return;
      }

      if (readyChannels == 0) {
        continue;
      }

      Set<SelectionKey> selectedKeys = selector.selectedKeys();
      Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

      while (keyIterator.hasNext()) {
        //System.out.println("Ready on " + readyChannels);

        SelectionKey key = keyIterator.next();
        keyIterator.remove();

        if (!key.isValid()) {
          continue;
        } else if (key.isAcceptable()) {
          System.out.println("Acceptable!");

          PFGroup g = new PFGroup();

          // 512KB buffers
          g.iBuffer = ByteBuffer.allocate(512000);
          g.oBuffer = ByteBuffer.allocate(512000);
          boolean iConnected = false;

          try {
            g.iChannel = serverSocketChannel.accept();
            iConnected = g.iChannel.finishConnect();
            if (iConnected){
              g.sidesOn++;
            }
            g.iChannel.configureBlocking(false);
            g.iKey = g.iChannel.register(selector, 0, g);

            g.oChannel = SocketChannel.open();
            g.oChannel.configureBlocking(false);
            g.oChannel.connect(new InetSocketAddress(remoteHost, remotePort));
            g.oKey =g.oChannel.register(selector, SelectionKey.OP_CONNECT, g);
          } catch (IOException e) {
            continue;
          }


        } else if (key.isConnectable()) {
          System.out.println("connectable!");
          try {
            SocketChannel c = (SocketChannel) key.channel();

            PFGroup g = (PFGroup)key.attachment();
            if (!c.finishConnect()) {
              System.out.println("couldn't finish conencting");
              continue;
            }
            g.sidesOn++;
            System.out.println("Initilized the bidirectional forward");
            key.interestOps(SelectionKey.OP_READ);
            g.iKey = g.iChannel.register(selector, SelectionKey.OP_READ, g);
          } catch (IOException e) {
            continue;
          }

        } else if (key.isReadable()) {

          try {

            ByteBuffer b = null;
            SocketChannel from = null;
            SocketChannel to = null;
            PFGroup g = (PFGroup)key.attachment();
            String label = null;
            if (key.channel() == g.iChannel){
              from = g.iChannel;
              to = g.oChannel;
              b = g.iBuffer;
              label = "incoming";
            } else if (key.channel() == g.oChannel){
              from = g.oChannel;
              to = g.iChannel;
              b = g.oBuffer;
              label = "outgoing";
            } 

            int i = from.read(b);
            b.flip();
            while (b.hasRemaining()) {
              int bytes = to.write(b);
              if(label.equals("incoming")){
                bUp += bytes;
              } else {
                bDown += bytes;
              }
            }
            b.clear();
            if (i == -1) {
              key.cancel();
              g.sidesOn--;
              if (g.sidesOn == 0){
                System.out.println("Done, closing keys");
                closeConnectionForKey(key);
              }
            }
          } catch (IOException e){
            Log.d(TAG, "closing connection for key.");
            closeConnectionForKey(key);
          }
        }
      }
    }
  }

  public class PFGroup {
    public ByteBuffer iBuffer;
    public ByteBuffer oBuffer;
    public SocketChannel iChannel;
    public SocketChannel oChannel;
    public int sidesOn = 0;
    SelectionKey iKey;
    SelectionKey oKey;
  }

}
