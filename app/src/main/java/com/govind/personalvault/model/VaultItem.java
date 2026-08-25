package com.govind.personalvault.model;

public final class VaultItem {
    public static final String PASSWORD = "password";
    public static final String NOTE = "note";
    public static final String CARD = "card";
    public static final String[] CATEGORIES = {
            "Personal", "Work", "Finance", "Shopping", "Social", "Travel", "Other"
    };

    public String id = "";
    public String kind = PASSWORD;
    public String title = "";
    public String username = "";
    public String secret = "";
    public String url = "";
    public String notes = "";
    public String category = "Personal";
    public boolean favorite;
    public String tags = "";
    public long createdAt;
    public long updatedAt;

    public VaultItem copy() {
        VaultItem copy = new VaultItem();
        copy.id = safe(id);
        copy.kind = safe(kind);
        copy.title = safe(title);
        copy.username = safe(username);
        copy.secret = safe(secret);
        copy.url = safe(url);
        copy.notes = safe(notes);
        copy.category = normalizeCategory(category);
        copy.favorite = favorite;
        copy.tags = safe(tags);
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public static boolean validKind(String value) {
        return PASSWORD.equals(value) || NOTE.equals(value) || CARD.equals(value);
    }

    public static String normalizeCategory(String value) {
        if (value == null) return "Personal";
        for (String category : CATEGORIES) {
            if (category.equalsIgnoreCase(value.trim())) return category;
        }
        return "Personal";
    }

    public String maskedSecret() {
        String digits = secret == null ? "" : secret.replaceAll("\\D", "");
        if (digits.length() < 4) return "••••";
        return "•••• " + digits.substring(digits.length() - 4);
    }

    public void clearSensitive() {
        title = "";
        username = "";
        secret = "";
        url = "";
        notes = "";
        tags = "";
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
