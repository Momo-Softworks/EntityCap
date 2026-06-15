package net.momosoftworks.entitycap.command;

import net.momosoftworks.entitycap.EntityCap;
import net.momosoftworks.entitycap.core.EntityCapManager;
import net.momosoftworks.entitycap.core.EntityCategory;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.WorldServer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * /entitycap status|names|reload
 *  - status: per-dimension counts vs configured caps
 *  - names:  distinct currently-loaded entity names + counts (full registry lives in the config)
 *  - reload: re-read the config and re-populate per-entity dials live
 */
public class EntityCapCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "entitycap";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/entitycap <status|names|reload>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, getCommandUsage(sender));
            return;
        }
        String sub = args[0].toLowerCase();
        if ("reload".equals(sub)) {
            EntityCap.config.load();
            EntityCap.config.populateRegistry(EntityCap.classifier);
            msg(sender, "[EntityCap] Config reloaded (" + EntityCap.config.entityCount() + " dials).");
        } else if ("status".equals(sub)) {
            status(sender);
        } else if ("names".equals(sub)) {
            names(sender);
        } else {
            msg(sender, getCommandUsage(sender));
        }
    }

    private void status(ICommandSender sender) {
        msg(sender, "[EntityCap] per-player (i/p/h): " + EntityCap.config.itemCapPerPlayer
                + "/" + EntityCap.config.passiveCapPerPlayer
                + "/" + EntityCap.config.hostileCapPerPlayer
                + " | unattended: " + EntityCap.config.unattendedItemCap
                + "/" + EntityCap.config.unattendedPassiveCap
                + "/" + EntityCap.config.unattendedHostileCap
                + " | dim ceiling: " + EntityCap.config.itemCap
                + "/" + EntityCap.config.passiveCap
                + "/" + EntityCap.config.hostileCap
                + (EntityCap.config.enabled ? "" : " (DISABLED)"));
        msg(sender, "[EntityCap] protect=" + EntityCap.config.protectedRadius
                + "b claim=" + EntityCap.config.claimRadius
                + "b interval=" + EntityCap.config.scanIntervalTicks + "t");

        // Diagnostics: confirms the handlers are actually running (independent of log config).
        msg(sender, "[EntityCap] blacklist-rejected (total): " + EntityCapManager.joinEvicted);
        if (EntityCapManager.sweepCycles == 0) {
            msg(sender, "[EntityCap] periodic scans run: 0 -- scan handler has not fired yet!");
        } else {
            long agoMs = System.currentTimeMillis() - EntityCapManager.lastCycleAtMillis;
            msg(sender, "[EntityCap] periodic scans run: " + EntityCapManager.sweepCycles
                    + " | last scan removed " + EntityCapManager.lastCycleCulled
                    + " (" + (agoMs / 1000) + "s ago)");
        }

        WorldServer[] worlds = MinecraftServer.getServer().worldServers;
        boolean anyShown = false;
        for (WorldServer world : worlds) {
            if (world == null) {
                continue;
            }
            int[] counts = new int[EntityCategory.values().length];
            int total = 0;
            List<?> loaded = world.loadedEntityList;
            for (int i = 0; i < loaded.size(); i++) {
                Entity e = (Entity) loaded.get(i);
                if (EntityCap.classifier.isExempt(e, EntityCap.config)) {
                    continue;
                }
                counts[EntityCap.classifier.classify(e, EntityCap.config).ordinal()]++;
                total++;
            }
            if (total == 0) {
                continue; // hide the dozens of empty/registered dimensions
            }
            anyShown = true;
            msg(sender, "  dim " + world.provider.dimensionId
                    + " (" + world.provider.getDimensionName() + ", "
                    + world.playerEntities.size() + "p)"
                    + ": item=" + counts[EntityCategory.ITEM.ordinal()]
                    + " passive=" + counts[EntityCategory.PASSIVE.ordinal()]
                    + " hostile=" + counts[EntityCategory.HOSTILE.ordinal()]
                    + " other=" + counts[EntityCategory.OTHER.ordinal()]);
        }
        if (!anyShown) {
            msg(sender, "  (no capped entities loaded in any dimension)");
        }
    }

    private void names(ICommandSender sender) {
        Map<String, Integer> tally = new TreeMap<String, Integer>();
        WorldServer[] worlds = MinecraftServer.getServer().worldServers;
        for (WorldServer world : worlds) {
            if (world == null) {
                continue;
            }
            List<?> loaded = world.loadedEntityList;
            for (int i = 0; i < loaded.size(); i++) {
                String name = EntityCap.classifier.getName((Entity) loaded.get(i));
                Integer c = tally.get(name);
                tally.put(name, c == null ? 1 : c + 1);
            }
        }
        msg(sender, "[EntityCap] loaded entity names (" + tally.size() + "):");
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            msg(sender, "  " + e.getKey() + " = " + e.getValue());
        }
    }

    private static void msg(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
