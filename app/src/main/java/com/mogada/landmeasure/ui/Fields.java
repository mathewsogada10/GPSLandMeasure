package com.mogada.landmeasure.ui;

import com.mogada.landmeasure.R;

final class Fields {
    static final String[] FORM_KEYS = {"owner", "phone", "email", "consent", "usage", "location"};
    static final int[] FORM_LABELS = {R.string.owner, R.string.phone, R.string.email, R.string.consent,
            R.string.usage, R.string.location};
    static final int[] LABELS = {
        R.string.land_label, R.string.owner, R.string.phone, R.string.email,
        R.string.identifier_label, R.string.identifier, R.string.consent,
        R.string.usage, R.string.usage_text, R.string.location, R.string.county,
        R.string.subcounty, R.string.ward, R.string.village, R.string.landmark,
        R.string.reference, R.string.reference_source, R.string.boundary, R.string.access
    };
    private Fields() {}
}
