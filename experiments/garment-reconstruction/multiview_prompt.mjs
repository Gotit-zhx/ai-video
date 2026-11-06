// Wearer-relative garment facts are the only orientation inputs. Camera and screen
// directions are derived here, so a per-view paragraph cannot silently swap them.
export const MULTIVIEW_PROMPT_VERSION = 'garment-turnaround-v1';
export const VIEW_ORDER = Object.freeze(['FRONT', 'BACK', 'LEFT', 'RIGHT']);
export const CAMERAS = Object.freeze({
  FRONT: {axis:'+Z', toward:'-Z', wearerRightScreen:'LEFT', frontScreenDirection:null},
  BACK: {axis:'-Z', toward:'+Z', wearerRightScreen:'RIGHT', frontScreenDirection:null},
  LEFT: {axis:'+X', toward:'-X', wearerRightScreen:null, frontScreenDirection:'LEFT'},
  RIGHT: {axis:'-X', toward:'+X', wearerRightScreen:null, frontScreenDirection:'RIGHT'}
});
const COLORS = {PALE_YELLOW:'pale butter-yellow', HEATHER_GRAY:'heather gray', CREAM:'cream', WHITE:'white', BLACK:'black', GRAY:'gray', BLUE:'blue', RED:'red'};
const allowedKeys = new Set(['schemaVersion', 'garment', 'outerColor', 'innerColor', 'highShoulder', 'lowShoulder', 'visibleStrapSide', 'strapHeight', 'sleeve', 'innerHemVisible', 'fit', 'referenceMode', 'views', 'shapeFacts', 'correction']);
const SHAPE_RANGES = {outerWidthOverHeight:[.5,2], waistWidthOverMaxOuterWidth:[.45,1], shoulderHighMinusLowOverOuterHeight:[.01,.5], grayStrapTopBelowHighShoulderRatio:[.001,.3], visibleInnerHemRatio:[.005,.2]};

export function validateMultiViewSpec(spec, {referenceCount} = {}) {
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) throw new Error('Multiview specification is required');
  for (const key of Object.keys(spec)) if (!allowedKeys.has(key)) throw new Error(`Unsupported multiview field: ${key}; use the canonical garment facts, not per-view overrides`);
  if (spec.schemaVersion !== 1 || spec.garment !== 'ASYMMETRIC_LAYERED_TOP') throw new Error('Unsupported multiview garment specification');
  if (!['LEFT', 'RIGHT'].includes(spec.highShoulder) || !['LEFT', 'RIGHT'].includes(spec.lowShoulder) || spec.highShoulder === spec.lowShoulder) throw new Error('High and low shoulders must be opposite wearer-relative sides');
  if (spec.visibleStrapSide !== spec.lowShoulder) throw new Error('Visible underlayer strap must remain on the low wearer-relative shoulder');
  if (spec.strapHeight !== 'BELOW_HIGH_SHOULDER') throw new Error('Strap-top height contradicts the source high-shoulder order');
  if (!Object.hasOwn(COLORS, spec.outerColor) || !Object.hasOwn(COLORS, spec.innerColor)) throw new Error('Use supported color facts without freeform orientation instructions');
  if (spec.sleeve !== 'CAP_DOLMAN' || spec.innerHemVisible !== true || !['RELAXED', 'SEMI_FITTED'].includes(spec.fit)) throw new Error('Unsupported or contradictory layered garment construction');
  if (!['SOURCE_ONLY', 'SOURCE_AND_PREVIOUS_DRAFT'].includes(spec.referenceMode)) throw new Error('A source-image role must be explicit');
  if (spec.correction !== undefined && (spec.correction !== 'SHOULDER_HEIGHT_AND_PROPORTIONS' || spec.referenceMode !== 'SOURCE_AND_PREVIOUS_DRAFT' || !spec.shapeFacts)) throw new Error('Targeted correction requires source plus previous draft and measured shape facts');
  if (referenceCount !== undefined && referenceCount !== (spec.referenceMode === 'SOURCE_ONLY' ? 1 : 2)) throw new Error('Reference-image count does not match the source/draft roles');
  if (!Array.isArray(spec.views) || spec.views.length !== VIEW_ORDER.length || spec.views.some((view, i) => view !== VIEW_ORDER[i])) throw new Error('Four-grid order must be FRONT, BACK, wearer LEFT, wearer RIGHT');
  if (spec.shapeFacts !== undefined) {
    if (!spec.shapeFacts || typeof spec.shapeFacts !== 'object' || Array.isArray(spec.shapeFacts) || Object.keys(spec.shapeFacts).length !== Object.keys(SHAPE_RANGES).length) throw new Error('Numeric shape facts must contain the complete supported measurement set');
    for (const [key,[low,high]] of Object.entries(SHAPE_RANGES)) if (!Number.isFinite(spec.shapeFacts[key]) || spec.shapeFacts[key] < low || spec.shapeFacts[key] > high) throw new Error(`Invalid normalized shape fact: ${key}`);
    if (spec.shapeFacts.grayStrapTopBelowHighShoulderRatio >= spec.shapeFacts.shoulderHighMinusLowOverOuterHeight) throw new Error('Strap top must lie between the low and high shoulder elevations');
  }
  return {valid:true, version:MULTIVIEW_PROMPT_VERSION, wearerTopology:{highShoulder:spec.highShoulder, lowShoulder:spec.lowShoulder, visibleStrapSide:spec.visibleStrapSide, strapHeight:spec.strapHeight}, cameras:CAMERAS, outputStillRequiresConsistencyCheck:true};
}

export function buildMultiViewPrompt(spec, options = {}) {
  validateMultiViewSpec(spec, options);
  const high = spec.highShoulder, low = spec.lowShoulder;
  const frontLow = low === 'RIGHT' ? 'LEFT' : 'RIGHT', backLow = low === 'RIGHT' ? 'RIGHT' : 'LEFT';
  const referenceRole = spec.referenceMode === 'SOURCE_ONLY'
    ? 'Image 1 is the original product photograph and the sole identity source.'
    : 'Image 1 is the original product photograph and the identity authority. Image 2 is only a previous generated draft; its mistakes must not override Image 1 or the canonical physical facts below.';
  const correction = spec.correction ? `
PRIMARY EDIT TASK: Edit the complete four-view sheet in Image 2. Image 1 constrains garment identity and cut proportions only; do not insert its photographed pixels as a view. Preserve the already-correct anatomical camera directions in Image 2, the two cloth layers, cap/dolman armholes, plain colors and the one shoulder strap. Correct these specific errors together in one shared garment:
1. The draft places the inner strap ABOVE the high outer shoulder, especially in side views. Lower the top of its curved ribbon until the fixed shoulder-height order and ratio below are true. Its front and back ends remain connected to the inner layer beneath the low outer neckline; do not leave a floating strip or stretch it down the chest.
2. Match the original outer width/height and waist ratios specified below. Correct the maximum shoulder/cap width and the waist relative to that CORRECTED width; do not interpret this as independently widening the absolute waist. Do not change to a tight slim T-shirt.
3. The draft's side images are vertically taller than its front/back images. All four cells must show the same physical outer H, with the same highest-shoulder and lowest-outer-hem Y coordinates. Keep one orthographic scale and pose, not separate per-view garment resizing. Side horizontal width remains DEPTH, not front width.
Regenerate all four cells as one consistent edited sheet; preserve correct details rather than redesigning each view.` : '';
  const f = spec.shapeFacts;
  const shapeFacts = f ? `
MEASURED SHAPE LOCK FROM ORIGINAL (ratios describe this same garment, not separate view redesign):
- Let H be the OUTER layer's vertical extent, from its highest shoulder to its lowest outer hem. Exclude the exposed inner strap and inner hem from this H. Let W be the outer layer's maximum horizontal extent across its cap sleeves in FRONT/BACK orthographic projection.
- FRONT/BACK W/H = ${f.outerWidthOverHeight}. Keep the top short and broad; do not elongate it into a slim T-shirt. The side view's horizontal width is torso DEPTH, not W; never force the side profile to this front-width ratio.
- The narrowest waist width in FRONT/BACK is ${f.waistWidthOverMaxOuterWidth} times W. Preserve this moderate taper; do not pinch the waist more tightly or add a corset shape.
- The high shoulder is ${f.shoulderHighMinusLowOverOuterHeight} times H above the low shoulder/outer neckline anchor. Preserve the wide shallow U between these unequal endpoints, not a straight diagonal line or a crew neck.
- The exposed inner strap's highest edge is ${f.grayStrapTopBelowHighShoulderRatio} times H BELOW the high outer shoulder. It remains above the low shoulder. Apply this one physical height to every view.
- The exposed inner hem band has approximately ${f.visibleInnerHemRatio} times H visible thickness below the outer hem, with the original shallow hem slope. Use these same layer heights in all cells.
- Match the original ratios within roughly five percent while preserving the actual silhouette. Padding and canvas size do not count as garment dimensions.\n` : '';
  return `Use case: product-mockup. Produce ONE 2x2 technical garment turnaround for coarse reconstruction.
INPUT AUTHORITY: ${referenceRole}${correction}
SINGLE PHYSICAL GARMENT SPECIFICATION (applies unchanged to every view):
- A ${COLORS[spec.outerColor]} asymmetric wide-neck cap/dolman-sleeve outer top and a ${COLORS[spec.innerColor]} inner layer. Preserve the source's jersey-cloth appearance and ${spec.fit === 'RELAXED' ? 'relaxed' : 'softly fitted'} cut. No logos or new graphics.
- All LEFT/RIGHT terms mean the WEARER'S anatomical sides. The wearer's ${high} outer shoulder is HIGH. The wearer's ${low} outer shoulder and neckline are LOW. They are not interchangeable.
- The one exposed inner-layer shoulder strap belongs to the wearer's ${spec.visibleStrapSide} side. Its top stays physically BELOW the high outer shoulder. Never move the strap to the other side or raise it to make it visible.
- The same narrow inner-layer band extends below the outer hem. Keep both layers, one visible strap, original neckline, cap/dolman sleeve openings and waist/hem proportions.
${shapeFacts}
GEOMETRY AND POSE: Construct one unchanged garment on an invisible neutral torso support, with supported chest, a smooth supported back, natural cloth volume and the original natural drape. Preserve neckline, armhole/sleeve and hem openings. No visible person, skin, mannequin, stand or hanger. Rotate only the camera; do not change pose, seam placement, shoulder height, layer ownership or cloth shape between cells. Hidden construction is a conservative hypothesis, not photographed evidence.
ONE COORDINATE SYSTEM: +X is the wearer's LEFT; -X is the wearer's RIGHT; +Y is up; +Z is the FRONT of the torso. All cameras have +Y up, orthographic projection, the same focal target at mid-torso, identical height, scale and vertical registration. No roll, pitch or perspective.
GRID ORDER AND DERIVED CAMERA VIEWS:
1. TOP LEFT FRONT: camera at +Z looking toward -Z. The low ${low} shoulder and exposed strap project to image ${frontLow}.
2. TOP RIGHT BACK: camera at -Z looking toward +Z. The SAME low ${low} shoulder and exposed strap project to image ${backLow}. Physical ownership does not swap when its screen position reverses.
3. BOTTOM LEFT wearer's LEFT side: camera at +X looking toward -X. The FRONT/chest points toward image LEFT and BACK points toward image RIGHT. The near shoulder is the wearer's LEFT. Apply actual near/far occlusion from the same garment.
4. BOTTOM RIGHT wearer's RIGHT side: camera at -X looking toward +X. The FRONT/chest points toward image RIGHT and BACK points toward image LEFT. The near shoulder is the wearer's RIGHT. Apply actual near/far occlusion from the same garment.
CONSISTENCY: Opposite orthographic cameras share compatible horizontally mirrored outer silhouettes. Their textures and near/far occlusion come from one 3D garment; do not swap or move features merely to display them. The same physical high shoulder and strap top retain their Y coordinates in every view, including when a point is occluded. Do not place the inner strap above the high outer shoulder in a side view.
OUTPUT: Four equal square cells, pure white background, one complete garment per cell, identical garment scale and vertical alignment, at least 12 percent margin. No text, labels, panel borders, arrows, floor or ground shadows. Preserve all garment edges without cropping. Coherent physical geometry takes priority over advertising styling.\n`;
}

// A bounded consistency gate for observations extracted from the generated sheet.
// A clean prompt alone is never a passed image/geometry check.
export function checkMultiviewObservations(spec, observations) {
  validateMultiViewSpec(spec);
  const issues = [], highPoints = [], outerHeights = [];
  for (const view of VIEW_ORDER) {
    const row = observations?.[view];
    if (!row || !['highShoulderY', 'strapTopY'].every(key => Number.isFinite(row[key]) && row[key] >= 0 && row[key] <= 1)) {
      issues.push(`${view}: missing normalized shoulder-height observations`); continue;
    }
    // Image Y points down. Strap top must be lower in the image than high shoulder.
    if (row.strapTopY <= row.highShoulderY) issues.push(`${view}: strap is not below the high shoulder`);
    highPoints.push(row.highShoulderY);
    if (CAMERAS[view].frontScreenDirection && row.frontScreenDirection !== CAMERAS[view].frontScreenDirection) issues.push(`${view}: anatomical side-camera orientation is unverified or reversed`);
    if (spec.shapeFacts) {
      if (['FRONT', 'BACK'].includes(view)) {
        for (const [field,target] of [['outerWidthOverHeight',spec.shapeFacts.outerWidthOverHeight], ['narrowestWaistOverMaxOuterWidth',spec.shapeFacts.waistWidthOverMaxOuterWidth]]) {
          if (!Number.isFinite(row[field]) || row[field] <= 0) issues.push(`${view}: missing ${field} observation`);
          else if (Math.abs(row[field]/target-1) > .05) issues.push(`${view}: ${field} differs from the source ratio by more than five percent`);
        }
      }
      const box = row.outerBboxPixels;
      if (Array.isArray(box) && box.length === 4 && box.every(Number.isFinite) && box[3] > box[1]) outerHeights.push(box[3]-box[1]);
      else issues.push(`${view}: missing outer-layer height observation`);
    }
  }
  if (highPoints.length === 4 && Math.max(...highPoints) - Math.min(...highPoints) > 0.03) issues.push('Views do not preserve high-shoulder vertical registration');
  if (outerHeights.length === 4 && Math.max(...outerHeights)/Math.min(...outerHeights) > 1.05) issues.push('Views do not preserve the same outer-layer height within five percent');
  return {status:issues.length ? 'NEEDS_REVIEW' : 'CONSISTENT_OBSERVATIONS', issues, physicalGroundTruth:false, fullGeometryOrMaterialVerified:false};
}
