package com.smbplayer.tv;

import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PrivateLibrary {
    private final SharedPreferences settings;
    PrivateLibrary(SharedPreferences settings) { this.settings = settings; }
    boolean hasPin() { return settings.contains("pin"); }

    boolean verify(String pin, boolean create) throws Exception {
        if (create) return !hasPin() && savePin(pin);
        if (!hasPin()) return false;
        byte[] salt = Base64.decode(settings.getString("salt", ""), Base64.DEFAULT);
        return MessageDigest.isEqual(hash(pin, salt), Base64.decode(settings.getString("pin", ""), Base64.DEFAULT));
    }

    boolean savePin(String pin) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return settings.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)).commit();
    }

    boolean clearPin() {
        return settings.edit().remove("pin").remove("salt").remove("marks").remove("records").commit();
    }

    private static byte[] hash(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 180000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    Set<String> marks() { return new LinkedHashSet<>(settings.getStringSet("marks", java.util.Collections.emptySet())); }

    static String key(String url) {
        Uri uri = Uri.parse(url);
        if ("content".equals(uri.getScheme())) {
            String id;
            try { id = DocumentsContract.getDocumentId(uri); }
            catch (IllegalArgumentException e) { id = DocumentsContract.getTreeDocumentId(uri); }
            return "local:" + uri.getAuthority() + ":" + id.replaceAll("/+$", "");
        }
        if ("smb".equals(uri.getScheme())) {
            String host = uri.getHost();
            int port = uri.getPort() < 0 ? 445 : uri.getPort();
            String path = uri.getPath();
            return "smb:" + (host == null ? "" : host.toLowerCase(Locale.ROOT)) + ":" + port
                    + (path == null ? "" : path.replaceAll("/+$", "").toLowerCase(Locale.ROOT));
        }
        return url;
    }

    static boolean under(String key, String root) {
        return key.equals(root) || key.startsWith(root + "/") || root.endsWith(":") && key.startsWith(root);
    }

    boolean hidden(String key, Set<String> marks) {
        for (String mark : marks) if (under(key, mark)) return true;
        return false;
    }

    void toggle(String key) {
        Set<String> keys = marks();
        if (!keys.remove(key)) keys.add(key);
        settings.edit().putStringSet("marks", keys).apply();
    }

    void remap(String from, String to) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : marks()) {
            if (!under(key, from)) keys.add(key);
            else if (to != null) keys.add(to + key.substring(from.length()));
        }
        settings.edit().putStringSet("marks", keys).apply();
    }
}
