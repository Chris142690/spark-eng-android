package com.aurora.word;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 会话 JSON 上传。
 *
 * 只往局域网中继 POST，不直连 GitHub——APK 里没有任何 token，反编译也拿不到凭据。
 * 中继跑在自己电脑上（sync_server.py），由它写文件并 git push。
 */
public final class Sync {
    private Sync() {}

    /** 返回是否 2xx。任何异常都当失败，绝不让网络问题冒泡到学习流程。 */
    public static boolean post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.flush();
            o.close();
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
