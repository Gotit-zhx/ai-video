"""Deploy and rebuild the verified Excel basis with bounded writes and a renewable sync pause."""
import datetime, gzip, importlib.util, io, json, os, re, secrets, shutil, signal, subprocess, sys, time, zipfile
from pathlib import Path
os.umask(0o077)
root=Path(sys.argv[1]).resolve()
if len(sys.argv)<3:sys.argv.append('maintenance')
assert shutil.disk_usage(root).free>3*1024**3
spec=importlib.util.spec_from_file_location('basis_release',root/'release-finance-excel-basis.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
release=module.release
core=release.core
pausefile=root/'basis-pause-private.json'
state=json.loads(pausefile.read_text()) if pausefile.exists() else {'original':{},'marker':''}
def literal(v):return 'CONVERT(0x'+str(v).encode().hex()+' USING utf8mb4)'
def pause():
    marker=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=5)).strftime('%Y-%m-%d %H:%M:%S.%f')
    found=release.base.sql("""
        CREATE TEMPORARY TABLE fi_basis_pending AS
        SELECT organization_id,user_id,shop_id,channel,next_at FROM finance_sync_states
        WHERE status<>'RUNNING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) AND next_at<'%s';
        SELECT organization_id,user_id,shop_id,channel,DATE_FORMAT(next_at,'%%Y-%%m-%%d %%H:%%i:%%s.%%f') FROM fi_basis_pending;
        UPDATE finance_sync_states st JOIN fi_basis_pending p ON st.organization_id=p.organization_id AND st.user_id=p.user_id
          AND st.shop_id=p.shop_id AND st.channel=p.channel SET st.next_at='%s' WHERE st.next_at=p.next_at
          AND st.status<>'RUNNING' AND (st.lease_until IS NULL OR st.lease_until<UTC_TIMESTAMP(6));
        DROP TEMPORARY TABLE fi_basis_pending;
    """%(marker,marker))
    for line in found.splitlines():
        parts=line.split('\t');assert len(parts)==5
        state['original'].setdefault('\t'.join(parts[:4]),parts[4])
    state['marker']=marker
    temporary=Path(str(pausefile)+'.tmp');temporary.write_text(json.dumps(state));temporary.replace(pausefile)
def resume():
    for key,previous in state['original'].items():
        release.base.sql('UPDATE finance_sync_states SET next_at='+literal(previous)+' WHERE '+
          ' AND '.join(c+'='+literal(v) for c,v in zip(('organization_id','user_id','shop_id','channel'),key.split('\t')))+
          ' AND next_at='+literal(state['marker'])+" AND status<>'RUNNING' AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
    (root/'basis-resumed.json').write_text(json.dumps({'restored':len(state['original']),'at':time.time()}))
    print('Original finance schedules restored; checkpoints and leases preserved.',flush=True)
def sql(statement):
    p=subprocess.run(['docker','exec','-i','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB'],input=statement,text=True,capture_output=True,timeout=30)
    if p.returncode:raise RuntimeError('Scoped maintenance account command failed')
    return p.stdout
process=None;progress=None;environment=root/'backfill-private.env';user=''
def interrupt(signum,frame):raise KeyboardInterrupt('Basis maintenance interrupted')
signal.signal(signal.SIGTERM,interrupt);signal.signal(signal.SIGHUP,interrupt)
try:
    for attempt in range(60):
        pause()
        if release.base.sql("SELECT COUNT(*) FROM finance_sync_states WHERE status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")=='0':break
        time.sleep(.5)
    else:raise RuntimeError('Active sync did not drain; resuming its schedules')
    if not (root/'activation.json').exists():
        module.previous.deploy()
    else:release.verify()
    pause()
    app=core.inspect(release.APP);db=core.inspect('ai-commerce-mysql')
    settings=dict(v.split('=',1) for v in db['Config']['Env']);schema=settings['MYSQL_DATABASE']
    assert re.fullmatch(r'[a-zA-Z0-9_]+',schema)
    network=next(iter(set(app['NetworkSettings']['Networks'])&set(db['NetworkSettings']['Networks'])))
    deployed=core.run('docker','exec',release.APP,'cat','/app/app.jar')
    assert core.sha(deployed)==json.loads((root/'prepared.json').read_text())['jarHash']
    with zipfile.ZipFile(io.BytesIO(deployed)) as z:
        for name in z.namelist():
            if name.startswith('BOOT-INF/classes/') and name.endswith('.class'):
                relative=name.removeprefix('BOOT-INF/classes/');assert '..' not in Path(relative).parts
                p=root/'production-classes'/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
    baseline=root/'platform-net-before.tsv.gz'
    if not baseline.exists():subprocess.run([sys.executable,str(root/'check-settlement-net-ledger.py'),str(root),'before'],check=True,timeout=60)
    user='fi_basis_'+secrets.token_hex(5);password=secrets.token_hex(32)
    sql(f"CREATE USER '{user}'@'%' IDENTIFIED BY '{password}';"+
        f"GRANT SELECT,UPDATE,INSERT,DELETE ON {schema}.finance_records TO '{user}'@'%';"+
        f"GRANT SELECT ON {schema}.finance_connections TO '{user}'@'%';"+
        f"GRANT SELECT ON {schema}.finance_reporting_months TO '{user}'@'%';")
    firstShops=','.join(json.loads((root/'excel-specification.json').read_text())['shops']) if (root/'excel-specification.json').exists() else ''
    environment.write_text(f'BACKFILL_JDBC=jdbc:mysql://ai-commerce-mysql:3306/{schema}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=8000&socketTimeout=30000&rewriteBatchedStatements=true\nBACKFILL_USER={user}\nBACKFILL_PASSWORD={password}\nBACKFILL_WORKERS=4\nBACKFILL_FIRST_SHOPS={firstShops}\n')
    output=root/'backfill';output.mkdir(exist_ok=True)
    spec=importlib.util.spec_from_file_location('basis_progress',root/'settlement-progress.py');pmod=importlib.util.module_from_spec(spec);spec.loader.exec_module(pmod)
    progress=pmod.SettlementProgress(root,app);progress.publish('RUNNING')
    command=['docker','run','--rm','--log-driver','none','--user','0:0','--name','finance-excel-basis-backfill','--network',network,'--memory','768m','--cpus','1.5','--env-file',str(environment),'-v',str(root/'production-classes')+':/classes:ro','-v',str(root/'maintenance')+':/maintenance:ro','-v','/tmp/finance-order-tags-qa-20261026:/deps:ro','-v',str(output)+':/out','--entrypoint','java',app['Config']['Image'],'-Xmx512m','-cp','/maintenance:/classes:/deps/lib/*','com.yingdao.aicommerce.finance.FinanceExcelBasisBackfill','/out']
    process=subprocess.Popen(command)
    lastPause=time.monotonic();lastReport=time.monotonic()
    while process.poll() is None:
        progress.publish('RUNNING')
        if time.monotonic()-lastPause>45:pause();lastPause=time.monotonic()
        if time.monotonic()-lastReport>30:
            files=[json.loads(p.read_text()) for p in output.glob('*.json') if p.stem.count('-')==4]
            print(json.dumps({'records':sum(p.get('records',0) for p in files),'bills':sum(p.get('bills',0) for p in files),'completeShops':sum(bool(p.get('complete')) for p in files)}),flush=True);lastReport=time.monotonic()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:pass
    if process.returncode:raise RuntimeError('Historical derivation failed; checkpoints retained')
    subprocess.run([sys.executable,str(root/'check-settlement-net-ledger.py'),str(root),'after'],check=True,timeout=60)
    time.sleep(6);progress.invalidate_snapshots();progress.publish('COMPLETE')
    (root/'basis-complete.json').write_text(json.dumps({'completedAt':time.time(),'sourceNetUnchanged':True}))
    print('Excel basis deployed and historical derivation complete.',flush=True)
except BaseException:
    if process is not None and process.poll() is None:
        subprocess.run(['docker','stop','--timeout','20','finance-excel-basis-backfill'],capture_output=True,timeout=30);process.wait(timeout=30)
    if progress is not None:progress.publish('FAILED')
    raise
finally:
    environment.unlink(missing_ok=True)
    if user:sql(f"DROP USER IF EXISTS '{user}'@'%';")
    resume()
