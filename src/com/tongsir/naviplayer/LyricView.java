package com.tongsir.naviplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词视图。
 *
 * 状态机：只有 ST_OK 才画歌词行；其余状态一律画一段居中占位提示
 * （ST_LOADING 显示“歌词加载中…”，ST_NONE / ST_ERROR / ST_IDLE 显示“暂无歌词”），
 * 绝不把占位文案混进真实歌词行里渲染。
 * 每行位置由外部传入的「播放器真实位置」实时匹配，不做逐行累加延时。
 */
public class LyricView extends View {

    /** 加载中 */
    public static final int ST_LOADING = 0;
    /** 已拿到带时间轴的歌词 */
    public static final int ST_OK = 1;
    /** 确认无歌词 */
    public static final int ST_NONE = 2;
    /** 取歌词失败（网络异常 / 服务端报错） */
    public static final int ST_ERROR = 3;
    /** 尚未开始播放，没有任何当前歌曲 */
    public static final int ST_IDLE = 4;

    private List<LrcParser.Line> lines = new ArrayList<LrcParser.Line>();
    private long timeMs = -1;
    private long offsetMs = 0;
    private int state = ST_IDLE;
    private Paint pNormal;
    private Paint pHigh;
    private Paint pTip;
    private float sizeNormal;
    private float sizeHigh;
    private String tipNone;
    private String tipLoading;

    public LyricView(Context c) {
        super(c);
        init();
    }

    public LyricView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public LyricView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        float sd = getResources().getDisplayMetrics().scaledDensity;
        sizeNormal = 14f * sd;
        sizeHigh = 18f * sd;
        // 低对比度屏：歌词一律纯白，不做任何 alpha 衰减；
        // 当前行靠字号（18sp vs 14sp）+ 加粗区分，而非靠透明度
        pNormal = new Paint(Paint.ANTI_ALIAS_FLAG);
        pNormal.setTextSize(sizeNormal);
        pNormal.setColor(0xFFFFFFFF);
        pHigh = new Paint(Paint.ANTI_ALIAS_FLAG);
        pHigh.setTextSize(sizeHigh);
        pHigh.setColor(0xFFFFFFFF);
        pHigh.setFakeBoldText(true);
        // 占位提示：同样纯白，字号取 15sp，介于普通行与高亮行之间
        pTip = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTip.setTextSize(15f * sd);
        pTip.setColor(0xFFFFFFFF);
        tipNone = getResources().getString(R.string.no_lyric);
        tipLoading = getResources().getString(R.string.lrc_loading);
    }

    /** 切歌时必须调用：清空旧歌词并复位游标，避免串台 */
    public void clear() {
        lines = new ArrayList<LrcParser.Line>();
        timeMs = -1;
        state = ST_LOADING;
        invalidate();
    }

    /** 无当前歌曲（停止 / 未开始播放）：显示“暂无歌词”而不是“加载中” */
    public void reset() {
        lines = new ArrayList<LrcParser.Line>();
        timeMs = -1;
        state = ST_IDLE;
        invalidate();
    }

    public void setState(int s) {
        if (state == s) return;
        state = s;
        invalidate();
    }

    public int getState() {
        return state;
    }

    public void setLines(List<LrcParser.Line> l) {
        lines = (l == null) ? new ArrayList<LrcParser.Line>() : l;
        timeMs = -1; // 强制下一次 setTime 立即生效并重算当前行
        state = lines.isEmpty() ? ST_NONE : ST_OK;
        invalidate();
    }

    /** 由播放器的真实位置驱动，每帧都在全量行里匹配，不做累加延时 */
    public void setTime(long ms) {
        long t = ms + offsetMs;
        if (t < 0) t = 0;
        if (t == timeMs) return;
        timeMs = t;
        invalidate();
    }

    /** 歌词整体提前/延后，毫秒。正值 = 歌词提前出现 */
    public void setOffset(long ms) {
        offsetMs = ms;
        timeMs = -1;
        invalidate();
    }

    public long getOffset() {
        return offsetMs;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int h = getHeight();
        if (h <= 0) return;

        // 无歌词 / 加载中 / 失败 / 未播放：画一段居中占位提示，不留白
        if (state != ST_OK || lines.isEmpty()) {
            String tip = (state == ST_LOADING) ? tipLoading : tipNone;
            if (tip == null) tip = "";
            tip = clip(tip, pTip);
            float tx = getPaddingLeft()
                    + (getWidth() - getPaddingLeft() - getPaddingRight()
                    - pTip.measureText(tip)) / 2f;
            if (tx < getPaddingLeft()) tx = getPaddingLeft();
            canvas.drawText(tip, tx,
                    h / 2f - (pTip.ascent() + pTip.descent()) / 2f, pTip);
            return;
        }

        float lh = h / 5f;

        int cur = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).time <= timeMs) cur = i;
            else break;
        }
        float frac = 0f;
        if (cur + 1 < lines.size()) {
            long a = lines.get(cur).time;
            long b = lines.get(cur + 1).time;
            if (b > a) frac = Math.min(1f, Math.max(0f, (timeMs - a) * 1f / (b - a)));
        }

        for (int i = cur - 2; i <= cur + 2; i++) {
            if (i < 0 || i >= lines.size()) continue;
            float y = h / 2f + (i - cur) * lh - frac * lh;
            String t = lines.get(i).text;
            if (i == cur) {
                canvas.drawText(clip(t, pHigh), x0(),
                        y - (pHigh.ascent() + pHigh.descent()) / 2f, pHigh);
            } else {
                canvas.drawText(clip(t, pNormal), x0(),
                        y - (pNormal.ascent() + pNormal.descent()) / 2f, pNormal);
            }
        }
    }

    /** 歌词左边距（尊重布局 padding，避免文本贴边） */
    private float x0() {
        return getPaddingLeft();
    }

    /** 超出内容宽度的歌词截断，避免被裁切得莫名其妙 */
    private String clip(String t, Paint p) {
        float max = getWidth() - getPaddingLeft() - getPaddingRight();
        if (max <= 0 || t == null) return t;
        if (p.measureText(t) <= max) return t;
        int n = p.breakText(t, true, max - p.measureText("\u2026"), null);
        if (n <= 0) return "";
        return t.substring(0, n) + "\u2026";
    }
}
