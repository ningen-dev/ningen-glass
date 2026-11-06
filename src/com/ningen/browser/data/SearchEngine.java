package com.ningen.browser.data;

import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自研搜索内核：直连结果源自己抓、自己排序，不接任何搜索 SDK。
 *
 * v3.4 起的改造：
 * 1) 多入口兜底——cn.bing / www.bing 依次试，任一失败不影响出结果；
 * 2) 智能排序——位置、标题命中、摘要命中、域名可信度加权，垃圾结果沉底；
 * 3) 去重——按链接归一化 + 标题指纹双维度去重，跨源同一篇只留一条；
 * 4) 补量——首屏条目不足时自动取下一页合并，避免“只有三条结果”；
 * 5) 缓存——同一查询 5 分钟内直接命中内存，回退重搜秒开；
 * 6) 提速——连接 6s / 读 8s（原来是 9s/12s），失败立刻换入口而不是干等。
 */
public final class SearchEngine {

    private static final String UA = "Mozilla/5.0 (Linux; Android 14; V2531A) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 6000;
    private static final int READ_TIMEOUT = 8000;
    private static final long CACHE_TTL = 5 * 60 * 1000L;

    /** 每页条数，与 Bing RSS 的 count 一致 */
    private static final int PAGE_SIZE = 20;

    private static final String[] BING_HOSTS = {
            "https://cn.bing.com/search?format=rss&count=" + PAGE_SIZE + "&q=",
            "https://www.bing.com/search?format=rss&count=" + PAGE_SIZE + "&q="
    };

    private static final class Cache {
        List<Models.Hit> hits;
        long at;
    }

    private static final LinkedHashMap<String, Cache> CACHE =
            new LinkedHashMap<String, Cache>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cache> eldest) {
                    return size() > 24;
                }
            };

    private static final class Ranked {
        Models.Hit hit;
        int rank;
        float weight;
        float score;
    }

    private SearchEngine() {
    }

    /** 兼容旧调用：取第一页 */
    public static List<Models.Hit> search(String query) {
        return search(query, 0);
    }

    /** page 从 0 开始，每页 {@link #PAGE_SIZE} 条 */
    public static List<Models.Hit> search(String rawQuery, int page) {
        String q = normalizeQuery(rawQuery);
        if (TextUtils.isEmpty(q)) return new ArrayList<>();

        String key = page + "|" + q.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            Cache c = CACHE.get(key);
            if (c != null && System.currentTimeMillis() - c.at < CACHE_TTL) {
                return new ArrayList<>(c.hits);
            }
        }

        List<Ranked> pool = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        // 两个源并发跑，总耗时取慢的那个，而不是相加
        final String fq = q;
        final int fp = page;
        final AtomicReferenceArray<List<Models.Hit>> slots =
                new AtomicReferenceArray<List<Models.Hit>>(2);
        Thread t1 = new Thread(new Runnable() {
            @Override
            public void run() {
                slots.set(0, bing(fq, fp));
            }
        });
        Thread t2 = new Thread(new Runnable() {
            @Override
            public void run() {
                slots.set(1, sogou(fq, fp));
            }
        });
        t1.start();
        t2.start();
        try {
            t1.join(READ_TIMEOUT + 2000L);
            t2.join(READ_TIMEOUT + 2000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        List<Models.Hit> bingHits = slots.get(0);
        if (bingHits == null) bingHits = new ArrayList<>();
        List<Models.Hit> sogouHits = slots.get(1);
        if (sogouHits == null) sogouHits = new ArrayList<>();

        merge(pool, seen, bingHits, 1.0f);
        // Bing RSS 一页只吐九条左右；Bing 全挂时搜狗顶上去当主源
        merge(pool, seen, sogouHits, bingHits.isEmpty() ? 1.0f : 0.82f);
        if (pool.size() < 6) merge(pool, seen, bing(q, page + 1), 0.6f);

        List<Models.Hit> out = rank(q, pool);
        synchronized (CACHE) {
            Cache c = new Cache();
            c.hits = out;
            c.at = System.currentTimeMillis();
            CACHE.put(key, c);
        }
        return out;
    }

    /** 输入联想（给搜索框下拉用），拿不到就返回空列表，不抛异常 */
    public static List<String> suggest(String rawQuery) {
        String q = normalizeQuery(rawQuery);
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(q)) return out;
        try {
            String body = getText("https://suggestion.baidu.com/su?ie=utf-8&wd=" + enc(q), "GBK");
            if (body != null) {
                Matcher m = Pattern.compile("\"([^\"]{1,40})\"")
                        .matcher(body.substring(Math.max(0, body.indexOf('['))));
                while (m.find() && out.size() < 8) {
                    String s = m.group(1).trim();
                    if (!s.isEmpty() && !out.contains(s)) out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ================= 取数 =================

    private static List<Models.Hit> bing(String q, int page) {
        for (String host : BING_HOSTS) {
            StringBuilder url = new StringBuilder(host).append(enc(q));
            if (page > 0) url.append("&first=").append(page * 10 + 1);
            byte[] data = get(url.toString());
            if (data == null || data.length == 0) continue;
            List<Models.Hit> hits = parseRss(new String(data, StandardCharsets.UTF_8));
            if (!hits.isEmpty()) return hits;
        }
        return new ArrayList<>();
    }

    /** 搜狗只对桌面 UA 输出结构稳定的结果页，移动 UA 会拿到壳页面 */
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final Pattern SOGOU_WRAP = Pattern.compile("<div class=\"vrwrap\"", Pattern.DOTALL);
    private static final Pattern SOGOU_H3 =
            Pattern.compile("<h3[^>]*class=\"[^\"]*vr-title[^\"]*\"[^>]*>(.*?)</h3>", Pattern.DOTALL);
    private static final Pattern SOGOU_A = Pattern.compile("<a\\b[^>]*?href=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern SOGOU_DESC = Pattern.compile(
            "class=\"(?:fz-mid space-txt|text-layout|str_info|str-text-info)[^\"]*\"[^>]*>(.*?)</(?:div|p|span)>",
            Pattern.DOTALL);
    private static final Pattern SOGOU_SUM =
            Pattern.compile("class=\"title-summary\"[^>]*>(.*?)</", Pattern.DOTALL);

    /** 第二结果源：搜狗网页版，补 Bing RSS 一页不足的条目 */
    private static List<Models.Hit> sogou(String q, int page) {
        List<Models.Hit> out = new ArrayList<>();
        String url = "https://www.sogou.com/web?query=" + enc(q) + (page > 0 ? "&pn=" + page : "");
        String html = getText(url, UA_DESKTOP);
        if (html == null || html.length() < 3000) return out;

        List<Integer> at = new ArrayList<>();
        Matcher w = SOGOU_WRAP.matcher(html);
        while (w.find() && at.size() <= PAGE_SIZE) at.add(w.start());
        if (at.isEmpty()) return out;
        at.add(html.length());

        for (int i = 0; i + 1 < at.size() && out.size() < PAGE_SIZE; i++) {
            String block = html.substring(at.get(i), at.get(i + 1));
            Matcher h3 = SOGOU_H3.matcher(block);
            if (!h3.find()) continue;
            String inner = h3.group(1);
            int cut = inner.indexOf("<div");
            String title = clean(cut > 0 ? inner.substring(0, cut) : inner);
            if (TextUtils.isEmpty(title)) continue;

            String link = "";
            String head = block.length() > 4000 ? block.substring(0, 4000) : block;
            Matcher am = SOGOU_A.matcher(head);
            while (am.find()) {
                String href = am.group(1);
                if (href == null || href.startsWith("javascript")) continue;
                if (href.contains("zhanzhang.sogou.com")) continue;
                if (href.startsWith("/link")) {
                    link = "https://www.sogou.com" + href;
                    break;
                }
                if (href.startsWith("http")) {
                    link = href;
                    break;
                }
            }
            if (TextUtils.isEmpty(link)) continue;

            String desc = "";
            Matcher dm = SOGOU_DESC.matcher(block);
            if (dm.find()) desc = clean(dm.group(1));
            if (TextUtils.isEmpty(desc)) {
                Matcher sm = SOGOU_SUM.matcher(block);
                if (sm.find()) desc = clean(sm.group(1));
            }
            if (desc.length() > 160) desc = desc.substring(0, 160);

            out.add(new Models.Hit(title, tidy(link), desc));
        }
        return out;
    }

    private static byte[] get(String url) {
        return get(url, UA);
    }

    private static byte[] get(String url, String ua) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", TextUtils.isEmpty(ua) ? UA : ua);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Accept", "application/rss+xml,text/xml,text/html,*/*");
            int code = conn.getResponseCode();
            if (code >= 400) return null;
            InputStream in = conn.getInputStream();
            try {
                return readAll(in);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String getText(String url, String charset) {
        return getText(url, charset, UA);
    }

    private static String getText(String url, String charset, String ua) {
        byte[] data = get(url, ua);
        if (data == null) return null;
        try {
            return new String(data, charset);
        } catch (Throwable t) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ================= 解析 =================

    private static List<Models.Hit> parseRss(String xml) {
        List<Models.Hit> list = new ArrayList<>();
        if (TextUtils.isEmpty(xml)) return list;
        XmlPullParser p = Xml.newPullParser();
        boolean inItem = false;
        String title = null, link = null, desc = null;
        try {
            p.setInput(new StringReader(xml));
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                String name = p.getName();
                if (ev == XmlPullParser.START_TAG) {
                    if ("item".equals(name)) {
                        inItem = true;
                        title = link = desc = null;
                    } else if (inItem) {
                        if ("title".equals(name)) title = clean(nextText(p));
                        else if ("link".equals(name)) link = nextText(p).trim();
                        else if ("description".equals(name)) desc = clean(nextText(p));
                    }
                } else if (ev == XmlPullParser.END_TAG && inItem && "item".equals(name)) {
                    inItem = false;
                    if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(link)) {
                        list.add(new Models.Hit(title, tidy(link), desc == null ? "" : desc));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    private static String nextText(XmlPullParser p) {
        try {
            String s = p.nextText();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= 合并 / 排序 =================

    private static void merge(List<Ranked> pool, HashSet<String> seen, List<Models.Hit> hits, float weight) {
        if (hits == null || hits.isEmpty()) return;
        for (int i = 0; i < hits.size(); i++) {
            Models.Hit h = hits.get(i);
            if (h == null || TextUtils.isEmpty(h.title) || TextUtils.isEmpty(h.link)) continue;
            if (!h.link.startsWith("http")) continue;
            if (isJunk(h.link, h.title)) continue;
            String lk = linkKey(h.link);
            String tk = titleKey(h.title);
            if (seen.contains(lk) || seen.contains(tk)) continue;
            seen.add(lk);
            seen.add(tk);
            Ranked r = new Ranked();
            r.hit = h;
            r.rank = i;
            r.weight = weight;
            pool.add(r);
        }
    }

    private static List<Models.Hit> rank(String q, List<Ranked> pool) {
        String[] tokens = tokens(q);
        for (Ranked r : pool) {
            float score = r.weight / (1f + r.rank * 0.07f);
            String t = r.hit.title == null ? "" : r.hit.title.toLowerCase(Locale.ROOT);
            String d = r.hit.desc == null ? "" : r.hit.desc.toLowerCase(Locale.ROOT);
            for (String tk : tokens) {
                if (t.contains(tk)) score += 0.30f;
                if (d.contains(tk)) score += 0.08f;
            }
            score += domainBonus(r.hit.link);
            if (t.length() < 6) score -= 0.25f;      // 极短标题通常是导航/垃圾条目
            if (d.length() < 20) score -= 0.05f;     // 无摘要的条目略降权
            r.score = score;
        }
        java.util.Collections.sort(pool, new java.util.Comparator<Ranked>() {
            @Override
            public int compare(Ranked a, Ranked b) {
                if (b.score != a.score) return Float.compare(b.score, a.score);
                return Integer.compare(a.rank, b.rank);
            }
        });
        List<Models.Hit> out = new ArrayList<>(pool.size());
        for (Ranked r : pool) out.add(r.hit);
        return out;
    }

    private static String[] tokens(String q) {
        String[] raw = q.toLowerCase(Locale.ROOT).split("[\\s,，、+]+");
        List<String> keep = new ArrayList<>();
        for (String s : raw) {
            if (s.length() >= 2 && !keep.contains(s)) keep.add(s);
        }
        if (keep.isEmpty() && q.length() >= 2) keep.add(q.toLowerCase(Locale.ROOT));
        return keep.toArray(new String[0]);
    }

    private static final Map<String, Float> AUTHORITY = new HashMap<>();

    static {
        AUTHORITY.put("wikipedia.org", 0.30f);
        AUTHORITY.put("zhihu.com", 0.26f);
        AUTHORITY.put("github.com", 0.26f);
        AUTHORITY.put("gitee.com", 0.20f);
        AUTHORITY.put("stackoverflow.com", 0.24f);
        AUTHORITY.put("developer.android.com", 0.30f);
        AUTHORITY.put("developer.mozilla.org", 0.28f);
        AUTHORITY.put("csdn.net", 0.14f);
        AUTHORITY.put("cnblogs.com", 0.16f);
        AUTHORITY.put("juejin.cn", 0.16f);
        AUTHORITY.put("bilibili.com", 0.14f);
        AUTHORITY.put("zh.wikisource.org", 0.24f);
        AUTHORITY.put("gov.cn", 0.28f);
        AUTHORITY.put("edu.cn", 0.24f);
    }

    private static final String[] JUNK_HOSTS = {
            "bing.com", "microsofttranslator.com", "go.microsoft.com", "msn.com",
            "doubleclick.net", "googleadservices.com", "taobao.com/click", "jd.com/hls"
    };

    private static float domainBonus(String link) {
        String host = host(link);
        float best = 0f;
        for (Map.Entry<String, Float> e : AUTHORITY.entrySet()) {
            if (host.endsWith(e.getKey()) && e.getValue() > best) best = e.getValue();
        }
        return best;
    }

    private static boolean isJunk(String link, String title) {
        String host = host(link);
        if (TextUtils.isEmpty(host)) return true;
        for (String bad : JUNK_HOSTS) {
            if (host.equals(bad) || host.endsWith("." + bad) || (link.contains(bad) && bad.contains("/"))) {
                return true;
            }
        }
        String t = title.toLowerCase(Locale.ROOT);
        return t.contains("百度一下") || t.contains("点击下载安装") || t.equals("网页");
    }

    private static String host(String link) {
        try {
            String s = link;
            int i = s.indexOf("://");
            if (i >= 0) s = s.substring(i + 3);
            int j = s.indexOf('/');
            if (j >= 0) s = s.substring(0, j);
            int k = s.indexOf(':');
            if (k >= 0) s = s.substring(0, k);
            s = s.toLowerCase(Locale.ROOT);
            if (s.startsWith("www.")) s = s.substring(4);
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String linkKey(String link) {
        String s = tidy(link).toLowerCase(Locale.ROOT);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s;
    }

    private static String titleKey(String title) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < title.length() && sb.length() < 20; i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return "t:" + sb;
    }

    // ================= 工具 =================

    /** 去掉常见跟踪参数，留干净的地址给用户点 */
    private static String tidy(String link) {
        if (link == null) return "";
        String s = link.trim();
        int frag = s.indexOf('#');
        if (frag >= 0) s = s.substring(0, frag);
        int qm = s.indexOf('?');
        if (qm < 0) return s;
        String base = s.substring(0, qm);
        String query = s.substring(qm + 1);
        StringBuilder keep = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.isEmpty()) continue;
            String k = part;
            int eq = part.indexOf('=');
            if (eq > 0) k = part.substring(0, eq);
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.startsWith("utm_") || lk.equals("spm") || lk.equals("from")
                    || lk.equals("fr") || lk.equals("src") || lk.equals("ref")) {
                continue;
            }
            if (keep.length() > 0) keep.append('&');
            keep.append(part);
        }
        return keep.length() == 0 ? base : base + "?" + keep;
    }

    private static String normalizeQuery(String q) {
        if (q == null) return "";
        String s = q.replace('\u3000', ' ').trim();
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        String out = s.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&hellip;", "…");
        return out.replaceAll("\\s+", " ").trim();
    }
}
