package com.mogada.landmeasure.ui;

import com.mogada.landmeasure.R;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;

public final class AppTheme {
    public static final String SYSTEM = "SYSTEM", LIGHT = "LIGHT", DARK = "DARK";
    static final String[] MODES = {SYSTEM, LIGHT, DARK};

    static int style(String mode, Configuration configuration) {
        return isDark(mode, configuration) ? R.style.Theme_LandMeasure_Dark : R.style.Theme_LandMeasure;
    }

    static boolean isDark(String mode, Configuration configuration) {
        return switch (mode) {
            case SYSTEM -> (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            case LIGHT -> false;
            case DARK -> true;
            default -> throw new IllegalArgumentException("Unknown display mode");
        };
    }

    public static final class Palette {
        public final int background;
        final int surface, primary, onPrimary, text, muted, outline;
        final int warningBackground, warningText, canvasBackground, canvasGrid, canvasLine, canvasFill;

        public Palette(Context context) {
            background = color(context, R.attr.lmBackground);
            surface = color(context, R.attr.lmSurface);
            primary = color(context, R.attr.lmPrimary);
            onPrimary = color(context, R.attr.lmOnPrimary);
            text = color(context, R.attr.lmText);
            muted = color(context, R.attr.lmMuted);
            outline = color(context, R.attr.lmOutline);
            warningBackground = color(context, R.attr.lmWarningBackground);
            warningText = color(context, R.attr.lmWarningText);
            canvasBackground = color(context, R.attr.lmCanvasBackground);
            canvasGrid = color(context, R.attr.lmCanvasGrid);
            canvasLine = color(context, R.attr.lmCanvasLine);
            canvasFill = color(context, R.attr.lmCanvasFill);
        }

        private static int color(Context context, int attribute) {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attribute, value, true))
                throw new IllegalStateException("Missing theme color");
            return value.resourceId == 0 ? value.data : context.getColor(value.resourceId);
        }
    }

    private AppTheme() {}
}
