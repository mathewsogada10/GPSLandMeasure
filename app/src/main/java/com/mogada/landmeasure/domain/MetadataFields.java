package com.mogada.landmeasure.domain;

public final class MetadataFields {
    public static final String[] KEYS = {
        "land", "owner", "phone", "email", "identifier_label", "identifier", "consent",
        "usage", "usage_text", "location", "county", "subcounty", "ward", "village",
        "landmark", "reference", "reference_source", "boundary", "access"
    };
    public static final String[] USAGES = {"agricultural", "residential", "commercial", "conservation", "mixed", "other"};

    public static int limit(String key) {
        return switch (key) {
            case "consent", "location", "boundary", "access", "usage_text" -> 2000;
            default -> 200;
        };
    }

    private MetadataFields() {}
}
