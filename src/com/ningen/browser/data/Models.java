package com.ningen.browser.data;

/** 数据模型集合，保持轻量、可序列化。 */
public final class Models {

    private Models() {}

    public static class DownloadItem {
        public String name = "";
        public String url = "";
        public long at;
        public long size;   // 已下载字节
        public long total;  // 文件总字节，0 表示服务器没给
        public int state; // 0 进行中 1 完成 2 失败 3 已暂停
        public String path = "";

        public static DownloadItem of(String name, String url) {
            DownloadItem d = new DownloadItem();
            d.name = name;
            d.url = url;
            d.at = System.currentTimeMillis();
            return d;
        }

        public String encode() {
            return esc(name) + "\u0002" + esc(url) + "\u0002" + at + "\u0002" + size + "\u0002" + state
                    + "\u0002" + esc(path) + "\u0002" + total;
        }

        public static DownloadItem decode(String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\u0002", -1);
            if (p.length < 5) return null;
            DownloadItem d = new DownloadItem();
            d.name = unesc(p[0]);
            d.url = unesc(p[1]);
            try {
                d.at = Long.parseLong(p[2]);
                d.size = Long.parseLong(p[3]);
                d.state = Integer.parseInt(p[4]);
                if (p.length >= 6) d.path = unesc(p[5]);
                if (p.length >= 7) d.total = Long.parseLong(p[6]);
            } catch (Exception e) {
                return null;
            }
            return d;
        }
    }

    public static class Hit {
        public String title = "";
        public String link = "";
        public String desc = "";

        public Hit(String t, String l, String d) {
            title = t;
            link = l;
            desc = d;
        }
    }

    public static class FeedItem {
        public String title;
        public String sub;
        public String url;
        public String tag;
        public int tone;

        public FeedItem(String title, String sub, String url, String tag, int tone) {
            this.title = title;
            this.sub = sub;
            this.url = url;
            this.tag = tag;
            this.tone = tone;
        }
    }

    public static class Msg {
        public String title;
        public String body;
        public String time;
        public int type; // 0 系统 1 下载 2 提示

        public Msg(String t, String b, String tm, int ty) {
            title = t;
            body = b;
            time = tm;
            type = ty;
        }
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("\u0002", " ").replace("\u0001", " ");
    }

    static String unesc(String s) {
        return s == null ? "" : s;
    }
}
