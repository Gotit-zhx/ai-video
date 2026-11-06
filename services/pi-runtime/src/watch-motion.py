"""Short temporal contact sheets for watch; original frames remain unchanged."""
import subprocess
from pathlib import Path


def motion_sheet(source, output, timestamp, duration):
    if timestamp >= duration - .05:
        return None
    times = sorted(set(round(min(max(0, duration - .05), timestamp + offset), 3)
                       for offset in (0, .2, .4)))
    if len(times) < 2:
        return None
    args = ['ffmpeg', '-v', 'error', '-y', '-filter_complex_threads', '1']
    for second in times:
        args += ['-ss', str(second), '-i', str(source)]
    filters = [f'[{i}:v]scale=320:320:force_original_aspect_ratio=decrease,pad=320:320:(ow-iw)/2:(oh-ih)/2:color=0x151719,setsar=1[t{i}]'
               for i in range(len(times))]
    filters.append(''.join(f'[t{i}]' for i in range(len(times))) + f'hstack=inputs={len(times)}[out]')
    args += ['-filter_complex', ';'.join(filters), '-map', '[out]', '-frames:v', '1',
             '-q:v', '3', '-threads', '1', str(output)]
    subprocess.run(args, check=True, capture_output=True, timeout=15)
    return {'path': str(Path(output).resolve()), 'timestamps': times}
