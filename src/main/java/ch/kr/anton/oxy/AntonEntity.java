package ch.kr.anton.oxy;

import java.util.Collections;
import java.util.List;

/**
 * One search hit returned by Anton (an actor or a place).
 *
 * <p>The {@link #fullId} is the value written into the TEI {@code @ref} attribute,
 * e.g. {@code {slug}-actors-123} or {@code {slug}-places-45}. Anton already returns
 * the project slug as part of {@code full_id}, so the plugin never assembles it itself.</p>
 */
final class AntonEntity {

    final long id;
    final String fullId;
    final String label;
    final String type;     // Anton authority_type, e.g. "Person" / "Körperschaft" / null
    final String detail;   // optional extra info (place: city/country), may be empty
    final String register; // "actors" | "places"
    /** Raw name strings (name + alternative_names + variants + abbreviations) for finding
     *  further occurrences in the document; may be empty. */
    final List<String> names;

    AntonEntity(long id, String fullId, String label, String type, String detail, String register) {
        this(id, fullId, label, type, detail, register, Collections.<String>emptyList());
    }

    AntonEntity(long id, String fullId, String label, String type, String detail, String register,
                List<String> names) {
        this.id = id;
        this.fullId = fullId;
        this.label = label == null ? "" : label;
        this.type = type == null ? "" : type;
        this.detail = detail == null ? "" : detail;
        this.register = register;
        this.names = names == null ? Collections.<String>emptyList() : names;
    }

    /** Rendered in the results list. */
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append(label.isEmpty() ? fullId : label);
        if (!detail.isEmpty()) {
            b.append(", ").append(detail);
        }
        if (!type.isEmpty()) {
            b.append("  [").append(type).append("]");
        }
        b.append("   → ").append(fullId);
        return b.toString();
    }
}
