/* Platform talent records and explicit interview handoff; no direct BOSS call. */
(() => {
  const stages = [['all','全部'],['invited','邀约'],['first','一面'],['second','二面'],['hired','已录取']];
  globalThis.createHrPool = ({root, section, element:el, button, allowed, navigate}) => {
    const panel=section('pool');
    let stage='all',page=1,generation=0,active=false,projects=[],rows=[],editing=null,saving=false;
    let interviewing=null,interviewData=null,interviewRequest='',interviewTimer=null,interviewSending=false,settingsData=null,settingsSaving=false,settingsEpoch=0,interviewEpoch=0;
    const toolbar=el('div','',panel,'hr-pool-toolbar');
    const intro=el('div','',toolbar);
    el('h2','让合适的人，始终在视野里',intro);
    el('p','按项目组沉淀人才，按招聘阶段持续跟进。',intro,'hr-hint');
    const actions=el('div','',toolbar,'hr-actions');
    button('钉钉设置',actions,openSettings);
    button('管理项目组',actions,()=>openProject());
    button('新建人才',actions,()=>openEditor()).className='hr-primary';
    const filters=el('form','',panel,'hr-form');
    const makeField=(label,parent,tag='input')=>{
      const wrap=el('label','',parent,'hr-field');el('span',label,wrap);return el(tag,'',wrap);
    };
    const projectFilter=makeField('项目组',filters,'select');
    const query=makeField('搜索人才',filters);
    query.placeholder='姓名、职位、公司或备注';query.maxLength=100;
    button('搜索',filters,()=>{page=1;load();});
    filters.addEventListener('submit',event=>{event.preventDefault();page=1;load();});
    projectFilter.addEventListener('change',()=>{page=1;load();});
    const tabs=el('div','',panel,'hr-pool-tabs');tabs.setAttribute('aria-label','招聘阶段');
    const tabButtons=stages.map(([key,label])=>{
      const b=button(label,tabs,()=>{stage=key;page=1;load();});
      b.dataset.stage=key;return b;
    });
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const result=el('div','',panel,'hr-result');
    const pager=el('div','',panel,'hr-pagination');
    const previous=button('上一页',pager,()=>{page--;load();});
    const pageLabel=el('span','',pager,'hr-hint');
    const next=button('下一页',pager,()=>{page++;load();});
    previous.disabled=next.disabled=true;pager.hidden=true;
    const dialog=el('dialog','',root,'hr-pool-dialog');
    const form=el('form','',dialog);
    const editorTitle=el('h2','新建人才',form);
    el('p','资料保存在平台，阶段仅作内部跟进记录。',form,'hr-hint');
    const fields=el('div','',form,'hr-pool-fields'), inputs={};
    for(const [key,label,limit] of [['name','姓名',100],['position','意向职位',200],['education','学历',100],
      ['experience','工作年限',100],['phone','联系方式',100],['city','城市',100],['company','最近公司',200]]){
      const input=inputs[key]=makeField(label,fields);input.maxLength=limit;
      input.name=key;input.autocomplete='off';input.required=key==='name';
    }
    const stageInput=makeField('招聘阶段',fields,'select');
    el('option','未设置阶段',stageInput).value='';
    stages.slice(1).forEach(([key,label])=>el('option',label,stageInput).value=key);
    const projectInput=makeField('所属项目组',fields,'select');
    const notes=inputs.notes=makeField('跟进备注',form,'textarea');notes.maxLength=5000;notes.rows=4;
    const editorError=el('p','',form,'hr-error');editorError.hidden=true;editorError.setAttribute('role','alert');
    const editorActions=el('div','',form,'hr-actions');
    const cancel=button('取消',editorActions,()=>dialog.close());
    const saveButton=el('button','保存人才',editorActions,'hr-primary');saveButton.type='submit';
    const projectDialog=el('dialog','',root,'hr-pool-dialog hr-pool-project-dialog');
    const projectForm=el('form','',projectDialog);
    el('h2','管理项目组',projectForm);
    el('p','人才按项目组分类，可新建或重命名。',projectForm,'hr-hint');
    const projectSelect=makeField('选择项目组',projectForm,'select');
    const projectName=makeField('项目组名称',projectForm);projectName.required=true;projectName.maxLength=80;
    const projectError=el('p','',projectForm,'hr-error');projectError.hidden=true;
    const projectActions=el('div','',projectForm,'hr-actions');
    button('关闭',projectActions,()=>projectDialog.close());
    const saveProject=el('button','保存项目组',projectActions,'hr-primary');saveProject.type='submit';
    projectSelect.addEventListener('change',()=>{projectName.value=projects.find(p=>p.id===projectSelect.value)?.name||'';});
    const interviewDialog=el('dialog','',root,'hr-pool-dialog hr-interview-dialog');
    const interviewTitle=el('h2','开始一面',interviewDialog);
    el('p','简历、岗位 JD 和面试题库将作为一份 ZIP 资料包发给负责人，可解压查看和打印题库。',interviewDialog,'hr-hint');
    const interviewFields=el('div','',interviewDialog,'hr-pool-fields');
    const resumeSelect=makeField('候选人简历',interviewFields,'select');
    const demandSelect=makeField('对应招聘需求',interviewFields,'select');
    const interviewSummary=el('p','',interviewDialog,'hr-interview-summary');
    const interviewProgress=el('div','',interviewDialog,'hr-interview-progress');
    interviewProgress.setAttribute('aria-label','一面资料发送进度');
    const interviewFeedback=el('p','',interviewDialog,'hr-hint');interviewFeedback.setAttribute('role','status');
    const interviewActions=el('div','',interviewDialog,'hr-actions');
    button('关闭',interviewActions,()=>interviewDialog.close());
    button('钉钉设置',interviewActions,()=>{interviewDialog.close();openSettings();});
    const interviewSend=button('发送一面资料',interviewActions,sendInterview);interviewSend.className='hr-primary';
    interviewDialog.addEventListener('close',()=>{++interviewEpoch;interviewing=null;interviewData=null;clearTimeout(interviewTimer);});
    const states={preparing:'正在准备一面资料包',sending:'正在发送到钉钉',submitted:'钉钉已接受，正在确认送达',
      sent:'一面资料已送达负责人',uncertain:'发送结果待人工核对，不会自动重发',failed:'发送未完成，可核对后手动重试'};
    const selectedDemand=()=>interviewData?.demands?.find(d=>d.id===demandSelect.value);
    function renderInterview(){
      const data=interviewData,demand=selectedDemand(),handoff=data?.handoff;
      const missing=[];
      if(!data?.config?.verified)missing.push('请先配置钉钉企业内部应用');
      if(!data?.resumes?.length)missing.push('候选人还没有审批通过的简历');
      if(!demand)missing.push('请在招聘需求池关联这份简历的具体职位');
      else{
        if(!demand.questionsReady)missing.push('请在招聘需求池生成当前版本的面试题库');
        if(!demand.recipientReady)missing.push('请在钉钉设置中绑定并校验该岗位负责人');
        if(!demand.resumeIds.includes(resumeSelect.value))missing.push('所选简历与招聘需求职位不一致');
      }
      interviewSummary.textContent=demand?`${demand.position} · ${demand.department||'部门待补充'} · 负责人：${demand.recipient||'未配置'} · ${demand.questionCount} 道面试题`:'';
      interviewFeedback.textContent=handoff?(states[handoff.status]||'正在核对发送记录')+(handoff.error?'。'+handoff.error:''):missing.join('；');
      interviewFeedback.className=missing.length||handoff?.status==='failed'?'hr-error':'hr-hint';
      interviewProgress.replaceChildren();
      const phase={preparing:0,sending:1,submitted:2,sent:3}[handoff?.status]??-1;
      ['准备资料','发送钉钉','核对回执','进入一面'].forEach((label,index)=>{
        const node=el('span',label,interviewProgress,'hr-interview-step'+(index<=phase?' is-current':''));
        if(index===phase&&phase<3)node.className+=' is-running';
      });
      const pending=!!handoff&&handoff.status!=='failed';
      interviewSend.textContent=handoff?.status==='failed'?'手动重试':'发送一面资料';
      interviewSend.disabled=interviewSending||pending||missing.length>0||data?.stage!=='invited';
      resumeSelect.disabled=demandSelect.disabled=interviewSending||pending;
      return missing.length===0&&!handoff&&data?.stage==='invited';
    }
    function fillInterview(data){
      const oldResume=resumeSelect.value,oldDemand=demandSelect.value;
      interviewData=data;resumeSelect.replaceChildren();demandSelect.replaceChildren();
      for(const resume of data.resumes||[])el('option',`${resume.filename} · ${resume.jobName}`,resumeSelect).value=resume.id;
      resumeSelect.value=data.resumes?.some(r=>r.id===oldResume)?oldResume:data.resumes?.[0]?.id||'';
      const matches=(data.demands||[]).filter(d=>d.resumeIds.includes(resumeSelect.value));
      el('option',matches.length>1?'请选择对应招聘需求':'暂无对应招聘需求',demandSelect).value='';
      for(const demand of matches)el('option',`${demand.position} · ${demand.department}`,demandSelect).value=demand.id;
      demandSelect.value=matches.some(d=>d.id===oldDemand)?oldDemand:matches.length===1?matches[0].id:'';
      return renderInterview();
    }
    resumeSelect.addEventListener('change',()=>fillInterview(interviewData));
    demandSelect.addEventListener('change',()=>renderInterview());
    async function refreshInterview(){
      clearTimeout(interviewTimer);
      if(!interviewing||!interviewDialog.open||!active||!allowed())return;
      const id=interviewing.id,epoch=interviewEpoch;
      try{
        const data=await request('/interview?talentId='+encodeURIComponent(id));
        if(epoch!==interviewEpoch||interviewing?.id!==id||!interviewDialog.open||!allowed())return;
        fillInterview(data);
        if(data.handoff?.status==='sent')load();
        if(['preparing','sending','submitted'].includes(data.handoff?.status))interviewTimer=setTimeout(refreshInterview,5000);
      }catch(error){if(epoch===interviewEpoch&&interviewing?.id===id)interviewFeedback.textContent=error.message;}
    }
    async function openInterview(row,autoStart=false){
      if(!allowed()||interviewSending)return;
      const epoch=++interviewEpoch;
      interviewing=row;interviewData=null;interviewRequest=crypto.randomUUID();
      interviewTitle.textContent='一面 · '+row.name;interviewFeedback.textContent='正在读取岗位、简历和发送记录…';
      resumeSelect.replaceChildren();demandSelect.replaceChildren();interviewSummary.textContent='';interviewProgress.replaceChildren();
      interviewSend.disabled=true;interviewDialog.showModal();
      await refreshInterview();
      if(epoch===interviewEpoch&&interviewing?.id===row.id&&autoStart&&renderInterview())await sendInterview();
    }
    async function sendInterview(){
      if(!interviewing||interviewSending||interviewSend.disabled||!allowed())return;
      const id=interviewing.id,data=interviewData,demand=selectedDemand(),epoch=interviewEpoch;
      let failure='';
      interviewSending=true;interviewSend.disabled=true;resumeSelect.disabled=demandSelect.disabled=true;
      interviewFeedback.textContent='正在提交一面资料…';
      try{
        const handoff=await request('/interview/start',{talentId:id,requestId:interviewRequest,
          version:data.version,resumeId:resumeSelect.value,demandId:demand.id,demandVersion:demand.version,
          recipientId:demand.recipientId,configVersion:data.config.version,retryId:data.handoff?.status==='failed'?data.handoff.id:undefined});
        if(epoch!==interviewEpoch||interviewing?.id!==id||!allowed())return;
        interviewData.handoff=handoff;renderInterview();await refreshInterview();
      }catch(error){
        if(epoch===interviewEpoch&&interviewing?.id===id){
          failure=error.message;
          interviewFeedback.textContent=error.message;
          // Keep the same request ID; a lost response must not queue another send.
          interviewTimer=setTimeout(refreshInterview,5000);
        }
      }finally{
        interviewSending=false;
        if(epoch===interviewEpoch&&interviewing?.id===id){
          renderInterview();if(failure)interviewFeedback.textContent=failure;
          if(interviewData?.handoff?.status==='failed')interviewRequest=crypto.randomUUID();
        }
      }
    }

    const settingsDialog=el('dialog','',root,'hr-pool-dialog hr-dingtalk-dialog');
    const settingsHead=el('div','',settingsDialog,'hr-dingtalk-heading');
    el('h2','钉钉设置',settingsHead);button('关闭',settingsHead,()=>settingsDialog.close());
    el('p','给负责人发个人工作通知，使用企业内部应用。完成一次配置后，“开始一面”会直接发送资料。',settingsDialog,'hr-hint');
    const guide=el('ol','',settingsDialog,'hr-dingtalk-guide');
    for(const text of [
      '请企业钉钉管理员登录开发者后台，在应用开发中创建企业内部应用，建议命名“赫逸招聘助手”。',
      '在“凭证与基础信息”复制 Client ID（AppKey）、Client Secret（AppSecret）和 AgentId，填写下方；密钥留空会保留已保存值。',
      '权限管理中开通“成员信息读权限”，通讯录范围只需覆盖面试负责人；工作通知与媒体上传权限通常默认开通，仍需核对应用权限。',
      '发布应用，将可使用范围包含面试负责人；如启用服务器出口 IP 白名单，添加 8.163.34.81，并以实际出口为准。',
      '从企业通讯录员工详情获取负责人的 UserID；如后台未展示，由管理员使用钉钉 API Explorer“根据手机号查询用户”取得。',
      '先保存并校验应用，再选择招聘需求、填写负责人 UserID 并绑定。系统会读取钉钉姓名供你核对，不会发送测试消息。'
    ])el('li',text,guide);
    const guideLinks=el('div','',settingsDialog,'hr-actions');
    for(const [label,url] of [['打开钉钉开发者后台','https://open-dev.dingtalk.com/'],
      ['工作通知官方说明','https://open.dingtalk.com/document/orgapp/asynchronous-sending-of-enterprise-session-messages']]){
      const a=el('a',label,guideLinks);a.href=url;a.target='_blank';a.rel='noopener noreferrer';
    }
    const settingsForm=el('form','',settingsDialog);
    const settingsFields=el('div','',settingsForm,'hr-pool-fields');
    const appKey=makeField('Client ID / AppKey',settingsFields);appKey.maxLength=200;appKey.autocomplete='off';
    const agentId=makeField('AgentId',settingsFields);agentId.maxLength=30;agentId.inputMode='numeric';
    const appSecret=makeField('Client Secret / AppSecret',settingsForm);appSecret.type='password';appSecret.maxLength=500;appSecret.autocomplete='new-password';
    const settingsFeedback=el('p','',settingsForm,'hr-hint');settingsFeedback.setAttribute('role','status');
    const configSave=el('button','保存并校验应用',el('div','',settingsForm,'hr-actions'),'hr-primary');configSave.type='submit';
    const recipientForm=el('form','',settingsDialog,'hr-dingtalk-recipient');
    el('h3','岗位负责人',recipientForm);
    const recipientFields=el('div','',recipientForm,'hr-pool-fields');
    const recipientDemand=makeField('招聘需求',recipientFields,'select');
    const recipientId=makeField('负责人 UserID',recipientFields);recipientId.maxLength=100;recipientId.autocomplete='off';
    const recipientName=el('p','',recipientForm,'hr-hint');
    const recipientSave=el('button','校验并绑定负责人',el('div','',recipientForm,'hr-actions'),'hr-primary');recipientSave.type='submit';
    const recipientFeedback=el('p','',recipientForm,'hr-hint');recipientFeedback.setAttribute('role','status');
    function showRecipient(){
      const demand=settingsData?.demands?.find(d=>d.id===recipientDemand.value);
      recipientId.value=demand?.recipient?.userId||'';
      recipientName.textContent=demand?.recipient?`已绑定：${demand.recipient.name}${demand.recipient.valid?'':' · 应用已更换，请重新校验'}`:
        demand?`部门：${demand.department} · 部门经理：${demand.manager||'未设置'} · 尚未绑定钉钉负责人`:'请先在招聘需求池建立关联职位的需求';
      recipientSave.disabled=!settingsData?.config?.verified||!demand||settingsSaving;
    }
    recipientDemand.addEventListener('change',showRecipient);
    async function loadSettings(epoch=settingsEpoch){
      const data=await request('/dingtalk');
      if(epoch!==settingsEpoch||!settingsDialog.open||!allowed()||!active)return;
      settingsData=data;appKey.value=data.config.appKey;agentId.value=data.config.agentId;
      appSecret.value='';appSecret.placeholder=data.config.hasSecret?'已保存；留空保留':'请输入应用密钥';
      settingsFeedback.textContent=data.config.verified?'应用凭证已校验。还需核对 AgentId、发布状态和负责人可见范围。':'尚未配置钉钉应用';
      recipientDemand.replaceChildren();el('option','请选择招聘需求',recipientDemand).value='';
      for(const demand of data.demands||[])el('option',`${demand.position} · ${demand.department}`,recipientDemand).value=demand.id;
      showRecipient();
    }
    async function openSettings(){
      if(!allowed())return;
      const epoch=++settingsEpoch;
      settingsData=null;appSecret.value='';settingsFeedback.textContent='正在读取配置…';
      configSave.disabled=recipientSave.disabled=true;recipientFeedback.textContent='';
      settingsDialog.showModal();
      try{await loadSettings(epoch);}catch(error){if(epoch===settingsEpoch&&settingsDialog.open)settingsFeedback.textContent=error.message;}
      finally{if(epoch===settingsEpoch)configSave.disabled=false;}
    }
    settingsForm.addEventListener('submit',async event=>{
      event.preventDefault();if(settingsSaving||!settingsData||!allowed())return;
      const epoch=settingsEpoch;
      settingsSaving=true;configSave.disabled=true;settingsFeedback.textContent='正在校验应用凭证…';
      try{
        await request('/dingtalk/save',{appKey:appKey.value.trim(),agentId:agentId.value.trim(),appSecret:appSecret.value.trim(),version:settingsData.config.version});
        if(epoch===settingsEpoch&&settingsDialog.open&&allowed())await loadSettings(epoch);
      }catch(error){if(epoch===settingsEpoch&&settingsDialog.open&&allowed())settingsFeedback.textContent=error.message;}
      finally{settingsSaving=false;configSave.disabled=false;appSecret.value='';showRecipient();}
    });
    recipientForm.addEventListener('submit',async event=>{
      event.preventDefault();if(settingsSaving||!settingsData||recipientSave.disabled||!allowed())return;
      const epoch=settingsEpoch;
      settingsSaving=true;recipientSave.disabled=true;recipientFeedback.textContent='正在核对钉钉负责人…';
      const demand=recipientDemand.value;
      try{
        const result=await request('/dingtalk/recipient',{demandId:demand,userId:recipientId.value.trim(),configVersion:settingsData.config.version});
        if(epoch===settingsEpoch&&settingsDialog.open&&allowed()){
          await loadSettings(epoch);if(epoch!==settingsEpoch)return;
          recipientDemand.value=demand;showRecipient();
          recipientFeedback.textContent=`已绑定 ${result.name}，请确认是该岗位的一面负责人。`;
        }
      }catch(error){if(epoch===settingsEpoch&&settingsDialog.open&&allowed())recipientFeedback.textContent=error.message;}
      finally{settingsSaving=false;showRecipient();}
    });
    settingsDialog.addEventListener('close',()=>{++settingsEpoch;appSecret.value='';settingsData=null;});

    async function request(path,body){
      const response=await api('/api/v1/hr/pool'+path,body?{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)
      }:{});
      if(response.success===false||!response.data||typeof response.data!=='object')throw Error(response.message||'读取人才库失败');
      return response.data;
    }
    function fillProjects(select,first,selected=''){
      select.replaceChildren();el('option',first,select).value='';
      for(const project of projects)el('option',project.name,select).value=project.id;
      select.value=selected;
    }
    function syncProjects(){
      const selected=projectFilter.value;
      fillProjects(projectFilter,'全部项目组',selected);
      el('option','未分组',projectFilter).value='unassigned';
      projectFilter.value=selected;
    }
    async function load(){
      if(!allowed()||!active)return;
      const ticket=++generation;
      result.setAttribute('aria-busy','true');feedback.textContent='正在读取人才库…';
      previous.disabled=next.disabled=true;
      tabButtons.forEach((b,i)=>b.setAttribute('aria-pressed',String(stages[i][0]===stage)));
      try{
        const data=await request('?'+new URLSearchParams({stage,project:projectFilter.value,q:query.value.trim(),page:String(page)}));
        if(ticket!==generation||!active||!allowed())return;
        projects=data.projects;rows=data.items;page=data.page;syncProjects();
        tabButtons.forEach((b,i)=>{const [key,label]=stages[i];b.textContent=`${label} ${data.counts[key]||0}`;});
        render();
        feedback.textContent=`共 ${data.total} 位人才`;
        previous.disabled=page<=1;next.disabled=page>=data.pages;
        pageLabel.textContent=`${page} / ${data.pages}`;pager.hidden=data.pages<=1;
      }catch(error){
        if(ticket!==generation||!active)return;
        rows=[];result.replaceChildren();el('p',error.message,result,'hr-error');
        feedback.textContent='读取失败';pager.hidden=true;
        button('重新加载',el('div','',result,'hr-actions'),load);
      }finally{if(ticket===generation)result.setAttribute('aria-busy','false');}
    }
    function render(){
      result.replaceChildren();
      if(!rows.length){
        const empty=el('div','',result,'hr-pool-empty');
        el('span','◎',empty,'hr-pool-empty-icon');el('h3','这里还没有人才记录',empty);
        el('p','新建人才，或从人才寻源中加入储备库；也可以调整筛选条件。',empty,'hr-hint');return;
      }
      const grid=el('div','',result,'hr-card-grid');
      for(const row of rows){
        const card=el('article','',grid,'hr-person-card');
        const canInvite=row.stage==='invited';
        if(canInvite){
          card.className+=' hr-invitable-card';card.tabIndex=0;card.setAttribute('role','button');
          card.setAttribute('aria-label','查看'+row.name+'的一面资料');
          card.addEventListener('click',event=>{if(!event.target?.closest?.('button,a,input,select,textarea'))openInterview(row);});
          card.addEventListener('keydown',event=>{
            if(event.target===card&&['Enter',' '].includes(event.key)){event.preventDefault();openInterview(row);}
          });
        }
        const head=el('div','',card,'hr-card-heading');
        const identity=el('div','',head,'hr-identity');el('span',row.name.slice(0,1),identity,'hr-avatar');
        const copy=el('div','',identity);el('h3',row.name,copy);el('p',row.position||'职位待补充',copy,'hr-hint');
        el('span',stages.find(([key])=>key===row.stage)?.[1]||'未设置阶段',head,'hr-badge');
        const tags=el('div','',card,'hr-tags');
        el('span',projects.find(p=>p.id===row.projectId)?.name||'未分组',tags,'hr-badge');
        el('span',row.source==='boss'?'BOSS 人才':row.source==='passive'?'简历池审批':'手动录入',tags,'hr-badge');
        const facts=el('dl','',card,'hr-facts');
        for(const [label,value] of [['学历',row.education],['工作年限',row.experience],['城市',row.city],['最近公司',row.company]]){
          const item=el('div','',facts);el('dt',label,item);el('dd',value||'未填写',item);
        }
        if(row.notes)el('p',row.notes,card,'hr-excerpt hr-pool-note');
        const footer=el('div','',card,'hr-actions');
        button('查看 / 编辑',footer,()=>openEditor(row));
        if(canInvite)button('开始一面',footer,()=>openInterview(row,true)).className='hr-primary';
        if(row.stage==='first')button('一面资料',footer,()=>openInterview(row)).className='hr-primary';
        el('span',`更新于 ${new Date(row.updatedAt).toLocaleDateString('zh-CN')}`,footer,'hr-hint');
      }
    }
    async function openEditor(row={}){
      if(!allowed())return;
      editing={...row,...(!row.id?{createId:crypto.randomUUID()}: {})};editorTitle.textContent=row.id?'编辑人才':'新建人才';
      Object.entries(inputs).forEach(([key,input])=>input.value=row[key]||'');
      stageInput.value=row.stage||'';fillProjects(projectInput,'未分组',row.projectId||'');
      editorError.hidden=true;saveButton.disabled=true;cancel.disabled=false;
      dialog.showModal();
      const ticket=generation;
      try{
        const data=await request('?page=1');
        if(ticket!==generation||!dialog.open)return;
        projects=data.projects;fillProjects(projectInput,'未分组',row.projectId||'');
        saveButton.disabled=false;
      }catch(error){if(dialog.open){editorError.textContent=error.message;editorError.hidden=false;}}
    }
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(saving||saveButton.disabled||!allowed())return;
      saving=true;saveButton.disabled=cancel.disabled=true;saveButton.textContent='正在保存…';
      editorError.hidden=true;const ticket=generation;
      const body={...editing,...Object.fromEntries(Object.entries(inputs).map(([k,v])=>[k,v.value.trim()])),
        stage:stageInput.value,projectId:projectInput.value,source:editing.source||'manual'};
      try{
        await request('/talents',body);
        if(ticket!==generation||!allowed())return;
        dialog.close();stage=body.stage||'all';query.value='';page=1;
        projectFilter.value=body.projectId||'';navigate('pool');
      }catch(error){if(ticket===generation){editorError.textContent=error.message;editorError.hidden=false;}}
      finally{saving=false;saveButton.disabled=cancel.disabled=false;saveButton.textContent='保存人才';}
    });
    async function openProject(){
      if(!allowed())return;
      projectName.value='';projectError.hidden=true;saveProject.disabled=true;
      fillProjects(projectSelect,'新建项目组');projectDialog.showModal();const ticket=generation;
      try{
        const data=await request('?page=1');
        if(ticket!==generation||!projectDialog.open)return;
        projects=data.projects;fillProjects(projectSelect,'新建项目组');saveProject.disabled=false;
      }catch(error){if(projectDialog.open){projectError.textContent=error.message;projectError.hidden=false;}}
    }
    projectForm.addEventListener('submit',async event=>{
      event.preventDefault();if(saveProject.disabled||!allowed())return;
      saveProject.disabled=true;projectError.hidden=true;const ticket=generation;
      const old=projects.find(p=>p.id===projectSelect.value);
      try{
        await request('/projects',{id:old?.id||'',version:old?.version,name:projectName.value.trim()});
        if(ticket!==generation||!allowed())return;
        projectDialog.close();load();
      }catch(error){if(ticket===generation){projectError.textContent=error.message;projectError.hidden=false;}}
      finally{saveProject.disabled=false;}
    });
    dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
    return {
      enter(){active=true;load();},
      leave(){active=false;++generation;dialog.close();projectDialog.close();interviewDialog.close();settingsDialog.close();clearTimeout(interviewTimer);},
      add:openEditor,
      reset(){active=false;++generation;projects=[];rows=[];editing=null;stage='all';page=1;
        query.value='';projectFilter.value='';dialog.close();projectDialog.close();interviewDialog.close();settingsDialog.close();clearTimeout(interviewTimer);
        interviewing=null;interviewData=null;settingsData=null;appKey.value=agentId.value=appSecret.value=recipientId.value='';recipientDemand.replaceChildren();
        Object.values(inputs).forEach(input=>input.value='');projectName.value='';
        [projectFilter,projectInput,projectSelect].forEach(select=>select.replaceChildren());
        editorError.textContent=projectError.textContent='';
        result.replaceChildren();feedback.textContent='';}
    };
  };
})();
