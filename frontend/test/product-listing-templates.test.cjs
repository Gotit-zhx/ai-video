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
const channels=[
  {logistics_channel_id:100,logistics_channel_name:'Shopee Self Pick-up',enabled:true},
  {logistics_channel_id:200,logistics_channel_name:'Standard Local',enabled:true},
  {logistics_channel_id:300,enabled:false},
];
test('following destination shop enables both open logistics without specifying shipping prices',()=>{
  assert.deepEqual(templates.logisticInfo(channels,{mode:'ALL_ENABLED',channelIds:[]}),
    [{logistic_id:100,enabled:true},{logistic_id:200,enabled:true}]);
});
test('selected logistics explicitly disables the other open channel',()=>{
  assert.deepEqual(templates.logisticInfo(channels,{mode:'SELECTED',channelIds:['200']}),
    [{logistic_id:100,enabled:false},{logistic_id:200,enabled:true}]);
});
test('empty selection, disabled and foreign destination channels block publish preparation',()=>{
  for(const channelIds of [[],['300'],['999']])
    assert.throws(()=>templates.logisticInfo(channels,{mode:'SELECTED',channelIds}));
  assert.throws(()=>templates.logisticInfo([],{mode:'ALL_ENABLED'}));
  assert.throws(()=>templates.logisticInfo(channels,{mode:'INVALID'}));
});
