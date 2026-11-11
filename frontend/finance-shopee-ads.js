(function(){
  'use strict';
  const states={READY:'等待采集',SYNCING:'正在采集',SUCCESS:'CPC 窗口已采集',NEEDS_PERMISSION:'Ads 报表权限待开通',NEEDS_AUTHORIZATION:'广告授权待更新',RATE_LIMITED:'限流等待',ERROR:'采集异常',PAUSED:'已暂停',WAITING_SHOP:'等待连接财务店铺',REMOVED:'已移除'};
  function valid(ctx,owner){if(!ctx.allowed()||ctx.company()!==owner||!ctx.canManage()||ctx.isActive&&!ctx.isActive())throw Error('当前公司或管理权限已变化，请重新打开');}
  function render(parent,ctx,compact=false){
    if(compact&&ctx.filters?.platform==='TIKTOK_SHOP')return;
    const {el,button,status}=ctx,box=compact?{n:el('aside',null,parent,'fi-card fi-shopee-ad-status')}:ctx.card(parent,'Shopee 广告授权与采集','独立 Ads Service 授权 · 按官方店铺编号匹配 · ERP 授权保持独立',12);
    box.n.dataset.shopeeAds='';box.n.dataset.updateKey='shopee-ads';
    if(!status||status.loadError){el('p','Shopee 广告状态读取失败；已采集费用仍按原记录展示，授权和覆盖情况待核对。',box.n,'fi-feedback');return;}
    if(!status.configured){el('p','Shopee 广告尚未接入：需先通过 Ads Service 正式审核并由管理员配置独立应用；现有 ERP 授权不能代替广告权限。'+(status.configurationError?' '+status.configurationError:''),box.n,'fi-feedback');}
    else if(!(status.items||[]).length)el('p','Shopee Ads 正式应用已配置，尚无独立广告授权。请在财务店铺管理由店铺管理员完成一次官方授权，之后自动采集。',box.n,'fi-muted');
    else el('p','Shopee 已连接 '+status.items.length+' 家广告店铺；当前只核对官方店铺 CPC 日报，未取得或异常日期不填零。',box.n,'fi-muted');
    const failures=(status.items||[]).filter(item=>['NEEDS_PERMISSION','NEEDS_AUTHORIZATION','RATE_LIMITED','ERROR','WAITING_SHOP','PAUSED'].includes(item.status));
    for(const item of failures)el('p',(states[item.status]||'状态待核对')+'：'+(item.error_message||'未取得的数据继续保留未知'),box.n,'fi-feedback');
    el('p',status.coverageBasis||'官方 expense 为 CPC 消耗；现金、授信、赠款及净额不倒算。',box.n,'fi-muted');
    if(compact){const link=el('a','查看 Shopee 授权与采集状态',box.n);link.href='#finance-shops';return;}
    if(ctx.canManage()&&status.configured)button('生成 Shopee 广告授权链接',box.h||box.n,()=>authorize(ctx),'fi-primary');
    const selected=(status.items||[]).filter(item=>!ctx.filters?.groupId||ctx.filters.groupId==='UNGROUPED'?!ctx.filters?.groupId||!item.group_id:item.group_id===ctx.filters.groupId);
    const rows=selected.map(item=>{
      const controls=el('div',null,null,'fi-actions');
      if(ctx.canManage())button(item.enabled?'暂停':'启用',controls,async b=>{
        const owner=ctx.company();b.disabled=true;
        try{valid(ctx,owner);await ctx.request('/shopee-ads/'+encodeURIComponent(item.external_shop_id),ctx.json('PUT',{organizationId:owner,enabled:!item.enabled}));valid(ctx,owner);ctx.reload();}
        catch(error){if(ctx.company()===owner&&(!ctx.isActive||ctx.isActive())&&b.parentElement?.isConnected)el('p',error.message,b.parentElement,'fi-feedback');}finally{b.disabled=false;}
      });
      const name=ctx.shops?.find(s=>s.id===item.shop_id);const scope=name?.display_name||name?.name||item.shop_name||'待连接对应财务店铺';
      return [scope,item.external_shop_id,item.currency,states[item.status]||'状态待核对',item.error_message||'—',Number(item.records||0)+' 条（已入库）',item.last_success_at?new Date(item.last_success_at).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}):'尚未完成',controls];
    });
    if(rows.length)ctx.table(box.n,['财务店铺','官方店铺 ID','原币','采集状态','提示','已入库记录数','最近完整窗口成功','操作'],rows);
    else el('p','当前范围暂无已授权的 Shopee 广告店铺。',box.n,'fi-empty');
    el('p','首次补齐近 90 天，每小时回查近 7 天、每天回查近 31 天；日记录数不等于全广告产品覆盖。来源明确归店，不需要人工逐笔绑定。',box.n,'fi-muted');
  }
  async function authorize(ctx){
    const owner=ctx.company(),box=ctx.dialog('Shopee 广告独立授权');box.d.dataset.shopeeAuthorization='';
    try{valid(ctx,owner);box.feedback.textContent='正在生成链接…';
      const result=await ctx.request('/shopee-ads/authorize',ctx.json('POST',{organizationId:owner}));valid(ctx,owner);if(!box.d.isConnected)return;
      box.feedback.textContent='';ctx.authorizationLink(box,result);
      ctx.el('p','请由店铺管理员在 Shopee 官方页面确认授权。系统自动按同公司官方店铺 ID 关联并补齐 CPC 日报；不会覆盖原 ERP 授权。',box.body,'fi-muted');
    }catch(error){if(box.d.isConnected&&(!ctx.isActive||ctx.isActive()))box.feedback.textContent=error.message;}
  }
  window.FinanceShopeeAds={render};
})();
