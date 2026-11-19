package com.doubao.mimo.panel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** 定时到点检查余额，低于阈值就发通知；每天最多提醒一次 */
public class AlertReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Alert.schedule(ctx);
        }
        if (!Net.prefs(ctx).getBoolean("alertEnabled", false)) return;

        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    double[] b = Net.balance(ctx);
                    double total = b[0];
                    SharedPreferences sp = Net.prefs(ctx);
                    double threshold = sp.getFloat("alertThreshold", 10f);
                    String today = Net.today();
                    String last = sp.getString("lastAlertDate", "");
                    if (total < threshold && !today.equals(last)) {
                        Alert.post(ctx, "MiMo 余额偏低",
                                "账户总可用余额 ¥" + Net.money(total) + "，已低于预警阈值 ¥" + Net.money(threshold));
                        sp.edit().putString("lastAlertDate", today).apply();
                    }
                    sp.edit().putString("lastCheck", Net.clock()).apply();
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }
        }, "mimo-alert").start();
    }
}
