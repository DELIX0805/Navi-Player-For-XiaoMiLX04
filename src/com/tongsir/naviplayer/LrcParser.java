package com.tongsir.naviplayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 解析。
 *
 * 核心原则：只产出“真实带时间轴”的歌词行。
 * 没有时间轴的纯文本一律丢弃，不再按 总时长/行数 匀铺——
 * 匀铺出来的是假时间轴，必然与演唱对不上，是“歌词不同步”的根源。
 */
public class LrcParser {

    public static class Line {
        public final long time;
        public final String text;

        public Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    /** 时间轴 [mm:ss.xx] 或 [mm:ss:xx] 或 [mm:ss] */
    private static final Pattern TS = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    /** 整体偏移 [offset:+500] / [offset:-500] */
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    /** 制作信息行：词/曲/编曲/出品/监制… 不当歌词显示 */
    private static final Pattern META = Pattern.compile(
            "^(作?词|作?曲|编曲|出品|监制|统筹|推广|发行|制作人?|录音|混音|母带|吉他|贝斯|鼓|键盘|伴唱|和声|合唱|弦乐|原唱|翻唱|OP|SP|唱片公司|联合出品|企划|文案|封面|总策划|音乐总监|音频编辑)\\s*[:：].*");

    /**
     * 占位/无效歌词的完整文本集合。整段歌词去掉时间轴后若只剩这些词，
     * 一律判定为“无歌词”，绝不渲染（旧版本把 Instrumental 当歌词逐行显示）。
     */
    private static final String[] PLACEHOLDERS = {
            "instrumental", "instrument", "inst.", "instrumental.",
            "pure music", "纯音乐", "伴奏", "无歌词", "暂无歌词", "此歌曲暂无歌词",
            "歌词加载中", "歌词获取中", "歌词加载失败", "歌词获取失败", "歌词不存在",
            "no lyrics", "no lyric", "lyrics not available", "lyric not available",
            "lyrics not found", "lyric not found", "not available",
            "none", "null", "unknown"
    };

    /** 歌词里是否含时间轴（至少两行，单行时间戳多半是误判） */
    public static boolean hasTimeline(String raw) {
        if (raw == null || raw.length() == 0) return false;
        String[] ls = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        int hit = 0;
        for (String s : ls) {
            if (TS.matcher(s.trim()).find()) {
                if (++hit >= 2) return true;
            }
        }
        return false;
    }

    /** 整段内容是否只是占位词/空内容 */
    public static boolean isPlaceholder(String raw) {
        if (raw == null) return true;
        String[] ls = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        for (String s : ls) {
            String body = stripTags(s);
            if (body.length() == 0) continue;
            if (!isPlaceholderLine(body)) return false;
        }
        return true; // 全空，或全部是占位词
    }

    /** 去掉标题里的括号后缀，用于提高匹配率：小苹果 (2015央视…) -> 小苹果 */
    public static String cleanTitle(String t) {
        if (t == null) return "";
        String r = t.trim();
        r = r.replaceAll("[（(\\[【][^）)\\]】]*[）)\\]】]", "").trim();
        r = r.replaceAll("(?i)\\s*-\\s*(live|remix|acoustic|cover|instrumental|纯音乐版|伴奏).*$", "").trim();
        if (r.length() == 0) return t.trim();
        return r;
    }

    public static String cleanArtist(String a) {
        if (a == null) return "";
        String r = a.trim();
        if (r.equals("[Unknown Artist]") || r.equalsIgnoreCase("unknown")) return "";
        return r;
    }

    /**
     * 解析 LRC。返回按时间升序的歌词行；
     * 无时间轴 / 纯占位词时返回空列表（调用方按“无歌词”处理）。
     */
    public static List<Line> parse(String raw) {
        List<Line> out = new ArrayList<Line>();
        if (raw == null || raw.length() == 0) return out;
        String[] lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n");

        // 先全量扫描 [offset:]。旧实现边扫边用，offset 写在文件末尾时
        // 只对后面几行生效，导致同一份歌词内时间轴撕裂。
        long offset = 0;
        for (String s : lines) {
            Matcher mo = OFFSET.matcher(s.trim());
            if (mo.find()) {
                try {
                    offset = Long.parseLong(mo.group(1));
                } catch (Exception e) {
                    // 非法数值按 0 处理
                }
                break;
            }
        }

        boolean anyTime = false;
        List<String> plain = new ArrayList<String>();

        for (String s : lines) {
            String t = s.trim();
            if (t.length() == 0) continue;
            if (OFFSET.matcher(t).find()) continue;

            Matcher m = TS.matcher(t);
            boolean matched = false;
            while (m.find()) {
                matched = true;
                anyTime = true;
                try {
                    long mm = Long.parseLong(m.group(1));
                    long ss = Long.parseLong(m.group(2));
                    long ms = 0;
                    if (m.group(3) != null) {
                        String f = m.group(3);
                        while (f.length() < 3) f = f + "0";
                        ms = Long.parseLong(f.substring(0, 3));
                    }
                    // offset 正值 = 歌词提前出现，等价于把时间戳往前挪
                    long time = mm * 60000L + ss * 1000L + ms - offset;
                    String text = t.substring(m.end()).trim();
                    if (text.length() > 0 && !isMeta(text) && !isPlaceholderLine(text)) {
                        out.add(new Line(Math.max(0, time), text));
                    }
                } catch (Exception e) {
                    // 单个非法时间戳跳过，不影响其余行
                }
            }
            if (!matched && !isMeta(t) && !isPlaceholderLine(t)) plain.add(t);
        }

        // 无时间轴：直接判无歌词，杜绝匀铺假时间轴
        if (!anyTime) return new ArrayList<Line>();

        if (!plain.isEmpty()) {
            // 混合型 LRC（部分行无时间轴）：接在末尾，避免整段丢失
            long last = out.isEmpty() ? 0 : out.get(out.size() - 1).time + 3000L;
            for (int i = 0; i < plain.size(); i++) {
                out.add(new Line(last + i * 3000L, plain.get(i)));
            }
        }

        Collections.sort(out, new Comparator<Line>() {
            public int compare(Line a, Line b) {
                return a.time < b.time ? -1 : (a.time > b.time ? 1 : 0);
            }
        });
        return out;
    }

    /** 去掉行内所有 [xx:xx.xx] / [offset:] 标签，返回正文 */
    private static String stripTags(String line) {
        String t = line.trim();
        t = TS.matcher(t).replaceAll("");
        t = OFFSET.matcher(t).replaceAll("");
        return t.trim();
    }

    private static boolean isPlaceholderLine(String t) {
        if (t == null) return true;
        String s = t.toLowerCase(Locale.US).trim();
        if (s.length() == 0) return true;
        // 纯标点 / 省略号 / 破折号
        if (s.replaceAll("[\\s\\p{Punct}\\u2026]+", "").length() == 0) return true;
        String bare = s.replaceAll("^[\\[（(<\\s]+", "").replaceAll("[\\]）)>\\s]+$", "").trim();
        for (int i = 0; i < PLACEHOLDERS.length; i++) {
            if (bare.equals(PLACEHOLDERS[i])) return true;
        }
        return false;
    }

    private static boolean isMeta(String t) {
        return META.matcher(t).matches();
    }
}
