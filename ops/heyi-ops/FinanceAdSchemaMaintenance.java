import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

/** Run through PropertiesLauncher in the existing application: no Spring or worker startup. */
public class FinanceAdSchemaMaintenance {
    public static void main(String[] args) {
        var json=new ObjectMapper();
        try {
            System.out.println(json.writeValueAsString(execute(args,json.readValue(System.in.readNBytes(65536),Map.class))));
        } catch(Exception failure) {
            System.out.println(json.writeValueAsString(Map.of("success",false,"failureType",failure.getClass().getSimpleName())));
            System.exit(1);
        }
    }
    public static Map<String,Object> execute(String[] args,Map<String,Object> values) {
            if(args.length!=2||!java.util.Set.of("validate","migrate").contains(args[0]))throw new IllegalArgumentException();
            String url=(String)values.get("url"),user=(String)values.get("user"),password=(String)values.get("password");
            String base=(String)values.getOrDefault("baseLocation","classpath:db/migration");
            if(!base.equals("classpath:db/migration")&&!url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"))throw new IllegalArgumentException();
            var original=Flyway.configure().dataSource(url,user,password).locations(base).createSchemas(false).load();
            if(!original.validateWithResult().validationSuccessful)throw new IllegalStateException("Existing migrations failed validation");
            if(args[0].equals("migrate")) {
                var current=original.info().current();
                if(current==null||!java.util.Set.of("147","148").contains(current.getVersion().getVersion()))throw new IllegalStateException("Unexpected schema");
                if(!new java.io.File(args[1],"V148__finance_ad_automation.sql").isFile())throw new IllegalArgumentException();
                var upgrade=Flyway.configure().dataSource(url,user,password).locations(base,"filesystem:"+args[1]).target("148").createSchemas(false).load();
                var result=upgrade.migrate();
                if(result.migrationsExecuted>1||!upgrade.validateWithResult().validationSuccessful)throw new IllegalStateException("Unexpected migration result");
            }
            var validation=original.validateWithResult();
            if(!validation.validationSuccessful)throw new IllegalStateException("Older runtime cannot validate upgraded schema");
            return Map.of("success",true,"mode",args[0],"oldRuntimeValidationPassed",true);
    }
}
