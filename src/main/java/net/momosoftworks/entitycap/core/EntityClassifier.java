package net.momosoftworks.entitycap.core;

import net.momosoftworks.entitycap.config.CapConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Pure helpers for naming, classifying and exempting entities. The single {@link #getName}
 * helper is used everywhere (config population, classification and cap lookup) so the string a
 * dial is keyed on always matches the string an entity reports.
 */
public class EntityClassifier {

    /**
     * Canonical name for an entity. Vanilla entities report via {@link EntityList#getEntityString};
     * some modded entities return null there, so we fall back to the class' simple name. Because
     * this same method is used to populate the config and to look caps up, the keys always agree.
     */
    public String getName(Entity entity) {
        String name = EntityList.getEntityString(entity);
        if (name == null || name.isEmpty()) {
            name = entity.getClass().getSimpleName();
        }
        return name;
    }

    /** Classify by concrete class (used when populating the config from the registry). */
    public EntityCategory classifyClass(Class<?> cls) {
        if (EntityItem.class.isAssignableFrom(cls)) {
            return EntityCategory.ITEM;
        }
        // IMob extends IAnimals in 1.7.10, so it must be checked first.
        if (IMob.class.isAssignableFrom(cls)) {
            return EntityCategory.HOSTILE;
        }
        if (EntityAnimal.class.isAssignableFrom(cls)
                || IAnimals.class.isAssignableFrom(cls)
                || EntityWaterMob.class.isAssignableFrom(cls)
                || EntityAmbientCreature.class.isAssignableFrom(cls)
                || EntityVillager.class.isAssignableFrom(cls)) {
            return EntityCategory.PASSIVE;
        }
        return EntityCategory.OTHER;
    }

    /** Classify a live entity, honouring the user's reclassification overrides. */
    public EntityCategory classify(Entity entity, CapConfig config) {
        if (entity instanceof EntityItem) {
            return EntityCategory.ITEM;
        }
        String name = getName(entity);
        if (config.extraHostileNames.contains(name)) {
            return EntityCategory.HOSTILE;
        }
        if (config.extraPassiveNames.contains(name)) {
            return EntityCategory.PASSIVE;
        }
        if (entity instanceof IMob) {
            return EntityCategory.HOSTILE;
        }
        if (entity instanceof EntityAnimal
                || entity instanceof IAnimals
                || entity instanceof EntityWaterMob
                || entity instanceof EntityAmbientCreature
                || entity instanceof EntityVillager) {
            return EntityCategory.PASSIVE;
        }
        return EntityCategory.OTHER;
    }

    /** True if the entity must never be culled. */
    public boolean isExempt(Entity entity, CapConfig config) {
        if (entity instanceof EntityPlayer) {
            return true;
        }
        if (!entity.isEntityAlive()) {
            return true;
        }
        // Bosses (Ender Dragon, Wither, modded bosses) all implement this marker.
        if (entity instanceof IBossDisplayData) {
            return true;
        }
        String name = getName(entity);
        if (config.excludedNames.contains(name)) {
            return true;
        }
        if (config.exemptTamed && entity instanceof EntityTameable && ((EntityTameable) entity).isTamed()) {
            return true;
        }
        if (config.exemptVillagers && entity instanceof EntityVillager) {
            return true;
        }
        // Built golems (iron/snow, and modded golems extending EntityGolem) are village/base defenders.
        if (config.exemptGolems && entity instanceof EntityGolem) {
            return true;
        }
        if (config.exemptNamed && entity instanceof EntityLiving && ((EntityLiving) entity).hasCustomNameTag()) {
            return true;
        }
        return false;
    }
}
