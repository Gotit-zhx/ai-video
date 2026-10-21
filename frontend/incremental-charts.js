/* Keep chart instances and send only changed components/series to ECharts. */
(() => {
  'use strict';
  const snapshots=new WeakMap(),unchanged=Symbol('unchanged');
  const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
  const functions=value=>typeof value==='function'||(value!==null&&typeof value==='object'&&Object.values(value).some(functions));
  const signature=value=>JSON.stringify(value,(_key,item)=>typeof item==='function'?String(item):item);
  function copy(value){
    if(Array.isArray(value))return value.map(copy);
    if(object(value))return Object.fromEntries(Object.entries(value).map(([key,item])=>[key,copy(item)]));
    return value;
  }
  function difference(before,after){
    if(signature(before)===signature(after))return unchanged;
    if(object(before)&&object(after)){
      const patch={};
      for(const key of new Set([...Object.keys(before),...Object.keys(after)])){
        const change=difference(before[key],after[key]);
        if(change!==unchanged)patch[key]=change;
      }
      return patch;
    }
    return after===undefined?null:after;
  }
  function identified(option){
    const next=copy(option),names=new Map();
    next.series=(next.series||[]).map((series,index)=>{
      const key=series.name||`series-${index}`,occurrence=names.get(key)||0;names.set(key,occurrence+1);
      series.id??=`${key}:${occurrence}`;
      const x=Array.isArray(next.xAxis)?next.xAxis[series.xAxisIndex||0]:next.xAxis;
      const y=Array.isArray(next.yAxis)?next.yAxis[series.yAxisIndex||0]:next.yAxis;
      const labels=x?.type==='category'?x.data:y?.type==='category'?y.data:null;
      const seen=new Map();
      if(Array.isArray(series.data))series.data=series.data.map((item,i)=>{
        if(Array.isArray(item))return item;
        const name=object(item)?item.name??labels?.[i]:labels?.[i];
        if(name==null)return item;
        const label=String(name),count=seen.get(label)||0;seen.set(label,count+1);
        return {name:label,...(object(item)?item:{value:item}),id:object(item)&&item.id!=null?item.id:`${label}:${count}`};
      });
      return series;
    });
    return next;
  }
  function update(instance,option){
    const next=identified(option),previous=snapshots.get(instance);
    if(!previous){instance.setOption(copy(next),{lazyUpdate:true});snapshots.set(instance,next);return true;}
    const patch={},replaceMerge=[];
    for(const key of new Set([...Object.keys(previous),...Object.keys(next)])){
      if(key==='series')continue;
      const change=difference(previous[key],next[key]);
      if(change!==unchanged){
        patch[key]=change;
        if(Array.isArray(next[key])&&Array.isArray(previous[key])&&next[key].length<previous[key].length
          &&['xAxis','yAxis','dataZoom','visualMap','legend'].includes(key))replaceMerge.push(key);
      }
    }
    const oldSeries=new Map(previous.series.map(series=>[series.id,series]));
    const removed=previous.series.some(series=>!next.series.some(item=>item.id===series.id));
    const changes=next.series.flatMap(series=>{
      const change=difference(oldSeries.get(series.id),series);
      // Retain unchanged series by id when another series has been removed.
      return change===unchanged?(removed?[{id:series.id}]:[]):[{...change,id:series.id}];
    });
    if(removed)replaceMerge.push('series');
    if(changes.length||removed)patch.series=changes;
    if(!Object.keys(patch).length)return false;
    // Refresh formatter closures only when this chart actually changed.
    if(functions(next.tooltip))patch.tooltip=next.tooltip;
    instance.setOption(patch,{lazyUpdate:true,...(replaceMerge.length?{replaceMerge}:{})});
    snapshots.set(instance,next);return true;
  }
  function resize(instance,node){
    const width=node.clientWidth,height=node.clientHeight;
    if(width>0&&height>0&&(instance.getWidth()!==width||instance.getHeight()!==height))instance.resize();
  }
  globalThis.IncrementalCharts={update,resize};
})();
