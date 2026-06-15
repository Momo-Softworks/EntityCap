package net.momosoftworks.entitycap.core;

/**
 * The buckets an entity can fall into. Each maps to a config sub-section under "entitycaps".
 * OTHER entities (projectiles, minecarts, XP orbs, ...) have no category cap and are only
 * managed if the user gives them an individual dial.
 */
public enum EntityCategory {
    ITEM("item"),
    PASSIVE("passive"),
    HOSTILE("hostile"),
    OTHER("other");

    private final String section;

    EntityCategory(String section) {
        this.section = section;
    }

    public String sectionName() {
        return section;
    }
}
