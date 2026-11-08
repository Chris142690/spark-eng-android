package com.aurora.word;

import java.util.List;

/**
 * 后台补传会话。独立顶层类，不做成员内部类——d8 8.2.2 在「外部类 + 内部类」同批进 dex 时
 * 会抛 NullPointerException 且静默不产出 classes.dex（打出假 APK，装上去闪退）。
 */
public final class SyncTask implements Runnable {
    private final Db db;
    private final String base;
    private final List<String> sids;

    public SyncTask(Db db, String base, List<String> sids) {
        this.db = db;
        this.base = base;
        this.sids = sids;
    }

    public void run() {
        for (int i = 0; i < sids.size(); i++) {
            String sid = sids.get(i);
            try {
                org.json.JSONObject o = db.sessionJson(sid);
                if (o == null) continue;
                if (Sync.post(base + "/session", o.toString())) {
                    db.markSynced(sid);
                }
            } catch (Exception e) {
                android.util.Log.e("AURORA", "sync fail sid=" + sid, e);
            }
        }
    }
}
