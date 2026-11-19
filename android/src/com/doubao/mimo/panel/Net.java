package com.doubao.mimo.panel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 共享的配置与网络访问：小组件、后台告警、主界面都用这一套 */
public class Net {

    public static final String PREF = "mimo_panel";
    public static final String BALANCE_URL = "https://api.xiaomimimo.com/v1/platform/balance";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String get(String url, String key) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("api-key", key);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MiMoPanel/2.0 (Android)");

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) throw new Exception("HTTP " + code + " " + sb);
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 返回 {总可用余额, 赠金余额}，CNY 优先 */
    public static double[] balance(Context c) throws Exception {
        String key = prefs(c).getString("apiKey", "");
        if (key == null || key.length() == 0) throw new Exception("no key");
        JSONObject o = new JSONObject(get(BALANCE_URL, key));
        JSONArray arr = o.optJSONArray("balance_infos");
        if (arr == null || arr.length() == 0) throw new Exception("no balance_infos");
        JSONObject info = arr.optJSONObject(0);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null && "CNY".equalsIgnoreCase(it.optString("currency"))) info = it;
        }
        if (info == null) throw new Exception("empty balance");
        double total = info.optDouble("total_available", Double.NaN);
        if (Double.isNaN(total)) {
            total = info.optDouble("cash_balance", 0) + info.optDouble("gift_balance", 0);
        }
        return new double[]{ total, info.optDouble("gift_balance", 0) };
    }

    /** 近 n 天每日消费（按日期升序），失败返回空 */
    public static double[] dailySpend(Context c, int days) throws Exception {
        String key = prefs(c).getString("apiKey", "");
        if (key == null || key.length() == 0) throw new Exception("no key");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        String start = f.format(new Date(now - (long) (days - 1) * 86400000L));
        String end = f.format(new Date(now));
        String url = "https://api.xiaomimimo.com/v1/platform/usage/daily?start_date=" + start + "&end_date=" + end;
        JSONObject o = new JSONObject(get(url, key));
        JSONArray arr = o.optJSONArray("data");
        double[] out = new double[days];
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            String d = r.optString("date", "");
            if (d.length() < 10) continue;
            long t = f.parse(d).getTime();
            int idx = days - 1 - (int) ((now - t) / 86400000L);
            if (idx >= 0 && idx < days) out[idx] += r.optDouble("consumed_amount", 0);
        }
        return out;
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String money(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    public static String clock() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }
}
