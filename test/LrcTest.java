import com.tongsir.naviplayer.LrcParser;
import java.util.List;

/** LrcParser 行为验证：时间轴解析、offset 位置、占位词、无时间轴、混合歌词 */
public class LrcTest {

    static int fail = 0;

    static void eq(String name, Object expect, Object actual) {
        boolean ok = String.valueOf(expect).equals(String.valueOf(actual));
        System.out.println((ok ? "  PASS " : "  FAIL ") + name
                + "   expect=" + expect + "   actual=" + actual);
        if (!ok) fail++;
    }

    /** 模拟 LyricView.onDraw 的匹配：按播放位置找当前行 */
    static String at(List<LrcParser.Line> lines, long posMs) {
        if (lines.isEmpty()) return "<none>";
        int cur = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).time <= posMs) cur = i;
            else break;
        }
        return lines.get(cur).text;
    }

    public static void main(String[] a) {
        System.out.println("== 1. 时间轴解析与实时位置匹配 ==");
        List<LrcParser.Line> l = LrcParser.parse(
                "[00:00.00]前奏\n[00:10.50]第一句\n[00:20.25]第二句\n[01:02.5]第三句\n");
        eq("行数", 4, l.size());
        eq("[00:00.00]", 0, l.get(0).time);
        eq("[00:10.50]", 10500, l.get(1).time);
        eq("[00:20.25]", 20250, l.get(2).time);
        eq("[01:02.5]", 62500, l.get(3).time);
        eq("位置 0ms", "前奏", at(l, 0));
        eq("位置 10499ms", "前奏", at(l, 10499));
        eq("位置 10500ms", "第一句", at(l, 10500));
        eq("位置 60200ms", "第二句", at(l, 60200));
        eq("位置 62500ms", "第三句", at(l, 62500));

        System.out.println("== 2. [offset:] 写在末尾也要对全部行生效 ==");
        List<LrcParser.Line> o = LrcParser.parse(
                "[00:10.00]A\n[00:20.00]B\n[offset:+500]\n");
        eq("A 提前 500ms", 9500, o.get(0).time);
        eq("B 提前 500ms", 19500, o.get(1).time);
        eq("9500ms 命中 A", "A", at(o, 9500));
        List<LrcParser.Line> o2 = LrcParser.parse("[offset:-300]\n[00:10.00]A\n");
        eq("负 offset 延后", 10300, o2.get(0).time);

        System.out.println("== 3. 占位词不得当歌词 ==");
        eq("Instrumental 判占位", true, LrcParser.isPlaceholder("Instrumental"));
        eq("[Instrumental] 判占位", true, LrcParser.isPlaceholder("[Instrumental]"));
        eq("纯音乐 判占位", true, LrcParser.isPlaceholder("纯音乐"));
        eq("无歌词 判占位", true, LrcParser.isPlaceholder("暂无歌词"));
        eq("真实歌词不判占位", false, LrcParser.isPlaceholder("[00:01.00]夜空中最亮的星"));
        eq("Instrumental 解析为空", 0, LrcParser.parse("Instrumental").size());
        eq("带轴的 Instrumental 解析为空", 0,
                LrcParser.parse("[00:00.00]Instrumental\n[00:05.00]Instrumental\n").size());

        System.out.println("== 4. 无时间轴纯文本不再匀铺假时间轴 ==");
        eq("纯文本返回空", 0, LrcParser.parse("一句歌词\n另一句歌词\n").size());

        System.out.println("== 5. 混合型 LRC 保留尾部 ==");
        List<LrcParser.Line> m = LrcParser.parse(
                "[00:01.00]主歌\n尾部无轴文本\n作词: 张三\n");
        eq("混合行数", 2, m.size());
        eq("尾部时间接在末尾", 4000, m.get(1).time);

        System.out.println("== 6. 制作信息行过滤 ==");
        eq("作词行被过滤", 1,
                LrcParser.parse("[00:01.00]主歌\n[00:02.00]作词：李四\n").size());

        System.out.println(fail == 0 ? "\nALL PASS" : "\nFAILED: " + fail);
        if (fail > 0) System.exit(1);
    }
}
