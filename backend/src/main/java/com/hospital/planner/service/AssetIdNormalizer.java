package com.hospital.planner.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical form: PREFIX-0000. "vent 1", "VENT_0001", "Vent-001" all become VENT-0001. */
public final class AssetIdNormalizer {
    private static final Pattern P = Pattern.compile("^([A-Za-z]+)[\\s_\\-]*0*(\\d+)$");

    private AssetIdNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m = P.matcher(s);
        if (m.matches()) {
            String digits = String.format("%4s", m.group(2)).replace(' ', '0');
            return m.group(1).toUpperCase() + "-" + digits;
        }
        String flat = s.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return flat.isEmpty() ? null : flat;
    }
}
