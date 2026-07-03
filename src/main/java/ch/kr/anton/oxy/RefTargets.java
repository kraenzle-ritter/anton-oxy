package ch.kr.anton.oxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.text.Document;

import ro.sync.ecss.extensions.api.AuthorDocumentController;
import ro.sync.ecss.extensions.api.node.AttrValue;
import ro.sync.ecss.extensions.api.node.AuthorElement;
import ro.sync.ecss.extensions.api.node.AuthorNode;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.author.WSAuthorEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;

/**
 * Locates the TEI element under the caret that should receive an Anton reference,
 * and writes the configured attribute — in both <b>Author</b> and <b>Text</b> mode.
 *
 * <p>Which elements are targets, which Anton register each maps to, and the
 * attribute name are all configurable (see {@link Config}). The default mapping is:</p>
 * <ul>
 *   <li>{@code persName} → {@code actors} (also covers organisations / Körperschaften)</li>
 *   <li>{@code orgName}  → {@code actors}</li>
 *   <li>{@code placeName}→ {@code places}</li>
 * </ul>
 */
final class RefTargets {

    private RefTargets() { }

    /** A located element that can report context and accept a reference value. */
    interface RefTarget {
        /** Anton register, e.g. "actors" or "places". */
        String register();
        /** Local element name, e.g. "persName". */
        String elementName();
        /** Inner text of the element, for pre-filling the search field (may be empty). */
        String currentText();
        /** Existing attribute value or null. */
        String currentRef();
        /** Write/replace the configured attribute with {@code value}. */
        void writeRef(String value) throws Exception;
        /**
         * Document offset from which a "next occurrence" search may continue so it
         * does not re-match the text just tagged (Text mode), or {@code -1} when the
         * mode cannot report one (Author mode). Call after {@link #writeRef}.
         */
        int afterOffset();
    }

    /**
     * A plain text selection that is <i>not</i> (yet) inside a mapped element and can
     * be wrapped in a fresh element carrying the reference — the "Wrap &amp; Tag" flow.
     */
    interface WrapTarget {
        /** The selected text, verbatim (used as search prefill and for "next occurrence"). */
        String selectedText();
        /**
         * Surround the selection with {@code <elementName attr="value">…</elementName>}.
         *
         * @return Text mode: the document offset just after the inserted element (so a
         *         "next occurrence" search continues past it); Author mode: {@code -1}.
         */
        int wrap(String elementName, String attr, String value) throws Exception;
        /**
         * Offset from which a "next occurrence" search should continue when the selection
         * is <em>skipped</em> (not wrapped): the end of the current selection, so the same
         * occurrence is not re-matched. Text mode only; Author mode returns {@code -1}.
         */
        int searchAnchor();
    }

    /** Convenience overload (default mapping + {@code @ref}) — used by tests. */
    static RefTarget locate(WSEditor editor) {
        return locate(editor, defaultTargets());
    }

    static Map<String, Config.Target> defaultTargets() {
        Map<String, Config.Target> m = new LinkedHashMap<String, Config.Target>();
        m.put("persName", new Config.Target("actors", "ref"));
        m.put("orgName", new Config.Target("actors", "ref"));
        m.put("placeName", new Config.Target("places", "ref"));
        return m;
    }

    /**
     * @param targets element local-name → {@link Config.Target} (register + attribute)
     * @return a target, or null if the caret is not inside a mapped element.
     */
    static RefTarget locate(WSEditor editor, Map<String, Config.Target> targets) {
        if (editor == null) {
            return null;
        }
        WSEditorPage page = editor.getCurrentPage();
        if (page instanceof WSAuthorEditorPage) {
            return locateAuthor((WSAuthorEditorPage) page, targets);
        }
        if (page instanceof WSTextEditorPage) {
            return locateText((WSTextEditorPage) page, targets);
        }
        return null;
    }

    // --- Wrap & Tag: locate a bare selection to be wrapped -----------------

    /**
     * @return a {@link WrapTarget} for the current text selection, or null if there is
     *         no non-blank selection. The caller should only reach for this when
     *         {@link #locate} found no mapped element under the caret.
     */
    static WrapTarget locateSelection(WSEditor editor) {
        if (editor == null) {
            return null;
        }
        WSEditorPage page = editor.getCurrentPage();
        if (page instanceof WSTextEditorPage) {
            return locateSelectionText((WSTextEditorPage) page);
        }
        if (page instanceof WSAuthorEditorPage) {
            return locateSelectionAuthor((WSAuthorEditorPage) page);
        }
        return null;
    }

    private static WrapTarget locateSelectionText(WSTextEditorPage page) {
        try {
            if (!page.hasSelection()) {
                return null;
            }
            int start = page.getSelectionStart();
            int end = page.getSelectionEnd();
            if (end <= start) {
                return null;
            }
            Document doc = page.getDocument();
            String sel = doc.getText(start, end - start);
            if (sel == null || sel.trim().isEmpty()) {
                return null;
            }
            return new TextWrapTarget(doc, start, end, sel);
        } catch (Exception e) {
            return null;
        }
    }

    private static WrapTarget locateSelectionAuthor(WSAuthorEditorPage page) {
        try {
            if (!page.hasSelection()) {
                return null;
            }
            int start = page.getSelectionStart();
            int end = page.getSelectionEnd();
            if (end <= start) {
                return null;
            }
            String sel = page.getSelectedText();
            if (sel == null || sel.trim().isEmpty()) {
                return null;
            }
            AuthorDocumentController ctrl = page.getDocumentController();
            return new AuthorWrapTarget(ctrl, start, end, sel, namespaceAt(ctrl, start));
        } catch (Exception e) {
            return null;
        }
    }

    /** Nearest non-empty namespace at {@code offset}, so a wrapped element stays in it. */
    private static String namespaceAt(AuthorDocumentController ctrl, int offset) {
        try {
            AuthorNode node = ctrl.getNodeAtOffset(offset);
            while (node != null) {
                if (node instanceof AuthorElement) {
                    String ns = ((AuthorElement) node).getNamespace();
                    if (ns != null && !ns.isEmpty()) {
                        return ns;
                    }
                }
                node = node.getParent();
            }
        } catch (Exception e) {
            // fall through
        }
        return "";
    }

    /**
     * Select the next <em>whole-word</em> occurrence of {@code surface} at or after
     * {@code fromOffset} (Text mode only), so the editor can tag it next. Author mode is
     * not supported and returns false.
     *
     * <p>Whole-word means the match must sit on word boundaries, so tagging "Schaan" no
     * longer stops inside "Schaaner". Only the boundary characters that are themselves
     * word characters are constrained, so multi-word surfaces ("Anna Sulger") and ones
     * ending in punctuation still match.</p>
     */
    static boolean selectNext(WSEditor editor, String surface, int fromOffset) {
        if (editor == null || surface == null || surface.isEmpty() || fromOffset < 0) {
            return false;
        }
        WSEditorPage page = editor.getCurrentPage();
        if (!(page instanceof WSTextEditorPage)) {
            return false;
        }
        try {
            WSTextEditorPage tp = (WSTextEditorPage) page;
            Document doc = tp.getDocument();
            String text = doc.getText(0, doc.getLength());
            int idx = indexOfWholeWord(text, surface, Math.min(fromOffset, text.length()));
            if (idx < 0) {
                return false;
            }
            tp.select(idx, idx + surface.length());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * First index of {@code needle} in {@code text} at or after {@code from} whose edges
     * sit on word boundaries — emulating regex {@code \bneedle\b} for the needle's own
     * boundary characters. Returns -1 if there is no such occurrence.
     */
    static int indexOfWholeWord(String text, String needle, int from) {
        if (needle.isEmpty()) {
            return -1;
        }
        int len = needle.length();
        for (int idx = text.indexOf(needle, from); idx >= 0; idx = text.indexOf(needle, idx + 1)) {
            boolean leftOk = idx == 0
                    || !(isWordChar(text.charAt(idx - 1)) && isWordChar(needle.charAt(0)));
            int after = idx + len;
            boolean rightOk = after >= text.length()
                    || !(isWordChar(needle.charAt(len - 1)) && isWordChar(text.charAt(after)));
            if (leftOk && rightOk) {
                return idx;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String escapeAttr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private static final class TextWrapTarget implements WrapTarget {
        private final Document doc;
        private final int start;
        private final int end;
        private final String selected;

        TextWrapTarget(Document doc, int start, int end, String selected) {
            this.doc = doc;
            this.start = start;
            this.end = end;
            this.selected = selected;
        }

        public String selectedText() { return selected; }

        public int wrap(String elementName, String attr, String value) throws Exception {
            String open = "<" + elementName + " " + attr + "=\"" + escapeAttr(value) + "\">";
            String close = "</" + elementName + ">";
            String wrapped = open + selected + close;
            doc.remove(start, end - start);
            doc.insertString(start, wrapped, null);
            return start + wrapped.length();
        }

        public int searchAnchor() { return end; }
    }

    private static final class AuthorWrapTarget implements WrapTarget {
        private final AuthorDocumentController ctrl;
        private final int start;
        private final int end;
        private final String selected;
        private final String ns;

        AuthorWrapTarget(AuthorDocumentController ctrl, int start, int end, String selected, String ns) {
            this.ctrl = ctrl;
            this.start = start;
            this.end = end;
            this.selected = selected;
            this.ns = ns;
        }

        public String selectedText() { return selected; }

        public int wrap(String elementName, String attr, String value) throws Exception {
            StringBuilder frag = new StringBuilder("<").append(elementName);
            if (ns != null && !ns.isEmpty()) {
                frag.append(" xmlns=\"").append(escapeAttr(ns)).append("\"");
            }
            frag.append(" ").append(attr).append("=\"").append(escapeAttr(value)).append("\"/>");
            // surroundInFragment uses an inclusive end offset -> end - 1.
            ctrl.surroundInFragment(frag.toString(), start, end - 1);
            return -1; // "next occurrence" is Text-mode only
        }

        public int searchAnchor() { return -1; } // Author mode: "next occurrence" unsupported
    }

    private static String localName(String qName) {
        if (qName == null) {
            return "";
        }
        int c = qName.indexOf(':');
        return c >= 0 ? qName.substring(c + 1) : qName;
    }

    // --- Author mode -------------------------------------------------------

    private static RefTarget locateAuthor(WSAuthorEditorPage page, Map<String, Config.Target> targets) {
        try {
            AuthorDocumentController ctrl = page.getDocumentController();
            int offset = page.getSelectionStart();
            AuthorNode node = ctrl.getNodeAtOffset(offset);
            while (node != null) {
                if (node instanceof AuthorElement) {
                    String ln = localName(node.getName());
                    Config.Target t = targets.get(ln);
                    if (t != null) {
                        return new AuthorRefTarget(ctrl, (AuthorElement) node, ln, t.register, t.attribute);
                    }
                }
                node = node.getParent();
            }
        } catch (Exception e) {
            // fall through -> null
        }
        return null;
    }

    private static final class AuthorRefTarget implements RefTarget {
        private final AuthorDocumentController ctrl;
        private final AuthorElement el;
        private final String name;
        private final String register;
        private final String attr;

        AuthorRefTarget(AuthorDocumentController ctrl, AuthorElement el, String name,
                        String register, String attr) {
            this.ctrl = ctrl;
            this.el = el;
            this.name = name;
            this.register = register;
            this.attr = attr;
        }

        public String register() { return register; }
        public String elementName() { return name; }

        public String currentText() {
            try {
                int start = el.getStartOffset();
                int len = el.getEndOffset() - start;
                return collapse(ctrl.getText(start, len));
            } catch (Exception e) {
                return "";
            }
        }

        public String currentRef() {
            AttrValue a = el.getAttribute(attr);
            return a == null ? null : a.getValue();
        }

        public void writeRef(String value) {
            ctrl.setAttribute(attr, new AttrValue(value), el);
        }

        public int afterOffset() { return -1; } // Author mode: "next occurrence" unsupported
    }

    // --- Text mode ---------------------------------------------------------

    private static RefTarget locateText(WSTextEditorPage page, Map<String, Config.Target> targets) {
        try {
            Document doc = page.getDocument();
            String text = doc.getText(0, doc.getLength());
            int caret = page.hasSelection() ? page.getSelectionStart() : page.getCaretOffset();

            // Find the nearest preceding start tag of any mapped element.
            int open = -1;
            String name = null;
            for (String n : targets.keySet()) {
                int idx = lastStartTag(text, n, caret);
                if (idx > open) {
                    open = idx;
                    name = n;
                }
            }
            if (open < 0) {
                return null;
            }
            int close = text.indexOf('>', open);
            if (close < 0) {
                return null;
            }
            // Make sure the caret is actually inside this element (not after its close tag).
            if (caret > close) {
                int closeTag = indexOfCloseTag(text, name, close);
                if (closeTag >= 0 && caret > closeTag) {
                    return null; // caret is past the element -> not enclosed
                }
            }
            Config.Target t = targets.get(name);
            if (t == null) {
                return null;
            }

            String selected = page.hasSelection() ? page.getSelectedText() : null;
            String inner = (selected != null && !selected.trim().isEmpty())
                    ? selected
                    : innerText(text, name, close);

            return new TextRefTarget(doc, open, close, text.substring(open, close + 1),
                    name, t.register, collapse(inner), t.attribute);
        } catch (Exception e) {
            return null;
        }
    }

    /** Last index of a real start tag {@code <name} at or before {@code pos}. */
    private static int lastStartTag(String text, String name, int pos) {
        String token = "<" + name;
        int from = Math.min(pos, text.length() - 1);
        while (from >= 0) {
            int idx = text.lastIndexOf(token, from);
            if (idx < 0) {
                return -1;
            }
            int after = idx + token.length();
            char c = after < text.length() ? text.charAt(after) : ' ';
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '>' || c == '/') {
                return idx;
            }
            from = idx - 1; // false positive (e.g. <placeNameX) -> keep looking
        }
        return -1;
    }

    private static int indexOfCloseTag(String text, String name, int from) {
        Matcher m = Pattern.compile("</" + Pattern.quote(name) + "\\s*>").matcher(text);
        if (m.find(from)) {
            return m.start();
        }
        return -1;
    }

    private static String innerText(String text, String name, int close) {
        int end = indexOfCloseTag(text, name, close);
        if (end < 0) {
            end = Math.min(text.length(), close + 200);
        }
        return text.substring(close + 1, end).replaceAll("<[^>]*>", " ");
    }

    private static final class TextRefTarget implements RefTarget {
        private final Document doc;
        private final int tagStart;
        private final int tagEnd;     // index of '>'
        private final String tag;     // e.g. <persName ...>
        private final String name;
        private final String register;
        private final String inner;
        private final String attr;
        private final Pattern attrPattern;

        TextRefTarget(Document doc, int tagStart, int tagEnd, String tag,
                      String name, String register, String inner, String attr) {
            this.doc = doc;
            this.tagStart = tagStart;
            this.tagEnd = tagEnd;
            this.tag = tag;
            this.name = name;
            this.register = register;
            this.inner = inner;
            this.attr = attr;
            this.attrPattern = Pattern.compile(
                    "(?s)\\s" + Pattern.quote(attr) + "\\s*=\\s*(\"[^\"]*\"|'[^']*')");
        }

        public String register() { return register; }
        public String elementName() { return name; }
        public String currentText() { return inner; }

        public String currentRef() {
            Matcher m = attrPattern.matcher(tag);
            if (m.find()) {
                String q = m.group(1);
                return q.substring(1, q.length() - 1);
            }
            return null;
        }

        public void writeRef(String value) throws Exception {
            String newTag = buildTag(tag, value);
            int len = tagEnd - tagStart + 1;
            doc.remove(tagStart, len);
            doc.insertString(tagStart, newTag, null);
        }

        /**
         * Anchor a "next occurrence" search at this element's close tag (the just-tagged
         * text sits before it, so it won't be re-matched). {@code writeRef} only rewrites
         * the start tag in place, so {@code tagStart} still points at it afterwards.
         */
        public int afterOffset() {
            try {
                String t = doc.getText(0, doc.getLength());
                return indexOfCloseTag(t, name, tagStart);
            } catch (Exception e) {
                return -1;
            }
        }

        private String buildTag(String startTag, String value) {
            boolean selfClose = startTag.endsWith("/>");
            String body = startTag.substring(1, selfClose ? startTag.length() - 2 : startTag.length() - 1);
            String attrText = " " + attr + "=\"" + value + "\"";
            Matcher m = attrPattern.matcher(body);
            if (m.find()) {
                body = body.substring(0, m.start()) + attrText + body.substring(m.end());
            } else {
                Matcher nm = Pattern.compile("^(\\s*[\\w:.\\-]+)").matcher(body);
                if (nm.find()) {
                    body = body.substring(0, nm.end()) + attrText + body.substring(nm.end());
                } else {
                    body = body + attrText;
                }
            }
            return "<" + body + (selfClose ? "/>" : ">");
        }
    }

    // --- shared ------------------------------------------------------------

    private static String collapse(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80).trim() : s;
    }
}
