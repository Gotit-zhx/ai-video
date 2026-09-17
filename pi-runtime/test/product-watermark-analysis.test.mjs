import test from 'node:test';
import assert from 'node:assert/strict';
import {validateWatermarkResult,runWatermarkAnalysis,WATERMARK_DETECTION_PROMPT,MAX_WATERMARK_DETECTION_VIEW_BYTES} from '../src/product-watermark-analysis.js';
const detection=(extra={})=>({decision:'ABSENT',confidence:.99,regions:[],...extra});
test('watermark absence is explicit; uncertainty never becomes automatic removal',()=>{
  assert.equal(validateWatermarkResult('WATERMARK_DETECTION_V1',detection()).decision,'ABSENT');
  assert.equal(validateWatermarkResult('WATERMARK_DETECTION_V1',detection({confidence:.7})).decision,'UNCERTAIN');
  for(const confidence of [.9,.96,.98,.989999]) {
    const result=validateWatermarkResult('WATERMARK_DETECTION_V1',detection({confidence}));
    assert.equal(result.decision,'UNCERTAIN');
    assert.match(result.reason,/直接提交生图/);
    assert.doesNotMatch(result.reason,/人工|复核/);
  }
  assert.equal(validateWatermarkResult('WATERMARK_DETECTION_V1',detection({confidence:1})).decision,'ABSENT');
  assert.throws(()=>validateWatermarkResult('WATERMARK_DETECTION_V1',detection({decision:'PRESENT'})));
  assert.throws(()=>validateWatermarkResult('WATERMARK_DETECTION_V1',{decision:'ABSENT'}));
});
test('inconsistent and out-of-frame removal masks are rejected',()=>{
  const regions=[{x:.1,y:.2,width:.3,height:.2}];
  assert.throws(()=>validateWatermarkResult('WATERMARK_DETECTION_V1',detection({regions})));
  assert.equal(validateWatermarkResult('WATERMARK_DETECTION_V1',detection({decision:'PRESENT',regions})).decision,'PRESENT');
  assert.throws(()=>validateWatermarkResult('WATERMARK_DETECTION_V1',detection({decision:'PRESENT',regions:[{x:.9,y:.2,width:.3,height:.2}]})));
  assert.equal(validateWatermarkResult('WATERMARK_DETECTION_V1',detection({decision:'PRESENT',regions:[{x:0,y:0,width:1,height:1}]})).decision,'UNCERTAIN');
});
test('validation cannot pass on residual watermark, altered structure, uncertainty or violations',()=>{
  const valid={valid:true,watermarkRemaining:false,structurePreserved:true,confidence:.98,violations:[]};
  assert.equal(validateWatermarkResult('WATERMARK_VALIDATION_V1',valid).valid,true);
  for(const change of [{watermarkRemaining:true},{structurePreserved:false},{confidence:.7},{violations:['领口变形']}])
    assert.equal(validateWatermarkResult('WATERMARK_VALIDATION_V1',{...valid,...change}).valid,false);
  assert.throws(()=>validateWatermarkResult('WATERMARK_VALIDATION_V1',{valid:true,confidence:.99}));
});
test('detection sends one image and validation requires both images',async()=>{
  const calls=[];
  const request=async(...args)=>{calls.push(args);return JSON.stringify(detection());};
  const input={model:{modelId:'vision',apiKey:'unit-only'},context:{analysisType:'WATERMARK_DETECTION_V1'},image:{mimeType:'image/png',data:'AA=='}};
  const result=await runWatermarkAnalysis(input,request,JSON.parse);
  assert.equal(result.result.decision,'ABSENT');
  assert.equal(calls[0][4].length,1);
  assert.equal(calls[0][6],1_000_000);
  await assert.rejects(runWatermarkAnalysis({...input,context:{analysisType:'WATERMARK_VALIDATION_V1'}},request,JSON.parse));
  assert.equal(calls.length,1);
});
test('model failure is surfaced without automatic repeat billing',async()=>{
  let calls=0;
  await assert.rejects(runWatermarkAnalysis({model:{apiKey:'unit-only'},context:{analysisType:'WATERMARK_DETECTION_V1'},image:{mimeType:'image/png',data:'AA=='}},async()=>{calls++;throw Error('timeout');},JSON.parse),/timeout/);
  assert.equal(calls,1);
});

const input=()=>({model:{modelId:'vision',apiKey:'unit-only'},context:{analysisType:'WATERMARK_DETECTION_V1'},image:{mimeType:'image/png',data:'AA=='}});
const view=()=>({mimeType:'image/jpeg',data:'AQID'});

test('enhanced confirmation sends the original first and one auxiliary view second without treating it as a generated candidate',async()=>{
  const body={...input(),context:{analysisType:'WATERMARK_DETECTION_V1',detectionPass:'ENHANCED_CONFIRMATION'},detectionViews:[view()],candidateImage:{mimeType:'image/webp',data:'Ag=='}};
  const before=structuredClone(body);
  let calls=0;
  const result=await runWatermarkAnalysis(body,async(model,key,prompt,context,attachments,tokens,timeout)=>{
    calls++;
    assert.equal(model,body.model);
    assert.equal(key,'unit-only');
    assert.equal(prompt,WATERMARK_DETECTION_PROMPT);
    assert.equal(JSON.parse(context).detectionPass,'ENHANCED_CONFIRMATION');
    assert.deepEqual(attachments,[{type:'image',mimeType:'image/png',data:'AA=='},{type:'image',mimeType:'image/jpeg',data:'AQID'}]);
    assert.equal(tokens,2000);assert.equal(timeout,90_000);
    return JSON.stringify(detection());
  },JSON.parse);
  assert.equal(calls,1);assert.equal(result.result.decision,'ABSENT');
  assert.deepEqual(body,before);
  for(const wording of ['第1张始终是完整原图','不是生成结果','不是后置复核','增强产生','低对比度','横向排列','不同材质','文字读不清','任一疑点','confidence至少0.99'])
    assert.ok(WATERMARK_DETECTION_PROMPT.includes(wording),wording);
});

test('validation remains a separate two-image contract and does not forward detection views',async()=>{
  const body={...input(),context:{analysisType:'WATERMARK_VALIDATION_V1'},candidateImage:{mimeType:'image/webp',data:'Ag=='},detectionViews:[view()]};
  let calls=0;
  const result=await runWatermarkAnalysis(body,async(model,key,prompt,context,attachments,tokens,timeout)=>{
    calls++;
    assert.deepEqual(attachments,[{type:'image',mimeType:'image/png',data:'AA=='},{type:'image',mimeType:'image/webp',data:'Ag=='}]);
    assert.ok(!prompt.includes('ENHANCED_CONFIRMATION'));
    assert.equal(timeout,1_000_000);
    return JSON.stringify({valid:true,watermarkRemaining:false,structurePreserved:true,confidence:.99,violations:[]});
  },JSON.parse);
  assert.equal(result.result.valid,true);assert.equal(calls,1);
});

for(const [name,detectionViews] of [
  ['non-array',view()],['null',null],['too many',[view(),view()]],['null image',[null]],
  ['unsupported MIME',[{...view(),mimeType:'image/svg+xml'}]],['missing MIME',[{data:'AA=='}]],
  ['missing data',[{mimeType:'image/png'}]],['non-string data',[{...view(),data:123}]],
  ['empty data',[{...view(),data:''}]],['invalid base64',[{...view(),data:'%%%='}]],
  ['data URL',[{...view(),data:'data:image/jpeg;base64,AQID'}]],
  ['decoded bytes over limit',[{...view(),data:Buffer.alloc(MAX_WATERMARK_DETECTION_VIEW_BYTES+1).toString('base64')}]]
]) {
  test(`invalid enhanced detection view ${name} fails before any model call`,async()=>{
    let calls=0;
    await assert.rejects(runWatermarkAnalysis({...input(),detectionViews},async()=>{calls++;return JSON.stringify(detection());},JSON.parse),error=>{
      assert.match(error.message,/直接提交生图/);assert.doesNotMatch(error.message,/人工|复核/);return true;
    });
    assert.equal(calls,0);
  });
}

test('enhanced-confirmation pass cannot silently run without its auxiliary image',async()=>{
  let calls=0;
  await assert.rejects(runWatermarkAnalysis({...input(),context:{analysisType:'WATERMARK_DETECTION_V1',detectionPass:'ENHANCED_CONFIRMATION'}},async()=>{calls++;},JSON.parse),/缺少辅助图/);
  assert.equal(calls,0);
});

test('enhanced-confirmation timeout uses 90 seconds and surfaces once without implicit model retries',async()=>{
  let calls=0;
  const timeoutError=Object.assign(new Error('vision response timed out'),{name:'TimeoutError'});
  await assert.rejects(runWatermarkAnalysis({...input(),context:{analysisType:'WATERMARK_DETECTION_V1',detectionPass:'ENHANCED_CONFIRMATION'},detectionViews:[view()]},async(model,key,prompt,context,attachments,tokens,timeout)=>{
    calls++;
    assert.equal(timeout,90_000);
    assert.equal(attachments.length,2);
    throw timeoutError;
  },JSON.parse),error=>error===timeoutError);
  assert.equal(calls,1);
});

test('auxiliary view at the byte limit is accepted and an empty view list keeps one-image detection',async()=>{
  const data=Buffer.alloc(MAX_WATERMARK_DETECTION_VIEW_BYTES).toString('base64');
  const lengths=[];
  const request=async(model,key,prompt,context,attachments)=>{lengths.push(attachments.length);return JSON.stringify(detection());};
  await runWatermarkAnalysis({...input(),detectionViews:[{mimeType:'image/png',data}]},request,JSON.parse);
  await runWatermarkAnalysis({...input(),detectionViews:[]},request,JSON.parse);
  assert.deepEqual(lengths,[2,1]);
});

test('uncertain detection and broad suspected watermark regions direct the caller to generation',()=>{
  const uncertain=detection({decision:'UNCERTAIN',confidence:1});
  assert.equal(validateWatermarkResult('WATERMARK_DETECTION_V1',uncertain),uncertain);
  const broad=validateWatermarkResult('WATERMARK_DETECTION_V1',detection({decision:'PRESENT',regions:[{x:0,y:0,width:1,height:.3}]}));
  assert.equal(broad.decision,'UNCERTAIN');assert.match(broad.reason,/直接提交生图/);
  const weak=validateWatermarkResult('WATERMARK_DETECTION_V1',detection({decision:'PRESENT',confidence:.8,regions:[{x:0,y:0,width:.2,height:.2}]}));
  assert.equal(weak.decision,'UNCERTAIN');assert.match(weak.reason,/直接提交生图/);
});
