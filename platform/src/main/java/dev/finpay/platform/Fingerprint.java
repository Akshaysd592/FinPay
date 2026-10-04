package dev.finpay.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Fingerprint {
    private Fingerprint() { }

    public static String of(Object... values) {
        var canonical = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            canonical.append(text.length()).append(':').append(text);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM must support SHA-256", exception);
        }
    }
}
