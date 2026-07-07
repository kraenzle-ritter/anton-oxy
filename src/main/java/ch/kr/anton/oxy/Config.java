package ch.kr.anton.oxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ro.sync.exml.workspace.api.options.WSOptionsStorage;

/**
 * Plugin configuration, persisted through Oxygen's options storage so the editor
 * only has to set it once. The base URL is the only thing that must be correct;
 * the project slug (e.g. {@code demo-}) is delivered by Anton inside {@code full_id}.
 */
final class Config {

    static final String OPT_URL      = "anton.oxy.baseUrl";
    static final String OPT_PERPAGE  = "anton.oxy.perPage";
    static final String OPT_INSECURE = "anton.oxy.insecureTls";
    static final String OPT_ATTR     = "anton.oxy.attribute";
    static final String OPT_TEMPLATE = "anton.oxy.template";
    static final String OPT_MAPPING  = "anton.oxy.mapping";
    static final String OPT_SCAN     = "anton.oxy.scanOccurrences";
    static final String OPT_CONTEXT  = "anton.oxy.contextChars";

    /** Default Anton instance; change to your tenant URL in the settings if needed. */
    static final String DEFAULT_URL = "https://kr.anton.ch";
    static final String DEFAULT_PERPAGE = "30";
    /**
     * Strict TLS by default (the default URL has a valid certificate). Enable lenient
     * TLS in the settings for local DDEV/mkcert hosts whose cert Java does not trust.
     */
    static final String DEFAULT_INSECURE = "false";
    /** Name of the attribute that receives the id (TEI editions commonly use @ref). */
    static final String DEFAULT_ATTR = "ref";
    /** Template for the written value. Placeholders: {fullId} {slug} {register} {id}. */
    static final String DEFAULT_TEMPLATE = "{fullId}";
    /**
     * One "element=register" per line ("#" comments allowed). An optional "@attribute"
     * suffix overrides the target attribute for that element, e.g. "unit=keywords@corresp".
     */
    static final String DEFAULT_MAPPING =
            "persName=actors\norgName=actors\nplaceName=places\n"
            + "objectName=keywords\nterm=keywords\nunit=keywords@corresp";
    /** Offer to tag further occurrences of a just-referenced actor/place (Text mode). */
    static final String DEFAULT_SCAN = "true";
    /** Context characters shown left and right of the base name in the occurrence preview. */
    static final String DEFAULT_CONTEXT = "60";
    static final int CONTEXT_MIN = 20;
    static final int CONTEXT_MAX = 300;

    private final WSOptionsStorage store;

    Config(WSOptionsStorage store) {
        this.store = store;
    }

    String getBaseUrl() {
        String v = val(OPT_URL, DEFAULT_URL);
        // strip trailing slashes so we can append "/api/..."
        return v.replaceAll("/+$", "");
    }

    void setBaseUrl(String v) {
        store.setOption(OPT_URL, v == null ? "" : v.trim());
    }

    int getPerPage() {
        try {
            return Integer.parseInt(val(OPT_PERPAGE, DEFAULT_PERPAGE));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    void setPerPage(int n) {
        store.setOption(OPT_PERPAGE, String.valueOf(n));
    }

    boolean isInsecureTls() {
        return "true".equalsIgnoreCase(val(OPT_INSECURE, DEFAULT_INSECURE));
    }

    void setInsecureTls(boolean b) {
        store.setOption(OPT_INSECURE, b ? "true" : "false");
    }

    String getAttribute() {
        String v = val(OPT_ATTR, DEFAULT_ATTR).trim();
        return v.isEmpty() ? DEFAULT_ATTR : v;
    }

    void setAttribute(String v) {
        store.setOption(OPT_ATTR, v == null ? "" : v.trim());
    }

    String getTemplate() {
        return val(OPT_TEMPLATE, DEFAULT_TEMPLATE);
    }

    void setTemplate(String v) {
        store.setOption(OPT_TEMPLATE, (v == null || v.trim().isEmpty()) ? DEFAULT_TEMPLATE : v.trim());
    }

    boolean isScanOccurrences() {
        return "true".equalsIgnoreCase(val(OPT_SCAN, DEFAULT_SCAN));
    }

    void setScanOccurrences(boolean b) {
        store.setOption(OPT_SCAN, b ? "true" : "false");
    }

    /** Context width (chars per side) for the occurrence preview, clamped to a sane range. */
    int getContextChars() {
        int n;
        try {
            n = Integer.parseInt(val(OPT_CONTEXT, DEFAULT_CONTEXT));
        } catch (NumberFormatException e) {
            n = Integer.parseInt(DEFAULT_CONTEXT);
        }
        return Math.max(CONTEXT_MIN, Math.min(CONTEXT_MAX, n));
    }

    void setContextChars(int n) {
        store.setOption(OPT_CONTEXT, String.valueOf(Math.max(CONTEXT_MIN, Math.min(CONTEXT_MAX, n))));
    }

    String getMappingRaw() {
        return val(OPT_MAPPING, DEFAULT_MAPPING);
    }

    void setMappingRaw(String v) {
        store.setOption(OPT_MAPPING, (v == null || v.trim().isEmpty()) ? DEFAULT_MAPPING : v.trim());
    }

    /** A configured target: which Anton register to search and which attribute to write. */
    static final class Target {
        final String register;
        final String attribute;
        Target(String register, String attribute) {
            this.register = register;
            this.attribute = attribute;
        }
    }

    /**
     * Parsed element-name → {@link Target} (insertion order preserved). Each mapping line
     * is {@code element=register} with an optional {@code @attribute} suffix that overrides
     * the global target attribute for that element (e.g. {@code unit=keywords@corresp}).
     */
    Map<String, Target> getTargets() {
        String defaultAttr = getAttribute();
        Map<String, Target> m = new LinkedHashMap<String, Target>();
        for (String line : getMappingRaw().split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String tag = line.substring(0, eq).trim();
            String rhs = line.substring(eq + 1).trim();
            String reg = rhs;
            String attr = defaultAttr;
            int at = rhs.indexOf('@');
            if (at >= 0) {
                reg = rhs.substring(0, at).trim();
                String a = rhs.substring(at + 1).trim();
                if (!a.isEmpty()) {
                    attr = a;
                }
            }
            if (!tag.isEmpty() && !reg.isEmpty()) {
                m.put(tag, new Target(reg, attr));
            }
        }
        if (m.isEmpty()) {
            m.put("persName", new Target("actors", defaultAttr));
            m.put("orgName", new Target("actors", defaultAttr));
            m.put("placeName", new Target("places", defaultAttr));
        }
        return m;
    }

    /** Element-name → register (convenience view of {@link #getTargets()}). */
    Map<String, String> getMapping() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Target> e : getTargets().entrySet()) {
            m.put(e.getKey(), e.getValue().register);
        }
        return m;
    }

    /** Distinct registers in mapping order, e.g. [actors, places, keywords]. */
    List<String> getRegisters() {
        List<String> out = new ArrayList<String>();
        for (Target t : getTargets().values()) {
            if (!out.contains(t.register)) {
                out.add(t.register);
            }
        }
        return out;
    }

    /** Build the attribute value for an entity using the configured template. */
    String formatRef(AntonEntity e) {
        String fullId = e.fullId;
        String register = e.register;
        String slug = "";
        String id = String.valueOf(e.id);
        String marker = "-" + register + "-";
        int p = fullId.indexOf(marker);
        if (p >= 0) {
            slug = fullId.substring(0, p);
            id = fullId.substring(p + marker.length());
        }
        return getTemplate()
                .replace("{fullId}", fullId)
                .replace("{slug}", slug)
                .replace("{register}", register)
                .replace("{id}", id);
    }

    private String val(String key, String def) {
        String v = store.getOption(key, def);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
