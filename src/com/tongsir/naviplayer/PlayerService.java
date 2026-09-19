package com.tongsir.naviplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerService extends Service {

    private static final String TAG = "Navi";
    private static final int NOTI_ID = 2718;
    private static final String CH = "navi_play";
    private boolean channelReady = false;

    public static final String ACT_PLAY = "com.tongsir.naviplayer.PLAY";
    public static final String ACT_NEXT = "com.tongsir.naviplayer.NEXT";
    public static final String ACT_PREV = "com.tongsir.naviplayer.PREV";

    public interface Callback {
        void onSongChanged(Song s, Bitmap cover);

        /** state 见 LyricView.ST_*；lines 为 null 表示当前没有可显示的歌词 */
        void onLyricsReady(Song s, List<LrcParser.Line> lines, int state);

        void onStateChanged(boolean playing);

        void onQueueReady(int size, String msg);
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final ExecutorService lrcPool = Executors.newSingleThreadExecutor();

    /**
     * R11：当前歌词任务句柄。切歌时取消上一个任务——在途的被中断、排队的直接移除。
     * 实测：不取消时快速切歌会在单线程池里积压十几个过期任务，每个最坏耗时
     * 一次 Navidrome 超时 + 五次 LRCLIB 往返，导致最新一首歌的歌词要等十几分钟
     * 才轮到，界面上长时间停在“歌词加载中…”。
     */
    private volatile java.util.concurrent.Future<?> lyricTask;

    private MediaPlayer mp;
    private PowerManager.WakeLock wl;
    private Prefs prefs;
    /** 会被 UI 线程的后台探测任务读取，reconfigure 又在主线程整体替换 → volatile */
    private volatile SubsonicClient api;
    private LrcSource lrc;

    /**
     * R1：队列被主线程 clear/addAll，同时后台线程（歌词取消回调、封面任务）
     * 会调用 current() 读它。普通 ArrayList 在这种交叉访问下可能抛
     * IndexOutOfBounds（线程池内未捕获异常会直接崩溃进程），故改用
     * CopyOnWriteArrayList：读无锁安全，写（仅换队列时）复制一次，开销可忽略。
     */
    private final List<Song> queue = new CopyOnWriteArrayList<Song>();
    /** R1：index 被主线程改、后台线程读，必须 volatile 保证可见性 */
    private volatile int index = 0;
    private boolean shuffle = false;
    private boolean playing = false;
    private boolean prepared = false;
    private boolean loading = false;
    /** 连续播放失败次数，用于防止错误循环（H2） */
    private int failCount = 0;
    private static final int MAX_FAIL = 5;
    /** WakeLock 最长持有 6 小时，异常路径兜底 */
    private static final long WAKELOCK_TIMEOUT = 6L * 3600 * 1000;

    private Callback callback;

    /** M3：用户意图标志。true=缓冲完成后应立即播放；切歌默认 true，手动暂停置 false */
    private boolean wantPlay = false;
    private boolean preparing = false;

    /** M2：最近一次封面/歌词，供 Activity 重连后回补 */
    private Bitmap lastCover = null;
    private List<LrcParser.Line> lastLines = null;
    private Song lastLyricSong = null;
    private int lastLyricState = LyricView.ST_LOADING;

    /**
     * 歌词请求序号。切歌/重配时自增，使所有在途的旧歌词任务与过期响应失效，
     * 避免“慢请求后返回 → 覆盖新歌”的串台。
     */
    private volatile int lrcSeq = 0;

    /**
     * R2：服务器世代号。每次 reconfigure 自增，使上一代“在途的取歌任务”
     * 结果作废——否则旧响应会覆盖新服务器的队列。
     */
    private volatile int apiEra = 0;

    /** M1：音频焦点 */
    private AudioManager am;
    private AudioManager.OnAudioFocusChangeListener afListener;

    public class LocalBinder extends Binder {
        public PlayerService get() {
            return PlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        shuffle = prefs.shuffle();
        lrc = new LrcSource(this, null);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "navi:play");
        wl.setReferenceCounted(false);
        startForeground(NOTI_ID, buildNotification("未开始播放", ""));
        initAudioFocus();
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer m) {
                next(true);
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int what, int extra) {
                Log.e(TAG, "player error " + what + "/" + extra);
                playing = false;
                notifyState();
                scheduleSkip();
                return true;
            }
        });
        if (prefs.configured()) {
            api = new SubsonicClient(prefs.url(), prefs.user(), prefs.pass());
            lrc = new LrcSource(this, api);
            loadQueue();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACT_PLAY.equals(a)) toggle();
            else if (ACT_NEXT.equals(a)) next(false);
            else if (ACT_PREV.equals(a)) prev();
        } else if (!loading && queue.isEmpty() && api != null) {
            // M9：被系统拉活（intent==null）时只建队列，不自动出声
            loadQueue(intent == null ? false : true);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return binder;
    }

    private void initAudioFocus() {
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        afListener = new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int change) {
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    // R3：永久丢失（别的应用开始播放）：放弃焦点，不再自动恢复
                    Log.i(TAG, "focus loss (permanent)");
                    pauseByFocus(false);
                } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    // R3：瞬时丢失（来电、导航播报）：只暂停，保留焦点与播放意图。
                    // 旧实现走 pause() → abandonFocus()，此后系统不会再回调
                    // AUDIOFOCUS_GAIN，打断结束后本应用永远无法自动恢复播放。
                    Log.i(TAG, "focus loss (transient) " + change);
                    pauseByFocus(true);
                } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    Log.i(TAG, "focus gain, wantPlay=" + wantPlay);
                    if (wantPlay && !playing) play();
                }
            }
        };
    }

    private boolean requestFocus() {
        if (am == null || afListener == null) return true;
        try {
            return am.requestAudioFocus(afListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    private void abandonFocus() {
        if (am == null || afListener == null) return;
        try {
            am.abandonAudioFocus(afListener);
        } catch (Exception e) {
            // ignore
        }
    }

    /** R6：WakeLock 获取统一入口。wl 理论可能为 null（PowerManager 拿不到），
     *  旧实现裸调 wl.acquire 一旦 NPE 会被外层 catch 吞掉，表现为“在播放却不持锁”。 */
    private void acquireLock() {
        try {
            if (wl != null && !wl.isHeld()) wl.acquire(WAKELOCK_TIMEOUT);
        } catch (Exception e) {
            Log.w(TAG, "wakelock acquire failed", e);
        }
    }

    /** R6：WakeLock 释放统一入口（wl 理论上可能为 null，且必须吞掉异常） */
    private void releaseLock() {
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void onDestroy() {
        abandonFocus();
        // R11：先取消在途歌词任务，再关池，尽量缩短网络等待
        java.util.concurrent.Future<?> lt = lyricTask;
        if (lt != null) lt.cancel(true);
        lyricTask = null;
        pool.shutdownNow();
        lrcPool.shutdownNow();
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception e) {
                // ignore
            }
        }
        releaseLock();
        // R6：释放对最后一张封面/歌词的静态引用，避免服务销毁后仍滞留
        lastCover = null;
        lastLines = null;
        lastLyricSong = null;
        lrc = null;
        super.onDestroy();
    }

    // ---------- 对外接口 ----------

    public void setCallback(Callback c) {
        this.callback = c;
        if (c == null) return;
        if (!queue.isEmpty() && index < queue.size()) {
            c.onQueueReady(queue.size(), null);
            Song s = current();
            // M2：Activity 重连后回补当前歌曲、封面与歌词，避免界面残缺
            if (s != null) {
                c.onSongChanged(s, s == lastLyricSong ? lastCover : null);
                c.onLyricsReady(s,
                        s == lastLyricSong ? lastLines : null,
                        s == lastLyricSong ? lastLyricState : LyricView.ST_LOADING);
            }
            c.onStateChanged(playing);
        }
    }

    public void reconfigure() {
        if (prefs.configured()) {
            // N7：先停当前播放并清旧队列，避免新旧服务器内容混播
            try {
                if (mp != null) {
                    mp.reset();
                }
            } catch (Exception e) {
                // ignore
            }
            playing = false;
            prepared = false;
            preparing = false;
            wantPlay = false;
            failCount = 0;
            history.clear();
            queue.clear();
            index = 0;
            lastCover = null;
            lastLines = null;
            lastLyricSong = null;
            lastLyricState = LyricView.ST_LOADING;
            lrcSeq++; // 作废旧服务器的在途歌词请求
            apiEra++; // R2：作废旧服务器的在途取歌请求
            // R2：必须复位 loading。旧实现若在队列加载途中重配，
            // 下面的 loadQueue 会被 "if (loading) return" 直接吞掉，
            // 新服务器队列永不加载，界面永久卡在旧状态。
            loading = false;
            if (callback != null) {
                callback.onSongChanged(null, null);
                callback.onLyricsReady(null, null, LyricView.ST_LOADING);
            }
            api = new SubsonicClient(prefs.url(), prefs.user(), prefs.pass());
            lrc = new LrcSource(this, api);
            loadQueue(true);
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    /**
     * 测一次到服务器的往返延迟（毫秒），失败或未配置返回 -1。
     *
     * 这是阻塞调用，只能在后台线程使用（UI 每秒探一次）。
     * 用 System.nanoTime 而不是 currentTimeMillis：后者会被 NTP 校时
     * 或系统时间调整拉偏，可能算出负数或突跳的延迟值。
     */
    public long pingServer() {
        SubsonicClient cli = api;
        if (cli == null) return -1;
        long t0 = System.nanoTime();
        if (!cli.ping()) return -1;
        long ms = (System.nanoTime() - t0) / 1000000L;
        return ms < 0 ? 0 : ms;
    }

    public Song current() {
        // R1：后台线程也会调用，任何越界都返回 null 而不是抛异常
        try {
            int i = index;
            if (i < 0 || i >= queue.size()) return null;
            return queue.get(i);
        } catch (Exception e) {
            return null;
        }
    }

    /** 播放器实际位置；只要已 prepared 就返回真实值（暂停时也保持） */
    public int position() {
        try {
            if (mp != null && prepared) return mp.getCurrentPosition();
        } catch (Exception e) {
            Log.w(TAG, "position", e);
        }
        return 0;
    }

    /** 优先用解码器实际时长，元数据只作兜底，避免进度与歌词基准不一致 */
    public int duration() {
        try {
            if (mp != null && prepared) {
                int d = mp.getDuration();
                if (d > 0) return d;
            }
        } catch (Exception e) {
            // ignore
        }
        Song s = current();
        return s != null ? s.duration : 0;
    }

    public void seekTo(int ms) {
        try {
            if (mp != null && prepared) mp.seekTo(ms);
        } catch (Exception e) {
            Log.e(TAG, "seek", e);
        }
    }

    public void toggle() {
        if (playing) pause();
        else play();
    }

    public void play() {
        if (queue.isEmpty()) {
            loadQueue(true);
            return;
        }
        try {
            if (mp.isPlaying()) return;
            wantPlay = true;
            if (prepared) {
                if (!requestFocus()) Log.w(TAG, "audio focus denied");
                mp.start();
                playing = true;
                acquireLock();
                notifyState();
                updateNotification();
            } else if (!preparing) {
                // M3：正在缓冲时不重复 setDataSource，只标记意图
                startCurrent(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "play", e);
        }
    }

    public void pause() {
        // N12：状态复位必须在 try 外，mp 抛异常时也不能丢
        wantPlay = false;
        playing = false;
        abandonFocus();
        try {
            if (mp.isPlaying()) mp.pause();
        } catch (Exception e) {
            Log.e(TAG, "pause", e);
        }
        releaseLock();
        notifyState();
        updateNotification();
    }

    /**
     * R3：由音频焦点变化引起的暂停。
     *
     * @param keepFocus true = 瞬时丢失：保留焦点与播放意图，收到 AUDIOFOCUS_GAIN 后自动恢复
     *                  false = 永久丢失：放弃焦点并清除播放意图
     */
    private void pauseByFocus(boolean keepFocus) {
        playing = false;
        try {
            if (mp != null && mp.isPlaying()) mp.pause();
        } catch (Exception e) {
            Log.e(TAG, "pauseByFocus", e);
        }
        if (!keepFocus) {
            wantPlay = false;
            abandonFocus();
        }
        releaseLock();
        notifyState();
        updateNotification();
    }

    public void setShuffle(boolean b) {
        shuffle = b;
        prefs.setShuffle(b);
    }

    /** 随机模式下的播放历史，用于“上一曲”回退（低1） */
    private final java.util.ArrayDeque<Integer> history = new java.util.ArrayDeque<Integer>();

    public void next(boolean auto) {
        if (queue.isEmpty()) return;
        // 低1：队列只有一首时自动播放不再无限重播同一首
        if (auto && queue.size() <= 1) {
            // N1：播完即停，必须复位状态，否则 UI 仍显示“播放中”
            Log.i(TAG, "single song queue, stop at end");
            playing = false;
            wantPlay = false;
            notifyState();
            updateNotification();
            return;
        }
        history.push(index);
        if (history.size() > 200) history.removeLast();
        if (shuffle && queue.size() > 1) {
            int n;
            do {
                n = (int) (Math.random() * queue.size());
            } while (n == index);
            index = n;
        } else {
            index = (index + 1) % queue.size();
        }
        startCurrent();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        // 低1：随机模式下“上一曲”应回退到真实播放过的曲目
        if (shuffle && !history.isEmpty()) {
            index = history.pop();
        } else {
            index = (index - 1 + queue.size()) % queue.size();
        }
        startCurrent();
    }

    // ---------- 内部 ----------

    private void loadQueue() {
        loadQueue(true);
    }

    /** 线程池关闭后（onDestroy 之后仍有延迟回调）提交任务会抛异常，这里统一兜住 */
    private boolean submit(ExecutorService es, Runnable r) {
        try {
            if (es == null || es.isShutdown()) return false;
            es.execute(r);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "submit rejected", e);
            return false;
        }
    }

    private void loadQueue(final boolean autoPlay) {
        if (loading || api == null) return;
        loading = true;
        final int era = apiEra;         // R2：绑定本次世代
        final SubsonicClient cli = api; // R2：绑定本次使用的客户端，避免读字段拿到新对象
        boolean ok = submit(pool, new Runnable() {
            public void run() {
                String err = null;
                List<Song> got = null;
                try {
                    got = cli.getSongs(500);
                } catch (Exception e) {
                    err = e.getMessage();
                    Log.e(TAG, "queue fail", e);
                }
                final String msg = err;
                final List<Song> res = got;
                main.post(new Runnable() {
                    public void run() {
                        // R2：期间重配过，这批结果属于上一代服务器，直接丢弃；
                        // 此时 loading 已由新一轮加载接管，不能在这里复位
                        if (era != apiEra) {
                            Log.i(TAG, "queue stale, dropped era=" + era);
                            return;
                        }
                        loading = false;
                        if (res != null && !res.isEmpty()) {
                            queue.clear();
                            queue.addAll(res);
                            index = 0;
                            if (callback != null) callback.onQueueReady(queue.size(), null);
                            startCurrent(autoPlay);
                        } else {
                            if (callback != null)
                                callback.onQueueReady(0, msg == null ? "取歌失败" : msg);
                        }
                    }
                });
            }
        });
        // R2：线程池已关闭时任务不会执行，loading 必须立即回滚，否则永久卡死
        if (!ok) loading = false;
    }

    /**
     * H2：播放失败后退避式跳下一曲。
     * 队列只有一首或连续失败超过阈值时停止，避免 1.5s/首 无限刷网络。
     */
    private void scheduleSkip() {
        if (queue.size() <= 1) return;
        failCount++;
        if (failCount > MAX_FAIL) {
            Log.e(TAG, "too many failures, stop");
            if (callback != null) callback.onQueueReady(0, "连续播放失败，已停止");
            return;
        }
        main.postDelayed(new Runnable() {
            public void run() {
                next(true);
            }
        }, 1500);
    }

    private void startCurrent() {
        startCurrent(true);
    }

    private void startCurrent(boolean auto) {
        final Song s = current();
        if (s == null || api == null) return;
        wantPlay = auto;
        preparing = true;
        prepared = false;
        try {
            mp.reset();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(api.streamUrl(s.id));
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    preparing = false;
                    failCount = 0;
                    int real = 0;
                    try {
                        real = m.getDuration();
                    } catch (Exception e) {
                        // ignore
                    }
                    Log.i(TAG, "prepared dur=" + real + " meta=" + s.duration);
                    // 元数据时长缺失/错误会让 LRCLIB 按错时长匹配（翻唱、live 版），
                    // 这里拿到解码器真实时长后重取一次，旧请求随 lrcSeq 自增作废
                    if (real > 0 && Math.abs((long) real - s.duration) > 3000L && current() == s) {
                        Log.i(TAG, "duration mismatch, refetch lyrics with " + real);
                        lrcSeq++;
                        fetchLyrics(s, real);
                    }
                    // M3：缓冲期间用户若已暂停，不应强制开播
                    if (!wantPlay) {
                        playing = false;
                        notifyState();
                        return;
                    }
                    if (!requestFocus()) Log.w(TAG, "audio focus denied");
                    m.start();
                    playing = true;
                    acquireLock();
                    notifyState();
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "startCurrent", e);
            // H2：播放源不可用时必须复位状态并跳下一曲，否则 playing 残留 true 卡死
            preparing = false;
            playing = false;
            notifyState();
            scheduleSkip();
            return;
        }
        updateNotification();
        notifySong(s, null);
        // 切歌立即作废上一首的在途请求，并把歌词置回「加载中」，界面当场清空旧歌词
        lrcSeq++;
        notifyLyrics(s, null, LyricView.ST_LOADING);
        fetchCover(s);
        fetchLyrics(s, 0);
    }

    private void fetchCover(final Song s) {
        // 封面：主线程池，尽快显示
        final SubsonicClient cli = api; // R2：绑定本次客户端，避免读到重配后的新对象
        if (cli == null) return;
        submit(pool, new Runnable() {
            public void run() {
                final Bitmap bmp = cli.cover(s.coverArt, 300);
                main.post(new Runnable() {
                    public void run() {
                        if (current() == s && bmp != null) {
                            lastCover = bmp;
                            if (callback != null) callback.onSongChanged(s, bmp);
                        }
                    }
                });
            }
        });
    }

    /**
     * 歌词：独立线程，网络慢也不拖累封面。
     * 每次提交都绑定当前 lrcSeq，回调前再校验一次，过期响应直接丢弃。
     */
    private void fetchLyrics(final Song s, final long durHint) {
        final int seq = lrcSeq;
        final LrcSource src = lrc; // R2：绑定本次歌词源，重配后旧任务不会打到新服务器
        final LrcSource.Cancel cancel = new LrcSource.Cancel() {
            public boolean isCancelled() {
                return seq != lrcSeq || current() != s;
            }
        };
        // R11：先取消上一个歌词任务，否则单线程池会被过期任务占满
        java.util.concurrent.Future<?> prev = lyricTask;
        if (prev != null) prev.cancel(true);
        lyricTask = null;
        Runnable job = new Runnable() {
            public void run() {
                final List<LrcParser.Line> lines = src.get(s, durHint, cancel);
                main.post(new Runnable() {
                    public void run() {
                        if (cancel.isCancelled()) {
                            Log.i(TAG, "lyrics stale, dropped: " + s.title);
                            return;
                        }
                        // 返回 null = 取词失败（网络异常/服务端报错），空表 = 确认无歌词
                        notifyLyrics(s, lines, lines == null
                                ? LyricView.ST_ERROR
                                : (lines.isEmpty() ? LyricView.ST_NONE : LyricView.ST_OK));
                    }
                });
            }
        };
        try {
            if (lrcPool.isShutdown()) return;
            lyricTask = lrcPool.submit(job);
        } catch (Exception e) {
            Log.w(TAG, "submit lyrics rejected", e);
        }
    }

    private void notifySong(Song s, Bitmap bmp) {
        lastCover = bmp;
        if (callback != null) callback.onSongChanged(s, bmp);
    }

    private void notifyLyrics(Song s, List<LrcParser.Line> lines, int state) {
        lastLyricSong = s;
        lastLines = lines;
        lastLyricState = state;
        if (callback != null) callback.onLyricsReady(s, lines, state);
    }

    private void notifyState() {
        if (callback != null) callback.onStateChanged(playing);
    }

    private PendingIntent pi(String act) {
        Intent i = new Intent(this, PlayerService.class).setAction(act);
        return PendingIntent.getService(this, act.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(String title, String sub) {
        if (Build.VERSION.SDK_INT >= 26 && !channelReady) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel c = new NotificationChannel(CH, "播放控制",
                        NotificationManager.IMPORTANCE_LOW);
                c.setSound(null, null);
                nm.createNotificationChannel(c);
                channelReady = true;
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(sub)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(content)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= 26) nb.setChannelId(CH);
        nb.addAction(R.drawable.ic_prev, "上一曲", pi(ACT_PREV));
        nb.addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                playing ? "暂停" : "播放", pi(ACT_PLAY));
        nb.addAction(R.drawable.ic_next, "下一曲", pi(ACT_NEXT));
        return nb.build();
    }

    private void updateNotification() {
        Song s = current();
        String t = s == null ? "未开始播放" : s.title;
        String a = s == null ? "" : s.artist;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(t, a));
    }
}
