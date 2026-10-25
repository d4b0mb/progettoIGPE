package it.ristomanager.cloud.menu;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.menu.MenuTypes.Course;
import it.ristomanager.cloud.menu.MenuTypes.MenuItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MenuRepository {

    private static final String COLS = "id, name, description, course, section, price_cents, allergens, available";

    private final JdbcClient jdbc;

    MenuRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<MenuItem> list(UUID rid) {
        return jdbc.sql("select " + COLS + " from menu_item where restaurant_id = :rid order by created_at, name")
                .param("rid", rid).query(MenuRepository::map).list();
    }

    public Optional<MenuItem> find(UUID rid, UUID id) {
        return jdbc.sql("select " + COLS + " from menu_item where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).query(MenuRepository::map).optional();
    }

    public List<MenuItem> findAll(UUID rid, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLS + " from menu_item where restaurant_id = :rid and id = any(:ids)")
                .param("rid", rid).param("ids", ids.toArray(UUID[]::new)).query(MenuRepository::map).list();
    }

    public MenuItem insert(UUID rid, MenuItem m) {
        return jdbc.sql("insert into menu_item (restaurant_id, name, description, course, section, price_cents, allergens, available) "
                        + "values (:rid, :name, :desc, :course, :section, :price, cast(:allergens as text[]), :available) returning " + COLS)
                .param("rid", rid).param("name", m.name()).param("desc", m.description())
                .param("course", m.course().name()).param("section", m.section()).param("price", m.priceCents())
                .param("allergens", names(m.allergens())).param("available", m.available())
                .query(MenuRepository::map).single();
    }

    public Optional<MenuItem> update(UUID rid, UUID id, MenuItem m) {
        return jdbc.sql("update menu_item set name = :name, description = :desc, course = :course, section = :section, price_cents = :price, "
                        + "allergens = cast(:allergens as text[]), available = :available "
                        + "where restaurant_id = :rid and id = :id returning " + COLS)
                .param("rid", rid).param("id", id).param("name", m.name()).param("desc", m.description())
                .param("course", m.course().name()).param("section", m.section()).param("price", m.priceCents())
                .param("allergens", names(m.allergens())).param("available", m.available())
                .query(MenuRepository::map).optional();
    }

    public boolean delete(UUID rid, UUID id) {
        return jdbc.sql("delete from menu_item where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).update() == 1;
    }

    private static String[] names(List<Allergen> allergens) {
        return allergens == null ? new String[0]
                : allergens.stream().map(Allergen::name).distinct().sorted().toArray(String[]::new);
    }

    private static MenuItem map(ResultSet rs, int n) throws SQLException {
        String[] allergens = (String[]) rs.getArray("allergens").getArray();
        // getObject returns null for a missing price. (wasNull() only describes the column read just before it.)
        Integer price = rs.getObject("price_cents", Integer.class);
        return new MenuItem(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                Course.valueOf(rs.getString("course")), rs.getString("section"), price,
                Arrays.stream(allergens).map(Allergen::valueOf).toList(), rs.getBoolean("available"));
    }
}
