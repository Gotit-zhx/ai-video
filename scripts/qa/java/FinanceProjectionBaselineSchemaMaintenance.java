import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Fixed additive V157 maintenance; no application context or automatic schema repair. */
public class FinanceProjectionBaselineSchemaMaintenance {
    static final String SQL = "V157__finance_projection_baseline_revisions.sql";
    static final String SHA = "69eb1901073fd35c9baf4aefa4c1ad5b66f7a8be76f212ba4685b025ea49939c";
    static final String PREVIOUS_SQL = "V156__finance_ad_campaign_metadata.sql";

    static final class OwnedDataSource implements javax.sql.DataSource {
        final String url, user, password, owner;
        final Path connections, token;
        OwnedDataSource(String url, String user, String password, String owner, Path connections, Path token) {
            this.url=url; this.user=user; this.password=password; this.owner=owner;
            this.connections=connections; this.token=token;
        }
        void authorized() throws SQLException {
            try {
                if (Files.isSymbolicLink(token) || !Files.readString(token).trim().equals("\""+owner+"\""))
                    throw new SQLException("Helper launch revoked");
            } catch (java.io.IOException failure) { throw new SQLException("Helper launch unavailable"); }
        }
        public Connection getConnection() throws SQLException { return getConnection(user, password); }
        public Connection getConnection(String user, String password) throws SQLException {
            authorized();
            Connection connection=DriverManager.getConnection(url, user, password);
            try (var statement=connection.createStatement(); var rows=statement.executeQuery("SELECT CONNECTION_ID()")) {
                if (!rows.next()) throw new SQLException("Missing helper connection identity");
                Files.writeString(connections, Long.toString(rows.getLong(1))+"\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
                authorized();
                return connection;
            } catch (Exception failure) {
                connection.close(); throw new SQLException("Owned connection registration rejected");
            }
        }
        public java.io.PrintWriter getLogWriter() { return null; }
        public void setLogWriter(java.io.PrintWriter writer) {}
        public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }
        public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        public <T>T unwrap(Class<T> type) throws SQLException { throw new SQLException(); }
        public boolean isWrapperFor(Class<?> type) { return false; }
    }

    static List<String> history(OwnedDataSource source) throws SQLException {
        var result=new ArrayList<String>();
        try (var connection=source.getConnection(); var statement=connection.createStatement();
             var rows=statement.executeQuery("SELECT installed_rank,COALESCE(version,''),type,script,COALESCE(checksum,''),success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                StringJoiner row=new StringJoiner("\t");
                for (int index=1; index<=6; index++) row.add(rows.getString(index));
                result.add(row.toString());
            }
        }
        return result;
    }

    static void verifyColumns(OwnedDataSource source) throws SQLException {
        try (var connection=source.getConnection(); var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows=statement.executeQuery("SELECT table_name,column_name,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='finance_query_partitions' AND column_name='full_rebuild_revision') OR (table_name='finance_query_dirty_days' AND column_name='requested_revision')) ORDER BY table_name")) {
                if (!rows.next() || !rows.getString(1).equals("finance_query_dirty_days") || !rows.getString(2).equals("requested_revision")
                    || !rows.getString(3).equals("bigint") || !rows.getString(4).equals("NO") || !rows.getString(5).equals("0"))
                    throw new SQLException("Dirty-day revision schema is invalid");
                if (!rows.next() || !rows.getString(1).equals("finance_query_partitions") || !rows.getString(2).equals("full_rebuild_revision")
                    || !rows.getString(3).equals("bigint") || !rows.getString(4).equals("NO") || !rows.getString(5).equals("1") || rows.next())
                    throw new SQLException("Full-rebuild revision schema is invalid");
            }
            // Only the two small derived tables are queried; never scan a raw ledger.
            try (var rows=statement.executeQuery("SELECT 1 FROM finance_query_partitions WHERE full_rebuild_revision<>CASE WHEN full_rebuild THEN requested_revision ELSE built_revision END LIMIT 1")) {
                if (rows.next()) throw new SQLException("Full-rebuild revision initialization differs");
            }
            try (var rows=statement.executeQuery("SELECT 1 FROM finance_query_dirty_days d JOIN finance_query_partitions p ON p.organization_id=d.organization_id AND p.platform_filter=d.platform_filter AND p.partition_month=d.partition_month WHERE d.requested_revision<>p.requested_revision LIMIT 1")) {
                if (rows.next()) throw new SQLException("Dirty-day revision initialization differs");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();
        try {
            if (args.length!=3 || !Set.of("validate","migrate").contains(args[0])) throw new IllegalArgumentException();
            var values=json.readValue(System.in.readNBytes(65536), Map.class);
            var directory=Path.of(args[1]); var sql=directory.resolve(SQL); var token=Path.of(args[2]);
            if (Files.isSymbolicLink(directory) || !directory.toRealPath().equals(directory.toAbsolutePath())) throw new IllegalArgumentException();
            try (var files=Files.list(directory)) {
                if (files.count()!=1 || Files.isSymbolicLink(sql) || !Files.isRegularFile(sql)
                    || !SHA.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sql)))))
                    throw new IllegalArgumentException();
            }
            String url=(String)values.get("url"), user=(String)values.get("user"), password=(String)values.get("password"), owner=(String)values.get("owner");
            if (owner==null || !owner.matches("[a-f0-9]{32}") || url==null || user==null || password==null
                || !url.startsWith("jdbc:mysql:") || url.toLowerCase(Locale.ROOT).contains("connectionattributes")
                || !owner.equals(System.getProperty("heyi.financeProjectionSchemaOwner"))
                || !token.equals(directory.getParent().resolve("launch.json"))) throw new IllegalArgumentException();
            url+=(url.contains("?")?"&":"?")+"connectionAttributes=heyi_finance_projection_owner:"+owner;
            var source=new OwnedDataSource(url,user,password,owner,directory.getParent().resolve(owner+".connections"),token);
            source.authorized();
            var original=Flyway.configure().dataSource(source).locations("classpath:db/migration").createSchemas(false).load();
            if (!original.validateWithResult().validationSuccessful || original.info().pending().length!=0) throw new IllegalStateException();
            var before=original.info().current();
            if (before==null || !before.getVersion().getVersion().equals("156")) throw new IllegalStateException();
            var applied156=Arrays.stream(original.info().applied()).filter(info -> info.getVersion()!=null
                && info.getVersion().getVersion().equals("156") && info.getType().name().equals("SQL")
                && info.getScript().equals(PREVIOUS_SQL) && info.getState().name().equals("SUCCESS")).toList();
            if (applied156.size()!=1 || applied156.getFirst().getChecksum()==null) throw new IllegalStateException();
            String previousChecksum=Integer.toString(applied156.getFirst().getChecksum());
            var upgrade=Flyway.configure().dataSource(source).locations("classpath:db/migration","filesystem:"+directory)
                .target("157").createSchemas(false).load();
            var pending=upgrade.info().pending();
            if (pending.length!=1 || !pending[0].getVersion().getVersion().equals("157") || !pending[0].getScript().equals(SQL))
                throw new IllegalStateException();
            var prefix=history(source);
            if (prefix.isEmpty() || prefix.stream().anyMatch(row -> {
                String[] fields=row.split("\\t", -1);
                return fields.length!=6 || !fields[5].equals("1") || (!fields[1].isEmpty()
                    && org.flywaydb.core.api.MigrationVersion.fromVersion(fields[1]).compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("156"))>0);
            })) throw new IllegalStateException();
            long previousRows=prefix.stream().filter(row -> {
                String[] fields=row.split("\\t", -1);
                return fields.length==6 && fields[1].equals("156") && fields[2].equals("SQL")
                    && fields[3].equals(PREVIOUS_SQL) && fields[4].equals(previousChecksum) && fields[5].equals("1");
            }).count();
            if (previousRows!=1) throw new IllegalStateException();
            int executed=0;
            if (args[0].equals("migrate")) {
                source.authorized(); executed=upgrade.migrate().migrationsExecuted;
                var after=history(source);
                if (executed!=1 || after.size()!=prefix.size()+1 || !after.subList(0,prefix.size()).equals(prefix)
                    || !after.get(after.size()-1).split("\\t")[1].equals("157")
                    || !after.get(after.size()-1).split("\\t")[3].equals(SQL)
                    || !after.get(after.size()-1).endsWith("\t1")
                    || !upgrade.validateWithResult().validationSuccessful || !upgrade.info().current().getVersion().getVersion().equals("157"))
                    throw new IllegalStateException();
            }
            if (executed==1) verifyColumns(source);
            if (!original.validateWithResult().validationSuccessful) throw new IllegalStateException();
            System.out.println(json.writeValueAsString(Map.of("success",true,"mode",args[0],"beforeVersion","156",
                "executed",executed,"oldRuntimeValidationPassed",true,"historyPrefixPreserved",true)));
        } catch (Exception failure) {
            System.out.println(json.writeValueAsString(Map.of("success",false,"failureType",failure.getClass().getSimpleName())));
            System.exit(1);
        }
    }
}
