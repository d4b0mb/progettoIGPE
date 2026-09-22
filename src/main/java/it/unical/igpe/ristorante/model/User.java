package it.unical.igpe.ristorante.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Un dipendente che può accedere al sistema.
 *
 * La password non è mai memorizzata in chiaro: il campo passwordHash contiene
 * il risultato di BCrypt (vedi persistence.PasswordHasher).
 */
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String username;
    private String passwordHash;
    private String fullName;
    private Role role;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    public User() {
        this.role = Role.VIEWER;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    public User(int id, String username, String passwordHash, String fullName, Role role) {
        this();
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
    }

    /** Scorciatoia usata ovunque nella view per abilitare/disabilitare i comandi. */
    public boolean can(Permission permission) {
        return active && role != null && role.can(permission);
    }

    /**
     * Copia indipendente: la scheda utente modifica la copia e la consegna al
     * Model, così un salvataggio rifiutato non lascia l'elenco alterato.
     */
    public User copy() {
        User c = new User(id, username, passwordHash, fullName, role);
        c.active = active;
        c.createdAt = createdAt;
        c.lastLoginAt = lastLoginAt;
        return c;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    @Override
    public String toString() {
        return fullName == null ? username : fullName;
    }
}
