package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import it.unical.igpe.ristorante.model.kitchen.Course;
import it.unical.igpe.ristorante.model.kitchen.Ticket;
import it.unical.igpe.ristorante.model.kitchen.TicketItem;
import it.unical.igpe.ristorante.model.kitchen.TicketPriority;
import it.unical.igpe.ristorante.model.kitchen.TicketStatus;

/**
 * Accesso alle tabelle tickets e ticket_items.
 *
 * Le comande sono salvate dal SERVER, non dai client: il server è l'unico
 * punto in cui passano tutte le comande di tutte le postazioni, quindi è
 * l'unico che può tenere uno storico completo e coerente.
 */
public class TicketDao {

    private final Connection connection;

    public TicketDao(Database database) {
        this.connection = database.getConnection();
    }

    public List<Ticket> findByDate(LocalDate date) {
        String sql = "SELECT * FROM tickets WHERE created_at LIKE ? ORDER BY id;";
        List<Ticket> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, date.toString() + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura delle comande fallita", e);
        }
        for (Ticket t : result) {
            loadItems(t);
        }
        return result;
    }

    private void loadItems(Ticket ticket) {
        String sql = "SELECT * FROM ticket_items WHERE ticket_id = ? ORDER BY id;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, ticket.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TicketItem item = new TicketItem();
                    item.setName(rs.getString("name"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setCourse(Converters.toEnum(Course.class, rs.getString("course"), Course.PRIMO));
                    item.setAllergens(Converters.toAllergens(rs.getString("allergens")));
                    item.setNote(rs.getString("note"));
                    ticket.addItem(item);
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura delle righe della comanda fallita", e);
        }
    }

    public int insert(Ticket ticket) {
        String sql = "INSERT INTO tickets (code, table_number, reservation_id, guest_name, guest_allergens, "
                   + "priority, status, note, created_by, created_at, acknowledged_at, ready_at, served_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, ticket.getCode());
            stmt.setInt(2, ticket.getTableNumber());
            stmt.setInt(3, ticket.getReservationId());
            stmt.setString(4, ticket.getGuestName());
            stmt.setString(5, Converters.toText(ticket.getGuestAllergens()));
            stmt.setString(6, ticket.getPriority().name());
            stmt.setString(7, ticket.getStatus().name());
            stmt.setString(8, ticket.getNote());
            stmt.setString(9, ticket.getCreatedBy());
            stmt.setString(10, Converters.toText(ticket.getCreatedAt()));
            stmt.setString(11, Converters.toText(ticket.getAcknowledgedAt()));
            stmt.setString(12, Converters.toText(ticket.getReadyAt()));
            stmt.setString(13, Converters.toText(ticket.getServedAt()));
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    ticket.setId(keys.getInt(1));
                }
            }
            insertItems(ticket);
            return ticket.getId();
        } catch (SQLException e) {
            throw new DataAccessException("Inserimento della comanda fallito", e);
        }
    }

    private void insertItems(Ticket ticket) throws SQLException {
        String sql = "INSERT INTO ticket_items (ticket_id, name, quantity, course, allergens, note) "
                   + "VALUES (?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (TicketItem item : ticket.getItems()) {
                stmt.setInt(1, ticket.getId());
                stmt.setString(2, item.getName());
                stmt.setInt(3, item.getQuantity());
                stmt.setString(4, item.getCourse().name());
                stmt.setString(5, Converters.toText(item.getAllergens()));
                stmt.setString(6, item.getNote());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    /** Aggiorna solo lo stato e le marcature temporali: le righe non cambiano più. */
    public void updateStatus(Ticket ticket) {
        String sql = "UPDATE tickets SET status = ?, priority = ?, acknowledged_at = ?, "
                   + "ready_at = ?, served_at = ? WHERE id = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ticket.getStatus().name());
            stmt.setString(2, ticket.getPriority().name());
            stmt.setString(3, Converters.toText(ticket.getAcknowledgedAt()));
            stmt.setString(4, Converters.toText(ticket.getReadyAt()));
            stmt.setString(5, Converters.toText(ticket.getServedAt()));
            stmt.setInt(6, ticket.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Aggiornamento della comanda fallito", e);
        }
    }

    /** Progressivo giornaliero usato per generare i codici comanda (C-001, C-002...). */
    public int countToday() {
        String sql = "SELECT COUNT(*) FROM tickets WHERE created_at LIKE ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, LocalDate.now().toString() + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Conteggio delle comande fallito", e);
        }
    }

    private Ticket mapRow(ResultSet rs) throws SQLException {
        Ticket t = new Ticket();
        t.setId(rs.getInt("id"));
        t.setCode(rs.getString("code"));
        t.setTableNumber(rs.getInt("table_number"));
        t.setReservationId(rs.getInt("reservation_id"));
        t.setGuestName(rs.getString("guest_name"));
        t.setGuestAllergens(Converters.toAllergens(rs.getString("guest_allergens")));
        t.setPriority(Converters.toEnum(TicketPriority.class, rs.getString("priority"), TicketPriority.NORMALE));
        t.setStatus(Converters.toEnum(TicketStatus.class, rs.getString("status"), TicketStatus.NUOVA));
        t.setNote(rs.getString("note"));
        t.setCreatedBy(rs.getString("created_by"));
        t.setCreatedAt(Converters.toDateTime(rs.getString("created_at")));
        t.setAcknowledgedAt(Converters.toDateTime(rs.getString("acknowledged_at")));
        t.setReadyAt(Converters.toDateTime(rs.getString("ready_at")));
        t.setServedAt(Converters.toDateTime(rs.getString("served_at")));
        return t;
    }
}
