package com.doubao.mimo.panel;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView web;
    private int insetTop, insetBottom, insetLeft, insetRight;
    private int bottomPadDp = 0;    // -1 = 自动（跟随系统导航条高度），0 = 不留白
    private static final String PREF = "mimo_panel";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int pageBg = Color.parseColor("#0b0d10");
        // 标准窗口：系统自己给状态栏和导航条留位，应用窗口只画在两者之间。
        // 之前强行边到边会让窗口比系统给的应用区域更大，后台卡片错位、底部露白。
        w.setStatusBarColor(pageBg);
        w.setNavigationBarColor(pageBg);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(pageBg));
        w.getDecorView().setBackgroundColor(pageBg);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0d10"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDefaultTextEncodingName("UTF-8");
        s.setSupportZoom(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        // 跟随系统字体大小设置，避免无视无障碍字号
        int fontScale = Math.round(getResources().getConfiguration().fontScale * 100f);
        s.setTextZoom(Math.max(85, Math.min(160, fontScale)));

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "MimoNative");

        // 保留系统长按菜单（选中 / 全选 / 复制 / 粘贴）
        web.setLongClickable(true);

        // 记录系统安全区（状态栏 / 挖孔 / 手势条），交给页面用 CSS 变量吸收，
        // 这样状态栏区域仍然是页面自己的渐变背景，不会出现色块断层。
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    insetTop = bars.top;
                    insetBottom = bars.bottom;
                    insetLeft = bars.left;
                    insetRight = bars.right;
                } else {
                    insetTop = insets.getSystemWindowInsetTop();
                    insetBottom = insets.getSystemWindowInsetBottom();
                    insetLeft = insets.getSystemWindowInsetLeft();
                    insetRight = insets.getSystemWindowInsetRight();
                }
                // 顶部交给页面处理（状态栏下面继续显示页面渐变）；
                // 底部导航条/手势条不透明，必须让 WebView 自己让开，否则会盖住内容。
                applyBottomPad(v);
                return insets;
            }
        });
        web.requestApplyInsets();

        bottomPadDp = getSharedPreferences(PREF, MODE_PRIVATE).getInt("bottomPadDp", 0);

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        // 恢复后台预警调度
        Alert.ensureChannel(this);
        Alert.schedule(this);
        WidgetProvider.refresh(this);
    }

    /** 底部留白：自动=系统导航条高度；手动值时按 dp 计 */
    private void applyBottomPad(View v) {
        int px;
        if (bottomPadDp < 0) {
            px = insetBottom;
        } else {
            px = Math.round(bottomPadDp * getResources().getDisplayMetrics().density);
        }
        if (v.getPaddingBottom() != px
                || v.getPaddingLeft() != insetLeft || v.getPaddingRight() != insetRight) {
            v.setPadding(insetLeft, 0, insetRight, px);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** JS 桥：原生 HTTP 请求 + 剪贴板读取 */
    private class Bridge {

        /** 在 Bridge 线程同步发起 HTTPS 请求，返回 {status, body, error} */
        @JavascriptInterface
        public String get(String url, String apiKey) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("api-key", apiKey);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "MiMoPanel/1.1 (Android)");

                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = readAll(in);

                JSONObject o = new JSONObject();
                o.put("status", code);
                o.put("body", body);
                return o.toString();
            } catch (Exception e) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("status", 0);
                    o.put("body", "");
                    o.put("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                    return o.toString();
                } catch (Exception ignore) {
                    return "{\"status\":0,\"body\":\"\",\"error\":\"unknown\"}";
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        /** 同步后台配置：Key、阈值、预警开关、检查间隔（小时） */
        @JavascriptInterface
        public void setConfig(String apiKey, double threshold, boolean alertEnabled, int hours) {
            Net.prefs(MainActivity.this).edit()
                    .putString("apiKey", apiKey == null ? "" : apiKey)
                    .putFloat("alertThreshold", (float) threshold)
                    .putBoolean("alertEnabled", alertEnabled)
                    .putInt("alertHours", hours <= 0 ? 6 : hours)
                    .apply();
            if (alertEnabled) {
                Alert.schedule(MainActivity.this);
            } else {
                Alert.cancel(MainActivity.this);
            }
            WidgetProvider.refresh(MainActivity.this);
        }

        /** 申请通知权限（Android 13+） */
        @JavascriptInterface
        public void requestNotifyPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 88);
                    }
                }
            });
        }

        /** 小组件是否已被系统注册 + 当前桌面实例数 */
        @JavascriptInterface
        public String widgetStatus() {
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(MainActivity.this);
                ComponentName me = new ComponentName(MainActivity.this, WidgetProvider.class);
                boolean found = false;
                for (AppWidgetProviderInfo info : m.getInstalledProviders()) {
                    if (info.provider != null && me.equals(info.provider)) { found = true; break; }
                }
                int n = m.getAppWidgetIds(me).length;
                return (found ? "已注册" : "系统未识别") + " · 桌面实例 " + n;
            } catch (Throwable t) {
                return "检测失败 " + t.getClass().getSimpleName();
            }
        }

        /** 请求系统直接把小组件放到桌面（不走桌面列表） */
        @JavascriptInterface
        public boolean pinWidget() {
            if (Build.VERSION.SDK_INT < 26) return false;
            try {
                final AppWidgetManager m = AppWidgetManager.getInstance(MainActivity.this);
                if (!m.isRequestPinAppWidgetSupported()) return false;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        m.requestPinAppWidget(new ComponentName(MainActivity.this, WidgetProvider.class),
                                null, null);
                    }
                });
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /** 立即跑一次后台检查（调试/手动触发） */
        @JavascriptInterface
        public void checkNow() {
            sendBroadcast(new android.content.Intent(MainActivity.this, AlertReceiver.class));
        }

        /** 设置底部留白（dp，-1 为自动） */
        @JavascriptInterface
        public void setBottomPad(final int dp) {
            bottomPadDp = dp;
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("bottomPadDp", dp).apply();
            runOnUiThread(new Runnable() {
                @Override public void run() { if (web != null) applyBottomPad(web); }
            });
        }

        /** 布局诊断数字，便于定位安全区问题 */
        @JavascriptInterface
        public String metrics() {
            float density = getResources().getDisplayMetrics().density;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int webH = web == null ? 0 : web.getHeight();
            int padB = web == null ? 0 : web.getPaddingBottom();
            return "insetTop=" + insetTop + ", insetBottom=" + insetBottom
                    + ", density=" + density
                    + ", screenH=" + screenH + ", webH=" + webH + ", padBottom=" + padB;
        }

        /** 返回系统安全区：top,bottom,left,right（单位 px） */
        @JavascriptInterface
        public String insets() {
            return insetTop + "," + insetBottom + "," + insetLeft + "," + insetRight;
        }

        /** 读取剪贴板纯文本，失败或为空时返回空串 */
        @JavascriptInterface
        public String clipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) MainActivity.this.getSystemService(CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        private String readAll(InputStream in) throws IOException {
            if (in == null) return "";
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        }
    }
}
