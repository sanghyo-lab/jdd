package com.jdd.commerce.common;

public final class Inputs {
    private Inputs() {}

    public static String identifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw CommerceException.invalid(name + " must contain 1 to 128 non-control characters");
        }
        return value;
    }

    public static void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw CommerceException.invalid("limit must be 1..100 and offset must be non-negative");
        }
    }
}
