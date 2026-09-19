package com.tongsir.naviplayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class SubsonicClient {

    private static final String TAG = "Navi";
    private static final String API = "1.16.1";
    private static final String CLIENT = "NaviPlayer";
    /** 单次响应上限 8MB，防止异常响应撑爆内存 */
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int TIMEOUT_CONNECT = 8000;
    private static final int TIMEOUT_READ = 15000;
    /**
     * R12：歌词是“可回退”请求——失败会自动转 LRCLIB，所以宁可快速失败，
     * 也不要拖住歌词任务队列。实测 Navidrome 对无歌词的歌要十几秒才应答，
     * 用 15s 超时会让每首歌白等一轮。
     */
    private static final int TIMEOUT_LYRICS = 6000;
    /**
     * 延迟探测是每秒一次的“心跳”，必须比正常请求更快地给出结论：
     * 超时阈值压在 2s 以内，配合调用方的“上一次没回来就不发下一次”，
     * 不会因链路卡死而堆积请求。
     */
    private static final int TIMEOUT_PING_CONNECT = 2000;
    private static final int TIMEOUT_PING_READ = 2000;

    private final String base;
    private final String user;
    private final String pass;
    private final String auth;

    public SubsonicClient(String base, String user, String pass) {
        this.base = base;
        this.user = user;
        this.pass = pass;
        // 低21：salt 随机化，避免 100 秒窗口内 token 复用
        String salt = "navi" + java.util.UUID.randomUUID().toString().substring(0, 8);
        this.auth = "&u=" + enc(user) + "&t=" + md5(pass + salt) + "&s=" + enc(salt);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                String h = Integer.toHexString(x & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String url(String view, String extra) {
        return base + "/rest/" + view + "?v=" + API + "&c=" + CLIENT + "&f=json" + auth
                + (extra == null ? "" : extra);
    }

    private String get(String u) throws Exception {
        // N4：复用 bytes()，避免超时/限流/关流逻辑三处重复
        byte[] b = bytes(u);
        if (b == null) throw new Exception("HTTP failed");
        return new String(b, "UTF-8");
    }


    /**
     * 取一批歌曲作为播放队列。Navidrome 单次上限 500。
     */
    public List<Song> getSongs(int size) throws Exception {
        String s = get(url("getRandomSongs.view", "&size=" + size));
        JSONObject o = new JSONObject(s).getJSONObject("subsonic-response");
        List<Song> list = new ArrayList<Song>();
        if (!o.has("randomSongs")) return list;
        JSONArray arr = o.getJSONObject("randomSongs").optJSONArray("song");
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject j = arr.getJSONObject(i);
            // R7：optString(key, default) 只在“键缺失”时返回默认值，
            // 键存在但值为空串时照样返回空串（Navidrome 里很常见），
            // 会让界面出现空标题/空歌手，这里显式再兜一层
            String id = nz(j.optString("id"), "");
            if (id.length() == 0) continue; // 无 id 无法播放，直接跳过
            String title = nz(j.optString("title"), "");
            String artist = nz(j.optString("artist"), "");
            int durSec = Math.max(0, j.optInt("duration", 0));
            list.add(new Song(
                    id,
                    title.length() > 0 ? title : "未知标题",
                    artist.length() > 0 ? artist : "未知艺术家",
                    j.optString("album", ""),
                    j.optString("coverArt", ""),
                    durSec * 1000,
                    bitRateOf(j, durSec),
                    formatOf(j)));
        }
        return list;
    }

    /**
     * 码率（kbps）。优先用服务端给的 bitRate；缺失时用「文件大小 / 时长」反推，
     * 保证界面上码率一栏尽量有值。
     */
    private static int bitRateOf(JSONObject j, int durSec) {
        int br = Math.max(0, j.optInt("bitRate", 0));
        if (br > 0) return br;
        if (durSec <= 0) return 0;
        long size = j.optLong("size", 0);
        if (size <= 0) return 0;
        // size 为字节，durSec 为秒 → 字节*8/秒/1000 = kbps
        long kbps = size * 8L / durSec / 1000L;
        if (kbps <= 0 || kbps > 100000L) return 0;
        return (int) kbps;
    }

    /**
     * 原始文件格式。suffix 就是文件扩展名（flac/mp3/m4a…），最准确；
     * 缺失时退到 contentType（audio/flac）。
     */
    private static String formatOf(JSONObject j) {
        String s = nz(j.optString("suffix", ""), "");
        if (s.length() == 0) {
            String ct = nz(j.optString("contentType", ""), "");
            int i = ct.indexOf('/');
            if (i >= 0) s = ct.substring(i + 1);
        }
        s = s.trim().toUpperCase(java.util.Locale.US);
        while (s.startsWith(".")) s = s.substring(1);
        // 异常长串会撑破界面上的格式标签
        if (s.length() > 5) s = s.substring(0, 5);
        return s;
    }

    /** R7：null / 纯空白一律视为空串，交给调用方给默认值 */
    private static String nz(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return t.length() == 0 ? def : t;
    }

    public String streamUrl(String id) {
        return base + "/rest/stream.view?id=" + enc(id) + "&maxBitRate=0&format=raw&v=" + API
                + "&c=" + CLIENT + auth;
    }

    public String coverUrl(String coverArt, int px) {
        return base + "/rest/getCoverArt.view?id=" + enc(coverArt) + "&size=" + px
                + "&v=" + API + "&c=" + CLIENT + auth;
    }

    public Bitmap cover(String coverArt, int px) {
        if (coverArt == null || coverArt.length() == 0) return null;
        // N2：只请求一次网络，先读进内存再解码，避免两次往返
        byte[] data = bytes(coverUrl(coverArt, px));
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bd = new BitmapFactory.Options();
            bd.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bd);
            if (bd.outWidth <= 0 || bd.outHeight <= 0) return null;
            int sample = 1;
            int max = px * 2;
            while (bd.outWidth / sample > max || bd.outHeight / sample > max) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            Log.e(TAG, "cover decode fail", e);
            return null;
        }
    }

    /** 一次性读取响应体，带大小上限，流必定关闭 */
    private byte[] bytes(String u) {
        return bytes(u, TIMEOUT_CONNECT, TIMEOUT_READ);
    }

    /** R12：可按请求类型定制超时（歌词走短超时） */
    private byte[] bytes(String u, int connectMs, int readMs) {
        return bytes(u, connectMs, readMs, false);
    }

    /**
     * @param quiet true = 失败不写日志。延迟探测每秒一次，服务器不可达时
     *              会一直失败，逐次打日志会把日志刷满、也白白增加 IO。
     */
    private byte[] bytes(String u, int connectMs, int readMs, boolean quiet) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.connect();
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) return null;
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (Exception e) {
            if (!quiet) Log.e(TAG, "http fail", e);
            return null;
        } finally {
            closeQuietly(in);
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 取歌词。Navidrome 会把内嵌标签与同目录同名 .lrc 归并后返回；
     * 没有歌词时返回空串。
     */
    public String lyrics(String artist, String title) {
        return lyrics(artist, title, null);
    }

    /**
     * @param ok 长度 >=1 时回写服务端是否成功应答。
     *           只有成功应答后的空串才代表“确认无歌词”；
     *           网络异常导致的空串不能落“无歌词”缓存。
     */
    public String lyrics(String artist, String title, boolean[] ok) {
        try {
            // R12：走短超时，失败就把机会让给 LRCLIB，不拖住后续歌曲的取词
            byte[] b = bytes(url("getLyrics.view",
                    "&artist=" + enc(artist) + "&title=" + enc(title)),
                    TIMEOUT_LYRICS, TIMEOUT_LYRICS);
            if (b == null) throw new Exception("HTTP failed");
            String s = new String(b, "UTF-8");
            JSONObject o = new JSONObject(s).getJSONObject("subsonic-response");
            if (ok != null && ok.length > 0) ok[0] = true;
            if (!o.has("lyrics")) return "";
            return o.getJSONObject("lyrics").optString("value", "");
        } catch (Exception e) {
            Log.e(TAG, "lyrics fail", e);
            return "";
        }
    }

    /**
     * 探活：走 ping.view（服务端最轻的接口，但仍完整经过认证与业务链路）。
     * 返回 true 表示服务端确实应答了 status=ok —— 比只看 TCP 连通更能反映
     * “能不能正常干活”，因为认证失败、服务端 5xx 都会落到 false。
     */
    public boolean ping() {
        try {
            byte[] b = bytes(url("ping.view", ""), TIMEOUT_PING_CONNECT, TIMEOUT_PING_READ, true);
            if (b == null) return false;
            JSONObject o = new JSONObject(new String(b, "UTF-8"))
                    .getJSONObject("subsonic-response");
            return "ok".equals(o.optString("status", ""));
        } catch (Exception e) {
            return false;
        }
    }

}
