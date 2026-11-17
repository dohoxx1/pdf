package com.example.comicviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

public class ReaderView extends View {

    public interface Listener {
        void onTap(float xFraction);
        /** toRight: 손가락이 오른쪽으로 움직임 */
        void onSwipe(boolean toRight);
        void onSized();
    }

    private Listener listener;
    private Bitmap pageA, pageB;
    private boolean two, rtl, center;
    private float scale = 1f, tx = 0f, ty = 0f;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gd;
    private final ScaleGestureDetector sd;

    public ReaderView(Context c) {
        super(c);
        textPaint.setColor(0xFFCCCCCC);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(22f * c.getResources().getDisplayMetrics().density);

        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null && getWidth() > 0) listener.onTap(e.getX() / getWidth());
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale > 1.01f) { scale = 1f; tx = 0; ty = 0; }
                else zoomTo(2.5f, e.getX(), e.getY());
                invalidate();
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale > 1.01f) {
                    tx -= dx; ty -= dy;
                    clamp();
                    invalidate();
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scale <= 1.05f && Math.abs(vx) > 600 && Math.abs(vx) > Math.abs(vy) && listener != null) {
                    listener.onSwipe(vx > 0);
                    return true;
                }
                return false;
            }
        });

        sd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomTo(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                invalidate();
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void setContent(Bitmap a, Bitmap b, boolean two, boolean rtl, boolean center) {
        this.pageA = a; this.pageB = b;
        this.two = two; this.rtl = rtl; this.center = center;
        scale = 1f; tx = 0; ty = 0;
        invalidate();
    }

    private void zoomTo(float ns, float fx, float fy) {
        ns = Math.max(1f, Math.min(4f, ns));
        tx = fx - (fx - tx) * ns / scale;
        ty = fy - (fy - ty) * ns / scale;
        scale = ns;
        clamp();
    }

    private void clamp() {
        float minX = getWidth() - getWidth() * scale;
        float minY = getHeight() - getHeight() * scale;
        tx = Math.max(minX, Math.min(0, tx));
        ty = Math.max(minY, Math.min(0, ty));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (listener != null) listener.onSized();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sd.onTouchEvent(e);
        if (!sd.isInProgress()) gd.onTouchEvent(e);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xFF111111);
        float W = getWidth(), H = getHeight();
        if (pageA == null && pageB == null) {
            c.drawText("메뉴의 '열기'로 PDF를 선택하세요", W / 2, H / 2, textPaint);
            return;
        }
        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale);
        if (!two) {
            drawFit(c, pageA, 0, W, W, H, 0);
        } else if (pageB == null && center) {
            drawFit(c, pageA, 0, W, W / 2, H, 0);
        } else {
            float half = W / 2;
            // 첫 페이지(pageA): 우→좌면 오른쪽, 좌→우면 왼쪽. 책등(가운데) 쪽으로 붙임.
            if (rtl) {
                drawFit(c, pageA, half, half, half, H, -1);
                drawFit(c, pageB, 0, half, half, H, +1);
            } else {
                drawFit(c, pageA, 0, half, half, H, +1);
                drawFit(c, pageB, half, half, half, H, -1);
            }
        }
        c.restore();
    }

    /** align: -1 = 영역 왼쪽, +1 = 영역 오른쪽, 0 = 가운데 */
    private void drawFit(Canvas c, Bitmap b, float x0, float regionW, float fitW, float H, int align) {
        if (b == null) return;
        float k = Math.min(fitW / b.getWidth(), H / b.getHeight());
        float dw = b.getWidth() * k, dh = b.getHeight() * k;
        float x = align < 0 ? x0 : (align > 0 ? x0 + regionW - dw : x0 + (regionW - dw) / 2);
        float y = (H - dh) / 2;
        c.drawBitmap(b, null, new android.graphics.RectF(x, y, x + dw, y + dh), paint);
    }
}
