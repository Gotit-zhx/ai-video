// Synthetic advertising data shared by interaction tests and the local visual preview.
export function createAdvertisingFixture(){
  const groups=[{id:'baby',name:'母婴组'},{id:'clothes',name:'服装组'}];
  const shops=[{id:'a',name:'演示菲律宾店',platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',group_id:'baby'},
    {id:'b',name:'演示泰国店',platform:'SHOPEE',currency:'THB',timezone:'Asia/Bangkok',group_id:'clothes'}];
  const rates={PHP:0.1,THB:0.2};
  const facts=Array.from({length:55},(_,i)=>({id:'ad-'+i,kind:'AD',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:1,record_date:'2026-10-09',category:'独立广告消耗',impressions:100,clicks:10,conversions:1,source:'API'}));
  facts.push({id:'ledger-a',kind:'FEE',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:30,record_date:'2026-10-10',category:'GMV Max 广告费用',source:'API'},
    {id:'ledger-b',kind:'FEE',shop_id:'b',group_id:'clothes',platform:'SHOPEE',currency:'THB',amount:20,record_date:'2026-10-11',category:'成交广告费',source:'API'},
    {id:'unassigned',kind:'AD',shop_id:'UNASSIGNED',group_id:'',platform:'TIKTOK_SHOP',currency:'PHP',amount:50,record_date:'2026-10-12',category:'独立广告消耗',impressions:1000,clicks:100,conversions:10,source:'IMPORT'},
    {id:'gmv',kind:'AD_GMV',shop_id:'a',group_id:'baby',platform:'TIKTOK_SHOP',currency:'PHP',amount:999,revenue:1998,record_date:'2026-10-09',category:'GMV Max 专项消耗',conversions:20,source:'API'});
  const accounts=[{advertiser_id:'100001',name:'演示广告账户',shop_id:'a',currency:'PHP',timezone:'Asia/Manila',status:'SUCCESS',last_success_at:'2026-11-06T01:00:00Z',gmv_store_id:'200001',gmv_status:'SUCCESS'}];
  const round=n=>Math.round(n*1e6)/1e6;
  const commission=row=>['达人广告佣金','合作伙伴店铺广告佣金'].includes(row.category);
  function selected(q,partial=false){return facts.filter(row=>(!q.start||row.record_date>=q.start)&&(!q.end||row.record_date<=q.end)
    &&(!q.shopId||row.shop_id===q.shopId)&&(!q.platform||row.platform===q.platform)
    &&(!q.groupId||(q.groupId==='UNGROUPED'?!row.group_id:row.group_id===q.groupId))&&(!q.currency||row.currency===q.currency))
    .map(row=>partial&&row.id==='ad-0'?{...row,amount:null,impressions:null}:{...row});}
  function overview(q,partial=false){
    const available=selected({...q,currency:''},partial);
    const cny=q.displayCurrency==='CNY',currency=q.currency||(cny?'':available[0]?.currency||shops.find(s=>s.id===q.shopId)?.currency||'PHP'),display=cny?'CNY':currency;
    const rows=selected({...q,currency},partial).map(row=>({...row,sourceType:row.kind==='FEE'?'SETTLEMENT':row.kind==='AD'?'INDEPENDENT':'GMV',
      currency:display,amount:row.amount==null?null:round(row.amount*(cny?rates[row.currency]:1)),revenue:row.revenue==null?null:round(row.revenue*(cny?rates[row.currency]:1))}));
    function summary(items){
      const sum=key=>items.some(x=>x[key]!=null)?round(items.reduce((n,x)=>n+(x[key]??0),0)):null;
      const known=items.filter(x=>x.amount!=null).length,amount=sum('amount')??0;
      const value={records:items.length,known_amounts:known,complete:known===items.length,hasRecords:items.length>0,currency:display,amount:known===items.length?amount:null,knownAmount:amount};
      for(const key of ['revenue','impressions','clicks','conversions']){value[key]=sum(key);value[key==='revenue'?'known_revenues':'known_'+key]=items.filter(x=>x[key]!=null).length;}
      return value;
    }
    function breakdown(items,keys){
      const groups=new Map();for(const item of items){const key=keys.map(k=>item[k]||'').join('|');if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);}
      return [...groups.values()].map(list=>({...summary(list.filter(x=>x.kind!=='AD_GMV')),...Object.fromEntries(keys.map(k=>[k,list[0][k]||''])),
        settlement:summary(list.filter(x=>x.kind==='FEE')),commission:summary(list.filter(commission)),independent:summary(list.filter(x=>x.kind==='AD')),gmv:summary(list.filter(x=>x.kind==='AD_GMV'))}));
    }
    const ordinary=rows.filter(x=>x.kind!=='AD_GMV'),independent=rows.filter(x=>x.kind==='AD'),ledger=rows.filter(x=>x.kind==='FEE');
    const performance=summary(independent),ratio=(value,denominator,scale=1)=>value!=null&&denominator>0?round(value/denominator*scale):null;
    const impressions=performance.known_impressions===performance.records?performance.impressions:null;
    const clicks=performance.known_clicks===performance.records?performance.clicks:null;
    const conversions=performance.known_conversions===performance.records?performance.conversions:null;
    Object.assign(performance,{clickThroughRate:ratio(clicks,impressions,100),costPerClick:ratio(performance.amount,clicks),costPerThousand:ratio(performance.amount,impressions,1000),costPerConversion:ratio(performance.amount,conversions),conversionRate:ratio(conversions,clicks,100)});
    const gmv=summary(rows.filter(x=>x.kind==='AD_GMV'));gmv.roi=ratio(gmv.revenue,gmv.amount);
    const trend=[...new Set(ordinary.map(x=>x.record_date))].sort().map(period=>{
      const items=ordinary.filter(x=>x.record_date===period);return {period,AD_TOTAL:summary(items).amount,AD_INDEPENDENT:items.some(x=>x.kind==='AD')?summary(items.filter(x=>x.kind==='AD')).amount:null,AD_SETTLEMENT:items.some(x=>x.kind==='FEE')?summary(items.filter(x=>x.kind==='FEE')).amount:null};
    });
    const advertising={total:summary(ordinary),independent:summary(independent),settlement:summary(ledger),commission:summary(ledger.filter(commission)),unassigned:summary(ordinary.filter(x=>x.shop_id==='UNASSIGNED')),
      shops:breakdown(rows,['shop_id','group_id']),groups:breakdown(rows,['group_id']),categories:breakdown(ordinary,['sourceType','category']),performance,gmv,trend,
      basis:'平台账单广告费与独立广告消耗按各自费用日期汇总；GMV Max 专项不重复相加。',profitBasis:'平台账单广告费已经包含在净结算中，利润只额外扣减独立广告消耗。',coverageNote:'当前为本地模拟验证数据；缺少记录不代表实际投放为零。'};
    return {filters:{...q,currency,displayCurrency:display},shops,groups,currencies:['PHP','THB'],sync:[],fees:[],statuses:[],advertising,
      totals:[{kind:'AD',...summary(independent)},{kind:'AD_GMV',...gmv},{kind:'SETTLEMENT',amount:500,revenue:600,records:1,known_amounts:1,known_revenues:1}],
      ranking:[{shop_id:'a',kind:'SETTLEMENT',amount:500,revenue:600,settled_gross_amount:600,currency:display,records:1}],trend:trend.map(x=>({period:x.period,AD:x.AD_INDEPENDENT})),
      profit:{currency:display,settledGrossAmount:600,platformFees:100,advertising:summary(independent).amount,operatingCost:20,productCost:20,logisticsLabelCost:0,amount:369.5,notes:[],formula:'利润按净结算减独立广告消耗及营业成本计算。'},
      fx:cny?{id:'test-fixed',time:'2026-11-06T00:00:00Z',provider:'模拟汇率'}:null,updatedAt:'2026-11-06T01:00:00Z',basis:'本地模拟验证数据'};
  }
  function records(q,partial=false){
    let rows=selected(q,partial).filter(row=>row.kind===q.kind);
    if(q.numbers){const wanted=new Set(q.numbers.split(','));rows=rows.filter(row=>wanted.has(row.id));}
    const total=rows.length,page=Number(q.page)||1;
    rows=rows.slice((page-1)*50,page*50).map(row=>({...row,external_id:row.id,order_id:row.kind==='FEE'?'order-'+row.id:'',advertiser_id:row.kind==='FEE'?'':'100001',
      cny_amount:row.amount==null?null:round(row.amount*rates[row.currency])}));
    const fields=[['record_date','费用日期','date'],['external_id','记录编号','text'],['shop_id','店铺','text'],['category','费用类别','text'],['currency','原币','text'],['amount','原币金额','money'],['cny_amount','人民币金额','money']].map(([id,label,type])=>({id,label,type}));
    return {items:rows,total,page,pageSize:50,fieldSettings:{view:q.kind,fields,visible:fields.map(f=>f.id),exported:fields.map(f=>f.id)}};
  }
  return {groups,shops,accounts,facts,overview,records};
}
