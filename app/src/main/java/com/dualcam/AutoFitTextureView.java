package com.dualcam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {

    private int ratioW = 0, ratioH = 0;

    public AutoFitTextureView(Context c) { super(c); }
    public AutoFitTextureView(Context c, AttributeSet a) { super(c, a); }
    public AutoFitTextureView(Context c, AttributeSet a, int d) { super(c, a, d); }

    public void setAspectRatio(int w, int h) {
        if (w < 0 || h < 0) throw new IllegalArgumentException("Negative dimension");
        ratioW = w;
        ratioH = h;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h);
        } else {
            // Mantiene ratio sin distorsión, fill width
            int newH = w * ratioH / ratioW;
            if (newH <= h) {
                setMeasuredDimension(w, newH);
            } else {
                setMeasuredDimension(h * ratioW / ratioH, h);
            }
        }
    }
}
