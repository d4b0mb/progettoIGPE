package it.ristomanager.cloud.menu;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.menu.MenuTypes.Course;
import it.ristomanager.cloud.menu.MenuTypes.Modifier;
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
public class ModifierRepository {

    private static final String COLS = "id, name, course, section, price_cents, allergens, available";

    private final JdbcClient jdbc;

    ModifierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** In the order they were entered, so the menu shows them as the restaurant listed them. */
    public List<Modifier> list(UUID rid) {
        return jdbc.sql("select " + COLS + " from menu_modifier where restaurant_id = :rid order by created_at, name")
                .param("rid", rid).query(ModifierRepository::map).list();
    }

    public List<Modifier> findAll(UUID rid, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLS + " from menu_modifier where restaurant_id = :rid and id = any(:ids) order by created_at, name")
                .param("rid", rid).param("ids", ids.toArray(UUID[]::new)).query(ModifierRepository::map).list();
    }

    public Modifier insert(UUID rid, Modifier m) {
        return jdbc.sql("insert into menu_modifier (restaurant_id, name, course, section, price_cents, allergens, available) "
                        + "values (:rid, :name, :course, :section, :price, cast(:allergens as text[]), :available) returning " + COLS)
                .param("rid", rid).param("name", m.name()).param("course", m.course().name()).param("section", m.section())
                .param("price", m.priceCents()).param("allergens", names(m.allergens())).param("available", m.available())
                .query(ModifierRepository::map).single();
    }

    public Optional<Modifier> update(UUID rid, UUID id, Modifier m) {
        return jdbc.sql("update menu_modifier set name = :name, course = :course, section = :section, price_cents = :price, "
                        + "allergens = cast(:allergens as text[]), available = :available "
                        + "where restaurant_id = :rid and id = :id returning " + COLS)
                .param("rid", rid).param("id", id).param("name", m.name()).param("course", m.course().name())
                .param("section", m.section()).param("price", m.priceCents()).param("allergens", names(m.allergens()))
                .param("available", m.available())
                .query(ModifierRepository::map).optional();
    }

    public boolean delete(UUID rid, UUID id) {
        return jdbc.sql("delete from menu_modifier where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).update() == 1;
    }

    private static String[] names(List<Allergen> allergens) {
        return allergens == null ? new String[0]
                : allergens.stream().map(Allergen::name).distinct().sorted().toArray(String[]::new);
    }

    private static Modifier map(ResultSet rs, int n) throws SQLException {
        String[] allergens = (String[]) rs.getArray("allergens").getArray();
        return new Modifier(rs.getObject("id", UUID.class), rs.getString("name"), Course.valueOf(rs.getString("course")),
                rs.getString("section"), rs.getInt("price_cents"), Arrays.stream(allergens).map(Allergen::valueOf).toList(),
                rs.getBoolean("available"));
    }
}
