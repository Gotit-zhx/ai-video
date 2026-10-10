const {test}=require('node:test');
const assert=require('node:assert/strict');
const templates=require('../product-listing-templates.js');
test('configured fixed parcels convert consistently without changing SKU, price or facts',()=>{
  assert.deepEqual(templates.fields({enabled:true,weightGrams:200,packageLengthCm:5,packageWidthCm:25,packageHeightCm:25}),
    {weightGrams:'200',packageLengthCm:'5',packageWidthCm:'25',packageHeightCm:'25'});
  assert.deepEqual(templates.fields({enabled:false,weightGrams:999}),{});
  assert.equal(templates.normalize('TIKTOK'),'TIKTOK_SHOP');
  assert.equal(templates.normalize('SHOPEE'),'SHOPEE');
});
