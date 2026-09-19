package com.tongsir.naviplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements PlayerService.Callback {

    private static final String TAG = "Navi";
    /** 延迟探测间隔：1 秒 */
    private static final int PING_INTERVAL = 1000;
    /** 未绑定服务（或未配置服务器）时给探测结果用的哨兵值 */
    private static final long RTT_NO_SERVICE = -2;
    /** 状态灯配色：跳闸红 / 未连接灰（正常态直接用纯白） */
    private static final int COLOR_DOT_BAD = 0xFFFF5252;
    private static final int COLOR_DOT_IDLE = 0xFF9AA0A6;
    private PlayerService svc;
    private boolean bound = false;
    private final Handler h = new Handler();
    private boolean dragging = false;

    private TextView title, artist, clock, brand, cur, dur, modeText;
    private ImageView cover, modeIcon;
    private LyricView lyric;
    private SeekBar seek;
    private ImageButton btnPlay;
    private LinearLayout btnMode;
    private Prefs prefs;
    private android.app.Dialog dialog; // N8：持有当前对话框，销毁时关闭防 WindowLeaked
    /** 当前界面显示的歌曲 ID，用于判断切歌与丢弃过期歌词 */
    private String shownId = null;

    // ---- 底部状态条 ----
    /** 当前音频格式标签（白底深字），无格式信息时整块 GONE */
    private TextView fmt;
    /** 当前文件码率 "922 kbps" */
    private TextView bitrate;
    /** 服务器状态灯 + 实时延迟文字 */
    private ImageView srvDot;
    private TextView srvText;
    /** 延迟探测专用线程：与 UI 主线程、播放服务线程区分开，互不阻塞 */
    private ExecutorService netPool;
    /** 上一次探测还没回来时不发下一次，避免链路卡顿时请求堆积 */
    private volatile boolean pingBusy = false;
    private volatile boolean destroyed = false;

    private final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            bound = true;
            svc.setCallback(MainActivity.this);
            applyMode();
            refreshOnce();
            probeServer(); // 连上就立刻探一次，不必干等下一秒
        }

        public void onServiceDisconnected(ComponentName n) {
            bound = false;
            svc = null;
            showServer(RTT_NO_SERVICE);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        brand = findViewById(R.id.brand);
        clock = findViewById(R.id.clock);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        cover = findViewById(R.id.cover);
        lyric = findViewById(R.id.lyric);
        seek = findViewById(R.id.seek);
        cur = findViewById(R.id.cur);
        dur = findViewById(R.id.dur);
        btnPlay = findViewById(R.id.btnPlay);
        btnMode = findViewById(R.id.btnMode);
        modeText = findViewById(R.id.modeText);
        modeIcon = findViewById(R.id.modeIcon);
        fmt = findViewById(R.id.fmt);
        bitrate = findViewById(R.id.bitrate);
        srvDot = findViewById(R.id.srvDot);
        srvText = findViewById(R.id.srvText);

        netPool = Executors.newSingleThreadExecutor();
        // 未绑定服务前的初始态，避免状态栏空着（绑定后 1 秒内会被真实探测覆盖）
        showServer(RTT_NO_SERVICE);
        showMeta(null);

        brand.setText("NAVIDROME");

        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (svc != null) svc.toggle();
            }
        });
        findViewById(R.id.btnPrev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (svc != null) svc.prev();
            }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (svc != null) svc.next(false);
            }
        });
        btnMode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (svc == null) return;
                svc.setShuffle(!svc.isShuffle());
                applyMode();
            }
        });

        lyric.setOffset(prefs.lrcOffset());
        lyric.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showOffsetDialog();
                return true;
            }
        });

        // H1：提供重新配置服务器的入口（顶栏左右两侧长按）
        View.OnLongClickListener setupLong = new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showSetup(true);
                return true;
            }
        };
        brand.setOnLongClickListener(setupLong);
        clock.setOnLongClickListener(setupLong);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && svc != null) {
                    int d = svc.duration();
                    // N6：long 运算，长歌曲（>35 分钟）时 int 会溢出
                    if (d > 0) cur.setText(fmt((int) ((long) p * d / 1000)));
                }
            }

            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) {
                    int d = svc.duration();
                    // N6：同上，long 运算防溢出
                    if (d > 0) svc.seekTo((int) ((long) s.getProgress() * d / 1000));
                }
            }
        });

        if (prefs.configured()) {
            startSvc();
            Toast.makeText(this, R.string.tip_setup, Toast.LENGTH_SHORT).show();
        } else {
            showSetup(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // R6：轮询改为跟随可见性启停，避免界面不可见时 200ms 空转
        h.removeCallbacks(tick);
        h.post(tick);
        // 延迟探测同样只在界面可见时跑：每秒一次请求没必要在后台空烧
        h.removeCallbacks(pingTick);
        h.post(pingTick);
    }

    @Override
    protected void onStop() {
        h.removeCallbacks(tick);
        h.removeCallbacks(pingTick);
        super.onStop();
    }

    private void startSvc() {
        Intent i = new Intent(this, PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            // 低22：降级调用本身也可能因后台限制再抛，不能再裸抛
            try {
                startService(i);
            } catch (Exception e2) {
                Log.e(TAG, "start service failed", e2);
                Toast.makeText(this, R.string.err_connect, Toast.LENGTH_LONG).show();
                return;
            }
        }
        bindService(i, conn, BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        h.removeCallbacks(tick);
        h.removeCallbacks(pingTick);
        if (netPool != null) netPool.shutdownNow();
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception e) {
                // ignore
            }
        }
        dialog = null;
        if (bound) {
            if (svc != null) svc.setCallback(null);
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            clock.setText(DateFormat.format("HH:mm", System.currentTimeMillis()).toString());
            if (bound && svc != null) {
                int p = svc.position();
                int d = svc.duration();
                // 歌词始终按解码器真实位置在整份时间轴里匹配，
                // 暂停、seek 也立即对齐，不是逐行累加延时推进
                lyric.setTime(p);
                // M8：拖动中不要用真实进度覆盖用户预览的时间
                if (!dragging) {
                    // R4：p*1000 在长曲目（>35.8 分钟）会 int 溢出，必须用 long 运算
                    if (d > 0) seek.setProgress((int) ((long) p * 1000 / d));
                    cur.setText(fmt(p));
                    dur.setText("-" + fmt(Math.max(0, d - p)));
                }
            }
            h.postDelayed(this, 200);
        }
    };

    private void refreshOnce() {
        if (svc == null) return;
        Song s = svc.current();
        if (s != null) {
            title.setText(s.title);
            artist.setText(s.artist);
        }
        showMeta(s);
        btnPlay.setImageResource(svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ---------- 底部状态条 ----------

    /**
     * 歌曲技术信息：格式标签 + 码率。
     * 两项都可能缺失（服务端没解析到标签），此时宁可整块留空也不显示 "-"，
     * 避免界面上出现无意义的占位符号。
     */
    private void showMeta(Song s) {
        if (s == null) {
            fmt.setVisibility(View.GONE);
            bitrate.setText("");
            return;
        }
        String f = s.format == null ? "" : s.format.trim();
        if (f.length() > 0) {
            fmt.setText(f);
            fmt.setVisibility(View.VISIBLE);
        } else {
            fmt.setVisibility(View.GONE);
        }
        bitrate.setText(s.bitRate > 0 ? (s.bitRate + " kbps") : "");
    }

    /** 每秒一次：探测到服务器的往返延迟并刷新状态灯 */
    private final Runnable pingTick = new Runnable() {
        public void run() {
            probeServer();
            if (destroyed) return;
            h.postDelayed(this, PING_INTERVAL);
        }
    };

    private void probeServer() {
        if (destroyed || netPool == null) return;
        final PlayerService s = svc;
        if (!bound || s == null) {
            showServer(RTT_NO_SERVICE);
            return;
        }
        if (pingBusy) return; // 上一次未回，跳过本轮
        pingBusy = true;
        try {
            netPool.execute(new Runnable() {
                public void run() {
                    final long rtt = s.pingServer();
                    h.post(new Runnable() {
                        public void run() {
                            pingBusy = false;
                            if (!destroyed) showServer(rtt);
                        }
                    });
                }
            });
        } catch (Exception e) {
            // 线程池已关闭（Activity 正在销毁），本轮探测作废
            pingBusy = false;
        }
    }

    /**
     * @param rtt >=0 往返毫秒数；-1 探测失败（离线）；RTT_NO_SERVICE 未绑定服务
     */
    private void showServer(long rtt) {
        if (srvDot == null || srvText == null) return;
        if (rtt == RTT_NO_SERVICE) {
            srvDot.setColorFilter(COLOR_DOT_IDLE, android.graphics.PorterDuff.Mode.SRC_IN);
            srvText.setText(R.string.srv_none);
        } else if (rtt < 0) {
            srvDot.setColorFilter(COLOR_DOT_BAD, android.graphics.PorterDuff.Mode.SRC_IN);
            srvText.setText(R.string.srv_off);
        } else {
            srvDot.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            srvText.setText(rtt + " ms");
        }
    }

    private void applyMode() {
        boolean sh = svc != null && svc.isShuffle();
        modeText.setText(sh ? R.string.mode_shuffle : R.string.mode_order);
        modeIcon.setImageResource(sh ? R.drawable.ic_shuffle : R.drawable.ic_order);
    }

    /** 低对比度屏：对话框内所有可写文字（含占位符）统一纯白 */
    private static void white(TextView v) {
        v.setTextColor(0xFFFFFFFF);
        v.setHintTextColor(0xFFFFFFFF);
    }

    /** dp -> px */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * R14：输入框统一样式。
     *
     * DialogTheme 的父主题是 Holo 之前的 @android:style/Theme.Black，它的
     * editTextBackground 是浅色的 editbox_background(#F2F2F2)。应用要求文字一律
     * 纯白，实测白字压浅底只有 1.12:1（WCAG 正文要求 ≥4.5:1），基本读不出来。
     * 这里换深色实底 + 描边 drawable（对比度约 17:1）。
     *
     * 顺带给死高度：旧主题的 EditText 靠 9-patch 撑到 72px 高，四个控件叠加后
     * 总高超过对话框可视区，最下面的复选框被挤到框外（层级 dump 里搜不到它）。
     */
    private void styleInput(EditText e, int hDp) {
        e.setBackgroundResource(R.drawable.input_bg);
        e.setGravity(android.view.Gravity.CENTER_VERTICAL);
        e.setIncludeFontPadding(false);
        int ph = dp(8);
        e.setPadding(ph, 0, ph, 0);
        // 光标/选区颜色：旧主题默认色在深底上看不见
        e.setHighlightColor(0xFF3A5A8C);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(hDp));
        lp.bottomMargin = dp(4);
        e.setLayoutParams(lp);
    }

    /** R14：对话框内按钮统一样式——默认浅灰底 #CCCCCC 配白字只有 1.61:1 */
    private void styleDialogButton(Button b) {
        b.setBackgroundResource(R.drawable.btn_dialog);
        b.setTextColor(0xFFFFFFFF);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = dp(10);
        b.setPadding(p, 0, p, 0);
    }

    /** AlertDialog 按钮文字强制纯白——不同 ROM 主题不一致，theme 兜不住 */
    private void whiteButtons(final AlertDialog d) {
        d.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface x) {
                int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                        AlertDialog.BUTTON_NEUTRAL};
                for (int i = 0; i < ids.length; i++) {
                    Button b = d.getButton(ids[i]);
                    if (b == null) continue;
                    b.setTextColor(0xFFFFFFFF);
                    // R14：默认按钮底是浅灰 #CCCCCC，白字压上去只有 1.61:1
                    b.setBackgroundResource(R.drawable.btn_dialog);
                }
            }
        });
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        // 低26：超过 1 小时用 H:MM:SS，避免三位数分钟撑破布局
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    // ---------- 服务回调 ----------

    /**
     * 只在「歌曲真的换了」时清空歌词/封面/进度。
     * 封面是异步到达的，会二次回调本方法；若不做 ID 判重，会把已加载好的歌词抹掉。
     */
    public void onSongChanged(Song s, Bitmap bmp) {
        if (s == null) {
            shownId = null;
            title.setText("");
            artist.setText("");
            cover.setImageBitmap(null);
            seek.setProgress(0);
            cur.setText("00:00");
            dur.setText("-00:00");
            // R8：无当前歌曲属于“暂无歌词”，不是“加载中”。
            // 旧实现用 clear()（→ ST_LOADING），一旦只走到这个分支
            // （例如停止播放），占位文案会永久停在“歌词加载中…”。
            lyric.reset();
            showMeta(null);
            return;
        }
        String id = s.id == null ? "" : s.id;
        if (!id.equals(shownId)) {
            shownId = id;
            cover.setImageBitmap(null); // M4：先清旧封面，避免张冠李戴
            seek.setProgress(0);
            cur.setText("00:00");
            lyric.clear();              // 切歌立即清空旧歌词，杜绝串台
            showMeta(s);                // 格式/码率随歌曲走，只在真的换歌时刷新
        }
        title.setText(s.title);
        artist.setText(s.artist);
        if (bmp != null) cover.setImageBitmap(bmp);
        dur.setText("-" + fmt(svc != null ? svc.duration() : s.duration));
    }

    public void onLyricsReady(Song s, List<LrcParser.Line> lines, int state) {
        if (s == null) {
            shownId = null;
            lyric.reset();
            return;
        }
        // 过期响应：切歌之后才回来的旧歌词一律丢弃
        String id = s.id == null ? "" : s.id;
        if (shownId != null && !id.equals(shownId)) {
            Log.i(TAG, "drop stale lyrics: " + s.title);
            return;
        }
        lyric.setLines(lines);
        lyric.setState(state); // 显式状态优先：OK / NONE / ERROR / LOADING
    }

    public void onStateChanged(boolean playing) {
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    public void onQueueReady(int size, String msg) {
        if (msg != null) {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            Log.e(TAG, msg);
        }
        if (size > 0) refreshOnce();
    }

    /** 歌词与演唱对不上时的手动校准 */
    private void showOffsetDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView tip = new TextView(this);
        tip.setText("正值 = 歌词提前，负值 = 延后（毫秒）");
        tip.setTextSize(13f);

        final EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setText(String.valueOf(prefs.lrcOffset()));
        e.setSingleLine(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button bm = new Button(this);
        bm.setText("-500");
        // R4：增量存进 tag，不再靠解析按钮文本（文本改了逻辑就崩）
        bm.setTag(Integer.valueOf(-500));
        Button b0 = new Button(this);
        b0.setText("归零");
        Button bp = new Button(this);
        bp.setText("+500");
        bp.setTag(Integer.valueOf(500));
        row.addView(bm);
        row.addView(b0);
        row.addView(bp);
        View.OnClickListener adj = new View.OnClickListener() {
            public void onClick(View v) {
                Object tag = v.getTag();
                int delta = (tag instanceof Integer) ? ((Integer) tag).intValue() : 0;
                e.setText(String.valueOf(parse(e.getText().toString(), 0) + delta));
            }
        };
        bm.setOnClickListener(adj);
        bp.setOnClickListener(adj);
        b0.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                e.setText("0");
            }
        });

        box.addView(tip);
        styleInput(e, 34);
        white(bm);
        white(b0);
        white(bp);
        white(e);
        white(tip);
        // R14：三个微调按钮等分一行，并统一深色底（默认浅灰底配白字读不出来）
        Button[] rowBtns = {bm, b0, bp};
        for (int i = 0; i < rowBtns.length; i++) {
            styleDialogButton(rowBtns[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            rowBtns[i].setLayoutParams(lp);
        }
        box.addView(e);
        box.addView(row);

        AlertDialog d1 = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("歌词时间微调")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        int v = parse(e.getText().toString(), 0);
                        if (v > 20000) v = 20000;
                        if (v < -20000) v = -20000;
                        prefs.setLrcOffset(v);
                        lyric.setOffset(v);
                        Toast.makeText(MainActivity.this, "已保存：" + v + " ms", Toast.LENGTH_SHORT).show();
                    }
                })
                .create();
        whiteButtons(d1);
        dialog = d1;
        hideImeUntilTap(d1);
        d1.show();
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- 首次配置 ----------

    /**
     * 服务器配置。allowCancel=true 表示已配置过（用于重新配置），可取消。
     */
    private void showSetup(boolean allowCancel) {
        final boolean first = !prefs.configured();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText eUrl = new EditText(this);
        eUrl.setHint(R.string.hint_server);
        eUrl.setText(first ? "http://192.168.1.10:4533" : prefs.url());
        eUrl.setSingleLine(true);
        final EditText eUser = new EditText(this);
        eUser.setHint(R.string.hint_user);
        if (!first) eUser.setText(prefs.user());
        eUser.setSingleLine(true);
        final EditText ePass = new EditText(this);
        ePass.setHint(first ? R.string.hint_pass : R.string.hint_pass_keep);
        // M6：密码掩码输入
        ePass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ePass.setSingleLine(true);
        final CheckBox cb = new CheckBox(this);
        cb.setText("开机自动启动播放");
        cb.setChecked(first || prefs.autostart());

        white(eUrl);
        white(eUser);
        white(ePass);
        white(cb);
        styleInput(eUrl, 32);
        styleInput(eUser, 32);
        styleInput(ePass, 32);
        LinearLayout.LayoutParams lpCb = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        lpCb.topMargin = dp(2);
        cb.setLayoutParams(lpCb);
        cb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // R14：ROM 主题的复选框是绿色勾 + 深灰空框，与「纯白无彩色」的约定冲突，
        // 且空框在暗面板上几乎看不见 → 换纯白单色版本
        // （用 setButtonDrawable(int)，CompoundButton.setButton(Drawable) 是隐藏 API）
        cb.setButtonDrawable(R.drawable.cb_bg);
        box.addView(eUrl);
        box.addView(eUser);
        box.addView(ePass);
        box.addView(cb);

        // R14：内容高度必须压进对话框可视区，否则最下面的复选框会被裁掉；
        // 再套一层 ScrollView 兜底（系统字体放大时也不至于丢控件）。
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.setup_title)
                .setView(sc)
                .setCancelable(allowCancel);
        if (allowCancel) b.setNegativeButton(R.string.btn_cancel, null);
        b.setPositiveButton(R.string.btn_save, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                String u = eUrl.getText().toString().trim();
                String usr = eUser.getText().toString().trim();
                String pw = ePass.getText().toString();
                // 低8：仅允许 http/https，避免构造出非法 URL
                if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    Toast.makeText(MainActivity.this, R.string.err_url, Toast.LENGTH_LONG).show();
                    return;
                }
                if (usr.length() == 0) {
                    Toast.makeText(MainActivity.this, R.string.err_user, Toast.LENGTH_LONG).show();
                    return;
                }
                if (pw.length() == 0) pw = prefs.pass(); // 留空表示不修改密码
                prefs.save(u, usr, pw);
                prefs.setAutostart(cb.isChecked());
                Toast.makeText(MainActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                if (svc != null) svc.reconfigure();
                else startSvc();
            }
        });
        AlertDialog d2 = b.create();
        whiteButtons(d2);
        dialog = d2;
        hideImeUntilTap(d2);
        d2.show();
    }

    /**
     * R14：对话框里不要一打开就弹软键盘。
     *
     * 内容外面套了 ScrollView 做兜底后，ScrollView 会把焦点自动送给第一个
     * 输入框（focusableViewAvailable），系统随即弹出 IME；这块屏只有 480px
     * 高，键盘会盖住整个表单。改为默认收起，用户点哪个输入框再弹。
     *
     * 键盘弹出后用 ADJUST_PAN 而不是 ADJUST_RESIZE：这块屏上「标题栏 94px +
     * 按钮栏 106px」的旧式对话框根本没有 340px 余量留给键盘，RESIZE 会把输入框
     * 整段裁掉（截图实证）；PAN 是把窗口整体上移，正在输入的那个框留在键盘上方。
     */
    private static void hideImeUntilTap(AlertDialog d) {
        android.view.Window w = d.getWindow();
        if (w == null) return;
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
    }

    // 关于对话框面板：父主题的 AlertDialog 背景是半透明的，实测透光率仅约 3%
    // （封面背后 (0,8,20) vs 空白处 (1,2,2)），白字对比度仍有约 19:1，无需处理。
    // 注意别用 window.setBackgroundDrawable 去"修"它：那会变成「整屏暗底 +
    // 内层旧面板」两层叠加，屏幕边缘多出一条边框。
    // 另：@android:style/AlertDialog 是私有资源，aapt2 会直接报错，改不动。
}
