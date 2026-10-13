#!/usr/bin/env python3
"""Build a self-contained unpacked Chrome extension ZIP and verify manifest assets."""
import argparse
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'manifest.json').read_text())
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, default=root.parent / 'frontend' / 'downloads' / f"hoyee-assistant-{manifest['version']}.zip")
output = parser.parse_args().output
output.parent.mkdir(parents=True, exist_ok=True)
references = [*manifest.get('icons', {}).values(), *manifest['action'].get('default_icon', {}).values(),
              manifest['action']['default_popup'], manifest['background']['service_worker']]
for script in manifest['content_scripts']:
    references.extend(script.get('js', []))
    references.extend(script.get('css', []))
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in ['manifest.json', 'README.md', 'src', 'assets']:
        source = root / name
        for path in ([source] if source.is_file() else sorted(source.rglob('*'))):
            if path.is_file() and path.name != '.DS_Store':
                archive.write(path, 'hoyee-assistant/' + str(path.relative_to(root)))
    assert archive.testzip() is None
    missing = [ref for ref in references if 'hoyee-assistant/' + ref not in archive.namelist()]
    if missing:
        raise ValueError(f'Missing required extension resources: {missing}')
print(f"Packaged v{manifest['version']}: {output} ({len(references)} manifest resources checked)")
