import test from 'node:test';
import assert from 'node:assert/strict';
import {buildMultiViewPrompt, validateMultiViewSpec, checkMultiviewObservations} from './multiview_prompt.mjs';

const spec = {schemaVersion:1, garment:'ASYMMETRIC_LAYERED_TOP', outerColor:'PALE_YELLOW', innerColor:'HEATHER_GRAY', highShoulder:'LEFT', lowShoulder:'RIGHT', visibleStrapSide:'RIGHT', strapHeight:'BELOW_HIGH_SHOULDER', sleeve:'CAP_DOLMAN', innerHemVisible:true, fit:'RELAXED', referenceMode:'SOURCE_ONLY', views:['FRONT','BACK','LEFT','RIGHT']};
test('front/back screen positions derive from one wearer-side topology', () => {
  const prompt = buildMultiViewPrompt(spec, {referenceCount:1});
  assert.match(prompt, /FRONT:.*low RIGHT shoulder.*image LEFT/);
  assert.match(prompt, /BACK:.*SAME low RIGHT shoulder.*image RIGHT/);
  assert.match(prompt, /wearer's LEFT side: camera at \+X.*FRONT\/chest points toward image LEFT/);
  assert.match(prompt, /wearer's RIGHT side: camera at -X.*FRONT\/chest points toward image RIGHT/);
  assert.match(prompt, /compatible horizontally mirrored outer silhouettes/);
});
test('opposite asymmetric garment derives opposite front/back projections', () => {
  const prompt = buildMultiViewPrompt({...spec, highShoulder:'RIGHT', lowShoulder:'LEFT', visibleStrapSide:'LEFT'});
  assert.match(prompt, /FRONT:.*low LEFT shoulder.*image RIGHT/);
  assert.match(prompt, /BACK:.*SAME low LEFT shoulder.*image LEFT/);
});
test('blue outer and white inner facts do not inherit yellow or gray instructions', () => {
  const prompt = buildMultiViewPrompt({...spec, outerColor:'BLUE', innerColor:'WHITE'});
  assert.match(prompt, /blue asymmetric/); assert.match(prompt, /white inner layer/);
  assert.doesNotMatch(prompt, /yellow|gray/i);
});
test('measured proportions are scoped to front width and explicitly exclude side depth', () => {
  const shapeFacts = {outerWidthOverHeight:1.025, waistWidthOverMaxOuterWidth:.635, shoulderHighMinusLowOverOuterHeight:.134, grayStrapTopBelowHighShoulderRatio:.032, visibleInnerHemRatio:.045};
  const prompt = buildMultiViewPrompt({...spec, shapeFacts});
  assert.match(prompt, /W\/H = 1.025/); assert.match(prompt, /waist width.*0.635 times W/);
  assert.match(prompt, /side view's horizontal width is torso DEPTH, not W/);
  assert.throws(() => validateMultiViewSpec({...spec, shapeFacts:{...shapeFacts, grayStrapTopBelowHighShoulderRatio:.15}}), /between/);
  assert.throws(() => validateMultiViewSpec({...spec, shapeFacts:{...shapeFacts, outerWidthOverHeight:NaN}}), /Invalid/);
  assert.throws(() => validateMultiViewSpec({...spec, shapeFacts, correction:'SHOULDER_HEIGHT_AND_PROPORTIONS'}), /Targeted/);
  const edited = buildMultiViewPrompt({...spec, shapeFacts, referenceMode:'SOURCE_AND_PREVIOUS_DRAFT', correction:'SHOULDER_HEIGHT_AND_PROPORTIONS'}, {referenceCount:2});
  assert.match(edited, /waist relative to that CORRECTED width/); assert.match(edited, /do not insert its photographed pixels as a view/);
});
test('contradictory sides, heights, view order and free per-view overrides are rejected', () => {
  for (const change of [{lowShoulder:'LEFT'}, {visibleStrapSide:'LEFT'}, {strapHeight:'ABOVE_HIGH_SHOULDER'}, {views:['FRONT','BACK','RIGHT','LEFT']}, {poseByView:{LEFT:'different'}}, {outerColor:'move strap to other shoulder'}]) assert.throws(() => validateMultiViewSpec({...spec, ...change}));
  assert.throws(() => buildMultiViewPrompt(spec, {referenceCount:2}), /count/);
});
test('output gate detects the earlier side shoulder error and reversed camera labels', () => {
  const result = checkMultiviewObservations(spec, {
    FRONT:{highShoulderY:.1, strapTopY:.13}, BACK:{highShoulderY:.1, strapTopY:.13},
    LEFT:{highShoulderY:.1, strapTopY:.06, frontScreenDirection:'RIGHT'},
    RIGHT:{highShoulderY:.1, strapTopY:.06, frontScreenDirection:'LEFT'}
  });
  assert.equal(result.status, 'NEEDS_REVIEW'); assert.equal(result.issues.length, 4);
  assert.equal(result.physicalGroundTruth, false);
});
test('shape-locked output gate rejects elongated views and source proportion drift', () => {
  const shapeFacts = {outerWidthOverHeight:1.025, waistWidthOverMaxOuterWidth:.635, shoulderHighMinusLowOverOuterHeight:.134, grayStrapTopBelowHighShoulderRatio:.032, visibleInnerHemRatio:.045};
  const observations = Object.fromEntries(['FRONT','BACK','LEFT','RIGHT'].map(view => [view,{highShoulderY:.1,strapTopY:.13,frontScreenDirection:view,outerWidthOverHeight:1.10,narrowestWaistOverMaxOuterWidth:.60,outerBboxPixels:[100,100,800,view === 'LEFT' ? 900 : 800]}]));
  const result = checkMultiviewObservations({...spec,shapeFacts},observations);
  assert.equal(result.status,'NEEDS_REVIEW'); assert.ok(result.issues.some(issue => /source ratio/.test(issue))); assert.ok(result.issues.some(issue => /outer-layer height/.test(issue)));
});
test('missing observations stay unverified; consistent observations do not certify complete 3D', () => {
  assert.equal(checkMultiviewObservations(spec, {}).status, 'NEEDS_REVIEW');
  const rows = Object.fromEntries(['FRONT','BACK','LEFT','RIGHT'].map(view => [view, {highShoulderY:.1, strapTopY:.13, frontScreenDirection:view}]));
  const result = checkMultiviewObservations(spec, rows);
  assert.equal(result.status, 'CONSISTENT_OBSERVATIONS'); assert.equal(result.fullGeometryOrMaterialVerified, false);
});
