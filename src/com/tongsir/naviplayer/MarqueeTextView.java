package com.tongsir.naviplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * 歌名专用 TextView：文本超出可用宽度时才左右循环滚动，未超出则完全静止、左对齐。
 *
 * 为什么不用系统的 android:ellipsize="marquee"：
 *  1. 原生 marquee 必须先让 view 处于 selected 状态才会动（要额外 setSelected + focusable），
 *     且停顿固定、速度不可控；
 *  2. 原生 marquee 是"整条滚出屏幕再从外面滚回来"，节奏生硬、循环点会闪。
 *
 * 这里的做法：把同一段文本连画两遍（中间隔一个 GAP），偏移量在 [0, 文本宽+GAP]
 * 之间推进。走到周期末端时直接把偏移减去一个周期 —— 此刻屏幕上显示的正好是
 * 第二份文本的起始部分，与偏移为 0 的画面逐像素一致，所以循环是无缝、不闪的。
 * 头部对齐时与尾部对齐时各插一次停顿，保证两端都读得完。
 *
 * 绘制全部发生在自身 116dp 宽的范围内（超出部分被 view 边界裁掉），
 * 不改尺寸、不影响任何其它元素的布局。
 *
 * 主界面两行都用它：歌名（@id/title）与歌手（@id/artist）。
 * 两行参数一致、同速推进，视觉上像一条整体滚动的走马灯。
 */
public class MarqueeTextView extends TextView {

    /** 滚动速度（dp/s）。40dp/s 在 240dpi 上约合 1.6 个中文字/秒，读得清也不拖沓 */
    private static final float SPEED_DP_S = 40f;
    /** 首尾可读停顿（ms）：文本头部对齐时、尾部对齐时各停一次 */
    private static final long PAUSE_MS = 1400L;
    /** 尾部结束后到下一次头部出现之间的间隔（dp），给循环留个节奏 */
    private static final float GAP_DP = 36f;

    private static final int PH_HEAD = 0;      // 头部停顿
    private static final int PH_TO_TAIL = 1;   // 滚到尾部对齐右边缘
    private static final int PH_TAIL = 2;      // 尾部停顿
    private static final int PH_TO_END = 3;    // 尾部滚出、下一条头部滚入

    private float speedPx;
    private float gapPx;

    private float offset;       // 当前滚出的距离
    private float textW;        // 文本完整宽度
    private float tailHold;     // 尾部对齐右边缘时的偏移量
    private float cycleLen;     // 一个循环周期 = 文本宽 + 间隔
    private boolean scrollable; // 文本是否真的超宽（决定滚不滚）
    private boolean running;

    private long lastNs;
    private long pauseUntilNs;
    private int phase = PH_HEAD;
    /** 上一次的文本：文本没真变就不复位滚动 */
    private String lastText;
    /** 文本真变了，等下一次 syncRunning 时从头开始 */
    private boolean restartPending;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };

    public MarqueeTextView(Context c) {
        super(c);
        init();
    }

    public MarqueeTextView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public MarqueeTextView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        speedPx = SPEED_DP_S * d;
        gapPx = GAP_DP * d;
    }

    /** 文本/尺寸变化后重新判定是否需要滚动 */
    private void remeasure() {
        CharSequence cs = getText();
        String t = (cs == null) ? "" : cs.toString();
        textW = (t.length() == 0) ? 0f : getPaint().measureText(t);
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        scrollable = avail > 0 && textW > avail + 0.5f;
        tailHold = Math.max(0f, textW - avail);
        cycleLen = textW + gapPx;
    }

    private void syncRunning() {
        boolean should = scrollable
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE;
        if (!should) {
            if (running) {
                running = false;
                removeCallbacks(tick);
            }
            offset = 0f;
            restartPending = false;
            invalidate();
            return;
        }
        long now = System.nanoTime();
        if (restartPending || !running) {
            // 从头开始：回到头部对齐，并留一次可读停顿
            offset = 0f;
            phase = PH_HEAD;
            lastNs = now;
            pauseUntilNs = now + PAUSE_MS * 1000000L;
            restartPending = false;
        }
        if (!running) {
            running = true;
            postOnAnimation(tick);
        }
        invalidate();
    }

    private void step() {
        if (!running) return;
        long now = System.nanoTime();
        float dt = (now - lastNs) / 1e9f;
        lastNs = now;
        // 掉帧或从后台回来时不让它一步跳很远（最多按 150ms 推进）
        if (dt > 0.15f) dt = 0.15f;
        if (dt < 0f) dt = 0f;

        switch (phase) {
            case PH_HEAD:
                if (now >= pauseUntilNs) phase = PH_TO_TAIL;
                break;
            case PH_TO_TAIL:
                offset += speedPx * dt;
                if (offset >= tailHold) {
                    offset = tailHold;
                    phase = PH_TAIL;
                    pauseUntilNs = now + PAUSE_MS * 1000000L;
                }
                break;
            case PH_TAIL:
                if (now >= pauseUntilNs) phase = PH_TO_END;
                break;
            case PH_TO_END:
            default:
                offset += speedPx * dt;
                if (offset >= cycleLen) {
                    // 无缝点：此刻画面等价于 offset=0，直接减一个周期不产生任何跳变
                    offset -= cycleLen;
                    if (offset < 0f) offset = 0f;
                    phase = PH_HEAD;
                    pauseUntilNs = now + PAUSE_MS * 1000000L;
                }
                break;
        }
        invalidate();
        postOnAnimation(tick);
    }

    @Override
    protected void onTextChanged(CharSequence s, int start, int before, int count) {
        super.onTextChanged(s, start, before, count);
        // 只有文本真的变了才把滚动复位。主界面在"切歌"和"封面异步到达"时会各 setText 一次，
        // 两次内容相同；若无条件复位，封面晚到时滚动会莫名从头开始，或被反复钉在起点不动。
        String t = (s == null) ? "" : s.toString();
        if (!t.equals(lastText)) {
            lastText = t;
            restartPending = true;
        }
        remeasure();
        syncRunning();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        remeasure();
        syncRunning();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        remeasure();
        syncRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        syncRunning();
    }

    @Override
    protected void onVisibilityChanged(android.view.View changedView, int v) {
        super.onVisibilityChanged(changedView, v);
        syncRunning();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 没超宽：交给 TextView 原生绘制（静止、左对齐、无省略号）
        if (!scrollable || textW <= 0f) {
            super.onDraw(canvas);
            return;
        }
        CharSequence cs = getText();
        if (cs == null || cs.length() == 0) {
            super.onDraw(canvas);
            return;
        }
        Paint p = getPaint();
        float base = getBaseline();
        if (base <= 0f) base = getPaddingTop() - p.ascent();

        String t = cs.toString();
        float x = getPaddingLeft() - offset;
        canvas.drawText(t, x, base, p);
        canvas.drawText(t, x + textW + gapPx, base, p);
    }
}
