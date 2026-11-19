package com.mogada.landmeasure.ui;

import com.mogada.landmeasure.R;
import com.mogada.landmeasure.domain.Geometry;
import com.mogada.landmeasure.domain.Models;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class BoundaryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Models.Point> points;
    private final String label;
    private final float density;
    private final double[] x, y;
    private final Path path = new Path();
    private final DashPathEffect dashed;
    private final AppTheme.Palette colors;
    private final int touchSlop;
    private float downX, downY;
    private int touchedEdge = -1, pendingEdge = -1, selectedEdge = -1;
    private boolean trackingTouch;
    private EdgeListener edgeListener;

    interface EdgeListener { void selected(int from, int to, double metres); }

    void setEdgeListener(EdgeListener listener) { edgeListener = listener; }

    public BoundaryView(Context context) { this(context, List.of()); }

    public BoundaryView(Context context, List<Models.Point> points) {
        super(context);
        colors = new AppTheme.Palette(context);
        this.points = new ArrayList<>(points);
        label = context.getString(R.string.canvas_description, points.size());
        setContentDescription(label);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        density = getResources().getDisplayMetrics().density;
        x = new double[points.size()]; y = new double[points.size()];
        dashed = new DashPathEffect(new float[]{8 * density, 6 * density}, 0);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(edgeCount() > 0);
        setFocusable(edgeCount() > 0);
        setMinimumHeight((int) (240 * density));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        project();
    }

    private void project() {
        if (points.isEmpty()) return;
        double origin = points.get(0).lon;
        double factor = Math.max(0.01, Math.cos(Math.toRadians(points.get(0).lat)));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            Models.Point p = points.get(i);
            origin = Geometry.unwrap(p.lon, origin);
            x[i] = origin * factor; y[i] = p.lat;
            minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]); maxY = Math.max(maxY, y[i]);
        }
        double scale = Math.max(0, Math.min((getWidth() - 64 * density) / Math.max(0.00001, maxX - minX),
                (getHeight() - 64 * density) / Math.max(0.00001, maxY - minY)));
        double midX = (minX + maxX) / 2, midY = (minY + maxY) / 2;
        path.reset();
        for (int i = 0; i < x.length; i++) {
            x[i] = getWidth() / 2d + (x[i] - midX) * scale;
            y[i] = getHeight() / 2d - (y[i] - midY) * scale;
            if (i == 0) path.moveTo((float) x[i], (float) y[i]);
            else path.lineTo((float) x[i], (float) y[i]);
        }
    }

    private int edgeCount() { return points.size() > 2 ? points.size() : Math.max(0, points.size() - 1); }

    private int edgeAt(float touchX, float touchY) {
        if (getWidth() <= 0 || getHeight() <= 0) return -1;
        double best = 24 * density;
        int edge = -1;
        for (int i = 0; i < edgeCount(); i++) {
            int next = (i + 1) % points.size();
            double dx = x[next] - x[i], dy = y[next] - y[i];
            double lengthSquared = dx * dx + dy * dy;
            double fraction = lengthSquared == 0 ? 0
                    : Math.max(0, Math.min(1, ((touchX - x[i]) * dx + (touchY - y[i]) * dy) / lengthSquared));
            double distance = Math.hypot(touchX - x[i] - fraction * dx, touchY - y[i] - fraction * dy);
            if (distance < best) { best = distance; edge = i; }
        }
        return edge;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || edgeCount() == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                touchedEdge = edgeAt(event.getX(), event.getY());
                trackingTouch = touchedEdge >= 0;
                downX = event.getX(); downY = event.getY();
                setPressed(trackingTouch);
                return trackingTouch;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false;
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    touchedEdge = -1; setPressed(false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return false;
                trackingTouch = false; setPressed(false);
                if (touchedEdge >= 0 && Math.hypot(event.getX() - downX, event.getY() - downY) <= touchSlop) {
                    pendingEdge = touchedEdge;
                    performClick();
                }
                touchedEdge = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false; touchedEdge = -1; setPressed(false);
                return true;
            }
            default -> { return super.onTouchEvent(event); }
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (edgeCount() == 0) return false;
        selectedEdge = pendingEdge >= 0 ? pendingEdge : (selectedEdge + 1) % edgeCount();
        pendingEdge = -1;
        int next = (selectedEdge + 1) % points.size();
        if (edgeListener != null)
            edgeListener.selected(selectedEdge, next, Geometry.distance(points.get(selectedEdge), points.get(next)));
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(colors.canvasBackground);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(density);
        paint.setColor(colors.canvasGrid);
        for (float x = 0; x < getWidth(); x += 32 * density) canvas.drawLine(x, 0, x, getHeight(), paint);
        for (float y = 0; y < getHeight(); y += 32 * density) canvas.drawLine(0, y, getWidth(), y, paint);
        if (points.isEmpty()) {
            paint.setColor(colors.text); paint.setTextSize(14 * density);
            canvas.drawText(getContext().getString(R.string.canvas_empty), 20 * density, getHeight() / 2f, paint);
            return;
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(colors.canvasFill);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setColor(colors.canvasLine); paint.setStrokeWidth(3 * density);
        canvas.drawPath(path, paint);
        if (points.size() > 2) {
            paint.setPathEffect(dashed);
            canvas.drawLine((float) x[x.length - 1], (float) y[y.length - 1], (float) x[0], (float) y[0], paint);
            paint.setPathEffect(null);
        }
        if (selectedEdge >= 0) {
            int next = (selectedEdge + 1) % points.size();
            paint.setColor(colors.text); paint.setStrokeWidth(6 * density);
            canvas.drawLine((float) x[selectedEdge], (float) y[selectedEdge], (float) x[next], (float) y[next], paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(12 * density); paint.setColor(colors.canvasLine);
        for (int i = 0; i < x.length; i++) {
            canvas.drawCircle((float) x[i], (float) y[i], (i == 0 ? 6 : 4) * density, paint);
            if (x.length < 60) canvas.drawText(Integer.toString(i + 1), (float) x[i] + 8 * density,
                    (float) y[i] - 6 * density, paint);
        }
    }
}
