package io.bluetape4k.workshop.commerce.voucherpool.compatibility;

import java.sql.DriverManager;

/** Immutable V000 campaign writer used to prove that the previous API can run against an expanded schema. */
public final class PreviousVoucherPoolBinaryMain {
    private PreviousVoucherPoolBinaryMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("expected JDBC URL, user and password");
        }
        try (var connection = DriverManager.getConnection(args[0], args[1], args[2])) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO voucher_pool_campaigns " +
                    "(tenant_id,campaign_id,state,user_identity_key_version,policy_version) " +
                    "VALUES ('compat-v000','00000000-0000-0000-0000-000000000537','DRAFT',3,1) " +
                    "ON CONFLICT (tenant_id,campaign_id) DO UPDATE " +
                    "SET revision=voucher_pool_campaigns.revision+1 " +
                    "RETURNING revision")) {
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("V000 voucher campaign API is unavailable");
                    }
                    System.out.println("previous-revision=" + result.getLong(1));
                }
            }
            connection.commit();
        }
    }
}
