package it.ristomanager.cloud.restaurant;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RestaurantRepository {

    private final JdbcClient jdbc;

    RestaurantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The id is chosen by the caller so sign-up can run as the new restaurant (row-level security). */
    public void insert(UUID id, String name) {
        jdbc.sql("insert into restaurant (id, name) values (:id, :name)")
                .param("id", id)
                .param("name", name)
                .update();
    }
}
