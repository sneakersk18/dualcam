package com.dualcam;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * Composites rear camera (fullscreen) + front camera (circular PiP top-right)
 * into a MediaRecorder input surface using OpenGL ES 2.0.
 *
 * prepare(outputSurface) — synchronous, blocks until EGL+GL ready
 * getBackInputSurface()  — give this to the back Camera2 session
 * getFrontInputSurface() — give this to the front Camera2 session
 * start()                — begin 30fps render loop
 * stop()                 — halt loop and release all GL/EGL resources
 */
public class DualCamEncoder {

    public static final int WIDTH  = 720;
    public static final int HEIGHT = 1280;

    // PiP circle: 238px diameter, top-right, 20px margin
    // Quad X is 238px wide (NDC x=[0.2833, 0.9444])
    // Quad Y is 423px tall (238 * 1280/720) so the portrait content
    // fills it at the correct aspect ratio — circle clip shows the center.
    // Circle center: cx=581, cy=1141 (gl_FragCoord, y=0 at bottom)
    private static final float PIP_X0 = 0.2833f;
    private static final float PIP_X1 = 0.9444f;
    // Center Y NDC = 0.7828; half-height NDC = (423/2)/640 = 0.3305
    private static final float PIP_Y0 = 0.4523f;   // 0.7828 - 0.3305
    private static final float PIP_Y1 = 1.1133f;   // 0.7828 + 0.3305  (clips at viewport top)

    // Circle center in gl_FragCoord pixels (y=0 at bottom of 720x1280 framebuffer)
    // cx = 720 - 20 - 119 = 581,  cy = 1280 - 20 - 119 = 1141,  r = 119
    private static final String CIRCLE_TEST =
        "float _dx = gl_FragCoord.x - 581.0;\n" +
        "float _dy = gl_FragCoord.y - 1141.0;\n" +
        "if (_dx*_dx + _dy*_dy > 14161.0) discard;\n";

    // ---- shaders ----
    private static final String VERT =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform   mat4 uTexMatrix;\n" +
        "varying   vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
        "}\n";

    private static final String FRAG_BACK =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform samplerExternalOES uTex;\n" +
        "void main() { gl_FragColor = texture2D(uTex, vTexCoord); }\n";

    private static final String FRAG_FRONT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform samplerExternalOES uTex;\n" +
        "void main() {\n" +
        CIRCLE_TEST +
        "    gl_FragColor = texture2D(uTex, vTexCoord);\n" +
        "}\n";

    // Interleaved vertex buffer layout: [x, y, u, v]
    private static final int STRIDE = 4 * 4; // 4 floats × 4 bytes

    private static final float[] FULL_QUAD = {
        -1f, -1f,  0f, 0f,
         1f, -1f,  1f, 0f,
        -1f,  1f,  0f, 1f,
         1f,  1f,  1f, 1f,
    };

    private static final float[] PIP_QUAD = {
        PIP_X0, PIP_Y0,  0f, 0f,
        PIP_X1, PIP_Y0,  1f, 0f,
        PIP_X0, PIP_Y1,  0f, 1f,
        PIP_X1, PIP_Y1,  1f, 1f,
    };

    // EGL
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext  = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface  = EGL14.EGL_NO_SURFACE;

    // GL
    private int mBackProg, mFrontProg;
    private int mBkPos, mBkTC, mBkTM, mBkTex;
    private int mFrPos, mFrTC, mFrTM, mFrTex;
    private FloatBuffer mFullBuf, mPipBuf;
    private int[] mTexIds = new int[2];

    // Camera feeds
    private SurfaceTexture mBackST, mFrontST;
    private Surface mBackSurface, mFrontSurface;
    private final float[] mBackTM  = new float[16];
    private final float[] mFrontTM = new float[16];
    private volatile boolean mBackNew, mFrontNew;

    // Thread
    private HandlerThread mThread;
    private Handler mHandler;
    private volatile boolean mRunning;

    // ---- public API ----

    /** Blocks caller until EGL context and camera input surfaces are ready. */
    public void prepare(Surface outputSurface) throws InterruptedException {
        mThread  = new HandlerThread("DualCamEncoder");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            try {
                initEGL(outputSurface);
                initGL();
                initTextures();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }

    public Surface getBackInputSurface()  { return mBackSurface;  }
    public Surface getFrontInputSurface() { return mFrontSurface; }

    public void start() {
        mRunning = true;
        mHandler.post(mRenderLoop);
    }

    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mRenderLoop);
        mHandler.post(() -> {
            releaseGL();
            releaseEGL();
            mThread.quitSafely();
        });
    }

    // ---- render loop ----

    private final Runnable mRenderLoop = new Runnable() {
        @Override public void run() {
            if (!mRunning) return;

            if (mBackNew  && mBackST  != null) { mBackST.updateTexImage();  mBackST.getTransformMatrix(mBackTM);   mBackNew  = false; }
            if (mFrontNew && mFrontST != null) { mFrontST.updateTexImage(); mFrontST.getTransformMatrix(mFrontTM); mFrontNew = false; }

            renderFrame();

            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, System.nanoTime());
            EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);

            if (mRunning) mHandler.postDelayed(this, 33);
        }
    };

    private void renderFrame() {
        GLES20.glViewport(0, 0, WIDTH, HEIGHT);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Back camera — fullscreen
        GLES20.glUseProgram(mBackProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexIds[0]);
        GLES20.glUniform1i(mBkTex, 0);
        GLES20.glUniformMatrix4fv(mBkTM, 1, false, mBackTM, 0);
        drawQuad(mFullBuf, mBkPos, mBkTC);

        // Front camera — circular PiP
        GLES20.glUseProgram(mFrontProg);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexIds[1]);
        GLES20.glUniform1i(mFrTex, 1);
        GLES20.glUniformMatrix4fv(mFrTM, 1, false, mFrontTM, 0);
        drawQuad(mPipBuf, mFrPos, mFrTC);
    }

    private void drawQuad(FloatBuffer buf, int posLoc, int tcLoc) {
        buf.position(0);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, STRIDE, buf);
        GLES20.glEnableVertexAttribArray(posLoc);
        buf.position(2);
        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, STRIDE, buf);
        GLES20.glEnableVertexAttribArray(tcLoc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ---- EGL ----

    private void initEGL(Surface outputSurface) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(mEGLDisplay, new int[2], 0, new int[2], 1);

        int[] attribs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1,  // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(mEGLDisplay, attribs, 0, configs, 0, 1, n, 0);

        int[] ctxAttr = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);

        int[] sfAttr = { EGL14.EGL_NONE };
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], outputSurface, sfAttr, 0);
        EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
    }

    private void releaseEGL() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mEGLSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            if (mEGLContext  != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglTerminate(mEGLDisplay);
        }
        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext  = EGL14.EGL_NO_CONTEXT;
        mEGLSurface  = EGL14.EGL_NO_SURFACE;
    }

    // ---- GL ----

    private void initGL() {
        mBackProg  = buildProg(VERT, FRAG_BACK);
        mFrontProg = buildProg(VERT, FRAG_FRONT);

        mBkPos = GLES20.glGetAttribLocation(mBackProg,   "aPosition");
        mBkTC  = GLES20.glGetAttribLocation(mBackProg,   "aTexCoord");
        mBkTM  = GLES20.glGetUniformLocation(mBackProg,  "uTexMatrix");
        mBkTex = GLES20.glGetUniformLocation(mBackProg,  "uTex");

        mFrPos = GLES20.glGetAttribLocation(mFrontProg,   "aPosition");
        mFrTC  = GLES20.glGetAttribLocation(mFrontProg,   "aTexCoord");
        mFrTM  = GLES20.glGetUniformLocation(mFrontProg,  "uTexMatrix");
        mFrTex = GLES20.glGetUniformLocation(mFrontProg,  "uTex");

        mFullBuf = buf(FULL_QUAD);
        mPipBuf  = buf(PIP_QUAD);

        Matrix.setIdentityM(mBackTM,  0);
        Matrix.setIdentityM(mFrontTM, 0);
    }

    private void initTextures() {
        GLES20.glGenTextures(2, mTexIds, 0);
        for (int id : mTexIds) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        mBackST = new SurfaceTexture(mTexIds[0]);
        mBackST.setDefaultBufferSize(1280, 720);
        mBackST.setOnFrameAvailableListener(st -> mBackNew = true, mHandler);
        mBackSurface = new Surface(mBackST);

        mFrontST = new SurfaceTexture(mTexIds[1]);
        mFrontST.setDefaultBufferSize(1280, 720);
        mFrontST.setOnFrameAvailableListener(st -> mFrontNew = true, mHandler);
        mFrontSurface = new Surface(mFrontST);
    }

    private void releaseGL() {
        if (mBackSurface  != null) { mBackSurface.release();  mBackSurface  = null; }
        if (mFrontSurface != null) { mFrontSurface.release(); mFrontSurface = null; }
        if (mBackST  != null) { mBackST.release();  mBackST  = null; }
        if (mFrontST != null) { mFrontST.release(); mFrontST = null; }
        GLES20.glDeleteTextures(2, mTexIds, 0);
        if (mBackProg  > 0) GLES20.glDeleteProgram(mBackProg);
        if (mFrontProg > 0) GLES20.glDeleteProgram(mFrontProg);
    }

    // ---- shader helpers ----

    private int buildProg(String vert, String frag) {
        int vs = shader(GLES20.GL_VERTEX_SHADER,   vert);
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, frag);
        int p  = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static FloatBuffer buf(float[] arr) {
        FloatBuffer fb = ByteBuffer.allocateDirect(arr.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(arr).position(0);
        return fb;
    }
}
