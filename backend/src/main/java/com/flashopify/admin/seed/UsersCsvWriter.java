package com.flashopify.admin.seed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Writes the k6 credentials file; a reader never sees a half-written file. */
final class UsersCsvWriter {

  /** One CSV row; {@code accessToken} is null when tokens are not exported. */
  record Row(long userId, String email, String password, String accessToken) {}

  private UsersCsvWriter() {}

  static void write(Path target, List<Row> rows, boolean withTokens) throws IOException {
    StringBuilder csv = new StringBuilder("userId,email,password");
    if (withTokens) {
      csv.append(",accessToken");
    }
    csv.append('\n');
    for (Row row : rows) {
      csv.append(row.userId())
          .append(',')
          .append(escape(row.email()))
          .append(',')
          .append(escape(row.password()));
      if (withTokens) {
        csv.append(',').append(escape(row.accessToken()));
      }
      csv.append('\n');
    }
    Path absolute = target.toAbsolutePath().normalize();
    Files.createDirectories(absolute.getParent());
    Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
    Files.writeString(temp, csv, StandardCharsets.UTF_8);
    try {
      Files.move(
          temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static String escape(String value) {
    if (value == null) {
      return "";
    }
    if (value.indexOf(',') >= 0
        || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }
}
