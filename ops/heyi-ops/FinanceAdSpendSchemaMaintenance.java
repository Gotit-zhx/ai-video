import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Dedicated V148-to-V150 addition, launched in the old application without Spring/workers. */
public class FinanceAdSpendSchemaMaintenance {
    static final String SQL="V150__finance_ad_spend_workspace.sql";
    static final String SHA="a8dfff7b4f3b722e137dd7bc39aebc0a8b5df9201b75a29199dc55211e7c1e13";
    public static void main(String[] args){
        var json=new ObjectMapper();
        try{System.out.println(json.writeValueAsString(execute(args,json.readValue(System.in.readNBytes(65536),Map.class))));}
        catch(Exception failure){System.out.println(json.writeValueAsString(Map.of("success",false,"failureType",failure.getClass().getSimpleName())));System.exit(1);}
    }
    public static Map<String,Object> execute(String[] args,Map<String,Object> values)throws Exception{
        if(args.length!=2||!Set.of("validate","migrate").contains(args[0]))throw new IllegalArgumentException();
        String url=(String)values.get("url"),user=(String)values.get("user"),password=(String)values.get("password"),base=(String)values.getOrDefault("baseLocation","classpath:db/migration");
        if(!base.equals("classpath:db/migration")&&!url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"))throw new IllegalArgumentException();
        Path directory=Path.of(args[1]),sql=directory.resolve(SQL);
        try(var files=Files.list(directory)){
            if(files.count()!=1||Files.isSymbolicLink(sql)||!Files.isRegularFile(sql)||!SHA.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sql)))))throw new IllegalArgumentException("Unreviewed migration input");
        }
        var original=Flyway.configure().dataSource(url,user,password).locations(base).createSchemas(false).load();
        if(!original.validateWithResult().validationSuccessful)throw new IllegalStateException("Existing migrations failed validation");
        var current=original.info().current();
        if(current==null||!Set.of("148","150").contains(current.getVersion().getVersion()))throw new IllegalStateException("Expected schema V148 or completed V150");
        if(args[0].equals("migrate")){
            var upgrade=Flyway.configure().dataSource(url,user,password).locations(base,"filesystem:"+directory).target("150").createSchemas(false).load();
            var result=upgrade.migrate();
            if(result.migrationsExecuted>1||!upgrade.validateWithResult().validationSuccessful||!"150".equals(upgrade.info().current().getVersion().getVersion()))throw new IllegalStateException("Unexpected migration result");
        }
        if(!original.validateWithResult().validationSuccessful)throw new IllegalStateException("Older runtime cannot validate upgraded schema");
        return Map.of("success",true,"mode",args[0],"oldRuntimeValidationPassed",true);
    }
}
