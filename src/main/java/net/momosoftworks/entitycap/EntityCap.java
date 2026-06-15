package net.momosoftworks.entitycap;

import net.momosoftworks.entitycap.command.EntityCapCommand;
import net.momosoftworks.entitycap.config.CapConfig;
import net.momosoftworks.entitycap.core.EntityCapManager;
import net.momosoftworks.entitycap.core.EntityClassifier;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.Logger;

/**
 * EntityCap - FIFO total entity capping for Minecraft 1.7.10.
 *
 * Server-side only logic; clients may connect without the mod (acceptableRemoteVersions = "*").
 */
@Mod(modid = EntityCap.MODID, name = EntityCap.NAME, version = EntityCap.VERSION,
        acceptableRemoteVersions = "*")
public class EntityCap {

    public static final String MODID = "entitycap";
    public static final String NAME = "EntityCap";
    public static final String VERSION = "1.0.0";

    public static Logger logger;
    public static CapConfig config;
    public static final EntityClassifier classifier = new EntityClassifier();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        config = new CapConfig(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // All mods have registered their entities by now, so the dial list is complete.
        config.populateRegistry(classifier);
        EntityCapManager manager = new EntityCapManager(config, classifier);
        FMLCommonHandler.instance().bus().register(manager);   // ServerTickEvent (periodic sweep)
        MinecraftForge.EVENT_BUS.register(manager);            // EntityJoinWorldEvent (instant cap)
        logger.info("EntityCap ready with " + config.entityCount() + " entity dials.");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new EntityCapCommand());
    }
}
