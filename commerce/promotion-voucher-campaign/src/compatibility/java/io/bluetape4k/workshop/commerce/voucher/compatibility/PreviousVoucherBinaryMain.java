package io.bluetape4k.workshop.commerce.voucher.compatibility;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Previous release fixture intentionally compiled against only the JDK JDBC API. */
public final class PreviousVoucherBinaryMain {
    private PreviousVoucherBinaryMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("expected mode, url, username, password, tenant, campaign id");
        }
        String mode = args[0];
        try (Connection connection = DriverManager.getConnection(args[1], args[2], args[3])) {
            if ("read-write".equals(mode)) {
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT count(*) FROM voucher_campaigns")) {
                    if (!result.next() || result.getLong(1) < 2L) {
                        throw new IllegalStateException("current binary row is not visible");
                    }
                }
            } else if (!"write".equals(mode)) {
                throw new IllegalArgumentException("unsupported mode");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO voucher_campaigns (tenant_id, campaign_id, state, starts_at, ends_at, " +
                    "capacity, allocated_count, per_user_limit, redemption_ttl_seconds, policy_version, revision) " +
                    "VALUES (?, ?::uuid, 'ACTIVE', CURRENT_TIMESTAMP - INTERVAL '1 minute', " +
                    "CURRENT_TIMESTAMP + INTERVAL '1 hour', 10, 0, 1, 3600, 1, 0) " +
                    "ON CONFLICT (tenant_id, campaign_id) DO NOTHING")) {
                statement.setString(1, args[4]);
                statement.setString(2, args[5]);
                statement.executeUpdate();
            }
        }
        System.out.println("PREVIOUS_OK mode=" + mode);
    }
}
