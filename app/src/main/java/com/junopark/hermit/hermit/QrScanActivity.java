package com.junopark.hermit.hermit;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.junopark.hermit.HermitLog;
import com.junopark.hermit.R;
import com.junopark.hermit.utils.UiHelper;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Scans a Shell QR pairing code (notes/DESIGN-I-qr-pairing.md, section 6.3): camera2, the back
 * camera, a TextureView preview and a small YUV_420_888 ImageReader (at most 640x480, two
 * buffers) whose Y plane ZXing decodes. The first text that starts like a hermit://pair link ends
 * the activity with it in {@link #EXTRA_LINK} (RESULT_OK); the caller parses and validates it.
 * The camera failing to open ends it with {@link #RESULT_CAMERA_FAILED}, so the caller can offer
 * the paste field instead. No AndroidX, no CameraX. Decoded text is never logged.
 *
 * Threads: the UI thread opens and closes the camera; camera callbacks and image decoding run on
 * one background HandlerThread, one frame at a time (acquireLatestImage drops the rest) and at
 * most about ten decodes a second.
 */
public class QrScanActivity extends Activity {
    public static final String EXTRA_LINK = "link";
    public static final int RESULT_CAMERA_FAILED = RESULT_FIRST_USER;

    private static final int MAX_DECODE_WIDTH = 640;
    private static final int MAX_DECODE_HEIGHT = 480;
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final long MIN_DECODE_INTERVAL_MS = 100;
    // How long closeCamera() waits for an open in progress to report back
    private static final long CAMERA_LOCK_TIMEOUT_MS = 2500;

    private TextureView textureView;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Size previewSize;
    // Set once a link was found or the activity is leaving; later frames and callbacks are ignored
    private volatile boolean finished;
    // Held from openCamera() until the open reports back (onOpened, onDisconnected, onError), and
    // by closeCamera() while it closes: an open in progress is never left behind when the
    // activity pauses (Camera2Basic pattern)
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private long lastDecodeAt;
    private final MultiFormatReader reader = new MultiFormatReader();

    /** Whether this device can scan at all: a camera, and not a TV. */
    public static boolean canScan(Context context) {
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return false;
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return false;
        }
        UiModeManager uiMode = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        return uiMode == null || uiMode.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** Whether the camera permission is granted (always, before Android 6). */
    public static boolean hasCameraPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        float density = getResources().getDisplayMetrics().density;
        TextView hint = new TextView(this);
        hint.setText(R.string.hermit_qr_scan_hint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTextColor(0xFFFFFFFF);
        hint.setBackgroundColor(0x99000000);
        hint.setGravity(Gravity.CENTER);
        int pad = Math.round(16 * density);
        hint.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(hint, hintParams);
        setContentView(root);
        UiHelper.notifyNewRootView(this);

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                configureTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasCameraPermission(this)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        finished = false;
        startCameraThread();
        if (textureView.isAvailable()) {
            openCamera();
        }
        // Otherwise onSurfaceTextureAvailable opens it
    }

    @Override
    protected void onPause() {
        finished = true;
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("QrCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void failCamera(String why) {
        HermitLog.warning("QR scanner: " + why);
        if (finished) {
            return;
        }
        finished = true;
        runOnUiThread(() -> {
            if (!isFinishing()) {
                setResult(RESULT_CAMERA_FAILED);
                finish();
            }
        });
    }

    // openCamera, startPreview, the camera callbacks and the closing itself are synchronized: the
    // UI thread closes while the camera thread may still be opening
    private synchronized void openCamera() {
        if (cameraDevice != null || finished || cameraHandler == null) {
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = null;
            StreamConfigurationMap map = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (m == null) {
                    continue;
                }
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                if (cameraId == null || back) {
                    cameraId = id;
                    map = m;
                }
                if (back) {
                    break;
                }
            }
            if (cameraId == null) {
                failCamera("no camera");
                return;
            }

            Size decodeSize = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888), MAX_DECODE_WIDTH, MAX_DECODE_HEIGHT);
            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class), MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);
            if (decodeSize == null || previewSize == null) {
                failCamera("no usable output size");
                return;
            }
            configureTransform(textureView.getWidth(), textureView.getHeight());

            imageReader = ImageReader.newInstance(decodeSize.getWidth(), decodeSize.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onFrame, cameraHandler);

            if (!hasCameraPermission(this)) {
                failCamera("no permission");
                return;
            }
            // Another open is still in progress (its callback needs this monitor to release the
            // lock): leave it to finish instead of waiting here and timing out
            if (!cameraOpenCloseLock.tryAcquire()) {
                return;
            }
            boolean opening = false;
            try {
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        synchronized (QrScanActivity.this) {
                            if (finished) {
                                // The activity paused meanwhile: nothing may stay open
                                camera.close();
                                cameraOpenCloseLock.release();
                                return;
                            }
                            cameraDevice = camera;
                        }
                        cameraOpenCloseLock.release();
                        startPreview();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        synchronized (QrScanActivity.this) {
                            camera.close();
                            cameraDevice = null;
                        }
                        cameraOpenCloseLock.release();
                        failCamera("camera disconnected");
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        synchronized (QrScanActivity.this) {
                            camera.close();
                            cameraDevice = null;
                        }
                        cameraOpenCloseLock.release();
                        failCamera("camera error " + error);
                    }
                }, cameraHandler);
                opening = true;
            } finally {
                if (!opening) {
                    // openCamera threw: no callback will release the lock
                    cameraOpenCloseLock.release();
                }
            }
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            failCamera(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failCamera("interrupted");
        }
    }

    /** The largest size within the bounds, else the smallest offered; null when none is offered. */
    private static Size chooseSize(Size[] sizes, int maxWidth, int maxHeight) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = null;
        Size smallest = null;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (smallest == null || area < (long) smallest.getWidth() * smallest.getHeight()) {
                smallest = s;
            }
            if (s.getWidth() <= maxWidth && s.getHeight() <= maxHeight
                    && (best == null || area > (long) best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best != null ? best : smallest;
    }

    private synchronized void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || cameraDevice == null || imageReader == null) {
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            final CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            synchronized (QrScanActivity.this) {
                                if (finished || cameraDevice == null) {
                                    session.close();
                                    return;
                                }
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(request.build(), null, cameraHandler);
                                } catch (CameraAccessException | IllegalStateException e) {
                                    failCamera("preview request: " + e.getMessage());
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            failCamera("session configuration failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            failCamera("session: " + e.getMessage());
        }
    }

    // Waits (bounded) for an open in progress to report back first, so its device is closed here
    // or, once finished is set, by onOpened itself; the monitor is taken only after the wait, as
    // onOpened needs it to release the lock
    private void closeCamera() {
        boolean locked = false;
        try {
            locked = cameraOpenCloseLock.tryAcquire(CAMERA_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                HermitLog.warning("QR scanner: the camera open did not report back in time");
            }
            synchronized (this) {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                cameraOpenCloseLock.release();
            }
        }
    }

    // On the camera thread: the newest frame's Y plane (row stride respected) through ZXing
    private void onFrame(ImageReader r) {
        Image image;
        try {
            image = r.acquireLatestImage();
        } catch (IllegalStateException e) {
            // The reader was closed meanwhile
            return;
        }
        if (image == null) {
            return;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            if (finished || now - lastDecodeAt < MIN_DECODE_INTERVAL_MS) {
                return;
            }
            lastDecodeAt = now;
            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane y = image.getPlanes()[0];
            ByteBuffer buffer = y.getBuffer();
            int rowStride = y.getRowStride();
            byte[] luma = new byte[width * height];
            if (rowStride == width) {
                buffer.get(luma, 0, Math.min(luma.length, buffer.remaining()));
            }
            else {
                byte[] row = new byte[rowStride];
                for (int i = 0; i < height; i++) {
                    int n = Math.min(rowStride, buffer.remaining());
                    if (n <= 0) {
                        break;
                    }
                    buffer.get(row, 0, n);
                    System.arraycopy(row, 0, luma, i * width, Math.min(width, n));
                }
            }
            String text = decode(luma, width, height);
            if (text != null && QrPairingLink.looksLikeLink(text)) {
                finished = true;
                final String link = text.trim();
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    closeCamera();
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_LINK, link));
                    finish();
                });
            }
        } finally {
            image.close();
        }
    }

    private String decode(byte[] luma, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false);
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            return result.getText();
        } catch (ReaderException | IllegalArgumentException e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    /**
     * Keeps the preview's aspect ratio (centre crop) and turns it upright: the frame arrives
     * oriented for the device's natural orientation, so a landscape display needs a rotation.
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(), (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        else {
            // Natural (portrait) orientation: the frame shows as height x width
            float frameWidth = previewSize.getHeight();
            float frameHeight = previewSize.getWidth();
            float scale = Math.max(viewWidth / frameWidth, viewHeight / frameHeight);
            matrix.setScale(frameWidth * scale / viewWidth, frameHeight * scale / viewHeight, centerX, centerY);
            if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, centerX, centerY);
            }
        }
        textureView.setTransform(matrix);
    }

    @Override
    public void onBackPressed() {
        finished = true;
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
