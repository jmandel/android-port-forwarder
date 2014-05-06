package org.pf;

import android.os.Handler;
import android.os.Bundle;
import android.os.Message;

import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.*;
import java.util.Set;
import java.io.IOException;
import java.util.Iterator;
import java.lang.Runnable;


public class PortForward implements Runnable {

  // 512KB buffers
  private ByteBuffer outgoingBuffer = ByteBuffer.allocate(512000);
  private ByteBuffer incomingBuffer = ByteBuffer.allocate(512000);
  public int localPort;
  public int remotePort;
  public String remoteHost;
  public Handler updateDataCount;

  int lastUp = -200000;
  int lastDown = -200000;
  int bUp = 0;
  int bDown = 0;


  public static void main(String args[]){
    PortForward f = new PortForward();
    f.localPort = 9999;
    f.remotePort = 5000;
    f.remoteHost = "localhost";
    Thread t = new Thread(f);
    t.start();
  }

  private void updateCounts(){
    if (bUp - lastUp < 10000 && bDown-lastDown < 10000)  {
        return;
    }

    lastUp = bUp;
    lastDown = bDown;

    Bundle b = new Bundle(4);
    b.putInt("bUp", bUp);
    b.putInt("bDown", bDown);

    Message msg = updateDataCount.obtainMessage();
    msg.setData(b);
    updateDataCount.sendMessage(msg);
  }

  @Override
  public void run () {
    updateCounts();
    try {
      System.out.println( "Server online");
      while(true) {
        SocketChannel incomingChannel;
        SocketChannel outgoingChannel;

        ServerSocketChannel serverSocketChannel;
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.socket().bind(new InetSocketAddress(localPort));

        Selector selector = Selector.open();
        SelectionKey incomingKey = null;
        SelectionKey outgoingKey = null;

        incomingChannel = serverSocketChannel.accept();
        outgoingChannel = SocketChannel.open();

        outgoingChannel.configureBlocking(false);
        outgoingKey = outgoingChannel.register(selector, SelectionKey.OP_CONNECT, outgoingBuffer);
        outgoingChannel.connect(new InetSocketAddress(remoteHost, remotePort));

        incomingChannel.configureBlocking(false);

        System.out.println( "Waiting for conn");

        int scount = 0;
        while(true) {

          boolean connOver = false;
          scount++;
          int readyChannels = selector.select();
	  if (Thread.currentThread().isInterrupted()){ 
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

          while(keyIterator.hasNext()) {
            System.out.println(  "Ready on " + readyChannels);

            SelectionKey key = keyIterator.next();
            keyIterator.remove();

            if (!key.isValid()) {
              continue;
            }

            else if (key.isConnectable()){
              System.out.println( "connectable!");
              SocketChannel c = (SocketChannel)key.channel();
              if (!c.finishConnect()) {
                System.out.println( "coudnl't finish conencting");
                continue;
              }
              incomingChannel.register(selector, SelectionKey.OP_READ, incomingBuffer);
              outgoingChannel.register(selector, SelectionKey.OP_READ, outgoingBuffer);
            }

            else if (key.isReadable()) {
              int i = ((SocketChannel)key.channel()).read((ByteBuffer)key.attachment());
              if (key.channel() == outgoingChannel) {
                outgoingBuffer.flip();
                while(outgoingBuffer.hasRemaining()){
                  bDown += incomingChannel.write(outgoingBuffer);
                }
                outgoingBuffer.clear();
              }
              if (key.channel() == incomingChannel) {
                incomingBuffer.flip();
                while(incomingBuffer.hasRemaining()){
                  bUp += outgoingChannel.write(incomingBuffer);
                }
                incomingBuffer.clear();
              }

              if (i == -1) {
                System.out.println( "Done, closing keys");
                incomingChannel.close();
                outgoingChannel.close();
                connOver = true;
              }
            } 

          }

          updateCounts();

          if (connOver) {
            serverSocketChannel.close();
            break;
          }

        }

        System.out.println("Done");
      }
    } catch (IOException e){

      System.out.println("ioexception");
      e.printStackTrace();

    }
  }
}
