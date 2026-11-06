"""Run inside detector image with repository mounted at /workspace."""
import json, sys, time
from pathlib import Path
from PIL import Image, ImageDraw
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'services/watermark-detector'))
from local_overlay import detect_overlay, VERSION
root=Path(__file__).resolve().parents[2]
out=root/'output/watermark-custom-qa';out.mkdir(parents=True,exist_ok=True)
results=[]
for name in ['source-original.jpg','extract-0.png','mockup_watermark_removed-2.png']:
    path=root/'output/batch-runs/20260908-pink-cat'/name
    im=Image.open(path).convert('RGB'); start=time.monotonic(); boxes=detect_overlay(im)
    draw=ImageDraw.Draw(im)
    for b in boxes:
        draw.rectangle((b['x']*im.width,b['y']*im.height,(b['x']+b['width'])*im.width,(b['y']+b['height'])*im.height),outline='red',width=3)
    im.save(out/(path.stem+'-candidates.jpg'))
    results.append(dict(fixture=name,version=VERSION,latencyMs=round((time.monotonic()-start)*1000),detections=boxes))
(out/'benchmark.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2))
