package com.gridstore.huevista.project.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Canonical ADE20K class names → numeric ID lookup. Models on Replicate
 * sometimes return human-readable labels ("wall", "windowpane") instead
 * of numeric class IDs; this lets us normalize both forms.
 *
 * Only the classes that matter for paint visualization are listed
 * exhaustively. Anything else gets a null lookup and is ignored — the
 * caller will use the numeric ID path when the model returns one.
 */
final class Ade20kClassNames {

    private static final Map<String, Integer> NAME_TO_ID = new HashMap<>();

    static {
        // ADE20K Scene Parsing 150-class index (1-based in some sources, 0-based here).
        put("wall", 0);
        put("building", 1);
        put("edifice", 1);
        put("sky", 2);
        put("floor", 3);
        put("flooring", 3);
        put("tree", 4);
        put("ceiling", 5);
        put("road", 6);
        put("route", 6);
        put("bed", 7);
        put("windowpane", 8);
        put("window", 8);
        put("grass", 9);
        put("cabinet", 10);
        put("sidewalk", 11);
        put("pavement", 11);
        put("person", 12);
        put("earth", 13);
        put("ground", 13);
        put("door", 14);
        put("double door", 14);
        put("table", 15);
        put("mountain", 16);
        put("plant", 17);
        put("flora", 17);
        put("curtain", 18);
        put("drape", 18);
        put("chair", 19);
        put("car", 20);
        put("automobile", 20);
        put("water", 21);
        put("painting", 22);
        put("picture", 22);
        put("sofa", 23);
        put("couch", 23);
        put("shelf", 24);
        put("house", 25);
        put("sea", 26);
        put("mirror", 27);
        put("rug", 28);
        put("field", 29);
        put("armchair", 30);
        put("seat", 31);
        put("fence", 32);
        put("desk", 33);
        put("rock", 34);
        put("stone", 34);
        put("wardrobe", 35);
        put("lamp", 36);
        put("bathtub", 37);
        put("railing", 38);
        put("cushion", 39);
        put("base", 40);
        put("box", 41);
        put("column", 42);
        put("pillar", 42);
        put("signboard", 43);
        put("sign", 43);
        put("chest of drawers", 44);
        put("dresser", 44);
        put("counter", 45);
        put("sand", 46);
        put("sink", 47);
        put("skyscraper", 48);
        put("fireplace", 49);
        put("refrigerator", 50);
        put("grandstand", 51);
        put("path", 52);
        put("stairs", 53);
        put("runway", 54);
        put("case", 55);
        put("pool table", 56);
        put("pillow", 57);
        put("screen door", 58);
        put("stairway", 59);
        put("river", 60);
        put("bridge", 61);
        put("bookcase", 62);
        put("blind", 63);
        put("coffee table", 64);
        put("toilet", 65);
        put("flower", 66);
        put("book", 67);
        put("hill", 68);
        put("bench", 69);
        put("countertop", 70);
        put("stove", 71);
        put("palm", 72);
        put("kitchen island", 73);
        put("computer", 74);
        put("swivel chair", 75);
        put("boat", 76);
        put("bar", 77);
        put("arcade machine", 78);
        put("hovel", 79);
        put("bus", 80);
        put("towel", 81);
        put("light", 82);
        put("light fixture", 82);
        put("truck", 83);
        put("tower", 84);
        put("chandelier", 85);
        put("awning", 86);
        put("streetlight", 87);
        put("booth", 88);
        put("television", 89);
        put("tv", 89);
        put("airplane", 90);
        put("dirt track", 91);
        put("apparel", 92);
        put("clothing", 92);
        put("pole", 93);
        put("land", 94);
        put("bannister", 95);
        put("escalator", 96);
        put("ottoman", 97);
        put("bottle", 98);
        put("buffet", 99);
        put("poster", 100);
        put("stage", 101);
        put("van", 102);
        put("ship", 103);
        put("fountain", 104);
        put("conveyer belt", 105);
        put("canopy", 106);
        put("washer", 107);
        put("plaything", 108);
        put("swimming pool", 109);
        put("stool", 110);
        put("barrel", 111);
        put("basket", 112);
        put("waterfall", 113);
        put("tent", 114);
        put("bag", 115);
        put("minibike", 116);
        put("cradle", 117);
        put("oven", 118);
        put("ball", 119);
        put("food", 120);
        put("step", 121);
        put("tank", 122);
        put("trade name", 123);
        put("microwave", 124);
        put("pot", 125);
        put("animal", 126);
        put("bicycle", 127);
        put("lake", 128);
        put("dishwasher", 129);
        put("screen", 130);
        put("blanket", 131);
        put("sculpture", 132);
        put("hood", 133);
        put("sconce", 134);
        put("vase", 135);
        put("traffic light", 136);
        put("tray", 137);
        put("ashcan", 138);
        put("trash can", 138);
        put("fan", 139);
        put("pier", 140);
        put("crt screen", 141);
        put("plate", 142);
        put("monitor", 143);
        put("bulletin board", 144);
        put("shower", 145);
        put("radiator", 146);
        put("glass", 147);
        put("clock", 148);
        put("flag", 149);
    }

    private Ade20kClassNames() {}

    private static void put(String name, int id) {
        NAME_TO_ID.put(name.toLowerCase(), id);
    }

    /** Returns the ADE20K class ID for a human-readable label, or null. */
    static Integer idFor(String name) {
        if (name == null) return null;
        return NAME_TO_ID.get(name.trim().toLowerCase());
    }
}
