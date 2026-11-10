"""Transport original registry blobs without rebuilding their signed manifests."""
import base64
import hashlib
import io
import json
import re
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path

MIB=1024**2
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


def export_archive(image,path,token):
    """Runner only: registry auth stays in memory and is absent from the archive."""
    repository=image['repository']
    if repository not in {'ghcr.io/gotit-zhx/ai-video/app','ghcr.io/gotit-zhx/ai-video/frontend'}:
        raise ValueError('Unexpected registry repository')
    name=repository.removeprefix('ghcr.io/');opener=urllib.request.build_opener(RegistryRedirect())
    url='https://ghcr.io/token?'+urllib.parse.urlencode({'service':'ghcr.io','scope':'repository:'+name+':pull'})
    credentials=base64.b64encode(('Gotit-zhx:'+token).encode()).decode()
    with opener.open(urllib.request.Request(url,headers={'Authorization':'Basic '+credentials}),timeout=30) as response:
        bearer=json.loads(response.read(MIB))['token']
    blobs={};root=Path(path).parent/'blobs';root.mkdir()
    def fetch(value,size,manifest,metadata):
        if value in blobs:
            file=blobs[value]
            if size is not None and file.stat().st_size!=size:raise ValueError('Conflicting OCI descriptor')
            return file.read_bytes() if metadata else None
        maximum=MIB if metadata else image['compressedBytes']
        if size is not None and size>maximum:raise ValueError('OCI descriptor exceeds budget')
        request=urllib.request.Request('https://ghcr.io/v2/'+name+('/manifests/' if manifest else '/blobs/')+value,
            headers={'Authorization':'Bearer '+bearer,'Accept':','.join(sorted(MANIFESTS|INDEXES))})
        file=root/value.split(':')[1];hashed=hashlib.sha256();count=0
        with opener.open(request,timeout=60) as response,file.open('xb') as output:
            while block:=response.read(MIB):
                count+=len(block)
                if count>maximum or (size is not None and count>size):raise ValueError('Registry blob exceeded size')
                hashed.update(block);output.write(block)
        if (size is not None and count!=size) or 'sha256:'+hashed.hexdigest()!=value:raise ValueError('Registry blob verification failed')
        blobs[value]=file
        return file.read_bytes() if metadata else None
    descriptor,_=graph(image,fetch)
    with tarfile.open(path,'w') as tar:
        for name,value in {'oci-layout':{'imageLayoutVersion':'1.0.0'},'index.json':index_for(image,descriptor)}.items():
            data=json.dumps(value,separators=(',',':')).encode();entry=tarfile.TarInfo(name);entry.size=len(data);tar.addfile(entry,io.BytesIO(data))
        for value,file in blobs.items():tar.add(file,arcname='blobs/sha256/'+value.split(':')[1],recursive=False)
    if Path(path).stat().st_size>image['compressedBytes']+2*MIB:raise ValueError('OCI archive exceeds framing budget')


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


class Registry:
    """Bounded runner-side registry downloads; authorization never enters receipts."""
    def __init__(self,image,token):
        if image['repository'] not in {'ghcr.io/gotit-zhx/ai-video/app','ghcr.io/gotit-zhx/ai-video/frontend'}:
            raise ValueError('Unexpected registry repository')
        self.image=image;self.name=image['repository'].removeprefix('ghcr.io/')
        self.opener=urllib.request.build_opener(RegistryRedirect())
        url='https://ghcr.io/token?'+urllib.parse.urlencode({'service':'ghcr.io','scope':'repository:'+self.name+':pull'})
        credentials=base64.b64encode(('Gotit-zhx:'+token).encode()).decode()
        with self.opener.open(urllib.request.Request(url,headers={'Authorization':'Basic '+credentials}),timeout=30) as response:
            self.bearer=json.loads(response.read(MIB))['token']
    def download(self,value,size,output,manifest=False,metadata=False):
        if not DIGEST.fullmatch(value) or (size is not None and (type(size) is not int or size<0)):
            raise ValueError('Invalid OCI descriptor')
        maximum=MIB if metadata else self.image['compressedBytes']
        if size is not None:maximum=min(maximum,size)
        request=urllib.request.Request('https://ghcr.io/v2/'+self.name+('/manifests/' if manifest else '/blobs/')+value,
            headers={'Authorization':'Bearer '+self.bearer,'Accept':','.join(sorted(MANIFESTS|INDEXES))})
        count=0;hashed=hashlib.sha256()
        with self.opener.open(request,timeout=60) as response:
            while block:=response.read(min(MIB,max(1,maximum+1-count))):
                count+=len(block)
                if count>maximum:raise ValueError('Registry blob exceeded size')
                hashed.update(block);output.write(block)
        if (size is not None and count!=size) or 'sha256:'+hashed.hexdigest()!=value:raise ValueError('Registry blob verification failed')


def export_metadata(image,token):
    registry=Registry(image,token);metadata={}
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


def export_blobs(image,descriptors,folder,token):
    registry=Registry(image,token);folder=Path(folder);folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    for item in descriptors:
        path=folder/item['digest'].split(':')[1]
        if path.exists():
            from .transfer import verified
            with path.open('rb') as cached:verified(cached,item)
            continue
        temporary=path.with_suffix('.part')
        try:
            with temporary.open('xb') as output:registry.download(item['digest'],item['size'],output)
            temporary.replace(path)
        finally:temporary.unlink(missing_ok=True)
