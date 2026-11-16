package com.mogada.landmeasure;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class MapDirections {
    static Intent intent(Models.Point point) {
        Uri destination = new Uri.Builder().scheme("https").authority("www.google.com")
                .path("/maps/dir/").appendQueryParameter("api", "1")
                .appendQueryParameter("destination", Double.toString(point.lat) + "," + Double.toString(point.lon))
                .appendQueryParameter("dir_action", "navigate").build();
        return new Intent(Intent.ACTION_VIEW, destination).setPackage("com.google.android.apps.maps");
    }

    static void open(Context context, Models.Point point) {
        Intent maps = intent(point);
        try {
            context.startActivity(maps);
        } catch (ActivityNotFoundException missingMaps) {
            context.startActivity(new Intent(maps).setPackage(null));
        }
    }

    private MapDirections() {}
}
