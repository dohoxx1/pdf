package com.example.comicviewer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ReaderView.Listener {

    private static final int REQ_OPEN = 1;
    private static final float OVERSAMPLE = 1.5f;   // 확대 대비 해상도 여유

    private final Object lock = new Object();
    private PdfiumCore core;
    private PdfDocument doc;
    private ParcelFileDescriptor pfd;
    private int pageCount, curPage;

    // 설정
    private boolean coverAlone = true;   // 표지 단독 페이지
    private boolean rtl = true;          // 우→좌 (일본식)
    private int layoutMode = 0;          // 0 자동(가로=양면), 1 양면, 2 단면

    private SharedPreferences prefs;
    private String docKey;

    private ReaderView reader;
    private LinearLayout bar;
    private SeekBar seek;
    private TextView pageText;
    private Button btnMode, btnCover, btnDir;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(Integer k, Bitmap v) { return v.getByteCount(); }
    };
    private volatile int cacheW, cacheH;
    private volatile int token;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("comic", MODE_PRIVATE);
        coverAlone = prefs.getBoolean("coverAlone", true);
        rtl = prefs.getBoolean("rtl", true);
        layoutMode = prefs.getInt("layoutMode", 0);
        core = new PdfiumCore(this);
        buildUi();

        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) {
            String last = prefs.getString("lastUri", null);
            if (last != null) uri = Uri.parse(last);
        }
        if (uri != null) openUri(uri, false);
    }

    // ---------- UI ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private Button makeBtn(String t, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setTextSize(14);
        bt.setOnClickListener(l);
        bt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return bt;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        reader = new ReaderView(this);
        reader.setListener(this);
        root.addView(reader, new FrameLayout.LayoutParams(-1, -1));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xDD202020);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(this);
        r1.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        pageText = new TextView(this);
        pageText.setTextColor(Color.WHITE);
        pageText.setGravity(Gravity.CENTER);
        pageText.setMinWidth(dp(130));
        r1.addView(pageText, new LinearLayout.LayoutParams(-2, -2));
        bar.addView(r1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r2 = new LinearLayout(this);
        r2.addView(makeBtn("열기", new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        }));
        btnMode = makeBtn("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                layoutMode = (layoutMode + 1) % 3;
                prefs.edit().putInt("layoutMode", layoutMode).apply();
                showCurrent();
            }
        });
        btnCover = makeBtn("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                coverAlone = !coverAlone;
                prefs.edit().putBoolean("coverAlone", coverAlone).apply();
                showCurrent();
            }
        });
        btnDir = makeBtn("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                rtl = !rtl;
                prefs.edit().putBoolean("rtl", rtl).apply();
                showCurrent();
            }
        });
        r2.addView(btnMode);
        r2.addView(btnCover);
        r2.addView(btnDir);
        bar.addView(r2, new LinearLayout.LayoutParams(-1, -2));

        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) pageText.setText((p + 1) + " / " + pageCount);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (doc != null) { curPage = s.getProgress(); showCurrent(); }
            }
        });
        updateBar();
    }

    private void updateBar() {
        btnMode.setText("보기: " + (layoutMode == 0 ? "자동" : layoutMode == 1 ? "양면" : "단면"));
        btnCover.setText("표지 단독: " + (coverAlone ? "ON" : "OFF"));
        btnDir.setText(rtl ? "우→좌 (일본식)" : "좌→우");
        if (doc == null) { pageText.setText("-"); seek.setEnabled(false); return; }
        seek.setEnabled(true);
        seek.setMax(Math.max(1, pageCount - 1));
        seek.setProgress(curPage);
        seek.setRotation(rtl ? 180f : 0f);
        boolean two = isTwo();
        int[] pg = pagesOfSpread(spreadOfPage(curPage, two), two);
        String t = (pg[0] + 1) + (pg[1] >= 0 ? "-" + (pg[1] + 1) : "");
        pageText.setText(t + " / " + pageCount);
    }

    // ---------- 파일 열기 ----------
    private void pickFile() {
        Intent i = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Toast.makeText(this, "파일 선택기를 열 수 없습니다", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (Build.VERSION.SDK_INT >= 19) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
            }
            openUri(uri, true);
        }
    }

    private void openUri(Uri uri, boolean remember) {
        try {
            ParcelFileDescriptor f = getContentResolver().openFileDescriptor(uri, "r");
            closeDoc();
            synchronized (lock) {
                pfd = f;
                doc = core.newDocument(f);
                pageCount = core.getPageCount(doc);
            }
            cache.evictAll();
            docKey = Integer.toHexString(uri.toString().hashCode());
            if (remember) prefs.edit().putString("lastUri", uri.toString()).apply();
            else prefs.edit().putString("lastUri", uri.toString()).apply();
            curPage = Math.max(0, Math.min(pageCount - 1, prefs.getInt("pg_" + docKey, 0)));
            bar.setVisibility(View.GONE);
            showCurrent();
        } catch (Exception e) {
            Toast.makeText(this, "PDF를 열 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            doc = null;
            updateBar();
        }
    }

    private void closeDoc() {
        token++;
        synchronized (lock) {
            if (doc != null) {
                try { core.closeDocument(doc); } catch (Throwable ignored) { }
                doc = null;
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) { }
                pfd = null;
            }
        }
    }

    // ---------- 스프레드 계산 ----------
    private boolean isTwo() {
        return layoutMode == 1 || (layoutMode == 0 && reader.getWidth() > reader.getHeight());
    }

    private int spreadCount(boolean two) {
        if (!two) return pageCount;
        return coverAlone ? 1 + pageCount / 2 : (pageCount + 1) / 2;
    }

    private int spreadOfPage(int p, boolean two) {
        if (!two) return p;
        if (coverAlone) return p == 0 ? 0 : (p + 1) / 2;
        return p / 2;
    }

    /** {첫 페이지, 둘째 페이지(없으면 -1)} */
    private int[] pagesOfSpread(int i, boolean two) {
        if (!two) return new int[]{i, -1};
        if (coverAlone) {
            if (i == 0) return new int[]{0, -1};
            int a = 2 * i - 1, b = 2 * i;
            return new int[]{a, b < pageCount ? b : -1};
        }
        int a = 2 * i, b = 2 * i + 1;
        return new int[]{a, b < pageCount ? b : -1};
    }

    // ---------- 표시 ----------
    private void showCurrent() {
        if (doc == null || reader.getWidth() == 0 || reader.getHeight() == 0) { updateBar(); return; }
        final boolean two = isTwo();
        final int idx = spreadOfPage(curPage, two);
        final int[] pg = pagesOfSpread(idx, two);
        curPage = pg[0];
        final int sw = two ? reader.getWidth() / 2 : reader.getWidth();
        final int sh = reader.getHeight();
        if (sw != cacheW || sh != cacheH) { cache.evictAll(); cacheW = sw; cacheH = sh; }
        final boolean center = two && coverAlone && pg[0] == 0 && pg[1] < 0;
        final boolean r = rtl;
        final int my = ++token;
        updateBar();
        if (docKey != null) prefs.edit().putInt("pg_" + docKey, curPage).apply();

        exec.execute(new Runnable() {
            @Override public void run() {
                final Bitmap a = getPage(pg[0], sw, sh);
                final Bitmap b = pg[1] >= 0 ? getPage(pg[1], sw, sh) : null;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (my == token) reader.setContent(a, b, two, r, center);
                    }
                });
                // 앞/뒤 스프레드 미리 렌더링
                int n = spreadCount(two);
                int[] ds = {1, -1};
                for (int d : ds) {
                    int j = idx + d;
                    if (j < 0 || j >= n || my != token) continue;
                    int[] q = pagesOfSpread(j, two);
                    getPage(q[0], sw, sh);
                    if (q[1] >= 0 && my == token) getPage(q[1], sw, sh);
                }
            }
        });
    }

    private Bitmap getPage(int p, int sw, int sh) {
        Bitmap hit = cache.get(p);
        if (hit != null) return hit;
        synchronized (lock) {
            if (doc == null) return null;
            try {
                core.openPage(doc, p);
                int pw = Math.max(1, core.getPageWidthPoint(doc, p));
                int ph = Math.max(1, core.getPageHeightPoint(doc, p));
                float s = Math.min((float) sw / pw, (float) sh / ph) * OVERSAMPLE;
                s = Math.min(s, 2400f / Math.max(pw, ph));
                int bw = Math.max(1, Math.round(pw * s));
                int bh = Math.max(1, Math.round(ph * s));
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                core.renderPageBitmap(doc, bmp, p, 0, 0, bw, bh);
                if (sw == cacheW && sh == cacheH) cache.put(p, bmp);
                return bmp;
            } catch (OutOfMemoryError oom) {
                cache.evictAll();
                return null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ---------- 이동 ----------
    private void go(int delta) {
        if (doc == null) return;
        boolean two = isTwo();
        int j = spreadOfPage(curPage, two) + delta;
        if (j < 0 || j >= spreadCount(two)) return;
        curPage = pagesOfSpread(j, two)[0];
        showCurrent();
    }

    @Override public void onTap(float x) {
        if (x < 0.3f) go(rtl ? 1 : -1);
        else if (x > 0.7f) go(rtl ? -1 : 1);
        else bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    @Override public void onSwipe(boolean toRight) {
        // 일본식: 손가락을 오른쪽으로 밀면 다음 페이지
        go(toRight == rtl ? 1 : -1);
    }

    @Override public void onSized() { showCurrent(); }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { go(1); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_UP) { go(-1); return true; }
        return super.onKeyDown(code, e);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
        closeDoc();
    }
}
