#!/usr/bin/env python3
"""Export hashes of an already published source tag for read-only host adoption."""
import argparse
import json
import subprocess
from pathlib import Path
import release


def main(version,output):
    if not release.VERSION.fullmatch(version):raise ValueError('Formal baseline version required')
    inventory=release.inventory(version)
    files={}
    for line in release.run('git','ls-tree','-r',version).splitlines():
        mode,kind,rest=line.split(' ',2);oid,path=rest.split('\t',1)
        files[path]=release.sha(subprocess.check_output(['git','cat-file','blob',oid],cwd=release.ROOT))
    output.write_text(json.dumps({'version':version,'source':inventory,'files':files},sort_keys=True)+ '\n')
    print('Published source hash contract exported:',version,len(files),'files')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('version');p.add_argument('output',type=Path)
    a=p.parse_args();main(a.version,a.output)
