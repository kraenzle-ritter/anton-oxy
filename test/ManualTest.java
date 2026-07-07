package ch.kr.anton.oxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.util.HashMap;
import java.util.Map;

import javax.swing.text.Document;
import javax.swing.text.PlainDocument;

import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

/**
 * Stand-alone sanity checks for the JSON parser and the Text-mode @ref rewriting.
 * Not a JUnit test (keeps the build dependency-free) — run via test/run.sh.
 */
public class ManualTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        testJson();
        testTextMode();
        testWrap();
        testNextOccurrence();
        testWholeWord();
        testDateGuess();
        testConfig();
        testOccurrences();
        testWrapRanges();
        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // --- JSON ---------------------------------------------------------------

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void testJson() {
        String body = "{\"data\":[{\"id\":10,\"full_id\":\"sulger-actors-10\","
                + "\"authority_type\":\"K\\u00f6rperschaft\",\"name\":\"Sp\\u00fcli\","
                + "\"fullname\":\"Cornelius Spielman\"}],\"meta\":{\"total\":1}}";
        Object root = Json.parse(body);
        java.util.Map m = (java.util.Map) root;
        java.util.List data = (java.util.List) m.get("data");
        java.util.Map a = (java.util.Map) data.get(0);
        check("json id", "10", String.valueOf(a.get("id")));
        check("json full_id", "sulger-actors-10", a.get("full_id"));
        check("json unicode", "Körperschaft", a.get("authority_type"));
        check("json fullname", "Cornelius Spielman", a.get("fullname"));
    }

    // --- Text mode ----------------------------------------------------------

    private static void testTextMode() throws Exception {
        // 1) persName without ref
        rewrite("persName, no ref",
                "<p>Wie <persName>Cornelius Spilman</persName>, von</p>",
                "<persName>".length() + 4, // caret somewhere inside the name
                "sulger-actors-10",
                "<persName ref=\"sulger-actors-10\">", "actors");

        // 2) persName with existing ref -> replaced
        rewrite("persName, replace ref",
                "<p>der <persName ref=\"sulger-actors-99\">Gouverneur</persName> kam</p>",
                25,
                "sulger-actors-17",
                "<persName ref=\"sulger-actors-17\">", "actors");

        // 3) placeName with other attributes preserved
        rewrite("placeName with attrs",
                "<p>nach <placeName n=\"1\" type=\"itinerar\">Macquian</placeName> zu</p>",
                40,
                "sulger-places-14",
                "<placeName ref=\"sulger-places-14\" n=\"1\" type=\"itinerar\">", "places");

        // 4) nested: caret inside <hi> within persName -> tags the persName
        rewrite("nested hi inside persName",
                "<p><persName>Herr <hi rend=\"latin\">Lobs</hi> kam</persName></p>",
                "<p><persName>Herr <hi rend=\"latin\">L".length(),
                "sulger-actors-18",
                "<persName ref=\"sulger-actors-18\">", "actors");

        // 5) caret outside any target -> no target located
        WSEditor ed = editor("<p>nur Text ohne Tag</p>", 8, -1);
        check("outside -> null target", "null", String.valueOf(RefTargets.locate(ed)));
    }

    private static void rewrite(String label, String xml, int caret, String fullId,
                                String expectStartTag, String expectRegister) throws Exception {
        WSEditor ed = editor(xml, caret, -1);
        RefTargets.RefTarget t = RefTargets.locate(ed);
        if (t == null) {
            fail(label + ": no target located");
            return;
        }
        check(label + " register", expectRegister, t.register());
        t.writeRef(fullId);
        String after = docOf(ed);
        if (after.contains(expectStartTag)) {
            pass(label);
        } else {
            fail(label + "\n   expected tag: " + expectStartTag + "\n   result: " + after);
        }
    }

    // --- Wrap & Tag ---------------------------------------------------------

    private static void testWrap() throws Exception {
        // Select "Anna Sulger" in bare text and wrap it in persName.
        String xml = "<p>Wie Anna Sulger, von</p>";
        int a = xml.indexOf("Anna");
        int b = a + "Anna Sulger".length();
        WSEditor ed = editorSel(xml, a, b);

        RefTargets.WrapTarget w = RefTargets.locateSelection(ed);
        if (w == null) {
            fail("wrap: no selection located");
            return;
        }
        check("wrap selectedText", "Anna Sulger", w.selectedText());

        int after = w.wrap("persName", "ref", "sulger-actors-1");
        String doc = lastDoc.getText(0, lastDoc.getLength());
        check("wrap inserts element", "true", String.valueOf(
                doc.contains("<persName ref=\"sulger-actors-1\">Anna Sulger</persName>")));
        check("wrap after offset points past close tag", "true",
                String.valueOf(after == doc.indexOf("</persName>") + "</persName>".length()));

        // Attribute value is XML-escaped.
        WSEditor ed2 = editorSel("<p>x</p>", "<p>".length(), "<p>x".length());
        RefTargets.locateSelection(ed2).wrap("term", "key", "a&b\"c");
        check("wrap escapes attr value", "true", String.valueOf(
                lastDoc.getText(0, lastDoc.getLength())
                        .contains("<term key=\"a&amp;b&quot;c\">x</term>")));

        // A blank selection is not a wrap target.
        WSEditor ed3 = editorSel("<p>   </p>", "<p>".length(), "<p>   ".length());
        check("blank selection -> null", "null", String.valueOf(RefTargets.locateSelection(ed3)));
    }

    // --- Next occurrence ----------------------------------------------------

    private static void testNextOccurrence() throws Exception {
        String xml = "<p>Wie Anna Sulger und Anna Sulger, von</p>";
        int a = xml.indexOf("Anna");
        int b = a + "Anna Sulger".length();
        WSEditor ed = editorSel(xml, a, b);

        RefTargets.WrapTarget w = RefTargets.locateSelection(ed);
        int after = w.wrap("persName", "ref", "sulger-actors-1");

        boolean found = RefTargets.selectNext(ed, "Anna Sulger", after);
        check("next occurrence found", "true", String.valueOf(found));
        // The freshly selected range must be the SECOND, still-untagged occurrence.
        WSTextEditorPage tp = (WSTextEditorPage) ((WSEditor) ed).getCurrentPage();
        check("next selects untagged occurrence", "Anna Sulger",
                lastDoc.getText(tp.getSelectionStart(), tp.getSelectionEnd() - tp.getSelectionStart()));
        check("next occurrence is past the tagged one", "true",
                String.valueOf(tp.getSelectionStart() >= after));

        // No further occurrence -> false.
        check("no further occurrence", "false",
                String.valueOf(RefTargets.selectNext(ed, "Anna Sulger", tp.getSelectionEnd())));
    }

    // --- Whole-word next-occurrence search ----------------------------------

    private static void testWholeWord() throws Exception {
        // "Schaan" must not match inside "Schaaner" — Stefan's report.
        String t = "nach Schaaner Art, dann Schaan selbst";
        int schaan = t.indexOf("Schaan selbst");
        check("whole word skips Schaaner", String.valueOf(schaan),
                String.valueOf(RefTargets.indexOfWholeWord(t, "Schaan", 0)));
        // No standalone "Schaan" at all -> not found.
        check("whole word none in Schaaner",
                "-1", String.valueOf(RefTargets.indexOfWholeWord("nur Schaaner hier", "Schaan", 0)));
        // Multi-word surface still matches (boundaries are spaces/punctuation).
        String m = "Wie Anna Sulger, von";
        check("whole word multi-word",
                String.valueOf(m.indexOf("Anna Sulger")),
                String.valueOf(RefTargets.indexOfWholeWord(m, "Anna Sulger", 0)));
        // selectNext honours the same boundary rule.
        String xml = "<p>Schaaner Weg und Schaan hier</p>";
        WSEditor ed = editorSel(xml, 0, 0);
        boolean found = RefTargets.selectNext(ed, "Schaan", 0);
        WSTextEditorPage tp = (WSTextEditorPage) ed.getCurrentPage();
        check("selectNext skips Schaaner", "true", String.valueOf(found));
        check("selectNext lands on whole Schaan", String.valueOf(xml.indexOf("Schaan hier")),
                String.valueOf(tp.getSelectionStart()));
    }

    // --- Date parsing -------------------------------------------------------

    private static void testDateGuess() {
        check("iso year", "2026", DateDialog.guessIso("2026"));
        check("iso full passthrough", "2026-07-03", DateDialog.guessIso("2026-07-03"));
        check("german full", "2026-07-03", DateDialog.guessIso("3. Juli 2026"));
        check("german month year", "2026-07", DateDialog.guessIso("Juli 2026"));
        check("numeric full", "2026-07-03", DateDialog.guessIso("3.7.2026"));
        check("numeric month year", "2026-07", DateDialog.guessIso("07.2026"));
        check("umlaut month", "1712-03", DateDialog.guessIso("März 1712"));
        check("fallback year", "1712", DateDialog.guessIso("um 1712 herum"));
        check("unrecognised empty", "", DateDialog.guessIso("irgendwann"));
    }

    // --- Config: template / mapping / custom attribute ----------------------

    private static void testConfig() throws Exception {
        // default mapping & registers
        Config def = new Config(store(new HashMap<String, String>()));
        check("default mapping persName", "actors", def.getMapping().get("persName"));
        check("default mapping objectName", "keywords", def.getMapping().get("objectName"));
        check("default registers", "[actors, places, keywords]", def.getRegisters().toString());
        check("unit attr override", "corresp", def.getTargets().get("unit").attribute);
        check("persName attr default", "ref", def.getTargets().get("persName").attribute);

        // template {fullId} (default)
        check("template fullId", "sulger-actors-10",
                def.formatRef(new AntonEntity(10, "sulger-actors-10", "x", "", "", "actors")));

        // custom template using parsed placeholders
        Map<String, String> o = new HashMap<String, String>();
        o.put(Config.OPT_TEMPLATE, "#{slug}:{register}:{id}");
        Config tpl = new Config(store(o));
        check("template parsed", "#sulger:places:14",
                tpl.formatRef(new AntonEntity(14, "sulger-places-14", "x", "", "", "places")));

        // custom mapping + custom attribute, end to end in text mode
        Map<String, String> o2 = new HashMap<String, String>();
        o2.put(Config.OPT_MAPPING, "rs=actors\n# comment\nplaceName=places");
        o2.put(Config.OPT_ATTR, "key");
        Config cfg = new Config(store(o2));
        check("custom mapping rs", "actors", cfg.getMapping().get("rs"));

        WSEditor ed = editor("<p>der <rs>Lobs</rs> kam</p>", 9, -1);
        RefTargets.RefTarget t = RefTargets.locate(ed, cfg.getTargets());
        if (t == null) {
            fail("custom element <rs> not located");
        } else {
            t.writeRef(cfg.formatRef(new AntonEntity(18, "demo-actors-18", "Lobs", "", "", "actors")));
            String after = lastDoc.getText(0, lastDoc.getLength());
            check("custom attribute written", "true",
                    String.valueOf(after.contains("<rs key=\"demo-actors-18\">")));
        }

        // per-element attribute override: unit=keywords@corresp
        Map<String, String> o3 = new HashMap<String, String>();
        o3.put(Config.OPT_MAPPING, "unit=keywords@corresp");
        Config cfg2 = new Config(store(o3));
        WSEditor ed2 = editor("<measure><num>3</num><unit>lb</unit></measure>",
                "<measure><num>3</num><unit>l".length(), -1);
        RefTargets.RefTarget t2 = RefTargets.locate(ed2, cfg2.getTargets());
        if (t2 == null) {
            fail("unit not located");
        } else {
            check("unit register", "keywords", t2.register());
            t2.writeRef(cfg2.formatRef(new AntonEntity(123, "demo-keywords-123", "Pfund", "", "", "keywords")));
            String after = lastDoc.getText(0, lastDoc.getLength());
            check("unit @corresp written", "true",
                    String.valueOf(after.contains("<unit corresp=\"demo-keywords-123\">")));
        }
    }

    /** Proxy-backed WSOptionsStorage returning the given overrides, else the supplied default. */
    private static WSOptionsStorage store(final Map<String, String> opts) {
        return (WSOptionsStorage) Proxy.newProxyInstance(
                ManualTest.class.getClassLoader(),
                new Class[] { WSOptionsStorage.class },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] a) {
                        if (method.getName().equals("getOption")) {
                            String k = (String) a[0];
                            return opts.containsKey(k) ? opts.get(k) : a[1];
                        }
                        return null;
                    }
                });
    }

    // --- proxy plumbing -----------------------------------------------------

    private static Document lastDoc;

    private static String docOf(WSEditor ed) throws Exception {
        return lastDoc.getText(0, lastDoc.getLength());
    }

    private static WSEditor editor(String xml, final int caret, int selEnd) throws Exception {
        final PlainDocument doc = new PlainDocument();
        doc.insertString(0, xml, null);
        lastDoc = doc;
        final int selStart = selEnd >= 0 ? caret : caret;
        final boolean hasSel = selEnd >= 0;

        final WSTextEditorPage page = (WSTextEditorPage) Proxy.newProxyInstance(
                ManualTest.class.getClassLoader(),
                new Class[] { WSTextEditorPage.class },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] a) {
                        String n = method.getName();
                        if (n.equals("getDocument")) return doc;
                        if (n.equals("getCaretOffset")) return caret;
                        if (n.equals("getSelectionStart")) return selStart;
                        if (n.equals("getSelectionEnd")) return hasSel ? selEnd : selStart;
                        if (n.equals("hasSelection")) return hasSel;
                        if (n.equals("getSelectedText")) return null;
                        return defaultFor(method.getReturnType());
                    }
                });

        return (WSEditor) Proxy.newProxyInstance(
                ManualTest.class.getClassLoader(),
                new Class[] { WSEditor.class },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] a) {
                        if (method.getName().equals("getCurrentPage")) {
                            return page;
                        }
                        return defaultFor(method.getReturnType());
                    }
                });
    }

    /**
     * Editor with a real, mutable text selection: {@code getSelectedText} returns the
     * selected substring and {@code select(a,b)} updates it (so selectNext is testable).
     */
    private static WSEditor editorSel(String xml, int selStart, int selEnd) throws Exception {
        final PlainDocument doc = new PlainDocument();
        doc.insertString(0, xml, null);
        lastDoc = doc;
        final int[] sel = { selStart, selEnd };

        final WSTextEditorPage page = (WSTextEditorPage) Proxy.newProxyInstance(
                ManualTest.class.getClassLoader(),
                new Class[] { WSTextEditorPage.class },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] a) throws Exception {
                        String n = method.getName();
                        if (n.equals("getDocument")) return doc;
                        if (n.equals("getCaretOffset")) return sel[0];
                        if (n.equals("getSelectionStart")) return sel[0];
                        if (n.equals("getSelectionEnd")) return sel[1];
                        if (n.equals("hasSelection")) return sel[1] > sel[0];
                        if (n.equals("getSelectedText")) return doc.getText(sel[0], sel[1] - sel[0]);
                        if (n.equals("select")) { sel[0] = (Integer) a[0]; sel[1] = (Integer) a[1]; return null; }
                        return defaultFor(method.getReturnType());
                    }
                });

        return (WSEditor) Proxy.newProxyInstance(
                ManualTest.class.getClassLoader(),
                new Class[] { WSEditor.class },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] a) {
                        if (method.getName().equals("getCurrentPage")) {
                            return page;
                        }
                        return defaultFor(method.getReturnType());
                    }
                });
    }

    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    // --- Occurrences: term derivation + scanning ----------------------------

    private static void testOccurrences() {
        // term derivation: "Nachname, Vorname" -> surname + "Vorname Nachname"
        AntonEntity barth = new AntonEntity(1, "kr-actors-1", "Barth, Karl", "Person", "", "actors",
                java.util.Arrays.asList("Barth, Karl"));
        java.util.List<String> terms = Occurrences.terms(barth, "Karl Barth");
        check("terms contain surname", "true", String.valueOf(terms.contains("Barth")));
        check("terms contain full", "true", String.valueOf(terms.contains("Karl Barth")));
        check("terms longest first", "Karl Barth", terms.get(0));

        java.util.Set<String> skip = new java.util.HashSet<String>(
                java.util.Arrays.asList("persName", "orgName", "placeName"));

        // scan: skip the already-tagged persName, match "Barths" (genitive) and "Barth."
        String doc = "<p><persName ref=\"kr-actors-1\">Karl Barth</persName> schrieb. "
                + "Barths Brief an Barth. Ende.</p>";
        java.util.List<Occurrences.Match> ms = Occurrences.find(doc, skip, terms);
        check("occurrences found", "2", String.valueOf(ms.size()));
        boolean allBarth = true;
        for (Occurrences.Match m : ms) {
            if (!doc.substring(m.start, m.end).equals("Barth")) {
                allBarth = false;
            }
        }
        check("genitive wraps base only", "true", String.valueOf(allBarth));

        // without the skip set, the occurrence inside <persName> is also found (3 total)
        java.util.List<Occurrences.Match> msNoSkip =
                Occurrences.find(doc, new java.util.HashSet<String>(), terms);
        check("no-skip finds tagged too", "3", String.valueOf(msNoSkip.size()));

        // "Barthold" must NOT match "Barth" (letter after base, not a genitive s)
        String doc2 = "<p>Barthold Meyer und Barths Werk.</p>";
        java.util.List<Occurrences.Match> ms2 = Occurrences.find(doc2, skip, terms);
        check("no false match in longer word", "1", String.valueOf(ms2.size()));
        check("matched the genitive one", "Barth", doc2.substring(ms2.get(0).start, ms2.get(0).end));

        // name variants from the API feed the term list (here: an alternative name)
        AntonEntity thur = new AntonEntity(2, "kr-actors-2", "Eduard Thurneysen", "Person", "",
                "actors", java.util.Arrays.asList("Eduard Thurneysen", "Thüneysen"));
        java.util.List<String> tThur = Occurrences.terms(thur, "Thurneysen");
        check("variant becomes a term", "true", String.valueOf(tThur.contains("Thüneysen")));

        // place genitive
        AntonEntity basel = new AntonEntity(5, "kr-places-5", "Basel", "Stadt", "", "places",
                java.util.Arrays.asList("Basel"));
        String doc3 = "<p>in Basel und Basels Umgebung</p>";
        java.util.List<Occurrences.Match> ms3 =
                Occurrences.find(doc3, skip, Occurrences.terms(basel, "Basel"));
        check("place occurrences", "2", String.valueOf(ms3.size()));

        // configurable preview context: a wider window shows more surrounding text and
        // never cuts a word at the edge (the base name stays wrapped in «…»).
        String doc4 = "<p>Weiterhin korrespondierte Emil Brunner ausfuehrlich mit Karl Barth "
                + "ueber die natuerliche Theologie ihrer Zeit.</p>";
        java.util.List<String> tBarth = Occurrences.terms(barth, "Karl Barth");
        String narrow = Occurrences.find(doc4, skip, tBarth, 20).get(0).snippet;
        String wide = Occurrences.find(doc4, skip, tBarth, 120).get(0).snippet;
        check("wide context is longer", "true", String.valueOf(wide.length() > narrow.length()));
        check("base name wrapped", "true", String.valueOf(wide.contains("«Karl Barth»")));
        check("no cut word at edge", "true",
                String.valueOf(wide.contains("Weiterhin") && wide.contains("Zeit")));

        // dialog row rendering: base name inside «…» becomes bold, guillemets dropped, HTML-safe
        String row = OccurrenceDialog.htmlRow("… mit «Karl Barth» ueber …");
        check("row is html", "true", String.valueOf(row.startsWith("<html>") && row.endsWith("</html>")));
        check("row bolds base", "true", String.valueOf(row.contains("<b>Karl Barth</b>")));
        check("row drops guillemets", "true", String.valueOf(!row.contains("«") && !row.contains("»")));
        check("row escapes markup", "true",
                String.valueOf(OccurrenceDialog.htmlRow("a < b «X» &").contains("&lt; b <b>X</b> &amp;")));
    }

    // --- RefTargets: batch wrapping (single covering-span edit) --------------

    private static void testWrapRanges() throws Exception {
        // two occurrences wrapped in one covering-span edit, offsets stay correct
        PlainDocument doc = new PlainDocument();
        doc.insertString(0, "Karl Barth und Barth.", null);
        RefTargets.wrapRanges(doc, new int[][] { {0, 10}, {15, 20} },
                "persName", "ref", "kr-actors-1");
        String out = doc.getText(0, doc.getLength());
        String expect = "<persName ref=\"kr-actors-1\">Karl Barth</persName> und "
                + "<persName ref=\"kr-actors-1\">Barth</persName>.";
        check("wrapRanges batch text", expect, out);

        // batch result matches wrapping each range on its own (from the end)
        PlainDocument seq = new PlainDocument();
        seq.insertString(0, "Karl Barth und Barth.", null);
        RefTargets.wrapRange(seq, 15, 20, "persName", "ref", "kr-actors-1");
        RefTargets.wrapRange(seq, 0, 10, "persName", "ref", "kr-actors-1");
        check("batch equals sequential", seq.getText(0, seq.getLength()), out);

        // single range still wraps correctly (delegates to wrapRange)
        PlainDocument one = new PlainDocument();
        one.insertString(0, "in Basel heute", null);
        RefTargets.wrapRanges(one, new int[][] { {3, 8} }, "placeName", "ref", "kr-places-5");
        check("wrapRanges single", "in <placeName ref=\"kr-places-5\">Basel</placeName> heute",
                one.getText(0, one.getLength()));

        // attribute value is XML-escaped in the generated start tags
        PlainDocument esc = new PlainDocument();
        esc.insertString(0, "X", null);
        RefTargets.wrapRanges(esc, new int[][] { {0, 1} }, "persName", "ref", "a&b<c");
        check("wrapRanges escapes value", "<persName ref=\"a&amp;b&lt;c\">X</persName>",
                esc.getText(0, esc.getLength()));
    }

    // --- assertions ---------------------------------------------------------

    private static void check(String label, String expect, Object actual) {
        if (expect.equals(String.valueOf(actual))) {
            pass(label);
        } else {
            fail(label + " — expected <" + expect + "> got <" + actual + ">");
        }
    }

    private static void pass(String label) { System.out.println("  ok   " + label); }

    private static void fail(String label) { failures++; System.out.println("  FAIL " + label); }
}
