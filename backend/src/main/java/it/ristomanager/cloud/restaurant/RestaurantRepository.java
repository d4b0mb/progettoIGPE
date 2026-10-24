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

    public UUID insert(String name) {
        return jdbc.sql("insert into restaurant (name) values (:name) returning id")
                .param("name", name)
                .query(UUID.class)
                .single();
    }
}
