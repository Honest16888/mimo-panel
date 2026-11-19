package com.doubao.mimo.panel;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;

/**
 * 自适应桌面小组件：横条 / 方形 / 迷你三套排布按尺寸自动切换，
 * 拖动改大小时实时重排（只用缓存，不多打接口）。
 */
public class WidgetProvider extends AppWidgetProvider {

    private static final int SPAN = 14;
    private static final String K_BAL = "wBalance";
    private static final String K_DAILY = "wDaily";
    private static final String K_ERR = "wError";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        renderAll(context);
        refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id, Bundle newOptions) {
        renderAll(context);
    }

    @Override
    public void onEnabled(Context context) {
        refresh(context);
    }

    public static void refresh(final Context context) {
        final Context ctx = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                SharedPreferences sp = Net.prefs(ctx);
                try {
                    double bal = Net.balance(ctx)[0];
                    double[] daily = Net.dailySpend(ctx, SPAN);
                    sp.edit()
                            .putFloat(K_BAL, (float) bal)
                            .putString(K_DAILY, join(daily))
                            .putBoolean(K_ERR, false)
                            .apply();
                } catch (Exception e) {
                    sp.edit().putBoolean(K_ERR, true).apply();
                }
                renderAll(ctx);
            }
        }, "mimo-widget").start();
    }

    /**
     * 页面刷新成功后主动推送最新余额与近 14 天消费，
     * 避免小组件只能等 30 分钟一次的 refresh() 才更新。
     */
    public static void pushBalance(Context c, double bal, String dailyCsv) {
        SharedPreferences sp = Net.prefs(c);
        SharedPreferences.Editor e = sp.edit()
                .putFloat(K_BAL, (float) bal)
                .putBoolean(K_ERR, false);
        if (dailyCsv != null && dailyCsv.length() > 3) e.putString(K_DAILY, dailyCsv);
        e.putLong("wTime", System.currentTimeMillis());
        e.apply();
        renderAll(c.getApplicationContext());
    }

    public static void renderAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        ComponentName cn = new ComponentName(c, WidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        for (int id : ids) {
            mgr.updateAppWidget(id, build(c, mgr, id));
        }
    }

    private static String join(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(v[i]);
        }
        return sb.append("]").toString();
    }

    private static double[] cachedDaily(SharedPreferences sp) {
        try {
            String s = sp.getString(K_DAILY, "");
            if (s == null || s.length() < 4) return null;
            JSONArray a = new JSONArray(s);
            double[] out = new double[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.optDouble(i, 0);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static RemoteViews build(Context c, AppWidgetManager mgr, int id) {
        SharedPreferences sp = Net.prefs(c);
        boolean err = sp.getBoolean(K_ERR, false);
        boolean hasBal = sp.contains(K_BAL);
        double bal = sp.getFloat(K_BAL, 0f);
        double[] daily = cachedDaily(sp);

        int wDp = 250, hDp = 110;
        try {
            Bundle o = mgr.getAppWidgetOptions(id);
            wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp);
            hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp);
        } catch (Throwable ignored) {
        }

        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_mimo);

        double today = (daily != null && daily.length > 0) ? daily[daily.length - 1] : 0;
        double avg = avg7(daily);
        String money = hasBal ? "\u00a5" + Net.money(bal) : "\u2014";
        String sub = !hasBal ? (err ? "未配置 Key" : "读取中\u2026")
                : ("今日 \u00a5" + Net.money(today) + " \u00b7 均 \u00a5" + Net.money(avg));
        String sub2 = "";
        if (hasBal && avg > 0.0001) {
            int left = (int) Math.floor(bal / avg);
            sub2 = left <= 0 ? "不足 1 天用量" : "可用约 " + left + " 天";
        }

        rv.setTextViewText(R.id.wide_bal, money);
        rv.setTextViewText(R.id.wide_sub, sub);
        rv.setTextViewText(R.id.wide_sub2, sub2);
        rv.setTextViewText(R.id.sq_bal, money);
        rv.setTextViewText(R.id.sq_sub, sub);
        rv.setTextViewText(R.id.mini_bal, money);
        rv.setTextViewText(R.id.mini_sub, sub2.length() > 0 ? sub2 : sub);

        int mode; // 0 横条, 1 方形, 2 迷你
        if (wDp < 115 || hDp < 75) {
            mode = 2;
        } else if (wDp >= 205 && (float) wDp / hDp >= 1.5f) {
            mode = 0;
        } else {
            mode = 1;
        }

        rv.setViewVisibility(R.id.box_wide, mode == 0 ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.box_square, mode == 1 ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.box_mini, mode == 2 ? View.VISIBLE : View.GONE);

        if (mode == 0) {
            rv.setImageViewBitmap(R.id.wide_chart, chart(daily, 118, 66));
        } else if (mode == 1) {
            int ch = Math.max(40, hDp - 92);
            rv.setImageViewBitmap(R.id.sq_chart, chart(daily, Math.max(96, wDp - 28), ch));
        }

        Intent i = new Intent(c, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        rv.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 2, i, flags));
        return rv;
    }

    private static double avg7(double[] v) {
        if (v == null || v.length == 0) return 0;
        int n = 0;
        double s = 0;
        for (int i = Math.max(0, v.length - 7); i < v.length; i++) { s += v[i]; n++; }
        return n > 0 ? s / n : 0;
    }

    private static Bitmap chart(double[] vals, int wDp, int hDp) {
        final float density = 2.5f;
        int W = Math.max(120, (int) (wDp * density));
        int H = Math.max(60, (int) (hDp * density));
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        boolean has = false;
        if (vals != null) for (double v : vals) if (v > 0) has = true;
        if (!has) {
            p.setColor(0xFF6B7381);
            p.setTextSize(Math.max(20f, H * 0.22f));
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("暂无趋势", W / 2f, H / 2f + H * 0.08f, p);
            return bmp;
        }

        double max = 0;
        for (double v : vals) max = Math.max(max, v);
        if (max <= 0) max = 1;

        float padT = H * 0.10f, padB = H * 0.14f, padL = W * 0.02f, padR = W * 0.03f;
        float plotW = W - padL - padR, plotH = H - padT - padB;
        int n = vals.length;

        p.setColor(0x22FFFFFF);
        p.setStrokeWidth(1f);
        cv.drawLine(padL, H - padB, W - padR, H - padB, p);

        Path area = new Path();
        Path line = new Path();
        for (int i = 0; i < n; i++) {
            float x = padL + plotW * (n == 1 ? 0.5f : i / (float) (n - 1));
            float y = H - padB - (float) (vals[i] / max) * plotH;
            if (i == 0) {
                line.moveTo(x, y);
                area.moveTo(x, H - padB);
                area.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(padL + plotW, H - padB);
        area.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0, padT, 0, H - padB, 0x66FF7A00, 0x0AFF7A00, Shader.TileMode.CLAMP));
        cv.drawPath(area, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(0xFFFF9F2E);
        stroke.setStrokeWidth(Math.max(3f, H * 0.028f));
        stroke.setStyle(Paint.Style.STROKE);
        cv.drawPath(line, stroke);

        float lx = padL + plotW, ly = H - padB - (float) (vals[n - 1] / max) * plotH;
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFC46B);
        cv.drawCircle(lx - 2, ly, Math.max(4f, H * 0.03f), dot);
        return bmp;
    }
}
