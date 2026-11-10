#!/usr/bin/env python3
"""Conservative, auditable suite selection. Unknown paths always expand to full."""
import argparse
import fnmatch
import json
import os
import subprocess
from pathlib import Path
import java
import release

ROOT=release.ROOT
FULL=['core-policy','java-unit','all-db','all-frontend','container-smoke','systemd-recovery']


def config():return json.loads((ROOT/'scripts/ci/test-scopes.json').read_text())


def catalog():
    cfg=config();sources,database=java.inventory()
    java_paths={p.stem:str(p.relative_to(ROOT)) for p in (ROOT/'src/test/java').rglob('*Test.java')}
    tests=sorted(str(p.relative_to(ROOT)) for base in ['frontend/test','frontend/tests'] for p in (ROOT/base).glob('*') if '.test.' in p.name)
    qa=sorted(str(p.relative_to(ROOT)) for p in (ROOT/'scripts/qa').glob('test-*.mjs') if p.name not in java.OPTIONAL['excludedQaScripts'])
    def entry(files,classes=()):return dict(files=sorted(files),classes=sorted(classes),media=False)
    result={'core-policy':entry(['scripts/ci/release.py','scripts/ci/scope.py']+[str(p.relative_to(ROOT)) for p in (ROOT/'scripts/ci/tests').glob('test_*.py')]+[str(p.relative_to(ROOT)) for p in (ROOT/'ops/heyi-ops/tests').glob('test_*.py')]),
            'frontend-assets':entry(['scripts/ci/frontend.py','frontend/package.py']),
            'finance-frontend':entry([p for p in qa if '/test-finance-' in p or '/test-incremental-' in p]),
            'all-frontend':entry(tests+qa+['scripts/ci/frontend.py']),
            'java-unit':entry([java_paths[n] for n in sources if n not in database and n not in java.OPTIONAL['excludedClasses']],set(sources)-set(database)-set(java.OPTIONAL['excludedClasses'])),
            'all-db':entry([java_paths[n] for n in database],database),
            'container-smoke':entry(['scripts/ci/container.py','scripts/ci/smoke.py']),
            'systemd-recovery':entry(['scripts/ci/systemd_test.py'])}
    for module,prefixes in cfg['modules'].items():
        classes=[name for name in database if any(name.startswith(p) for p in prefixes)]
        result[module+'-db']=entry([java_paths[n] for n in classes],classes)
    for name,item in result.items():
        item['media']=name in cfg['mediaSuites']
        if not item['files'] or any(not (ROOT/p).is_file() for p in item['files']):raise ValueError('Empty or missing suite inputs: '+name)
    return result


def classify_changed_paths(paths,context):
    suites={'core-policy'};reasons=set();full=False;host=False;unsupported=False;cfg=config()
    for path in paths:
        if release.documentation_only([path]):continue
        if path.startswith(('ops/','compose')) or path in {'scripts/ci/release.py','scripts/heyi-mysql-backup.py'}:
            host=True;reasons.add('host_maintenance');full=True
        if path.startswith(('services/','apps/','config/','compose')):
            unsupported=True;full=True;reasons.add('unsupported_component')
        if path.startswith(('src/test/','frontend/test/','frontend/tests/','scripts/qa/test-')):
            if path not in cfg['mappedTests']:
                full=True;reasons.add('unmapped_test')
            elif path.startswith('src/test/'):
                sources,database=java.inventory()
                if Path(path).stem in database:
                    module=next((m for m,prefixes in cfg['modules'].items() if any(Path(path).stem.startswith(p) for p in prefixes)),None)
                    suites.update(['java-unit',module+'-db' if module else 'all-db','container-smoke'])
                elif path.endswith('Test.java'):suites.update(['java-unit','container-smoke'])
                else:suites.update(['java-unit','all-db','container-smoke'])
                reasons.add('mapped_module')
            elif 'finance' in path:suites.update(['finance-frontend','frontend-assets','container-smoke']);reasons.add('mapped_module')
            else:suites.update(['all-frontend','container-smoke']);reasons.add('mapped_module')
        elif path.startswith(('docs/',)) or (path.endswith('.md') and not path.startswith(('src/','services/','apps/'))):pass
        elif path.startswith('frontend/') and path not in {'frontend/Dockerfile','frontend/package.py','frontend/nginx.conf','frontend/.dockerignore'}:
            suites.update(['frontend-assets','finance-frontend' if Path(path).name.startswith('finance-') else 'all-frontend','container-smoke']);reasons.add('mapped_module')
        elif path.startswith('src/main/java/'):
            module=next((m for m in cfg['modules'] if '/'+m+'/' in path),None)
            if module and module!='finance':suites.update(['java-unit',module+'-db','container-smoke']);reasons.add('mapped_module')
            else:full=True;reasons.add('high_risk')
        elif path.startswith(('src/main/','.github/','scripts/ci/','scripts/qa/','ops/','src/test/')) or path in {'pom.xml','Dockerfile','maven-settings.xml','.dockerignore','frontend/Dockerfile','frontend/package.py','frontend/nginx.conf','frontend/.dockerignore'}:
            full=True;reasons.add('high_risk')
        else:full=True;reasons.add('unknown_path')
    if context.get('lastFullPassed') is None:full=True;reasons.add('no_full_baseline')
    elif context.get('lastFullPassed') is not True:full=True;reasons.add('full_failed')
    if context.get('lastFullPassed') is True and context.get('lastFullAgeHours',49)>48:full=True;reasons.add('full_stale')
    if context.get('firstV2') or context.get('policyChanged') or context.get('forceFull'):full=True;reasons.add('high_risk')
    if full:suites=set(FULL)
    profile='full' if full else ('docs' if suites=={'core-policy'} else 'module')
    if profile=='docs':reasons.add('docs_only')
    return dict(profile=profile,requiredSuites=sorted(suites),requiresHostMaintenance=host,unsupported=unsupported,reasonCodes=sorted(reasons))


def file_digest(paths):
    files={}
    for path in sorted(set(paths)):
        p=ROOT/path
        if not p.is_file():raise ValueError('Missing required evidence input: '+path)
        files[path]=release.sha(p.read_bytes())
    return release.sha(release.canonical(files))


def inputs(suites):
    all_suites=catalog()
    policy=[str(p.relative_to(ROOT)) for base in ['.github','scripts/ci'] for p in (ROOT/base).rglob('*') if p.is_file() and not any(x in p.parts for x in ['tests','__pycache__','node_modules']) and p.suffix in {'.py','.yml','.json','.java','.sha256'}]
    tools=['pom.xml','maven-settings.xml','scripts/ci/runtime.json','scripts/ci/dependencies.sha256','scripts/ci/qa/package-lock.json','Dockerfile','frontend/Dockerfile','frontend/package.py','src/main/docker/runtime.json','src/main/docker/runtime.Dockerfile','src/main/docker/runtime-packages.lock','src/main/docker/runtime-bom.sha256']
    fixtures=[str(p.relative_to(ROOT)) for base in ['src/test/resources','scripts/qa/fixtures'] for p in (ROOT/base).rglob('*') if p.is_file()]
    # Include inherited Java helpers (not only *Test.java) and production inputs.
    helpers=[str(p.relative_to(ROOT)) for p in (ROOT/'src/test/java').rglob('*.java') if not p.name.endswith('Test.java')]
    result={name:file_digest(all_suites[name]['files']+fixtures+(helpers if name.endswith('-db') or name=='java-unit' else [])) for name in suites}
    return dict(repository=release.REPOSITORY,tree=release.run('git','rev-parse','HEAD^{tree}'),policyDigest=file_digest(policy),toolchainDigest=file_digest(tools),suiteInputDigests=result,requiredSuites=sorted(suites))

def full_baseline_context():
    """Latest develop CI failure blocks fast selection; no artifact/self-trust fallback."""
    import datetime
    import evidence
    request=inputs(FULL)
    try:
        runs=evidence.api('repos/'+release.REPOSITORY+'/actions/workflows/ci.yml/runs?branch=develop&per_page=30')['workflow_runs']
        for run in runs:
            if run.get('event') not in {'schedule','push','workflow_dispatch'} or run.get('status')!='completed':continue
            if run.get('conclusion')!='success':return {'lastFullPassed':False,'lastFullAgeHours':0}
            tree=evidence.api('repos/'+release.REPOSITORY+'/git/commits/'+run['head_sha'])['tree']['sha']
            try:verified=evidence.fetch_verified(run['id'],run['run_attempt'],dict(request,tree=tree))
            except (ValueError,KeyError,subprocess.CalledProcessError,subprocess.TimeoutExpired):continue
            if verified.get('profile')!='full':continue
            date=datetime.datetime.fromisoformat(run['updated_at'].replace('Z','+00:00'))
            age=(datetime.datetime.now(datetime.timezone.utc)-date).total_seconds()/3600
            return {'lastFullPassed':True,'lastFullAgeHours':max(0,age),'dbDurations':verified.get('dbDurations',{})}
    except (ValueError,KeyError,subprocess.CalledProcessError,subprocess.TimeoutExpired):pass
    return {}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base');parser.add_argument('--output',default='target/ci-plan.json');args=parser.parse_args()
    paths=release.run('git','diff','--name-only',args.base,'HEAD').splitlines() if args.base and args.base!='0'*40 else ['unknown.bin']
    # Until a qualified full baseline is supplied, the safe outcome is full even with the flag on.
    context=full_baseline_context() if os.environ.get('GH_TOKEN') else {}
    context['forceFull']=(os.environ.get('HEYI_FAST_CI_ENABLED')!='true' or os.environ.get('FORCE_FULL')=='true')
    context['firstV2']=os.environ.get('HEYI_V2_BOOTSTRAPPED')!='true'
    plan=classify_changed_paths(paths,context);plan.update(inputs(plan['requiredSuites']));plan['dbDurations']=context.get('dbDurations',{})
    plan['jobs']={k: any(s in plan['requiredSuites'] for s in v) for k,v in {'unit':['java-unit'],'database':['all-db','product-db','video-db','knowledge-db','finance-db'],'frontend':['all-frontend','finance-frontend','frontend-assets'],'container':['container-smoke'],'systemd':['systemd-recovery']}.items()}
    out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(plan,sort_keys=True,indent=2)+'\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as f:
            f.write('plan='+json.dumps(plan,separators=(',',':'))+'\n')
            for key,value in plan['jobs'].items():f.write(key+'='+str(value).lower()+'\n')
