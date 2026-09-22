package it.unical.igpe.ristorante.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * I tre livelli di accesso richiesti dalle specifiche.
 *
 * Ogni ruolo porta con se' l'insieme dei privilegi che concede: il controllo
 * degli accessi è quindi centralizzato qui e non sparso nell'interfaccia.
 */
public enum Role {

    /** Tier 1: accesso completo, può modificare qualsiasi cosa. */
    ADMIN(1, "Amministratore", EnumSet.allOf(Permission.class)),

    /** Tier 2: può inserire e modificare prenotazioni e usare la cucina. */
    OPERATOR(2, "Operatore", EnumSet.of(
            Permission.VIEW_RESERVATIONS,
            Permission.EDIT_RESERVATIONS,
            Permission.VIEW_FLOOR_PLAN,
            Permission.SEND_TICKETS,
            Permission.MANAGE_KITCHEN)),

    /** Tier 3: sola lettura. */
    VIEWER(3, "Sola lettura", EnumSet.of(
            Permission.VIEW_RESERVATIONS,
            Permission.VIEW_FLOOR_PLAN));

    private final int tier;
    private final String label;
    private final Set<Permission> permissions;

    Role(int tier, String label, Set<Permission> permissions) {
        this.tier = tier;
        this.label = label;
        // unmodifiableSet: nessuno può aggiungere privilegi a runtime.
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public int getTier() {
        return tier;
    }

    public String getLabel() {
        return label;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    @Override
    public String toString() {
        return "Tier " + tier + " - " + label;
    }
}
