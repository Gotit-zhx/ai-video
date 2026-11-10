"""Bounded digest negotiation; no caller-selected paths or container-store writes."""
import base64
import hashlib
import io
import json
import os
import re
import subprocess
import selectors
import time
import tarfile
import tempfile
from pathlib import Path
from . import oci

TTL_SECONDS = 1800
PLAN_ID = re.compile(r'[a-f0-9]{32}\Z')


class CacheIntegrityError(ValueError):
    pass


class CacheUnavailable(RuntimeError):
    pass


def unique_descriptors(descriptors):
    unique={}
    if not isinstance(descriptors,list) or len(descriptors)>264:raise ValueError('Too many OCI descriptors')
    for item in descriptors:
        if not isinstance(item,dict) or not oci.DIGEST.fullmatch(item.get('digest','')) or type(item.get('size')) is not int or item['size']<0:
            raise ValueError('Invalid blob descriptor')
        value={'digest':item['digest'],'size':item['size']}
        if value['digest'] in unique and unique[value['digest']]!=value:raise ValueError('Conflicting blob sizes')
        unique[value['digest']]=value
    return sorted(unique.values(),key=lambda d:d['digest'])


def verified(stream,descriptor,output=None):
    hashed=hashlib.sha256();count=0
    while block:=stream.read(min(oci.MIB,max(1,descriptor['size']+1-count))):
        count+=len(block)
        if count>descriptor['size']:raise CacheIntegrityError('Blob exceeds verified size')
        hashed.update(block)
        if output is not None:output.write(block)
    if count!=descriptor['size'] or 'sha256:'+hashed.hexdigest()!=descriptor['digest']:
        raise CacheIntegrityError('Blob content differs from verified descriptor')


def plan_missing(descriptors,open_cached):
    missing=[]
    for descriptor in unique_descriptors(descriptors):
        stream=open_cached(descriptor['digest'])
        if stream is None:missing.append(descriptor);continue
        with stream:verified(stream,descriptor)
    return missing


def validate_metadata(image,metadata):
    if image['repository'] not in {'ghcr.io/gotit-zhx/ai-video/app','ghcr.io/gotit-zhx/ai-video/frontend'}:
        raise ValueError('Unexpected registry repository')
    if not isinstance(metadata,dict) or len(metadata)>3:raise ValueError('Invalid OCI metadata map')
    raw={};descriptors=[];used=set()
    for value,encoded in metadata.items():
        if not oci.DIGEST.fullmatch(value) or not isinstance(encoded,str):raise ValueError('Invalid metadata digest')
        data=base64.b64decode(encoded,validate=True)
        if len(data)>oci.MIB or oci.digest(data)!=value:raise ValueError('Metadata checksum differs')
        raw[value]=data
    def fetch(value,size,manifest,is_metadata):
        if is_metadata:
            if value not in raw:raise ValueError('Missing OCI metadata')
            data=raw[value]
            if size is not None and len(data)!=size:raise ValueError('Metadata size differs')
            used.add(value);size=len(data)
        descriptors.append({'digest':value,'size':size})
        return raw[value] if is_metadata else None
    root,_=oci.graph(image,fetch)
    if used!=set(raw):raise ValueError('Unreferenced OCI metadata')
    return root,unique_descriptors(descriptors),raw


def receive_blobs(folder,stream,sent,expected):
    ordered=unique_descriptors(sent)
    if sent!=ordered or ordered!=unique_descriptors(expected):raise ValueError('Upload differs from negotiated missing set')
    folder=Path(folder);folder.mkdir(mode=0o700,parents=True,exist_ok=True)
    for item in ordered:
        path=folder/item['digest'][7:];temporary=path.with_suffix('.part');remaining=item['size'];hashed=hashlib.sha256()
        try:
            with temporary.open('wb') as output:
                while remaining:
                    block=stream.read(min(oci.MIB,remaining))
                    if not block:raise ValueError('Truncated blob upload')
                    output.write(block);hashed.update(block);remaining-=len(block)
                output.flush();os.fsync(output.fileno())
            if 'sha256:'+hashed.hexdigest()!=item['digest']:raise ValueError('Uploaded blob checksum differs')
            os.replace(temporary,path)
        finally:temporary.unlink(missing_ok=True)
    if stream.read(1):raise ValueError('Unexpected trailing blob data')


def reconstruct(image,root,descriptors,open_blob,path):
    with tarfile.open(path,'w') as archive:
        for name,value in {'oci-layout':{'imageLayoutVersion':'1.0.0'},'index.json':oci.index_for(image,root)}.items():
            data=json.dumps(value,separators=(',',':')).encode();entry=tarfile.TarInfo(name);entry.size=len(data)
            archive.addfile(entry,io.BytesIO(data))
        for descriptor in unique_descriptors(descriptors):
            source=open_blob(descriptor['digest'])
            if source is None:raise FileNotFoundError('Negotiated blob was evicted')
            with source:
                entry=tarfile.TarInfo('blobs/sha256/'+descriptor['digest'][7:]);entry.size=descriptor['size'];archive.addfile(entry,source)
    oci.validate_archive(path,image)


def temporary_peak(target,descriptors):
    # Uploaded/cached blobs, complete tar copies, daemon import and source extraction
    # can coexist. Do not discount this by the number of cached layers.
    blob_bytes=sum(d['size'] for d in unique_descriptors(descriptors))
    return (blob_bytes+sum(i['compressedBytes']+2*oci.MIB+i['unpackedBytes'] for i in target['images'].values())+
            2*target['sourceArchiveBytes']+target['sourceUnpackedBytes']+3*oci.MIB)


class ContainerdCache:
    """Read via ctr's supported content API; never access Docker's data directory."""
    def __init__(self,parent,descriptors):
        self.sizes={d["digest"]:d["size"] for d in unique_descriptors(descriptors)}
        self.temporary=tempfile.TemporaryDirectory(prefix='cache-',dir=parent)
        self.root=Path(self.temporary.name)
    def __enter__(self):
        from .host import command
        try:
            driver=json.loads(command('docker','info','--format','{{json .DriverStatus}}',timeout=15))
            if not any(row==['driver-type','io.containerd.snapshotter.v1'] for row in (driver or [])):
                raise CacheUnavailable('Classic image store requires full transfer')
            command('ctr','-n','moby','content','ls','-q','digest=='+next(iter(self.sizes),'sha256:'+'0'*64),timeout=15)
        except (RuntimeError,OSError,ValueError,subprocess.SubprocessError):
            self.temporary.cleanup();raise CacheUnavailable('Container content API unavailable') from None
        return self
    def __exit__(self,*_):self.temporary.cleanup()
    def open(self,value):
        from .host import command
        if value not in self.sizes:raise ValueError('Digest is not in verified graph')
        path=self.root/value[7:]
        if path.exists():return path.open('rb')
        present=command('ctr','-n','moby','content','ls','-q','digest=='+value,timeout=15).decode().splitlines()
        if value not in present:return None
        with path.open('xb') as output:
            result=subprocess.Popen(['ctr','-n','moby','content','get',value],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
            deadline=time.monotonic()+180;count=0
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(result.stdout,selectors.EVENT_READ)
                    while True:
                        if not selector.select(max(0,deadline-time.monotonic())):raise CacheIntegrityError('Cached content read timed out')
                        block=os.read(result.stdout.fileno(),min(oci.MIB,max(1,self.sizes[value]+1-count)))
                        if not block:break
                        count+=len(block)
                        if count>self.sizes[value]:raise CacheIntegrityError('Cached content exceeds verified size')
                        output.write(block)
                result.wait(timeout=max(0.01,deadline-time.monotonic()))
            finally:
                if result.poll() is None:result.kill();result.wait()
                result.stdout.close()
        if result.returncode:
            path.unlink(missing_ok=True)
            # A content GC between list and read is a miss; other failures fail closed.
            present=command('ctr','-n','moby','content','ls','-q','digest=='+value,timeout=15).decode().splitlines()
            if value not in present:return None
            raise CacheIntegrityError('Cached content could not be read')
        return path.open('rb')
