package de.syntaxium.worldtrim;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entscheidet, ob ein Chunk "unberührte Natur" ist.
 *
 * Bau-Erkennung: Jeder Chunk speichert pro 16er-Abschnitt eine Palette aller darin vorkommenden Blöcke.
 * Taucht dort auch nur EIN Block auf, der nicht auf der Natur-Liste steht (Bretter, Glas, Fackeln,
 * Truhen, Schienen, Betonblöcke ...), gilt der Chunk als bebaut und wird nie gelöscht.
 * Natürliche Strukturen (Dörfer, Minen, Tempel ...) werden dadurch ebenfalls behalten.
 */
public final class ChunkInspector {

    /** Blöcke, die bei der Weltgenerierung von selbst entstehen. Alles andere zählt als Bau. */
    private static final Set<String> NATURAL_BLOCKS = new HashSet<>(List.of(
            // Luft & Flüssigkeiten
            "air", "cave_air", "void_air", "water", "lava", "bubble_column",
            // Gestein & Boden
            "stone", "granite", "diorite", "andesite", "deepslate", "tuff", "calcite", "bedrock",
            "dripstone_block", "pointed_dripstone", "smooth_basalt", "obsidian", "magma_block",
            "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "clay",
            "gravel", "sand", "red_sand", "sandstone", "red_sandstone", "suspicious_sand", "suspicious_gravel",
            "terracotta", "white_terracotta", "orange_terracotta", "yellow_terracotta", "brown_terracotta",
            "red_terracotta", "light_gray_terracotta",
            "snow", "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice",
            "infested_stone", "infested_deepslate",
            // Erze
            "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore", "emerald_ore", "lapis_ore", "diamond_ore",
            "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore", "deepslate_gold_ore",
            "deepslate_redstone_ore", "deepslate_emerald_ore", "deepslate_lapis_ore", "deepslate_diamond_ore",
            "raw_iron_block", "raw_copper_block",
            // Höhlen
            "moss_block", "moss_carpet", "azalea", "flowering_azalea", "azalea_leaves", "flowering_azalea_leaves",
            "hanging_roots", "spore_blossom", "glow_lichen", "cave_vines", "cave_vines_plant",
            "big_dripleaf", "big_dripleaf_stem", "small_dripleaf",
            "amethyst_block", "budding_amethyst", "small_amethyst_bud", "medium_amethyst_bud",
            "large_amethyst_bud", "amethyst_cluster",
            "sculk", "sculk_vein", "sculk_sensor", "sculk_shrieker", "sculk_catalyst",
            // Bäume
            "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log",
            "cherry_log", "pale_oak_log",
            "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
            "mangrove_leaves", "cherry_leaves", "pale_oak_leaves",
            "mangrove_roots", "muddy_mangrove_roots", "mangrove_propagule", "bee_nest",
            "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss", "creaking_heart", "resin_clump",
            "brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "brown_mushroom", "red_mushroom",
            // Pflanzen
            "vine", "cocoa", "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass", "leaf_litter",
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sea_pickle", "sugar_cane", "cactus", "cactus_flower",
            "bamboo", "bamboo_sapling", "lily_pad", "sweet_berry_bush", "pumpkin", "melon",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers",
            "open_eyeblossom", "closed_eyeblossom",
            // Korallen
            "tube_coral_block", "brain_coral_block", "bubble_coral_block", "fire_coral_block", "horn_coral_block",
            "tube_coral", "brain_coral", "bubble_coral", "fire_coral", "horn_coral",
            "tube_coral_fan", "brain_coral_fan", "bubble_coral_fan", "fire_coral_fan", "horn_coral_fan",
            "tube_coral_wall_fan", "brain_coral_wall_fan", "bubble_coral_wall_fan", "fire_coral_wall_fan",
            "horn_coral_wall_fan",
            // Nether
            "netherrack", "soul_sand", "soul_soil", "basalt", "blackstone", "glowstone",
            "nether_quartz_ore", "nether_gold_ore", "ancient_debris",
            "crimson_nylium", "warped_nylium", "crimson_stem", "warped_stem", "nether_wart_block", "warped_wart_block",
            "shroomlight", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots", "nether_sprouts",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "fire", "soul_fire",
            // End
            "end_stone", "chorus_plant", "chorus_flower", "end_gateway"
    ));

    /** Wesen, die von selbst spawnen. Alles andere (Rüstungsständer, Itemrahmen, Loren ...) zählt als Bau. */
    private static final Set<String> NATURAL_ENTITIES = new HashSet<>(List.of(
            "item", "experience_orb", "falling_block",
            "cow", "pig", "sheep", "chicken", "horse", "donkey", "llama", "rabbit", "fox", "wolf", "goat",
            "panda", "polar_bear", "parrot", "ocelot", "bee", "mooshroom", "turtle", "axolotl", "frog", "tadpole",
            "armadillo", "squid", "glow_squid", "cod", "salmon", "tropical_fish", "pufferfish", "dolphin", "bat",
            "zombie", "zombie_villager", "skeleton", "creeper", "spider", "cave_spider", "enderman", "witch",
            "drowned", "husk", "stray", "bogged", "slime", "phantom", "silverfish", "creaking",
            "zombified_piglin", "piglin", "hoglin", "strider", "ghast", "magma_cube"
    ));

    private final Set<String> naturalBlocks;

    public ChunkInspector(Collection<String> extraNaturalBlocks) {
        naturalBlocks = new HashSet<>(NATURAL_BLOCKS);
        for (String s : extraNaturalBlocks) naturalBlocks.add(strip(s));
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** InhabitedTime in Ticks, oder -1 wenn nicht vorhanden. */
    public static long inhabitedTime(Map<String, Object> root) {
        Object v = root.get("InhabitedTime");
        if (v == null) {
            Map<String, Object> level = Nbt.compound(root.get("Level"));
            if (level != null) v = level.get("InhabitedTime");
        }
        return v instanceof Number n ? n.longValue() : -1;
    }

    /**
     * @return null, wenn der Chunk nur natürliche Blöcke enthält; sonst den ersten gefundenen Bau-Block
     *         (oder "?" bei unbekanntem Format – dann wird der Chunk sicherheitshalber behalten).
     */
    public String findBuiltBlock(Map<String, Object> root) {
        Map<String, Object> level = Nbt.compound(root.get("Level"));
        List<Object> sections = Nbt.list(root.get("sections"));
        if (sections == null && level != null) sections = Nbt.list(level.get("Sections"));
        if (sections == null) return "?"; // unbekannter Aufbau -> sicherheitshalber behalten

        for (Object o : sections) {
            Map<String, Object> section = Nbt.compound(o);
            if (section == null) continue;
            List<Object> palette = null;
            Map<String, Object> states = Nbt.compound(section.get("block_states"));
            if (states != null) palette = Nbt.list(states.get("palette"));
            if (palette == null) palette = Nbt.list(section.get("Palette"));
            if (palette == null) {
                // Abschnitt mit Blockdaten, aber ohne lesbare Palette (sehr altes Format) -> behalten
                if (states != null || section.containsKey("BlockStates") || section.containsKey("Blocks")) return "?";
                continue;
            }
            for (Object p : palette) {
                Map<String, Object> entry = Nbt.compound(p);
                String name = entry == null ? null : (String) entry.get("Name");
                if (name == null) return "?";
                if (!naturalBlocks.contains(strip(name))) return strip(name);
            }
        }

        // Sehr alte Chunks speichern Entities noch im Chunk selbst
        if (level != null) {
            String e = findBuiltEntity(Nbt.list(level.get("Entities")));
            if (e != null) return e;
        }
        return null;
    }

    /** Prüft eine Entity-Liste. @return null, wenn nur natürliche Tiere/Monster darin sind. */
    public static String findBuiltEntity(List<Object> entities) {
        if (entities == null) return null;
        for (Object o : entities) {
            Map<String, Object> e = Nbt.compound(o);
            if (e == null) continue;
            String id = e.get("id") instanceof String s ? strip(s) : "?";
            if (!NATURAL_ENTITIES.contains(id)) return "Entity " + id;
            // benannte, gezähmte, angeleinte oder dauerhaft gemachte Tiere zählen als Spielerwerk
            if (e.containsKey("CustomName") || e.containsKey("Owner") || e.containsKey("leash")
                    || e.containsKey("Leash") || isTrue(e.get("PersistenceRequired"))) {
                return "Entity " + id + " (benannt/gezähmt)";
            }
            String passenger = findBuiltEntity(Nbt.list(e.get("Passengers")));
            if (passenger != null) return passenger;
        }
        return null;
    }

    private static boolean isTrue(Object o) {
        return o instanceof Number n && n.intValue() != 0;
    }
}
