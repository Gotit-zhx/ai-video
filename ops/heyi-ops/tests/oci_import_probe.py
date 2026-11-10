"""Explicit isolated Docker probe. Removes only its unique container/image reference."""
import io
import json
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from heyi_ops import oci,transfer
from test_transfer import fixture


def main():
    image,metadata,blobs=fixture();identity=uuid.uuid4().hex
    config=json.loads(blobs[image['configDigest']]);config['config']={'Labels':{'org.heyi.test-id':identity}}
    config_raw=json.dumps(config).encode();manifest=json.loads(blobs[image['manifestDigest']])
    manifest['config'].update(digest=oci.digest(config_raw),size=len(config_raw))
    manifest_raw=json.dumps(manifest).encode();layer=next(v for k,v in blobs.items() if k not in metadata)
    image.update(manifestDigest=oci.digest(manifest_raw),configDigest=oci.digest(config_raw),compressedBytes=len(config_raw)+len(layer))
    blobs={oci.digest(raw):raw for raw in (manifest_raw,config_raw,layer)}
    import base64
    metadata={oci.digest(raw):base64.b64encode(raw).decode() for raw in (manifest_raw,config_raw)}
    root,descriptors,_=transfer.validate_metadata(image,metadata)
    reference=image['repository']+'@'+image['manifestDigest'];container='heyi-transfer-probe-'+identity
    def docker(*args):return subprocess.run(['docker',*args],check=True,capture_output=True,timeout=120).stdout
    try:
        with tempfile.TemporaryDirectory(prefix='heyi-oci-probe-') as temporary:
            folder=Path(temporary)
            transfer.reconstruct(image,root,descriptors,lambda d:io.BytesIO(blobs[d]),folder/'full.tar')
            docker('image','load','--input',str(folder/'full.tar'))
            first=json.loads(docker('image','inspect',reference))[0]
            # Preserve the old root/config, receive only the actual layer bytes.
            cached={k:v for k,v in blobs.items() if k in metadata}
            missing=transfer.plan_missing(descriptors,lambda d:io.BytesIO(cached[d]) if d in cached else None)
            transfer.receive_blobs(folder/'received',io.BytesIO(layer),missing,missing)
            def opened(d):return io.BytesIO(cached[d]) if d in cached else (folder/'received'/d[7:]).open('rb')
            transfer.reconstruct(image,root,descriptors,opened,folder/'delta.tar')
            docker('image','load','--input',str(folder/'delta.tar'))
            second=json.loads(docker('image','inspect',reference))[0]
            assert first['Id']==second['Id']
            assert first.get('Descriptor',{}).get('digest')==image['manifestDigest'] or reference in first.get('RepoDigests',[])
            docker('container','create','--name',container,reference,'/hello.txt')
            print(json.dumps({'probe':'passed','manifestDigest':image['manifestDigest'],'configDigest':image['configDigest'],
                'logicalBlobBytes':sum(d['size'] for d in descriptors),'deltaUploadBytes':sum(d['size'] for d in missing),
                'fullAndDeltaImageIdEqual':True,'businessVolumes':False}))
    finally:
        subprocess.run(['docker','container','rm',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
        subprocess.run(['docker','image','rm',reference],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)


if __name__=='__main__':main()
