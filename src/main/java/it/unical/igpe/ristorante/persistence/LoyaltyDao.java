package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.unical.igpe.ristorante.model.loyalty.LoyaltyAccount;
import it.unical.igpe.ristorante.model.loyalty.LoyaltySummary;
import it.unical.igpe.ristorante.model.loyalty.LoyaltyVisit;

/** Accesso alle tabelle loyalty_accounts e loyalty_visits. */
public class LoyaltyDao {

    private static final Pattern CODE_NUMBER = Pattern.compile("^FID-(\\d+)$");

    private final Connection connection;

    public LoyaltyDao(Database database) {
        this.connection = database.getConnection();
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    public List<LoyaltyAccount> findAllAccounts() {
        String sql = "SELECT * FROM loyalty_accounts ORDER BY guest_name;";
        List<LoyaltyAccount> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(mapAccount(rs));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura dei clienti fedeltà fallita", e);
        }
        return result;
    }

    public LoyaltyAccount findAccountByCode(String code) {
        String sql = "SELECT * FROM loyalty_accounts WHERE code = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, code);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapAccount(rs) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Ricerca del cliente fedeltà fallita", e);
        }
    }

    /**
     * Il prossimo codice libero, in sequenza: FID-00001, FID-00002, ...
     *
     * Si guarda il numero più alto già usato invece di contare le righe: un
     * account eliminato non farebbe così riassegnare un codice già stampato
     * su una tessera cliente.
     */
    public String nextCode() {
        int max = 0;
        try (PreparedStatement stmt = connection.prepareStatement("SELECT code FROM loyalty_accounts;");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Matcher m = CODE_NUMBER.matcher(rs.getString("code"));
                if (m.matches()) {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Calcolo del prossimo codice fedeltà fallito", e);
        }
        return String.format("FID-%05d", max + 1);
    }

    public int insertAccount(LoyaltyAccount a) {
        String sql = "INSERT INTO loyalty_accounts (code, guest_name, phone, email, created_by, created_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, a.getCode());
            stmt.setString(2, a.getGuestName());
            stmt.setString(3, a.getPhone());
            stmt.setString(4, a.getEmail());
            stmt.setString(5, a.getCreatedBy());
            stmt.setString(6, Converters.toText(a.getCreatedAt()));
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    a.setId(keys.getInt(1));
                }
            }
            return a.getId();
        } catch (SQLException e) {
            throw new DataAccessException("Inserimento del cliente fedeltà fallito", e);
        }
    }

    public void updateAccount(LoyaltyAccount a) {
        String sql = "UPDATE loyalty_accounts SET guest_name = ?, phone = ?, email = ? WHERE id = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, a.getGuestName());
            stmt.setString(2, a.getPhone());
            stmt.setString(3, a.getEmail());
            stmt.setInt(4, a.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Aggiornamento del cliente fedeltà fallito", e);
        }
    }

    public void deleteAccount(int accountId) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM loyalty_accounts WHERE id = ?;")) {
            stmt.setInt(1, accountId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Eliminazione del cliente fedeltà fallita", e);
        }
    }

    private LoyaltyAccount mapAccount(ResultSet rs) throws SQLException {
        LoyaltyAccount a = new LoyaltyAccount();
        a.setId(rs.getInt("id"));
        a.setCode(rs.getString("code"));
        a.setGuestName(rs.getString("guest_name"));
        a.setPhone(rs.getString("phone"));
        a.setEmail(rs.getString("email"));
        a.setCreatedBy(rs.getString("created_by"));
        a.setCreatedAt(Converters.toDateTime(rs.getString("created_at")));
        return a;
    }

    // ------------------------------------------------------------------
    // Visite
    // ------------------------------------------------------------------

    public List<LoyaltyVisit> findVisitsForAccount(int accountId) {
        String sql = "SELECT * FROM loyalty_visits WHERE account_id = ? ORDER BY visit_date DESC, id DESC;";
        List<LoyaltyVisit> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapVisit(rs));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura delle visite fallita", e);
        }
        return result;
    }

    public int insertVisit(LoyaltyVisit v) {
        String sql = "INSERT INTO loyalty_visits "
                   + "(account_id, visit_date, total_amount, paid_amount, notes, created_by, created_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, v.getAccountId());
            stmt.setString(2, v.getVisitDate().toString());
            stmt.setDouble(3, v.getTotalAmount());
            stmt.setDouble(4, v.getPaidAmount());
            stmt.setString(5, v.getNotes());
            stmt.setString(6, v.getCreatedBy());
            stmt.setString(7, Converters.toText(v.getCreatedAt()));
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    v.setId(keys.getInt(1));
                }
            }
            return v.getId();
        } catch (SQLException e) {
            throw new DataAccessException("Inserimento della visita fallito", e);
        }
    }

    public void deleteVisit(int visitId) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM loyalty_visits WHERE id = ?;")) {
            stmt.setInt(1, visitId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Eliminazione della visita fallita", e);
        }
    }

    /** Numero di visite, spesa totale e pagato totale di un cliente, in un'unica interrogazione. */
    public LoyaltySummary summarize(int accountId) {
        String sql = "SELECT COUNT(*) AS n, COALESCE(SUM(total_amount), 0) AS total, "
                   + "COALESCE(SUM(paid_amount), 0) AS paid, MAX(visit_date) AS last_visit "
                   + "FROM loyalty_visits WHERE account_id = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, accountId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return new LoyaltySummary(0, 0, 0, null);
                }
                String lastVisit = rs.getString("last_visit");
                return new LoyaltySummary(rs.getInt("n"), rs.getDouble("total"), rs.getDouble("paid"),
                        lastVisit == null ? null : LocalDate.parse(lastVisit));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Calcolo del riepilogo fedeltà fallito", e);
        }
    }

    private LoyaltyVisit mapVisit(ResultSet rs) throws SQLException {
        LoyaltyVisit v = new LoyaltyVisit();
        v.setId(rs.getInt("id"));
        v.setAccountId(rs.getInt("account_id"));
        v.setVisitDate(LocalDate.parse(rs.getString("visit_date")));
        v.setTotalAmount(rs.getDouble("total_amount"));
        v.setPaidAmount(rs.getDouble("paid_amount"));
        v.setNotes(rs.getString("notes"));
        v.setCreatedBy(rs.getString("created_by"));
        v.setCreatedAt(Converters.toDateTime(rs.getString("created_at")));
        return v;
    }
}
