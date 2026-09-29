/* Platform records and a human-authored invitation outbox; no direct BOSS call. */
(() => {
  const stages = [['all','全部'],['invited','邀约'],['first','一面'],['second','二面'],['hired','已录取']];
  globalThis.createHrPool = ({root, section, element:el, button, allowed, navigate}) => {
    const panel=section('pool');
    let stage='all',page=1,generation=0,active=false,projects=[],rows=[],editing=null,saving=false;
    let messaging=null,messageRequest='',messageTimer=null,messageSending=false,messageBlocked=false;
    const toolbar=el('div','',panel,'hr-pool-toolbar');
    const intro=el('div','',toolbar);
    el('h2','让合适的人，始终在视野里',intro);
    el('p','按项目组沉淀人才，按招聘阶段持续跟进。',intro,'hr-hint');
    const actions=el('div','',toolbar,'hr-actions');
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
    const messageDialog=el('dialog','',root,'hr-pool-dialog hr-invite-dialog');
    const messageTitle=el('h2','发送邀约',messageDialog);
    el('p','编辑好消息后发送给这位求职者的 BOSS 会话。助手连接原招聘账号后执行。',messageDialog,'hr-hint');
    const messageText=makeField('邀约内容',messageDialog,'textarea');
    messageText.rows=6;messageText.maxLength=1000;
    messageText.placeholder='例如：您好，想邀请您进一步沟通，您明天下午方便参加面试吗？';
    const messageFeedback=el('p','',messageDialog,'hr-hint');messageFeedback.setAttribute('role','status');
    const messageActions=el('div','',messageDialog,'hr-actions');
    button('关闭',messageActions,()=>{messageDialog.close();clearTimeout(messageTimer);messaging=null;});
    const messageSend=button('发送邀约',messageActions,sendInvitation);messageSend.className='hr-primary';
    el('h3','发送记录',messageDialog,'hr-invite-record-title');
    const messageHistory=el('div','',messageDialog,'hr-invite-history');
    messageText.addEventListener('input',()=>{messageRequest=crypto.randomUUID();});
    messageDialog.addEventListener('close',()=>{messaging=null;clearTimeout(messageTimer);messageText.value='';});
    const messageStates={queued:'等待助手发送',sending:'正在发送',sent:'已发送',uncertain:'结果待核对，请查看 BOSS 会话',failed:'发送未完成',cancelled:'已取消'};

    async function refreshMessages(){
      if(!messaging||!messageDialog.open||!active||!allowed())return;
      const id=messaging.id;
      clearTimeout(messageTimer);
      try{
        const data=await request('/messages?talentId='+encodeURIComponent(id));
        if(messaging?.id!==id||!messageDialog.open)return;
        messageHistory.replaceChildren();
        messageBlocked=(data.items||[]).some(item=>['queued','sending','uncertain'].includes(item.status));
        messageSend.disabled=messageBlocked||messageSending;
        if(!data.items?.length)el('p','暂无发送记录',messageHistory,'hr-hint');
        for(const item of data.items||[]){
          const card=el('div','',messageHistory,'hr-invite-record');
          el('p',item.message,card,'hr-prose');
          el('p',[messageStates[item.status]||item.status,new Date(item.updatedAt).toLocaleString()].join(' · '),card,'hr-hint');
        }
      }catch(error){if(messaging?.id===id)messageFeedback.textContent=error.message;}
      finally{if(messaging?.id===id&&messageDialog.open)messageTimer=setTimeout(refreshMessages,5000);}
    }
    async function openMessage(row){
      if(!allowed()||messageSending)return;
      messaging=row;messageRequest=crypto.randomUUID();messageBlocked=false;
      messageTitle.textContent='邀约 · '+row.name;messageText.value='';messageFeedback.textContent='';
      messageSend.disabled=true;messageHistory.replaceChildren();
      messageDialog.showModal();await refreshMessages();
    }
    async function sendInvitation(){
      if(!messaging||messageSending||messageBlocked||!allowed())return;
      const message=messageText.value.trim();
      if(!message){messageFeedback.textContent='请先填写邀约内容。';messageText.focus?.();return;}
      const id=messaging.id;
      messageSending=true;messageSend.disabled=true;messageText.disabled=true;
      try{
        const result=await request('/messages',{talentId:id,requestId:messageRequest,message});
        if(messaging?.id!==id||!messageDialog.open)return;
        messageFeedback.textContent=messageStates[result.status]||'已提交';
        messageText.value='';messageRequest=crypto.randomUUID();await refreshMessages();
      }catch(error){if(messaging?.id===id)messageFeedback.textContent=error.message;}
      finally{messageSending=false;messageText.disabled=false;messageSend.disabled=messageBlocked;}
    }

    async function request(path,body){
      const response=await api('/api/v1/hr/pool'+path,body?{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)
      }:{});
      if(response.success===false)throw Error(response.message||'读取人才库失败');
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
        const canInvite=row.stage==='invited'&&row.source==='passive';
        if(canInvite){
          card.className+=' hr-invitable-card';card.tabIndex=0;card.setAttribute('role','button');
          card.setAttribute('aria-label','编辑并发送邀约给'+row.name);
          card.addEventListener('click',event=>{if(!event.target?.closest?.('button,a,input,select,textarea'))openMessage(row);});
          card.addEventListener('keydown',event=>{
            if(event.target===card&&['Enter',' '].includes(event.key)){event.preventDefault();openMessage(row);}
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
        if(canInvite)button('发送邀约',footer,()=>openMessage(row)).className='hr-primary';
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
      leave(){active=false;++generation;dialog.close();projectDialog.close();messageDialog.close();messaging=null;clearTimeout(messageTimer);},
      add:openEditor,
      reset(){active=false;++generation;projects=[];rows=[];editing=null;stage='all';page=1;
        query.value='';projectFilter.value='';dialog.close();projectDialog.close();messageDialog.close();messaging=null;messageText.value='';clearTimeout(messageTimer);
        Object.values(inputs).forEach(input=>input.value='');projectName.value='';
        [projectFilter,projectInput,projectSelect].forEach(select=>select.replaceChildren());
        editorError.textContent=projectError.textContent='';
        result.replaceChildren();feedback.textContent='';}
    };
  };
})();
