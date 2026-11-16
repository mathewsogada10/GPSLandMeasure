package com.mogada.landmeasure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class BoundaryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Models.Point> points;
    private final String label;
    private final float density;
    private final double[] x, y;
    private final Path path = new Path();
    private final DashPathEffect dashed;
    private final AppTheme.Palette colors;

    public BoundaryView(Context context) { this(context, List.of()); }

    BoundaryView(Context context, List<Models.Point> points) {
        super(context);
        colors = new AppTheme.Palette(context);
        this.points = new ArrayList<>(points);
        label = context.getString(R.string.canvas_description, points.size());
        setContentDescription(label);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        density = getResources().getDisplayMetrics().density;
        x = new double[points.size()]; y = new double[points.size()];
        dashed = new DashPathEffect(new float[]{8 * density, 6 * density}, 0);
        setMinimumHeight((int) (240 * density));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(colors.canvasBackground);
        paint.setStrokeWidth(density);
        paint.setColor(colors.canvasGrid);
        for (float x = 0; x < getWidth(); x += 32 * density) canvas.drawLine(x, 0, x, getHeight(), paint);
        for (float y = 0; y < getHeight(); y += 32 * density) canvas.drawLine(0, y, getWidth(), y, paint);
        if (points.isEmpty()) {
            paint.setColor(colors.text); paint.setTextSize(14 * density);
            canvas.drawText(getContext().getString(R.string.canvas_empty), 20 * density, getHeight() / 2f, paint);
            return;
        }
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
        double scale = Math.min((getWidth() - 64 * density) / Math.max(0.00001, maxX - minX),
                (getHeight() - 64 * density) / Math.max(0.00001, maxY - minY));
        double midX = (minX + maxX) / 2, midY = (minY + maxY) / 2;
        path.reset();
        for (int i = 0; i < x.length; i++) {
            x[i] = getWidth() / 2d + (x[i] - midX) * scale;
            y[i] = getHeight() / 2d - (y[i] - midY) * scale;
            if (i == 0) path.moveTo((float) x[i], (float) y[i]);
            else path.lineTo((float) x[i], (float) y[i]);
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
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(12 * density);
        for (int i = 0; i < x.length; i++) {
            canvas.drawCircle((float) x[i], (float) y[i], (i == 0 ? 6 : 4) * density, paint);
            if (x.length < 60) canvas.drawText(Integer.toString(i + 1), (float) x[i] + 8 * density,
                    (float) y[i] - 6 * density, paint);
        }
    }
}
