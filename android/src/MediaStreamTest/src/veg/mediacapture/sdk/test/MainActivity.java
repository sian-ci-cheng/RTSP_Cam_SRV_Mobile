/*
 *
  * Copyright (c) 2011-2018 VXG Inc.
 *
 */


package veg.mediacapture.sdk.test;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.TextureView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import veg.mediacapture.sdk.test.demo.R;
import veg.mediacapture.sdk.test.rtspserver.CameraStreamer;


public class MainActivity extends Activity
{
    private static final String TAG 	 = "MediaCaptureTest";

	private static final boolean USE_PORTRAIT_MODE = false;
	private static final int CAMERA_PERMISSION_REQUEST = 1001;

    private SharedPreferences settings=null;

    private CameraStreamer				streamer = null;
    private boolean						misStreaming = false;

	private ImageView led;
    private TextView 					captureStatusText = null;
    private TextView 					captureStatusText2 = null;
    private TextView					captureStatusStat = null;
    private ImageButton					mbuttonRec = null;
    private ImageButton 				mbuttonSettings = null;
    private TextureView					captureView = null;

    private MulticastLock multicastLock = null;
    private PowerManager.WakeLock mWakeLock;

	public static MainActivity sMainActivity;

	private int mVideoWidth = 1280;
	private int mVideoHeight = 720;
	private int mVideoBitrateKbps = 700;
	private int mRtspPort = 5540;
	private boolean mAudioEnabled = true;
	private int mAudioBitrateKbps = 64;
	private boolean mRecordEnabled = false;
	private boolean mSecondaryEnabled = true;
	private boolean mGpsEnabled = true;
	private long mGpsUpdateIntervalMs = 1000;

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		setTitle(R.string.app_name);

		super.onCreate(savedInstanceState);

		sMainActivity = this;

		// Prevents the phone to go to sleep mode
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "veg.mediacapture.sdk.test.mediastream");


		WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		multicastLock = wifi.createMulticastLock("multicastLock");
		multicastLock.setReferenceCounted(true);
		multicastLock.acquire();

		getWindow().requestFeature(Window.FEATURE_PROGRESS);
		getWindow().setFeatureInt(Window.FEATURE_PROGRESS, Window.PROGRESS_VISIBILITY_ON);

		settings = PreferenceManager.getDefaultSharedPreferences(this);

		setContentView(R.layout.main);

		if(USE_PORTRAIT_MODE){
			//set portrait mode
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		}else{
			//set landscape mode
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		}

		ActionBar bar = getActionBar();
		if(bar != null)
			bar.hide();

		led = (ImageView)findViewById(R.id.led);
		led.setImageResource(R.drawable.led_green);

		captureStatusText = (TextView)findViewById(R.id.statusRec);
		captureStatusStat = (TextView)findViewById(R.id.statusStat);
		captureStatusStat.setText("");

		captureStatusText2 = (TextView)findViewById(R.id.statusRec2);
		captureStatusText2.setText("");

		captureView = (TextureView)findViewById(R.id.captureView);

		load_config();

		streamer = new CameraStreamer(this);
		streamer.statusListener = this::onStreamerStatus;

		java.util.List<String> missingPermissions = new java.util.ArrayList<>();
		if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			missingPermissions.add(Manifest.permission.CAMERA);
		}
		if (mAudioEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			missingPermissions.add(Manifest.permission.RECORD_AUDIO);
		}
		if (mGpsEnabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
		}
		if (!missingPermissions.isEmpty()) {
			requestPermissions(missingPermissions.toArray(new String[0]), CAMERA_PERMISSION_REQUEST);
		}

        mbuttonSettings = (ImageButton) findViewById(R.id.imageButtonMenu);
		mbuttonSettings.setSoundEffectsEnabled(false);
		mbuttonSettings.setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						Intent intent;
						if(isRec()){
							Toast.makeText(MainActivity.sMainActivity,"Press 'Stop Streaming' button  first", Toast.LENGTH_LONG).show();
						}else{
							// Starts SettingsActivity where user can change the streaming quality
							intent = new Intent(MainActivity.sMainActivity.getBaseContext(),SettingsActivity.class);
							startActivity(intent);
							finish();
						}
					}
				}
			);

		mbuttonRec = (ImageButton) findViewById(R.id.button_capture);
		mbuttonRec.setSoundEffectsEnabled(false);
		mbuttonRec.setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if(streamer == null)
							return;
						if( !isRec() ){
							if (checkSelfPermission(Manifest.permission.CAMERA)
									!= PackageManager.PERMISSION_GRANTED) {
								Toast.makeText(MainActivity.sMainActivity,"Camera permission required", Toast.LENGTH_LONG).show();
								return;
							}

							captureStatusText.setText("Starting...");
							captureStatusStat.setText("");
							led.setImageResource(R.drawable.led_red);

							streamer.start(mVideoWidth, mVideoHeight, mVideoBitrateKbps, 30, mRtspPort,
									mAudioEnabled, mAudioBitrateKbps, mRecordEnabled, mSecondaryEnabled,
									mGpsEnabled, mGpsUpdateIntervalMs, captureView);
							misStreaming = true;
							mbuttonRec.setImageResource(R.drawable.ic_stop);
						}else{
							streamer.stop();
							misStreaming = false;
							led.setImageResource(R.drawable.led_green);
							captureStatusText.setText("");
							captureStatusStat.setText("");
							captureStatusText2.setText("");
							mbuttonRec.setImageResource(R.drawable.ic_fiber_manual_record_red);
						}
					}
				}
			);
	}

	public boolean isRec(){
		return misStreaming;
	}

	private void onStreamerStatus(String status){
		Log.i(TAG, "=streamer status="+status);
		captureStatusText.setText(status);
	}

	private static int parseIntOrDefault(String s, int def){
		try{
			return Integer.parseInt(s);
		}catch(NumberFormatException e){
			e.printStackTrace();
			return def;
		}
	}

	void load_config(){
		String sres = settings.getString("videoRes", "1280");
		mVideoWidth = parseIntOrDefault(sres, 1280);
		mVideoHeight = mVideoWidth * 9 / 16;

		String svbitrate = settings.getString("HRVbitrate", "700");
		mVideoBitrateKbps = parseIntOrDefault(svbitrate, 700);

		String surlport = settings.getString("urlport", "5540");
		mRtspPort = parseIntOrDefault(surlport, 5540);

		mAudioEnabled = settings.getBoolean("audio_enable", true);
		String sabitrate = settings.getString("audio_bitrate", "64");
		mAudioBitrateKbps = parseIntOrDefault(sabitrate, 64);

		mRecordEnabled = settings.getBoolean("record_enable", false);
		mSecondaryEnabled = settings.getBoolean("secvideo_enable", true);

		mGpsEnabled = settings.getBoolean("gps_enable", true);
		String sgpsinterval = settings.getString("gps_update_interval_ms", "1000");
		mGpsUpdateIntervalMs = parseIntOrDefault(sgpsinterval, 1000);

		// serverType / bitrateMode are read by SettingsActivity but not yet consumed here --
		// bitrate-mode control is deferred to a later phase.
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == CAMERA_PERMISSION_REQUEST) {
			for (int i = 0; i < permissions.length; i++) {
				if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
					String label;
					if (Manifest.permission.CAMERA.equals(permissions[i])) {
						label = "Camera";
					} else if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
						label = "Location";
					} else {
						label = "Microphone";
					}
					Toast.makeText(this, label + " permission denied", Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	protected void onPause()
	{
		Log.e(TAG, "onPause()");
		super.onPause();
	}

	@Override
  	protected void onResume()
	{
		Log.e(TAG, "onResume()");
		super.onResume();
  	}

  	@Override
	protected void onStart()
  	{
      	Log.e(TAG, "onStart()");
		super.onStart();
		sMainActivity = this;

		// Lock screen
		mWakeLock.acquire();
	}

  	@Override
	protected void onStop()
  	{
  		Log.e(TAG, "onStop()");
		super.onStop();

		if (streamer != null && misStreaming) {
			streamer.stop();
			misStreaming = false;
		}

		// A WakeLock should only be released when isHeld() is true !
		if (mWakeLock.isHeld()) mWakeLock.release();
	}

    @Override
    public void onBackPressed()
    {
		super.onBackPressed();
    }

  	@Override
  	protected void onDestroy()
  	{
  		Log.e(TAG, "onDestroy()");

		if (streamer != null && misStreaming) {
			streamer.stop();
			misStreaming = false;
		}

		System.gc();

		if (multicastLock != null) {
		    multicastLock.release();
		    multicastLock = null;
		}
		super.onDestroy();
   	}

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
		super.onCreateOptionsMenu(menu);

		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
    }

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		Intent intent;
		int itemId = item.getItemId();

		if (itemId == R.id.menu_settings) {
			if(isRec()){
				Toast.makeText(this,"Press 'Stop Streaming' button  first", Toast.LENGTH_LONG).show();
			}else{
				intent = new Intent(this.getBaseContext(),SettingsActivity.class);
				startActivity(intent);
				finish();
			}
			return true;
		} else if (itemId == R.id.menu_exit) {
			if(isRec()){
				Toast.makeText(this,"Press 'Stop Streaming' button  first", Toast.LENGTH_LONG).show();
			}else{
				finish();
			}
			return true;
		} else {
			return super.onOptionsItemSelected(item);
		}
	}
}
