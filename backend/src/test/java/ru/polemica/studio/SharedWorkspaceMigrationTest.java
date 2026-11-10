package ru.polemica.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SharedWorkspaceMigrationTest {
  @Test
  void existingResourcesSurviveRemovalOfOwnership() throws Exception {
    String url =
        "jdbc:h2:mem:migration_"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var sql = connection.createStatement()) {
      sql.executeUpdate(
          "INSERT INTO studio_users(id,username,email,password_hash)"
              + " VALUES('u','legacy','legacy@example.test','unused')");
      sql.executeUpdate(
          "INSERT INTO tournaments(id,owner_id,name,created_at) VALUES('t','u','Existing"
              + " tournament',CURRENT_TIMESTAMP)");
      sql.executeUpdate(
          "INSERT INTO"
              + " games(id,tournament_id,owner_id,table_number,game_number,overlay_token,state_json,updated_at)"
              + " VALUES('g','t','u',1,1,'existing-obs-token','{}',CURRENT_TIMESTAMP)");
      sql.executeUpdate(
          "INSERT INTO game_events(id,game_id,revision,description,state_json,created_at)"
              + " VALUES('e','g',0,'Existing history','{}',CURRENT_TIMESTAMP)");
      sql.executeUpdate(
          "INSERT INTO uploads(id,owner_id,created_at) VALUES('photo','u',CURRENT_TIMESTAMP)");

      Flyway.configure().dataSource(url, "sa", "").load().migrate();

      for (String table : new String[] {"tournaments", "games", "game_events", "uploads"}) {
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
          rows.next();
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
      }
      try (var rows = sql.executeQuery("SELECT overlay_token FROM games WHERE id='g'")) {
        rows.next();
        assertThat(rows.getString(1)).isEqualTo("existing-obs-token");
      }
      try (var rows =
          sql.executeQuery(
              "SELECT COUNT(*) FROM information_schema.columns WHERE column_name='owner_id'")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      sql.executeUpdate(
          "INSERT INTO tournaments(id,name,created_at) VALUES('new','Without"
              + " owner',CURRENT_TIMESTAMP)");
      sql.executeUpdate("INSERT INTO uploads(id,created_at) VALUES('new-photo',CURRENT_TIMESTAMP)");
    }
  }
}
