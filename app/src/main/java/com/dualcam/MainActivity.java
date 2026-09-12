package com.dualcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 100;
    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    // UI
    private TextureView textureBack, textureFront;
    private View filterOverlay;
    private TextView filterNameView, recIndicator;
    private StickerView stickerView;
    private MaterialButton btnCapture, btnRecord, btnSticker;
    private LinearLayout filtersRow;

    // Camera
    private CameraManager cameraManager;
    private String backCameraId, frontCameraId;
    private CameraDevice backCamera, frontCamera;
    private CameraCaptureSession backSession, frontSession;
    private ImageReader imageReader;

    // Thread
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    // Recording
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private Uri recordingUri;

    // Filters
    private static final int FILTER_NORMAL = 0;
    private static final int FILTER_VIVID = 1;
    private static final int FILTER_BW = 2;
    private static final int FILTER_WARM = 3;
    private static final int FILTER_COOL = 4;
    private static final int FILTER_DISTORT = 5;
    private int currentFilter = FILTER_NORMAL;

    private static final String[][] FILTER_DATA = {
        {"NORMAL",   "#00000000"},
        {"VIVIDO",   "#33FF6600"},
        {"B&W",      "#44000000"},
        {"CÁLIDO",   "#33FF8800"},
        {"FRÍO",     "#3300AAFF"},
        {"DISTORSIÓN","#33AA00FF"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureBack = findViewById(R.id.texture_back);
        textureFront = findViewById(R.id.texture_front);
        filterOverlay = findViewById(R.id.filter_overlay);
        filterNameView = findViewById(R.id.filter_name);
        recIndicator = findViewById(R.id.rec_indicator);
        stickerView = findViewById(R.id.sticker_view);
        btnCapture = findViewById(R.id.btn_capture);
        btnRecord = findViewById(R.id.btn_record);
        btnSticker = findViewById(R.id.btn_sticker);
        filtersRow = findViewById(R.id.filters_row);

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERM_REQUEST);
        } else {
            init();
        }
    }

    private boolean hasPermissions() {
        for (String p : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean ok = true;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (ok) init();
        else Toast.makeText(this, "Se necesitan permisos de cámara y micrófono", Toast.LENGTH_LONG).show();
    }

    private void init() {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findCameraIds();
        buildFilterButtons();
        setupButtons();
        setupTextureListeners();
    }

    private void findCameraIds() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK && backCameraId == null) {
                        backCameraId = id;
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontCameraId == null) {
                        frontCameraId = id;
                    }
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void buildFilterButtons() {
        filtersRow.removeAllViews();
        for (int i = 0; i < FILTER_DATA.length; i++) {
            final int idx = i;
            MaterialButton btn = new MaterialButton(this,
                null, com.google.android.material.R.attr.borderlessButtonStyle);
            btn.setText(FILTER_DATA[i][0]);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(11f);
            btn.setPadding(12, 0, 12, 0);
            btn.setOnClickListener(v -> applyFilter(idx));
            filtersRow.addView(btn);
        }
    }

    private void setupButtons() {
        btnCapture.setOnClickListener(v -> takePhoto());
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });
        btnSticker.setOnClickListener(v -> showStickerPicker());
        stickerView.setOnLongClickListener(v -> {
            stickerView.removeSelected();
            return true;
        });
    }

    private void setupTextureListeners() {
        textureBack.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                startCameraThread();
                openCamera(backCameraId, true);
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        textureFront.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                openCamera(frontCameraId, false);
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    private void openCamera(String cameraId, boolean isBack) {
        if (cameraId == null || cameraHandler == null) return;
        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    if (isBack) { backCamera = camera; createBackSession(); }
                    else        { frontCamera = camera; createFrontSession(); }
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); }
                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Error cámara " + (isBack ? "trasera" : "frontal"), Toast.LENGTH_SHORT).show());
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createBackSession() {
        if (backCamera == null || !textureBack.isAvailable()) return;
        try {
            SurfaceTexture st = textureBack.getSurfaceTexture();
            st.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(st);

            // ImageReader para captura de fotos
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                android.media.Image image = reader.acquireLatestImage();
                if (image != null) { savePhoto(image); image.close(); }
            }, cameraHandler);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(imageReader.getSurface());

            CaptureRequest.Builder previewBuilder = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            backCamera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    backSession = session;
                    try { session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler); }
                    catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void createFrontSession() {
        if (frontCamera == null || !textureFront.isAvailable()) return;
        try {
            SurfaceTexture st = textureFront.getSurfaceTexture();
            st.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(st);

            CaptureRequest.Builder previewBuilder = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            frontCamera.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    frontSession = session;
                    try { session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler); }
                    catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // ===== FILTROS =====

    private void applyFilter(int filterIdx) {
        currentFilter = filterIdx;
        String name = FILTER_DATA[filterIdx][0];
        String color = FILTER_DATA[filterIdx][1];
        filterNameView.setText(name);

        if (filterIdx == FILTER_NORMAL) {
            filterOverlay.setVisibility(View.GONE);
        } else {
            filterOverlay.setVisibility(View.VISIBLE);
            filterOverlay.setBackgroundColor(Color.parseColor(color));
        }
    }

    // ===== FOTO =====

    private void takePhoto() {
        if (backSession == null || imageReader == null) return;
        try {
            CaptureRequest.Builder captureBuilder = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            backSession.capture(captureBuilder.build(), null, cameraHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void savePhoto(android.media.Image image) {
        ByteBuffer buf = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);

        // Decodificar y aplicar efectos
        Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bmp == null) return;

        // Capturar frame frontal también
        Bitmap frontBmp = textureFront.getBitmap();

        // Crear imagen merged (trasera arriba, frontal abajo)
        Bitmap merged = createMergedPhoto(bmp, frontBmp);

        // Aplicar filtro de color
        Bitmap filtered = applyColorFilter(merged, currentFilter);

        // Guardar en galería
        saveToGallery(filtered, false);
        runOnUiThread(() -> Toast.makeText(this, "Foto guardada", Toast.LENGTH_SHORT).show());
    }

    private Bitmap createMergedPhoto(Bitmap back, Bitmap front) {
        int w = back.getWidth();
        int totalH = back.getHeight() + (front != null ? front.getHeight() : 0);
        Bitmap merged = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(merged);
        canvas.drawBitmap(back, 0, 0, null);
        if (front != null) {
            Bitmap scaledFront = Bitmap.createScaledBitmap(front, w, front.getHeight(), true);
            canvas.drawBitmap(scaledFront, 0, back.getHeight(), null);
        }
        // Línea divisora
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setStrokeWidth(6f);
        canvas.drawLine(0, back.getHeight(), w, back.getHeight(), p);
        return merged;
    }

    private Bitmap applyColorFilter(Bitmap src, int filter) {
        if (filter == FILTER_NORMAL) return src;
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();

        switch (filter) {
            case FILTER_VIVID:
                cm.setSaturation(2.5f);
                break;
            case FILTER_BW:
                cm.setSaturation(0f);
                break;
            case FILTER_WARM:
                float[] warmMatrix = {
                    1.2f, 0,    0,    0, 20,
                    0,    1.0f, 0,    0, 0,
                    0,    0,    0.8f, 0, -10,
                    0,    0,    0,    1, 0
                };
                cm.set(warmMatrix);
                break;
            case FILTER_COOL:
                float[] coolMatrix = {
                    0.8f, 0,    0,    0, -10,
                    0,    1.0f, 0,    0, 0,
                    0,    0,    1.3f, 0, 20,
                    0,    0,    0,    1, 0
                };
                cm.set(coolMatrix);
                break;
            case FILTER_DISTORT:
                return applyDistortion(src);
        }

        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    private Bitmap applyDistortion(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] srcPixels = new int[w * h];
        int[] dstPixels = new int[w * h];
        src.getPixels(srcPixels, 0, w, 0, 0, w, h);
        float cx = w / 2f, cy = h / 2f;
        float strength = 0.0003f;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float r2 = dx * dx + dy * dy;
                float factor = 1.0f + strength * r2;
                int sx = (int)(cx + dx * factor);
                int sy = (int)(cy + dy * factor);
                sx = Math.max(0, Math.min(w - 1, sx));
                sy = Math.max(0, Math.min(h - 1, sy));
                dstPixels[y * w + x] = srcPixels[sy * w + sx];
            }
        }
        out.setPixels(dstPixels, 0, w, 0, 0, w, h);
        return out;
    }

    private void saveToGallery(Bitmap bmp, boolean isVideo) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "DUALCAM_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DualCam");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, os);
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // ===== VIDEO =====

    private void startRecording() {
        if (backCamera == null) return;
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, "DUALCAM_" + timestamp + ".mp4");
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DualCam");
            recordingUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoSize(1280, 720);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(5_000_000);

            java.io.FileDescriptor fd = getContentResolver().openFileDescriptor(recordingUri, "w").getFileDescriptor();
            mediaRecorder.setOutputFile(fd);
            mediaRecorder.prepare();

            // Recrear sesión con superficie de MediaRecorder
            SurfaceTexture st = textureBack.getSurfaceTexture();
            st.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(st);
            Surface recorderSurface = mediaRecorder.getSurface();

            CaptureRequest.Builder builder = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recorderSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            backCamera.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        backSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                            mediaRecorder.start();
                            isRecording = true;
                            runOnUiThread(() -> {
                                recIndicator.setVisibility(View.VISIBLE);
                                btnRecord.setText("⏹");
                                btnRecord.setTextColor(Color.WHITE);
                            });
                        } catch (CameraAccessException e) { e.printStackTrace(); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error al iniciar grabación", Toast.LENGTH_SHORT).show());
                    }
                }, cameraHandler);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) return;
        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        } catch (Exception e) { e.printStackTrace(); }
        isRecording = false;
        runOnUiThread(() -> {
            recIndicator.setVisibility(View.GONE);
            btnRecord.setText("⏺");
            btnRecord.setTextColor(Color.parseColor("#FF4444"));
            Toast.makeText(this, "Video guardado", Toast.LENGTH_SHORT).show();
        });
        // Restaurar sesión de preview
        createBackSession();
    }

    // ===== STICKERS =====

    private void showStickerPicker() {
        String[] emojis = {"😂", "😍", "🔥", "✨", "💀", "🤡", "😎", "👻", "💪", "🫠",
                           "❤️", "💥", "⭐", "🎉", "🤔", "😤", "🥵", "😈", "🤩", "💫"};
        new AlertDialog.Builder(this)
            .setTitle("Agregar sticker")
            .setItems(emojis, (d, which) -> stickerView.addSticker(emojis[which]))
            .setNeutralButton("Limpiar todo", (d, w) -> stickerView.clearStickers())
            .show();
    }

    // ===== LIFECYCLE =====

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermissions()) {
            startCameraThread();
            if (textureBack.isAvailable()) openCamera(backCameraId, true);
            if (textureFront.isAvailable()) openCamera(frontCameraId, false);
        }
    }

    private void closeCamera() {
        if (isRecording) stopRecording();
        if (backSession != null) { try { backSession.stopRepeating(); } catch (Exception ignored) {} backSession.close(); backSession = null; }
        if (frontSession != null) { try { frontSession.stopRepeating(); } catch (Exception ignored) {} frontSession.close(); frontSession = null; }
        if (backCamera != null) { backCamera.close(); backCamera = null; }
        if (frontCamera != null) { frontCamera.close(); frontCamera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }
}
