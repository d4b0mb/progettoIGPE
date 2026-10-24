package it.ristomanager.cloud.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.auth.AuthService;
import it.ristomanager.cloud.support.PostgresTestConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The second lock. These tests talk to the database directly, as the application
 * does, and deliberately leave out the restaurant filter that the repositories
 * add: they prove the database alone keeps restaurants apart.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class RowLevelSecurityIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AuthService auth;

    UUID restaurantA;
    UUID restaurantB;
    UUID ownerB;

    @BeforeEach
    void createTwoRestaurants() {
        var a = auth.signup("Restaurant A", "Owner A", "a-" + UUID.randomUUID() + "@example.com", "correct-horse-battery");
        var b = auth.signup("Restaurant B", "Owner B", "b-" + UUID.randomUUID() + "@example.com", "correct-horse-battery");
        restaurantA = a.user().restaurantId();
        restaurantB = b.user().restaurantId();
        ownerB = b.user().id();
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    @Test
    void withoutAnyRestaurantTheDatabaseShowsNothing() {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(count("restaurant")).isZero();
        assertThat(count("app_user")).isZero();
        assertThat(count("refresh_token")).isZero();
    }

    @Test
    void eachRestaurantSeesOnlyItsOwnRowsEvenWithoutAWhereClause() {
        TenantContext.callAs(restaurantA, () -> {
            assertThat(count("restaurant")).isEqualTo(1);
            assertThat(jdbc.sql("select restaurant_id from app_user").query(UUID.class).list())
                    .isNotEmpty().containsOnly(restaurantA);
            assertThat(jdbc.sql("select restaurant_id from refresh_token").query(UUID.class).list())
                    .isNotEmpty().containsOnly(restaurantA);
            return null;
        });
    }

    @Test
    void anotherRestaurantsRowCannotBeFetchedEvenByExactId() {
        TenantContext.callAs(restaurantA, () -> {
            long hits = jdbc.sql("select count(*) from app_user where id = :id")
                    .param("id", ownerB).query(Long.class).single();
            assertThat(hits).isZero();
            long restaurants = jdbc.sql("select count(*) from restaurant where id = :id")
                    .param("id", restaurantB).query(Long.class).single();
            assertThat(restaurants).isZero();
            return null;
        });
    }

    @Test
    void anotherRestaurantsRowsCannotBeChangedOrDeleted() {
        TenantContext.callAs(restaurantA, () -> {
            int updated = jdbc.sql("update app_user set display_name = 'hacked' where id = :id")
                    .param("id", ownerB).update();
            int renamed = jdbc.sql("update restaurant set name = 'hacked' where id = :id")
                    .param("id", restaurantB).update();
            int deleted = jdbc.sql("delete from refresh_token where user_id = :id")
                    .param("id", ownerB).update();
            assertThat(updated).isZero();
            assertThat(renamed).isZero();
            assertThat(deleted).isZero();
            return null;
        });
        TenantContext.callAs(restaurantB, () -> {
            assertThat(jdbc.sql("select display_name from app_user where id = :id")
                    .param("id", ownerB).query(String.class).single()).isEqualTo("Owner B");
            return null;
        });
    }

    @Test
    void aRowCannotBeWrittenIntoAnotherRestaurant() {
        TenantContext.callAs(restaurantA, () -> {
            assertThatThrownBy(() -> jdbc.sql("""
                    insert into app_user (restaurant_id, email, password_hash, display_name, role)
                    values (:rid, :email, 'x', 'Intruder', 'OWNER')
                    """)
                    .param("rid", restaurantB)
                    .param("email", "intruder-" + UUID.randomUUID() + "@example.com")
                    .update())
                    .rootCause().hasMessageContaining("row-level security");
            return null;
        });
        // And no restaurant at all cannot write either.
        assertThatThrownBy(() -> jdbc.sql("insert into restaurant (name) values ('ghost')").update())
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void connectionsFromThePoolNeverCarryOverAnotherRestaurant() throws Exception {
        int threads = 12;
        int rounds = 60;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                UUID mine = t % 2 == 0 ? restaurantA : restaurantB;
                UUID other = t % 2 == 0 ? restaurantB : restaurantA;
                results.add(pool.submit(() -> {
                    for (int i = 0; i < rounds; i++) {
                        boolean ok = TenantContext.callAs(mine, () ->
                                jdbc.sql("select count(*) from app_user where restaurant_id = :o")
                                        .param("o", other).query(Long.class).single() == 0
                                        && jdbc.sql("select count(*) from app_user where restaurant_id = :m")
                                        .param("m", mine).query(Long.class).single() > 0);
                        if (!ok) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : results) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theApplicationRoleCannotBypassSecurity() {
        var role = jdbc.sql("select rolsuper, rolbypassrls from pg_roles where rolname = current_user")
                .query((rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}).single();
        assertThat(role[0]).as("superuser").isFalse();
        assertThat(role[1]).as("bypassrls").isFalse();

        String owner = jdbc.sql("select tableowner from pg_tables where schemaname = 'public' and tablename = 'app_user'")
                .query(String.class).single();
        String me = jdbc.sql("select current_user").query(String.class).single();
        assertThat(owner).as("the app must not own the tables").isNotEqualTo(me);
    }

    /**
     * Guardrail for the future: any table that carries restaurant_id (and the
     * restaurant table itself) must have row-level security switched on and a
     * policy. A new migration that forgets this fails here.
     */
    @Test
    void everyTenantTableHasRowLevelSecurityAndAPolicy() {
        List<String> tenantTables = jdbc.sql("""
                select c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind = 'r'
                   and (c.relname = 'restaurant'
                        or exists (select 1 from information_schema.columns col
                                    where col.table_schema = 'public'
                                      and col.table_name = c.relname
                                      and col.column_name = 'restaurant_id'))
                """).query(String.class).list();
        assertThat(tenantTables).contains("restaurant", "app_user", "refresh_token");

        for (String table : tenantTables) {
            boolean enabled = jdbc.sql("select relrowsecurity from pg_class where oid = ('public.' || :t)::regclass")
                    .param("t", table).query(Boolean.class).single();
            long policies = jdbc.sql("select count(*) from pg_policies where schemaname = 'public' and tablename = :t")
                    .param("t", table).query(Long.class).single();
            assertThat(enabled).as("RLS enabled on " + table).isTrue();
            assertThat(policies).as("policies on " + table).isGreaterThan(0);
        }
    }
}
