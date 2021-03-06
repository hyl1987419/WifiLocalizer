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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.apache.http.HttpVersion;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreProtocolPNames;
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
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
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
import android.support.v4.app.NavUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import cz.muni.fi.sandbox.dsp.filters.ContinuousConvolution;
import cz.muni.fi.sandbox.dsp.filters.FrequencyCounter;
import cz.muni.fi.sandbox.dsp.filters.SinXPiWindow;
import cz.muni.fi.sandbox.service.stepdetector.MovingAverageStepDetector;
import cz.muni.fi.sandbox.service.stepdetector.MovingAverageStepDetector.MovingAverageStepDetectorState;


class ScanComparable implements Comparator<ScanResult> {

    @Override
    public int compare(ScanResult s1, ScanResult s2) {
        return (s1.level>s2.level ? -1 : (s1.level==s2.level ? 0 : 1));
    }
}


public class LocalizePhone extends Activity implements SensorEventListener {

	private static final String WIFI_URL = "http://shiraz.eecs.berkeley.edu:8001/wifi/submit_fingerprint";
	private static final String IMAGE_URL = "http://quebec.eecs.berkeley.edu:8001/";
	private static final String CENTRAL_DYNAMIC_URL = "http://136.152.38.222:8000/central/receive_hdg_and_dis";


	File pictureFile;
	String encImage;

	private long timestamp;

	TextView textView;
	MapView mapView;

	private WifiManager wifi;
	private Camera camera;
	private CameraPreview mPreview;
	private SensorManager mSensorManager;
	private Sensor linearAccelerometer, rotationSensor, accelerometer;

	/*
	private ArrayList<Float> la1 = new ArrayList<Float>();
	private ArrayList<Float> la2 = new ArrayList<Float>();
	private ArrayList<Float> la3 = new ArrayList<Float>();*/
	private float[] rotationMatrix = new float[16];
	private float[] newRotationVector = new float[3], oldRotationVector = new float[3], deltaRotationVector = new float[4];

	private float[] cameraPose = new float[3], orientation = new float[3];

	private boolean mAppStopped;

	private BroadcastReceiver receiver;

	private double[] currentLocation = {22.0*13.0, 48.5*13.0};

	private MovingAverageStepDetector mStepDetector;
	private ContinuousConvolution mCC;
	private FrequencyCounter freqCounter;

	double movingAverage1 = MovingAverageStepDetector.MA1_WINDOW;
	double movingAverage2 = MovingAverageStepDetector.MA2_WINDOW;

	double lowPowerCutoff = MovingAverageStepDetector.LOW_POWER_CUTOFF_VALUE;
	double highPowerCutoff = MovingAverageStepDetector.HIGH_POWER_CUTOFF_VALUE;

	private int mMASize = 20;
	@SuppressWarnings("unused")
	private float mSpeed = 1f;
	float mConvolution, mLastConvolution;

	double stepLength = -10.0;

	JSONObject wifiResponse, imageResponse, JSONParams;
	static byte[] image;

	private boolean mTouched;
	private ArrayList<Float> Xrecord = new ArrayList<Float>();
	private ArrayList<Float> Yrecord = new ArrayList<Float>();




	private class MapView extends View {	
		private ArrayList<String> walls = new ArrayList<String>();
		private Paint wallPaint = new Paint();
		private Paint circlePaint = new Paint();
		private Paint recordPaint = new Paint();

		public MapView(Context context) {
			super(context);

			wallPaint.setColor(Color.BLACK);
			wallPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
			circlePaint.setColor(Color.RED);
			circlePaint.setFlags(Paint.ANTI_ALIAS_FLAG);
			recordPaint.setColor(Color.BLUE);
			recordPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

			// Load floor map from Assets folder
			AssetManager assetManager = getAssets();
			InputStream input;
	        try {
	        	input = assetManager.open("cory2p_rotated.edge");   
	        	BufferedReader reader = new BufferedReader(new InputStreamReader(input));
	        	String line= reader.readLine();
	        	while (line != null) {
	        		walls.add(line);
	        		line = reader.readLine();
	        	}       
	        } catch (IOException e) {
	            // TODO Auto-generated catch block
	            e.printStackTrace();
	        }
		}

		protected void onDraw(Canvas canvas) {
			for (String line: walls) {
				String[] splited = line.split("\\s+");
	        	float x1 = (Float.parseFloat(splited[0])+20)*13f;
	        	float y1 = -(Float.parseFloat(splited[1])-49)*13f;
	        	float x2 = (Float.parseFloat(splited[2])+20)*13f;
	        	float y2 = -(Float.parseFloat(splited[3])-49)*13f;
				canvas.drawLine(x1, y1, x2, y2, wallPaint);
			}
			for (int i=0; i<Xrecord.size(); i++) {
				canvas.drawCircle(Xrecord.get(i).floatValue(), Yrecord.get(i).floatValue(), 8f, recordPaint);
			}
			if (mTouched) {
				circlePaint.setColor(Color.BLUE);
				canvas.drawCircle((float)currentLocation[0], (float)currentLocation[1], 8f, circlePaint);
				Xrecord.add((float) currentLocation[0]);
				Yrecord.add((float) currentLocation[1]);
				circlePaint.setColor(Color.RED);
				mTouched = false;
			}
			else {
				canvas.drawCircle((float)currentLocation[0], (float)currentLocation[1], 8f, circlePaint);
			}
		}

		public boolean onTouchEvent(MotionEvent event) {

			switch (event.getAction()) {
			case MotionEvent.ACTION_UP:
				mTouched = true;
				Log.d("TOUCH", "touch event detected");
				break;
			}
			return true;
		}
	}


	@Override
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
        
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        
        linearAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        rotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        mStepDetector = new MovingAverageStepDetector(movingAverage1, movingAverage2, lowPowerCutoff, highPowerCutoff);

		mCC = new ContinuousConvolution(new SinXPiWindow(mMASize));
		freqCounter = new FrequencyCounter(20);
        

		IntentFilter i = new IntentFilter();
		i.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

		registerReceiver(receiver = new BroadcastReceiver(){


		@Override
		public void onReceive(Context c, Intent i){

			JSONObject query, queryCore;
			HashMap<String,Integer> macRSSI = new HashMap<String,Integer>();
			HashMap<String, JSONObject> postedData = new HashMap<String, JSONObject>();
			List<ScanResult> scanResults = wifi.getScanResults();

			if(scanResults == null || scanResults.isEmpty()) {
				textView.setText("No wifi network detected!");
				setContentView(textView);
			}
			else {	
				Collections.sort(scanResults, new ScanComparable());

				for (ScanResult scan : scanResults) {
					macRSSI.put(scan.BSSID.toString(), scan.level);
				}

				//macRSSI.put("cluster_id", 1);
				macRSSI.put("cutoff_dB", -65);			

				queryCore = new JSONObject(macRSSI);
				postedData.put("fingerprint_data", queryCore);
				query = new JSONObject(postedData);	

				new WifiQueryTask(WIFI_URL, query).execute(c);
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
		getMenuInflater().inflate(R.menu.localize_phone, menu);
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
      
        setContentView(R.layout.activity_localize_phone);
        
		mPreview = new CameraPreview(this, camera);
        FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview);
        preview.addView(mPreview);
        
        mapView = new MapView(this);
        FrameLayout mapElem = (FrameLayout) findViewById(R.id.map);
        mapElem.addView(mapView);
        mapElem.setKeepScreenOn(true);
        

        mAppStopped = false; 
        
        mSensorManager.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        
        wifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        
        Runnable r1 = null;
		if (wifi.isWifiEnabled()) {
			r1 = new Runnable()
	        {
	            public void run() 
	            {
	            	if(!mAppStopped) {		
	            		scan();
	            		handler.postDelayed(this, 4500);
	                }
	            }
	        };
			handler.postDelayed(r1, 50);
		
		 Runnable r2 = new Runnable() {

		        @Override
		        public void run() {
		        	if(!mAppStopped) {
		        		camera.startPreview();
		        		timestamp = System.currentTimeMillis();
		        		camera.autoFocus(new AFCallback());
		        		camera.startPreview(); 

		        		if (pictureFile != null) {
		        			JSONParams = new JSONObject();
		    				try {
		    					JSONParams.put("method", "client_query");
		    					JSONParams.put("user", "cyu");
		    					JSONParams.put("database", "0815_db");
		    					JSONParams.put("deadline_seconds", 20.0);
		    					JSONObject JSONPose = new JSONObject();
		    						JSONPose.put("latitude", 0);
		    						JSONPose.put("longitude", 0);
		    						JSONPose.put("yaw", cameraPose[0]);
		    						JSONPose.put("pitch", cameraPose[1]);
		    						JSONPose.put("roll", cameraPose[2]);
		    						JSONPose.put("ambiguity_meters", 1e12);
		    					JSONParams.put("pose",JSONPose);
		    					JSONObject JSONReturn = new JSONObject();
		    					    JSONReturn.put("statistics", true);
		    						JSONReturn.put("estimated_client_pose", true);
		    						JSONReturn.put("image_data", false);
		    						JSONReturn.put("image_only", false);
		    						JSONReturn.put("pose_visualization_only", false);
		    					JSONParams.put("return", JSONReturn);
		    				}
		    				catch (JSONException e) {
		    					// TODO Auto-generated catch block
		    					e.printStackTrace();
		    				}
		        			
							new ImageQueryTask(IMAGE_URL).execute(getApplicationContext());
						}
            			handler.postDelayed(this, 6000);
		        	}
		        }
		    }; 
		    handler.postDelayed(r2, 2500); 
		    
		}
		else {
			textView = new TextView(this);
			textView.setTextSize(17);
			textView.setText("Wi-Fi is currently turned off. To find out your location in the building, turn Wi-Fi on and then try again.");
			setContentView(textView);
		}
	}




	private void scan() {
		if (wifi.startScan()) { }
		else {
			Log.d("SCANNING_FAILURE","Wi-Fi is turned off!");
		}
	}
	
	
	private class AFCallback implements AutoFocusCallback {
		@Override
		public void onAutoFocus(boolean success, Camera arg1) {
			if (success) {
				arg1.takePicture(null, null, mPicture);
				Log.d("Picture", "Picture taken!");
			}
			else {
				Log.d("AUTO-FOCUS", "Auto Focus failed");
			}
		}
	}



	private class ImageQueryTask extends AsyncTask<Context, Void, Boolean> 
    {
        private String url_str;
        
        private long oldTime, newTime;

        public ImageQueryTask(String url) {
            this.url_str = url;
        }
        
        
        protected Boolean doInBackground(Context... c) {
			try {
				URL url = new URL(url_str);
				HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

				oldTime = System.currentTimeMillis()/1000;				   
			   
				DefaultHttpClient client = new DefaultHttpClient();
				client.getParams().setParameter(CoreProtocolPNames.PROTOCOL_VERSION, HttpVersion.HTTP_1_1);
				HttpPost httppost = new HttpPost(url.toString());
				MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);					

				entity.addPart("params", new StringBody(JSONParams.toString()));
				entity.addPart("data", new FileBody(pictureFile));

				Log.d("DATA", new FileBody(pictureFile).toString());
				Log.d("Timing", "Finish uploading image");

				httppost.setEntity(entity);	

				ResponseHandler<String> res = new BasicResponseHandler();
				String response = client.execute(httppost, res);
				newTime = System.currentTimeMillis()/1000;
				
				
				try {
					JSONObject JSONResponse = new JSONObject(response);
					Log.d("ImageResponse", JSONResponse.toString());

					JSONObject imageResponse = new JSONObject();
					imageResponse.put("image_location", JSONResponse.get("local_x") + " " + JSONResponse.get("local_y"));
					imageResponse.put("image_confidence", JSONResponse.get("overall_confidence"));

					new CentralQueryTask(CENTRAL_DYNAMIC_URL, imageResponse).execute(c);
					urlConnection.disconnect();
					return true;
				}
				catch (JSONException e) {
					e.printStackTrace();
					urlConnection.disconnect();
					return false;
				} 			
			}
			catch (MalformedURLException e){ }
			catch (IOException e) {Log.d("URL_EXCEPTION","IMAGE FAILURE!"+ e.getMessage()); }

			return false;
        }
        
        protected void onPostExecute(Boolean result) {
        	CharSequence text;
        	long diff = newTime - oldTime;
        	if (!result) {
        		text = "Image caused error on the server! Time elapsed: " + diff + " secs!";
        	}
        	else {
        	    text = "Received image response in " + diff + " secs!";
        	}
        	Toast toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT);
        	toast.show();
        }
    }






	private class CentralQueryTask extends AsyncTask<Context, Void, Void> {
        private String url_str;
        private JSONObject json;

        public CentralQueryTask(String url, JSONObject json) {
            this.url_str = url;
            this.json = json;
        }    
        
        protected Void doInBackground(Context... c) {   	
			byte[] data = json.toString().getBytes();
			try {
				URL url = new URL(url_str);

				HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
				   try {
					 urlConnection.setReadTimeout(10000);
					 urlConnection.setConnectTimeout(35000);

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

				     // Parse response sent from central server
				     InputStream in = new BufferedInputStream(urlConnection.getInputStream());
				     BufferedReader br = new BufferedReader(new InputStreamReader(in));	     

				     StringBuilder builder = new StringBuilder();
				     String line = null;
				     for (; (line = br.readLine()) != null;) {
				         builder.append(line).append("\n");
				     }       

				     JSONTokener tokener = new JSONTokener(builder.toString());
				     JSONObject finalResult = new JSONObject(tokener);

				     currentLocation[0] = (Double.valueOf(finalResult.getDouble("x")) + 20.0) * 13.0;
				     currentLocation[1] = -(Double.valueOf(finalResult.getDouble("y")) - 49.0) * 13.0;
				     mapView.postInvalidate();

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
			catch (MalformedURLException e) {Log.d("MalformedURLException", e.getMessage()); }
			catch (IOException e) {Log.d("URL_EXCEPTION","FAILURE!"+ e.getMessage()); }		
			return null;
        }
    }





	private class WifiQueryTask extends AsyncTask<Context, Void, Void> {
        private String url_str;
        private JSONObject json;

        public WifiQueryTask(String url, JSONObject json) {
            this.url_str = url;
            this.json = json;
        }
        
        protected Void doInBackground(Context... c) {	
			byte[] data = json.toString().getBytes();
			try {
				URL url = new URL(url_str);
				HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
				   try {
					 urlConnection.setReadTimeout( 30000 /*milliseconds*/ );
					 urlConnection.setConnectTimeout( 30000 /* milliseconds */ );

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
				     BufferedReader br = new BufferedReader(new InputStreamReader(in));

				     StringBuilder builder = new StringBuilder();
				     String line = null;
				     for (; (line = br.readLine()) != null;) {
				         builder.append(line).append("\n");
				     }  
				     //Log.d("Timing", "Time4: Received location from WiFi server!");  

				     JSONTokener tokener = new JSONTokener(builder.toString());
				     JSONObject finalResult = new JSONObject(tokener);

				     HashMap<String, String> wifiResponseMap = new HashMap<String, String>();
				     wifiResponseMap.put("wifi_status", (Integer.valueOf(finalResult.getInt("status")).toString()));
				     wifiResponseMap.put("wifi_location", finalResult.getString("location"));
				     wifiResponseMap.put("wifi_confidence", (Double.valueOf(finalResult.getDouble("confidence")).toString()));
				     wifiResponse = new JSONObject(wifiResponseMap);

				     Log.d("Timing", "Time5: WiFi location sent to central server for processing!");
				     new CentralQueryTask(CENTRAL_DYNAMIC_URL, wifiResponse).execute(c);

				     in.close();
				     out.close();

				   } catch (JSONException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				    finally {
				     //urlConnection.setRequestProperty("Connection", "close");
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
		super.onPause();
		
		mAppStopped = true;
		camera.stopPreview();
		camera.release();	

		mSensorManager.unregisterListener(this, linearAccelerometer);
		mSensorManager.unregisterListener(this, rotationSensor);
		mSensorManager.unregisterListener(this, accelerometer);
		unregisterReceiver(receiver);
	}







	/*******    Camera Code     ********/

	/** A safe way to get an instance of the Camera object. */
	public static Camera getCameraInstance() {
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
	            mCamera.setDisplayOrientation(90);
	            mCamera.startPreview();
	            //this.setVisibility(View.GONE);
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
	        
	        //Log.d("Supported scene modes", parameters.getSupportedSceneModes().toString());
	        //Log.d("Exposure Compensation", parameters.getMaxExposureCompensation() + " " + parameters.getMinExposureCompensation());
	        List<Size> sizes = parameters.getSupportedPictureSizes();
	        parameters.setPictureSize(sizes.get(4).width, sizes.get(4).height);// Picture dimension(6) = 1152*864 < 1 megapixels
	        //parameters.setSceneMode(Camera.Parameters.SCENE_MODE_STEADYPHOTO); 
	        parameters.setSceneMode(Camera.Parameters.SCENE_MODE_ACTION);
	        parameters.setExposureCompensation(-2);
	        //parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
	        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
	        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
	        parameters.set("orientation", "portrait");
            parameters.set("rotation", 90);
	        Log.d("Parameters", sizes.get(0).width + " " + sizes.get(0).height);
	        mCamera.setParameters(parameters);

	        // start preview with new settings
	        try {
	        	mCamera.setDisplayOrientation(90);
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

	        pictureFile = getOutputMediaFile();
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
		    //image = baos.toByteArray(); 
		    //encImage = Base64.encodeToString(image, Base64.DEFAULT);

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
	            Log.d("ImageExperiments", "failed to create directory");
	            return null;
	        }
	    }

	    // Create a media file name
	    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
	    File mediaFile = new File(mediaStorageDir.getPath() + File.separator +
	        "IMG_"+ timeStamp + ".jpg");

	    return mediaFile;
	}




	public void processAccelerometerEvent(SensorEvent event) {		
			mConvolution = (float) (mCC.process(event.values[2]));
			mStepDetector.onSensorChanged(event);
			displayStepDetectorState(mStepDetector);
	}


	void displayStepDetectorState(MovingAverageStepDetector detector) {
		MovingAverageStepDetectorState s = detector.getState();
		boolean stepDetected = s.states[0];
		boolean signalPowerOutOfRange = s.states[1];

		if (stepDetected) {
			if (!signalPowerOutOfRange && detector.stepLength >= 0) {
				Log.d("Valid Step", "Valid step!");
					
				/*
				// If a step is detected, do PCA on linear acceleration values to get user heading
				double[][] floatData = new double[3][la1.size()];
				for (int i=0; i<la1.size(); ++i) {
					floatData[0][i] = la1.get(i);
					floatData[1][i] = la2.get(i);
					floatData[2][i] = la3.get(i);
				}
				
				Matrix matrixData = (new Matrix(floatData)).transpose();
				//double[][] matrixAdjusted = PCA.getMeanAdjusted(matrixData.getArray(), pca.getMeans());
				PCA pca = new PCA(matrixData.getArray());
				List<PrincipalComponent> mainComponents = pca.getDominantComponents(3);
				PrincipalComponent largestPC = mainComponents.get(0); 
				//Log.d("PCA", largestPC.eigenVector[0] + " " + largestPC.eigenVector[1]);
				
				
				int sumOfDotProducts = 0;
				for (int i=0; i<la1.size(); ++i) {
					sumOfDotProducts += (la1.get(i) * largestPC.eigenVector[0]) + (la2.get(i) * largestPC.eigenVector[1]);
				}
				int sign = sumOfDotProducts>0?1:-1;
				double angle = Math.atan(largestPC.eigenVector[1] / largestPC.eigenVector[0]);
				if (sign == -1) {
					angle -= Math.PI;
				}
				angle += 3*Math.PI/2;
				Log.d("PCA", angle + "");
				//Log.d("PCA", mainComponents.get(0).eigenValue + " " + mainComponents.get(1).eigenValue);
				
				// Reset linear acceleration values
				la1.clear();
				la2.clear();
				la3.clear();*/
				

				JSONObject motion;
				HashMap<String, Double> motionMap = new HashMap<String, Double>();
				motionMap.put("hdg", (double)orientation[0]);
				//motionMap.put("hdg", angle);
				motionMap.put("dis", detector.stepLength);
				motion = new JSONObject(motionMap);
				new CentralQueryTask(CENTRAL_DYNAMIC_URL, motion).execute(this.getApplicationContext());

				Log.d("REQUEST", "Valid step sent to central server!");
			}
		}
	}





	/*****    Sensor Code    ****/

	public final void onAccuracyChanged(Sensor sensor, int accuracy) {
	    // Do something here if sensor accuracy changes.
	  }

	public void onSensorChanged(SensorEvent event){
		int type = event.sensor.getType();

		synchronized (this) {
				if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
					
					processAccelerometerEvent(event);
					freqCounter.push(event.timestamp);
					float rate = freqCounter.getRateF();
					if (rate != 0.0f)
						mSpeed = 100f / rate;
				}
			}
		/*
		if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
			
			float resultVec[] = new float[4];
            float accVals[]= event.values.clone();
            float relativeacc[] = new float[4];
            relativeacc[0] = accVals[0];
            relativeacc[1] = accVals[1];
            relativeacc[2] = accVals[2];
            relativeacc[3] = 0;
            android.opengl.Matrix.multiplyMV(resultVec, 0, rotationMatrix, 0, relativeacc, 0);
            //Log.d("PCA", "resultVec: " + resultVec[0] + " " + resultVec[1] + " " + resultVec[2] + " " + resultVec[3] + " ");
			la1.add(resultVec[0]);
			la2.add(resultVec[1]);
			la3.add(resultVec[2]);
			
		}	*/ 
	   if (type == Sensor.TYPE_ROTATION_VECTOR) {
		   long currTimestamp = System.currentTimeMillis(); 
		   orientation = new float[3];

		   newRotationVector = event.values.clone();
		   if (oldRotationVector != null) {
			   deltaRotationVector[0] = newRotationVector[0] - oldRotationVector[0];
			   deltaRotationVector[1] = newRotationVector[1] - oldRotationVector[1];
			   deltaRotationVector[2] = newRotationVector[2] - oldRotationVector[2];
			   deltaRotationVector[3] = 0;
		   }
		   SensorManager.getRotationMatrixFromVector(rotationMatrix, newRotationVector);

		   if (currTimestamp >= timestamp) {
			   SensorManager.getOrientation(rotationMatrix, orientation);
			   cameraPose[0] = (float) Math.round(Math.toDegrees(orientation[2])*100)/100; // Row
			   cameraPose[1] = (float) Math.round(Math.toDegrees(orientation[1])*100)/100; // Pitch
			   cameraPose[2] = (float) Math.round(Math.toDegrees(orientation[0])*100)/100; // Yaw
		   }
           oldRotationVector[0] = newRotationVector[0];
           oldRotationVector[1] = newRotationVector[1];
           oldRotationVector[2] = newRotationVector[2];
	   }
	 }
}