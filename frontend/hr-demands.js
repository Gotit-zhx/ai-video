/* Recruitment planning uses platform APIs and the configured DRIVER model only. */
(() => {
  const statuses=[['all','全部'],['draft','待启动'],['recruiting','招聘中'],['paused','暂停'],['filled','已完成'],['cancelled','已取消']];
  globalThis.createHrDemands=({root,section,element:el,button,allowed})=>{
    const panel=section('demands');
    let active=false,generation=0,page=1,status='all',editing=null,saving=false,timer=null,pollBusy=false,pollErrors=0,outputTab='portrait',departments=[],directoryReady=false;
    const header=el('div','',panel,'hr-pool-toolbar'),intro=el('div','',header);
    el('h2','招聘需求池',intro);el('p','明确岗位需求，让寻源与面试有据可依。',intro,'hr-hint');
    const actions=el('div','',header,'hr-actions');
    button('刷新',actions,()=>load());
    button('新建需求',actions,()=>open()).className='hr-primary';
    const search=el('form','',panel,'hr-form');
    const field=(name,label,parent,tag='input')=>{
      const wrap=el('label','',parent,'hr-field');el('span',label,wrap);
      const input=el(tag,'',wrap);input.name=name;return input;
    };
    const q=field('demand-search','搜索招聘需求',search);q.placeholder='岗位、部门或经理';q.maxLength=100;
    button('搜索',search,()=>{page=1;load();});
    search.addEventListener('submit',event=>{event.preventDefault();page=1;load();});
    const tabs=el('div','',panel,'hr-pool-tabs');tabs.setAttribute('aria-label','需求状态');
    const statusButtons=statuses.map(([key,label])=>button(label,tabs,()=>{status=key;page=1;load();}));
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const results=el('div','',panel,'hr-result');
    const pager=el('div','',panel,'hr-pagination');
    const previous=button('上一页',pager,()=>{page--;load();});
    const pageLabel=el('span','',pager,'hr-hint');
    const next=button('下一页',pager,()=>{page++;load();});
    pager.hidden=true;previous.disabled=next.disabled=true;
    const dialog=el('dialog','',root,'hr-pool-dialog hr-demand-dialog');
    const form=el('form','',dialog),title=el('h2','新建招聘需求',form);
    el('p','保存岗位信息，一键生成三份招聘参考资料。',form,'hr-hint');
    const grid=el('div','',form,'hr-pool-fields'),inputs={};
    for(const [key,label,type,limit] of [['date','需求日期','date',10],['position','招聘岗位','text',200],['departmentId','归属部门','select',36],
      ['manager','部门经理','text',120],['category','岗位类别','text',100],
      ['headcount','编制人数','number',3],['years','最低工作年限（年）','number',4]]){
      const input=inputs[key]=field(key,label,grid,type==='select'?'select':'input');if(type!=='select')input.type=type;input.maxLength=limit;
      input.required=['date','position','departmentId','headcount'].includes(key);
      if(key==='headcount'){input.min='1';input.max='999';input.step='1';}
      if(key==='years'){input.min='0';input.max='60';input.step='0.5';}
    }
    inputs.category.placeholder='例如：技术、运营、销售、职能';
    inputs.manager.readOnly=true;inputs.manager.placeholder='选择部门后自动带出';
    inputs.departmentId.addEventListener('change',()=>{
      inputs.manager.value=departments.find(d=>d.id===inputs.departmentId.value)?.manager||'';
    });
    inputs.status=field('status','需求状态',grid,'select');
    statuses.slice(1).forEach(([key,label])=>el('option',label,inputs.status).value=key);
    inputs.education=field('education','最低学历',grid,'select');
    ['不限','高中 / 中专','大专','本科','硕士','博士'].forEach(label=>el('option',label,inputs.education).value=label);
    for(const [key,label] of [['responsibilities','岗位职责'],['requirements','任职要求']]){
      inputs[key]=field(key,label,form,'textarea');inputs[key].rows=4;inputs[key].maxLength=10000;
    }
    const error=el('p','',form,'hr-error');error.hidden=true;error.setAttribute('role','alert');
    const saveNote=el('p','',form,'hr-hint');saveNote.setAttribute('role','status');
    const formActions=el('div','',form,'hr-actions');
    const close=button('关闭',formActions,()=>{stopPoll();dialog.close();});
    const saveButton=el('button','保存需求',formActions);saveButton.type='submit';
    const generateButton=button('一键 AI 生成',formActions,generate);generateButton.className='hr-primary';
    const aiArea=el('section','',dialog,'hr-demand-ai');
    const aiHeader=el('div','',aiArea,'hr-section-heading');el('h3','AI 岗位分析',aiHeader);
    const aiStatus=el('span','尚未生成',aiHeader,'hr-hint');aiStatus.setAttribute('role','status');
    const aiMessage=el('p','',aiArea,'hr-hint');
    const retryStatus=button('重新检查生成状态',el('div','',aiArea,'hr-actions'),()=>{pollErrors=0;poll();});retryStatus.hidden=true;
    const outputTabs=el('div','',aiArea,'hr-pool-tabs');
    const outputButtons=[['portrait','岗位人才画像'],['competencies','岗位胜任能力'],['interviewQuestions','结构化面试题']]
      .map(([key,label])=>button(label,outputTabs,()=>{outputTab=key;renderOutput();}));
    const output=el('div','',aiArea,'hr-result');
    const request=async(path,body)=>{
      const res=await api('/api/v1/hr/demands'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
      if(res.success===false)throw Error(res.message||'操作失败');return res.data;
    };
    const stopPoll=()=>{clearInterval(timer);timer=null;};
    function paintState(){
      const running=editing?.aiStatus==='running';
      Object.values(inputs).forEach(input=>input.disabled=saving||running);
      saveButton.disabled=generateButton.disabled=saving||running||!directoryReady;
      close.disabled=saving;
      generateButton.textContent=saving?'正在提交…':running?'驱动模型生成中…':editing?.aiResult?.portrait?'重新 AI 生成':'一键 AI 生成';
      aiStatus.textContent=running?'驱动模型正在生成':editing?.aiStatus==='complete'?`已生成 · ${editing.aiResult.modelId||'驱动模型'}`
        :editing?.aiStatus==='failed'?'生成失败':editing?.aiStatus==='stale'?'需求已更新':'尚未生成';
      aiMessage.textContent=running?'正在生成画像、胜任能力和面试题。可以关闭窗口，结果会自动保存。'
        :editing?.aiStatus==='failed'?editing.aiError:editing?.aiStatus==='stale'?'下面是旧版需求的分析，请根据最新信息重新生成。'
        :'根据已填写的岗位信息生成，结果供招聘团队审核和完善。';
      aiHeader.classList.toggle('is-generating',running);
      const oldResult=editing?.aiResult?.sourceVersion&&editing.aiResult.sourceVersion!==editing.version;
      if(oldResult&&editing?.aiStatus!=='stale')aiMessage.textContent+=' 下方保留的是旧版需求的结果。';
      renderOutput();
    }
    function renderOutput(){
      output.replaceChildren();
      outputButtons.forEach((b,i)=>b.setAttribute('aria-pressed',String(['portrait','competencies','interviewQuestions'][i]===outputTab)));
      const data=editing?.aiResult;
      if(!data?.portrait){el('p','填写岗位职责和任职要求后，点击“一键 AI 生成”。',output,'hr-hint');return;}
      const list=(label,items,parent)=>{
        el('h4',label,parent);const ul=el('ul','',parent,'hr-demand-list');
        for(const item of items||[])el('li',String(item),ul);
      };
      if(outputTab==='portrait'){
        el('p',data.portrait.summary,output,'hr-demand-summary');
        const cards=el('div','',output,'hr-card-grid');
        for(const [key,label] of [['mustHave','硬性要求'],['niceToHave','优先条件'],['experience','相关经历'],['evaluationFocus','验证重点']]){
          list(label,data.portrait[key],el('article','',cards,'hr-record'));
        }
      }else if(outputTab==='competencies'){
        const cards=el('div','',output,'hr-card-grid');
        for(const item of data.competencies||[]){
          const card=el('article','',cards,'hr-record');
          el('h4',item.name,card);el('p',item.description,card);
          el('p','期望水平：'+item.level,card,'hr-hint');el('p','验证证据：'+item.evidence,card,'hr-hint');
        }
      }else for(const [index,item] of (data.interviewQuestions||[]).entries()){
        const card=el('article','',output,'hr-record');el('span',`问题 ${index+1} · ${item.competency}`,card,'hr-badge');
        el('h4',item.question,card);list('建议追问',item.followUps,card);
        el('h4','评分标准',card);el('p',item.evaluationCriteria,card);
        list('优秀答案信号',item.strongAnswerSignals,card);
      }
    }
    async function load(){
      if(!active||!allowed())return;const ticket=++generation;
      feedback.textContent='正在读取招聘需求…';previous.disabled=next.disabled=true;
      try{
        const data=await request('?'+new URLSearchParams({status,q:q.value.trim(),page:String(page)}));
        if(ticket!==generation||!active||!allowed())return;
        results.replaceChildren();page=data.page;
        statusButtons.forEach((b,i)=>{const [key,label]=statuses[i];b.textContent=`${label} ${data.counts[key]||0}`;b.setAttribute('aria-pressed',String(key===status));});
        feedback.textContent=`共 ${data.total} 项需求`;pageLabel.textContent=`${page} / ${data.pages}`;
        pager.hidden=data.pages<=1;previous.disabled=page<=1;next.disabled=page>=data.pages;
        if(!data.items.length){el('p','暂无招聘需求，点击“新建需求”开始。',results,'hr-pool-empty');return;}
        const cards=el('div','',results,'hr-card-grid');
        for(const row of data.items){
          const card=el('article','',cards,'hr-person-card'),head=el('div','',card,'hr-card-heading');
          el('h3',row.position,head);el('span',statuses.find(([k])=>k===row.status)?.[1],head,'hr-badge');
          el('p',`${row.department} · ${row.category||'类别未填写'}`,card,'hr-hint');
          const facts=el('dl','',card,'hr-facts');
          for(const [label,value] of [['需求日期',row.date],['编制人数',`${row.headcount} 人`],['部门经理',row.manager||'未填写'],
            ['最低学历',row.education||'不限'],['最低工作年限',Number(row.years)>0?`${row.years} 年`:'不限']]){
            const item=el('div','',facts);el('dt',label,item);el('dd',value,item);
          }
          const actions=el('div','',card,'hr-actions');button('查看 / 编辑',actions,()=>open(row));
          el('span',row.aiStatus==='running'?'AI 生成中':row.aiStatus==='complete'?'AI 分析已就绪':row.aiStatus==='stale'?'AI 结果待更新':row.aiStatus==='failed'?'AI 生成失败':'待生成 AI 分析',actions,'hr-hint');
        }
      }catch(err){if(ticket===generation){feedback.textContent='读取失败';results.replaceChildren();el('p',err.message,results,'hr-error');}}
    }
    async function open(row={}){
      if(!allowed())return;stopPoll();pollErrors=0;retryStatus.hidden=true;
      const now=new Date(),date=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
      editing={date,headcount:1,years:0,status:'draft',education:'不限',createId:crypto.randomUUID(),...row};
      title.textContent=row.id?'招聘需求详情':'新建招聘需求';
      Object.entries(inputs).forEach(([key,input])=>input.value=editing[key]??'');
      directoryReady=false;inputs.departmentId.replaceChildren();el('option','正在读取部门…',inputs.departmentId).value='';
      error.hidden=true;saveNote.textContent='';outputTab='portrait';paintState();dialog.showModal();
      const ticket=generation;
      try{
        const response=await api('/api/v1/hr/departments');
        if(ticket!==generation||!dialog.open||!allowed())return;
        departments=response.data.items;inputs.departmentId.replaceChildren();
        el('option',departments.length?'请选择归属部门':'请先在部门管理中新建部门',inputs.departmentId).value='';
        for(const department of departments.filter(d=>d.status==='ACTIVE'||d.id===row.departmentId))
          el('option',department.name+(department.status==='ACTIVE'?'':'（已停用）'),inputs.departmentId).value=department.id;
        inputs.departmentId.value=row.departmentId||'';
        if(row.department&&!row.departmentId)saveNote.textContent=`原部门：${row.department}。请从统一部门列表选择后保存。`;
        if(row.departmentId)inputs.manager.value=departments.find(d=>d.id===row.departmentId)?.manager||row.manager||'';
        directoryReady=true;paintState();
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;}}
      if(row.aiStatus==='running')startPoll();
    }
    function payload(){
      return {...editing,...Object.fromEntries(Object.entries(inputs).map(([key,input])=>[key,String(input.value).trim()])),
        department:departments.find(d=>d.id===inputs.departmentId.value)?.name||editing.department||'',
        headcount:Number(inputs.headcount.value),years:Number(inputs.years.value)};
    }
    async function saveSnapshot(){
      const body=payload();
      const changed=!editing.id||body.department!==editing.department||Object.keys(inputs).some(key=>String(body[key])!==String(editing[key]??''));
      return changed?await request('/save',body):editing;
    }
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(saving||!directoryReady||editing?.aiStatus==='running'||!allowed())return;
      saving=true;paintState();error.hidden=true;const ticket=generation;
      try{
        const saved=await saveSnapshot();
        if(ticket!==generation||!allowed())return;
        editing=saved;saveNote.textContent='需求已保存';title.textContent='招聘需求详情';
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;}}
      finally{if(ticket===generation){saving=false;paintState();}}
    });
    async function generate(){
      if(saving||!directoryReady||editing?.aiStatus==='running'||!allowed()||!form.reportValidity())return;
      if(!inputs.responsibilities.value.trim()||!inputs.requirements.value.trim()){
        error.textContent='请先填写岗位职责和任职要求';error.hidden=false;return;
      }
      if(editing?.aiResult?.portrait&&!confirm('重新生成会再次调用驱动模型，成功后替换现有分析。继续吗？'))return;
      saving=true;paintState();error.hidden=true;const ticket=generation;
      try{
        const saved=await saveSnapshot();
        if(ticket!==generation||!allowed())return;
        editing=saved;
        const generated=await request('/generate',{id:saved.id,version:saved.version,regenerate:!!saved.aiResult?.portrait});
        if(ticket!==generation||!allowed())return;
        editing=generated;
        saveNote.textContent='需求已保存，生成结果将自动关联到此需求';pollErrors=0;startPoll();
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;}}
      finally{if(ticket===generation){saving=false;paintState();}}
    }
    function startPoll(){stopPoll();timer=setInterval(poll,2500);}
    async function poll(){
      if(pollBusy||!dialog.open||!editing?.id||!allowed()){if(!dialog.open)stopPoll();return;}
      pollBusy=true;const ticket=generation,id=editing.id;
      try{
        const data=await request('?id='+encodeURIComponent(id));
        if(ticket!==generation||!dialog.open||editing?.id!==id||!allowed())return;
        editing=data.item;pollErrors=0;retryStatus.hidden=true;paintState();
        if(editing.aiStatus!=='running')stopPoll();else if(!timer)startPoll();
      }catch(err){
        if(ticket!==generation)return;
        if(++pollErrors>=3){stopPoll();retryStatus.hidden=false;aiMessage.textContent='暂时无法读取生成状态，后台结果仍会保存。请重新检查。';}
      }finally{pollBusy=false;}
    }
    dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();else stopPoll();});
    dialog.addEventListener('close',()=>{stopPoll();if(active&&!saving)load();});
    return {
      enter(){active=true;load();},
      leave(){active=false;++generation;stopPoll();dialog.close();},
      reset(){active=false;++generation;stopPoll();dialog.close();editing=null;saving=false;page=1;status='all';
        departments=[];directoryReady=false;inputs.departmentId.replaceChildren();
        q.value='';Object.values(inputs).forEach(input=>input.value='');results.replaceChildren();output.replaceChildren();
        error.textContent=saveNote.textContent=aiMessage.textContent='';}
    };
  };
})();
