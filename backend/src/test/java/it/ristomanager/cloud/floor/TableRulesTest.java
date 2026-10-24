package it.ristomanager.cloud.floor;

import static org.assertj.core.api.Assertions.assertThat;

import it.ristomanager.cloud.floor.FloorTypes.TableShape;
import org.junit.jupiter.api.Test;

class TableRulesTest {

    @Test
    void roundTableSeatsFollowTheCircumference() {
        // 1.2 m wide round table: circumference about 3.77 m, 0.6 m per guest.
        assertThat(TableRules.suggestedSeats(TableShape.ROUND, 1.2, 1.2)).isEqualTo(6);
    }

    @Test
    void rectangularTableSeatsGoAlongBothEdges() {
        // 1.2 x 0.8: two seats per 0.6 m of width, two per 0.6 m of height.
        assertThat(TableRules.suggestedSeats(TableShape.RECTANGLE, 1.2, 0.8)).isEqualTo(6);
    }

    @Test
    void aTableAlwaysSuggestsAtLeastTwoSeats() {
        assertThat(TableRules.suggestedSeats(TableShape.ROUND, 0.4, 0.4)).isEqualTo(2);
        assertThat(TableRules.suggestedSeats(TableShape.RECTANGLE, 0.3, 0.3)).isEqualTo(2);
    }

    @Test
    void roundAndSquareTablesAreAsTallAsTheyAreWide() {
        assertThat(TableRules.normalizedHeight(TableShape.ROUND, 1.0, 2.0)).isEqualTo(1.0);
        assertThat(TableRules.normalizedHeight(TableShape.SQUARE, 1.0, 2.0)).isEqualTo(1.0);
        assertThat(TableRules.normalizedHeight(TableShape.RECTANGLE, 1.0, 2.0)).isEqualTo(2.0);
    }
}
