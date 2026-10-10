"""Cap/dolman pattern: broad arm openings, slanted neckline, smooth supported panels.

The seed is a reusable style family, not a product-specific fitted output.
Its four true boundary loops and shared sewing indices come from the panel kernel.
"""
import json
from pathlib import Path
import numpy as np
from geometry_v3 import DEFAULT_PARAMS
from geometry_offshoulder import build_mesh


def make_seed():
    params = {**DEFAULT_PARAMS, 'torso_width': .82, 'body_length': 1.32, 'depth': .47,
              'sleeve_length': .15, 'sleeve_drop': .06, 'neck_width': .58,
              'neck_depth': .115, 'back_neck_depth': .115, 'cuff_width': .44,
              'hem_flare': -.035, 'body_taper': .10, 'chest_depth': 1.12,
              'waist_depth': 1., 'hem_depth': 1.02, 'shoulder_drop': .10,
              'chest_support': .55, 'back_drape': .55, 'shoulder_slope': .34,
              'low_cuff_scale': 1.07}
    mesh = build_mesh(params, 'preview')
    vertices, faces = np.asarray(mesh['vertices']), np.asarray(mesh['faces'])
    smooth = lambda x: np.clip(x, 0, 1)**2 * (3-2*np.clip(x, 0, 1))
    # The asymmetric neckline does not tilt both cap openings with it. Recover
    # the untwisted panel height, then fade the neckline shear toward the cuffs.
    # Applying a full-body shear was the source of the raised second shoulder
    # peak and the opposite rolled-down sleeve.
    y0 = vertices[:, 1].copy()
    for _ in range(8):
        y0 = vertices[:, 1] - .34*vertices[:, 0]*smooth((y0/params['body_length']-.18)/.65)
    influence = 1-.85*smooth((np.abs(vertices[:, 0])-.29)/(.66-.29))
    vertices[:, 1] = y0+.34*vertices[:, 0]*smooth((y0/params['body_length']-.18)/.65)*influence
    # Allow a gentle oblique hem. Forcing a horizontal hem then fitting its image
    # with row scaling creates a shelf on the raised side.
    vertices[:, 1] -= .10 * vertices[:, 0] * (1-smooth(vertices[:, 1]/.66))
    vertices[:, 1] -= vertices[:, 1].min()
    vertices[:, 1] *= 1.35/vertices[:, 1].max()
    # A slanted shear applied to a rounded ordinary-shirt shoulder creates a
    # second high point outside the neckline. Dolman shoulders descend smoothly
    # from one neckline apex to the cap edge.
    neck_half = params['neck_width']/2
    # Use actual vertical triangle-edge intersections. Seam vertices include
    # underarm points at nearby X: interpolating them as a top edge makes spikes.
    edges = np.unique(np.sort(np.concatenate([faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]]), axis=1), axis=0)
    a, b = vertices[edges[:, 0]], vertices[edges[:, 1]]
    xs = np.linspace(neck_half, vertices[:, 0].max(), 257)
    tops = []
    for x in xs:
        valid = (np.minimum(a[:, 0], b[:, 0]) <= x+1e-7) & (np.maximum(a[:, 0], b[:, 0]) >= x-1e-7)
        aa, bb = a[valid], b[valid]
        t = np.clip((x-aa[:, 0])/np.where(abs(bb[:, 0]-aa[:, 0])>1e-10, bb[:, 0]-aa[:, 0], 1), 0, 1)
        tops.append(np.max(aa[:, 1]+(bb[:, 1]-aa[:, 1])*t))
    tops = np.asarray(tops)
    for i, (x, y, _) in enumerate(vertices):
        if x < xs[0]: continue
        t = np.clip((x-xs[0])/(xs[-1]-xs[0]), 0, 1)
        current_top = np.interp(x, xs, tops)
        desired_top = tops[0]*(1-t)+tops[-1]*t-.018*np.sin(np.pi*t)
        vertices[i, 1] += np.clip(desired_top-current_top, -.16, 0)*smooth((y-current_top+.30)/.30)
    vertices[:, 1] *= 1.35/vertices[:, 1].max()
    # Cap/dolman armholes are ovals, not the large round tube end of a T-shirt.
    for name in ['left_cuff', 'right_cuff']:
        for i in mesh['openings'][name]: vertices[i, 2] *= .50
    protected = set(mesh['semantic_regions']['seams']) | {i for loop in mesh['openings'].values() for i in loop}
    front = set(mesh['semantic_regions']['front'])
    neighbors = [set() for _ in vertices]
    for triangle in faces:
        for a, b in zip(triangle, np.roll(triangle, -1)):
            neighbors[a].add(b); neighbors[b].add(a)
    x, y = np.abs(vertices[:, 0]), vertices[:, 1]
    t = np.clip(y/(1.35*.6), 0, 1)
    half = .41*(1+params['hem_flare']*(1-t)-params['body_taper']*np.sin(np.pi*t))
    half += .23*smooth((y/1.35-.45)/.2)
    target = .235*np.sqrt(np.maximum(0, 1-(x/half)**2))
    target *= np.array([1.04 if i in front else -.95 for i in range(len(vertices))])
    # Screened smoothing in depth only, with actual openings and sewing edges
    # held exactly. This removes the earlier artificial sleeve/body troughs.
    z = vertices[:, 2].copy()
    for _ in range(350):
        next_z = z.copy()
        for i, ns in enumerate(neighbors):
            if i in protected: continue
            next_z[i] = (.05*target[i]+sum(z[j] for j in ns)/len(ns))/1.05
        z = next_z
    vertices[:, 2] = z
    mesh['vertices'] = vertices.round(7).tolist()
    mesh['params']['body_length'] = 1.35
    mesh['geometryVersion'] = 'cap-dolman-asymmetric-panels/1.6'
    mesh['cageSmoothSigma'] = 10
    mesh['provenance'] = 'Reusable cap/dolman sewing pattern with wide neckline and large diagonal armholes; not the earlier gray-product fitted seed.'
    mesh['support'] = 'Smooth screened elliptic panels; openings and sewing boundaries pinned; no random wrinkle displacement.'
    return mesh


if __name__ == '__main__':
    import sys
    path = Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).parents[1]/'frontend/vendor/three/garment-seed-dolman.json'
    mesh = make_seed()
    payload = {k: mesh[k] for k in ['vertices', 'faces', 'openings', 'semantic_regions', 'params', 'geometryVersion', 'cageSmoothSigma', 'provenance', 'support']}
    path.write_text(json.dumps(payload, separators=(',', ':')))
    print(json.dumps({'path': str(path), 'vertices': len(mesh['vertices']), 'faces': len(mesh['faces'])}))
