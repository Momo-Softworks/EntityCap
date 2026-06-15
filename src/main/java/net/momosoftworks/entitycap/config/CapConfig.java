package net.momosoftworks.entitycap.config;

import net.momosoftworks.entitycap.core.EntityCategory;
import net.momosoftworks.entitycap.core.EntityClassifier;
import net.minecraft.entity.EntityList;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a Forge {@link Configuration}. The [general] section holds the category caps and toggles;
 * the entitycaps.* sections hold a per-entity dial (-1 = no individual cap) that is auto-populated
 * from the entity registry and grows as new entity types are seen.
 */
public class CapConfig {

    private final Configuration cfg;

    public boolean enabled;
    public int scanIntervalTicks;
    public int protectedRadius;
    public int claimRadius;
    public int itemCapPerPlayer;
    public int passiveCapPerPlayer;
    public int hostileCapPerPlayer;
    public int unattendedItemCap;
    public int unattendedPassiveCap;
    public int unattendedHostileCap;
    public int itemCap;
    public int passiveCap;
    public int hostileCap;
    public boolean exemptTamed;
    public boolean exemptNamed;
    public boolean exemptVillagers;
    public boolean exemptGolems;
    public Set<String> excludedNames = new HashSet<String>();
    public Set<String> extraHostileNames = new HashSet<String>();
    public Set<String> extraPassiveNames = new HashSet<String>();

    /** name -> individual cap (only entries with a value are stored; missing = -1). */
    private final Map<String, Integer> entityCaps = new HashMap<String, Integer>();
    private boolean dirty;

    public CapConfig(File file) {
        cfg = new Configuration(file);
        load();
    }

    /** (Re)read everything from disk. */
    public void load() {
        cfg.load();

        enabled = cfg.getBoolean("enabled", "general", true,
                "Master toggle for entity capping.");
        scanIntervalTicks = cfg.getInt("scanIntervalTicks", "general", 100, 1, 72000,
                "Ticks between cap scans (20 ticks = 1 second). Higher = cheaper, less responsive.");

        protectedRadius = cfg.getInt("protectedRadius", "general", 32, 0, 512,
                "The 'no-poof bubble': entities within this many blocks of ANY player are never "
                + "culled, so players never watch something despawn near them. 32 = vanilla's "
                + "never-despawn sphere. Lower it if culling feels too weak near players.");
        claimRadius = cfg.getInt("claimRadius", "general", 128, 16, 512,
                "An entity is 'attended' by the nearest player within this many blocks and counts "
                + "against that player's per-player cap. 128 = vanilla's mob-spawn sphere. Entities "
                + "with no player this close are 'unattended' (see unattended*Cap below).");

        // Per-player caps: the primary near-player control. Each entity is charged to its nearest
        // player within claimRadius; total allowance scales with how many players are online.
        itemCapPerPlayer = cfg.getInt("itemCapPerPlayer", "general", 200, -1, 1000000,
                "Max dropped items charged to a single player (within claimRadius). -1 = unlimited.");
        passiveCapPerPlayer = cfg.getInt("passiveCapPerPlayer", "general", 40, -1, 1000000,
                "Max passive mobs charged to a single player (within claimRadius). -1 = unlimited.");
        hostileCapPerPlayer = cfg.getInt("hostileCapPerPlayer", "general", 70, -1, 1000000,
                "Max hostile mobs charged to a single player (within claimRadius). -1 = unlimited.");

        // Unattended caps: the primary far control. One shared bucket per dimension for entities
        // with no player within claimRadius (chunkloaded farms, spawn chunks, abandoned bases).
        unattendedItemCap = cfg.getInt("unattendedItemCap", "general", 500, -1, 1000000,
                "Max unattended dropped items per dimension (no player within claimRadius). "
                + "-1 = unlimited. A normal farm sits well under this; only runaway piles get trimmed.");
        unattendedPassiveCap = cfg.getInt("unattendedPassiveCap", "general", 250, -1, 1000000,
                "Max unattended passive mobs per dimension (no player within claimRadius). "
                + "-1 = unlimited. Passive mobs never despawn on their own in 1.7.10, so this is "
                + "what stops AFK breeding farms from accumulating forever.");
        unattendedHostileCap = cfg.getInt("unattendedHostileCap", "general", 300, -1, 1000000,
                "Max unattended hostile mobs per dimension (no player within claimRadius). "
                + "-1 = unlimited.");

        // Dimension backstop: an absolute emergency ceiling on the WHOLE dimension per category.
        // The per-player and unattended caps above do the day-to-day work; this only catches
        // pathological totals, so keep it comfortably ABOVE the unattended caps.
        itemCap = cfg.getInt("itemCap", "general", 1500, -1, 1000000,
                "Emergency ceiling: max dropped items per dimension (all of them). -1 = unlimited. "
                + "Keep ABOVE unattendedItemCap; the per-player/unattended caps are the real limits.");
        passiveCap = cfg.getInt("passiveCap", "general", 800, -1, 1000000,
                "Emergency ceiling: max passive mobs per dimension (all of them). -1 = unlimited. "
                + "Keep ABOVE unattendedPassiveCap; the per-player/unattended caps are the real limits.");
        hostileCap = cfg.getInt("hostileCap", "general", 800, -1, 1000000,
                "Emergency ceiling: max hostile mobs per dimension (all of them). -1 = unlimited. "
                + "Keep ABOVE unattendedHostileCap; the per-player/unattended caps are the real limits.");
        exemptTamed = cfg.getBoolean("exemptTamed", "general", true,
                "Never cull tamed/owned pets (wolves, cats, horses).");
        exemptNamed = cfg.getBoolean("exemptNamed", "general", true,
                "Never cull entities with a custom name tag.");
        exemptVillagers = cfg.getBoolean("exemptVillagers", "general", true,
                "Never cull villagers.");
        exemptGolems = cfg.getBoolean("exemptGolems", "general", true,
                "Never cull built golems (iron/snow golems and modded EntityGolem types).");
        excludedNames = toSet(cfg.getStringList("excludedNames", "general", new String[0],
                "Entity names that are never culled, regardless of caps."));
        extraHostileNames = toSet(cfg.getStringList("extraHostileNames", "general", new String[0],
                "Extra entity names to treat as hostile (for mobs not detected automatically)."));
        extraPassiveNames = toSet(cfg.getStringList("extraPassiveNames", "general", new String[0],
                "Extra entity names to treat as passive (for mobs not detected automatically)."));

        // Load any per-entity dials the user already has on disk.
        entityCaps.clear();
        for (EntityCategory c : EntityCategory.values()) {
            loadSection(c);
        }

        if (cfg.hasChanged()) {
            cfg.save();
        }
        dirty = false;
    }

    private void loadSection(EntityCategory category) {
        String section = "entitycaps." + category.sectionName();
        if (!cfg.hasCategory(section)) {
            return;
        }
        ConfigCategory cc = cfg.getCategory(section);
        for (Iterator<String> it = cc.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            Property p = cc.get(key);
            entityCaps.put(key, p.getInt(-1));
        }
    }

    /**
     * Walk the entity registry and make sure every registered type has a dial. Existing values are
     * preserved by {@link Configuration#get}; only missing entries get the -1 default. Call this in
     * postInit once all mods have registered their entities.
     */
    public void populateRegistry(EntityClassifier classifier) {
        for (Iterator<?> it = EntityList.IDtoClassMapping.keySet().iterator(); it.hasNext(); ) {
            Object idObj = it.next();
            int id = ((Number) idObj).intValue();
            Object clsObj = EntityList.IDtoClassMapping.get(idObj);
            if (!(clsObj instanceof Class)) {
                continue;
            }
            Class<?> cls = (Class<?>) clsObj;
            String name = EntityList.getStringFromID(id);
            if (name == null || name.isEmpty()) {
                name = cls.getSimpleName();
            }
            ensureEntity(name, classifier.classifyClass(cls));
        }
        saveIfDirty();
    }

    /**
     * Ensure a dial exists for {@code name} under {@code category}; adds a -1 default if missing.
     * Used both by registry population and "populate on sight" during scans (so modded entities
     * that the registry walk misses still get a dial the first time they load).
     */
    public void ensureEntity(String name, EntityCategory category) {
        if (name == null || entityCaps.containsKey(name)) {
            return;
        }
        String section = "entitycaps." + category.sectionName();
        Property p = cfg.get(section, name, -1,
                "Individual FIFO cap for " + name + ". -1 = governed by its category cap only.");
        entityCaps.put(name, p.getInt(-1));
        dirty = true;
    }

    public int getEntityCap(String name) {
        Integer v = entityCaps.get(name);
        return v == null ? -1 : v.intValue();
    }

    /** Absolute per-dimension emergency ceiling for a category. */
    public int getCategoryCap(EntityCategory category) {
        switch (category) {
            case ITEM:
                return itemCap;
            case PASSIVE:
                return passiveCap;
            case HOSTILE:
                return hostileCap;
            default:
                return -1;
        }
    }

    /** Per-player budget for a category (entities charged to their nearest player). */
    public int getPerPlayerCap(EntityCategory category) {
        switch (category) {
            case ITEM:
                return itemCapPerPlayer;
            case PASSIVE:
                return passiveCapPerPlayer;
            case HOSTILE:
                return hostileCapPerPlayer;
            default:
                return -1;
        }
    }

    /** Shared per-dimension budget for entities with no player within claimRadius. */
    public int getUnattendedCap(EntityCategory category) {
        switch (category) {
            case ITEM:
                return unattendedItemCap;
            case PASSIVE:
                return unattendedPassiveCap;
            case HOSTILE:
                return unattendedHostileCap;
            default:
                return -1;
        }
    }

    public int entityCount() {
        return entityCaps.size();
    }

    public void saveIfDirty() {
        if (dirty || cfg.hasChanged()) {
            cfg.save();
            dirty = false;
        }
    }

    private static Set<String> toSet(String[] values) {
        Set<String> set = new HashSet<String>();
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                set.add(v);
            }
        }
        return set;
    }
}
