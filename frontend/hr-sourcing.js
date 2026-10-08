/* Job-bound sourcing. Reading/analyzing never sends a greeting. */
(() => {
  const sendStates={queued:'等待助手发送',sending:'发送中',sent:'已打招呼 · 等待对方回复',
    uncertain:'发送结果待核对',failed:'未发送，请核对助手状态',expired:'卡片已过期，本次未发送'};
  function contactStatus(contact){
    if(contact.status!=='queued'||!contact.waitInfo?.reasonCode)return sendStates[contact.status]||contact.status;
    const reasons={REQUEST_BUDGET_WAIT:'请求额度等待',SEND_INTERVAL:'等待发送间隔',JOB_STATUS_WAIT:'等待核实职位开放状态',
      JOB_NOT_OPEN:'职位未开放，暂停发送',RISK_PAUSED:'平台要求暂停，未发送',GREET_QUOTA:'今日招呼额度不足',
      CARD_EXPIRED:'候选人卡片已过期，未发送',DAILY_GREETING_LIMIT:'已达每日 150 次上限，本次未发送'};
    const retry=Number(contact.waitInfo.retryAt)||0;
    return (reasons[contact.waitInfo.reasonCode]||'等待助手发送')+(retry>Date.now()/1000
      ?` · 预计 ${new Date(retry*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})} 继续`:'');
  }
  function matchingScore(item){
    if(Number.isFinite(item.verifiedMatch))return Math.round(Math.max(0,Math.min(100,item.verifiedMatch)));
    const score=Number(item.score)||0;
    const coverage=Number(item.totalWeight)>0?Number(item.knownWeight||0)/Number(item.totalWeight):1;
    return Math.round(Math.max(0,Math.min(100,score*coverage)));
  }
  function requirementLabel(...values){
    return values.map(value=>String(value??'').trim()).find(value=>value&&!/^\d+$/.test(value))||'';
  }
  function rows(value,depth=0){
    if(depth>6||!value)return [];
    if(Array.isArray(value))return value;
    for(const key of ['geekList','recommendList','list','items','data','result','zpData']){
      const found=rows(value[key],depth+1);if(found.length)return found;
    }
    return [];
  }
  function hasMore(value,depth=0){
    if(depth>6||!value||Array.isArray(value)||typeof value!=='object')return null;
    for(const key of ['hasMore','hasNext','hasNextPage']){
      if([false,0,'0','false'].includes(value[key]))return false;
      if([true,1,'1','true'].includes(value[key]))return true;
    }
    for(const key of ['data','result','zpData']){
      const found=hasMore(value[key],depth+1);if(found!==null)return found;
    }
    return null;
  }
  globalThis.HeyiHrSourcing={rows,matchingScore};
  globalThis.createHrSourcing=({root,panel,element:el,button,allowed,navigate,hrApi})=>{
    let selected=null,record=null,active=false,generation=0,busy=false,timer=null,polling=false,pollErrors=0;
    let demands=[],demandLoading=false;
    let finding=false,searchError='',searchProgress='',searchSession=null,pausedSearch=false;
    let criteriaDraft=[],criteriaScope='',criteriaDirty=false;
    const painted=new WeakMap();
    function changed(node,value){
      const signature=JSON.stringify(value);
      if(painted.get(node)===signature)return false;
      painted.set(node,signature);node.replaceChildren();return true;
    }
    const request=async(path,body)=>{
      const result=await api('/api/v1/hr/'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
      if(!result||result.success===false||!Object.hasOwn(result,'data'))throw Error(result?.message||'响应未完整接收，请刷新查看保存结果');
      return result.data;
    };
    const nav=el('div','',panel,'hr-sourcing-nav');
    button('← 返回职位管理',nav,()=>navigate('jobs')).className='hr-text-button';
    const title=el('h2','从职位开始寻源',nav);
    const feedback=el('p','请选择职位卡片里的“AI 寻源”。',panel,'hr-hint');
    feedback.setAttribute('role','status');
    const layout=el('div','',panel,'hr-sourcing-layout');
    const reference=el('section','',layout,'hr-sourcing-card');
    el('span','01 / 寻找条件',reference,'hr-eyebrow');
    el('h3','先确定匹配条件',reference);
    el('p','使用招聘需求池的岗位分析，确认几条关键工作能力，再开始寻找。参考简历可在需求池选填上传。',reference,'hr-hint');
    const demandLabel=el('label','',reference,'hr-field');el('span','招聘需求',demandLabel);
    const demandSelect=el('select','',demandLabel);demandSelect.setAttribute('aria-label','选择本职位招聘需求');
    const profileStatus=el('p','',reference,'hr-hint');
    const criteriaOutput=el('div','',reference,'hr-sourcing-criteria');
    el('h4','匹配条件',criteriaOutput);
    el('p','确认核心方向、关键技能和实战经历。修改文字即可，权重等选项在高级设置中。',criteriaOutput,'hr-hint');
    const criteriaRows=el('div','',criteriaOutput,'hr-sourcing-criteria-rows');
    const advanced=el('details','',criteriaOutput,'hr-sourcing-advanced');
    el('summary','高级设置 · 权重与必需项',advanced);
    const advancedRows=el('div','',advanced,'hr-sourcing-advanced-rows');
    const advancedActions=el('div','',advanced,'hr-actions');
    const addCriterion=button('添加条件',advancedActions,()=>{
      if(criteriaDraft.length>=5)return;
      criteriaDraft.push({text:'',weight:20,required:false,kind:'custom'});dirtyCriteria();renderCriteria(true);
    });addCriterion.className='hr-secondary';
    const resetCriteria=button('恢复 JD 建议',advancedActions,()=>{
      if(busy||running()||!record?.criteria?.suggestedConditions?.length)return;
      criteriaDraft=record.criteria.suggestedConditions.map(row=>({...row}));
      dirtyCriteria();renderCriteria(true);
    });resetCriteria.className='hr-text-button';
    const basicRequirements=el('p','',criteriaOutput,'hr-hint');
    const criteriaActions=el('div','',criteriaOutput,'hr-actions');
    const saveCriteria=button('确认并保存条件',criteriaActions,async()=>{
      const version=record.version;
      const saved=await mutate('sourcing/criteria',{...params(),conditions:criteriaDraft.map(row=>({...row}))});
      if(saved){
        criteriaDirty=false;
        if(record.version!==version)searchSession=null;
        render();renderCriteria(true);
      }
    });saveCriteria.className='hr-primary';
    const criteriaStatus=el('p','',criteriaOutput,'hr-hint');
    const profileOutput=el('div','',reference,'hr-sourcing-profile');
    const find=el('section','',layout,'hr-sourcing-card');
    el('span','02 / 人才寻源',find,'hr-eyebrow');
    el('h3','按已确认条件寻找人才',find);
    el('p','先核对岗位方向与原文依据。关键条件未确认、或信息覆盖不足时保留为待核实，结果供 HR 参考。',find,'hr-hint');
    const controls=el('div','',find,'hr-actions');
    const countLabel=el('label','',controls,'hr-field');el('span','获取人数',countLabel);
    const count=el('select','',countLabel);count.setAttribute('aria-label','获取人数');
    for(const n of [5,10,15,20])el('option',n+' 人',count).value=String(n);
    el('span','匹配 ≥ 80 · 核心条件需核实',controls,'hr-badge hr-sourcing-threshold');
    const findButton=button('一键 AI 获取',controls,findPeople);findButton.className='hr-primary';
    const pauseButton=button('暂停寻找',controls,()=>{pausedSearch=true;pauseButton.disabled=true;});
    pauseButton.className='hr-secondary';pauseButton.hidden=true;
    const refresh=button('刷新结果',controls,()=>reload());refresh.className='hr-secondary';
    const searchStatus=el('p','',find,'hr-hint');
    searchStatus.setAttribute('role','status');searchStatus.setAttribute('aria-live','polite');
    const suggestions=el('div','',find,'hr-sourcing-suggestions');
    const results=el('div','',find,'hr-sourcing-results');
    const diagnostics=el('details','',find,'hr-sourcing-diagnostics');
    el('summary','查看未收录原因',diagnostics);
    const diagnosticRows=el('div','',diagnostics);
    const dialog=el('dialog','',root,'hr-pool-dialog hr-sourcing-greeting');
    const greetingTitle=el('h2','打招呼',dialog);
    const greetingHint=el('p','',dialog,'hr-hint');
    const templateLabel=el('label','',dialog,'hr-field');el('span','选择话术',templateLabel);
    const templates=el('select','',templateLabel);
    const messageLabel=el('label','',dialog,'hr-field');el('span','发送内容',messageLabel);
    const message=el('textarea','',messageLabel);message.rows=6;message.maxLength=1000;
    const greetingError=el('p','',dialog,'hr-error');greetingError.setAttribute('role','alert');
    const greetingActions=el('div','',dialog,'hr-actions');
    const cancel=button('取消',greetingActions,()=>dialog.close());
    const send=button('确认向该候选人发送',greetingActions,sendGreeting);send.className='hr-primary';
    let candidate=null,speeches=[],sending=false;
    const valid=ticket=>active&&ticket===generation&&allowed();
    const stop=()=>{clearInterval(timer);timer=null;};
    const running=()=>record?.profileState==='running'||(
      record?.run?.version===record?.version&&record?.run?.state==='running');
    async function accountCheck(expected){
      const value=(await hrApi('/api/v1/hr/login/status')).data;
      if(value?.state!=='complete'||value.paused||!/^[a-f0-9]{64}$/.test(value.accountKey||''))
        throw Error(value?.error||'请先连接并登录本机招聘助手');
      if(expected&&expected!==value.accountKey)throw Error('当前招聘账号已变化，请从职位管理重新进入');
      return value;
    }
    function params(){return {id:record.id,accountKey:record.accountKey,version:record.version};}
    function dirtyCriteria(){
      criteriaDirty=true;findButton.disabled=true;
      criteriaStatus.textContent='条件已修改，请确认并保存后再寻找。';
    }
    function renderCriteria(force=false){
      const locked=busy||running();
      if(!force&&!changed(criteriaRows,[criteriaScope,locked]))return;
      criteriaRows.replaceChildren();advancedRows.replaceChildren();
      criteriaDraft.forEach((row,index)=>{
        const node=el('div','',criteriaRows,'hr-sourcing-condition');
        const heading=el('div','',node,'hr-sourcing-condition-heading');
        const names={direction:'核心方向',skills:'关键技能',experience:'实战经历',custom:'补充条件'};
        el('strong',`${index+1}. ${names[row.kind]||['核心方向','关键技能','实战经历'][index]||'补充条件'}`,heading);
        const importance=el('span',row.required?'必需':'参考',heading,'hr-badge');
        const text=el('textarea','',node);text.rows=2;text.maxLength=250;text.value=row.text;
        text.setAttribute('aria-label',`匹配条件 ${index+1}`);text.disabled=locked;
        text.addEventListener('input',()=>{row.text=text.value;dirtyCriteria();});
        const options=el('div','',advancedRows,'hr-sourcing-condition-options');
        el('span',`条件 ${index+1}`,options,'hr-hint');
        const label=el('label','权重 ',options);
        const weight=el('input','',label);weight.type='number';weight.min='1';weight.max='100';weight.value=String(row.weight);
        weight.setAttribute('aria-label',`条件 ${index+1} 权重`);weight.disabled=locked;
        weight.addEventListener('input',()=>{
          row.weight=Number(weight.value);dirtyCriteria();
        });
        const requiredLabel=el('label','',options);
        const required=el('input','',requiredLabel);required.type='checkbox';required.checked=row.required;required.disabled=locked;
        required.setAttribute('aria-label',`条件 ${index+1} 必须满足`);
        required.addEventListener('change',()=>{row.required=required.checked;importance.textContent=row.required?'必需':'参考';dirtyCriteria();});
        el('span','必须满足',requiredLabel);
        const remove=button('移除',options,()=>{criteriaDraft.splice(index,1);dirtyCriteria();renderCriteria(true);});
        remove.className='hr-text-button';remove.disabled=locked||criteriaDraft.length===1;
      });
      addCriterion.disabled=locked||criteriaDraft.length>=5;
      resetCriteria.disabled=locked||!record?.criteria?.suggestedConditions?.length;
      saveCriteria.disabled=locked||record?.profileState!=='complete';
      criteriaStatus.textContent=criteriaDirty?'条件已修改，请确认并保存后再寻找。':record?.criteria?.confirmed
        ?'条件已确认。核心条件需有原文依据，已核实的信息权重须覆盖至少 60%。'
        :record?.criteria?.needsReview?'匹配规则已优化，请核对并保存后再寻找；旧结果需重新评估。'
        :'已自动带入 JD 建议，核对并保存后即可开始寻找。';
    }
    function render(){
      title.textContent=record?.jobName||selected?.name||'从职位开始寻源';
      layout.hidden=!record;
      if(!record)return;
      const locked=busy||running();
      demandSelect.disabled=locked||demandLoading;
      findButton.disabled=locked||record.profileState!=='complete'||record.criteria?.confirmed===false||criteriaDirty;
      const analyzing=record.run?.version===record.version&&record.run?.state==='running';
      findButton.textContent=finding?'正在获取…':analyzing?'AI 分析中…':searchSession?'继续寻找':'一键 AI 获取';
      pauseButton.hidden=!finding;pauseButton.disabled=pausedSearch;
      findButton.setAttribute('aria-busy',String(finding||analyzing));
      refresh.disabled=busy;count.disabled=locked;
      profileStatus.textContent=record.sourceDemand?.id
        ?record.profileState==='complete'?`来源：${record.sourceDemand.position} · 需求第 ${record.sourceDemand.version} 版 · ${record.sourceDemand.referenceCount||0} 份参考简历`
          :'需求或 JD 已更新，请在需求池重新生成，再使用最新岗位分析。'
        :record.profileState==='complete'?'当前保留已有画像，可选择需求池分析替换。':'请先在招聘需求池生成岗位分析，再选择使用。';
      const scope=JSON.stringify([record.id,record.version,record.criteria]);
      if(scope!==criteriaScope){
        criteriaScope=scope;criteriaDirty=false;
        const initial=record.criteria?.confirmed===false&&!record.criteria?.needsReview
          &&record.criteria?.suggestedConditions?.length?record.criteria.suggestedConditions:record.criteria?.conditions||[];
        criteriaDraft=initial.map(row=>({...row}));
      }
      basicRequirements.textContent=(record.basicRequirements||[]).length
        ?'岗位要求 · '+record.basicRequirements.map(row=>row.text).join(' · ')+'；资料缺失标为待核实。'
        :'学历、年限按岗位明确要求核验；不限时不额外设置门槛。';
      criteriaOutput.hidden=record.profileState!=='complete';renderCriteria();
      if(changed(profileOutput,[record.profileState,record.profile])&&record.profile?.summary){
        if(record.profileState!=='complete')el('p','以下为旧版画像，重新提炼完成后才可用于寻源。',profileOutput,'hr-hint');
        const details=el('details','',profileOutput,'hr-description');el('summary','查看完整画像与建议 JD',details);
        el('p',record.profile.summary,details,'hr-prose');
        el('p',record.profile.suggestedJd,details,'hr-prose');
        for(const item of record.profile.evidence||[]){
          el('strong',item.requirement,details);el('p',item.evidence,details,'hr-hint');
        }
        for(const item of record.profile.questions||[])el('p','待确认：'+item,details,'hr-hint');
      }
      const run=record.run;
      searchStatus.textContent=run?.state==='running'
        ?`正在分析候选人 · 已等待 ${Math.max(0,Math.floor(Date.now()/1000-run.startedAt))} 秒`
        :run?.state==='failed'?run.error:run?.state==='complete'
        ?`已读取 ${run.pagesRead||1} 页 · 分析 ${run.examined||0} 人 · 初步匹配 ${run.candidates?.length||0} / ${run.wanted} 人 · 资料不足 ${run.needsInfo||0} 人 · 条件未达标 ${run.belowThreshold||0} 人${run.error?' · '+run.error:''}`:'确认条件并选择人数后开始获取。';
      if(run&&run.version!==record.version)searchStatus.textContent+=' 这是旧版画像结果，请重新获取后再联系。';
      if(run?.version===record.version&&run?.stopReason==='no_matches_100')searchStatus.textContent='已分析 100 人，仍无匹配，已停止寻找。请调整并保存匹配条件。';
      suggestions.hidden=run?.version!==record.version||run?.stopReason!=='no_matches_100';
      if(changed(suggestions,run?.suggestions))for(const item of run?.suggestions||[])
        el('p',item,suggestions,'hr-hint');
      searchStatus.className=searchError?'hr-error':'hr-hint';
      if(searchError)searchStatus.textContent=searchError;
      else if(finding&&searchProgress)searchStatus.textContent=searchProgress;
      if(changed(results,[run?.candidates,run?.version,record.version,locked]))for(const item of run?.candidates||[]){
        const card=el('article','',results,'hr-person-card');
        const head=el('div','',card,'hr-card-heading');el('h3',item.name,head);
        el('span',record.criteria?.confirmed===false?'旧条件结果':item.checks
          ?`满足 ${item.checks.filter(check=>check.status==='matched').length} / ${item.checks.length} 条`:`${item.score} · AI 参考`,head,'hr-badge');
        const score=matchingScore(item);
        const scoreBox=el('div','',card,'hr-sourcing-score');
        const scoreHeading=el('div','',scoreBox,'hr-sourcing-score-heading');
        el('span',record.criteria?.confirmed===false||run.version!==record.version?'历史条件评分':'岗位匹配度',scoreHeading);
        const scoreValue=el('div','',scoreHeading,'hr-sourcing-score-value');
        el('strong',String(score),scoreValue);el('span','/ 100',scoreValue);
        const track=el('div','',scoreBox,'hr-sourcing-score-track');
        track.setAttribute('role','meter');track.setAttribute('aria-label','岗位匹配度');
        track.setAttribute('aria-valuemin','0');track.setAttribute('aria-valuemax','100');track.setAttribute('aria-valuenow',String(score));
        el('span','',track).style.width=score+'%';
        el('p','按已核实条件权重计算 · AI 辅助参考',scoreBox,'hr-hint');
        if(item.checks)el('p',`信息覆盖 ${item.coverage??Math.round((item.knownWeight||0)/(item.totalWeight||1)*100)}% · ${item.pendingConditions} 条待核实`,card,'hr-hint');
        el('p',item.summary,card,'hr-prose');
        for(const check of item.basicChecks||[])el('p',check.label+'：'+(
          {matched:'符合',unmatched:'不符合',unknown:'待核实'}[check.status]||'待核实')+' · '+check.evidence,card,'hr-hint');
        if(item.basicRequirementsPending)el('p','历史结果尚未核验学历、年限，请重新获取后再联系。',card,'hr-hint');
        for(const evidence of (item.evidence||[]).slice(0,2))el('p',String(evidence).slice(0,160),card,'hr-hint');
        if(item.checks?.length){
          const details=el('details','',card,'hr-sourcing-checks');
          el('summary','逐条匹配依据',details);
          el('p',`已知条件参考分 ${item.score}。匹配度按全部条件权重计算，待核实部分尚未计入。`,details,'hr-hint');
          for(const [index,check] of item.checks.entries()){
            const labels={matched:'匹配',partial:'部分匹配',unmatched:'明确不符',unknown:'待核实'};
            el('strong',`条件 ${index+1} · ${labels[check.status]||check.status}`,details);
            el('p',check.text+'：'+check.evidence,details,'hr-hint');
            if(check.quote)el('p','原文：'+check.quote,details,'hr-hint');
          }
        }
        else if(item.gaps?.length)el('p','待核实：'+item.gaps.join('；'),card,'hr-hint');
        const actions=el('div','',card,'hr-actions');
        if(item.contact)el('span',item.contact.status==='sent'&&!item.contact.friend_id
          ?'已打招呼 · 正在关联对应会话':contactStatus(item.contact),actions,'hr-hint');
        else{
          const greet=button('打招呼',actions,()=>openGreeting(item));greet.className='hr-primary';
          greet.disabled=locked||record.criteria?.confirmed===false||run.version!==record.version||!item.canGreet;
          if(!item.canGreet&&!item.basicRequirementsPending)el('span','平台未提供首次打招呼参数',actions,'hr-hint');
        }
      }
      diagnostics.hidden=!run?.reviewExamples?.length;
      if(changed(diagnosticRows,run?.reviewExamples))for(const item of run?.reviewExamples||[]){
        el('strong',`${item.name} · ${item.matchState==='needs_info'?'资料不足，需核实':item.score+' 分，未达已确认条件'}`,diagnosticRows);
        for(const check of item.checks||[])el('p',check.text+'：'+check.evidence,diagnosticRows,'hr-hint');
        for(const check of item.basicChecks||[])el('p',check.text+'：'+(
          {matched:'符合',unmatched:'不符合',unknown:'待核实'}[check.status]||'待核实')+' · '+check.evidence,diagnosticRows,'hr-hint');
      }
      const pendingContact=run?.candidates?.some(item=>item.contact&&(
        ['queued','sending'].includes(item.contact.status)||(item.contact.status==='sent'&&!item.contact.friend_id)));
      if((running()||pendingContact)&&!timer){pollErrors=0;timer=setInterval(()=>reload(true),running()?4000:10000);}
      if(!running()&&!pendingContact)stop();
    }
    async function reload(quiet=false){
      if(!active||!record||polling||busy||!allowed())return;
      const ticket=generation,query=params();polling=true;
      try{
        const value=await request('sourcing?'+new URLSearchParams(query));
        if(valid(ticket)){record=value;pollErrors=0;render();}
      }catch(error){if(valid(ticket)){feedback.textContent=error.message;if(quiet&&++pollErrors>=3)stop();}}
      finally{polling=false;}
    }
    async function mutate(path,body){
      if(!record||busy||!allowed())return;
      const ticket=generation;busy=true;feedback.textContent='正在保存…';render();
      try{
        const value=await request(path,body);
        if(valid(ticket)){record=value;feedback.textContent='已保存';return true;}
      }catch(error){if(valid(ticket))feedback.textContent=error.message;}
      finally{if(valid(ticket)){busy=false;render();}}
      return false;
    }
    demandSelect.addEventListener('change',async()=>{
      if(busy||running()||demandLoading||!allowed()||!record)return;
      if(demandSelect.value==='__manage__'){navigate('demands');return;}
      const selectedDemand=demands.find(row=>row.id===demandSelect.value);
      if(!selectedDemand)return;
      if(selectedDemand.aiStatus!=='complete'){
        feedback.textContent='该需求尚未生成最新岗位分析，请在下拉栏选择“新建 / 更新招聘需求”。';return;
      }
      const applied=await mutate('sourcing/demand',{...params(),demandId:selectedDemand.id});
      if(applied){searchSession=null;feedback.textContent='已载入招聘需求池分析，请确认匹配条件。';}
    });
    async function loadDemands(){
      if(!record||!active||!allowed()||demandLoading)return;
      const ticket=generation,id=record.id;demandLoading=true;render();
      try{
        const items=[];let page=1;
        do{
          const value=await request('demands?'+new URLSearchParams({jobId:record.jobId,jobAccountKey:record.accountKey,page}));
          if(!valid(ticket))return;
          if(record.id!==id)return;
          items.push(...(value.items||[]));if(page>=Number(value.pages||1))break;page++;
        }while(valid(ticket));
        demands=items;
        demandSelect.replaceChildren();el('option',items.length?'请选择招聘需求':'本职位暂无招聘需求',demandSelect).value='';
        for(const row of items)el('option',`${row.position} · ${row.date} · ${row.aiStatus==='complete'?'分析已就绪':'待生成最新分析'}`,demandSelect).value=row.id;
        el('option','＋ 新建 / 更新招聘需求',demandSelect).value='__manage__';
        demandSelect.value=record.profileState==='complete'&&items.some(row=>row.id===record.sourceDemand?.id
          &&row.version===record.sourceDemand.version&&row.aiStatus==='complete')?record.sourceDemand.id:'';
      }catch(error){if(valid(ticket))feedback.textContent='招聘需求读取失败：'+error.message;}
      finally{if(valid(ticket)){demandLoading=false;render();}}
    }
    async function waitForAnalysis(ticket,snapshot,value){
      const id=value.run?.id,started=Date.now();
      while(value.run?.state==='running'){
        if(!valid(ticket))return null;
        if(Date.now()-started>360000)throw Error('分析仍未完成，可刷新查看进度；本轮没有重新提交 AI');
        await new Promise(resolve=>setTimeout(resolve,1500));
        if(!valid(ticket))return null;
        value=await request('sourcing?'+new URLSearchParams({...snapshot,runId:id}));
        if(!valid(ticket))return null;
        if(value?.run?.id!==id)throw Error('寻源结果已变化，请刷新核对；本轮已停止');
        record=value;render();
      }
      return value;
    }
    async function findPeople(){
      if(!record||busy||running()||record.profileState!=='complete'||record.criteria?.confirmed===false||criteriaDirty||!allowed())return;
      const ticket=generation,snapshot=params(),jobId=record.jobId;busy=finding=true;searchError='';searchProgress='';render();
      const progress=text=>{searchProgress=text;feedback.textContent=searchStatus.textContent=text;};
      progress('正在确认招聘账号和岗位…');
      pausedSearch=false;pauseButton.disabled=false;
      const desired=Number(count.value);
      if(!searchSession||searchSession.id!==record.id||searchSession.version!==record.version
        ||searchSession.desired!==desired)searchSession={id:record.id,version:record.version,
          desired,requestId:crypto.randomUUID(),candidates:[],seen:new Set(),page:0,batch:1,ended:false,qualified:0,examined:0};
      const session=searchSession,{candidates,seen,requestId}=session;
      let {page,ended,qualified}=session;
      const wait=()=>new Promise(resolve=>setTimeout(resolve,3000));
      async function connected(){
        while(valid(ticket)&&!pausedSearch){
          const value=(await hrApi('/api/v1/hr/login/status')).data;
          const code=value?.hold?.code||value?.failure_code;
          if(value?.paused&&value.reconnecting&&['REQUEST_BUDGET_EXHAUSTED','SESSION_UNAVAILABLE'].includes(code)){
            progress('助手正在等待额度或连接恢复 · 已找到的人选保留');await wait();continue;
          }
          return accountCheck(snapshot.accountKey);
        }
        return null;
      }
      try{
        const account=await connected();if(!account)return;
        if(!account.capabilities?.includes('active-sourcing'))throw Error('请更新招聘助手到 0.1.10 或以上版本');
        // Continue only after a completed batch. Never retry failed AI calls or
        // repeat pages; desktop budgets and risk holds still guard every read.
        for(;qualified<desired&&!pausedSearch;session.batch++){
          const batch=session.batch;
          if(!qualified&&session.examined>=100){searchSession=null;break;}
          const target=Math.min(5,desired-qualified,qualified?5:100-session.examined);
          while(candidates.length<target&&!ended&&!pausedSearch){
            if(!await connected()||!valid(ticket))return;
            const nextPage=page+1;
            if(nextPage>20&&!account.capabilities?.includes('continuous-sourcing'))
              throw Error('请更新招聘助手到 0.1.13，旧版助手限制了推荐页码；当前结果和进度已保留');
            progress(`正在读取本职位推荐 · 第 ${nextPage} 页 · 已匹配 ${qualified} / ${desired} 人`);
            let result;
            try{result=(await hrApi('/api/v1/hr/recommendations?'+new URLSearchParams({jobId,page:nextPage}))).data;}
            catch(error){
              const status=(await hrApi('/api/v1/hr/login/status')).data;
              if(['REQUEST_BUDGET_EXHAUSTED','SESSION_UNAVAILABLE'].includes(status?.hold?.code)&&status.reconnecting){
                progress('等待助手恢复后继续当前页，已找到的人选保留');await wait();continue;
              }
              throw error;
            }
            if(!valid(ticket))return;
            if(result?.ok!==true){
              if(result?.error?.code==='REQUEST_BUDGET_EXHAUSTED'){
                progress('读取额度已用完，等待助手恢复后继续当前页');await wait();continue;
              }
              throw Error(result?.error?.message||'候选人读取失败，本轮已停止');
            }
            page=nextPage;session.page=page;
            let fresh=0;
            for(const item of rows(result).slice(0,40)){
              const info=item.geekCard||item.geekInfo||item.geekBaseInfo||item;
              const id=String(info.encryptGeekId||info.encGeekId||item.encryptGeekId||'');
              if(id&&!seen.has(id)){seen.add(id);candidates.push(item);fresh++;}
            }
            if(!fresh&&hasMore(result)!==false&&rows(result).length)
              throw Error('平台重复返回已读取的人选，已暂停；可继续寻找核对下一页');
            if(!fresh||hasMore(result)===false)ended=session.ended=true;
          }
          if(pausedSearch)break;
          if(!candidates.length)break;
          if(!await connected()||!valid(ticket))return;
          const current=candidates.slice(0,target);
          progress(`已读取 ${current.length} 位候选人 · 第 ${batch} 批 AI 分析 · 已匹配 ${qualified} / ${desired} 人`);
          let value=await request('sourcing/search',{...snapshot,count:desired,candidates:current,requestId,batchIndex:batch,page});
          if(!valid(ticket))return;
          record=value;render();
          value=await waitForAnalysis(ticket,snapshot,value);
          if(!value||!valid(ticket))return;
          record=value;qualified=session.qualified=value.run?.candidates?.length||0;
          if(value.run?.error||value.run?.state==='failed'){
            searchSession=null;
            throw Error(value.run.error||'本批分析未完成，本轮已停止');
          }
          candidates.splice(0,current.length);
          session.examined=value.run?.examined??session.examined+current.length;
          if(value.run?.stopReason==='no_matches_100'||!qualified&&session.examined>=100){
            searchSession=null;
            record.run={...record.run,stopReason:'no_matches_100',suggestions:record.run?.suggestions||[
              '建议保留 2–3 条核心工作能力，将相近岗位经历和可替代技能写清楚，调整后确认保存。']};
            break;
          }
          if(ended&&!candidates.length){session.batch++;break;}
        }
        if(!seen.size)throw Error('BOSS 本次没有返回可用候选人，没有调用 AI');
        feedback.textContent=record.run?.stopReason==='no_matches_100'?'已分析 100 人，仍无匹配，已停止寻找。请调整并保存匹配条件。'
          :qualified>=desired?`已获取 ${qualified} 位初步匹配人选（已知条件 ≥ 80 分）。`
          :`已获取 ${qualified} / ${desired} 人。${pausedSearch?'已暂停，可以继续寻找。':'本职位推荐已读完。'}`;
        if(qualified>=desired||ended&&!candidates.length)searchSession=null;
      }catch(error){if(valid(ticket))searchError=feedback.textContent=(record?.run?.candidates?.length
        ?`已保留 ${record.run.candidates.length} 位匹配人选 · `:'')+error.message;}
      finally{if(valid(ticket)){
        if(pausedSearch)feedback.textContent='已暂停寻找，当前进度保留，可点击继续寻找。';
        busy=finding=false;searchProgress='';render();
      }}
    }
    function fillTemplate(){
      const text=speeches.find(row=>row.id===templates.value)?.content||'您好，我们正在招聘{职位}，看到您的相关经历，希望与您进一步沟通。如果您感兴趣，欢迎回复。';
      message.value=text.replace(/\{姓名\}/g,candidate?.name||'您好').replace(/\{职位\}/g,record?.jobName||'该岗位');
    }
    templates.addEventListener('change',fillTemplate);
    async function openGreeting(item){
      const ticket=generation;
      candidate=item;message.value='';greetingTitle.textContent='向 '+item.name+' 打招呼';
      greetingHint.textContent='正在确认助手连接并加载话术…';greetingError.textContent='';
      send.disabled=true;dialog.showModal();
      try{
        const state=await accountCheck(record.accountKey);
        if(!state.capabilities?.includes('sourcing-credentials-v2'))throw Error('请更新招聘助手到 0.1.12 或以上版本后再打招呼');
        if(!valid(ticket)||!dialog.open)return;
        const data=await request('speeches');if(!valid(ticket)||!dialog.open)return;
        candidate=item;speeches=(data.items||[]).filter(row=>row.active&&['active_greet','general'].includes(row.kind));
        templates.replaceChildren();el('option','自定义话术',templates).value='';
        for(const row of speeches)el('option',row.name,templates).value=row.id;
        templates.value=speeches[0]?.id||'';fillTemplate();
        greetingTitle.textContent='向 '+item.name+' 打招呼';
        const daily=state.dailyGreeting;
        greetingHint.textContent=record.jobName+' · 每日最多 150 次'+(Number.isInteger(daily?.used)
          ?`，今日已用 ${daily.used} 次`:'')+'。发送成功后跟进回复和简历，归入主动邀约。';
        greetingError.textContent='';send.disabled=false;
      }catch(error){if(valid(ticket)&&dialog.open){greetingHint.textContent='尚未发送';greetingError.textContent=error.message;}}
    }
    async function sendGreeting(){
      if(sending||!candidate||!message.value.trim()||!allowed())return;
      const ticket=generation;sending=true;send.disabled=cancel.disabled=true;
      try{
        await accountCheck(record.accountKey);
        if(!valid(ticket)||!dialog.open)return;
        await request('sourcing/greet',{...params(),candidateId:candidate.id,message:message.value.trim(),confirmed:true});
        if(valid(ticket)){dialog.close();feedback.textContent='已确认，等待本机助手按发送间隔执行。';await reload();}
      }catch(error){if(valid(ticket))greetingError.textContent=error.message;}
      finally{sending=false;send.disabled=cancel.disabled=false;}
    }
    async function enter(){
      active=true;const ticket=++generation;busy=!!selected;render();
      if(!selected){feedback.textContent='请返回职位管理，选择职位卡片里的“AI 寻源”。';return;}
      feedback.textContent='正在确认招聘账号和职位…';
      try{
        const state=await accountCheck();
        if(!valid(ticket))return;
        if(!state.capabilities?.includes('job-jd'))throw Error('请更新招聘助手以读取岗位 JD');
        let value=await request('sourcing?'+new URLSearchParams({accountKey:state.accountKey,jobId:selected.id}));
        if(!value||selected.refresh){
          const response=(await hrApi('/api/v1/hr/jobs?'+new URLSearchParams({jobId:selected.id}))).data;
          if(response?.ok!==true)throw Error(response?.error?.message||'岗位 JD 读取失败');
          const jd=globalThis.HeyiHrJobJD.jobDetail(response.data,selected.id);
          await accountCheck(state.accountKey);
          if(!valid(ticket))return;
          value=await request('sourcing/save',{accountKey:state.accountKey,jobId:selected.id,jobName:jd.name||selected.name,
            description:jd.description,education:requirementLabel(jd.education,selected.education),
            experience:requirementLabel(jd.experience,selected.experience)});
        }
        if(valid(ticket)){record=value;selected.refresh=false;feedback.textContent='已关联 '+record.jobName+'，请选择招聘需求池的岗位分析。';await loadDemands();}
      }catch(error){if(valid(ticket)){record=null;feedback.textContent=error.message;}}
      finally{if(valid(ticket)){busy=false;render();}}
    }
    function leave(){active=false;generation++;stop();busy=finding=demandLoading=false;searchProgress='';dialog.close();}
    return {select(row){selected={id:String(row.encryptJobId||row.encJobId||row.encryptId||row.jobId||row.id),
      name:row.jobName||row.name||'',education:requirementLabel(row.degreeName,row.degreeDesc,row.education),
      experience:requirementLabel(row.experienceName,row.workYearDesc,row.experience),refresh:true};record=null;searchError='';},
      enter,leave,reset(){leave();selected=record=candidate=null;searchSession=null;criteriaDraft=[];criteriaScope='';criteriaDirty=false;demands=[];speeches=[];
        searchError='';
        demandSelect.replaceChildren();profileOutput.replaceChildren();results.replaceChildren();templates.replaceChildren();
        criteriaRows.replaceChildren();advancedRows.replaceChildren();diagnosticRows.replaceChildren();
        for(const node of [profileOutput,results,criteriaRows,diagnosticRows])painted.delete(node);
        message.value='';greetingError.textContent=greetingHint.textContent='';
        greetingTitle.textContent='打招呼';feedback.textContent='请从职位管理选择具体职位。';render();}};
  };
})();
