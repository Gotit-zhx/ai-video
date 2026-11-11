#!/usr/bin/env python3
"""Root-only V150 maintenance, after candidate verification and control-plane installation.

Run under systemd, with the same command plus --recover as ExecStopPost. Inputs and
the verified SQL/runner stay in this operation's private directory for recovery.
"""
import hashlib,json,os,re,shlex,subprocess,sys,time
from pathlib import Path
from heyi_ops.state import Store,read,atomic
from heyi_ops.host import Host,inspect,config_hash
from heyi_ops.storage import require_space

SQL_NAME='V150__finance_ad_spend_workspace.sql'
SQL_SHA='a8dfff7b4f3b722e137dd7bc39aebc0a8b5df9201b75a29199dc55211e7c1e13'
HISTORY="SELECT installed_rank,COALESCE(version,''),type,script,COALESCE(checksum,''),success FROM flyway_schema_history ORDER BY installed_rank"
def sha(value):return hashlib.sha256(value).hexdigest()

def protected_state(host):
    result={}
    for table in ('finance_ad_accounts','finance_ad_stores','finance_ad_streams','finance_sync_states','finance_api_pacing'):
        columns=host.sql("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"+table+"' ORDER BY ordinal_position").splitlines()
        if not columns or any(not re.fullmatch(r'[a-z_]+',c) for c in columns):raise ValueError('Invalid protected table columns')
        fields=','.join("COALESCE(CAST(`"+c+"` AS CHAR),'[NULL]')" for c in columns)
        # Hash each row and sort the hashes: no credentials or raw checkpoints enter the receipt.
        result[table]=sha('\n'.join(sorted(host.sql("SELECT SHA2(CONCAT_WS(CHAR(0),"+fields+"),256) FROM "+table).splitlines())).encode())
    return result

def history_after(before,after):
    rows=after.splitlines();previous=before.splitlines()
    if len(rows)!=len(previous)+1 or rows[:-1]!=previous:raise RuntimeError('Migration history prefix changed')
    last=rows[-1].split('\t')
    if last[1:4]!=['150','SQL',SQL_NAME] or last[-1]!='1':raise RuntimeError('V150 did not complete successfully')

def run_java(root,owner,mode):
    app=inspect('ai-commerce-app');env=dict(v.split('=',1) for v in app['Config']['Env'])
    payload=json.dumps({k:env[v] for k,v in [('url','DB_URL'),('user','DB_USERNAME'),('password','DB_PASSWORD')]}).encode()
    directory='/tmp/heyi-schema-'+owner
    try:
        subprocess.run(['docker','exec','ai-commerce-app','mkdir','-p',directory+'/migration'],check=True,capture_output=True)
        for source,destination in [(root/'FinanceAdSpendSchemaMaintenance.class',directory+'/FinanceAdSpendSchemaMaintenance.class'),(root/SQL_NAME,directory+'/migration/'+SQL_NAME)]:
            source.chmod(0o644)  # Public code/SQL inside a root-only directory; never credentials.
            subprocess.run(['docker','cp',str(source),'ai-commerce-app:'+destination],check=True,capture_output=True)
        args=['java','-Xms16m','-Xmx128m','-XX:MaxMetaspaceSize=96m','-XX:ActiveProcessorCount=2',
              '-Dheyi.schema.owner='+owner,'-Dloader.path='+directory,'-Dloader.main=FinanceAdSpendSchemaMaintenance',
              '-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher',mode,directory+'/migration']
        command=['docker','exec','-i','ai-commerce-app','sh','-c','echo $$ > '+shlex.quote(directory+'/pid')+'; exec '+shlex.join(args)]
        completed=subprocess.run(command,input=payload,capture_output=True,timeout=180)
        lines=[json.loads(line) for line in completed.stdout.decode().splitlines() if line.startswith('{')]
        if completed.returncode or not lines or lines[-1].get('success') is not True:raise RuntimeError('Migration runner rejected the operation')
        return lines[-1]
    finally:
        pid=subprocess.run(['docker','exec','ai-commerce-app','cat',directory+'/pid'],capture_output=True,text=True).stdout.strip()
        if pid.isdigit():
            cmdline=subprocess.run(['docker','exec','ai-commerce-app','cat','/proc/'+pid+'/cmdline'],capture_output=True).stdout
            if ('-Dheyi.schema.owner='+owner).encode() in cmdline:
                subprocess.run(['docker','exec','ai-commerce-app','kill','-TERM',pid],capture_output=True)
                raise RuntimeError('Migration runner still active; recovery must revalidate history')
        subprocess.run(['docker','exec','ai-commerce-app','rm','-rf',directory],check=True,capture_output=True)

def execute(owner,recover=False):
    if os.geteuid()!=0 or not re.fullmatch(r'[a-f0-9]{32}',owner):raise ValueError('Root and an operation ID are required')
    config=read('/etc/heyi-ops/config.json');store=Store(config['root'],config['lockPath'],config['compatibilityLockPath']);host=Host(config)
    root=Path(config['root'])/'schema-maintenance'/owner;inputs=read(root/'inputs.json')
    with store.exclusive():
        state=read(root/'state.json',{})
        if state.get('phase')=='succeeded':return state
        if recover and not state:return {'phase':'not-started'}
        if state and state.get('inputsSha256')!=sha((root/'inputs.json').read_bytes()):raise RuntimeError('Recovery inputs changed')
        if store.active()['version']!=inputs['expectedVersion'] or store.incomplete():raise RuntimeError('Active release or maintenance changed')
        previously_gated=host.owns_gate(owner)
        manifest=read(root/'candidate-release.json');baseline=read(Path(config['root'])/'baseline.json')
        if sha((root/'candidate-release.json').read_bytes())!=inputs['candidateManifestSha256']:raise RuntimeError('Candidate manifest changed')
        if config['controlPlaneRevision']!=manifest['source']['commit'] or baseline['operationsInputs']!=manifest['source']['inputs']['operations']:raise RuntimeError('Verified candidate control plane must be installed first')
        if sha((root/SQL_NAME).read_bytes())!=SQL_SHA or sha((root/'FinanceAdSpendSchemaMaintenance.class').read_bytes())!=inputs['runnerSha256']:raise RuntimeError('Maintenance inputs changed')
        if state.get('accepted'):
            # Business may already have resumed after an interrupted ungate. Revalidate the
            # accepted runtime instead of comparing legitimate new work with the old snapshot.
            host.verify(store.active(),maintenance=previously_gated)
            if previously_gated:host.ungate(owner)
            host.verify(store.active());state.update(phase='succeeded',finishedAt=int(time.time()))
            atomic(root/'state.json',state);return {'phase':'succeeded','operationId':owner,'recovered':True}
        app=inspect('ai-commerce-app')
        if app['Id']!=inputs['appContainerId'] or config_hash(app,baseline['environmentKeys']['app'])!=baseline['configurationHashes']['app']:raise RuntimeError('Application configuration changed')
        if not state:
            require_space(config['root'],64*1024**2)
            host.compatible(store.active())
            if host.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")!='148':raise RuntimeError('Expected schema V148')
            state={'phase':'prepared','operationId':owner,'inputsSha256':sha((root/'inputs.json').read_bytes()),'beforeHistory':host.sql(HISTORY),
                'previousMigrationInputs':baseline['migrationInputs'],'originalBaseline':baseline,'startedAt':int(time.time())}
            atomic(root/'state.json',state)
        try:
            host.gate(owner)
            if not (previously_gated and state.get('safePointReached')):
                host.drain(owner);state['safePointReached']=True;atomic(root/'state.json',state)
            if 'protectedState' not in state:
                state['protectedState']=protected_state(host);state['phase']='ready-to-migrate';atomic(root/'state.json',state)
            current=host.sql(HISTORY)
            if current==state['beforeHistory']:
                if state.get('migrationAttempted'):
                    raise RuntimeError('Uncertain DDL attempt; inspect partial tables before recovery')
                run_java(root,owner,'validate')
                # MySQL DDL is not transactional. An interrupted CREATE can persist
                # without a Flyway history row; never automatically replay that case.
                state['migrationAttempted']=True;atomic(root/'state.json',state)
                run_java(root,owner,'migrate')
            after=host.sql(HISTORY);history_after(state['beforeHistory'],after)
            if protected_state(host)!=state['protectedState']:raise RuntimeError('Original account, checkpoint or rate-limit state changed')
            # Exercise the real old application on V150 while the maintenance gate remains closed.
            # Graceful restart also releases idle JDBC sessions, without touching database files.
            if not state.get('oldRuntimeRestartPassed'):
                state['phase']='restarting-old-runtime';atomic(root/'state.json',state)
                subprocess.run(['docker','restart','--time','60','ai-commerce-app'],check=True,capture_output=True,timeout=120)
                deadline=time.monotonic()+180
                while True:
                    try:
                        code,body=host.request('/actuator/health')
                        if code==200 and json.loads(body).get('status')=='UP':break
                    except (OSError,ValueError):pass
                    if time.monotonic()>deadline:raise RuntimeError('Old application did not start on V150')
                    time.sleep(2)
                state['oldRuntimeRestartPassed']=True;atomic(root/'state.json',state)
            validated=run_java(root,owner,'validate')
            if protected_state(host)!=state['protectedState']:raise RuntimeError('Original account, checkpoint or rate-limit state changed after restart')
            receipt={'phase':'verified','operationId':owner,'sourceCommit':manifest['source']['commit'],
                'beforeSchemaFingerprint':sha(state['beforeHistory'].encode()),'afterSchemaFingerprint':sha(after.encode()),
                'migrationInputs':manifest['source']['inputs']['migrations'],'previousMigrationInputs':state['previousMigrationInputs'],
                'addedVersions':['150'],'sqlSha256':SQL_SHA,'historyPrefixPreserved':True,'protectedDataPreserved':True,
                'oldRuntimeValidationPassed':validated['oldRuntimeValidationPassed'],'oldRuntimeRestartPassed':True,'verifiedAt':int(time.time())}
            existing=read(root/'verified.json')
            if existing:
                if {k:v for k,v in receipt.items() if k!='verifiedAt'}!={k:v for k,v in existing.items() if k!='verifiedAt'}:raise RuntimeError('Verified receipt changed')
            else:atomic(root/'verified.json',receipt)
            updated=dict(baseline,schemaFingerprint=receipt['afterSchemaFingerprint'],migrationInputs=receipt['migrationInputs'],
                schemaCompatibility={'operationId':owner,'receiptSha256':sha((root/'verified.json').read_bytes())})
            atomic(Path(config['root'])/'baseline.json',updated)
            host.verify(store.active(),maintenance=True)
            state.update(phase='restoring',accepted=True);atomic(root/'state.json',state)
            host.ungate(owner);host.verify(store.active())
            state.update(phase='succeeded',finishedAt=int(time.time()));atomic(root/'state.json',state)
            return {'phase':'succeeded','operationId':owner,'schemaVersion':'150','applicationVersion':store.active()['version']}
        except BaseException as failure:
            state.update(phase='needs_attention',errorType=type(failure).__name__,updatedAt=int(time.time()))
            if not state.get('migrationAttempted') and host.sql(HISTORY)==state['beforeHistory']:
                host.ungate(owner);state['phase']='aborted'
            atomic(root/'state.json',state)
            raise

if __name__=='__main__':
    try:print(json.dumps(execute(sys.argv[1],'--recover' in sys.argv[2:])))
    except BaseException as failure:
        print(json.dumps({'phase':'failed','errorType':type(failure).__name__}));sys.exit(1)
