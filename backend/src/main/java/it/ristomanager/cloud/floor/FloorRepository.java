package it.ristomanager.cloud.floor;

import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.floor.FloorTypes.Obstacle;
import it.ristomanager.cloud.floor.FloorTypes.ObstacleType;
import it.ristomanager.cloud.floor.FloorTypes.Room;
import it.ristomanager.cloud.floor.FloorTypes.ServiceStatus;
import it.ristomanager.cloud.floor.FloorTypes.TableShape;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every query names the restaurant explicitly (lock 1); row-level security in the
 * database enforces it again (lock 2).
 */
@Repository
public class FloorRepository {

    private static final String ROOM_COLS = "id, name, width_m, height_m, grid_step_m";
    private static final String TABLE_COLS = "id, room_id, number, name, x_m, y_m, width_m, height_m, "
            + "rotation_deg, seats, shape, accessible, service_status";
    private static final String OBSTACLE_COLS = "id, room_id, type, name, x_m, y_m, width_m, height_m, rotation_deg";

    private final JdbcClient jdbc;

    FloorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- rooms

    public List<Room> listRooms(UUID rid) {
        return jdbc.sql("select " + ROOM_COLS + " from room where restaurant_id = :rid order by name")
                .param("rid", rid).query(FloorRepository::room).list();
    }

    public Optional<Room> findRoom(UUID rid, UUID id) {
        return jdbc.sql("select " + ROOM_COLS + " from room where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).query(FloorRepository::room).optional();
    }

    public Room insertRoom(UUID rid, String name, double w, double h, double grid) {
        return jdbc.sql("insert into room (restaurant_id, name, width_m, height_m, grid_step_m) "
                        + "values (:rid, :name, :w, :h, :grid) returning " + ROOM_COLS)
                .param("rid", rid).param("name", name).param("w", w).param("h", h).param("grid", grid)
                .query(FloorRepository::room).single();
    }

    public Optional<Room> updateRoom(UUID rid, UUID id, String name, double w, double h, double grid) {
        return jdbc.sql("update room set name = :name, width_m = :w, height_m = :h, grid_step_m = :grid "
                        + "where restaurant_id = :rid and id = :id returning " + ROOM_COLS)
                .param("rid", rid).param("id", id).param("name", name).param("w", w).param("h", h).param("grid", grid)
                .query(FloorRepository::room).optional();
    }

    public boolean roomHasElements(UUID rid, UUID id) {
        return jdbc.sql("select exists(select 1 from dining_table where restaurant_id = :rid and room_id = :id) "
                        + "or exists(select 1 from obstacle where restaurant_id = :rid and room_id = :id)")
                .param("rid", rid).param("id", id).query(Boolean.class).single();
    }

    public boolean deleteRoom(UUID rid, UUID id) {
        return jdbc.sql("delete from room where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).update() == 1;
    }

    // ---- tables

    public List<DiningTable> listTables(UUID rid, UUID roomId) {
        return jdbc.sql("select " + TABLE_COLS + " from dining_table where restaurant_id = :rid and room_id = :room order by number")
                .param("rid", rid).param("room", roomId).query(FloorRepository::table).list();
    }

    public List<DiningTable> listAllTables(UUID rid) {
        return jdbc.sql("select " + TABLE_COLS + " from dining_table where restaurant_id = :rid order by number")
                .param("rid", rid).query(FloorRepository::table).list();
    }

    public Optional<DiningTable> findTable(UUID rid, UUID id) {
        return jdbc.sql("select " + TABLE_COLS + " from dining_table where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).query(FloorRepository::table).optional();
    }

    public int nextTableNumber(UUID rid) {
        return jdbc.sql("select coalesce(max(number), 0) + 1 from dining_table where restaurant_id = :rid")
                .param("rid", rid).query(Integer.class).single();
    }

    public DiningTable insertTable(UUID rid, UUID roomId, DiningTable t) {
        return jdbc.sql("insert into dining_table (restaurant_id, room_id, number, name, x_m, y_m, width_m, height_m, "
                        + "rotation_deg, seats, shape, accessible, service_status) "
                        + "values (:rid, :room, :number, :name, :x, :y, :w, :h, :rot, :seats, :shape, :acc, :status) "
                        + "returning " + TABLE_COLS)
                .param("rid", rid).param("room", roomId).param("number", t.number()).param("name", t.name())
                .param("x", t.xMeters()).param("y", t.yMeters()).param("w", t.widthMeters()).param("h", t.heightMeters())
                .param("rot", t.rotationDegrees()).param("seats", t.seats()).param("shape", t.shape().name())
                .param("acc", t.accessible()).param("status", t.serviceStatus().name())
                .query(FloorRepository::table).single();
    }

    public Optional<DiningTable> updateTable(UUID rid, UUID id, DiningTable t) {
        return jdbc.sql("update dining_table set number = :number, name = :name, x_m = :x, y_m = :y, width_m = :w, "
                        + "height_m = :h, rotation_deg = :rot, seats = :seats, shape = :shape, accessible = :acc "
                        + "where restaurant_id = :rid and id = :id returning " + TABLE_COLS)
                .param("rid", rid).param("id", id).param("number", t.number()).param("name", t.name())
                .param("x", t.xMeters()).param("y", t.yMeters()).param("w", t.widthMeters()).param("h", t.heightMeters())
                .param("rot", t.rotationDegrees()).param("seats", t.seats()).param("shape", t.shape().name())
                .param("acc", t.accessible())
                .query(FloorRepository::table).optional();
    }

    public Optional<DiningTable> setServiceStatus(UUID rid, UUID id, ServiceStatus status) {
        return jdbc.sql("update dining_table set service_status = :status where restaurant_id = :rid and id = :id "
                        + "returning " + TABLE_COLS)
                .param("rid", rid).param("id", id).param("status", status.name())
                .query(FloorRepository::table).optional();
    }

    public boolean deleteTable(UUID rid, UUID id) {
        return jdbc.sql("delete from dining_table where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).update() == 1;
    }

    // ---- obstacles

    public List<Obstacle> listObstacles(UUID rid, UUID roomId) {
        return jdbc.sql("select " + OBSTACLE_COLS + " from obstacle where restaurant_id = :rid and room_id = :room order by type, name")
                .param("rid", rid).param("room", roomId).query(FloorRepository::obstacle).list();
    }

    public Obstacle insertObstacle(UUID rid, UUID roomId, Obstacle o) {
        return jdbc.sql("insert into obstacle (restaurant_id, room_id, type, name, x_m, y_m, width_m, height_m, rotation_deg) "
                        + "values (:rid, :room, :type, :name, :x, :y, :w, :h, :rot) returning " + OBSTACLE_COLS)
                .param("rid", rid).param("room", roomId).param("type", o.type().name()).param("name", o.name())
                .param("x", o.xMeters()).param("y", o.yMeters()).param("w", o.widthMeters()).param("h", o.heightMeters())
                .param("rot", o.rotationDegrees())
                .query(FloorRepository::obstacle).single();
    }

    public Optional<Obstacle> updateObstacle(UUID rid, UUID id, Obstacle o) {
        return jdbc.sql("update obstacle set type = :type, name = :name, x_m = :x, y_m = :y, width_m = :w, "
                        + "height_m = :h, rotation_deg = :rot where restaurant_id = :rid and id = :id returning " + OBSTACLE_COLS)
                .param("rid", rid).param("id", id).param("type", o.type().name()).param("name", o.name())
                .param("x", o.xMeters()).param("y", o.yMeters()).param("w", o.widthMeters()).param("h", o.heightMeters())
                .param("rot", o.rotationDegrees())
                .query(FloorRepository::obstacle).optional();
    }

    public boolean deleteObstacle(UUID rid, UUID id) {
        return jdbc.sql("delete from obstacle where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).update() == 1;
    }

    // ---- row mapping

    private static Room room(ResultSet rs, int n) throws SQLException {
        return new Room(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getDouble("width_m"), rs.getDouble("height_m"), rs.getDouble("grid_step_m"));
    }

    private static DiningTable table(ResultSet rs, int n) throws SQLException {
        return new DiningTable(rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getInt("number"), rs.getString("name"),
                rs.getDouble("x_m"), rs.getDouble("y_m"), rs.getDouble("width_m"), rs.getDouble("height_m"),
                rs.getDouble("rotation_deg"), rs.getInt("seats"), TableShape.valueOf(rs.getString("shape")),
                rs.getBoolean("accessible"), ServiceStatus.valueOf(rs.getString("service_status")));
    }

    private static Obstacle obstacle(ResultSet rs, int n) throws SQLException {
        return new Obstacle(rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                ObstacleType.valueOf(rs.getString("type")), rs.getString("name"),
                rs.getDouble("x_m"), rs.getDouble("y_m"), rs.getDouble("width_m"), rs.getDouble("height_m"),
                rs.getDouble("rotation_deg"));
    }
}
