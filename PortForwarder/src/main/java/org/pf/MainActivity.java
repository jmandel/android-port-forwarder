/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pf;

import android.os.Message;
import android.os.Handler;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.EditText;
import android.content.SharedPreferences;
import com.example.android.common.logger.Log;
import com.example.android.common.logger.LogFragment;
import com.example.android.common.logger.LogWrapper;
import com.example.android.common.logger.MessageOnlyLogFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Forward a single TCP port to an outside host + port. Only supports one socket
 * at a time. Source based on com.example.android.networkconnect.
 */
public class MainActivity extends FragmentActivity {

  public static final String TAG = "Network Connect";
  public static final String PREFS_NAME = "PortForwardingSettings";

  Menu menu;
  boolean forwarding = true;
  String titleForwarding = "Forwarding. Tap to stop.";
  String titlePaused = "Paused. Tap to begin.";
  SimpleTextFragment introFragment;
  // Reference to the fragment showing events, so we can clear it with a button
  // as necessary.
  private LogFragment mLogFragment;
  Thread t;

  TextView localPortView;
  TextView remotePortView;
  TextView remoteHostView;

  int remotePort;
  int localPort;
  String remoteHost;

  Handler updateDataCount = new Handler(){
    public void handleMessage(Message msg) {
      Bundle b;
      b=msg.getData();

      introFragment.getTextView().setText(
          String.format("Up: %.2fkb\nDown: %.2fkb", 
            b.getInt("bUp",-1000)/1000.0,
            b.getInt("bDown",-1000)/1000.0));
    }
  };

  private void killThread(){
    if (t != null){
      t.interrupt();
    }
  }

  private void readTextViews(){
    remotePort = Integer.parseInt(remotePortView.getText().toString());
    localPort = Integer.parseInt(localPortView.getText().toString());
    remoteHost = remoteHostView.getText().toString();
  }

  private void newThread(){
    if (t != null) {
      try {
        t.join();
      } catch  (InterruptedException e){
        System.out.println("couldn't join killed thread");
        System.exit(1);
      }
    }
    
    readTextViews();
    PortForward f = new PortForward();
    f.remotePort = remotePort;
    f.remoteHost = remoteHost;
    f.localPort = localPort;
    f.updateDataCount = updateDataCount;
    t = new Thread(f);
    t.start();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.sample_main);

    // Initialize text fragment that displays intro text.
    introFragment = (SimpleTextFragment)
      getSupportFragmentManager().findFragmentById(R.id.intro_fragment);
    introFragment.setText(R.string.welcome_message);
    introFragment.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.0f);
    introFragment.getTextView().setText("Waiting to begin.");
    initializeLogging();

    SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
    remotePort = settings.getInt("remotePort", -1);
    localPort = settings.getInt("localPort", -1);
    remoteHost = settings.getString("remoteHost", "");

    localPortView = (TextView) findViewById(R.id.local_port);
    remotePortView = (TextView) findViewById(R.id.remote_port);
    remoteHostView = (TextView) findViewById(R.id.remote_host);

    if (localPort>0){
      localPortView.setText(String.valueOf(localPort));
    }
    if (remotePort>0){
      remotePortView.setText(String.valueOf(remotePort));
    }
    if (!remoteHost.equals("")){
      remoteHostView.setText(remoteHost);
      newThread();
    } else {
      forwarding = false; 
    }

  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    this.menu = menu;
    setForwardingString(menu.findItem(R.id.fetch_action));
    return true;
  }

  private void setForwardingString(MenuItem i){
    if (forwarding) {
      i.setTitle(titleForwarding);
    }  else {
      i.setTitle(titlePaused);
    }
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      // When the user clicks FETCH, fetch the first 500 characters of
      // raw HTML from www.google.com.
      case R.id.fetch_action:
        this.forwarding = !this.forwarding;
        if (this.forwarding) {
          newThread();
        } else {
          killThread();
        }
        setForwardingString(item);
        return true;
      case R.id.clear_action:
        mLogFragment.getLogView().setText("");
        readTextViews();
	SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putInt("localPort", localPort);
        editor.putInt("remotePort", remotePort);
        editor.putString("remoteHost", remoteHost);
        editor.commit();
        return true;
    }
    return false;
  }

  /** Create a chain of targets that will receive log data */
  public void initializeLogging() {

    // Using Log, front-end to the logging chain, emulates
    // android.util.log method signatures.

    // Wraps Android's native log framework
    LogWrapper logWrapper = new LogWrapper();
    Log.setLogNode(logWrapper);

    // A filter that strips out everything except the message text.
    MessageOnlyLogFilter msgFilter = new MessageOnlyLogFilter();
    logWrapper.setNext(msgFilter);

    // On screen logging via a fragment with a TextView.
    mLogFragment =
      (LogFragment) getSupportFragmentManager().findFragmentById(R.id.log_fragment);
    msgFilter.setNext(mLogFragment.getLogView());
  }
}
