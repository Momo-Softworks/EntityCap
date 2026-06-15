package net.momosoftworks.entitycap.core;

import net.momosoftworks.entitycap.EntityCap;
import net.momosoftworks.entitycap.config.CapConfig;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the cap on a low-frequency server tick. Each pass scans every loaded dimension once (main
 * thread - mandatory in 1.7.10), charges every non-exempt entity to its nearest player, and culls
 * over-budget entities.
 *
 * <p>Three independent budgets keep what players see intact:
 * <ul>
 *   <li><b>Per-player</b> - entities within {@code claimRadius} of a player count against that
 *       player's budget; the total allowance scales with the player count.</li>
 *   <li><b>Unattended</b> - entities with no player within {@code claimRadius} share one generous
 *       per-dimension bucket (chunkloaded farms, spawn chunks, abandoned bases).</li>
 *   <li><b>Dimension backstop</b> - an absolute per-category ceiling on the whole dimension.</li>
 * </ul>
 * Eviction always removes the entity <em>farthest</em> from its nearest player first (oldest as a
 * tiebreaker) and <b>never</b> touches anything within {@code protectedRadius} of a player, so a
 * player never watches an entity poof near them.
 */
public class EntityCapManager {

    /** One loaded entity plus its distance to the nearest player, computed once per sweep. */
    private static final class Tracked {
        final Entity entity;
        final double dist2;        // squared distance to nearest player (MAX if none online)
        final boolean protectedNear; // within protectedRadius of a player -> never cull

        Tracked(Entity entity, double dist2, boolean protectedNear) {
            this.entity = entity;
            this.dist2 = dist2;
            this.protectedNear = protectedNear;
        }
    }

    /** Sorts farthest-from-player first; oldest-loaded breaks ties. These die first. */
    private static final Comparator<Tracked> FARTHEST_FIRST = new Comparator<Tracked>() {
        @Override
        public int compare(Tracked a, Tracked b) {
            if (a.dist2 != b.dist2) {
                return a.dist2 > b.dist2 ? -1 : 1;
            }
            return b.entity.ticksExisted - a.entity.ticksExisted;
        }
    };

    /** Live diagnostics, surfaced by /entitycap status (logging is unreliable across modpacks). */
    public static volatile int sweepCycles;
    public static volatile int lastCycleCulled;
    public static volatile long lastCycleAtMillis;
    public static volatile int joinEvicted;

    private final CapConfig config;
    private final EntityClassifier classifier;
    private int tickCounter;
    private boolean loggedActive;

    public EntityCapManager(CapConfig config, EntityClassifier classifier) {
        this.config = config;
        this.classifier = classifier;
    }

    /**
     * Instant rejection only for an explicit "never allow" (a cap of 0 on the entity dial or its
     * category ceiling). All quantity enforcement is handled by the periodic sweep, which respects
     * the protected radius -- so spawns near a player are never poofed the instant they arrive.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!config.enabled || event.world == null || event.world.isRemote) {
            return;
        }
        Entity newcomer = event.entity;
        if (newcomer == null || classifier.isExempt(newcomer, config)) {
            return;
        }
        EntityCategory category = classifier.classify(newcomer, config);
        String name = classifier.getName(newcomer);
        config.ensureEntity(name, category);

        int indiv = config.getEntityCap(name);
        int cap;
        if (indiv >= 0) {
            cap = indiv;
        } else if (category == EntityCategory.OTHER) {
            return;
        } else {
            cap = config.getCategoryCap(category);
        }
        if (cap == 0) {
            event.setCanceled(true);
            joinEvicted++;
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !config.enabled) {
            return;
        }
        if (!loggedActive) {
            loggedActive = true;
            EntityCap.logger.info("EntityCap: server tick handler active, scanning every "
                    + config.scanIntervalTicks + " ticks.");
        }
        if (++tickCounter < config.scanIntervalTicks) {
            return;
        }
        tickCounter = 0;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null) {
            return;
        }
        int removedThisCycle = 0;
        for (WorldServer world : server.worldServers) {
            if (world == null) {
                continue;
            }
            try {
                removedThisCycle += sweep(world);
            } catch (Throwable t) {
                EntityCap.logger.error("EntityCap: error while sweeping dimension "
                        + (world.provider != null ? world.provider.dimensionId : "?"), t);
            }
        }
        sweepCycles++;
        lastCycleCulled = removedThisCycle;
        lastCycleAtMillis = System.currentTimeMillis();
        // Deliberately NOT saving here. Writing the file mid-session fights anyone live-editing
        // it (the editor sees it change on disk) and could clobber unsaved manual edits. Any
        // entity discovered on-sight stays in memory for this session and is persisted at the next
        // startup or /entitycap reload, when writing the file is expected.
    }

    private int sweep(WorldServer world) {
        final int protectedR2 = config.protectedRadius * config.protectedRadius;
        final int claimR2 = config.claimRadius * config.claimRadius;

        @SuppressWarnings("unchecked")
        List<EntityPlayer> players = new ArrayList<EntityPlayer>(world.playerEntities);

        // Per-dimension dial buckets (individual caps are global, like before).
        Map<String, List<Tracked>> namedBuckets = new HashMap<String, List<Tracked>>();
        // Per-player category buckets: nearest player -> category -> entities.
        Map<EntityPlayer, EnumMap<EntityCategory, List<Tracked>>> perPlayer =
                new HashMap<EntityPlayer, EnumMap<EntityCategory, List<Tracked>>>();
        // One unattended bucket per category, and one whole-dimension bucket per category.
        EnumMap<EntityCategory, List<Tracked>> unattended = new EnumMap<EntityCategory, List<Tracked>>(EntityCategory.class);
        EnumMap<EntityCategory, List<Tracked>> dimension = new EnumMap<EntityCategory, List<Tracked>>(EntityCategory.class);

        List<?> loaded = world.loadedEntityList;
        for (int i = 0; i < loaded.size(); i++) {
            Entity entity = (Entity) loaded.get(i);
            if (classifier.isExempt(entity, config)) {
                continue;
            }
            EntityCategory category = classifier.classify(entity, config);
            String name = classifier.getName(entity);
            config.ensureEntity(name, category);

            // Find the nearest player (cheap: a handful of players, squared distance, no sqrt).
            EntityPlayer nearest = null;
            double best = Double.MAX_VALUE;
            for (int p = 0; p < players.size(); p++) {
                EntityPlayer pl = players.get(p);
                double d2 = entity.getDistanceSqToEntity(pl);
                if (d2 < best) {
                    best = d2;
                    nearest = pl;
                }
            }
            boolean protectedNear = nearest != null && best <= protectedR2;
            Tracked t = new Tracked(entity, best, protectedNear);

            int individualCap = config.getEntityCap(name);
            if (individualCap >= 0) {
                bucket(namedBuckets, name).add(t);
                continue; // dialled entities are governed solely by their dial
            }
            if (category == EntityCategory.OTHER) {
                continue;
            }

            boolean attended = nearest != null && best <= claimR2;
            if (attended) {
                EnumMap<EntityCategory, List<Tracked>> byCat = perPlayer.get(nearest);
                if (byCat == null) {
                    byCat = new EnumMap<EntityCategory, List<Tracked>>(EntityCategory.class);
                    perPlayer.put(nearest, byCat);
                }
                bucket(byCat, category).add(t);
            } else {
                bucket(unattended, category).add(t);
            }
            // Everything (attended or not) also feeds the whole-dimension emergency ceiling.
            bucket(dimension, category).add(t);
        }

        Set<Entity> doomed = new HashSet<Entity>();
        // 1) Individual dials (per dimension).
        for (Map.Entry<String, List<Tracked>> e : namedBuckets.entrySet()) {
            evict(e.getValue(), config.getEntityCap(e.getKey()), doomed);
        }
        // 2) Per-player budgets.
        for (EnumMap<EntityCategory, List<Tracked>> byCat : perPlayer.values()) {
            for (Map.Entry<EntityCategory, List<Tracked>> e : byCat.entrySet()) {
                evict(e.getValue(), config.getPerPlayerCap(e.getKey()), doomed);
            }
        }
        // 3) Unattended budgets.
        for (Map.Entry<EntityCategory, List<Tracked>> e : unattended.entrySet()) {
            evict(e.getValue(), config.getUnattendedCap(e.getKey()), doomed);
        }
        // 4) Dimension backstop (excludes anything already doomed above).
        for (Map.Entry<EntityCategory, List<Tracked>> e : dimension.entrySet()) {
            evict(e.getValue(), config.getCategoryCap(e.getKey()), doomed);
        }

        for (Entity e : doomed) {
            e.setDead();
        }
        if (!doomed.isEmpty()) {
            EntityCap.logger.info("EntityCap: dim "
                    + (world.provider != null ? world.provider.dimensionId : "?")
                    + " culled " + doomed.size()
                    + " entit" + (doomed.size() == 1 ? "y" : "ies") + ".");
        }
        return doomed.size();
    }

    /**
     * Mark the farthest-from-player members of an over-cap bucket for removal, never touching
     * anything inside the protected radius. Entities already marked by an earlier pass don't count
     * toward the cap and aren't re-marked.
     */
    private void evict(List<Tracked> list, int cap, Set<Entity> doomed) {
        if (cap < 0) {
            return;
        }
        List<Tracked> live = new ArrayList<Tracked>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!doomed.contains(list.get(i).entity)) {
                live.add(list.get(i));
            }
        }
        int over = live.size() - cap;
        if (over <= 0) {
            return;
        }
        Collections.sort(live, FARTHEST_FIRST);
        for (int i = 0; i < live.size() && over > 0; i++) {
            Tracked t = live.get(i);
            if (t.protectedNear) {
                continue; // the no-poof bubble wins, even if it leaves the bucket over cap
            }
            doomed.add(t.entity);
            over--;
        }
    }

    private static <K> List<Tracked> bucket(Map<K, List<Tracked>> map, K key) {
        List<Tracked> list = map.get(key);
        if (list == null) {
            list = new ArrayList<Tracked>();
            map.put(key, list);
        }
        return list;
    }
}
