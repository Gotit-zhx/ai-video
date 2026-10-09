/* Platform talent records and explicit interview handoff; no direct BOSS call. */
(() => {
  const stages = [['invited','邀约'],['first','一面'],['second','二面'],['hired','已录取']];
  const defaultOutcome=stage=>['first','second'].includes(stage)?'pending':'all';
  globalThis.createHrPool = ({root, section, element:el, button, allowed, navigate,beginOnboarding}) => {
    const panel=section('pool');
    let stage='invited',outcome='all',department='',page=1,generation=0,active=false,departments=[],rows=[],editing=null,saving=false;
    let departmentButtons=[],departmentDirectory='',pageButtons=[];
    let interviewing=null,interviewData=null,interviewRequest='',interviewTimer=null,interviewSending=false,settingsData=null,settingsSaving=false,settingsEpoch=0,interviewEpoch=0;
    let interviewRound='first',interviewerProof=null,identifying=false;
    let directoryData=null,directoryPage=1,directoryBusy=false,directoryEpoch=0,directoryTimer=null;
    const toolbar=el('div','',panel,'hr-pool-toolbar');
    const intro=el('div','',toolbar);
    el('h2','让合适的人，始终在视野里',intro);
    el('p','按部门归档人才，按招聘阶段持续跟进。',intro,'hr-hint');
    const actions=el('div','',toolbar,'hr-actions');
    button('钉钉设置',actions,openSettings);
    const filters=el('form','',panel,'hr-form');
    const makeField=(label,parent,tag='input')=>{
      const wrap=el('label','',parent,'hr-field');el('span',label,wrap);return el(tag,'',wrap);
    };
    const query=makeField('搜索人才',filters);
    query.placeholder='姓名、职位、公司或备注';query.maxLength=100;
    button('搜索',filters,()=>{page=1;load();});
    filters.addEventListener('submit',event=>{event.preventDefault();page=1;load();});
    const departmentBar=el('div','',panel,'hr-pool-tabs hr-pool-departments');
    departmentBar.setAttribute('aria-label','部门');
    const tabs=el('div','',panel,'hr-pool-tabs');tabs.setAttribute('aria-label','招聘阶段');
    const tabButtons=stages.map(([key,label])=>{
      const b=button(label,tabs,()=>{stage=key;outcome=defaultOutcome(stage);page=1;load();});
      b.dataset.stage=key;return b;
    });
    const outcomeBar=el('div','',panel,'hr-pool-tabs hr-pool-outcomes');outcomeBar.hidden=true;
    outcomeBar.setAttribute('aria-label','面试结果');
    const outcomeOptions=[['pending','待填写意见'],['passed','通过'],['failed','未通过']].map(([key,label])=>{
      const option=button(label,outcomeBar,()=>{outcome=key;page=1;load();});
      option.dataset.outcome=key;return {option,key,label};
    });
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const result=el('div','',panel,'hr-result');
    const pager=el('div','',panel,'hr-pagination hr-pool-pagination');
    pager.setAttribute('role','navigation');pager.setAttribute('aria-label','人才列表分页');
    const previous=button('上一页',pager,()=>{page--;load();});
    const pageNumbers=el('div','',pager,'hr-pool-page-numbers');
    const next=button('下一页',pager,()=>{page++;load();});
    const pageLabel=el('span','',pager,'hr-hint');
    previous.disabled=next.disabled=true;pager.hidden=true;
    const dialog=el('dialog','',root,'hr-pool-dialog');
    const form=el('form','',dialog);
    const editorTitle=el('h2','编辑人才',form);
    el('p','资料保存在平台，阶段仅作内部跟进记录。',form,'hr-hint');
    const fields=el('div','',form,'hr-pool-fields'), inputs={};
    for(const [key,label,limit] of [['name','姓名',100],['position','意向职位',200],['education','学历',100],
      ['experience','工作年限',100],['phone','联系方式',100],['city','城市',100],['company','最近公司',200]]){
      const input=inputs[key]=makeField(label,fields);input.maxLength=limit;
      input.name=key;input.autocomplete='off';input.required=key==='name';
    }
    const stageInput=makeField('招聘阶段',fields,'select');
    stages.forEach(([key,label])=>el('option',label,stageInput).value=key);
    const departmentInput=makeField('所属部门',fields,'select');
    const notes=inputs.notes=makeField('跟进备注',form,'textarea');notes.maxLength=5000;notes.rows=4;
    const editorError=el('p','',form,'hr-error');editorError.hidden=true;editorError.setAttribute('role','alert');
    const editorActions=el('div','',form,'hr-actions');
    const cancel=button('取消',editorActions,()=>dialog.close());
    const saveButton=el('button','保存人才',editorActions,'hr-primary');saveButton.type='submit';
    const interviewDialog=el('dialog','',root,'hr-pool-dialog hr-interview-dialog');
    const interviewTitle=el('h2','开始一面',interviewDialog);
    const interviewIntro=el('p','简历、岗位 JD 和面试题库将作为一份 ZIP 资料包发给负责人，可解压查看和打印题库。',interviewDialog,'hr-hint');
    const interviewFields=el('div','',interviewDialog,'hr-pool-fields');
    const resumeSelect=makeField('候选人简历',interviewFields,'select');
    const demandSelect=makeField('对应招聘需求',interviewFields,'select');
    const recipientFields=el('div','',interviewDialog,'hr-pool-fields hr-interview-recipient');recipientFields.hidden=true;
    const recipientSelect=makeField('二面钉钉面试人员（可选）',recipientFields,'select');
    const directoryFields=el('div','',interviewDialog,'hr-directory');directoryFields.hidden=true;
    const directoryFilters=el('div','',directoryFields,'hr-directory-filters');
    const directoryQuery=makeField('查询公司人员',directoryFilters);directoryQuery.maxLength=100;
    directoryQuery.placeholder='输入姓名或部门';
    const directoryDepartment=makeField('钉钉部门',directoryFilters,'select');
    el('option','所有可见部门',directoryDepartment).value='';
    const directoryRefresh=button('刷新通讯录',directoryFilters,()=>{directoryPage=1;loadDirectory(true);});
    directoryRefresh.className='hr-secondary';
    const directoryStatus=el('p','',directoryFields,'hr-hint');directoryStatus.setAttribute('role','status');
    const directoryResults=el('div','',directoryFields,'hr-directory-results');
    directoryResults.setAttribute('aria-label','公司人员查询结果');
    const directoryPager=el('div','',directoryFields,'hr-actions');
    const directoryPrevious=button('上一页',directoryPager,()=>{directoryPage--;loadDirectory();});
    const directoryNext=button('下一页',directoryPager,()=>{directoryPage++;loadDirectory();});
    const directoryPageLabel=el('span','',directoryPager,'hr-hint');directoryPager.hidden=true;
    const mobileFields=el('div','',interviewDialog,'hr-pool-fields');mobileFields.hidden=true;
    const interviewerMobile=makeField('面试人员手机号',mobileFields);interviewerMobile.inputMode='tel';interviewerMobile.maxLength=30;
    const identifyButton=button('识别面试人员',mobileFields,identifyInterviewer);
    const identityFeedback=el('p','',interviewDialog,'hr-hint');identityFeedback.hidden=true;
    const interviewSummary=el('p','',interviewDialog,'hr-interview-summary');
    const interviewProgress=el('div','',interviewDialog,'hr-interview-progress');
    interviewProgress.setAttribute('aria-label','一面资料发送进度');
    const interviewFeedback=el('p','',interviewDialog,'hr-hint');interviewFeedback.setAttribute('role','status');
    const interviewActions=el('div','',interviewDialog,'hr-actions');
    button('关闭',interviewActions,()=>interviewDialog.close());
    const interviewDownload=el('a','下载资料 ZIP',interviewActions,'hr-secondary hr-interview-download');
    interviewDownload.hidden=true;interviewDownload.download='';
    const interviewSettings=button('钉钉设置',interviewActions,()=>{interviewDialog.close();openSettings();});
    const interviewSend=button('发送一面资料',interviewActions,sendInterview);interviewSend.className='hr-primary';
    interviewDialog.addEventListener('close',()=>{++interviewEpoch;++directoryEpoch;interviewing=null;interviewData=null;
      clearTimeout(interviewTimer);clearTimeout(directoryTimer);});
    const states={preparing:'正在准备一面资料包',sending:'正在发送到钉钉',submitted:'钉钉已接受，正在确认送达',
      sent:'一面资料已送达负责人',uncertain:'发送结果待人工核对，不会自动重发',failed:'发送未完成，可核对后手动重试'};
    const selectedDemand=()=>interviewData?.demands?.find(d=>d.id===demandSelect.value);
    const selectedRecipient=()=>['mobile','directory'].includes(recipientSelect.value)?interviewerProof:
      interviewData?.recipients?.find(r=>r.bindingKey===recipientSelect.value);
    function renderInterview(){
      const data=interviewData,demand=selectedDemand(),handoff=data?.handoff;
      const second=interviewRound==='second',label=second?'二面':'一面',person=second?selectedRecipient():null;
      const delivered=handoff?.status==='sent';
      interviewDownload.hidden=!(data?.packageReady&&(handoff||(resumeSelect.value&&demand?.questionsReady)));
      interviewDownload.removeAttribute('href');
      if(!interviewDownload.hidden){
        const query=new URLSearchParams({talentId:interviewing.id,round:interviewRound});
        if(!handoff){query.set('resumeId',resumeSelect.value);query.set('demandId',demand.id);}
        interviewDownload.href='/api/v1/hr/pool/interview/package?'+query;
      }
      interviewSettings.hidden=interviewSend.hidden=delivered||data?.reviewed||(!second&&data?.stage!=='invited');
      recipientFields.hidden=!second;mobileFields.hidden=!second||recipientSelect.value!=='mobile'||(!!handoff&&handoff.status!=='failed');
      directoryFields.hidden=!second||recipientSelect.value!=='directory'||(!!handoff&&handoff.status!=='failed')||data?.reviewed;
      identifyButton.hidden=mobileFields.hidden;
      identityFeedback.hidden=mobileFields.hidden&&directoryFields.hidden;
      interviewProgress.setAttribute('aria-label',label+'资料发送进度');
      interviewIntro.textContent=delivered?'简历、岗位 JD 和面试题库已作为 ZIP 资料包送达负责人，可解压查看和打印题库。':
        second?'可选择钉钉面试人员发送简历、岗位 JD 和面试题库，也可以关闭此窗口直接填写二面意见。':
          '简历、岗位 JD 和面试题库将作为一份 ZIP 资料包发给负责人，可解压查看和打印题库。';
      const missing=[];
      if(!data?.config?.verified)missing.push('请先配置钉钉企业内部应用');
      if(!data?.resumes?.length)missing.push('候选人还没有审批通过的简历');
      if(!demand)missing.push('请在招聘需求池关联这份简历的具体职位');
      else{
        if(!demand.questionsReady)missing.push('请在招聘需求池生成当前版本的面试题库');
        if(!second&&!demand.recipientReady)missing.push('请在部门管理中绑定并校验该岗位的钉钉负责人');
        if(!demand.resumeIds.includes(resumeSelect.value))missing.push('所选简历与招聘需求职位不一致');
      }
      if(second&&!person)missing.push('发送资料前请选择或识别二面面试人员');
      interviewSummary.textContent=demand?`${demand.position} · ${demand.department||'部门待补充'} · 面试人员：${handoff?.recipient||(second?person?.name:demand.recipient)||'未选择'} · ${demand.questionCount} 道面试题`:'';
      interviewFeedback.textContent=handoff?(states[handoff.status]||'正在核对发送记录').replaceAll('一面',label)+(handoff.error?'。'+handoff.error:''):missing.join('；');
      interviewFeedback.className=(!handoff&&missing.length)||handoff?.status==='failed'?'hr-error':'hr-hint';
      interviewProgress.replaceChildren();
      const phase={preparing:0,sending:1,submitted:2,sent:3}[handoff?.status]??-1;
      ['准备资料','发送钉钉','核对回执',second?'资料送达':'进入一面'].forEach((label,index)=>{
        const node=el('span',label,interviewProgress,'hr-interview-step'+(index<=phase?' is-current':''));
        if(index===phase&&phase<3)node.className+=' is-running';
      });
      const pending=!!handoff&&handoff.status!=='failed';
      interviewSend.textContent=handoff?.status==='failed'?'手动重试':'发送'+label+'资料';
      interviewSend.disabled=interviewSending||identifying||pending||missing.length>0||data?.reviewed||data?.stage!==(second?'second':'invited');
      resumeSelect.disabled=demandSelect.disabled=interviewSending||pending;
      recipientSelect.disabled=interviewerMobile.disabled=interviewSending||pending||identifying||data?.reviewed;
      identifyButton.disabled=interviewSending||pending||identifying||data?.reviewed;
      const locked=interviewSending||pending||identifying||data?.reviewed;
      directoryQuery.disabled=directoryDepartment.disabled=locked;
      directoryRefresh.disabled=locked||directoryBusy;
      directoryPrevious.disabled=locked||directoryBusy||directoryPage<=1;
      directoryNext.disabled=locked||directoryBusy||directoryPage>=(directoryData?.pages||1);
      for(const b of directoryResults.children)b.disabled=locked||directoryBusy;
      return missing.length===0&&!handoff&&data?.stage==='invited';
    }
    function fillInterview(data){
      const oldResume=resumeSelect.value,oldDemand=demandSelect.value;
      const oldRecipient=recipientSelect.value,hadData=!!interviewData;
      if(interviewData?.config?.version!==data.config?.version){
        interviewerProof=null;directoryData=null;directoryBusy=false;++directoryEpoch;clearTimeout(directoryTimer);
        directoryResults.replaceChildren();identityFeedback.textContent='';
      }
      interviewData=data;resumeSelect.replaceChildren();demandSelect.replaceChildren();
      recipientSelect.replaceChildren();el('option','不发送，可直接填写二面意见',recipientSelect).value='';
      el('option','从公司通讯录选择（支持查询）',recipientSelect).value='directory';
      for(const person of data.recipients||[])el('option',person.name+(person.department?' · '+person.department:''),recipientSelect).value=person.bindingKey;
      el('option','其他钉钉人员（用手机号识别）',recipientSelect).value='mobile';
      recipientSelect.value=['mobile','directory'].includes(oldRecipient)||data.recipients?.some(r=>r.bindingKey===oldRecipient)?oldRecipient:
        !hadData&&interviewRound==='second'&&!data.handoff&&!data.reviewed?'directory':'';
      for(const resume of data.resumes||[])el('option',`${resume.filename} · ${resume.jobName}`,resumeSelect).value=resume.id;
      resumeSelect.value=data.resumes?.some(r=>r.id===oldResume)?oldResume:data.resumes?.[0]?.id||'';
      const matches=(data.demands||[]).filter(d=>d.resumeIds.includes(resumeSelect.value));
      el('option',matches.length>1?'请选择对应招聘需求':'暂无对应招聘需求',demandSelect).value='';
      for(const demand of matches)el('option',`${demand.position} · ${demand.department}`,demandSelect).value=demand.id;
      demandSelect.value=matches.some(d=>d.id===oldDemand)?oldDemand:matches.length===1?matches[0].id:'';
      const ready=renderInterview();
      if(!directoryFields.hidden&&!directoryData&&!directoryBusy)loadDirectory();
      return ready;
    }
    resumeSelect.addEventListener('change',()=>fillInterview(interviewData));
    demandSelect.addEventListener('change',()=>renderInterview());
    recipientSelect.addEventListener('change',()=>{interviewerProof=null;identityFeedback.textContent='';++directoryEpoch;
      directoryBusy=false;clearTimeout(directoryTimer);renderInterview();
      if(!directoryFields.hidden)loadDirectory();});
    interviewerMobile.addEventListener('input',()=>{interviewerProof=null;identityFeedback.textContent='';renderInterview();});
    directoryQuery.addEventListener('input',()=>{directoryPage=1;++directoryEpoch;clearTimeout(directoryTimer);
      directoryTimer=setTimeout(()=>loadDirectory(),300);});
    directoryQuery.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();directoryPage=1;loadDirectory();}});
    directoryDepartment.addEventListener('change',()=>{directoryPage=1;loadDirectory();});
    async function loadDirectory(refresh=false){
      clearTimeout(directoryTimer);
      if(!interviewing||!interviewDialog.open||directoryFields.hidden||!allowed()||!interviewData?.config?.verified)return;
      const epoch=interviewEpoch,id=interviewing.id,serial=++directoryEpoch,version=interviewData.config.version;
      directoryBusy=true;renderInterview();directoryStatus.textContent='正在查询公司通讯录…';
      try{
        const params=new URLSearchParams({configVersion:version,q:directoryQuery.value,
          departmentId:directoryDepartment.value,page:directoryPage});
        if(refresh)params.set('refresh','1');
        const data=await request('/dingtalk/directory?'+params);
        if(epoch!==interviewEpoch||serial!==directoryEpoch||interviewing?.id!==id||!allowed()||
          interviewData?.config?.version!==version||directoryFields.hidden)return;
        directoryData=data;directoryPage=data.page;
        const dept=directoryDepartment.value;
        directoryDepartment.replaceChildren();el('option','所有可见部门',directoryDepartment).value='';
        for(const row of data.departments||[])el('option',row.name,directoryDepartment).value=row.id;
        directoryDepartment.value=data.departments?.some(d=>d.id===dept)?dept:'';
        directoryResults.replaceChildren();
        for(const person of data.items||[]){
          const b=button('',directoryResults,()=>identifyInterviewer(person.userId));b.className='hr-directory-person';
          b.dataset.userId=person.userId;
          b.setAttribute('aria-pressed',String(interviewerProof?.userId===person.userId));
          el('strong',person.name,b);el('span',person.department||'直属人员',b);
          el('small','ID 尾号 '+person.userId.slice(-4),b);
        }
        directoryStatus.className=data.state==='failed'?'hr-error':'hr-hint';
        directoryStatus.textContent=data.state==='running'?`正在读取通讯录 · 已读取 ${data.departmentCount} 个部门、${data.employeeCount} 人…`:
          data.state==='failed'?data.error:`可查询 ${data.employeeCount} 人 · 当前找到 ${data.total} 人`+
            (!data.total?'，试试其他姓名或部门':'');
        directoryPager.hidden=data.state!=='complete'||data.pages<=1;
        directoryPageLabel.textContent=`第 ${data.page} / ${data.pages} 页`;
        if(data.state==='running')directoryTimer=setTimeout(()=>loadDirectory(),1500);
      }catch(error){
        if(epoch===interviewEpoch&&serial===directoryEpoch){directoryStatus.className='hr-error';directoryStatus.textContent=error.message;}
      }finally{
        if(epoch===interviewEpoch&&serial===directoryEpoch){directoryBusy=false;renderInterview();}
      }
    }
    async function identifyInterviewer(userId){
      if(!interviewing||identifying||!allowed()||!interviewData?.config?.verified)return;
      const id=interviewing.id,epoch=interviewEpoch;
      const chosenId=typeof userId==='string'?userId:'';
      interviewerProof=null;identifying=true;renderInterview();identityFeedback.textContent='正在核对钉钉员工身份…';
      try{
        const person=await request('/interview/identity',{talentId:id,...(chosenId?{userId:chosenId}:{mobile:interviewerMobile.value}),
          configVersion:interviewData.config.version});
        if(epoch!==interviewEpoch||interviewing?.id!==id||!allowed())return;
        interviewerProof=person;identityFeedback.textContent='已识别：'+person.name+'。请核对后点击发送二面资料。';
        for(const b of directoryResults.children)b.setAttribute('aria-pressed',String(b.dataset.userId===person.userId));
      }catch(error){if(epoch===interviewEpoch)identityFeedback.textContent=error.message;}
      finally{if(epoch===interviewEpoch){identifying=false;renderInterview();}}
    }
    async function refreshInterview(){
      clearTimeout(interviewTimer);
      if(!interviewing||!interviewDialog.open||!active||!allowed())return;
      const id=interviewing.id,epoch=interviewEpoch;
      try{
        const data=await request('/interview?talentId='+encodeURIComponent(id)+'&round='+interviewRound);
        if(epoch!==interviewEpoch||interviewing?.id!==id||!interviewDialog.open||!allowed())return;
        fillInterview(data);
        if(data.handoff?.status==='sent')load();
        if(['preparing','sending','submitted'].includes(data.handoff?.status))interviewTimer=setTimeout(refreshInterview,5000);
      }catch(error){if(epoch===interviewEpoch&&interviewing?.id===id)interviewFeedback.textContent=error.message;}
    }
    async function openInterview(row,autoStart=false,round=row.stage==='second'||row.interviewReviews?.second?'second':'first'){
      if(!allowed()||interviewSending)return;
      const epoch=++interviewEpoch;
      interviewing=row;interviewData=null;interviewRequest=crypto.randomUUID();interviewRound=round;
      ++directoryEpoch;clearTimeout(directoryTimer);directoryData=null;directoryPage=1;directoryBusy=false;identifying=false;
      directoryQuery.value='';directoryDepartment.value='';directoryResults.replaceChildren();directoryStatus.textContent='';
      interviewerProof=null;interviewerMobile.value='';recipientSelect.replaceChildren();identityFeedback.textContent='';
      interviewDownload.hidden=true;interviewDownload.removeAttribute('href');
      interviewTitle.textContent=(round==='second'?'二面资料 · ':autoStart?'开始一面 · ':'人才详情 · ')+row.name;interviewFeedback.textContent='正在读取岗位、简历和发送记录…';
      resumeSelect.replaceChildren();demandSelect.replaceChildren();interviewSummary.textContent='';interviewProgress.replaceChildren();
      interviewSend.disabled=true;interviewSettings.hidden=interviewSend.hidden=row.stage!=='invited';interviewDialog.showModal();
      await refreshInterview();
      if(epoch===interviewEpoch&&interviewing?.id===row.id&&autoStart&&renderInterview())await sendInterview();
    }
    async function sendInterview(){
      if(!interviewing||interviewSending||interviewSend.disabled||!allowed())return;
      const id=interviewing.id,data=interviewData,demand=selectedDemand(),epoch=interviewEpoch;
      let failure='';
      interviewSending=true;interviewSend.disabled=true;resumeSelect.disabled=demandSelect.disabled=true;
      interviewFeedback.textContent='正在提交'+(interviewRound==='second'?'二面':'一面')+'资料…';
      try{
        const person=interviewRound==='second'?selectedRecipient():null;
        const handoff=await request('/interview/start',{talentId:id,requestId:interviewRequest,
          round:interviewRound,
          version:data.version,resumeId:resumeSelect.value,demandId:demand.id,demandVersion:demand.version,
          recipientId:person?.userId||demand.recipientId,recipientBindingKey:person?.bindingKey||demand.recipientBindingKey||'',
          identityProof:person?.identityProof,
          configVersion:data.config.version,retryId:data.handoff?.status==='failed'?data.handoff.id:undefined});
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
      '权限管理中开通“成员信息读权限”和“根据手机号获取成员基本信息权限”，通讯录范围覆盖面试负责人；仍需核对工作通知与媒体上传权限。',
      '发布应用，将可使用范围包含面试负责人；如启用服务器出口 IP 白名单，添加 8.163.34.81，并以实际出口为准。',
      '先保存并校验应用，再到部门管理输入负责人在企业通讯录中的手机号，识别并核对姓名后确认绑定。系统自动保存企业 UserID，无需手工填写。',
      '招聘需求选择已绑定的归属部门，关联岗位自动使用该部门负责人；更换钉钉应用后需要重新绑定。识别和绑定不会发送测试消息。'
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
    const recipientSection=el('div','',settingsDialog,'hr-dingtalk-recipient');
    el('h3','部门负责人',recipientSection);
    el('p','在部门管理中用手机号识别、核对姓名并绑定，关联岗位自动使用。',recipientSection,'hr-hint');
    button('前往部门管理',el('div','',recipientSection,'hr-actions'),()=>{settingsDialog.close();location.hash='hr-departments';});
    async function loadSettings(epoch=settingsEpoch){
      const data=await request('/dingtalk');
      if(epoch!==settingsEpoch||!settingsDialog.open||!allowed()||!active)return;
      settingsData=data;appKey.value=data.config.appKey;agentId.value=data.config.agentId;
      appSecret.value='';appSecret.placeholder=data.config.hasSecret?'已保存；留空保留':'请输入应用密钥';
      settingsFeedback.textContent=data.config.verified?'应用凭证已校验。还需核对 AgentId、发布状态和负责人可见范围。':'尚未配置钉钉应用';
    }
    async function openSettings(){
      if(!allowed())return;
      const epoch=++settingsEpoch;
      settingsData=null;appSecret.value='';settingsFeedback.textContent='正在读取配置…';
      configSave.disabled=true;
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
      finally{settingsSaving=false;configSave.disabled=false;appSecret.value='';}
    });
    settingsDialog.addEventListener('close',()=>{++settingsEpoch;appSecret.value='';settingsData=null;});

    const reviewDialog=el('dialog','',root,'hr-pool-dialog hr-interview-review-dialog');
    const reviewHead=el('div','',reviewDialog,'hr-dingtalk-heading');
    const reviewTitle=el('h2','一面意见',reviewHead);
    const reviewClose=button('关闭',reviewHead,()=>reviewDialog.close());
    const reviewIntro=el('p','',reviewDialog,'hr-hint');
    const reviewOpinion=makeField('面试意见',reviewDialog,'textarea');reviewOpinion.rows=6;reviewOpinion.maxLength=5000;
    reviewOpinion.placeholder='记录候选人的能力表现、优势、疑虑及判断依据';
    const reviewFeedback=el('p','',reviewDialog,'hr-hint');reviewFeedback.setAttribute('role','status');
    const reviewActions=el('div','',reviewDialog,'hr-actions');
    const reviewReject=button('不通过',reviewActions,()=>saveReview('failed'));
    const reviewApprove=button('通过',reviewActions,()=>saveReview('passed'));reviewApprove.className='hr-primary';
    let reviewing=null,reviewRequest='',reviewEpoch=0,reviewSaving=false;
    function syncReviewActions(){
      const completed=!!reviewing?.interviewReviews?.[reviewing.round];
      reviewApprove.hidden=reviewReject.hidden=completed;
      reviewApprove.disabled=reviewReject.disabled=reviewSaving||!reviewOpinion.value.trim();
      reviewClose.disabled=reviewSaving;
    }
    reviewOpinion.addEventListener('input',syncReviewActions);
    reviewDialog.addEventListener('close',()=>{++reviewEpoch;reviewing=null;reviewOpinion.value='';reviewFeedback.textContent='';
      reviewTitle.textContent='面试意见';reviewIntro.textContent='';});
    reviewDialog.addEventListener('cancel',event=>{if(reviewSaving)event.preventDefault();});
    function openReview(row,round){
      if(!allowed()||reviewSaving)return;
      ++reviewEpoch;reviewing={...row,round};reviewRequest=crypto.randomUUID();
      const label=round==='first'?'一面':'二面',saved=row.interviewReviews?.[round];
      reviewTitle.textContent=`${label}意见 · ${row.name}`;
      reviewIntro.textContent=saved?`${label}结果：${saved.outcome==='passed'?'通过':'未通过'} · ${new Date(saved.createdAt).toLocaleString('zh-CN')}`:
        round==='first'?'填写一面意见后选择结果，通过后自动进入二面。':'通过后填写入职安排，并流转到入职安排及跟进。';
      reviewOpinion.value=saved?.opinion||'';reviewOpinion.readOnly=!!saved;reviewFeedback.textContent='';
      syncReviewActions();reviewDialog.showModal();
    }
    async function saveReview(decision){
      if(reviewSaving||!reviewing||!allowed()||!reviewOpinion.value.trim()||reviewing.interviewReviews?.[reviewing.round])return;
      if(reviewing.round==='second'&&decision==='passed'){
        if(!beginOnboarding){reviewFeedback.textContent='请更新页面以填写入职安排';return;}
        beginOnboarding(reviewing,{talentId:reviewing.id,version:reviewing.version,round:'second',outcome:'passed',
          opinion:reviewOpinion.value.trim(),requestId:reviewRequest},()=>{reviewDialog.close();navigate('onboarding');});
        return;
      }
      const epoch=reviewEpoch,row=reviewing;reviewSaving=true;syncReviewActions();reviewFeedback.textContent='正在保存面试意见…';
      try{
        await request('/interview/review',{talentId:row.id,version:row.version,round:row.round,outcome:decision,
          opinion:reviewOpinion.value.trim(),requestId:reviewRequest});
        if(epoch!==reviewEpoch||!active||!allowed())return;
        reviewDialog.close();stage=row.round==='first'&&decision==='passed'?'second':row.round;
        outcome=row.round==='first'&&decision==='passed'?'pending':decision;page=1;
        await load();
      }catch(error){if(epoch===reviewEpoch&&active&&allowed())reviewFeedback.textContent=error.message;}
      finally{reviewSaving=false;syncReviewActions();}
    }

    async function request(path,body){
      const response=await api('/api/v1/hr/pool'+path,body?{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)
      }:{});
      if(response.success===false||!response.data||typeof response.data!=='object')throw Error(response.message||'读取人才库失败');
      return response.data;
    }
    function fillDepartments(select,first,selected=''){
      select.replaceChildren();el('option',first,select).value='';
      for(const department of departments)el('option',department.name,select).value=department.id;
      select.value=selected;
    }
    function syncDepartments(){
      const options=[['','全部部门'],...departments.map(d=>[d.id,d.name]),['unassigned','待关联部门']];
      const directory=JSON.stringify(options);
      if(directory!==departmentDirectory){
        departmentDirectory=directory;departmentBar.replaceChildren();
        departmentButtons=options.map(([key,label])=>{
          const b=button(label,departmentBar,()=>{department=key;page=1;load();});
          b.dataset.department=key;return {b,key};
        });
      }
      departmentButtons.forEach(({b,key})=>b.setAttribute('aria-pressed',String(key===department)));
    }
    function syncOutcomes(counts){
      outcomeBar.hidden=!['first','second'].includes(stage);
      outcomeOptions.forEach(({option,key,label})=>{
        option.setAttribute('aria-pressed',String(key===outcome));
        if(counts)option.textContent=`${label} ${counts[key]||0}`;
      });
    }
    function renderPagination(data){
      previous.disabled=page<=1;next.disabled=page>=data.pages;
      pageNumbers.replaceChildren();pageButtons=[];
      const pages=[...new Set([1,page-1,page,page+1,data.pages])].filter(n=>n>=1&&n<=data.pages).sort((a,b)=>a-b);
      pages.forEach((number,index)=>{
        if(index&&number-pages[index-1]>1)el('span','…',pageNumbers,'hr-hint');
        const b=button(String(number),pageNumbers,()=>{if(page!==number){page=number;load();}});
        b.setAttribute('aria-label',`第 ${number} 页`);
        if(number===page)b.setAttribute('aria-current','page');
        pageButtons.push(b);
      });
      pageLabel.textContent=`第 ${page} / ${data.pages} 页 · 共 ${data.total} 人 · 每页 20 人`;
      pager.hidden=false;
    }
    async function load(){
      if(!allowed()||!active)return;
      const ticket=++generation;
      result.setAttribute('aria-busy','true');feedback.textContent='正在读取人才库…';
      previous.disabled=next.disabled=true;
      pageButtons.forEach(b=>b.disabled=true);
      tabButtons.forEach((b,i)=>b.setAttribute('aria-pressed',String(stages[i][0]===stage)));
      syncDepartments();syncOutcomes();
      try{
        const data=await request('?'+new URLSearchParams({stage,outcome,department,q:query.value.trim(),page:String(page)}));
        if(ticket!==generation||!active||!allowed())return;
        departments=data.departments||[];rows=data.items;page=data.page;syncDepartments();
        syncOutcomes(data.outcomeCounts);
        tabButtons.forEach((b,i)=>{const [key,label]=stages[i];b.textContent=`${label} ${(data.pendingCounts||data.counts)[key]||0}`;});
        render();
        feedback.textContent=`共 ${data.total} 位人才`;
        renderPagination(data);
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
        el('p','简历审批通过后进入储备库，也可从 AI 寻源中加入候选人；可以调整筛选条件查看。',empty,'hr-hint');return;
      }
      const grid=el('div','',result,'hr-card-grid hr-pool-grid');
      for(const row of rows){
        const card=el('article','',grid,'hr-person-card');
        const canInvite=row.stage==='invited';
        const head=el('div','',card,'hr-card-heading');
        const identity=el('div','',head,'hr-identity');
        const avatar=button(row.name.slice(0,1),identity,()=>openInterview(row));
        avatar.className='hr-avatar hr-pool-profile-link';avatar.setAttribute('aria-label',`查看${row.name}的详情`);
        const copy=el('div','',identity),heading=el('h3','',copy);
        const name=button(row.name,heading,()=>openInterview(row));name.className='hr-pool-name-link';
        name.title=`查看${row.name}的详情`;
        el('p',row.position||'职位待补充',copy,'hr-hint');
        el('span',row.stage==='onboarding'?'待入职':stages.find(([key])=>key===row.stage)?.[1]||'未设置阶段',head,'hr-badge');
        const tags=el('div','',card,'hr-tags');
        el('span',departments.find(d=>d.id===row.departmentId)?.name||'待关联部门',tags,'hr-badge');
        el('span',row.source==='boss'?'BOSS 人才':row.source==='passive'?'简历池审批':'手动录入',tags,'hr-badge');
        const reviewRound=['first','second'].includes(stage)?stage:['first','second'].includes(row.stage)?row.stage:null;
        const reviewResult=reviewRound&&row.interviewReviews?.[reviewRound];
        if(reviewRound)el('span',`${reviewRound==='first'?'一面':'二面'} · ${reviewResult?(reviewResult.outcome==='passed'?'通过':'未通过'):'待填写意见'}`,tags,'hr-badge');
        const facts=el('dl','',card,'hr-facts');
        for(const [label,value] of [['学历',row.education],['工作年限',row.experience],['城市',row.city],['最近公司',row.company]]){
          const item=el('div','',facts);el('dt',label,item);el('dd',value||'简历未提供',item);
        }
        if(row.notes)el('p',row.notes,card,'hr-excerpt hr-pool-note');
        const footer=el('div','',card,'hr-actions');
        if(canInvite)button('开始一面',footer,()=>openInterview(row,true)).className='hr-primary';
        if(row.stage==='second'&&reviewRound==='second'&&!reviewResult)
          button('发送二面资料',footer,()=>openInterview(row,false,'second'));
        if(reviewRound&&(row.stage===reviewRound||reviewResult)){
          button(reviewRound==='first'?'一面意见':'二面意见',footer,()=>openReview(row,reviewRound)).className='hr-primary';
        }
      }
    }
    async function openEditor(row={}){
      if(!allowed())return;
      editing={...row,...(!row.id?{createId:crypto.randomUUID()}: {})};editorTitle.textContent=row.id?'编辑人才':'加入储备人才库';
      Object.entries(inputs).forEach(([key,input])=>input.value=row[key]||'');
      stageInput.value=row.stage||'invited';fillDepartments(departmentInput,'待关联部门',row.departmentId||'');
      editorError.hidden=true;saveButton.disabled=true;cancel.disabled=false;
      dialog.showModal();
      const ticket=generation;
      try{
        const data=await request('?page=1');
        if(ticket!==generation||!dialog.open)return;
        departments=data.departments||[];fillDepartments(departmentInput,'待关联部门',row.departmentId||'');
        saveButton.disabled=false;
      }catch(error){if(dialog.open){editorError.textContent=error.message;editorError.hidden=false;}}
    }
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(saving||saveButton.disabled||!allowed())return;
      saving=true;saveButton.disabled=cancel.disabled=true;saveButton.textContent='正在保存…';
      editorError.hidden=true;const ticket=generation;
      const body={...editing,...Object.fromEntries(Object.entries(inputs).map(([k,v])=>[k,v.value.trim()])),
        stage:stageInput.value,departmentId:departmentInput.value,source:editing.source||'manual'};
      try{
        await request('/talents',body);
        if(ticket!==generation||!allowed())return;
        dialog.close();stage=body.stage||'invited';outcome=defaultOutcome(stage);query.value='';page=1;
        department=body.departmentId||'';navigate('pool');
      }catch(error){if(ticket===generation){editorError.textContent=error.message;editorError.hidden=false;}}
      finally{saving=false;saveButton.disabled=cancel.disabled=false;saveButton.textContent='保存人才';}
    });
    dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
    return {
      enter(){active=true;load();},
      leave(){active=false;++generation;dialog.close();interviewDialog.close();settingsDialog.close();reviewDialog.close();clearTimeout(interviewTimer);},
      add:openEditor,
      reset(){active=false;++generation;departments=[];rows=[];editing=null;stage='invited';outcome='all';department='';page=1;departmentDirectory='';syncDepartments();syncOutcomes();
        query.value='';dialog.close();interviewDialog.close();settingsDialog.close();reviewDialog.close();clearTimeout(interviewTimer);
        interviewing=null;interviewData=null;settingsData=null;appKey.value=agentId.value=appSecret.value='';
        ++directoryEpoch;clearTimeout(directoryTimer);directoryData=null;directoryBusy=false;interviewerProof=null;
        directoryQuery.value=interviewerMobile.value='';directoryDepartment.replaceChildren();directoryResults.replaceChildren();
        directoryStatus.textContent=identityFeedback.textContent='';recipientSelect.replaceChildren();
        interviewDownload.hidden=true;interviewDownload.removeAttribute('href');
        Object.values(inputs).forEach(input=>input.value='');
        departmentInput.replaceChildren();
        pageButtons=[];pageNumbers.replaceChildren();pageLabel.textContent='';pager.hidden=true;
        editorError.textContent='';
        result.replaceChildren();feedback.textContent='';}
    };
  };
})();
