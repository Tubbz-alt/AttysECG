package tech.glasgowneuro.attysecg;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


/**
 * Created by Mustafa Biyikli on 25/06/2020
 */

public class PPGPlotFragment extends Fragment {

    private TextureView mTextureView;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
    private Size mPreviewSize;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private ImageReader mImageReader;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private TextView rgbView;
    private TextView bpmView;
    private TextView fps;
    private Chronometer chronometer;
    private Bitmap bitmap;

    private Button flashButton;
    private Button recordButton;

    private Timer loopTimer;

    private boolean isFlashON = false;
    static boolean isRecording = false;
    private boolean aboveThreshold = true;
    private boolean bpmInitialLoop = true;
    private int loop_counter = 0;

    private List<Float> PPG_data = new ArrayList<>();
    static List<Float> ECG_II_data = new ArrayList<>();

    private String TAG = "PPGPlotFragment";
    private String flashONTag = "FLASH OFF";
    private String flashOFFTag = "FLASH ON";

    private final double maxRed = Math.pow(2, 8);
    private final int HISTORY_SIZE = AttysECG.fpsFixed.getLower() * 3;
    private long timeStamp;

    private SimpleXYSeries redHistorySeries = null;
    private SimpleXYSeries thresholdHistorySeries = null;

    private XYPlot ppgPlot = null;

    private Vibrator vibrator;
    private float THRESHOLD = (float) 30.0;

    private Highpass highpass = null;
    private MatchedFilter matchedFilter = null;
    // Time reversed template for matched filter. Calculated from actual PPG data.
    private double[] double_coefficients = {1.7802734,  1.3839874,  1.1468048, -0.7828369, -3.3840485,
            -5.482147 , -6.950699 , -1.9465332,  1.2310181,  1.9858093,
            2.610321 ,  2.5477142};
    private float[] float_coefficients = new float[double_coefficients.length];

    /**
     * Mustafa's functions
     */
     private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            bitmap = mTextureView.getBitmap();

            Objects.requireNonNull(getActivity()).runOnUiThread(new Runnable() {
                float redBucket = 0;
                float greenBucket = 0;
                float blueBucket = 0;
                float pixelCount = 0;
                @SuppressLint("SetTextI18n")
                @Override
                public void run() {
                    for (int y = 0; y < bitmap.getHeight(); y+=bitmap.getHeight() / 10) {
                        for (int x = 0; x < bitmap.getWidth(); x+=bitmap.getWidth() / 10) {
                            int pixel = bitmap.getPixel(x, y);
                            pixelCount++;

                            redBucket += Color.red(pixel);
                            greenBucket += Color.green(pixel);
                            blueBucket += Color.blue(pixel);
                        }
                    }
                    float r = redBucket/pixelCount;
                    float g = greenBucket/pixelCount;
                    float b = blueBucket/pixelCount;

                    rgbView.setText("R:" + Math.round(r) + " G:" + Math.round(g) + " B:" + Math.round(b));

                    float maxValue = 0;
                    float minValue = 256;
                    for (int i = 0; i < redHistorySeries.size(); i++) {
                        float val = redHistorySeries.getY(i).floatValue();
                        if (val > maxValue) maxValue = val;
                        if (val < minValue) minValue = val;
                    }
                    ppgPlot.setRangeBoundaries(minValue, maxValue, BoundaryMode.FIXED);

                    // DC removal with high-pass filter
                    float r_filtered = highpass.filter(r);

                    // Matched filter
                    float r_matched = matchedFilter.matched_filter(r_filtered);

                    // Heart Rate detector
                    THRESHOLD = (float) 1.1 * (maxValue + minValue); // Adaptive threshold
                    heartRate_detect(r_matched, THRESHOLD);

                    // Recorder
                    if (isRecording) PPG_data.add(r_filtered);

                    // Live Plot
                    addValue(r_matched, THRESHOLD);

                    /*
                    // YUV format: taken from: https://stackoverflow.com/questions/5960247/convert-bitmap-array-to-yuv-ycbcr-nv21
                    long Y = ( (  66 * r + 129 * g +  25 * b + 128) >> 8) +  16;
                    long U = ( ( -38 * r -  74 * g + 112 * b + 128) >> 8) + 128;
                    long V = ( ( 112 * r -  94 * g -  18 * b + 128) >> 8) + 128;
                    rgbView.setText("Y:" + Y + " U:" + U + " V:" + V);
                    if (isRecording) PPG_data.add((Y+U+V)/3);
                    */
                }
            });
            loop_counter++; // used for FPS calculation
        }

    };

    @SuppressLint("SetTextI18n")
    private void heartRate_detect(float signal, float threshold) {
        if (signal >= threshold && aboveThreshold) {
            if (bpmInitialLoop) {
                timeStamp = new Date().getTime();
                bpmInitialLoop = false;
            } else {
                double bpm = 60.0 / ((new Date().getTime() - timeStamp) / 1000.0);
                if (bpm > 40 && bpm < 180) bpmView.setText((int) bpm + "bpm");
                else bpmView.setText("-");
                bpmInitialLoop = true;
            }
            aboveThreshold = false;
        } else if (signal < threshold && !aboveThreshold) {
            aboveThreshold = true;
        }
    }

    private void recorder() {
        vibrate(100);
        if (!isRecording) {
            // Start recording & start timer without blocking UI
            AsyncTask.execute(new Runnable() {
                @SuppressLint("SetTextI18n")
                @Override
                public void run() {
                    isRecording = true;
                    recordButton.setText("SAVE PPG/ECG");
                    chronometer.setBase(SystemClock.elapsedRealtime());
                    chronometer.setAlpha(1);
                    chronometer.start();
                }
            });

        } else {
            // Save the RGB/ECG data to .txt without blocking UI
            AsyncTask.execute(new Runnable() {
                @SuppressLint("SetTextI18n")
                @Override
                public void run() {
                    isRecording = false;
                    recordButton.setText("RECORD");
                    chronometer.setAlpha(0.5f);
                    chronometer.stop();

                    File file = new File(Objects.requireNonNull(getActivity()).getFilesDir(), "PPG_ECG_recordings");
                    if (!file.exists()) {
                        boolean wasSuccessful = file.mkdir();
                        if (!wasSuccessful) Log.e("File.mkdir()", "Failed");
                    }

                    try {
                        File ppgFile = new File(file, "PPG" + "_" + new Date().getTime());
                        File ecgFile = new File(file, "ECG" + "_" + new Date().getTime());

                        FileWriter writer_ppg = new FileWriter(ppgFile);
                        FileWriter writer_ecg = new FileWriter(ecgFile);

                        writer_ppg.append(PPG_data.toString());
                        writer_ecg.append(ECG_II_data.toString());

                        writer_ppg.flush();
                        writer_ecg.flush();
                        writer_ppg.close();
                        writer_ecg.close();

                    } catch (Exception e) {
                        Log.e("PPG/ECG recording error", "Could not save!");
                    }

                    PPG_data.clear(); // Clear the red data
                    ECG_II_data.clear(); // Clear the ECG II data
                }
            });
        }
    }

     private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Objects.requireNonNull(getActivity()).finish();
        }

    };

    // Camera2 Related Methods
     private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(Objects.requireNonNull(getContext()), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            new AttysECG().requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            AttysECG.mCameraManager.openCamera(AttysECG.mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    // Closes the current camera
     private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    // Sets up member variables related to camera

     private void setUpCameraOutputs(int width, int height) {
        int MAX_PREVIEW_WIDTH = 1920;
        int MAX_PREVIEW_HEIGHT = 1080;
        try {
            for (String cameraId : AttysECG.mCameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = AttysECG.mCameraManager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(null, mBackgroundHandler);

                Point displaySize = new Point();
                Objects.requireNonNull(getActivity()).getWindowManager().getDefaultDisplay().getSize(displaySize);

                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, maxPreviewWidth, maxPreviewHeight, largest);

                AttysECG.mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            Toast.makeText(getContext(), "Camera2 API not supported on this device", Toast.LENGTH_LONG).show();
        }
    }

    // Flash related methods

    private void flashON(final boolean SWITCH) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mCaptureSession.stopRepeating();
                    if (SWITCH) {
                        mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                        isFlashON = true;
                        flashButton.setText(flashONTag);
                    } else {
                        mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                        isFlashON = false;
                        flashButton.setText(flashOFFTag);
                    }
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // Vibrator
    private void vibrate(int milliseconds) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //deprecated in API 26
            vibrator.vibrate(milliseconds);
        }
    }

    // Creates camera capture session for preview

     private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // FPS range set
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, AttysECG.fpsFixed);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getContext(), "Failed", Toast.LENGTH_SHORT).show();
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Background thread / handler methods
     private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

     private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Utility methods
     private Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e("Camera2", "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

     private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = Objects.requireNonNull(getActivity()).getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /**
     * END of Mustafa's functions
     */

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        for (int i = 0; i < double_coefficients.length; i++) float_coefficients[i] = (float) double_coefficients[i];

        // Init high-pass filter
        highpass = new Highpass();
        matchedFilter = new MatchedFilter();
        matchedFilter.set_coefficients(float_coefficients);

        AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(Objects.requireNonNull(requireActivity()), R.style.CustomDialog);
        dlgAlert.setIcon(R.drawable.fingertip);
        dlgAlert.setMessage("Place your index fingertip over the rear camera to analyse your heart-rate\n\n" +
                "You can use the record PPG/ECG button to simultaneously record PPG & ECG data to a .txt file as an array\n\n" +
                "Recordings saved to: /data/data/tech.glasgowneuro.attysecg/files/PPG_ECG_recordings");
        dlgAlert.setTitle("PPG Info");
        dlgAlert.setPositiveButton("OK", null);
        dlgAlert.setCancelable(false);
        dlgAlert.setPositiveButton("Ok",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        flashON(true);
                    }
                });
        dlgAlert.create().show();

        Log.d(TAG, "onCreate, creating Fragment");
        vibrator = (Vibrator) Objects.requireNonNull(getActivity()).getSystemService(Context.VIBRATOR_SERVICE);

        if (container == null) {
            return null;
        }

        final View view = inflater.inflate(R.layout.ppgplotfragment, container, false);

        mTextureView = view.findViewById(R.id.texture);
        rgbView = view.findViewById(R.id.rgbView);
        bpmView = view.findViewById(R.id.bpmPPG);
        fps = view.findViewById(R.id.fpsView);
        chronometer = view.findViewById(R.id.chronometer);

        flashButton = view.findViewById(R.id.flashButton);
        flashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vibrate(200);
                if (Objects.requireNonNull(getActivity()).getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                    if (!isFlashON) {
                        flashON(true);
                    } else {
                        flashON(false);
                    }
                } else {
                    Toast.makeText(getContext(), "No flash available on this device", Toast.LENGTH_SHORT).show();
                }
            }
        });

        recordButton = view.findViewById(R.id.recordButton);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recorder();
            }
        });

        // setup the APR Levels plot:
        ppgPlot = view.findViewById(R.id.ppgPlotView);

        // Set padding
        XYGraphWidget ppgGraph = ppgPlot.getGraph();
        ppgGraph.setPadding(10, 50, 50, 10);

        ppgPlot.setBorderPaint(null);
        ppgPlot.setPlotMargins(0, 0, 0, 0);

        redHistorySeries = new SimpleXYSeries("R");
        thresholdHistorySeries = new SimpleXYSeries("threshold");
        redHistorySeries.useImplicitXVals();
        thresholdHistorySeries.useImplicitXVals();

        ppgPlot.setDomainBoundaries(0, HISTORY_SIZE, BoundaryMode.FIXED);
        ppgPlot.setRangeBoundaries(-maxRed/2, maxRed/2, BoundaryMode.FIXED);

        ppgPlot.addSeries(redHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(250, 0, 0), null, null, null));
        ppgPlot.addSeries(thresholdHistorySeries,
                new LineAndPointFormatter(
                        Color.BLACK, null, null, null));

        ppgPlot.setDomainLabel("PPG #");
        ppgPlot.setRangeLabel("");

        ppgPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 10);


        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 0, 0));
        ppgPlot.getGraph().setDomainGridLinePaint(paint);
        ppgPlot.getGraph().setRangeGridLinePaint(paint);

        return view;
    }

    private synchronized void addValue(final float v, final float t) {
        if (redHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "redHistorySeries == null");
            }
            return;
        }

        // get rid the oldest sample in history:
        if (redHistorySeries.size() > HISTORY_SIZE) {
            redHistorySeries.removeFirst();
            thresholdHistorySeries.removeFirst();
        }

        // add the latest history sample:
        redHistorySeries.addLast(null, v);
        thresholdHistorySeries.addLast(null, t);

        ppgPlot.redraw();
    }

    @Override
    public void onResume() {
        super.onResume();
        loopTimer = new Timer();
        loopTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Objects.requireNonNull(getActivity()).runOnUiThread(new Runnable() {
                    @SuppressLint("SetTextI18n")
                    @Override
                    public void run() {
                        fps.setText("FPS " + loop_counter);
                        loop_counter = 0;
                    }
                });
            }
        }, 0, 1000);
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        flashON(false);
        loopTimer.cancel();
        closeCamera();
        stopBackgroundThread();
    }
}
