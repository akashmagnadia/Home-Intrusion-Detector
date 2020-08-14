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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import com.armcomptech.homeintrusiondetector.customview.OverlayView;
import com.armcomptech.homeintrusiondetector.env.BorderedText;
import com.armcomptech.homeintrusiondetector.env.ImageUtils;
import com.armcomptech.homeintrusiondetector.env.Logger;
import com.armcomptech.homeintrusiondetector.tflite.Classifier;
import com.armcomptech.homeintrusiondetector.tflite.TFLiteObjectDetectionAPIModel;
import com.armcomptech.homeintrusiondetector.tracking.MultiBoxTracker;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged SSD model.
  //TODO: Match the byte in the error but 640*640*3 should equal to the byte buffer
  // 512
  // 300
  private static final int TF_OD_API_INPUT_SIZE = /*640*/300;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
  private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt";
  private static final boolean MAINTAIN_ASPECT = false;

  private static final Size DESIRED_PREVIEW_SIZE = new Size(/*640*//*1920*/640, /*480*//*1080*/480);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;

  private Classifier detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    BorderedText borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
          TFLiteObjectDetectionAPIModel.create(
              getAssets(),
              TF_OD_API_MODEL_FILE,
              TF_OD_API_LABELS_FILE,
              TF_OD_API_INPUT_SIZE,
              TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing classifier!");
      Toast toast =
          Toast.makeText(
              getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    int sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
                sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
            canvas -> {
              tracker.draw(canvas);
              if (isDebug()) {
                tracker.drawDebug(canvas);
              }
            });

    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
  }

  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
            () -> {
              LOGGER.i("Running detection on image " + currTimestamp);
              final long startTime = SystemClock.uptimeMillis();
              final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
              lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

              cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
              final Canvas canvas1 = new Canvas(cropCopyBitmap);
              final Paint paint = new Paint();
              paint.setColor(Color.RED);
              paint.setStyle(Style.STROKE);
              paint.setStrokeWidth(2.0f);

              float minimumConfidence = (float) 50/100;

              final List<Classifier.Recognition> mappedRecognitions =
                      new LinkedList<>();

              for (final Classifier.Recognition result : results) {
                if (result.getTitle().equals("person")) {
                  final RectF location = result.getLocation();
                  if (location != null && result.getConfidence() >= minimumConfidence) {
                    canvas1.drawRect(location, paint);

                    cropToFrameTransform.mapRect(location);

                    result.setLocation(location);
                    mappedRecognitions.add(result);

                    checkForObject(results);
                  }
                }
              }

              tracker.trackResults(mappedRecognitions, currTimestamp);
              trackingOverlay.postInvalidate();

              computingDetection = false;
            });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.tfe_od_camera_connection_fragment_tracking;
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  @Override
  protected Size getDesiredPreviewFrameSize() {
    WindowManager wm = (WindowManager) CameraActivity.getContext().getSystemService(Context.WINDOW_SERVICE);
    getScreenOrientation();
    assert wm != null;
    Display display = wm.getDefaultDisplay();
    @SuppressWarnings("deprecation") int width = display.getWidth();
    @SuppressWarnings("deprecation") int height = display.getHeight();
//    return new Size(width, height);

    if (getScreenOrientation() == 0 || getScreenOrientation() == 180) {
      return DESIRED_PREVIEW_SIZE;
    } else if (getScreenOrientation() == 90 || getScreenOrientation() == 270) {
      if (height > width) {
        //noinspection SuspiciousNameCombination
        return new Size(height, width);
      } else if (width >= 1000) {
        return new Size(1920, 1080);
      } else {
        return new Size(width, height);
      }
    }
    return DESIRED_PREVIEW_SIZE;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(() -> detector.setUseNNAPI(isChecked));
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(() -> detector.setNumThreads(numThreads));
  }
}
