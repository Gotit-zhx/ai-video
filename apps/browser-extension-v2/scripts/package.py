#!/usr/bin/env python3
"""Build the standalone V2 edition using the maintained marketplace and login adapters."""
import argparse
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
legacy = root.parent / 'browser-extension'
name = '赫逸印花采集 v2.0'
prefix = 'hoyee-print-collector-v2/'

def package(output):
    manifest = json.loads((legacy / 'manifest.json').read_text())
    manifest.update(name=name, short_name=name, version='2.0.0',
                    description='从 Shopee 和 TikTok Shop 商品页采集图片并打开赫逸印花套版。')
    manifest['action']['default_title'] = name
    for entry in manifest['content_scripts']:
        entry['js'] = ['src/content/capture-print.js' if path == 'src/content/inject.js' else path for path in entry['js']]
    manifest['host_permissions'] = ['https://hoyee.org.cn/*']
    manifest.pop('optional_host_permissions', None)
    refs = {file for entry in manifest['content_scripts'] for file in entry['js']}
    refs.add(manifest['background']['service_worker'])
    refs.add(manifest['action']['default_popup'])
    refs.update(manifest['icons'].values())
    refs.update(manifest['action']['default_icon'].values())
    files = {}
    for directory in ('src', 'assets'):
        for path in sorted((legacy / directory).rglob('*')):
            if path.is_file() and not path.name.startswith('.'):
                files[path.relative_to(legacy).as_posix()] = path.read_bytes()
    del files['src/content/inject.js']
    files['src/content/capture-print.js'] = (root / 'src/content/capture-print.js').read_bytes()
    popup = 'src/popup/popup.html'
    files[popup] = (files[popup].decode().replace('赫逸商品一键采集', name).replace('商品一键采集</h1>', name + '</h1>')
                    .replace('现在可以在 Shopee 或 TikTok Shop 商品详情和商品卡片上点击“一键采集”。发布仍需回到平台确认。',
                             '在 Shopee 或 TikTok Shop 商品详情页点击“采集印花图 v2.0”。')
                    .replace('/downloads/hoyee-assistant-latest.zip', '/assets/hoyee-print-collector-v2.zip')
                    .replace('采集公开商品字段 · 不自动发布', '采集商品图').encode())
    if not refs <= files.keys():
        raise RuntimeError(f'Missing manifest resources: {sorted(refs-files.keys())}')
    files['manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    files['README.md'] = (root / 'README.md').read_bytes()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w') as archive:
        for path, data in sorted(files.items()):
            info = zipfile.ZipInfo(prefix + path)
            info.external_attr = 0o644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('Invalid extension ZIP')
    print(f'{name} · {len(files)} files · {output}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    package(parser.parse_args().output)
