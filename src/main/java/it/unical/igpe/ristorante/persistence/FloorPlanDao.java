package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import it.unical.igpe.ristorante.model.floor.FloorElement;
import it.unical.igpe.ristorante.model.floor.FloorPlan;
import it.unical.igpe.ristorante.model.floor.Obstacle;
import it.unical.igpe.ristorante.model.floor.ObstacleType;
import it.unical.igpe.ristorante.model.floor.RestaurantTable;
import it.unical.igpe.ristorante.model.floor.TableShape;
import it.unical.igpe.ristorante.model.floor.TableStatus;

/**
 * Salvataggio e caricamento della piantina.
 *
 * Tavoli e ostacoli condividono la stessa tabella floor_elements: la colonna
 * "kind" dice quale sottoclasse ricostruire in fase di lettura. È la
 * strategia "single table inheritance", la più semplice da leggere quando le
 * sottoclassi sono poche e simili.
 *
 * Il salvataggio è fatto in transazione: o si scrive tutta la piantina o non
 * si scrive niente. Senza transazione, un errore a metà lascerebbe sul disco
 * una sala con i muri della versione nuova e i tavoli di quella vecchia.
 */
public class FloorPlanDao {

    private final Connection connection;

    public FloorPlanDao(Database database) {
        this.connection = database.getConnection();
    }

    /** Carica la prima piantina disponibile, oppure null se non ne esistono. */
    public FloorPlan loadFirst() {
        String sql = "SELECT * FROM floor_plans ORDER BY id LIMIT 1;";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            FloorPlan plan = new FloorPlan();
            plan.setId(rs.getInt("id"));
            plan.setName(rs.getString("name"));
            plan.setWidthMeters(rs.getDouble("width_m"));
            plan.setHeightMeters(rs.getDouble("height_m"));
            plan.setGridStepMeters(rs.getDouble("grid_step_m"));
            loadElements(plan);
            return plan;
        } catch (SQLException e) {
            throw new DataAccessException("Caricamento della piantina fallito", e);
        }
    }

    private void loadElements(FloorPlan plan) throws SQLException {
        String sql = "SELECT * FROM floor_elements WHERE plan_id = ? ORDER BY id;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, plan.getId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    plan.add(mapElement(rs));
                }
            }
        }
    }

    private FloorElement mapElement(ResultSet rs) throws SQLException {
        String kind = rs.getString("kind");
        FloorElement element;

        if ("TABLE".equals(kind)) {
            RestaurantTable table = new RestaurantTable();
            table.setNumber(rs.getInt("table_number"));
            table.setShape(Converters.toEnum(TableShape.class, rs.getString("shape"), TableShape.RECTANGLE));
            // Si ricarica solo lo stato deciso dall'operatore. Le versioni
            // precedenti salvavano anche PRENOTATO/OCCUPATO, che però sono
            // calcolati dalle prenotazioni: setServiceStatus li riporta a LIBERO.
            table.setServiceStatus(Converters.toEnum(TableStatus.class, rs.getString("table_status"), TableStatus.LIBERO));
            table.setAccessible(rs.getInt("accessible") == 1);
            element = table;
        } else {
            Obstacle obstacle = new Obstacle();
            obstacle.setType(Converters.toEnum(ObstacleType.class, rs.getString("obstacle_type"), ObstacleType.MURO));
            element = obstacle;
        }

        element.setId(rs.getInt("element_id"));
        element.setName(rs.getString("name"));
        element.setXMeters(rs.getDouble("x_m"));
        element.setYMeters(rs.getDouble("y_m"));
        element.setWidthMeters(rs.getDouble("w_m"));
        element.setHeightMeters(rs.getDouble("h_m"));
        element.setRotationDegrees(rs.getDouble("rotation"));

        // I posti vanno impostati DOPO le dimensioni: il setter della forma
        // riallinea larghezza e altezza e non deve sovrascrivere il valore salvato.
        if (element instanceof RestaurantTable table) {
            table.setSeats(rs.getInt("seats"));
        }
        return element;
    }

    /** Salva la piantina, sostituendo integralmente gli elementi precedenti. */
    public void save(FloorPlan plan) {
        try {
            connection.setAutoCommit(false);

            if (plan.getId() == 0) {
                insertPlan(plan);
            } else {
                updatePlan(plan);
            }

            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM floor_elements WHERE plan_id = ?;")) {
                del.setInt(1, plan.getId());
                del.executeUpdate();
            }

            insertElements(plan);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new DataAccessException("Salvataggio della piantina fallito", e);
        } finally {
            restoreAutoCommit();
        }
    }

    private void insertPlan(FloorPlan plan) throws SQLException {
        String sql = "INSERT INTO floor_plans (name, width_m, height_m, grid_step_m) VALUES (?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, plan.getName());
            stmt.setDouble(2, plan.getWidthMeters());
            stmt.setDouble(3, plan.getHeightMeters());
            stmt.setDouble(4, plan.getGridStepMeters());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    plan.setId(keys.getInt(1));
                }
            }
        }
    }

    private void updatePlan(FloorPlan plan) throws SQLException {
        String sql = "UPDATE floor_plans SET name = ?, width_m = ?, height_m = ?, grid_step_m = ? WHERE id = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, plan.getName());
            stmt.setDouble(2, plan.getWidthMeters());
            stmt.setDouble(3, plan.getHeightMeters());
            stmt.setDouble(4, plan.getGridStepMeters());
            stmt.setInt(5, plan.getId());
            stmt.executeUpdate();
        }
    }

    private void insertElements(FloorPlan plan) throws SQLException {
        String sql = "INSERT INTO floor_elements (plan_id, element_id, kind, name, x_m, y_m, w_m, h_m, "
                   + "rotation, table_number, seats, shape, table_status, accessible, obstacle_type) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (FloorElement e : plan.getElements()) {
                stmt.setInt(1, plan.getId());
                stmt.setInt(2, e.getId());
                stmt.setString(3, (e instanceof RestaurantTable) ? "TABLE" : "OBSTACLE");
                stmt.setString(4, e.getName());
                stmt.setDouble(5, e.getXMeters());
                stmt.setDouble(6, e.getYMeters());
                stmt.setDouble(7, e.getWidthMeters());
                stmt.setDouble(8, e.getHeightMeters());
                stmt.setDouble(9, e.getRotationDegrees());

                if (e instanceof RestaurantTable t) {
                    stmt.setInt(10, t.getNumber());
                    stmt.setInt(11, t.getSeats());
                    stmt.setString(12, t.getShape().name());
                    stmt.setString(13, t.getServiceStatus().name());
                    stmt.setInt(14, t.isAccessible() ? 1 : 0);
                    stmt.setNull(15, java.sql.Types.VARCHAR);
                } else {
                    Obstacle o = (Obstacle) e;
                    stmt.setNull(10, java.sql.Types.INTEGER);
                    stmt.setNull(11, java.sql.Types.INTEGER);
                    stmt.setNull(12, java.sql.Types.VARCHAR);
                    stmt.setNull(13, java.sql.Types.VARCHAR);
                    stmt.setInt(14, 0);
                    stmt.setString(15, o.getType().name());
                }
                // addBatch accumula le righe e le invia tutte insieme:
                // molto più veloce di una executeUpdate per elemento.
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // il rollback fallito non aggiunge informazioni utili all'errore originale
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // idem
        }
    }
}
