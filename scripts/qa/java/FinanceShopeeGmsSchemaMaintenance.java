import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Fixed additive V158 maintenance; no application context or automatic schema repair. */
public class FinanceShopeeGmsSchemaMaintenance {
    static final String SQL = "V158__finance_shopee_gms_reference.sql";
    static final String SHA = "0aac50cd3cd29824adfa83c6171668e8086d81456ab8e811f68cf29f589e92e6";
    static final String PREVIOUS_SQL = "V157__finance_projection_baseline_revisions.sql";

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
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='finance_shopee_gms_sync' AND column_name='campaign_revision' AND column_type='char(64)' AND is_nullable='NO') OR (table_name='finance_shopee_gms_daily' AND column_name='amount' AND column_type='decimal(24,6)' AND is_nullable='NO'))")) {
                if(!rows.next()||rows.getInt(1)!=2)throw new SQLException("GMS reference schema is invalid");
            }
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('fin_qp_shopee_gms_ai','fin_qp_shopee_gms_au','fin_qp_shopee_gms_ad','fin_qp_shopee_gms_scope_au','fin_qp_shopee_gms_grant_bd','fin_shopee_gms_grant_au')")) {
                if(!rows.next()||rows.getInt(1)!=6)throw new SQLException("GMS invalidation triggers are incomplete");
            }
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('fk_shopee_gms_sync_grant','fk_shopee_gms_daily_grant') AND delete_rule='CASCADE'")) {
                if(!rows.next()||rows.getInt(1)!=2)throw new SQLException("GMS grant ownership is incomplete");
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
            if (before==null || !before.getVersion().getVersion().equals("157")) throw new IllegalStateException();
            var applied157=Arrays.stream(original.info().applied()).filter(info -> info.getVersion()!=null
                && info.getVersion().getVersion().equals("157") && info.getType().name().equals("SQL")
                && info.getScript().equals(PREVIOUS_SQL) && info.getState().name().equals("SUCCESS")).toList();
            if (applied157.size()!=1 || applied157.getFirst().getChecksum()==null) throw new IllegalStateException();
            String previousChecksum=Integer.toString(applied157.getFirst().getChecksum());
            var upgrade=Flyway.configure().dataSource(source).locations("classpath:db/migration","filesystem:"+directory)
                .target("158").createSchemas(false).load();
            var pending=upgrade.info().pending();
            if (pending.length!=1 || !pending[0].getVersion().getVersion().equals("158") || !pending[0].getScript().equals(SQL))
                throw new IllegalStateException();
            var prefix=history(source);
            if (prefix.isEmpty() || prefix.stream().anyMatch(row -> {
                String[] fields=row.split("\\t", -1);
                return fields.length!=6 || !fields[5].equals("1") || (!fields[1].isEmpty()
                    && org.flywaydb.core.api.MigrationVersion.fromVersion(fields[1]).compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("157"))>0);
            })) throw new IllegalStateException();
            long previousRows=prefix.stream().filter(row -> {
                String[] fields=row.split("\\t", -1);
                return fields.length==6 && fields[1].equals("157") && fields[2].equals("SQL")
                    && fields[3].equals(PREVIOUS_SQL) && fields[4].equals(previousChecksum) && fields[5].equals("1");
            }).count();
            if (previousRows!=1) throw new IllegalStateException();
            int executed=0;
            if (args[0].equals("migrate")) {
                source.authorized(); executed=upgrade.migrate().migrationsExecuted;
                var after=history(source);
                if (executed!=1 || after.size()!=prefix.size()+1 || !after.subList(0,prefix.size()).equals(prefix)
                    || !after.get(after.size()-1).split("\\t")[1].equals("158")
                    || !after.get(after.size()-1).split("\\t")[3].equals(SQL)
                    || !after.get(after.size()-1).endsWith("\t1")
                    || !upgrade.validateWithResult().validationSuccessful || !upgrade.info().current().getVersion().getVersion().equals("158"))
                    throw new IllegalStateException();
            }
            if (executed==1) verifyColumns(source);
            if (!original.validateWithResult().validationSuccessful) throw new IllegalStateException();
            System.out.println(json.writeValueAsString(Map.of("success",true,"mode",args[0],"beforeVersion","157",
                "executed",executed,"oldRuntimeValidationPassed",true,"historyPrefixPreserved",true)));
        } catch (Exception failure) {
            System.out.println(json.writeValueAsString(Map.of("success",false,"failureType",failure.getClass().getSimpleName())));
            System.exit(1);
        }
    }
}
