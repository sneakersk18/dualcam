package com.dualcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private View filterOverlay, pipContainer;
    private TextView filterNameView, recIndicator, zoomIndicator;
    private StickerView stickerView;
    private FaceEffectView faceEffectView;
    private MaterialButton btnCapture, btnRecord, btnSticker;
    private LinearLayout effectsRow;

    // Camera
    private CameraManager cameraManager;
    private String backCameraId, frontCameraId;
    private CameraDevice backCamera, frontCamera;
    private CameraCaptureSession backSession, frontSession;
    private Surface backPreviewSurface;

    // Thread
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    // Recording
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private Uri recordingUri;

    // Zoom
    private ScaleGestureDetector scaleDetector;
    private float currentZoom = 1.0f;
    private float maxZoom = 1.0f;
    private Runnable hideZoomIndicator;

    // Effects
    private int currentEffect = FaceEffectView.EFFECT_NONE;
    private MaterialButton[] effectBtns;

    private static final Object[][] EFFECT_DATA = {
        {FaceEffectView.EFFECT_NONE,    "✖",  "SIN"},
        {FaceEffectView.EFFECT_HEARTS,  "❤️", "AMOR"},
        {FaceEffectView.EFFECT_FIRE,    "🔥", "FUEGO"},
        {FaceEffectView.EFFECT_SPARKS,  "✨", "CHISPAS"},
        {FaceEffectView.EFFECT_SNOW,    "❄️", "NIEVE"},
        {FaceEffectView.EFFECT_GLITCH,  "📺", "GLITCH"},
        {FaceEffectView.EFFECT_NEON,    "💜", "NEON"},
        {FaceEffectView.EFFECT_VINTAGE, "📷", "RETRO"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        textureBack    = findViewById(R.id.texture_back);
        textureFront   = findViewById(R.id.texture_front);
        filterOverlay  = findViewById(R.id.filter_overlay);
        filterNameView = findViewById(R.id.filter_name);
        recIndicator   = findViewById(R.id.rec_indicator);
        zoomIndicator  = findViewById(R.id.zoom_indicator);
        stickerView    = findViewById(R.id.sticker_view);
        faceEffectView = findViewById(R.id.face_effect_view);
        btnCapture     = findViewById(R.id.btn_capture);
        btnRecord      = findViewById(R.id.btn_record);
        btnSticker     = findViewById(R.id.btn_sticker);
        effectsRow     = findViewById(R.id.effects_row);
        pipContainer   = findViewById(R.id.pip_container);

        setupPipDrag();
        setupZoom();

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERM_REQUEST);
        } else {
            init();
        }
    }

    private boolean hasPermissions() {
        for (String p : PERMISSIONS)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
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
        loadMaxZoom();
        buildEffectButtons();
        setupButtons();
        setupTextureListeners();
    }

    private void findCameraIds() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK  && backCameraId  == null) backCameraId  = id;
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontCameraId == null) frontCameraId = id;
                }
            }
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void loadMaxZoom() {
        if (backCameraId == null) return;
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(backCameraId);
            Float mz = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (mz != null) ? mz : 5.0f;
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // ===== TRANSFORM: centerCrop sin distorsión =====
    private void applyFillTransform(TextureView tv, float contentW, float contentH) {
        tv.post(() -> {
            int vw = tv.getWidth(), vh = tv.getHeight();
            if (vw == 0 || vh == 0) return;
            float scaleX = vw / contentW;
            float scaleY = vh / contentH;
            float maxScale = Math.max(scaleX, scaleY);
            Matrix m = new Matrix();
            m.setScale(maxScale / scaleX, maxScale / scaleY, vw / 2f, vh / 2f);
            tv.setTransform(m);
        });
    }

    // ===== ZOOM =====
    private void setupZoom() {
        hideZoomIndicator = () -> {
            if (zoomIndicator != null) zoomIndicator.setVisibility(View.GONE);
        };
        scaleDetector = new ScaleGestureDetector(this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    currentZoom *= d.getScaleFactor();
                    currentZoom = Math.max(1.0f, Math.min(maxZoom, currentZoom));
                    applyZoom();
                    showZoomIndicator();
                    return true;
                }
            });
    }

    private void applyZoom() {
        if (backCamera == null || backSession == null || backCameraId == null) return;
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(backCameraId);
            Rect sensor = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensor == null) return;
            int cropW = (int)(sensor.width()  / currentZoom);
            int cropH = (int)(sensor.height() / currentZoom);
            int cx = sensor.centerX(), cy = sensor.centerY();
            Rect crop = new Rect(cx - cropW/2, cy - cropH/2, cx + cropW/2, cy + cropH/2);

            CaptureRequest.Builder b = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(backPreviewSurface);
            b.set(CaptureRequest.SCALER_CROP_REGION, crop);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            backSession.setRepeatingRequest(b.build(), null, cameraHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void showZoomIndicator() {
        runOnUiThread(() -> {
            zoomIndicator.setText(String.format(Locale.US, "%.1f×", currentZoom));
            zoomIndicator.setVisibility(View.VISIBLE);
            zoomIndicator.removeCallbacks(hideZoomIndicator);
            zoomIndicator.postDelayed(hideZoomIndicator, 2000);
        });
    }

    // ===== EFFECTS TRAY =====
    private void buildEffectButtons() {
        effectsRow.removeAllViews();
        effectBtns = new MaterialButton[EFFECT_DATA.length];

        for (int i = 0; i < EFFECT_DATA.length; i++) {
            final int idx = i;
            final int effect = (int) EFFECT_DATA[i][0];
            String emoji = (String) EFFECT_DATA[i][1];
            String label = (String) EFFECT_DATA[i][2];

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dpToPx(6), 0, dpToPx(6), 0);

            MaterialButton btn = new MaterialButton(this, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
            btn.setText(emoji);
            btn.setTextSize(18f);
            int size = dpToPx(52);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            btn.setLayoutParams(lp);
            btn.setBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
            btn.setCornerRadius(size / 2);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setPadding(0, 0, 0, 0);
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            effectBtns[i] = btn;

            TextView lbl = new TextView(this);
            lbl.setText(label);
            lbl.setTextColor(Color.WHITE);
            lbl.setTextSize(8.5f);
            lbl.setGravity(Gravity.CENTER);
            lbl.setPadding(0, dpToPx(3), 0, 0);

            btn.setOnClickListener(v -> {
                currentEffect = effect;
                faceEffectView.setEffect(effect);
                filterNameView.setText(label);
                for (MaterialButton b : effectBtns) markEffectSelected(b, false);
                markEffectSelected(btn, true);
            });

            col.addView(btn);
            col.addView(lbl);
            effectsRow.addView(col);
        }
        markEffectSelected(effectBtns[0], true);
        filterNameView.setText((String) EFFECT_DATA[0][2]);
    }

    private void markEffectSelected(MaterialButton btn, boolean selected) {
        if (selected) {
            btn.setStrokeColor(ColorStateList.valueOf(Color.WHITE));
            btn.setStrokeWidth(dpToPx(2));
            btn.setBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        } else {
            btn.setStrokeWidth(0);
            btn.setBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ===== SETUP =====
    private void setupButtons() {
        btnCapture.setOnClickListener(v -> {
            filterOverlay.setVisibility(View.VISIBLE);
            filterOverlay.setBackgroundColor(0xAAFFFFFF);
            filterOverlay.postDelayed(() -> filterOverlay.setVisibility(View.GONE), 120);
            takeDualPhoto();
        });
        btnRecord.setOnClickListener(v -> { if (isRecording) stopRecording(); else startRecording(); });
        btnSticker.setOnClickListener(v -> showStickerPicker());
        stickerView.setOnLongClickListener(v -> { stickerView.removeSelected(); return true; });
    }

    private void setupPipDrag() {
        pipContainer.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - e.getRawX();
                        dY = v.getY() - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float nx = Math.max(0, Math.min(e.getRawX() + dX, getWindow().getDecorView().getWidth()  - v.getWidth()));
                        float ny = Math.max(0, Math.min(e.getRawY() + dY, getWindow().getDecorView().getHeight() - v.getHeight()));
                        v.setX(nx); v.setY(ny);
                        return true;
                }
                return false;
            }
        });
    }

    private void setupTextureListeners() {
        textureBack.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                startCameraThread();
                openCamera(backCameraId, true);
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                applyFillTransform(textureBack, 720, 1280);
            }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        textureFront.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                openCamera(frontCameraId, false);
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                applyFillTransform(textureFront, 720, 1280);
            }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        textureBack.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            return true;
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
                @Override public void onError(@NonNull CameraDevice camera, int error) { camera.close(); }
            }, cameraHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void createBackSession() {
        if (backCamera == null || !textureBack.isAvailable()) return;
        try {
            SurfaceTexture st = textureBack.getSurfaceTexture();
            st.setDefaultBufferSize(1280, 720);
            applyFillTransform(textureBack, 720, 1280);
            backPreviewSurface = new Surface(st);

            List<Surface> surfaces = new ArrayList<>(Arrays.asList(backPreviewSurface));

            CaptureRequest.Builder b = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(backPreviewSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            backCamera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    backSession = session;
                    try { session.setRepeatingRequest(b.build(), null, cameraHandler); }
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
            applyFillTransform(textureFront, 720, 1280);
            Surface previewSurface = new Surface(st);

            CaptureRequest.Builder b = frontCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            frontCamera.createCaptureSession(Arrays.asList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    frontSession = session;
                    try { session.setRepeatingRequest(b.build(), null, cameraHandler); }
                    catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, cameraHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    // ===== FOTO DUAL (composite trasera + frontal circular) =====
    private void takeDualPhoto() {
        // Captura los frames actuales de ambas TextureViews en el hilo UI
        Bitmap rear  = textureBack.getBitmap();
        Bitmap front = textureFront.getBitmap();
        if (rear == null) return;

        new Thread(() -> {
            Bitmap output = rear.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(output);

            if (front != null) {
                // PiP: 150dp circular en la esquina superior derecha, margen 20dp
                int pipPx  = dpToPx(150);
                int margin = dpToPx(20);

                // Recortar cuadrado central del frame frontal
                int side = Math.min(front.getWidth(), front.getHeight());
                int fx = (front.getWidth()  - side) / 2;
                int fy = (front.getHeight() - side) / 2;
                Bitmap square  = Bitmap.createBitmap(front, fx, fy, side, side);
                Bitmap scaled  = Bitmap.createScaledBitmap(square, pipPx, pipPx, true);

                // Hacer circular con PorterDuff
                Bitmap circular = Bitmap.createBitmap(pipPx, pipPx, Bitmap.Config.ARGB_8888);
                Canvas cc = new Canvas(circular);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                cc.drawOval(new RectF(0, 0, pipPx, pipPx), p);
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
                cc.drawBitmap(scaled, 0, 0, p);
                p.setXfermode(null);

                // Dibujar en la esquina superior derecha
                int pipX = output.getWidth() - pipPx - margin;
                int pipY = margin;
                canvas.drawBitmap(circular, pipX, pipY, null);

                // Borde negro 75% opacidad
                Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                border.setStyle(Paint.Style.STROKE);
                border.setColor(0xBF000000);
                border.setStrokeWidth(dpToPx(3));
                canvas.drawOval(pipX, pipY, pipX + pipPx, pipY + pipPx, border);

                square.recycle(); scaled.recycle(); circular.recycle(); front.recycle();
            }

            // Guardar en galería
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "DUALCAM_" + ts + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DualCam");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    output.compress(Bitmap.CompressFormat.JPEG, 95, os);
                } catch (IOException e) { e.printStackTrace(); }
            }
            output.recycle(); rear.recycle();
            runOnUiThread(() -> Toast.makeText(this, "📸 Foto guardada", Toast.LENGTH_SHORT).show());
        }).start();
    }

    // ===== VIDEO =====
    private void startRecording() {
        if (backCamera == null) return;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, "DUALCAM_" + ts + ".mp4");
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
            mediaRecorder.setVideoEncodingBitRate(8_000_000);
            java.io.FileDescriptor fd = getContentResolver().openFileDescriptor(recordingUri, "w").getFileDescriptor();
            mediaRecorder.setOutputFile(fd);
            mediaRecorder.prepare();

            SurfaceTexture st = textureBack.getSurfaceTexture();
            st.setDefaultBufferSize(1280, 720);
            backPreviewSurface = new Surface(st);
            Surface recSurface = mediaRecorder.getSurface();

            CaptureRequest.Builder b = backCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(backPreviewSurface);
            b.addTarget(recSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            backCamera.createCaptureSession(Arrays.asList(backPreviewSurface, recSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                        backSession = session;
                        try {
                            session.setRepeatingRequest(b.build(), null, cameraHandler);
                            mediaRecorder.start();
                            isRecording = true;
                            runOnUiThread(() -> {
                                recIndicator.setVisibility(View.VISIBLE);
                                btnRecord.setText("⏹");
                                btnRecord.setTextColor(Color.WHITE);
                            });
                        } catch (CameraAccessException e) { e.printStackTrace(); }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {}
                }, cameraHandler);
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void stopRecording() {
        if (!isRecording || mediaRecorder == null) return;
        try { mediaRecorder.stop(); mediaRecorder.reset(); mediaRecorder.release(); } catch (Exception ignored) {}
        mediaRecorder = null;
        isRecording = false;
        runOnUiThread(() -> {
            recIndicator.setVisibility(View.GONE);
            btnRecord.setText("⏺");
            btnRecord.setTextColor(Color.parseColor("#FF4444"));
            Toast.makeText(this, "🎬 Video guardado", Toast.LENGTH_SHORT).show();
        });
        createBackSession();
    }

    // ===== STICKERS =====
    private void showStickerPicker() {
        final String[] emojis = {
            "😂","😍","🔥","✨","💀","🤡","😎","👻","💪","🫠",
            "❤️","💥","⭐","🎉","🤔","😤","🥵","😈","🤩","💫",
            "🌈","🦋","🏆","🎸","🍕","🌙","☀️","🎯","🦄","💎",
            "🐱","🐶","🐸","🍓","🍩","🎃","🦊","🐧","🌺","🎀"
        };
        final int COLS = 5;

        // Construir cuadrícula con LinearLayout (más fiable que GridView en AlertDialog)
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(4));

        AlertDialog[] holder = new AlertDialog[1];

        for (int r = 0; r < Math.ceil((double) emojis.length / COLS); r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                TextView cell = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(58), 1f);
                cell.setLayoutParams(lp);
                cell.setGravity(Gravity.CENTER);
                if (idx < emojis.length) {
                    cell.setText(emojis[idx]);
                    cell.setTextSize(26f);
                    final String emoji = emojis[idx];
                    cell.setOnClickListener(v -> {
                        stickerView.addSticker(emoji);
                        // mantener diálogo abierto (como WhatsApp)
                    });
                    cell.setBackgroundResource(android.R.drawable.list_selector_background);
                }
                row.addView(cell);
            }
            grid.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(grid);

        holder[0] = new AlertDialog.Builder(this)
            .setTitle("Stickers")
            .setView(sv)
            .setNeutralButton("Limpiar todo", (d, w) -> stickerView.clearStickers())
            .setNegativeButton("Cerrar", null)
            .create();
        holder[0].show();
    }

    // ===== LIFECYCLE =====
    @Override
    protected void onPause() {
        super.onPause();
        if (faceEffectView != null) faceEffectView.setEffect(FaceEffectView.EFFECT_NONE);
        closeCamera();
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermissions()) {
            startCameraThread();
            if (textureBack.isAvailable())  openCamera(backCameraId, true);
            if (textureFront.isAvailable()) openCamera(frontCameraId, false);
            if (faceEffectView != null) faceEffectView.setEffect(currentEffect);
        }
    }

    private void closeCamera() {
        if (isRecording) stopRecording();
        if (backSession  != null) { try { backSession.stopRepeating();  } catch (Exception ignored) {} backSession.close();  backSession  = null; }
        if (frontSession != null) { try { frontSession.stopRepeating(); } catch (Exception ignored) {} frontSession.close(); frontSession = null; }
        if (backCamera   != null) { backCamera.close();  backCamera  = null; }
        if (frontCamera  != null) { frontCamera.close(); frontCamera = null; }
        if (backPreviewSurface != null) { backPreviewSurface.release(); backPreviewSurface = null; }
    }
}
