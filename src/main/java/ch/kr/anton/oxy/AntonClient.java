package ch.kr.anton.oxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Thin HTTP client for Anton's public search API.
 *
 * <p>Performs a live {@code GET {base}/api/{register}?search=...&amp;format=json}
 * for every query (this is the whole point of the plugin — it does NOT bulk-download
 * the full register the way the older zbz approach did). The search endpoints are
 * public, so no authentication is sent.</p>
 */
final class AntonClient {

    private final Config config;

    AntonClient(Config config) {
        this.config = config;
    }

    /**
     * @param register "actors" or "places"
     * @param query    free-text search term (matched by Anton against name,
     *                 alternative names, variants, abbreviations, notes)
     */
    List<AntonEntity> search(String register, String query) throws IOException {
        String url = config.getBaseUrl()
                + "/api/" + register
                + "?format=json"
                + "&perPage=" + config.getPerPage()
                + "&search=" + enc(query);

        HttpURLConnection con = open(url);
        try {
            con.setRequestMethod("GET");
            con.setRequestProperty("Accept", "application/json");
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);

            int code = con.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? con.getInputStream() : con.getErrorStream();
            String body = read(is);
            if (code < 200 || code >= 300) {
                throw new IOException("Anton HTTP " + code + " für " + url + "\n" + shorten(body));
            }
            return parse(register, body);
        } finally {
            con.disconnect();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<AntonEntity> parse(String register, String body) {
        List<AntonEntity> out = new ArrayList<AntonEntity>();
        Object root = Json.parse(body);
        if (!(root instanceof Map)) {
            return out;
        }
        Object data = ((Map) root).get("data");
        if (!(data instanceof List)) {
            return out;
        }
        for (Object o : (List) data) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map m = (Map) o;
            String fullId = str(m.get("full_id"));
            if (fullId == null || fullId.isEmpty()) {
                continue;
            }
            long id = 0L;
            Object idO = m.get("id");
            if (idO instanceof Number) {
                id = ((Number) idO).longValue();
            }
            String label = firstNonEmpty(m, "fullname", "name", "label");
            String type = firstNonEmpty(m, "authority_type", "type");
            String detail = "places".equals(register) ? placeDetail(m) : "";
            out.add(new AntonEntity(id, fullId, label, type, detail, register, names(m)));
        }
        return out;
    }

    /**
     * Raw name strings for the record, used to find further occurrences of the same entity
     * in the document. Collects {@code name}, {@code fullname}, {@code alternative_names},
     * {@code variants} and {@code abbreviations}; array values are taken element-wise, single
     * strings are kept intact (the "Nachname, Vorname" split happens later in {@link Occurrences}).
     */
    @SuppressWarnings("rawtypes")
    private List<String> names(Map m) {
        List<String> out = new ArrayList<String>();
        addNames(out, m.get("name"));
        addNames(out, m.get("fullname"));
        addNames(out, m.get("alternative_names"));
        addNames(out, m.get("variants"));
        addNames(out, m.get("abbreviations"));
        return out;
    }

    @SuppressWarnings("rawtypes")
    private void addNames(List<String> out, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof List) {
            for (Object o : (List) v) {
                addName(out, str(o));
            }
        } else {
            addName(out, str(v));
        }
    }

    private void addName(List<String> out, String s) {
        if (s != null) {
            s = s.trim();
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private String firstNonEmpty(Map m, String... keys) {
        for (String k : keys) {
            String v = str(m.get(k));
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    @SuppressWarnings("rawtypes")
    private String placeDetail(Map m) {
        StringBuilder b = new StringBuilder();
        appendPart(b, str(m.get("city")));
        appendPart(b, str(m.get("state")));
        appendPart(b, str(m.get("country")));
        return b.toString();
    }

    private void appendPart(StringBuilder b, String part) {
        if (part != null && !part.isEmpty()) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(part);
        }
    }

    // --- low level helpers -------------------------------------------------

    private HttpURLConnection open(String url) throws IOException {
        URLConnection c = new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && config.isInsecureTls()) {
            applyInsecure((HttpsURLConnection) c);
        }
        return (HttpURLConnection) c;
    }

    /** Trust-all TLS for this single connection only (used for local mkcert/DDEV hosts). */
    private void applyInsecure(HttpsURLConnection c) {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new SecureRandom());
            c.setSSLSocketFactory(sc.getSocketFactory());
            c.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
        } catch (Exception e) {
            // fall back to default (strict) TLS
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 240 ? s.substring(0, 240) + "…" : s;
    }
}
