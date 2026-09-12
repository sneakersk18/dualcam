package com.dualcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class CircleFrameLayout extends FrameLayout {

    private final Path clipPath = new Path();
    private final RectF rect = new RectF();

    public CircleFrameLayout(Context c) { super(c); setWillNotDraw(false); }
    public CircleFrameLayout(Context c, AttributeSet a) { super(c, a); setWillNotDraw(false); }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float r = Math.min(w, h) / 2f;
        rect.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(clipPath);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
