"""Transport original registry blobs without rebuilding their signed manifests."""
import base64
import contextlib
import http.client
import signal
import ssl
import subprocess
import threading
import time
import urllib.error
import hashlib
import io
import json
import re
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path
from .timing import TimingRecorder

MIB=1024**2
ATTEMPT_SECONDS=120
FILE_SECONDS=300
MAX_ATTEMPTS=3
DIGEST=re.compile(r'sha256:[a-f0-9]{64}\Z')
MANIFESTS={'application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json'}
INDEXES={'application/vnd.oci.image.index.v1+json','application/vnd.docker.distribution.manifest.list.v2+json'}


def digest(data):return 'sha256:'+hashlib.sha256(data).hexdigest()


def graph(image,fetch):
    """Read one amd64 graph; fetch verifies every descriptor's hash and size."""
    seen=set()
    def get(value,size=None,manifest=False,metadata=False):
        if not DIGEST.fullmatch(value) or (size is not None and (type(size) is not int or size<0)):
            raise ValueError('Invalid OCI descriptor')
        seen.add(value)
        return fetch(value,size,manifest,metadata)
    raw=get(image['manifestDigest'],manifest=True,metadata=True);root=json.loads(raw);body=root
    if root.get('mediaType') in INDEXES:
        entries=root.get('manifests',[])
        if len(entries)!=1 or entries[0].get('platform')!={'architecture':'amd64','os':'linux'}:
            raise ValueError('Only one explicit linux/amd64 image may be transferred')
        child=entries[0];body=json.loads(get(child['digest'],child['size'],manifest=True,metadata=True))
    if body.get('mediaType') not in MANIFESTS or len(body.get('layers',[]))>128:
        raise ValueError('Unsupported OCI manifest')
    config=body['config']
    if config['digest']!=image['configDigest']:raise ValueError('OCI config differs from release')
    configuration=json.loads(get(config['digest'],config['size'],metadata=True))
    if configuration.get('architecture')!='amd64' or configuration.get('os')!='linux':raise ValueError('OCI platform differs')
    descriptors=[config,*body['layers']]
    if sum(d['size'] for d in descriptors)!=image['compressedBytes']:raise ValueError('OCI layer budget differs')
    for layer in body['layers']:
        if layer.get('urls'):raise ValueError('External OCI layers are not allowed')
        get(layer['digest'],layer['size'])
    return {'mediaType':root['mediaType'],'digest':image['manifestDigest'],'size':len(raw)},seen


def index_for(image,descriptor):
    return {'schemaVersion':2,'manifests':[{**descriptor,'annotations':{'io.containerd.image.name':image['repository']+'@'+image['manifestDigest']}}]}


class RegistryRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        if urllib.parse.urlsplit(newurl).scheme!='https':raise ValueError('Insecure registry redirect')
        request=super().redirect_request(req,fp,code,msg,headers,newurl)
        if urllib.parse.urlsplit(newurl).netloc!=urllib.parse.urlsplit(req.full_url).netloc:
            request.remove_header('Authorization')
        return request


def export_archive(image,path,token,timings=None):
    """Full and delta use the same bounded registry reader and unchanged blobs."""
    registry=Registry(image,token,timings=timings);blobs={}
    root=Path(path).parent/'blobs';root.mkdir()
    def fetch(value,size,manifest,metadata):
        if value in blobs:
            file=blobs[value]
            if size is not None and file.stat().st_size!=size:raise ValueError('Conflicting OCI descriptor')
            return file.read_bytes() if metadata else None
        file=root/value[7:];temporary=file.with_suffix('.part');created=False
        try:
            with temporary.open('xb') as output:
                created=True;registry.download(value,size,output,manifest,metadata)
            temporary.replace(file)
        finally:
            if created:temporary.unlink(missing_ok=True)
        blobs[value]=file
        return file.read_bytes() if metadata else None
    descriptor,_=graph(image,fetch)
    with registry.timings.measure('ociExport') as timing:
        with tarfile.open(path,'w') as tar:
            for name,value in {'oci-layout':{'imageLayoutVersion':'1.0.0'},'index.json':index_for(image,descriptor)}.items():
                data=json.dumps(value,separators=(',',':')).encode();entry=tarfile.TarInfo(name);entry.size=len(data);tar.addfile(entry,io.BytesIO(data))
            for value,file in blobs.items():tar.add(file,arcname='blobs/sha256/'+value[7:],recursive=False)
        if Path(path).stat().st_size>image['compressedBytes']+2*MIB:raise ValueError('OCI archive exceeds framing budget')
        timing.add_bytes(Path(path).stat().st_size)


def validate_archive(path,image):
    if Path(path).stat().st_size>image['compressedBytes']+2*MIB:raise ValueError('OCI archive exceeds framing budget')
    with tarfile.open(path,'r:') as tar:
        entries={}
        for entry in tar:
            if (not entry.isfile() or entry.name in entries or entry.size<0 or
                not (entry.name in {'oci-layout','index.json'} or re.fullmatch(r'blobs/sha256/[a-f0-9]{64}',entry.name))):
                raise ValueError('Unsafe OCI archive member')
            entries[entry.name]=entry
            if len(entries)>134:raise ValueError('Too many OCI archive members')
        end=max((entry.offset_data+((entry.size+511)//512)*512 for entry in entries.values()),default=0)
        with Path(path).open('rb') as padding:
            padding.seek(end);tail_size=0
            while block:=padding.read(64*1024):
                tail_size+=len(block)
                if any(block):raise ValueError('Trailing OCI archive data')
        if tail_size<1024:raise ValueError('Truncated OCI archive end')
        def metadata(name):
            if name not in entries or entries[name].size>MIB:raise ValueError('Missing or oversized OCI metadata')
            return json.load(tar.extractfile(entries[name]))
        if metadata('oci-layout')!={'imageLayoutVersion':'1.0.0'}:raise ValueError('Invalid OCI layout')
        descriptors=[]
        def fetch(value,size,manifest,is_metadata):
            name='blobs/sha256/'+value.split(':')[1];entry=entries.get(name)
            if not entry or (size is not None and entry.size!=size) or (is_metadata and entry.size>MIB):raise ValueError('OCI blob size differs')
            descriptors.append({'digest':value,'size':entry.size})
            hashed=hashlib.sha256();data=[]
            with tar.extractfile(entry) as source:
                while block:=source.read(MIB):
                    hashed.update(block)
                    if is_metadata:data.append(block)
            if 'sha256:'+hashed.hexdigest()!=value:raise ValueError('OCI blob digest differs')
            return b''.join(data) if is_metadata else None
        descriptor,seen=graph(image,fetch)
        if metadata('index.json')!=index_for(image,descriptor):raise ValueError('OCI import names or root manifest differ')
        if set(entries)!={'oci-layout','index.json',*('blobs/sha256/'+v.split(':')[1] for v in seen)}:
            raise ValueError('Unreferenced OCI archive member')
        return {'root':descriptor,'descriptors':descriptors}


@contextlib.contextmanager
def wall_deadline(seconds):
    """Unix runner main-thread deadline, including DNS, TLS and trickling reads."""
    if seconds<=0:raise TimeoutError('Download deadline exceeded')
    if threading.current_thread() is not threading.main_thread() or not hasattr(signal,'setitimer'):
        raise RuntimeError('Bounded downloads require the Unix main thread')
    if signal.getitimer(signal.ITIMER_REAL)[0]:raise RuntimeError('An outer process deadline is already active')
    previous=signal.getsignal(signal.SIGALRM)
    def expired(*_):raise TimeoutError('Download deadline exceeded')
    signal.signal(signal.SIGALRM,expired);signal.setitimer(signal.ITIMER_REAL,seconds)
    try:yield
    finally:signal.setitimer(signal.ITIMER_REAL,0);signal.signal(signal.SIGALRM,previous)


def recoverable_download(error):
    if isinstance(error,urllib.error.HTTPError):return error.code in {429,500,502,503,504}
    if isinstance(error,urllib.error.URLError):return not isinstance(error.reason,(ssl.SSLError,ValueError))
    return isinstance(error,(TimeoutError,ConnectionError,http.client.IncompleteRead))


def download_file(opener,request,output,maximum,size=None,expected=None,timing=None):
    """At most three recoverable attempts; one 300s budget and 120s per attempt."""
    deadline=time.monotonic()+FILE_SECONDS;start=output.tell()
    try:
        for attempt in range(MAX_ATTEMPTS):
            remaining=deadline-time.monotonic()
            if remaining<=0:raise TimeoutError('Download file budget exhausted')
            duration=min(ATTEMPT_SECONDS,remaining);attempt_end=time.monotonic()+duration
            output.seek(start);output.truncate();hashed=hashlib.sha256();count=0
            try:
                with wall_deadline(duration):
                    with opener.open(request,timeout=min(60,duration)) as response:
                        while True:
                            if time.monotonic()>=attempt_end:raise TimeoutError('Download attempt deadline exceeded')
                            block=response.read(min(MIB,max(1,maximum+1-count)))
                            if time.monotonic()>=attempt_end:raise TimeoutError('Download attempt deadline exceeded')
                            if not block:break
                            count+=len(block)
                            if timing:timing.add_bytes(len(block))
                            if count>maximum:raise ValueError('Registry blob exceeded size')
                            hashed.update(block);output.write(block)
                if (size is not None and count!=size) or (expected is not None and 'sha256:'+hashed.hexdigest()!=expected):
                    raise ValueError('Registry blob verification failed')
                return count
            except Exception as error:
                output.seek(start);output.truncate()
                if not recoverable_download(error) or attempt+1==MAX_ATTEMPTS:raise
        raise TimeoutError('Download attempts exhausted')
    except BaseException:
        output.seek(start);output.truncate();raise


class Registry:
    """Bounded runner-side registry downloads; authorization never enters receipts."""
    def __init__(self,image,token,timings=None):
        if image['repository'] not in {'ghcr.io/gotit-zhx/ai-video/app','ghcr.io/gotit-zhx/ai-video/frontend'}:
            raise ValueError('Unexpected registry repository')
        self.image=image;self.name=image['repository'].removeprefix('ghcr.io/');self.timings=timings or TimingRecorder()
        self.opener=urllib.request.build_opener(RegistryRedirect())
        url='https://ghcr.io/token?'+urllib.parse.urlencode({'service':'ghcr.io','scope':'repository:'+self.name+':pull'})
        credentials=base64.b64encode(('Gotit-zhx:'+token).encode()).decode();output=io.BytesIO()
        with self.timings.measure('registryResolve') as timing:
            download_file(self.opener,urllib.request.Request(url,headers={'Authorization':'Basic '+credentials}),output,MIB,timing=timing)
            self.bearer=json.loads(output.getvalue())['token']
    def download(self,value,size,output,manifest=False,metadata=False):
        if not DIGEST.fullmatch(value) or (size is not None and (type(size) is not int or size<0)):
            raise ValueError('Invalid OCI descriptor')
        maximum=MIB if metadata else self.image['compressedBytes']
        if size is not None:
            if size>maximum:raise ValueError('OCI descriptor exceeds budget')
            maximum=size
        request=urllib.request.Request('https://ghcr.io/v2/'+self.name+('/manifests/' if manifest else '/blobs/')+value,
            headers={'Authorization':'Bearer '+self.bearer,'Accept':','.join(sorted(MANIFESTS|INDEXES))})
        with self.timings.measure('registryDownload') as timing:
            download_file(self.opener,request,output,maximum,size,value,timing)


def export_metadata(image,token,timings=None):
    registry=Registry(image,token,timings=timings);metadata={}
    def fetch(value,size,manifest,is_metadata):
        if not is_metadata:return None
        if value not in metadata:
            output=io.BytesIO();registry.download(value,size,output,manifest,is_metadata);metadata[value]=output.getvalue()
        if size is not None and len(metadata[value])!=size:raise ValueError('Conflicting metadata size')
        return metadata[value]
    graph(image,fetch)
    result={value:base64.b64encode(data).decode() for value,data in metadata.items()}
    if len(json.dumps(result).encode())>MIB:raise ValueError('Metadata frame exceeds budget')
    return result


def export_blobs(image,descriptors,folder,token,timings=None):
    registry=Registry(image,token,timings=timings);folder=Path(folder);folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    for item in descriptors:
        path=folder/item['digest'].split(':')[1]
        if path.exists():
            from .transfer import verified
            with path.open('rb') as cached:verified(cached,item)
            continue
        temporary=path.with_suffix('.part');created=False
        try:
            with temporary.open('xb') as output:
                created=True;registry.download(item['digest'],item['size'],output)
            temporary.replace(path)
        finally:
            if created:temporary.unlink(missing_ok=True)


def saved_metadata(image_id,maximum):
    """Read an immutable Docker image-save stream without files or container exports."""
    if not isinstance(image_id,str) or not DIGEST.fullmatch(image_id) or type(maximum) is not int or maximum<0:
        raise ValueError('Invalid immutable Docker save bounds')
    process=subprocess.Popen(['docker','image','save',image_id],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    class Limited:
        count=0
        def read(self,size=-1):
            size=MIB if size<0 else size
            block=process.stdout.read(min(size,max(1,maximum+1-self.count)))
            self.count+=len(block)
            if self.count>maximum:raise ValueError('Docker save exceeded image size budget')
            return block
    source=Limited();result={};retained=0;members=0
    try:
        with wall_deadline(180):
            with tarfile.open(fileobj=source,mode='r|') as archive:
                for member in archive:
                    members+=1
                    if members>512 or member.size<0 or member.size>maximum:raise ValueError('Docker save member exceeds budget')
                    if member.isdir():continue
                    if not member.isfile() or member.name.startswith('/') or '..' in Path(member.name).parts:
                        raise ValueError('Unsafe Docker save member')
                    if member.size>MIB:continue
                    raw=archive.extractfile(member).read(MIB+1)
                    try:value=json.loads(raw)
                    except (ValueError,UnicodeError):continue
                    if not isinstance(value,dict):continue
                    if value.get('mediaType') not in MANIFESTS|INDEXES and not {'architecture','os','rootfs'}<=set(value):continue
                    identity=digest(raw)
                    if identity in result:continue
                    retained+=len(raw)
                    if retained>4*MIB:raise ValueError('Docker save metadata exceeds budget')
                    result[identity]=raw
            while source.read(MIB):pass
            if process.wait(timeout=1):raise RuntimeError('Docker image save failed')
        return result
    finally:
        if process.poll() is None:process.kill();process.wait(timeout=5)
        process.stdout.close()
