"""Offline reference-specific watermark screening. Never establishes general absence."""
import hashlib
from pathlib import Path
import cv2
import numpy as np
from reference_overlay import detect_reference

VERSION = 'overlay-reference-v2'
FILES = [Path(__file__), Path(__file__).with_name('reference_overlay.py'),
         *sorted(Path(__file__).with_name('references').glob('*'))]
MODEL_SHA = hashlib.sha256(b''.join(p.name.encode()+p.read_bytes() for p in FILES)).hexdigest()

def detect_overlay(image):
    copy = image.convert('RGB')
    copy.thumbnail((1024,1024))
    return detect_reference(np.asarray(copy))
