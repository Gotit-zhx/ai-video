import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(new URL('../../frontend/incremental-charts.js',import.meta.url),'utf8'),context);
const {update,resize}=context.IncrementalCharts,calls=[];
const chart={setOption:(option,settings)=>calls.push(JSON.parse(JSON.stringify({option,settings})))};
const option=(amount=100)=>({
  animation:true,legend:{data:['订单','结算']},
  xAxis:{type:'category',data:['周一','周二']},yAxis:{type:'value'},
  series:[{name:'订单',type:'line',data:[amount,200]},{name:'结算',type:'line',data:[80,180]}]
});
assert.equal(update(chart,option()),true);
assert.equal(update(chart,option()),false);
assert.equal(calls.length,1,'identical response must not call setOption');
assert.equal(update(chart,option(120)),true);
assert.deepEqual(Object.keys(calls[1].option),['series'],'only changed series is submitted');
assert.equal(calls[1].option.series.length,1);
assert.deepEqual(Object.keys(calls[1].option.series[0]).sort(),['data','id']);
assert.equal(calls[1].option.series[0].data[0].id,calls[0].option.series[0].data[0].id,'data point identity survives value changes');
assert.equal(calls[1].settings.replaceMerge,undefined,'ordinary updates do not replace all series');
const removed=option(120);removed.series.pop();removed.legend.data.pop();update(chart,removed);
assert.deepEqual(calls[2].settings.replaceMerge,['series']);
assert.deepEqual(calls[2].option.series,[{id:'订单:0'}],'removing a series retains the other by id');
const ordered={yAxis:{type:'category',data:['B','A']},series:[{type:'bar',data:[200,100]}]};
const rank={setOption:(option)=>calls.push(JSON.parse(JSON.stringify(option)))};
update(rank,ordered);const first=calls.at(-1).series[0].data;
update(rank,{yAxis:{type:'category',data:['A','B']},series:[{type:'bar',data:[110,200]}]});
assert.equal(calls.at(-1).series[0].data[0].id,first[1].id,'rank changes follow the same item');
let resized=0;
const sized={getWidth:()=>800,getHeight:()=>300,resize:()=>resized++};
resize(sized,{clientWidth:800,clientHeight:300});assert.equal(resized,0);
resize(sized,{clientWidth:900,clientHeight:300});assert.equal(resized,1);
console.log('Incremental chart checks passed: unchanged, changed series, point identity, removal, ranking and resize.');
