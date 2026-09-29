(() => {
  const request=async(path,body)=>{
    const response=await api('/api/v1/hr/'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
    return response.data;
  };
  const sendStates={queued:'等待助手发送',sending:'发送中',sent:'已发送',uncertain:'结果待核对，请查看 BOSS 会话',failed:'发送未完成',cancelled:'已取消发送'};
  const statuses={pending:'待审批',approved:'已通过',rejected:'未通过'};
  const phases={watching:'正在等待新消息',reply_queued:'已发现主动消息，等待话术回复',
    request_queued:'已回复，等待索取简历',receiving:'已索取，等待接收简历',
    template_required:'请在话术管理中配置默认被动邀约回复',chat_required:'正在准备 BOSS 沟通页面',
    budget_wait:'正在按请求频率排队，会自动继续',send_wait:'正在按发送间隔排队',
    needs_review:'有发送结果待核对，请查看 BOSS 会话',disabled:'被动邀约已关闭'};
  const eventLabels={INCOMING_DETECTED:'收到主动消息',AUTO_REPLY_SENT:'话术回复已发送',
    AUTO_REPLY_UNCERTAIN:'话术回复结果待核对',RESUME_REQUESTED:'已索取简历',
    RESUME_REQUEST_UNCERTAIN:'索取结果待核对',RESUME_RECEIVED:'已收到简历'};
  function flowSummary(flow,ruleId){
    const counts={};
    for(const group of ruleId?[flow.counts?.[ruleId]||{}]:Object.values(flow.counts||{})){
      for(const [key,value] of Object.entries(group))counts[key]=(counts[key]||0)+value;
    }
    const totals=[['inspect','待处理'],['reply_ready','待回复'],['ready','待索取'],['requested','待接收'],['stored','已入池'],
      ['reply_uncertain','回复待核对'],['request_uncertain','索取待核对'],['request_failed','索取未完成']]
      .filter(([key])=>counts[key]).map(([key,label])=>`${label} ${counts[key]}`);
    const clock=value=>new Date(value*1000).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false});
    if(flow.phase==='budget_wait'&&Number.isFinite(flow.retryAt)){
      totals.push(flow.retryAt*1000>Date.now()?`预计 ${clock(flow.retryAt)} 继续`:'正在重新检查可用额度');
    }
    if(flow.lastProgressAt>0)totals.push(`上次进展 ${clock(flow.lastProgressAt)}`);
    return [phases[flow.phase]||'已开启，等待助手检查',...totals].join(' · ');
  }
  globalThis.createHrPassive=({root,section,element:el,button,allowed,navigate,hrApi})=>{
    let active='',generation=0,items=[],page=1,filter='',selected=null,timer=null,poolTimer=null;
    const speechPanel=section('speeches'),resumePanel=section('resume-pool');
    const toolbar=(panel,title,subtitle)=>{
      const head=el('div','',panel,'hr-pool-toolbar'),intro=el('div','',head);
      el('h2',title,intro);el('p',subtitle,intro,'hr-hint');
      return el('div','',head,'hr-actions');
    };
    const speechActions=toolbar(speechPanel,'话术管理','配置被动邀约的首次回复与审批后的婉拒话术。自动回复确认成功后，才会继续索取简历。');
    button('刷新',speechActions,load);
    button('新建话术',speechActions,()=>editSpeech()).className='hr-primary';
    const speechFeedback=el('p','',speechPanel,'hr-hint'),speechRows=el('div','',speechPanel,'hr-result');
    const resumeActions=toolbar(resumePanel,'简历池','先看简历，再做决定。AI 辅助分析由 HR 自行选择，仅供参考。');
    const statusSelect=el('select','',resumeActions);
    for(const [value,label] of [['','全部'],...Object.entries(statuses)])el('option',label,statusSelect).value=value;
    statusSelect.setAttribute('aria-label','审批状态');
    statusSelect.onchange=()=>{filter=statusSelect.value;page=1;load();};
    button('刷新',resumeActions,load);
    const poolActivity=el('p','',resumePanel,'hr-hint');
    poolActivity.setAttribute('aria-live','polite');
    const resumeFeedback=el('p','',resumePanel,'hr-hint'),resumeRows=el('div','',resumePanel,'hr-result');
    const speechDialog=el('dialog','',root,'hr-pool-dialog'),speechForm=el('form','',speechDialog);
    const speechTitle=el('h2','新建话术',speechForm),fields=el('div','',speechForm,'hr-pool-fields');
    function field(parent,label,tag='input'){
      const wrap=el('label','',parent,'hr-field');el('span',label,wrap);return el(tag,'',wrap);
    }
    const name=field(fields,'名称');name.required=true;name.maxLength=80;
    const kind=field(fields,'用途','select');
    for(const [value,label] of [['passive_reply','被动邀约回复'],['reject','婉拒'],['invite','面试邀约'],['general','其他沟通']])el('option',label,kind).value=value;
    const content=field(speechForm,'话术正文','textarea');content.required=true;content.maxLength=1000;content.rows=7;
    el('p','支持 {姓名}、{职位}。被动邀约先发送默认回复话术，成功后再使用 BOSS 官方“索取简历”操作。',speechForm,'hr-hint');
    const enabled=field(speechForm,'启用');enabled.type='checkbox';
    const isDefault=field(speechForm,'设为该用途的默认话术');isDefault.type='checkbox';
    kind.onchange=()=>{isDefault.disabled=!['reject','passive_reply'].includes(kind.value);if(isDefault.disabled)isDefault.checked=false;};
    const speechError=el('p','',speechForm,'hr-error');speechError.setAttribute('role','alert');
    const editActions=el('div','',speechForm,'hr-actions');
    button('取消',editActions,()=>speechDialog.close());
    const save=el('button','保存话术',editActions,'hr-primary');save.type='submit';
    let editing=null,saving=false;
    function editSpeech(row={}){
      editing=row;speechTitle.textContent=row.id?'编辑话术':'新建话术';
      name.value=row.name||'';kind.value=row.kind||'passive_reply';content.value=row.content||'';
      enabled.checked=row.active!==false;isDefault.checked=!!row.isDefault;kind.onchange();
      speechError.textContent='';speechDialog.showModal();
    }
    speechForm.onsubmit=async event=>{
      event.preventDefault();if(saving||!allowed())return;saving=true;save.disabled=true;
      const ticket=generation;
      try{
        await request('speeches/save',{id:editing.id,version:editing.version,name:name.value.trim(),
          kind:kind.value,content:content.value.trim(),active:enabled.checked,isDefault:isDefault.checked});
        if(ticket!==generation||!allowed())return;
        speechDialog.close();await load();
      }catch(error){if(ticket===generation)speechError.textContent=error.message;}
      finally{saving=false;save.disabled=false;}
    };
    const detail=el('dialog','',root,'hr-pool-dialog hr-resume-review'),detailBody=el('div','',detail);
    button('关闭',el('div','',detail,'hr-actions'),()=>{detail.close();selected=null;clearTimeout(timer);});
    detail.addEventListener('close',()=>{selected=null;clearTimeout(timer);});
    function link(parent,text,url){const a=el('a',text,parent,'hr-file-link');a.href=url;a.target='_blank';a.rel='noopener';return a;}
    async function refreshPoolActivity(){
      if(active!=='resume-pool'||!allowed())return;const ticket=generation;
      try{
        const status=await hrApi('/api/v1/hr/login/status');
        if(ticket!==generation||active!=='resume-pool'||!allowed())return;
        poolActivity.textContent=status.data.state==='complete'&&!status.data.paused
          ?flowSummary(status.data.passive||{}):'助手未连接，自动邀约暂停';
      }catch{if(ticket===generation&&active==='resume-pool')poolActivity.textContent='正在等待助手恢复连接';}
    }
    async function load(){
      if(!active||!allowed())return;const ticket=++generation,mode=active;
      const feedback=mode==='speeches'?speechFeedback:resumeFeedback,box=mode==='speeches'?speechRows:resumeRows;
      feedback.textContent='正在读取…';
      try{
        const data=await request(mode==='speeches'?'speeches':`resume-pool?page=${page}&status=${filter}`);
        if(ticket!==generation||mode!==active||!allowed())return;
        items=data.items||[];box.replaceChildren();feedback.textContent=`共 ${data.total??items.length} 条`;
        if(!items.length)el('p',mode==='speeches'?'还没有话术，请配置默认被动邀约回复和默认婉拒话术。':'暂无简历。启用职位的被动邀约后，收到的简历会在这里归档。',box,'hr-pool-empty');
        const grid=el('div','',box,'hr-card-grid');
        for(const row of items){
          const card=el('article','',grid,'hr-person-card'),head=el('div','',card,'hr-card-heading');
          el('h3',row.name||'姓名待补充',head);
          if(mode==='speeches'){
            el('span',row.isDefault?(row.kind==='passive_reply'?'默认被动回复':'默认婉拒'):row.active?'启用':'停用',head,'hr-badge');
            el('p',row.content,card,'hr-prose');
            button('编辑话术',el('div','',card,'hr-actions'),()=>editSpeech(row));
          }else{
            el('span',statuses[row.status]||row.status,head,'hr-badge');
            el('p',row.jobName,card);el('p',[row.education,row.experience,row.city].filter(Boolean).join(' · ')||'详情见简历原件',card,'hr-hint');
            if(row.rejection)el('p','婉拒：'+(sendStates[row.rejection.status]||row.rejection.status),card,'hr-hint');
            if(row.aiState==='complete')el('p',`AI 参考匹配度 ${row.aiResult.score} / 100`,card,'hr-hint');
            button('查看与审批',el('div','',card,'hr-actions'),()=>openResume(row.id)).className='hr-primary';
          }
        }
        if(mode==='resume-pool'&&data.pages>1){
          const pages=el('div','',box,'hr-actions');
          button('上一页',pages,()=>{page--;load();}).disabled=data.page<=1;
          el('span',`${data.page} / ${data.pages}`,pages);
          button('下一页',pages,()=>{page++;load();}).disabled=data.page>=data.pages;
        }
        if(mode==='resume-pool'&&data.events?.length){
          el('h3','被动邀约记录',box);
          for(const event of data.events){
            const label=eventLabels[event.code];if(!label)continue;
            el('p',[event.name,event.jobName,label,new Date(event.createdAt).toLocaleString()].filter(Boolean).join(' · '),box,'hr-hint');
          }
        }
      }catch(error){if(ticket===generation)feedback.textContent=error.message;}
    }
    async function openResume(id){
      const ticket=generation;selected=id;detailBody.replaceChildren();el('p','正在读取简历…',detailBody,'hr-hint');
      if(!detail.open)detail.showModal();
      try{
        const [data,speeches,projects]=await Promise.all([request('resume-pool?id='+encodeURIComponent(id)),request('speeches'),request('pool')]);
        if(ticket!==generation||selected!==id||!allowed()||!detail.open)return;
        const row=data.items[0];if(!row)throw Error('简历不存在');
        drawResume(row,speeches.items.find(s=>s.isDefault&&s.active&&s.kind==='reject'),projects.projects||[]);
      }catch(error){if(ticket===generation&&selected===id){detailBody.replaceChildren();el('p',error.message,detailBody,'hr-error');}}
    }
    function drawResume(row,template,projects){
      detailBody.replaceChildren();
      el('h2',row.name||'候选人简历',detailBody);
      el('p',`${row.jobName} · ${statuses[row.status]}`,detailBody,'hr-hint');
      const files=el('div','',detailBody,'hr-actions');
      link(files,['pdf','png','jpg','jpeg'].includes(row.extension)?'查看简历原件':'下载 Word 原件',row.fileUrl);
      if(['pdf','png','jpg','jpeg'].includes(row.extension)){
        const preview=el('details','',detailBody,'hr-description');
        el('summary','展开原件预览',preview);
        preview.addEventListener('toggle',()=>{
          if(!preview.open||preview.children.length>1)return;
          const asset=el(row.extension==='pdf'?'iframe':'img','',preview,'hr-resume-original');
          asset.title='简历原件';asset.src=row.fileUrl;if(asset.tagName==='IMG')asset.alt='简历原件';
        });
      }
      el('p',[row.education,row.experience,row.city,row.company].filter(Boolean).join(' · '),detailBody,'hr-hint');
      const ai=el('section','',detailBody,'hr-review-ai');
      el('h3','AI 辅助分析匹配度',ai);el('p','可选，仅供参考。AI 不参与审批，也不会触发发送消息。',ai,'hr-hint');
      el('p',row.extractionNote||'',ai,'hr-hint');
      const textDetails=el('details','',ai);el('summary','查看或补充用于分析的简历文字',textDetails);
      const resumeText=field(textDetails,'简历正文','textarea');resumeText.value=row.resumeText||'';resumeText.rows=6;resumeText.maxLength=60000;
      const jobText=field(textDetails,'岗位职责与要求','textarea');jobText.value=row.jobDescription||'';jobText.rows=4;jobText.maxLength=10000;
      const aiFeedback=el('p',row.aiError||'',ai,'hr-hint'),aiActions=el('div','',ai,'hr-actions');
      const analyze=button(row.aiState==='running'?'分析中…':row.aiState==='complete'?'重新分析':'AI 辅助分析',aiActions,async()=>{
        analyze.disabled=true;
        try{
          await request('resume-pool/analyze',{id:row.id,resumeText:resumeText.value,jobDescription:jobText.value,regenerate:row.aiState==='complete'});
          if(selected===row.id&&allowed())await openResume(row.id);
        }catch(error){if(selected===row.id)aiFeedback.textContent=error.message;analyze.disabled=false;}
      });
      analyze.disabled=row.aiState==='running';
      if(row.aiState==='running'){
        aiFeedback.textContent='正在使用驱动模型分析，您仍可直接审批简历。';
        clearTimeout(timer);timer=setTimeout(()=>{if(selected===row.id&&active==='resume-pool')openResume(row.id);},5000);
      }
      if(row.aiState==='complete'){
        const value=row.aiResult;
        el('p',`${value.score} / 100 · 参考匹配度`,ai,'hr-salary');el('p',value.summary,ai,'hr-prose');
        for(const [key,title] of [['strengths','匹配信息'],['gaps','待核实差距'],['questions','建议面试问题'],['limitations','分析局限']]){
          if(!value[key]?.length)continue;el('h4',title,ai);const ul=el('ul','',ai);for(const text of value[key])el('li',text,ul);
        }
        if(value.evidence?.length){
          const table=el('table','',ai,'hr-evidence-table'),header=el('tr','',el('thead','',table));
          for(const label of ['岗位要求','简历依据','分析'])el('th',label,header);
          const body=el('tbody','',table);for(const evidence of value.evidence){const tr=el('tr','',body);for(const key of ['requirement','evidence','assessment'])el('td',evidence[key],tr);}
        }
      }
      const feedback=el('p','',detailBody,'hr-error');feedback.setAttribute('role','alert');
      if(row.status==='pending'){
        const group=field(detailBody,'通过后归属项目组','select');el('option','未分组',group).value='';
        for(const project of projects)el('option',project.name,group).value=project.id;group.value=row.projectId||'';
        const rejection=el('section','',detailBody,'hr-review-ai');
        el('h3','不通过时自动发送',rejection);
        el('p',template?template.content.replace(/\{(姓名|职位)\}/g,(_,key)=>key==='姓名'?(row.name||'您好'):row.jobName):'尚未配置默认婉拒话术，请先到话术管理设置。',rejection,'hr-prose');
        button('管理话术',el('div','',rejection,'hr-actions'),()=>navigate('speeches'));
        const actions=el('div','',detailBody,'hr-actions');let reviewing=false;
        async function review(action){
          if(reviewing||!allowed())return;reviewing=true;approve.disabled=reject.disabled=true;
          try{
            await request('resume-pool/review',{id:row.id,version:row.version,action,projectId:group.value});
            if(selected===row.id&&allowed()){detail.close();await load();}
          }catch(error){if(selected===row.id)feedback.textContent=error.message;}
          finally{reviewing=false;approve.disabled=false;reject.disabled=!template;}
        }
        const approve=button('通过，加入邀约',actions,()=>review('approve'));approve.className='hr-primary';
        const reject=button('不通过，自动婉拒',actions,()=>review('reject'));reject.disabled=!template;
      }else{
        el('p',`审批人：${row.reviewedBy||'—'} · ${row.reviewedAt?new Date(row.reviewedAt).toLocaleString():''}`,detailBody,'hr-hint');
        if(row.rejection){
          el('h3','婉拒发送记录',detailBody);el('p',sendStates[row.rejection.status]||row.rejection.status,detailBody,'hr-hint');
          el('p',row.rejection.message,detailBody,'hr-prose');
          el('p',`${row.rejection.templateName||'话术'} · 版本 ${row.rejection.templateVersion}`,detailBody,'hr-hint');
          if(row.rejection.status==='queued')button('取消尚未发送的婉拒',el('div','',detailBody,'hr-actions'),async()=>{
            try{await request('resume-pool/review',{id:row.id,action:'cancel-send'});if(selected===row.id)await openResume(row.id);}
            catch(error){if(selected===row.id)feedback.textContent=error.message;}
          });
        }
      }
    }
    async function jobToggle(card,row){
      const wrap=el('label','',card,'hr-passive-toggle'),input=el('input','',wrap);
      input.type='checkbox';input.disabled=true;el('span','被动邀约',wrap);
      const note=el('p','正在读取配置…',card,'hr-hint');
      let accountKey='',rule;
      const updateNote=status=>{
        if(!rule?.enabled){note.textContent='默认关闭。开启后，主动消息触发默认话术回复，再索取简历。';return;}
        if(status.data.state!=='complete'||status.data.paused){note.textContent='助手未连接，自动邀约暂停';return;}
        note.textContent=flowSummary(status.data.passive||{},rule.id);
      };
      const refreshStatus=async()=>{
        if(!allowed()||!card.isConnected)return;
        try{
          const status=await hrApi('/api/v1/hr/login/status');
          if(status.data.accountKey&&status.data.accountKey!==accountKey){
            input.disabled=true;note.textContent='招聘账号已变化，请刷新职位配置';return;
          }
          updateNote(status);
        }catch{note.textContent='正在等待助手恢复连接';}
        if(allowed()&&card.isConnected)setTimeout(refreshStatus,30000);
      };
      try{
        const [status,data]=await Promise.all([hrApi('/api/v1/hr/login/status'),request('passive')]);
        if(!allowed()||!card.isConnected)return;
        accountKey=status.data.accountKey||'';
        if(status.data.state!=='complete'||!status.data.capabilities?.includes('passive-resume')){
          note.textContent='连接新版招聘助手后可开启';return;
        }
        const id=String(row.encryptJobId||row.encJobId||row.job_id||row.jobId||row.id||'');
        rule=data.items.find(item=>item.jobId===id&&item.accountKey===accountKey);
        input.checked=rule?.enabled===true;input.disabled=false;
        updateNote(status);
        setTimeout(refreshStatus,30000);
        input.onchange=async()=>{
          input.disabled=true;const desired=input.checked;
          try{
            rule=await request('passive/save',{accountKey,jobId:id,version:rule?.version,enabled:desired,
              jobName:String(row.jobName||row.name||row.title||''),description:String(row.description||row.jobDescription||''),
              education:String(row.degreeName||row.degreeDesc||row.education||''),experience:String(row.experienceName||row.experience||row.workYearDesc||''),
              projectId:rule?.projectId||''});
            if(allowed()&&card.isConnected){input.checked=rule.enabled;note.textContent=rule.enabled?'已开启：新主动消息触发默认话术回复，成功后索取简历。':'已关闭新的自动回复与索取，已索取的简历继续接收。';}
          }catch(error){if(allowed()&&card.isConnected){input.checked=!desired;note.textContent=error.message;}}
          finally{input.disabled=false;}
        };
      }catch(error){if(allowed()&&card.isConnected)note.textContent=error.message;}
    }
    return {jobToggle,enter(route){
      active=route;clearInterval(poolTimer);load();
      if(route==='resume-pool'){
        globalThis.HeyiHrTransport?.startAutoConnect?.();
        refreshPoolActivity();
        poolTimer=setInterval(()=>{
          if(active!=='resume-pool'||!allowed())return;
          if(!detail.open)load();
          refreshPoolActivity();
        },30000);
      }
    },leave(){active='';++generation;clearTimeout(timer);clearInterval(poolTimer);poolActivity.textContent='';detail.close();speechDialog.close();},
      reset(){this.leave();items=[];selected=null;editing=null;page=1;filter='';statusSelect.value='';speechRows.replaceChildren();resumeRows.replaceChildren();detailBody.replaceChildren();content.value=name.value='';speechError.textContent='';}};
  };
})();
