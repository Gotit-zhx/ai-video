"""Asymmetric off-shoulder extension of the shared open garment shell."""
import numpy as np
from geometry_v3 import build_mesh as supported_mesh, smooth


def build_mesh(params, resolution='preview'):
    p = dict(params)
    slope = p.pop('shoulder_slope', .45)
    cuff_scale = p.pop('low_cuff_scale', 1.35)
    if not np.isfinite(slope) or not .1 <= slope <= .8:
        raise ValueError('shoulder_slope outside supported range')
    m = supported_mesh(p, resolution=resolution)
    v = np.asarray(m['vertices'])
    from geometry_v2 import _structure
    st = _structure(p)
    sleeve_weight = smooth((np.abs(v[:,0]) - p['torso_width']*.5) / p['sleeve_length']) * (v[:,0]<0)
    v[:,1] += (cuff_scale-1) * (v[:,1] - (st['root_center']-p['sleeve_drop'])) * sleeve_weight
    # Shared deformation on welded indices preserves sleeve attachment and all
    # four open loops. Positive X is wearer's left, high shoulder in this source.
    weight = smooth((v[:, 1] / p['body_length'] - .18) / .65)
    v[:, 1] += slope * v[:, 0] * weight
    m['vertices'] = v.tolist()
    m['params']['shoulder_slope'] = float(slope)
    m['params']['low_cuff_scale'] = float(cuff_scale)
    m['geometryVersion'] = 'offshoulder-supported/0.1'
    m['limitations'].append('Asymmetric worn drape and hidden back are inferred; no physical dimensions recovered.')
    return m
