/*
 * Copyright 2020 Akash Magnadia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.armcomptech.homeintrusiondetector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.Log;
import android.util.Patterns;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.armcomptech.homeintrusiondetector.EmailLogic.SendMailTask;
import com.armcomptech.homeintrusiondetector.audio.RecognizeCommands;
import com.armcomptech.homeintrusiondetector.video.CameraConnectionFragment;
import com.armcomptech.homeintrusiondetector.video.LegacyCameraConnectionFragment;
import com.armcomptech.homeintrusiondetector.video.env.ImageUtils;
import com.armcomptech.homeintrusiondetector.video.env.Logger;
import com.armcomptech.homeintrusiondetector.video.tflite.Classifier;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("deprecation")
public abstract class CameraActivity extends AppCompatActivity
        implements OnImageAvailableListener,
        Camera.PreviewCallback {

  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_RECORD_AUDIO = Manifest.permission.RECORD_AUDIO;
  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  private static final String WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
  private static final String READ_EXTERNAL_STORAGE = Manifest.permission.READ_EXTERNAL_STORAGE;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  private Handler VideoHandler;
  private HandlerThread VideoHandlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;
  private FrameLayout frameLayout;

  private boolean greenLightToTakePhoto = true;

  CameraConnectionFragment camera2Fragment;
  Fragment fragment;



  static List<String> emailAddresses;
  // Constants that control the behavior of the recognition code and model
  // settings. See the audio recognition tutorial for a detailed explanation of
  // all these, but you should customize them to match your training settings if
  // you are running your own model.
  private static final int SAMPLE_RATE = 16000;
  private static final int SAMPLE_DURATION_MS = 1000;
  private static final int RECORDING_LENGTH = (int) (SAMPLE_RATE * SAMPLE_DURATION_MS / 1000);
  private static final long AVERAGE_WINDOW_DURATION_MS = 1000;
  private static final float DETECTION_THRESHOLD = 0.50f;
  private static final int SUPPRESSION_MS = 700; //1500
  private static final int MINIMUM_COUNT = 3;
  private static final long MINIMUM_TIME_BETWEEN_SAMPLES_MS = 30;
  private static final String LABEL_FILENAME = "file:///android_asset/audio.txt";
  private static final String MODEL_FILENAME = "file:///android_asset/audio.tflite";

  // UI elements.
  private static final int REQUEST_RECORD_AUDIO = 13;
  private static final String LOG_TAG = CameraActivity.class.getSimpleName();

  // Working variables.
  short[] recordingBuffer = new short[RECORDING_LENGTH];
  int recordingOffset = 0;
  boolean shouldContinue = true;
  private Thread recordingThread;
  boolean shouldContinueRecognition = true;
  private Thread recognitionThread;
  private final ReentrantLock recordingBufferLock = new ReentrantLock();

  private List<String> labels = new ArrayList<>();
  private List<String> displayedLabels = new ArrayList<>();
  private RecognizeCommands recognizeCommands = null;

  private Interpreter tfLite;
  private long lastProcessingTimeMs;
  private HandlerThread audioBackgroundThread;
  private Handler audioBackgroundHandler;

  private Boolean glassBreakingCheckBox;
  private Boolean doorbellCheckBox;
  private Boolean knockCheckBox;
  private Boolean personDetectionCheckBox;
  private int emailTimeoutSeconds;
  private int maxAttachments;
  private int autoDeletionDays;

  private Handler monitoringSystemHandler = new Handler();
  private Boolean monitoringSystemActive = false;

  private Handler emailTimeoutCoolDownHandler = new Handler();
  private Boolean emailTimeoutCoolingDown = false;

  private Button cancelHowToUseDialog;

  //TODO: Turn off when releasing official software
  public boolean isDebug() {
    return false;
  }

  /** Memory-map the model file in Assets. */
  private static MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename)
          throws IOException {
    AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
    FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
    FileChannel fileChannel = inputStream.getChannel();
    long startOffset = fileDescriptor.getStartOffset();
    long declaredLength = fileDescriptor.getDeclaredLength();
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
  }

  @SuppressLint("StaticFieldLeak")
  private static CameraActivity instance;

  public CameraActivity() {
    instance = this;
  }

  public static Context getContext() {
    return instance;
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  protected void onCreate(final Bundle savedInstanceState) {

    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    setContentView(R.layout.activity_camera);
    Toolbar toolbar = findViewById(R.id.toolbar);
    Objects.requireNonNull(toolbar.getOverflowIcon()).setColorFilter(ContextCompat.getColor(this, R.color.white), PorterDuff.Mode.SRC_ATOP);
    setSupportActionBar(toolbar);
    Objects.requireNonNull(getSupportActionBar()).setDisplayShowTitleEnabled(true);
    getSupportActionBar().setTitle("(Inactive) Home Intrusion Detector");

    if (hasPermission()) {
      setFragment();
    } else {
      requestPermission();
    }

    int numThreads = 4; //default threads to be used by the device
    setNumThreads(numThreads);

    frameLayout = findViewById(R.id.container);

    emailAddresses = new ArrayList<>();

    // Load the labels for the model, but only display those that don't start
    // with an underscore.
    String actualLabelFilename = LABEL_FILENAME.split("file:///android_asset/", -1)[1];
    Log.i(LOG_TAG, "Reading labels from: " + actualLabelFilename);
    BufferedReader br = null;
    try {
      br = new BufferedReader(new InputStreamReader(getAssets().open(actualLabelFilename)));
      String line;
      while ((line = br.readLine()) != null) {
        labels.add(line);
        if (line.charAt(0) != '_') {
          displayedLabels.add(line.substring(0, 1).toUpperCase() + line.substring(1));
        }
      }
      br.close();
    } catch (IOException e) {
      throw new RuntimeException("Problem reading label file!", e);
    }

    // Set up an object to smooth recognition results to increase accuracy.
    recognizeCommands =
            new RecognizeCommands(
                    labels,
                    AVERAGE_WINDOW_DURATION_MS,
                    DETECTION_THRESHOLD,
                    SUPPRESSION_MS,
                    MINIMUM_COUNT,
                    MINIMUM_TIME_BETWEEN_SAMPLES_MS);

    String actualModelFilename = MODEL_FILENAME.split("file:///android_asset/", -1)[1];
    try {
      tfLite = new Interpreter(loadModelFile(getAssets(), actualModelFilename));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    tfLite.resizeInput(0, new int[] {RECORDING_LENGTH, 1});
    tfLite.resizeInput(1, new int[] {1});

    // Start the recording and recognition threads.
    requestMicrophonePermission();
    startRecording();
    startRecognition();
  }

  public static List<String> getEmailAddresses() {
    return emailAddresses;
  }

  public static void addEmailAddress(String emailAddress) {
    emailAddresses.add(emailAddress);
  }

  public static void removeEmailAddress(String emailAddress) {
    emailAddresses.remove(emailAddress);
  }

  public void checkForSettings() {
    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

    glassBreakingCheckBox = sharedPreferences.getBoolean("glass_breaking", true);
    doorbellCheckBox = sharedPreferences.getBoolean("doorbell", true);
    knockCheckBox = sharedPreferences.getBoolean("knock", true);
    personDetectionCheckBox = sharedPreferences.getBoolean("person_detection", true);
    emailTimeoutSeconds = Integer.parseInt(sharedPreferences.getString("email_timeout", "30"));
    maxAttachments = Integer.parseInt(sharedPreferences.getString("attachment_max_value", "10"));
    autoDeletionDays = Integer.parseInt(sharedPreferences.getString("auto_deletion", "15"));

    RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);

    if (getScreenOrientation() == 0 || getScreenOrientation() == 180) {
      params.setMargins(0, 0, 0,0);
    } else if (getScreenOrientation() == 90 || getScreenOrientation() == 270) {

      WindowManager wm = (WindowManager) CameraActivity.getContext().getSystemService(Context.WINDOW_SERVICE);
      getScreenOrientation();
      assert wm != null;
      Display display = wm.getDefaultDisplay();
      int width = display.getWidth();
      int height = display.getHeight();

      if (width > 1000) {
        params.setMargins(0, 0, 0,0);
      } else //noinspection IntegerDivisionInFloatingPointContext
        if (height/width < 0.70) {
        params.setMargins(150, 0, 150,0);
      } else {
        params.setMargins(0, 0, 0,0);
      }
    }
    frameLayout.setLayoutParams(params);
  }

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  /** Callback for android.hardware.Camera API */
  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }

    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    yuvBytes[0] = bytes;
    //noinspection SuspiciousNameCombination
    yRowStride = previewWidth;

    imageConverter =
            () -> ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);

    postInferenceCallback =
            () -> {
              camera.addCallbackBuffer(bytes);
              isProcessingFrame = false;
            };
    processImage();
  }

  /** Callback for Camera2 API */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
              () -> ImageUtils.convertYUV420ToARGB8888(
                      yuvBytes[0],
                      yuvBytes[1],
                      yuvBytes[2],
                      previewWidth,
                      previewHeight,
                      yRowStride,
                      uvRowStride,
                      uvPixelStride,
                      rgbBytes);

      postInferenceCallback =
              () -> {
                image.close();
                isProcessingFrame = false;
              };

      processImage();
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  @Override
  public synchronized void onStart() {
    greenLightToTakePhoto = true;
    LOGGER.d("onStart " + this);
    checkForSettings();

    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    checkForSettings();
    greenLightToTakePhoto = true;
    LOGGER.d("onResume " + this);
    super.onResume();

    VideoHandlerThread = new HandlerThread("inference");
    VideoHandlerThread.start();
    VideoHandler = new Handler(VideoHandlerThread.getLooper());

    startBackgroundThread();
  }

  @Override
  public synchronized void onPause() {
    greenLightToTakePhoto = false;
    LOGGER.d("onPause " + this);

    VideoHandlerThread.quitSafely();
    try {
      VideoHandlerThread.join();
      VideoHandlerThread = null;
      VideoHandler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    greenLightToTakePhoto = false;
    LOGGER.d("onStop " + this);

    super.onStop();

    stopBackgroundThread();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();

    stopBackgroundThread();
//    android.os.Process.killProcess(android.os.Process.myPid());
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (VideoHandler != null) {
      VideoHandler.post(r);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onRequestPermissionsResult(
          final int requestCode, final String[] permissions, final int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSIONS_REQUEST) {
      if (allPermissionsGranted(grantResults)) {
        setFragment();
      } else {
        requestPermission();
      }
    }
  }

  private static boolean allPermissionsGranted(final int[] grantResults) {
    for (int result : grantResults) {
      if (result != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

      return (checkSelfPermission(PERMISSION_RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) &&
              (checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED) &&
              (checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) &&
              (checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
        Toast.makeText(
                CameraActivity.this,
                "Camera permission is required to use this app",
                Toast.LENGTH_LONG)
                .show();
      }
      if (shouldShowRequestPermissionRationale(WRITE_EXTERNAL_STORAGE)) {
        Toast.makeText(
                CameraActivity.this,
                "Storage permission required to save photos",
                Toast.LENGTH_LONG)
                .show();
      }
      if (shouldShowRequestPermissionRationale(READ_EXTERNAL_STORAGE)) {
        Toast.makeText(
                CameraActivity.this,
                "Storage permission required to save photos",
                Toast.LENGTH_LONG)
                .show();
      }
      requestPermissions(new String[] {PERMISSION_RECORD_AUDIO, PERMISSION_CAMERA, WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private boolean isHardwareLevelSupported(
          CameraCharacteristics characteristics) {
    @SuppressWarnings("ConstantConditions") int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return false;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL <= deviceLevel;
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      assert manager != null;
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        //noinspection ConstantConditions
        useCamera2API =
                (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                        || isHardwareLevelSupported(
                        characteristics);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  protected void setFragment() {
    String cameraId = chooseCamera();

    if (useCamera2API) {
      camera2Fragment =
              CameraConnectionFragment.newInstance(
                      (size, rotation) -> {
                        previewHeight = size.getHeight();
                        previewWidth = size.getWidth();
                        CameraActivity.this.onPreviewSizeChosen(size, rotation);
                      },
                      this,
                      getLayoutId(),
                      getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;


    } else {
      fragment =
              new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    }

    getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public void checkForObject(List<Classifier.Recognition> results) {
    for (final Classifier.Recognition result : results) {

      if (result.getTitle().equals("person")
              && (result.getConfidence() >= (float) (60 / 100))
              && monitoringSystemActive
              && personDetectionCheckBox) {

        // if green light for squirrel and confidence level is surpassed
        if (camera2Fragment != null) {
          if (isDebug()) {
            Toast.makeText(CameraActivity.this, "Detected person: " + result.getConfidence(), Toast.LENGTH_SHORT).show();
          } else {
            if (greenLightToTakePhoto) {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                camera2Fragment.takePicture();
              }
            }

            sendEmail("Home Intrusion Alert - person Detected",
                    "Your phone may have saw a person. Your phone as taken a picture.",
                    false);
          }
        } else {
          if (isDebug()) {
            Toast.makeText(CameraActivity.this, "Detected person: " + result.getConfidence(), Toast.LENGTH_SHORT).show();
          } else {
            if (greenLightToTakePhoto) {
              ((LegacyCameraConnectionFragment) fragment).takePicture();
            }
              sendEmail("Home Intrusion Alert - person Detected",
                    "Your phone may have saw a person. Your phone as taken a picture.",
                      false);
          }
        }
      }
    }
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
      case Surface.ROTATION_0:
        break;
    }
    return 0;
  }

  protected abstract void processImage();

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

  protected abstract int getLayoutId();

  protected abstract Size getDesiredPreviewFrameSize();

  protected abstract void setNumThreads(int numThreads);

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.option_menu, menu);

    menu.add(0, R.id.settings, 0, menuIconWithText(getResources().getDrawable(R.drawable.ic_baseline_settings_24_black), "Settings"));
    menu.add(0, R.id.testEmail, 1, menuIconWithText(getResources().getDrawable(R.drawable.ic_baseline_email_24_black), "Send Test Email"));
    menu.add(0, R.id.aboutThisApp, 1, menuIconWithText(getResources().getDrawable(R.drawable.ic_baseline_help_24_black), "How To Use"));
    menu.add(0, R.id.privacy_policy, 1, menuIconWithText(getResources().getDrawable(R.drawable.ic_baseline_lock_24_black), "Privacy Policy"));


    return true;
  }

  private CharSequence menuIconWithText(Drawable r, String title) {

    r.setBounds(0, 0, r.getIntrinsicWidth(), r.getIntrinsicHeight());
    SpannableString sb = new SpannableString("    " + title);
    ImageSpan imageSpan = new ImageSpan(r, ImageSpan.ALIGN_BOTTOM);
    sb.setSpan(imageSpan, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return sb;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();

    if (item.isChecked()) {
      item.setChecked(false);
    } else {
      item.setChecked(true);
    }

    switch (id) {
      case R.id.aboutThisApp:
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.how_to_use_view);
        dialog.show();

        break;

      case R.id.settings:
        startActivity(new Intent(this, SettingsActivity.class));
        break;

      case R.id.testEmail:
        if (getEmailAddresses().isEmpty()) {
          askForValidEmailAddress(true, 0);
        } else {
          sendEmail("Test Subject", "Test Body", true);
        }
        break;

      case R.id.privacy_policy:
        String link = "https://smartanimaldetector.blogspot.com/2020/08/home-intrusion-detector-privacy-policy.html";
        Intent myWebLink = new Intent(android.content.Intent.ACTION_VIEW);
        myWebLink.setData(Uri.parse(link));
        startActivity(myWebLink);
        break;

      case R.id.instantOn:
        delayedMonitoringSystemOn(1);
        break;

      case R.id.delay30secondOn:
        Toast.makeText(CameraActivity.this, "Monitoring System will be active in 30 seconds", Toast.LENGTH_SHORT).show();
        delayedMonitoringSystemOn(30);
        break;

      case R.id.delay1minuteOn:
        Toast.makeText(CameraActivity.this, "Monitoring System will be active in 1 minute", Toast.LENGTH_SHORT).show();
        delayedMonitoringSystemOn(60);
        break;

      case R.id.delay2minuteOn:
        Toast.makeText(CameraActivity.this, "Monitoring System will be active in 2 minutes", Toast.LENGTH_SHORT).show();
        delayedMonitoringSystemOn(120);
        break;

      default:
        break;
    }
    return super.onOptionsItemSelected(item);
  }

  public static boolean isValidEmail(CharSequence target) {
    return (!TextUtils.isEmpty(target) && Patterns.EMAIL_ADDRESS.matcher(target).matches());
  }

  private void sendEmail(String Subject, String Body, Boolean testEmail) {
    String fromEmail = getString(R.string.email);
    String fromPassword = getString(R.string.password);

    if (testEmail) {
      //if test email then send now
      List<File> toSendFileNameList = getFileNameListToSend();
      new SendMailTask(this).execute(fromEmail, fromPassword, getEmailAddresses(), Subject, Body, toSendFileNameList);
    } else {
      //if not test email than it is subject to a cooldown
      if (!emailTimeoutCoolingDown) {
        List<File> toSendFileNameList = getFileNameListToSend();
        new SendMailTask(this).execute(fromEmail, fromPassword, getEmailAddresses(), Subject, Body, toSendFileNameList);

        emailTimeoutCoolingDown = true;
        activateEmailTimeoutCooldown(emailTimeoutSeconds); //default is 30
      }
    }


  }

  private List<File> getFileNameListToSend() {
    List<File> toSendFileNameList = new ArrayList<>();
    File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Home Intrusion Detector");
    File[] list = file.listFiles();
    if (list != null) {
      int numFilesToSend = 0;
      for (File f: list){

        autoDeletion(f, autoDeletionDays);
        //all files older than 15 days are automatically deleted by default

        if (numFilesToSend >= maxAttachments) {
          break; //only 10 maximum photos can be sent in an email by default
        }

        String name = f.getName();
        if (name.endsWith("notSent.jpg")) {
          File renamedFile = new File(String.valueOf(f).replace("notSent", ""));
          toSendFileNameList.add(renamedFile);
          f.renameTo(renamedFile);
        }
        numFilesToSend++;
      }
    }

    return toSendFileNameList;
  }

  private void autoDeletion(File f, int days) {
    long diff = new Date().getTime() - f.lastModified();

    if (diff > days * 24 * 60 * 60 * 1000) {
      f.delete();
    }
    //all files older than x days are automatically deleted
  }

  private void requestMicrophonePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      requestPermissions(
              new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
    }
  }

  public synchronized void startRecording() {
    if (recordingThread != null) {
      return;
    }
    shouldContinue = true;
    recordingThread =
            new Thread(
                    new Runnable() {
                      @Override
                      public void run() {
                        record();
                      }
                    });
    recordingThread.start();
  }

  public synchronized void stopRecording() {
    if (recordingThread == null) {
      return;
    }
    shouldContinue = false;
    recordingThread = null;
  }

  private void record() {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

    // Estimate the buffer size we'll need for this device.
    int bufferSize =
            AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      bufferSize = SAMPLE_RATE * 2;
    }
    short[] audioBuffer = new short[bufferSize / 2];

    AudioRecord record =
            new AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
      Log.e(LOG_TAG, "Audio Record can't initialize!");
      return;
    }

    record.startRecording();

    Log.v(LOG_TAG, "Start recording");

    // Loop, gathering audio data and copying it to a round-robin buffer.
    while (shouldContinue) {
      int numberRead = record.read(audioBuffer, 0, audioBuffer.length);
      int maxLength = recordingBuffer.length;
      int newRecordingOffset = recordingOffset + numberRead;
      int secondCopyLength = Math.max(0, newRecordingOffset - maxLength);
      int firstCopyLength = numberRead - secondCopyLength;
      // We store off all the data for the recognition thread to access. The ML
      // thread will copy out of this buffer into its own, while holding the
      // lock, so this should be thread safe.
      recordingBufferLock.lock();
      try {
        System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, firstCopyLength);
        System.arraycopy(audioBuffer, firstCopyLength, recordingBuffer, 0, secondCopyLength);
        recordingOffset = newRecordingOffset % maxLength;
      } finally {
        recordingBufferLock.unlock();
      }
    }

    record.stop();
    record.release();
  }

  public synchronized void startRecognition() {
    if (recognitionThread != null) {
      return;
    }
    shouldContinueRecognition = true;
    recognitionThread =
            new Thread(
                    new Runnable() {
                      @Override
                      public void run() {
                        recognize();
                      }
                    });
    recognitionThread.start();
  }

  public synchronized void stopRecognition() {
    if (recognitionThread == null) {
      return;
    }
    shouldContinueRecognition = false;
    recognitionThread = null;
  }

  private void recognize() {

    Log.v(LOG_TAG, "Start recognition");

    short[] inputBuffer = new short[RECORDING_LENGTH];
    float[][] floatInputBuffer = new float[RECORDING_LENGTH][1];
    float[][] outputScores = new float[1][labels.size()];
    int[] sampleRateList = new int[] {SAMPLE_RATE};

    // Loop, grabbing recorded data and running the recognition model on it.
    while (shouldContinueRecognition) {
      long startTime = new Date().getTime();
      // The recording thread places data in this round-robin buffer, so lock to
      // make sure there's no writing happening and then copy it to our own
      // local version.
      recordingBufferLock.lock();
      try {
        int maxLength = recordingBuffer.length;
        int firstCopyLength = maxLength - recordingOffset;
        int secondCopyLength = recordingOffset;
        System.arraycopy(recordingBuffer, recordingOffset, inputBuffer, 0, firstCopyLength);
        System.arraycopy(recordingBuffer, 0, inputBuffer, firstCopyLength, secondCopyLength);
      } finally {
        recordingBufferLock.unlock();
      }

      // We need to feed in float values between -1.0f and 1.0f, so divide the
      // signed 16-bit inputs.
      for (int i = 0; i < RECORDING_LENGTH; ++i) {
        floatInputBuffer[i][0] = inputBuffer[i] / 32767.0f;
      }

      Object[] inputArray = {floatInputBuffer, sampleRateList};
      Map<Integer, Object> outputMap = new HashMap<>();
      outputMap.put(0, outputScores);

      // Run the model.
      tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

      // Use the smoother to figure out if we've had a real recognition event.
      long currentTime = System.currentTimeMillis();
      final RecognizeCommands.RecognitionResult result =
              recognizeCommands.processLatestResults(outputScores[0], currentTime);
      lastProcessingTimeMs = new Date().getTime() - startTime;
      runOnUiThread(
              new Runnable() {
                @Override
                public void run() {

                  //inferenceTimeTextView.setText(lastProcessingTimeMs + " ms");

                  // If we do have a new command, highlight the right list entry.
                  if (!result.foundCommand.startsWith("_") && result.isNewCommand) {

                    switch (result.foundCommand) {
                      case "glass_breaking":
                        if (glassBreakingCheckBox && monitoringSystemActive) {
                          if (isDebug()) {
                            Toast.makeText(CameraActivity.this, "Detected glass_breaking: " + result.score, Toast.LENGTH_SHORT).show();
                          } else {
                            sendEmail("Home Intrusion Alert - Glass Breaking Detected",
                                    "Your phone may have heard a glass breaking.",
                                    false);
                          }
                        }
                        break;

                      case "doorbell":
                        if (doorbellCheckBox && monitoringSystemActive) {
                          if (isDebug()) {
                            Toast.makeText(CameraActivity.this, "Detected doorbell: " + result.score, Toast.LENGTH_SHORT).show();
                          } else {
                            sendEmail("Home Intrusion Alert - Doorbell Detected",
                                    "Your phone may have heard a doorbell.",
                                    false);
                          }
                        }
                        break;

                      case "knock":
                        if (knockCheckBox && monitoringSystemActive) {
                          if (isDebug()) {
                            Toast.makeText(CameraActivity.this, "Detected knock: " + result.score, Toast.LENGTH_SHORT).show();
                          } else {
                            sendEmail("Home Intrusion Alert - knock Detected",
                                    "Your phone may have heard a knock of some kind such a door knock",
                                    false);
                          }
                        }
                        break;
                    }
                  }
                }
              });
      try {
        // We don't need to run too frequently, so snooze for a bit.
        Thread.sleep(MINIMUM_TIME_BETWEEN_SAMPLES_MS);
      } catch (InterruptedException e) {
        // Ignore
      }
    }

    Log.v(LOG_TAG, "End recognition");
  }

  private static final String HANDLE_THREAD_NAME = "AudioBackground";

  private void startBackgroundThread() {
    audioBackgroundThread = new HandlerThread(HANDLE_THREAD_NAME);
    audioBackgroundThread.start();
    audioBackgroundHandler = new Handler(audioBackgroundThread.getLooper());
    audioBackgroundHandler.post(() -> tfLite.setNumThreads(4));
  }

  private void stopBackgroundThread() {
    if (audioBackgroundThread != null) {
      audioBackgroundThread.quitSafely();
    }
    try {
      if (audioBackgroundThread != null) {
        audioBackgroundThread.join();
      }
      audioBackgroundThread = null;
      audioBackgroundHandler = null;
    } catch (InterruptedException e) {
      Log.e("amlan", "Interrupted when stopping background thread", e);
    }
  }

  private void activateEmailTimeoutCooldown(long seconds) {
    Runnable emailTimeoutCooldownRunnable = () -> {
      emailTimeoutCoolingDown = false;
      // after x seconds this will set to true so that email can be sent again
    };
    emailTimeoutCoolDownHandler.postDelayed(emailTimeoutCooldownRunnable, seconds * 1000);
  }

  private void delayedMonitoringSystemOn(long seconds) {
    //TODO: if system is active tell user
    if (getEmailAddresses().isEmpty()) {
      askForValidEmailAddress(false, seconds);
    } else {
      delayedMonitoringSystemOn(seconds);
    }
  }

  private void delayedMonitoringSystemOnHelper(long seconds) {
    Runnable monitoringActiveRunnable = () -> {
      monitoringSystemActive = true;
      getSupportActionBar().setTitle("(Active) Home Intrusion Detector"); //change the title to notify user
      Toast.makeText(CameraActivity.this, "Monitoring System in now activate", Toast.LENGTH_SHORT).show();
    };
    monitoringSystemHandler.postDelayed(monitoringActiveRunnable, seconds * 1000);
  }

  private void askForValidEmailAddress(Boolean testEmail, long seconds) {
    final EditText edittext = new EditText(this);
    AlertDialog.Builder alert = new AlertDialog.Builder(this);
    alert.setTitle("Add Email Address");
    alert.setMessage("Please enter a valid email address to send a email to yourself. You can always add or remove emails in the settings.");

    alert.setView(edittext);

    alert.setPositiveButton("Add Email", new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int whichButton) {
        if (isValidEmail(String.valueOf(edittext.getText()))) {
          addEmailAddress(String.valueOf(edittext.getText()));
          if (!testEmail) {
            delayedMonitoringSystemOnHelper(seconds);
          } else {
            //if asking email for test email
            sendEmail("Test Subject", "Test Body", testEmail);
          }
        } else {
          Toast.makeText(CameraActivity.this, "Enter a valid email Address", Toast.LENGTH_LONG).show();
        }
      }
    });

    alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int whichButton) {
        if (testEmail) {
          Toast.makeText(CameraActivity.this, "Email prompt canceled, therefore your test email will not be sent", Toast.LENGTH_LONG).show();
        } else {
          Toast.makeText(CameraActivity.this, "Email prompt canceled, therefore Monitoring system will not be activated", Toast.LENGTH_LONG).show();
        }

      }
    });

    alert.show();
  }
}