package com.gridstore.huevista.image.model;

/**
 * Everything one look at a photo can tell us, in one object.
 *
 * <p>The pipeline has always asked the vision model a single question — inside or
 * outside — because that is all the prompts needed. This is the fuller answer: the same
 * scene verdict, plus what KIND of place it is and what colour its walls are right now.
 *
 * <p>Every field except {@link #scene} is allowed to be absent, and each is absent
 * independently. That is the point: a model that cannot read a colour off a
 * deep-shadowed wall must still be able to tell us the room is a bathroom, and a
 * malformed hex must not cost us the scene the whole run depends on. Consumers treat
 * {@link HouseType#UNKNOWN} and a null hex as "say nothing extra", never as an error.
 *
 * @param scene          INDOOR / OUTDOOR, or null when the photo is neither (= INVALID).
 *                       The one field the pipeline cannot proceed without.
 * @param houseType      never null; {@link HouseType#UNKNOWN} when the model would not
 *                       commit or contradicted its own scene answer
 * @param wallHex        "#rrggbb" of the largest painted wall as it appears under this
 *                       photo's light, or null when no wall is painted, readable, or
 *                       big enough in frame to judge
 * @param wallColourName the model's own everyday words for that colour ("faded
 *                       terracotta"), or null whenever {@code wallHex} is null
 * @param trimHex        "#rrggbb" of the trim when it is clearly a different colour from
 *                       the walls, else null
 */
public record SceneAnalysis(
        ImageType scene,
        HouseType houseType,
        String wallHex,
        String wallColourName,
        String trimHex
) {

    /** The answer when only the scene could be read — every extra field absent. */
    public static SceneAnalysis sceneOnly(ImageType scene) {
        return new SceneAnalysis(scene, HouseType.UNKNOWN, null, null, null);
    }

    /** True when a colour was actually read off a wall, rather than declined. */
    public boolean hasWallColour() {
        return wallHex != null && !wallHex.isBlank();
    }
}
