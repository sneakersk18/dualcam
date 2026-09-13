package com.dualcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class FaceEffectView extends View {

    public static final int EFFECT_NONE    = 0;
    public static final int EFFECT_HEARTS  = 1;
    public static final int EFFECT_FIRE    = 2;
    public static final int EFFECT_SPARKS  = 3;
    public static final int EFFECT_SNOW    = 4;
    public static final int EFFECT_GLITCH  = 5;
    public static final int EFFECT_NEON    = 6;
    public static final int EFFECT_VINTAGE = 7;
    public static final int EFFECT_AR_CROWN   = 8;
    public static final int EFFECT_AR_BIGEYES = 9;
    public static final int EFFECT_AR_DOG     = 10;

    private int currentEffect = EFFECT_NONE;
    private final Random rng = new Random();
    private long tick = 0;

    // AR face tracking
    private android.hardware.camera2.params.Face[] mFaces;
    private int mSensorW, mSensorH, mViewW, mViewH;

    private final Paint pp  = new Paint(Paint.ANTI_ALIAS_FLAG); // particle
    private final Paint tp  = new Paint(Paint.ANTI_ALIAS_FLAG); // text/emoji
    private final Paint np  = new Paint(Paint.ANTI_ALIAS_FLAG); // neon
    private final Paint gp  = new Paint();                       // grain/glitch
    private final Paint vp  = new Paint(Paint.ANTI_ALIAS_FLAG); // vignette

    // Particle pools – float[] per-particle, layout varies by effect
    private final List<float[]> hearts = new ArrayList<>();
    private final List<float[]> fire   = new ArrayList<>();
    private final List<float[]> sparks = new ArrayList<>();
    private final List<float[]> snow   = new ArrayList<>();

    // Glitch state
    private final List<int[]> glitchStrips = new ArrayList<>();
    private int glitchCooldown = 0;
    private int nextGlitchAt   = 0;

    // Neon state
    private float neonHue = 0f;

    // Vignette gradient (rebuilt on size change)
    private RadialGradient vignetteGradient;

    private static final int GRAIN_COUNT = 600;

    public FaceEffectView(Context c) { super(c); init(); }
    public FaceEffectView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        tp.setTextAlign(Paint.Align.CENTER);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setEffect(int effect) {
        currentEffect = effect;
        hearts.clear(); fire.clear(); sparks.clear(); snow.clear();
        glitchStrips.clear(); glitchCooldown = 0; neonHue = 0f; tick = 0;
        // Clear face data when switching to non-AR effects
        if (effect != EFFECT_AR_CROWN && effect != EFFECT_AR_BIGEYES && effect != EFFECT_AR_DOG) {
            mFaces = null;
        }
        removeCallbacks(loopRunnable);
        if (effect != EFFECT_NONE) {
            int w = getWidth(), h = getHeight();
            if (w > 0 && h > 0) seed(effect, w, h);
            post(loopRunnable);
        }
        invalidate();
    }

    public void setFaces(android.hardware.camera2.params.Face[] faces,
                         int sensorW, int sensorH, int viewW, int viewH) {
        mFaces = faces;
        mSensorW = sensorW; mSensorH = sensorH;
        mViewW = viewW; mViewH = viewH;
        invalidate();
    }

    // Maps sensor (landscape) coords to view (portrait) coords for sensor_orientation=90.
    // For a 90° rotated sensor: sensor X axis -> view Y (inverted), sensor Y axis -> view X.
    private float mapFaceX(float sensorY) {
        if (mSensorH == 0) return 0;
        return sensorY / mSensorH * mViewW;
    }
    private float mapFaceY(float sensorX) {
        if (mSensorW == 0) return 0;
        return (1f - sensorX / mSensorW) * mViewH;
    }

    private final Runnable loopRunnable = () -> {
        if (currentEffect != EFFECT_NONE) {
            invalidate();
        }
    };

    // ── Size change ───────────────────────────────────────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            buildVignette(w, h);
            if (currentEffect != EFFECT_NONE) seed(currentEffect, w, h);
        }
    }

    private void buildVignette(int w, int h) {
        float cx = w / 2f, cy = h / 2f;
        float r  = (float) Math.sqrt(cx * cx + cy * cy);
        vignetteGradient = new RadialGradient(cx, cy, r,
            new int[]  { 0x00000000, 0x00000000, 0xCC000000 },
            new float[]{ 0f, 0.55f, 1f },
            Shader.TileMode.CLAMP);
    }

    private void seed(int effect, int w, int h) {
        hearts.clear(); fire.clear(); sparks.clear(); snow.clear();
        switch (effect) {
            case EFFECT_HEARTS: for (int i=0;i<6;i++)  spawnHeart(w, h, rng.nextFloat()*h); break;
            case EFFECT_FIRE:   for (int i=0;i<40;i++) spawnFire(w, h, rng.nextInt(h/2)+h/2); break;
            case EFFECT_SPARKS: for (int i=0;i<25;i++) spawnSpark(w, h); break;
            case EFFECT_SNOW:   for (int i=0;i<40;i++) spawnSnow(w, h, rng.nextFloat()*h); break;
        }
    }

    // ── Master draw ───────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (currentEffect == EFFECT_NONE) return;
        tick++;
        int w = getWidth(), h = getHeight();
        switch (currentEffect) {
            case EFFECT_HEARTS:      drawHearts(canvas, w, h);    break;
            case EFFECT_FIRE:        drawFire(canvas, w, h);      break;
            case EFFECT_SPARKS:      drawSparks(canvas, w, h);    break;
            case EFFECT_SNOW:        drawSnow(canvas, w, h);      break;
            case EFFECT_GLITCH:      drawGlitch(canvas, w, h);    break;
            case EFFECT_NEON:        drawNeon(canvas, w, h);      break;
            case EFFECT_VINTAGE:     drawVintage(canvas, w, h);   break;
            case EFFECT_AR_CROWN:    drawArCrown(canvas);         break;
            case EFFECT_AR_BIGEYES:  drawArBigEyes(canvas);       break;
            case EFFECT_AR_DOG:      drawArDog(canvas);           break;
        }
        postDelayed(loopRunnable, 16);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HEARTS  [x, y, vx, vy, alpha, scale]
    // ═══════════════════════════════════════════════════════════════════════════
    private void spawnHeart(int w, int h, float startY) {
        if (hearts.size() >= 20) return;
        hearts.add(new float[]{
            60 + rng.nextFloat()*(w-120), startY,
            (rng.nextFloat()-.5f)*1.8f,
            -(2.5f+rng.nextFloat()*2.5f),
            255f, 0.6f+rng.nextFloat()*0.9f
        });
    }

    private void drawHearts(Canvas canvas, int w, int h) {
        if (tick%8==0) spawnHeart(w, h, h+20);
        tp.setTextSize(48);
        for (int i=hearts.size()-1; i>=0; i--) {
            float[] p = hearts.get(i);
            p[0]+=p[2]; p[1]+=p[3]; p[4]-=2.5f;
            if (p[1]<-80||p[4]<=0) { hearts.remove(i); continue; }
            tp.setAlpha((int)Math.max(0,Math.min(255,p[4])));
            canvas.save();
            canvas.scale(p[5], p[5], p[0], p[1]);
            canvas.drawText(i%2==0?"❤️":"💗", p[0], p[1], tp);
            canvas.restore();
        }
        tp.setAlpha(255);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  FIRE  [x, y, vx, vy, life, maxLife, size]
    // ═══════════════════════════════════════════════════════════════════════════
    private void spawnFire(int w, int h, float startY) {
        if (fire.size() >= 140) return;
        float life = 40+rng.nextInt(50);
        fire.add(new float[]{
            rng.nextFloat()*w, startY,
            (rng.nextFloat()-.5f)*2.5f, -(1.5f+rng.nextFloat()*3.5f),
            life, life, 8+rng.nextFloat()*22
        });
    }

    private void drawFire(Canvas canvas, int w, int h) {
        if (tick%2==0) for (int i=0;i<5+rng.nextInt(4);i++) spawnFire(w,h,h+rng.nextFloat()*30);
        for (int i=fire.size()-1; i>=0; i--) {
            float[] p = fire.get(i);
            p[0]+=p[2]; p[1]+=p[3]; p[4]--;
            p[2]+=(rng.nextFloat()-.5f)*0.3f;
            if (p[4]<=0||p[1]<-60) { fire.remove(i); continue; }
            float lf = p[4]/p[5];
            int color;
            if      (lf>.75f) color = lerp(0xFFFFFF00, 0xFFFF8800, (1f-lf)*4f);
            else if (lf>.45f) color = lerp(0xFFFF8800, 0xFFFF2200, (.75f-lf)/.30f);
            else              color = lerp(0xFFFF2200, 0x88330000, (.45f-lf)/.45f);
            pp.setColor(color); pp.setAlpha((int)(lf*210));
            canvas.drawCircle(p[0], p[1], p[6]*lf*.7f+p[6]*.3f, pp);
        }
        pp.setAlpha(255);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SPARKS  [x, y, life, maxLife, phase, size]
    // ═══════════════════════════════════════════════════════════════════════════
    private void spawnSpark(int w, int h) {
        if (sparks.size() >= 70) return;
        float life = 30+rng.nextInt(60);
        sparks.add(new float[]{
            rng.nextFloat()*w, rng.nextFloat()*h,
            life, life, rng.nextFloat()*6.28f, 3+rng.nextFloat()*9
        });
    }

    private void drawSparks(Canvas canvas, int w, int h) {
        if (tick%4==0) for (int i=0;i<3;i++) spawnSpark(w,h);
        for (int i=sparks.size()-1; i>=0; i--) {
            float[] p = sparks.get(i);
            p[2]--;
            if (p[2]<=0) { sparks.remove(i); continue; }
            float lf = p[2]/p[3];
            float tw = (float)(Math.sin(p[4]+tick*0.25)*0.5+0.5);
            int alpha = (int)(tw*lf*255);
            int col = i%3==0 ? 0xFFFFD700 : i%3==1 ? 0xFFFFF8DC : 0xFFFFEC8B;
            pp.setColor(col); pp.setAlpha(alpha);
            float r = p[5]*lf, cx=p[0], cy=p[1];
            pp.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, r*.3f, pp);
            pp.setStyle(Paint.Style.STROKE); pp.setStrokeWidth(r*.15f);
            canvas.drawLine(cx-r,cy,cx+r,cy,pp);
            canvas.drawLine(cx,cy-r,cx,cy+r,pp);
            float d=r*.6f;
            canvas.drawLine(cx-d,cy-d,cx+d,cy+d,pp);
            canvas.drawLine(cx+d,cy-d,cx-d,cy+d,pp);
            pp.setStyle(Paint.Style.FILL);
        }
        pp.setAlpha(255);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SNOW  [x, y, vy, radius, phase, amp]
    // ═══════════════════════════════════════════════════════════════════════════
    private void spawnSnow(int w, int h, float startY) {
        if (snow.size() >= 90) return;
        snow.add(new float[]{
            rng.nextFloat()*w, startY,
            0.8f+rng.nextFloat()*2.2f, 2+rng.nextFloat()*6,
            rng.nextFloat()*6.28f, 20+rng.nextFloat()*40
        });
    }

    private void drawSnow(Canvas canvas, int w, int h) {
        if (tick%3==0) spawnSnow(w, h, -10);
        for (int i=snow.size()-1; i>=0; i--) {
            float[] p = snow.get(i);
            p[1]+=p[2];
            p[0]+=(float)(Math.sin(p[4]+tick*.05)*p[5]*.03);
            if (p[0]<-10) p[0]=w+10; if (p[0]>w+10) p[0]=-10;
            if (p[1]>h+20) { snow.remove(i); continue; }
            pp.setColor(0xFFFFFFFF); pp.setAlpha(160+rng.nextInt(80));
            canvas.drawCircle(p[0],p[1],p[3],pp);
            pp.setAlpha(255);
            canvas.drawCircle(p[0]-p[3]*.25f, p[1]-p[3]*.25f, p[3]*.35f, pp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GLITCH
    // ═══════════════════════════════════════════════════════════════════════════
    private void drawGlitch(Canvas canvas, int w, int h) {
        // Subtle scanlines every frame
        gp.setColor(0x0A000000); gp.setStyle(Paint.Style.FILL);
        for (int y=0; y<h; y+=4) canvas.drawRect(0,y,w,y+1,gp);

        if (tick >= nextGlitchAt) {
            glitchStrips.clear();
            for (int i=0; i<3+rng.nextInt(7); i++) {
                int sy=rng.nextInt(h), sh=2+rng.nextInt(28);
                int oR=(rng.nextBoolean()?1:-1)*(4+rng.nextInt(16));
                int oB=-oR+(rng.nextBoolean()?1:-1)*rng.nextInt(8);
                glitchStrips.add(new int[]{sy,sh,oR,oB});
            }
            glitchCooldown = 2+rng.nextInt(4);
            nextGlitchAt   = (int)tick+glitchCooldown+18+rng.nextInt(37);
        }
        if (glitchCooldown>0) {
            glitchCooldown--;
            for (int[] s : glitchStrips) {
                gp.setColor(Color.argb(90,255,0,0));   canvas.drawRect(s[2],s[0],w+s[2],s[0]+s[1],gp);
                gp.setColor(Color.argb(90,0,0,255));   canvas.drawRect(s[3],s[0],w+s[3],s[0]+s[1],gp);
                gp.setColor(Color.argb(40,255,255,255)); canvas.drawRect(0,s[0],w,s[0]+s[1],gp);
            }
            if (rng.nextInt(6)==0) {
                gp.setColor(Color.argb(60,255,255,255));
                int ny=rng.nextInt(h);
                canvas.drawRect(0,ny,w,ny+2+rng.nextInt(6),gp);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  NEON border
    // ═══════════════════════════════════════════════════════════════════════════
    private void drawNeon(Canvas canvas, int w, int h) {
        neonHue = (neonHue+1.2f)%360f;
        float pulse  = (float)(Math.sin(tick*.08)*.5+.5);
        float strokeW = 8f+pulse*14f;

        np.setStyle(Paint.Style.STROKE);
        float inset = strokeW*1.5f;

        np.setStrokeWidth(strokeW*2.5f);
        np.setColor(Color.HSVToColor(55, new float[]{neonHue,1f,1f}));
        canvas.drawRoundRect(inset,inset,w-inset,h-inset,24,24,np);

        np.setStrokeWidth(strokeW*1.4f); np.setAlpha(110);
        canvas.drawRoundRect(inset,inset,w-inset,h-inset,24,24,np);

        np.setStrokeWidth(strokeW*.5f); np.setAlpha(220);
        np.setColor(Color.HSVToColor(220, new float[]{(neonHue+30f)%360f,.7f,1f}));
        canvas.drawRoundRect(inset,inset,w-inset,h-inset,24,24,np);

        // Corner dots
        np.setStyle(Paint.Style.FILL); np.setAlpha(180);
        float dr=6f+pulse*6f;
        canvas.drawCircle(inset,inset,dr,np);
        canvas.drawCircle(w-inset,inset,dr,np);
        canvas.drawCircle(inset,h-inset,dr,np);
        canvas.drawCircle(w-inset,h-inset,dr,np);
        np.setAlpha(255);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  VINTAGE
    // ═══════════════════════════════════════════════════════════════════════════
    private void drawVintage(Canvas canvas, int w, int h) {
        // Warm sepia tint
        gp.setColor(0x14A0602A); gp.setStyle(Paint.Style.FILL);
        canvas.drawRect(0,0,w,h,gp);
        // Film grain – dark
        gp.setColor(0xFF000000);
        for (int i=0;i<GRAIN_COUNT;i++) {
            gp.setAlpha(8+rng.nextInt(50));
            float gx=rng.nextFloat()*w, gy=rng.nextFloat()*h, gs=.5f+rng.nextFloat()*1.5f;
            canvas.drawRect(gx,gy,gx+gs,gy+gs,gp);
        }
        // Film grain – bright
        gp.setColor(0xFFFFFFFF);
        for (int i=0;i<GRAIN_COUNT/5;i++) {
            gp.setAlpha(4+rng.nextInt(22));
            float gx=rng.nextFloat()*w, gy=rng.nextFloat()*h;
            canvas.drawRect(gx,gy,gx+1,gy+1,gp);
        }
        // Vignette
        if (vignetteGradient!=null) {
            vp.setShader(vignetteGradient);
            canvas.drawRect(0,0,w,h,vp);
            vp.setShader(null);
        }
        // Occasional flutter line
        if (rng.nextInt(3)==0) {
            gp.setColor(0xFFFFFFFF); gp.setAlpha(6+rng.nextInt(12));
            int ly=rng.nextInt(h);
            canvas.drawRect(0,ly,w,ly+1,gp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  AR EFFECTS — face-tracked emoji overlays
    // ═══════════════════════════════════════════════════════════════════════════

    private void drawArCrown(Canvas canvas) {
        if (mFaces == null || mFaces.length == 0) return;
        for (android.hardware.camera2.params.Face face : mFaces) {
            android.graphics.Rect bounds = face.getBounds();
            // Map face bounds corners via sensor→view transform
            float left   = mapFaceX(bounds.top);
            float right  = mapFaceX(bounds.bottom);
            float top    = mapFaceY(bounds.right);
            float bottom = mapFaceY(bounds.left);
            float faceW  = Math.abs(right - left);
            float cx     = (left + right) / 2f;
            float faceTop = Math.min(top, bottom);
            tp.setTextSize(faceW * 0.9f);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setAlpha(255);
            // Draw crown above the face
            canvas.drawText("👑", cx, faceTop - faceW * 0.1f, tp);
        }
    }

    private void drawArBigEyes(Canvas canvas) {
        if (mFaces == null || mFaces.length == 0) return;
        for (android.hardware.camera2.params.Face face : mFaces) {
            android.graphics.Rect bounds = face.getBounds();
            float faceW = Math.abs(mapFaceX(bounds.bottom) - mapFaceX(bounds.top));
            float eyeSize = faceW * 0.6f;
            tp.setTextSize(eyeSize);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setAlpha(255);
            // Left eye
            android.graphics.Point leftEye = face.getLeftEyePosition();
            if (leftEye != null) {
                float ex = mapFaceX(leftEye.y);
                float ey = mapFaceY(leftEye.x);
                canvas.drawText("👁", ex, ey + eyeSize * 0.35f, tp);
            }
            // Right eye
            android.graphics.Point rightEye = face.getRightEyePosition();
            if (rightEye != null) {
                float ex = mapFaceX(rightEye.y);
                float ey = mapFaceY(rightEye.x);
                canvas.drawText("👁", ex, ey + eyeSize * 0.35f, tp);
            }
        }
    }

    private void drawArDog(Canvas canvas) {
        if (mFaces == null || mFaces.length == 0) return;
        for (android.hardware.camera2.params.Face face : mFaces) {
            android.graphics.Rect bounds = face.getBounds();
            float left   = mapFaceX(bounds.top);
            float right  = mapFaceX(bounds.bottom);
            float top    = mapFaceY(bounds.right);
            float bottom = mapFaceY(bounds.left);
            float faceW  = Math.abs(right - left);
            float cx     = (left + right) / 2f;
            float faceTop = Math.min(top, bottom);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setAlpha(255);
            // Dog head/ears above face
            tp.setTextSize(faceW * 0.9f);
            canvas.drawText("🐶", cx, faceTop - faceW * 0.05f, tp);
            // Dog nose at mouth position
            android.graphics.Point mouth = face.getMouthPosition();
            if (mouth != null) {
                float mx = mapFaceX(mouth.y);
                float my = mapFaceY(mouth.x);
                tp.setTextSize(faceW * 0.5f);
                canvas.drawText("🐽", mx, my + faceW * 0.25f, tp);
            }
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────
    private static int lerp(int c1, int c2, float t) {
        t = Math.max(0f,Math.min(1f,t));
        return Color.argb(
            (int)(((c1>>24)&0xFF) + (((c2>>24)&0xFF)-((c1>>24)&0xFF))*t),
            (int)(((c1>>16)&0xFF) + (((c2>>16)&0xFF)-((c1>>16)&0xFF))*t),
            (int)(((c1>> 8)&0xFF) + (((c2>> 8)&0xFF)-((c1>> 8)&0xFF))*t),
            (int)(((c1    )&0xFF) + (((c2    )&0xFF)-((c1    )&0xFF))*t));
    }
}
