package com.dualcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class StickerView extends View {

    public static class Sticker {
        String emoji;
        float x, y;
        float size;
        boolean selected;

        Sticker(String emoji, float x, float y) {
            this.emoji = emoji;
            this.x = x;
            this.y = y;
            this.size = 72f;
        }
    }

    private final List<Sticker> stickers = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Sticker dragging = null;
    private float lastTouchX, lastTouchY;

    public StickerView(Context context) { super(context); init(); }
    public StickerView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        paint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void addSticker(String emoji) {
        float cx = getWidth() > 0 ? getWidth() / 2f : 300f;
        float cy = getHeight() > 0 ? getHeight() / 2f : 400f;
        stickers.add(new Sticker(emoji, cx, cy));
        invalidate();
    }

    public void clearStickers() {
        stickers.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (Sticker s : stickers) {
            paint.setTextSize(s.size);
            // Sombra sutil
            paint.setColor(Color.BLACK);
            paint.setAlpha(80);
            canvas.drawText(s.emoji, s.x + 2, s.y + 2, paint);
            // Sticker
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            canvas.drawText(s.emoji, s.x, s.y, paint);
            // Borde si seleccionado
            if (s.selected) {
                Paint border = new Paint();
                border.setStyle(Paint.Style.STROKE);
                border.setColor(Color.WHITE);
                border.setStrokeWidth(2f);
                canvas.drawCircle(s.x, s.y - s.size / 4, s.size * 0.7f, border);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = findSticker(x, y);
                if (dragging != null) {
                    for (Sticker s : stickers) s.selected = false;
                    dragging.selected = true;
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }
                for (Sticker s : stickers) s.selected = false;
                invalidate();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging != null) {
                    dragging.x += x - lastTouchX;
                    dragging.y += y - lastTouchY;
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                dragging = null;
                break;
        }
        return super.onTouchEvent(event);
    }

    private Sticker findSticker(float x, float y) {
        for (int i = stickers.size() - 1; i >= 0; i--) {
            Sticker s = stickers.get(i);
            float dx = x - s.x;
            float dy = y - s.y;
            if (Math.sqrt(dx * dx + dy * dy) < s.size) return s;
        }
        return null;
    }

    public void removeSelected() {
        stickers.removeIf(s -> s.selected);
        invalidate();
    }

    public void scaleSelected(float factor) {
        for (Sticker s : stickers) {
            if (s.selected) {
                s.size = Math.max(30f, Math.min(200f, s.size * factor));
            }
        }
        invalidate();
    }
}
