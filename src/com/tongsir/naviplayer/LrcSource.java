package com.tongsir.naviplayer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 歌词获取编排，对照 Navidrome 的歌词来源优先级：
 * 1) 磁盘缓存（上一次已确认的结果）
 * 2) Navidrome getLyrics —— 服务端已把「内嵌标签」与「同目录同名 .lrc 文件」归并后返回
 * 3) LRCLIB /api/get（artist + title + duration 精确匹配）
 * 4) LRCLIB /api/get（标题清洗后重试一次）
 * 5) LRCLIB /api/search（按真实时长差挑最优，差 >15s 直接弃用）
 *
 * 全过程只接受「带时间轴」的结果：无时间轴一律返回 null（无歌词），
 * 绝不按 总时长/行数 匀铺，避免产出必然对不上的假时间轴。
 */
public class LrcSource {

    private static final String TAG = "Navi";
    private static final String LRC = "https://lrclib.net/api/";
    /** R13：UA 与 AndroidManifest 的 versionName 保持同步，便于服务端侧识别版本 */
    private static final String USER_AGENT = "NaviPlayer/1.8";
    private static final int TIMEOUT_C = 6000;
    private static final int TIMEOUT_R = 10000;

    /** 请求取消钩子：切歌后旧请求的结果必须丢弃 */
    public interface Cancel {
        boolean isCancelled();
    }

    private final Context ctx;
    private final SubsonicClient api;

    public LrcSource(Context c, SubsonicClient api) {
        this.ctx = c.getApplicationContext();
        this.api = api;
    }

    /**
     * 同步方法，必须在后台线程调用。
     *
     * @param durationMs 真实解码时长优先；<=0 时回退到元数据时长
     * @param c          取消钩子，可为 null
     * @return 带时间轴的歌词行；null = 无歌词（调用方不应渲染任何占位文案）
     */
    public List<LrcParser.Line> get(Song s, long durationMs, Cancel c) {
        File cache = cacheFile(s);
        String cached = read(cache);
        if (cached != null) {
            if (cached.length() == 0) {
                Log.i(TAG, "lyrics cached-none: " + s.title);
                return null;
            }
            List<LrcParser.Line> l = LrcParser.parse(cached);
            if (!l.isEmpty()) {
                Log.i(TAG, "lyrics cached: " + s.title);
                return l;
            }
        }

        long dur = durationMs > 0 ? durationMs : s.duration;
        long sec = dur > 0 ? dur / 1000L : 0;

        String raw = null;
        boolean netDown = false;

        // 2) Navidrome：内嵌标签 + 同名 .lrc
        String nv = "";
        if (api != null) {
            boolean[] ok = new boolean[1];
            nv = api.lyrics(s.artist, s.title, ok);
            if (!ok[0]) netDown = true;
        }
        if (nv == null) nv = "";
        if (LrcParser.hasTimeline(nv) && !LrcParser.isPlaceholder(nv)) {
            raw = nv;
            Log.i(TAG, "lyrics<-navidrome: " + s.title);
        }
        if (cancelled(c)) return null;

        // 3) LRCLIB 精确匹配
        if (raw == null) {
            boolean[] ok = new boolean[1];
            raw = lrclibGet(LrcParser.cleanArtist(s.artist), LrcParser.cleanTitle(s.title), sec, ok);
            if (raw != null) Log.i(TAG, "lyrics<-lrclib-get: " + s.title);
            else if (!ok[0]) netDown = true;
        }
        if (cancelled(c)) return null;

        // 4) 原始标题再试一次
        if (raw == null) {
            boolean[] ok = new boolean[1];
            raw = lrclibGet(LrcParser.cleanArtist(s.artist), s.title, sec, ok);
            if (raw != null) Log.i(TAG, "lyrics<-lrclib-retry: " + s.title);
            else if (!ok[0]) netDown = true;
        }
        if (cancelled(c)) return null;

        // 5) 搜索兜底
        if (raw == null) {
            boolean[] ok = new boolean[1];
            raw = lrclibSearch(LrcParser.cleanTitle(s.title), LrcParser.cleanArtist(s.artist), sec, ok);
            if (raw != null) Log.i(TAG, "lyrics<-lrclib-search: " + s.title);
            else if (!ok[0]) netDown = true;
        }

        if (raw == null) {
            Log.i(TAG, "lyrics none (netDown=" + netDown + "): " + s.title);
            // 只有确认各源都正常回答过才缓存“无歌词”；
            // 断网导致的空结果不落盘，下次还有机会拿到歌词
            if (!netDown) write(cache, "");
            return null;
        }

        write(cache, raw);
        List<LrcParser.Line> l = LrcParser.parse(raw);
        return l.isEmpty() ? null : l;
    }

    private static boolean cancelled(Cancel c) {
        return c != null && c.isCancelled();
    }

    // ---------- LRCLIB ----------

    private String lrclibGet(String artist, String title, long seconds, boolean[] reachable) {
        try {
            StringBuilder u = new StringBuilder(LRC).append("get?track_name=").append(enc(title));
            if (artist != null && artist.length() > 0) u.append("&artist_name=").append(enc(artist));
            // R5：LRCLIB 的 duration 参数是“精确匹配”——实测差 1 秒即返回 404，
            // 旧实现恒带 duration，使 /api/get 这一级几乎永远落空，白耗一次往返
            // 还降低了命中率（Angelina 带 duration=208 得 404，不带则 200 直接拿到词）。
            // 改为先不带 duration 按 track+artist 精确匹配，拿回结果后再用服务端
            // duration 与本地真实时长比对，差 >15s 视为同名不同版本（翻唱/live）弃用。
            Resp r = reqWithRetry(u.toString());
            if (r.code == -1) {
                Log.i(TAG, "lrclib-get unreachable: " + title);
                return null; // 网络层异常：服务未应答
            }
            // 404 是“服务正常、确实没有这首歌”，必须算作可达，
            // 否则这类歌永远不会落“无歌词”缓存，每次播放都要重查 4 次网络
            if (r.code == 200 || r.code == 404) reachable[0] = true;
            if (r.code != 200 || r.body == null) {
                Log.i(TAG, "lrclib-get miss(" + r.code + "): " + title);
                return null;
            }
            JSONObject o = new JSONObject(r.body);
            String syn = o.optString("syncedLyrics", "");
            if (syn == null || syn.length() == 0 || !LrcParser.hasTimeline(syn)
                    || LrcParser.isPlaceholder(syn)) return null;
            double rd = o.optDouble("duration", -1d);
            if (seconds > 0 && rd > 0 && Math.abs(rd - seconds) > 15d) {
                Log.i(TAG, "lrclib-get duration mismatch srv=" + rd + " local=" + seconds);
                return null;
            }
            return syn;
        } catch (Exception e) {
            return null;
        }
    }

    private String lrclibSearch(String title, String artist, long seconds, boolean[] reachable) {
        try {
            String q = title + (artist != null && artist.length() > 0 ? " " + artist : "");
            Resp r = reqWithRetry(LRC + "search?q=" + enc(q));
            if (r.code == -1) {
                Log.i(TAG, "lrclib search unreachable");
                return null;
            }
            if (r.code == 200) reachable[0] = true;
            if (r.code != 200 || r.body == null) {
                Log.i(TAG, "lrclib search http " + r.code);
                return null;
            }
            JSONArray arr = new JSONArray(r.body);
            String best = null;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < arr.length() && i < 10; i++) {
                JSONObject o = arr.getJSONObject(i);
                String syn = o.optString("syncedLyrics", "");
                if (syn == null || syn.length() == 0 || !LrcParser.hasTimeline(syn)) continue;
                if (LrcParser.isPlaceholder(syn)) continue;
                // 服务端时长是浮点（如 197.172245），必须按 double 取再比
                double od = o.optDouble("duration", -1d);
                long d = od > 0 ? (long) Math.abs(od - seconds) : Long.MAX_VALUE / 4;
                if (d < bestDiff) {
                    bestDiff = d;
                    best = syn;
                }
            }
            // 时长差超过 15 秒宁可不用，避免匹配到翻唱/live 版
            if (best != null && bestDiff <= 15) return best;
            if (best != null) Log.i(TAG, "lrclib search best diff=" + bestDiff + " (rejected)");
            return null;
        } catch (Exception e) {
            Log.i(TAG, "lrclib search err " + e);
            return null;
        }
    }

    /** R5：带状态码的响应；code = -1 表示网络层异常，一个字节都没收到 */
    private static final class Resp {
        final int code;
        final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Resp req(String u) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(TIMEOUT_C);
            c.setReadTimeout(TIMEOUT_R);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) return new Resp(code, null); // 保留 404/5xx 语义
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new Resp(code, new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return new Resp(-1, null);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception e) {
                // ignore
            }
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    /**
     * R5：5xx（实测 LRCLIB 会返回 503 ServerOverloaded）退避后重试一次；
     * 4xx 是确定性答复，重试无意义，直接返回。
     */
    private Resp reqWithRetry(String u) {
        Resp r = req(u);
        if (r.code >= 500) {
            sleep(800);
            Resp r2 = req(u);
            if (r2.code != -1) return r2;
        }
        return r;
    }

    /** R6：中断标志必须保留，否则池内线程的中断状态被吞掉 */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 缓存 ----------

    /** 缓存上限，超出后清掉最旧的一半，避免长期累积 */
    private static final int MAX_CACHE = 2000;
    /** R7：单个缓存文件读取上限 */
    private static final int MAX_LRC_BYTES = 1024 * 1024;

    /** R9：trim 要 listFiles + 排序，旧实现每次取词都做一遍（含缓存命中路径）。
     *  改为每 32 次调用才真正检查一次，省掉无谓的目录扫描。
     *  仅在 lrcPool 单线程内访问，无需同步。 */
    private int trimTick = 0;
    private static final int TRIM_EVERY = 32;

    private File cacheFile(Song s) {
        File d = new File(ctx.getFilesDir(), "lrc");
        if (!d.exists()) d.mkdirs();
        if (++trimTick >= TRIM_EVERY) {
            trimTick = 0;
            trimCache(d);
        }
        String key = s.title + "|" + s.artist;
        return new File(d, md5(key) + ".txt");
    }

    private static void trimCache(File d) {
        try {
            File[] fs = d.listFiles();
            if (fs == null) return;
            // R9：清掉写入中断残留的 .tmp（超过 1 分钟即视为残留）
            long now = System.currentTimeMillis();
            for (int i = 0; i < fs.length; i++) {
                if (fs[i].getName().endsWith(".tmp")
                        && now - fs[i].lastModified() > 60000L) {
                    try {
                        fs[i].delete();
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            if (fs.length <= MAX_CACHE) return;
            Arrays.sort(fs, new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.lastModified() < b.lastModified() ? -1
                            : (a.lastModified() > b.lastModified() ? 1 : 0);
                }
            });
            for (int i = 0; i < fs.length / 2; i++) {
                try {
                    fs[i].delete();
                } catch (Exception e) {
                    // ignore
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static String read(File f) {
        try {
            // R7：本地缓存文件理论可被外部写入（设备已 root），加长度上限防止读爆内存
            if (!f.exists() || f.length() > MAX_LRC_BYTES) return null;
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String s) {
        try {
            // R9：先写临时文件再改名，避免进程被杀/断电时留下半截缓存
            // 被下次读出来当成“残歌词”
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(s.getBytes("UTF-8"));
            out.close();
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            // ignore
        }
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
            // R7：回退值也必须是合法文件名，负号开头在部分实现里会被误当参数解析
            return "h" + Integer.toHexString(s == null ? 0 : s.hashCode());
        }
    }
}
