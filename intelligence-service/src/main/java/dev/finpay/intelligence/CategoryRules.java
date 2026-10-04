package dev.finpay.intelligence;

import java.util.Locale;

/** Transparent baseline classifier, not a trained AI model. */
public final class CategoryRules {
    private CategoryRules() { }

    public static String categorize(String description) {
        if (description == null || description.isBlank()) return "OTHER";
        String normalized = description.toUpperCase(Locale.ROOT);
        if (normalized.contains("SWIGGY") || normalized.contains("ZOMATO")) return "FOOD";
        if (normalized.contains("AMAZON")) return "SHOPPING";
        if (normalized.contains("UBER") || normalized.contains("OLA ")) return "TRAVEL";
        if (normalized.contains("ELECTRICITY") || normalized.contains("BROADBAND")) return "BILLS";
        return "OTHER";
    }
}
