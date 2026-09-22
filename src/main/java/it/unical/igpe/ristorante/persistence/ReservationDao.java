package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import it.unical.igpe.ristorante.model.Reservation;
import it.unical.igpe.ristorante.model.ReservationStatus;

/** Accesso alla tabella reservations. */
public class ReservationDao {

    private final Connection connection;

    public ReservationDao(Database database) {
        this.connection = database.getConnection();
    }

    public List<Reservation> findAll() {
        return query("SELECT * FROM reservations ORDER BY date_time;");
    }

    /**
     * Prenotazioni di un singolo giorno.
     *
     * Poiché le date sono salvate in ISO-8601, tutte le righe di un giorno
     * hanno lo stesso prefisso "AAAA-MM-GG": basta un LIKE, senza funzioni di
     * data specifiche del DBMS.
     */
    public List<Reservation> findByDate(LocalDate date) {
        String sql = "SELECT * FROM reservations WHERE date_time LIKE ? ORDER BY date_time;";
        List<Reservation> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, date.toString() + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura delle prenotazioni del giorno fallita", e);
        }
        return result;
    }

    private List<Reservation> query(String sql) {
        List<Reservation> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura delle prenotazioni fallita", e);
        }
        return result;
    }

    public int insert(Reservation r) {
        String sql = "INSERT INTO reservations (guest_name, phone, email, loyalty_id, date_time, "
                   + "duration_min, party_size, high_chairs, stroller_spaces, table_id, status, "
                   + "allergens, notes, created_by, created_at, updated_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(stmt, r);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    r.setId(keys.getInt(1));
                }
            }
            return r.getId();
        } catch (SQLException e) {
            throw new DataAccessException("Inserimento della prenotazione fallito", e);
        }
    }

    public void update(Reservation r) {
        String sql = "UPDATE reservations SET guest_name = ?, phone = ?, email = ?, loyalty_id = ?, "
                   + "date_time = ?, duration_min = ?, party_size = ?, high_chairs = ?, "
                   + "stroller_spaces = ?, table_id = ?, status = ?, allergens = ?, notes = ?, "
                   + "created_by = ?, created_at = ?, updated_at = ? WHERE id = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bind(stmt, r);
            stmt.setInt(17, r.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Aggiornamento della prenotazione fallito", e);
        }
    }

    public void delete(int reservationId) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM reservations WHERE id = ?;")) {
            stmt.setInt(1, reservationId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Eliminazione della prenotazione fallita", e);
        }
    }

    public int count() {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM reservations;");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DataAccessException("Conteggio delle prenotazioni fallito", e);
        }
    }

    /** I parametri di INSERT e UPDATE sono gli stessi e nello stesso ordine. */
    private void bind(PreparedStatement stmt, Reservation r) throws SQLException {
        stmt.setString(1, r.getGuestName());
        stmt.setString(2, r.getPhone());
        stmt.setString(3, r.getEmail());
        stmt.setString(4, r.getLoyaltyId());
        stmt.setString(5, Converters.toText(r.getDateTime()));
        stmt.setInt(6, r.getDurationMinutes());
        stmt.setInt(7, r.getPartySize());
        stmt.setInt(8, r.getHighChairs());
        stmt.setInt(9, r.getStrollerSpaces());
        stmt.setInt(10, r.getTableId());
        stmt.setString(11, r.getStatus().name());
        stmt.setString(12, Converters.toText(r.getAllergens()));
        stmt.setString(13, r.getNotes());
        stmt.setString(14, r.getCreatedBy());
        stmt.setString(15, Converters.toText(r.getCreatedAt()));
        stmt.setString(16, Converters.toText(r.getUpdatedAt()));
    }

    private Reservation mapRow(ResultSet rs) throws SQLException {
        Reservation r = new Reservation();
        r.setId(rs.getInt("id"));
        r.setGuestName(rs.getString("guest_name"));
        r.setPhone(rs.getString("phone"));
        r.setEmail(rs.getString("email"));
        r.setLoyaltyId(rs.getString("loyalty_id"));
        r.setDateTime(Converters.toDateTime(rs.getString("date_time")));
        r.setDurationMinutes(rs.getInt("duration_min"));
        r.setPartySize(rs.getInt("party_size"));
        r.setHighChairs(rs.getInt("high_chairs"));
        r.setStrollerSpaces(rs.getInt("stroller_spaces"));
        r.setTableId(rs.getInt("table_id"));
        r.setStatus(Converters.toEnum(ReservationStatus.class, rs.getString("status"), ReservationStatus.ATTESA));
        r.setAllergens(Converters.toAllergens(rs.getString("allergens")));
        r.setNotes(rs.getString("notes"));
        r.setCreatedBy(rs.getString("created_by"));
        r.setCreatedAt(Converters.toDateTime(rs.getString("created_at")));
        r.setUpdatedAt(Converters.toDateTime(rs.getString("updated_at")));
        return r;
    }
}
