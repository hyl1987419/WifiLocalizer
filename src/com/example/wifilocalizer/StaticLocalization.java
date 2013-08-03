package com.example.wifilocalizer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StrictMode;
import android.support.v4.app.NavUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;




public class StaticLocalization extends Activity implements SensorEventListener {

	static byte[] image;
	
	private long timestamp;
	
	
	TextView textView;
	
	
	private WifiManager wifi;
	private Camera camera;
	private CameraPreview mPreview;
	private SensorManager mSensorManager;
	private Sensor linearAccelerometer, rotationSensor;
	
	
	private float[] linearAcceleration = new float[4];
	private float[] globalDeltaRotationVector = {0, 0, 0, 0};
	private float[] globalAcceleration = {0, 0, 0, 0};
	
	private float[] rotationMatrix = new float[16];
	private float[] newRotationVector = new float[3], oldRotationVector = new float[3], deltaRotationVector = new float[4];
	
	private float[] cameraPose = new float[3], orientation = new float[3];
	
	private boolean DEVELOPER_MODE = false, mAppStopped;
	
	private BroadcastReceiver receiver;

	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		if (DEVELOPER_MODE) {
	         StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
	                 .detectDiskReads()
	                 .detectDiskWrites()
	                 .detectNetwork()   // or .detectAll() for all detectable problems
	                 .penaltyLog()
	                 .build());
	         StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
	                 .detectLeakedSqlLiteObjects()
	                 .detectLeakedClosableObjects()
	                 .penaltyLog()
	                 .penaltyDeath()
	                 .build());
	     }
		
		
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_static_localization);
        
        
        
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        
        linearAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        rotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        
		
		IntentFilter i = new IntentFilter();
		i.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
		
		registerReceiver(receiver = new BroadcastReceiver(){

		
		
		
		@Override
		public void onReceive(Context c, Intent i){
		// Code to execute when SCAN_RESULTS_AVAILABLE_ACTION event occurs
			
			JSONObject query, queryCore;
			JSONObject pose, returnParams;
			
			HashMap<String,Integer> macRSSI = new HashMap<String,Integer>();
			HashMap<String, JSONObject> postedData = new HashMap<String, JSONObject>();
			HashMap<String, Float> poseMap = new HashMap<String, Float>();
			HashMap<String, Boolean> returnMap = new HashMap<String, Boolean>();
			HashMap<String, Object> paramsMap = new HashMap<String, Object>();
  			
			
			List<ScanResult> scanResults = wifi.getScanResults();
			
			if(scanResults == null || scanResults.isEmpty()) {
				textView.setText("No wifi network detected!");
				setContentView(textView);
			}
			else {
			
				Collections.sort(scanResults, new ScanComparable());
			
				for (ScanResult scan : scanResults) {
					//int linearLevel = WifiManager.calculateSignalLevel(scan.level, 99);
					
					//macRSSI.put(scan.BSSID.toString(), scan.level*100-linearLevel);
					macRSSI.put(scan.BSSID.toString(), scan.level);
				}
				
				
				
				queryCore = new JSONObject(macRSSI);
				postedData.put("fingerprint_data", queryCore);
				query = new JSONObject(postedData);
				
				
				
				poseMap.put("latitude", 0f);
				poseMap.put("longitude", 0f);
				poseMap.put("altitude", 0f);
				poseMap.put("yaw", cameraPose[0]);
				poseMap.put("pitch", cameraPose[1]);
				poseMap.put("roll", cameraPose[2]);
				poseMap.put("ambiguity_meters", (float)1.0e+126);
				pose = new JSONObject(poseMap);

				returnMap.put("statistics", true);
				returnMap.put("image_data", false);
				returnMap.put("estimated_client_pose", true);
				returnMap.put("pose_visualization_only", false);
				returnParams = new JSONObject(returnMap);
				
				paramsMap.put("method", "client_query");
				paramsMap.put("user", "chaoran");
				paramsMap.put("database", "corydb");
				paramsMap.put("deadline_seconds", 15.0);
				paramsMap.put("disable_gpu", false);
				paramsMap.put("perfmode", "fast");
				paramsMap.put("pose", pose);
				paramsMap.put("return", returnParams);
				
				
				HashMap<String, Object> imageQueryMap = new HashMap<String, Object>();
				imageQueryMap.put("data", image);
				imageQueryMap.put("params", paramsMap);
				JSONObject imageQuery = new JSONObject(imageQueryMap);
							
				new WifiQueryTask("http://django.kung-fu.org:8001/wifi/submit_fingerprint", query).execute(c);
				new ImageQueryTask("https://ahvaz.eecs.berkeley.edu/", imageQuery).execute(c);
				Log.d("ImageQuery", "Image query sent to server!");
			}
			}
		}
	,i);    				
			
		// Show the Up button in the action bar.
		setupActionBar();
	}

	
	
	
	
	/**
	 * Set up the {@link android.app.ActionBar}, if the API is available.
	 */
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private void setupActionBar() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			getActionBar().setDisplayHomeAsUpEnabled(true);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.static_localization, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			// This ID represents the Home or Up button. In the case of this
			// activity, the Up button is shown. Use NavUtils to allow users
			// to navigate up one level in the application structure. For
			// more details, see the Navigation pattern on Android Design:
			//
			// http://developer.android.com/design/patterns/navigation.html#up-vs-back
			//
			NavUtils.navigateUpFromSameTask(this);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	
	
	Handler handler=new Handler();
	@Override
    protected void onResume() {
        super.onResume();
        
        camera = getCameraInstance();
		mPreview = new CameraPreview(this, camera);
        FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview_static);
        preview.addView(mPreview);
        
        preview.setKeepScreenOn(true);
        
        
        mAppStopped = false; 
        
        mSensorManager.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
        
        
        
        wifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        
		if (wifi.isWifiEnabled()) {
			
			final Runnable r = new Runnable()
	        {
	            public void run() 
	            {
	            	if(!mAppStopped) {
	            		
	            		camera.startPreview();
	            		scan();
	            		timestamp = System.currentTimeMillis();
	            		camera.takePicture(null, null, mPicture);
	            		camera.startPreview();
	            		handler.postDelayed(this, 10000);
	                }
	                
	            }
	        };

			handler.postDelayed(r, 10);
	        
			
			
		}
		else {
			textView = new TextView(this);
			textView.setTextSize(17);
			textView.setText("Wi-Fi is currently turned off. To find out your location in the building, turn Wi-Fi on and then try again.");
			setContentView(textView);
		}
		
		
		
	}
	
	
	
	
	public void scan() {
		
		if (wifi.startScan()) { }
		else {
			Log.d("SCANNING_FAILURE","Wi-Fi is turned off!");
		}
	}
	
	
	
	
	
	
	
	private class ImageQueryTask extends AsyncTask<Context, Void, Void> 
    {
        private String url_str;
        private JSONObject json;

        public ImageQueryTask(String url, JSONObject json)
        {
            this.url_str = url;
            this.json = json;
        }
        
        
        protected Void doInBackground(Context... c) {
			byte[] data = json.toString().getBytes();
		
			try {
				
				URL url = new URL(url_str);
		
				
				HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
				   try {
					 urlConnection.setReadTimeout( 10000 /*milliseconds*/ );
					 urlConnection.setConnectTimeout( 15000 /* milliseconds */ );
					   
					 urlConnection.setDoInput(true);
				     urlConnection.setDoOutput(true);
				     urlConnection.setFixedLengthStreamingMode(data.length);
				     
				     urlConnection.setRequestProperty("content-type","application/json; charset=utf-8");
				     urlConnection.setRequestProperty("Accept", "application/json");
				     urlConnection.setRequestMethod("POST");

				     urlConnection.connect();
				     OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
			
				     out.write(data);
				     Log.d("DATA", json.toString());
				     out.flush();
				     
				     
				     // Parse response sent from image localization server
				     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
				     BufferedReader br = new BufferedReader(new InputStreamReader(in));
				     
				     
				     StringBuilder builder = new StringBuilder();
				     String line = null;
				     for (; (line = br.readLine()) != null;) {
				         builder.append(line).append("\n");
				     }     
				     
				     
				     JSONTokener tokener = new JSONTokener(builder.toString());
				     JSONObject finalResult = new JSONObject(tokener);
				     
				   
				     Log.d("ImageResponse", finalResult.getString("local_x") + " " + finalResult.getString("local_y"));
				     Log.d("ImageResponse", (Double.valueOf(finalResult.getDouble("overall_confidence")).toString()));
				          
				     
				     in.close();
				     out.close();
				   } catch (JSONException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				    finally {
				     urlConnection.disconnect();
				    }
				  
				Log.d("URL_CONNECTION","SUCCESS!");
			}
			catch (MalformedURLException e){ }
			catch (IOException e) {Log.d("URL_EXCEPTION","FAILURE!"+ e.getMessage()); }
			
			return null;
        }
    }
	
	
	
	
	
	@SuppressWarnings("unused")
	private class CentralQueryTask extends AsyncTask<Context, Void, Void> 
    {
        private String url_str;
        private JSONObject json;

        public CentralQueryTask(String url, JSONObject json)
        {
            this.url_str = url;
            this.json = json;
        }
        
        
        protected Void doInBackground(Context... c) {
			byte[] data = json.toString().getBytes();
		
			try {
				
				URL url = new URL(url_str);
		
				
				HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
				   try {
					 urlConnection.setReadTimeout( 10000 /*milliseconds*/ );
					 urlConnection.setConnectTimeout( 15000 /* milliseconds */ );
					   
					 urlConnection.setDoInput(true);
				     urlConnection.setDoOutput(true);
				     urlConnection.setFixedLengthStreamingMode(data.length);
				     
				     urlConnection.setRequestProperty("content-type","application/json; charset=utf-8");
				     urlConnection.setRequestProperty("Accept", "application/json");
				     urlConnection.setRequestMethod("POST");

				     urlConnection.connect();
				     OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
			
				     out.write(data);
				     Log.d("DATA", json.toString());
				     out.flush();
				     
				     
				     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
				     
				     in.close();
				     out.close();
				   }
				    finally {
				     urlConnection.disconnect();
				    }
				  
				Log.d("URL_CONNECTION","SUCCESS!");
			}
			catch (MalformedURLException e){ }
			catch (IOException e) {Log.d("URL_EXCEPTION","FAILURE!"+ e.getMessage()); }
			
			return null;
        }
    }
	

	

	
	
	private class WifiQueryTask extends AsyncTask<Context, Void, Void> 
    {
        private String url_str;
        private JSONObject json;

        public WifiQueryTask(String url, JSONObject json)
        {
            this.url_str = url;
            this.json = json;
        }
        
        
        protected Void doInBackground(Context... c) {
			byte[] data = json.toString().getBytes();
		
			try {
				
				URL url = new URL(url_str);
		
				
				HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
				   try {
					 urlConnection.setReadTimeout( 10000 /*milliseconds*/ );
					 urlConnection.setConnectTimeout( 15000 /* milliseconds */ );
					   
					 urlConnection.setDoInput(true);
				     urlConnection.setDoOutput(true);
				     urlConnection.setFixedLengthStreamingMode(data.length);
				     
				     urlConnection.setRequestProperty("content-type","application/json; charset=utf-8");
				     urlConnection.setRequestProperty("Accept", "application/json");
				     urlConnection.setRequestMethod("POST");

				     urlConnection.connect();
				     OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
			
				     out.write(data);
				     Log.d("DATA", json.toString());
				     out.flush();
				     
				     
				     // Parse response sent from WiFi localization server
				     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
				     BufferedReader br = new BufferedReader(new InputStreamReader(in));
				     
				     
				     StringBuilder builder = new StringBuilder();
				     String line = null;
				     for (; (line = br.readLine()) != null;) {
				         builder.append(line).append("\n");
				     }     
				     
				     
				     JSONTokener tokener = new JSONTokener(builder.toString());
				     JSONObject finalResult = new JSONObject(tokener);
				     
				   
				     Log.d("status", (Integer.valueOf(finalResult.getInt("status")).toString()));
				     Log.d("location", finalResult.getString("location"));
				     Log.d("confidence", (Double.valueOf(finalResult.getDouble("confidence")).toString()));
				     
				     
				     
				     in.close();
				     out.close();
				   } catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				    finally {
				     urlConnection.disconnect();
				    }
				  
				Log.d("URL_CONNECTION","SUCCESS!");
			}
			catch (MalformedURLException e){ }
			catch (IOException e) {Log.d("URL_EXCEPTION","FAILURE!"+ e.getMessage()); }
			
			return null;
        }
    }
	

	
	
	
	protected void onPause() {
		mAppStopped = true;
		camera.stopPreview();
		camera.release();
		
		super.onPause();	
		
		mSensorManager.unregisterListener(this, linearAccelerometer);
		mSensorManager.unregisterListener(this, rotationSensor);
		unregisterReceiver(receiver);
	}
	
	
	
	
	
	
	
	
	
	/*******    Camera Code     ********/	
	
	/** A safe way to get an instance of the Camera object. */
	public static Camera getCameraInstance(){
	    Camera c = null;
	    try {
	        c = Camera.open(); // attempt to get a Camera instance
	    }
	    catch (Exception e){
	        // Camera is not available (in use or does not exist)
	    }
	    return c; // returns null if camera is unavailable
	}
	
	
	
	/** A basic Camera preview class */
	public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
	    private SurfaceHolder mHolder;
	    private Camera mCamera;

	    public CameraPreview(Context context, Camera camera) {
	        super(context);
	        mCamera = camera;

	        // Install a SurfaceHolder.Callback so we get notified when the
	        // underlying surface is created and destroyed.
	        mHolder = getHolder();
	        mHolder.addCallback(this);
	    }

	    public void surfaceCreated(SurfaceHolder holder) {
	        // The Surface has been created, now tell the camera where to draw the preview.
	    	
	        try {
	            mCamera.setPreviewDisplay(holder);
	            mCamera.startPreview();
	        } catch (IOException e) {
	            Log.d("TAG1: ", "Error setting camera preview: " + e.getMessage());
	        }
	    }

	    public void surfaceDestroyed(SurfaceHolder holder) {
	        // empty. Take care of releasing the Camera preview in your activity.
	    }

	    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
	        // If your preview can change or rotate, take care of those events here.
	        // Make sure to stop the preview before resizing or reformatting it.

	        if (mHolder.getSurface() == null){
	          // preview surface does not exist
	          return;
	        }

	        // stop preview before making changes
	        try {
	            mCamera.stopPreview();
	        } catch (Exception e){
	          // ignore: tried to stop a non-existent preview
	        }

	        // set preview size and make any resize, rotate or
	        // reformatting changes here
	        Camera.Parameters parameters = mCamera.getParameters();
	        List<Size> sizes = parameters.getSupportedPictureSizes();
	        /*
	        for (Size s: sizes) {
	        	Log.d("size", s.width + " " + s.height);
	        }
	       	*/
	        parameters.setPictureSize(sizes.get(5).width, sizes.get(5).height);
	        mCamera.setParameters(parameters);
	        

	        // start preview with new settings
	        try {
	            mCamera.setPreviewDisplay(mHolder);
	            mCamera.startPreview();

	        } catch (Exception e){
	            Log.d("TAG2: ", "Error starting camera preview: " + e.getMessage());
	        }
	    }
	}
	
	
	
	
	private PictureCallback mPicture = new PictureCallback() {

	    @Override
	    public void onPictureTaken(byte[] data, Camera camera) {

	    	//getOutputMediaFile();
	    
	    	
	        File pictureFile = getOutputMediaFile();
	        if (pictureFile == null){
	            Log.d("TAG3: ", "Error creating media file, check storage permissions!");
	            return;
	        }

	        try {
	            FileOutputStream fos = new FileOutputStream(pictureFile);
	            fos.write(data);
	            fos.close();
	        } catch (FileNotFoundException e) {
	            Log.d("TAG4: ", "File not found: " + e.getMessage());
	        } catch (IOException e) {
	            Log.d("TAG5: ", "Error accessing file: " + e.getMessage());
	        }
	        
	        
	        FileInputStream fis = null;
		    try {
		        fis = new FileInputStream(pictureFile);
		    } 
		    catch (FileNotFoundException e) {
		        e.printStackTrace();
		    }

		    Bitmap bm = BitmapFactory.decodeStream(fis);
		    ByteArrayOutputStream baos = new ByteArrayOutputStream();  
		    bm.compress(Bitmap.CompressFormat.JPEG, 100 , baos);    
		    image = baos.toByteArray(); 
		    //encImage = Base64.encodeToString(b, Base64.DEFAULT);
		    
		    //pictureFile.delete();
	    }
	};
	
	
	
	
	/** Create a File for saving an image or video */
	@SuppressLint("SimpleDateFormat")
	private static File getOutputMediaFile(){
	    // To be safe, you should check that the SDCard is mounted
	    // using Environment.getExternalStorageState() before doing this.

	    File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
	              Environment.DIRECTORY_PICTURES), "LocalizingImages");
	    // This location works best if you want the created images to be shared
	    // between applications and persist after your app has been uninstalled.

	    // Create the storage directory if it does not exist
	    if (! mediaStorageDir.exists()){
	        if (! mediaStorageDir.mkdirs()){
	            Log.d("LocalizingImages", "failed to create directory");
	            return null;
	        }
	    }

	    // Create a media file name
	    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
	    File mediaFile = new File(mediaStorageDir.getPath() + File.separator +
	        "IMG_"+ timeStamp + ".jpg");
	    
	    return mediaFile;
	}
	
	
	
	
	
	
	
	
	
	
	/*****    Sensor Code    ****/
	
	public final void onAccuracyChanged(Sensor sensor, int accuracy) {
	    // Do something here if sensor accuracy changes.
	  }
	
	
	
	public void onSensorChanged(SensorEvent event){
		int type = event.sensor.getType();
		
		
		if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
			linearAcceleration[0] = event.values[0];
			linearAcceleration[1] = event.values[1];
			linearAcceleration[2] = event.values[2];
			linearAcceleration[3] = 0;
		}	
	   
		
	   else if (type == Sensor.TYPE_ROTATION_VECTOR) {
		   long currTimestamp = System.currentTimeMillis(); 
		   orientation = new float[3];
		   
		   newRotationVector = event.values.clone();
		   if (oldRotationVector != null) {
			   deltaRotationVector[0] = newRotationVector[0] - oldRotationVector[0];
			   deltaRotationVector[1] = newRotationVector[1] - oldRotationVector[1];
			   deltaRotationVector[2] = newRotationVector[2] - oldRotationVector[2];
			   deltaRotationVector[3] = 0;
			   //Log.d("DELTA_ROTATION", deltaRotationVector[0] + " " + deltaRotationVector[1] + " " + deltaRotationVector[2]);
		   }
		   SensorManager.getRotationMatrixFromVector(rotationMatrix, newRotationVector);
		   
		   
		   
		   if (currTimestamp >= timestamp) {
			   SensorManager.getOrientation(rotationMatrix, orientation);
			   cameraPose[2] = (float) Math.round(Math.toDegrees(orientation[2])*100)/100; // Row
			   cameraPose[1] = (float) Math.round(Math.toDegrees(orientation[1])*100)/100; // Pitch
			   cameraPose[0] = (float) Math.round(Math.toDegrees(orientation[0])*100)/100; // Yaw
			   //Log.d("ORIENTATION", cameraPose[0]+" "+cameraPose[1]+" "+cameraPose[2]);
			   Log.d("YAW", cameraPose[2] + "");
		   }
		   
		   
		   
		   for (int i=0;i<4;i++) {
			   globalDeltaRotationVector[i] = 0;
			   globalAcceleration[i] = 0;
           }
           
           for (int i=0;i<4;i++) {
        	   for (int j=0;j<4;j++) {
        		   globalDeltaRotationVector[i] += rotationMatrix[(i+1)*(j+1)-1] * deltaRotationVector[i];
        		   globalAcceleration[i] += rotationMatrix[(i+1)*(j+1)-1] * linearAcceleration[i];
        	   }
           }
           //Log.d("GLOBAL_ACCELERATION", globalAcceleration[0] + " " + globalAcceleration[1] + " " + globalAcceleration[2]);
           oldRotationVector[0] = newRotationVector[0];
           oldRotationVector[1] = newRotationVector[1];
           oldRotationVector[2] = newRotationVector[2];
	   }
	 }
}
