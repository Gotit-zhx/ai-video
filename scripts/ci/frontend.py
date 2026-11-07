#!/usr/bin/env python3
"""Shared entry point for the production frontend packager."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).resolve().parents[2]/"frontend/package.py"),run_name="__main__")
