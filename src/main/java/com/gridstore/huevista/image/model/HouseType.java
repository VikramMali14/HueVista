package com.gridstore.huevista.image.model;

/**
 * What KIND of place the photo shows, one level finer than {@link ImageType}.
 *
 * <p>{@code ImageType} answers "is the camera inside or outside", which is enough to pick
 * between two prompts. It is not enough to write either of them well: the exterior prompt
 * spends a paragraph on rooflines and parapets that a compound wall does not have, and the
 * interior prompt's FINISH rules — written for a room whose walls should end up smooth
 * plaster — are exactly wrong in a bathroom, where the tile running to head height is a
 * finished material and not unfinished work.
 *
 * <p>So this exists to let a prompt say one or two more sentences that are true about THIS
 * building. It is deliberately not a taxonomy: every member here earns its place by
 * changing what the cleaning or mask prompt should say, and a distinction that changes
 * nothing (villa vs. bungalow) is not a member.
 *
 * <p>{@link #UNKNOWN} is the honest answer and the safe one. Every consumer must treat it
 * as "add no clause", which is what makes a wrong classification a missed optimisation
 * rather than a wrong instruction.
 */
public enum HouseType {

    // ── Exteriors ────────────────────────────────────────────────────────────
    /** A standalone house — bungalow, villa, farmhouse. The default exterior. */
    INDEPENDENT_HOUSE("Independent house", false),
    /** A multi-storey residential block: repeated floors, repeated balconies. */
    APARTMENT_BLOCK("Apartment block", false),
    /** One unit in a terrace — shares walls, so neighbours are in frame. */
    ROW_HOUSE("Row house", false),
    /** A shop or commercial frontage at street level. Signage is permanent here. */
    SHOPFRONT("Shopfront", false),
    /** A boundary or compound wall / gate. No roof, no windows, no interior. */
    COMPOUND_WALL("Compound wall", false),

    // ── Interiors ────────────────────────────────────────────────────────────
    LIVING_ROOM("Living room", true),
    BEDROOM("Bedroom", true),
    /** Cabinetry and counters dominate; the paintable wall is often a narrow band. */
    KITCHEN("Kitchen", true),
    /** Tiled to head height. The tile is a finish, not an unfinished wall. */
    BATHROOM("Bathroom", true),
    /** Stairwell, landing, corridor — tall, awkwardly lit, little furniture. */
    STAIRWELL_OR_HALLWAY("Stairwell or hallway", true),
    /** An office, shop or showroom interior rather than a home. */
    OFFICE_OR_SHOP("Office or shop interior", true),

    /** The model would not commit, or nobody asked. Adds no prompt clause. */
    UNKNOWN("Unknown", false);

    private final String label;
    private final boolean interior;

    HouseType(String label, boolean interior) {
        this.label = label;
        this.interior = interior;
    }

    /** What this type is called on screen — the admin panel and the logs. */
    public String getLabel() {
        return label;
    }

    /** True for the interior members. UNKNOWN reports false and means nothing by it. */
    public boolean isInterior() {
        return interior;
    }

    /**
     * Parses a name coming from outside — the vision model's JSON, or an admin's
     * override on the segment request.
     *
     * <p>Never throws and never returns null: anything unrecognised is {@link #UNKNOWN},
     * which every consumer already treats as "add no clause". A model that invents a
     * house type should cost us the extra sentence in the prompt, not the run.
     */
    public static HouseType parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (HouseType t : values()) {
            if (t.name().equals(key)) return t;
        }
        return UNKNOWN;
    }

    /**
     * Whether this type can sensibly describe a photo of the given scene.
     *
     * <p>The two answers come from the same model reply, and they can disagree — a photo
     * classified OUTDOOR whose type came back BEDROOM means the model contradicted
     * itself. Rather than trust the half we happen to read second, a mismatch is
     * downgraded to {@link #UNKNOWN} by {@code ClaudeVisionService}: the scene is the
     * answer the pipeline has always relied on, so it wins, and the type simply stops
     * contributing a clause.
     */
    public boolean fits(ImageType scene) {
        if (this == UNKNOWN || scene == null || scene == ImageType.UNKNOWN) return true;
        return interior == (scene == ImageType.INDOOR);
    }
}
