package veg.mediacapture.sdk.test;

import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.Log;
import veg.mediacapture.sdk.MediaCaptureConfig;
import veg.mediacapture.sdk.MediaCaptureConfig.CaptureVideoResolution;
import veg.mediacapture.sdk.test.demo.R;


@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity {
	static final public String TAG = "SettingsActivity";

	public static SettingsActivity sThis;
	SharedPreferences settings=null;
	
	CheckBoxPreference record_audio_enable = null;
	CheckBoxPreference record_capture_screen = null;
	ListPreference record_audioBitrate = null;
	CheckBoxPreference record_enable = null;
	ListPreference  server_mode = null;
	CheckBoxPreference secvideo_enable = null;

	Preference streaming_login = null;
	Preference streaming_urlch = null;
	Preference streaming_port = null;
	

	private void set_changed_flag(String key){
		if(settings == null)
			return;

		Editor ed = settings.edit();
		ed.putInt(key, 1);
		ed.apply();
	}

	void set_server_changed(){
		set_changed_flag("server_changed");
	}

	void set_record_changed(){
		set_changed_flag("record_changed");
	}
	void set_record_changed_audio(){
		set_changed_flag("record_changed_audio");
	}

	void set_storage_changed(){
		set_changed_flag("storage_changed");
	}

	void set_streaming_changed(){
		set_changed_flag("streaming_changed");
	}

	void update_record_audio_enable(boolean isenabled){
		record_audio_enable.setSummary(isenabled?"audio ON":"audio OFF");
		record_audioBitrate.setEnabled(isenabled);
		//record_audioChannels.setEnabled(isenabled);
		//record_audioSampling.setEnabled(isenabled);
	}

	void update_record_capture_screen(boolean isenabled){
		record_capture_screen.setSummary(isenabled?"Screen Capture":"Camera Capture");
	}


	void update_streaming_rtmp_enable(boolean isenabled){
		streaming_login.setEnabled(isenabled);
		streaming_urlch.setEnabled(isenabled);
		streaming_port.setEnabled(!isenabled);
	}

	public boolean check_alert_reset()
	{
		String message = "Are you sure reset settings to defaults?";
		new AlertDialog.Builder(this)
		.setTitle("Warning")
		.setMessage(message)
		.setPositiveButton("Yes", new DialogInterface.OnClickListener() 
		{
			public void onClick(DialogInterface dialog, int which) 
			{
				Log.i(TAG, "YES");
					
				on_reset_settings();
			}
		})
		.setNegativeButton("No", new DialogInterface.OnClickListener() 
		{
			public void onClick(DialogInterface dialog, int which) 
			{
				Log.i(TAG, "NO");
			}
		}).show();

		return true;
	}

	public void on_reset_settings()
	{
		Log.v(TAG, "=on_reset_settings");
		
		String androidID = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
		Editor ed = settings.edit();

		ed.clear();
		String surlr = settings.getString("urlrtmp", "rtmp://54.173.34.172:1937/publish_demo/");
		//surlr += androidID;
		ed.putString("urlrtmp", surlr);
		ed.putString("urlch", "0");
		ed.putString("login", "demo");
		ed.putString("urlport", "5540");
		//ed.putString("urlpasscode", "0000");
		ed.putInt("intro", 0);
		ed.apply();
		
		set_record_changed();
		set_record_changed_audio();
		set_server_changed();
		set_storage_changed();
		set_streaming_changed();

		Intent intent = new Intent(this.getBaseContext(),SettingsActivity.class);
		startActivityForResult(intent, 0);
		
		finish();
	}

	private static int parseIntOrDefault(String s, int def){
		try{
			return Integer.parseInt(s);
		}catch(NumberFormatException e){
			e.printStackTrace();
			return def;
		}
	}

	public String getServerType(String str){
		int val = parseIntOrDefault(str, 1);

		String s="";
		switch( val ){
		case 1:
			s = "Publish RTMP";
			break;
		case 2:
			s = "RTSP";
			break;
		}
		return s;
	}


	public String getSRes(String str){
		int val = parseIntOrDefault(str, 1280);

		String s="";
		switch( val ){
		case 3840:
			s="3840x2160";
			break;
		case 1920:
			s = "1920x1080";
			break;
		case 1280:
			s = "1280x720";
			break;
		case 721:
			s = "720x576";
			break;
		case 720:
			s = "720x480";
			break;
		case 640:
			s = "640x480";
			break;
		case 352:
			s = "352x288";
			break;
		case 320:
			s = "320x240";
			break;
		case 176:
			s = "176x144";
			break;
		}
		return s;
	}

	public String getSBitrateMode(String str){
		int val = parseIntOrDefault(str, -1);

		String s="ABR";
		switch(val){
			case 1:
				s="VBR";
				break;
			case 2:
				s="CBR";
				break;
			case 0:
				s="CQ";
				break;
		}
		return s;
	}

	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		
		sThis = this;

		addPreferencesFromResource(R.xml.preferences);

		settings = PreferenceManager.getDefaultSharedPreferences(this);
		//Editor ed = settings.edit();
		
		PackageInfo pinfo;
		try 
		{
			pinfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			
			String AppVersion = pinfo.versionName;	
			String AppName = (String) getText(R.string.app_name);
			setTitle("Settings "+ AppName + " " + AppVersion);
		} 
		catch (NameNotFoundException e1) 
		{
			e1.printStackTrace();
		}


		final ListPreference streaming_serverType = (ListPreference) findPreference("serverType");
		streaming_serverType.setSummary(getServerType(streaming_serverType.getValue()));


		final ListPreference record_bitrateMode = (ListPreference) findPreference("bitrateMode");
		record_bitrateMode.setSummary( getSBitrateMode(record_bitrateMode.getValue()));

		final ListPreference record_HRVbitrate = (ListPreference) findPreference("HRVbitrate");
		String sHRVbitrate = settings.getString("HRVbitrate", "700");
		record_HRVbitrate.setSummary(sHRVbitrate+" kbps");

		final ListPreference record_videoRes = (ListPreference) findPreference("videoRes");
		CharSequence[] resE = record_videoRes.getEntries();
		CharSequence[] resEV = record_videoRes.getEntryValues();
		
		List<CharSequence> resNewE = new ArrayList<CharSequence>();
		List<CharSequence> resNewEV = new ArrayList<CharSequence>();
		if( MainActivity.sMainActivity != null && MainActivity.sMainActivity.mConfig != null){
			MediaCaptureConfig config = MainActivity.sMainActivity.mConfig;
			List<MediaCaptureConfig.CaptureVideoResolution> resList = config.getVideoSupportedRes();
			for(MediaCaptureConfig.CaptureVideoResolution vr : resList){
				String svr = ""+((config.getVideoOrientation() == 0)?config.getVideoWidth(vr):config.getVideoHeight(vr));
				if(config.getVideoResolution() == MediaCaptureConfig.CaptureVideoResolution.VR_720x576){
					svr = "721";
				}
				int i;
				for(i = 0; i<resEV.length; i++){
					if(resEV[i].toString().equals(svr)){
						resNewE.add(resE[i]);
						resNewEV.add(resEV[i]);
						break;
					}
				}
			}
		}
		record_videoRes.setEntries(resNewE.toArray(new CharSequence[resNewE.size()]));
		record_videoRes.setEntryValues(resNewEV.toArray(new CharSequence[resNewEV.size()]));
		record_videoRes.setSummary(getSRes(record_videoRes.getValue()));
		
		record_enable = (CheckBoxPreference) findPreference("record_enable");

		secvideo_enable = (CheckBoxPreference) findPreference("secvideo_enable");
		
		record_audio_enable = (CheckBoxPreference) findPreference("audio_enable");
		boolean isaudio = settings.getBoolean("audio_enable", true);
		
		record_audioBitrate = (ListPreference) findPreference("audio_bitrate");
		String sAudioBitrate = settings.getString("audio_bitrate", "64");
		record_audioBitrate.setSummary(sAudioBitrate+" kbps");

		update_record_audio_enable(isaudio);

		record_capture_screen = (CheckBoxPreference) findPreference("capture_screen");
		boolean iscaptureScreen = settings.getBoolean("capture_screen", false);

		update_record_capture_screen(iscaptureScreen);


		streaming_login = findPreference("login");
		String slogin = settings.getString("login", "demo");
		streaming_login.setSummary(slogin);

		streaming_urlch = findPreference("urlch");
		String surlch = settings.getString("urlch", "0");
		streaming_urlch.setSummary(surlch);

		streaming_port = findPreference("urlport");
		String surlport = settings.getString("urlport", "5540");
		streaming_port.setSummary(surlport);
		
		boolean is_rtmp = !getServerType(streaming_serverType.getValue()).equals("RTSP");//settings.getBoolean("enable_rtmp", true);
		update_streaming_rtmp_enable(is_rtmp);
		
		final PreferenceScreen common_header = (PreferenceScreen) findPreference("common_set");
		common_header.bind(getListView());
		
		common_header.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference preference) {
				Log.i(TAG, "=on reset Pressed");
				check_alert_reset();
				return true;
			}
		});

		streaming_serverType.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				String serverType = getServerType(newValue.toString());
				preference.setSummary(serverType);
				update_streaming_rtmp_enable(!serverType.equals("RTSP"));
				set_server_changed();
				return true;
			}
		});

		
		streaming_login.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary((String)newValue);
				set_streaming_changed();
				return true;
			}
		});
		streaming_urlch.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary((String)newValue);
				set_streaming_changed();
				return true;
			}
		});
		streaming_port.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary((String)newValue);
				set_streaming_changed();
				return true;
			}
		});
		record_enable.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				set_record_changed();
				return true;
			}
		});
		secvideo_enable.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				set_record_changed();
				return true;
			}
		});
		record_audio_enable.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				Boolean isenable = (Boolean)newValue;
				update_record_audio_enable(isenable.booleanValue());
				set_record_changed();
				return true;
			}
		});

		record_capture_screen.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				Boolean isenable = (Boolean)newValue;
				update_record_capture_screen(isenable.booleanValue());
				set_record_changed();
				return true;
			}
		});

		record_audioBitrate.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

			@Override
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary((String)newValue+" kbps");
				set_record_changed();
				set_record_changed_audio();
				return true;
			}
		});

		record_bitrateMode.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary(getSBitrateMode((String)newValue));
				set_record_changed();
				return true;
			}
		});
		
		record_HRVbitrate.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary((String)newValue+" kbps");
				set_record_changed();
				return true;
			}
		});
		
		record_videoRes.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				preference.setSummary(getSRes(newValue.toString()));
				set_record_changed();
				return true;
			}
		});

		
	}

	@Override
	public void onPause() {
		Log.i(TAG, "=onPause");
		super.onPause();
	}

	@Override
	public void onResume() {
		Log.i(TAG, "=onResume");
		super.onResume();
	}

	
	@Override
	public void onBackPressed() {
		Log.i(TAG, "=onBackPressed");

		finish();
		
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
	}

}
