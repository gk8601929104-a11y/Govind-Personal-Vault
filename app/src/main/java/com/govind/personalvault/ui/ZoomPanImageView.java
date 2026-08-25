package com.govind.personalvault.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;
import android.widget.ImageView;

/**
 * Pinch-zoom, pan, double-tap zoom, and edge swipe. Bitmap stays in memory only.
 */
public final class ZoomPanImageView extends ImageView {
    public interface SwipeListener {
        void onSwipeNext();
        void onSwipePrevious();
    }

    public interface TapListener {
        void onSingleTap();
    }

    private static final float MAX_SCALE = 8f;
    private static final float DOUBLE_TAP_SCALE = 2.6f;
    private static final float SWIPE_MIN_PX = 96f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final PointF last = new PointF();

    private SwipeListener swipeListener;
    private TapListener tapListener;
    private boolean swipeEnabled = true;
    private float minScale = 1f;
    private float currentScale = 1f;
    private int contentWidth;
    private int contentHeight;

    public ZoomPanImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float next = clamp(currentScale * factor, minScale, MAX_SCALE);
                factor = next / currentScale;
                currentScale = next;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                clampToView();
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (tapListener != null) tapListener.onSingleTap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (currentScale > minScale * 1.08f) {
                    fitToView();
                } else {
                    float target = Math.min(MAX_SCALE, minScale * DOUBLE_TAP_SCALE);
                    float factor = target / currentScale;
                    currentScale = target;
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    clampToView();
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!swipeEnabled || swipeListener == null || e1 == null || e2 == null) return false;
                if (currentScale > minScale * 1.05f) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < SWIPE_MIN_PX || Math.abs(dx) < Math.abs(dy) * 1.4f) return false;
                if (Math.abs(velocityX) < 400f) return false;
                if (dx < 0f) swipeListener.onSwipeNext();
                else swipeListener.onSwipePrevious();
                return true;
            }
        });
    }

    public void setSwipeListener(SwipeListener listener) { swipeListener = listener; }

    public void setTapListener(TapListener listener) { tapListener = listener; }

    public void setSwipeEnabled(boolean enabled) { swipeEnabled = enabled; }

    public boolean isZoomed() { return currentScale > minScale * 1.05f; }

    @Override public void setImageBitmap(Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        if (bitmap == null || bitmap.isRecycled()) {
            contentWidth = 0;
            contentHeight = 0;
            matrix.reset();
            setImageMatrix(matrix);
            return;
        }
        contentWidth = bitmap.getWidth();
        contentHeight = bitmap.getHeight();
        post(this::fitToView);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (contentWidth > 0 && contentHeight > 0) fitToView();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (contentWidth <= 0 || contentHeight <= 0) return super.onTouchEvent(event);
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                last.set(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - last.x;
                    float dy = event.getY() - last.y;
                    last.set(event.getX(), event.getY());
                    if (currentScale > minScale * 1.02f) {
                        matrix.postTranslate(dx, dy);
                        clampToView();
                        setImageMatrix(matrix);
                    }
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void fitToView() {
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0 || contentWidth <= 0 || contentHeight <= 0) return;
        float scale = Math.min(viewW / (float) contentWidth, viewH / (float) contentHeight);
        if (scale <= 0f) scale = 1f;
        minScale = scale;
        currentScale = scale;
        matrix.reset();
        matrix.postScale(scale, scale);
        float dx = (viewW - contentWidth * scale) / 2f;
        float dy = (viewH - contentHeight * scale) / 2f;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    private void clampToView() {
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        matrix.getValues(values);
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scaleX = values[Matrix.MSCALE_X];
        float scaleY = values[Matrix.MSCALE_Y];
        float contentW = contentWidth * scaleX;
        float contentH = contentHeight * scaleY;

        float minX;
        float maxX;
        if (contentW <= viewW) {
            minX = maxX = (viewW - contentW) / 2f;
        } else {
            minX = viewW - contentW;
            maxX = 0f;
        }
        float minY;
        float maxY;
        if (contentH <= viewH) {
            minY = maxY = (viewH - contentH) / 2f;
        } else {
            minY = viewH - contentH;
            maxY = 0f;
        }
        float dx = 0f;
        float dy = 0f;
        if (transX < minX) dx = minX - transX;
        else if (transX > maxX) dx = maxX - transX;
        if (transY < minY) dy = minY - transY;
        else if (transY > maxY) dy = maxY - transY;
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
