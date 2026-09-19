package com.tongsir.naviplayer;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String P = "navi";
    private static final String K_URL = "url";
    private static final String K_USER = "user";
    private static final String K_PASS = "pass";
    private static final String K_SHUFFLE = "shuffle";
    private static final String K_AUTOSTART = "autostart";
    private static final String K_OFFSET = "lrcOffset";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public boolean configured() {
        return url() != null && url().length() > 0 && user() != null && user().length() > 0;
    }

    public String url() { return trimSlash(sp.getString(K_URL, "")); }
    public String user() { return sp.getString(K_USER, ""); }
    public String pass() { return sp.getString(K_PASS, ""); }

    public void save(String url, String user, String pass) {
        sp.edit().putString(K_URL, trimSlash(url)).putString(K_USER, user)
                .putString(K_PASS, pass).apply();
    }

    public boolean shuffle() { return sp.getBoolean(K_SHUFFLE, false); }
    public void setShuffle(boolean b) { sp.edit().putBoolean(K_SHUFFLE, b).apply(); }

    public boolean autostart() { return sp.getBoolean(K_AUTOSTART, false); }
    public void setAutostart(boolean b) { sp.edit().putBoolean(K_AUTOSTART, b).apply(); }

    /** 歌词整体偏移，毫秒 */
    public int lrcOffset() { return sp.getInt(K_OFFSET, 0); }
    public void setLrcOffset(int ms) { sp.edit().putInt(K_OFFSET, ms).apply(); }

    private static String trimSlash(String s) {
        if (s == null) return "";
        String r = s.trim();
        // N13：保留 "http://" 结尾的双斜杠，只剥多余的单个尾部斜杠
        while (r.endsWith("/") && !r.endsWith("//")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
