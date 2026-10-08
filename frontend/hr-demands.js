/* Recruitment planning uses platform APIs and the configured DRIVER model only. */
(() => {
  const statuses=[['all','全部'],['draft','待启动'],['recruiting','招聘中'],['paused','暂停'],['filled','已完成'],['cancelled','已取消']];
  const jobId=row=>String(row?.encryptJobId||row?.encJobId||row?.encryptId||row?.jobId||row?.job_id||row?.id||'');
  function jobRows(value,depth=0){
    if(depth>5||!value)return [];
    if(Array.isArray(value))return value.filter(row=>row&&typeof row==='object'&&/^[\w-]{1,180}$/.test(jobId(row)));
    for(const key of ['jobList','list','records','items','jobs','data','result','zpData']){
      const rows=jobRows(value[key],depth+1);if(rows.length)return rows;
    }
    return [];
  }
  function jobDetail(data,id){
    let row=data;
    for(let i=0;i<5&&row&&typeof row==='object';i++){
      const description=row.jobDesc||row.description||row.jobDescription||row.postDescription;
      if(typeof description==='string'&&description.trim()){
        const returned=row.encryptJobId||row.encJobId||row.encryptId||row.jobId;
        if(returned&&String(returned)!==id)throw Error('返回的岗位与所选职位不一致，请重新读取');
        return {description,name:String(row.jobName||row.name||row.title||''),
          education:String(row.degreeName||row.degreeDesc||row.education||''),
          experience:String(row.experienceName||row.workYearDesc||row.experience||'')};
      }
      row=row?.jobInfo||row?.jobDetail||row?.job||row?.data||row?.zpData;
    }
    throw Error('该职位没有返回完整 JD，本次未调用 AI，请在职位管理核对后重试');
  }
  globalThis.HeyiHrJobJD={jobRows,jobDetail};
  globalThis.createHrDemands=({root,section,element:el,button,allowed,hrApi})=>{
    const panel=section('demands');
    let active=false,generation=0,page=1,status='all',editing=null,saving=false,timer=null,pollBusy=false,pollErrors=0,outputTab='portrait',departments=[],directoryReady=false;
    let jobs=[],jobAccount='',jobsReady=false,jdLoading=false,jdGeneration=0,jobSnapshot=null,submitPhase='',uploading=false;
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
    for(const [key,label,type,limit] of [['date','需求日期','date',10],['jobId','招聘岗位','select',180],['departmentId','归属部门','select',36],
      ['manager','部门经理','text',120],['category','岗位类别','text',100],
      ['headcount','编制人数','number',3],['years','最低工作年限（年）','number',4]]){
      const input=inputs[key]=field(key,label,grid,type==='select'?'select':'input');if(type!=='select')input.type=type;input.maxLength=limit;
      input.required=['date','jobId','departmentId','headcount'].includes(key);
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
    const jdArea=el('details','',form,'hr-demand-jd');
    el('summary','关联职位 JD',jdArea);
    const jdNote=el('p','选择具体职位后读取岗位职责与任职要求。',jdArea,'hr-hint');
    jdNote.setAttribute('role','status');
    const jdText=el('div','',jdArea,'hr-prose');
    for(const [key,label] of [['responsibilities','补充岗位职责（选填）'],['requirements','补充任职要求（选填）']]){
      inputs[key]=field(key,label,form,'textarea');inputs[key].rows=4;inputs[key].maxLength=10000;
    }
    const error=el('p','',form,'hr-error');error.hidden=true;error.setAttribute('role','alert');
    const saveNote=el('p','',form,'hr-hint');saveNote.setAttribute('role','status');
    const formActions=el('div','',form,'hr-actions');
    const close=button('关闭',formActions,()=>{stopPoll();dialog.close();});
    dialog.addEventListener('close',()=>{jdGeneration++;jdLoading=false;});
    const saveButton=el('button','保存需求',formActions);saveButton.type='submit';
    const generateButton=button('一键 AI 生成',formActions,generate);generateButton.className='hr-primary';
    const aiArea=el('section','',dialog,'hr-demand-ai');
    const aiHeader=el('div','',aiArea,'hr-section-heading');el('h3','AI 岗位分析',aiHeader);
    const aiStatus=el('span','尚未生成',aiHeader,'hr-hint');aiStatus.setAttribute('role','status');
    const aiProgress=el('div','',aiArea,'hr-demand-progress');
    const progressTrack=el('div','',aiProgress,'hr-demand-progress-track');
    progressTrack.setAttribute('role','progressbar');progressTrack.setAttribute('aria-label','岗位分析生成进度');
    const progressFill=el('div','',progressTrack,'hr-demand-progress-fill');
    const progressSteps=el('div','',aiProgress,'hr-demand-progress-steps');
    const stepNodes=['提交需求','模型生成','结构校验','完成'].map(label=>el('span',label,progressSteps));
    const aiMessage=el('p','',aiArea,'hr-hint');
    const referenceArea=el('div','',aiArea,'hr-demand-references');
    const referenceHeader=el('div','',referenceArea,'hr-section-heading');
    const referenceLabel=el('label','',referenceHeader,'hr-demand-reference-upload');
    el('strong','参考简历（选填）',referenceLabel);
    const referenceFiles=el('input','',referenceLabel);referenceFiles.type='file';referenceFiles.multiple=true;
    referenceFiles.accept='.pdf,.docx';referenceFiles.setAttribute('aria-label','上传岗位分析参考简历');
    el('p','可上传优秀员工简历，AI 会结合岗位 JD 提炼能力。未上传时，仍按岗位 JD 生成。支持 PDF、DOCX，每份不超过 10 MB，最多 10 份。',referenceArea,'hr-hint');
    const referenceStatus=el('p','',referenceArea,'hr-hint');referenceStatus.setAttribute('role','status');
    const referenceRows=el('div','',referenceArea,'hr-sourcing-references');
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
      Object.values(inputs).forEach(input=>input.disabled=saving||uploading||running||jdLoading);
      inputs.jobId.disabled ||= !jobsReady;
      inputs.departmentId.disabled ||= !directoryReady;
      saveButton.disabled=generateButton.disabled=saving||uploading||running||jdLoading||!directoryReady||!jobsReady;
      close.disabled=saving||uploading;
      referenceFiles.disabled=saving||uploading||running||jdLoading||!directoryReady||!jobsReady;
      referenceRows.replaceChildren();
      for(const item of editing?.references||[]){
        const row=el('div','',referenceRows,'hr-sourcing-reference');
        el('strong',item.filename,row);
        const remove=button('移除',row,()=>changeReferences([],item.id));
        remove.className='hr-text-button';remove.disabled=referenceFiles.disabled;
      }
      const elapsed=running&&editing.aiStartedAt?Math.max(0,Math.floor(Date.now()/1000-editing.aiStartedAt)):0;
      generateButton.textContent=saving?(submitPhase||'正在提交…'):running?'驱动模型生成中…':editing?.aiResult?.portrait?'重新 AI 生成':'一键 AI 生成';
      aiStatus.textContent=running?`驱动模型正在生成 · 已等待 ${elapsed} 秒`:editing?.aiStatus==='complete'?`已生成 · ${editing.aiResult.modelId||'驱动模型'}`
        :editing?.aiStatus==='failed'?'生成失败':editing?.aiStatus==='stale'?'需求已更新':'尚未生成';
      aiMessage.textContent=running?'请求已提交，正在等待驱动模型返回画像、胜任能力和面试题。完成后会自动显示；可以关闭窗口，结果仍会保存。'
        :editing?.aiStatus==='failed'?editing.aiError:editing?.aiStatus==='stale'?'下面是旧版需求的分析，请根据最新信息重新生成。'
        :'生成前读取所选职位的最新 JD，结合补充信息生成，结果供招聘团队审核和完善。';
      aiHeader.classList.toggle('is-generating',running);
      const phase=editing?.aiProgress?.phase||'connecting';
      const step=editing?.aiStatus==='complete'?3:phase==='validating'?2:phase==='generating'?1:0;
      aiProgress.hidden=!(saving||running||editing?.aiStatus==='failed'||editing?.aiStatus==='complete');
      aiProgress.classList.toggle('is-failed',editing?.aiStatus==='failed');
      aiProgress.classList.toggle('is-running',saving||running);
      progressFill.style.width=`${[12,45,80,100][step]}%`;
      // Generation has no known total. The bar represents stages, not a guessed percentage.
      progressTrack.removeAttribute('aria-valuenow');
      progressTrack.setAttribute('aria-valuetext',editing?.aiStatus==='failed'?'生成失败':saving?submitPhase||'提交需求':stepNodes[step].textContent);
      stepNodes.forEach((node,index)=>node.classList.toggle('is-current',index===step));
      if(running){
        const received=Number(editing.aiProgress?.receivedChars||0);
        aiStatus.textContent=`${['正在连接驱动模型','驱动模型生成中','正在校验分析结构'][Math.min(step,2)]} · 已等待 ${elapsed} 秒`;
        aiMessage.textContent=step===2?'模型已返回，正在校验画像、胜任能力和面试题。'
          :received>0?`已接收 ${received.toLocaleString()} 字，完成校验后自动显示。可以关闭窗口，进度和结果仍会保存。`
          :'请求已提交，正在等待模型响应。可以关闭窗口，进度和结果仍会保存。';
      }
      const oldResult=editing?.aiResult?.sourceVersion&&editing.aiResult.sourceVersion!==editing.version;
      if(oldResult&&editing?.aiStatus!=='stale')aiMessage.textContent+=' 下方保留的是旧版需求的结果。';
      renderOutput();
    }
    function renderOutput(){
      output.replaceChildren();
      outputButtons.forEach((b,i)=>b.setAttribute('aria-pressed',String(['portrait','competencies','interviewQuestions'][i]===outputTab)));
      const data=editing?.aiResult;
      if(!data?.portrait){
        el('p',editing?.aiStatus==='running'?'结果返回后将在这里显示。'
          :editing?.aiStatus==='failed'?'需求和关联 JD 已保留，本次没有生成结果。'
          :'关联具体职位后即可生成；岗位职责和任职要求可额外补充。',output,'hr-hint');return;
      }
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
    async function readJobs(){
      const status=(await hrApi('/api/v1/hr/login/status')).data;
      if(status.state!=='complete'||status.paused||!/^[a-f0-9]{64}$/.test(status.accountKey||''))throw Error('请先连接本机招聘助手，再选择招聘职位');
      if(!status.capabilities?.includes('job-jd'))throw Error('请更新招聘助手到 0.1.9 或以上版本，以读取完整岗位 JD');
      const response=(await hrApi('/api/v1/hr/jobs')).data;
      if(response?.ok!==true)throw Error(response?.error?.message||'职位列表读取失败，请稍后手动重试');
      const after=(await hrApi('/api/v1/hr/login/status')).data;
      if(after.accountKey!==status.accountKey||after.state!=='complete'||after.paused)throw Error('招聘账号已变化，请重新打开需求并选择职位');
      return {account:status.accountKey,rows:jobRows(response.data)};
    }
    async function readSelectedJob(){
      const id=inputs.jobId.value,selected=jobs.find(row=>jobId(row)===id);
      if(!selected)throw Error('请从当前账号选择具体职位');
      const ticket=++jdGeneration;jdLoading=true;jdNote.textContent='正在读取该职位的最新 JD…';paintState();
      try{
        const status=(await hrApi('/api/v1/hr/login/status')).data;
        if(status.accountKey!==jobAccount||status.state!=='complete'||status.paused)throw Error('招聘账号已变化，请重新打开需求并选择职位');
        const result=(await hrApi('/api/v1/hr/jobs?'+new URLSearchParams({jobId:id}))).data;
        if(result?.ok!==true)throw Error(result?.error?.message||'岗位 JD 读取失败，本次未调用 AI');
        if(ticket!==jdGeneration||!dialog.open||!allowed())throw Error('职位读取已取消');
        const detail=jobDetail(result.data,id);
        const document=new DOMParser().parseFromString(detail.description.replace(/<br\s*\/?>|<\/(?:p|div|li)>/gi,'\n'),'text/html');
        document.querySelectorAll('script,style').forEach(node=>node.remove());
        const text=document.body.textContent.trim();
        if(!text||text.length>8000)throw Error(text?'岗位 JD 超过 8000 字，请精简岗位内容后重试':'该岗位 JD 为空，本次未调用 AI');
        const after=(await hrApi('/api/v1/hr/login/status')).data;
        if(after.accountKey!==jobAccount||after.state!=='complete'||after.paused)throw Error('招聘账号已变化，本次未调用 AI');
        if(ticket!==jdGeneration||!dialog.open||!allowed())throw Error('职位读取已取消');
        jobSnapshot={jobId:id,jobAccountKey:jobAccount,position:detail.name||String(selected.jobName||selected.name||selected.title),
          jobDescription:text,jobFetchedAt:new Date().toISOString()};
        jdText.textContent=text;jdNote.textContent=`已读取「${jobSnapshot.position}」的 JD · AI 将结合这份原文生成`;
        return jobSnapshot;
      }catch(err){if(ticket===jdGeneration){jobSnapshot=null;jdText.textContent='';jdNote.textContent=err.message;}throw err;}
      finally{if(ticket===jdGeneration){jdLoading=false;paintState();}}
    }
    inputs.jobId.addEventListener('change',async()=>{
      jobSnapshot=null;jdText.textContent='';error.hidden=true;
      try{await readSelectedJob();}catch(err){error.textContent=err.message;error.hidden=false;}
    });
    async function open(row={}){
      if(!allowed()||saving||uploading)return;++generation;stopPoll();pollErrors=0;retryStatus.hidden=true;
      const now=new Date(),date=`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
      editing={date,headcount:1,years:0,status:'draft',education:'不限',createId:crypto.randomUUID(),...row};
      title.textContent=row.id?'招聘需求详情':'新建招聘需求';
      Object.entries(inputs).forEach(([key,input])=>input.value=editing[key]??'');
      directoryReady=false;jobsReady=false;jobSnapshot=row.jobId?{jobId:row.jobId,jobAccountKey:row.jobAccountKey,
        position:row.position,jobDescription:row.jobDescription,jobFetchedAt:row.jobFetchedAt}:null;
      const opening=++jdGeneration;jdLoading=false;jdText.textContent=row.jobDescription||'';
      jobs=[];jobAccount='';
      jdNote.textContent=row.jobDescription?'已保存关联职位 JD；生成前会重新读取最新内容。':'选择具体职位后读取岗位职责与任职要求。';
      inputs.jobId.replaceChildren();el('option','正在读取职位…',inputs.jobId).value='';
      inputs.departmentId.replaceChildren();el('option','正在读取部门…',inputs.departmentId).value='';
      error.hidden=true;saveNote.textContent=referenceStatus.textContent='';referenceFiles.value='';outputTab='portrait';paintState();dialog.showModal();
      const ticket=generation;
      try{
        const [departmentResult,jobResult]=await Promise.allSettled([api('/api/v1/hr/departments'),readJobs()]);
        if(ticket!==generation||opening!==jdGeneration||!dialog.open||!allowed())return;
        if(departmentResult.status==='rejected')throw departmentResult.reason;
        const response=departmentResult.value;
        departments=response.data.items;inputs.departmentId.replaceChildren();
        el('option',departments.length?'请选择归属部门':'请先在部门管理中新建部门',inputs.departmentId).value='';
        for(const department of departments.filter(d=>d.status==='ACTIVE'||d.id===row.departmentId))
          el('option',department.name+(department.status==='ACTIVE'?'':'（已停用）'),inputs.departmentId).value=department.id;
        inputs.departmentId.value=row.departmentId||'';
        if(row.department&&!row.departmentId)saveNote.textContent=`原部门：${row.department}。请从统一部门列表选择后保存。`;
        if(row.departmentId)inputs.manager.value=departments.find(d=>d.id===row.departmentId)?.manager||row.manager||'';
        directoryReady=true;
        inputs.jobId.replaceChildren();
        if(jobResult.status==='rejected'){
          el('option',row.position?`${row.position}（已保存关联）`:'暂时无法读取职位',inputs.jobId).value=row.jobId||'';
          throw jobResult.reason;
        }
        jobs=jobResult.value.rows;jobAccount=jobResult.value.account;
        el('option',jobs.length?'请选择具体职位':'当前账号没有可选职位',inputs.jobId).value='';
        const name=job=>String(job.jobName||job.name||job.title||'未命名职位');
        for(const job of jobs){
          const duplicate=jobs.filter(other=>name(other)===name(job)).length>1;
          const label=[name(job),job.cityName||job.locationName,job.statusDesc||job.jobStatusDesc,
            duplicate?'编号 '+jobId(job).slice(-8):''].filter(Boolean).join(' · ');
          el('option',label,inputs.jobId).value=jobId(job);
        }
        inputs.jobId.value=row.jobAccountKey===jobAccount?row.jobId||'':'';
        if(row.position&&!row.jobId)saveNote.textContent=`原岗位：${row.position}。请关联具体职位后保存。`;
        if(row.jobId&&inputs.jobId.value!==row.jobId){jobSnapshot=null;jdText.textContent='';jdNote.textContent='原关联职位不在当前账号列表，请重新选择。';}
        jobsReady=true;paintState();
      }catch(err){if(ticket===generation&&opening===jdGeneration){error.textContent=err.message;error.hidden=false;paintState();}}
      if(row.aiStatus==='running')startPoll();
    }
    function payload(){
      return {...editing,...Object.fromEntries(Object.entries(inputs).map(([key,input])=>[key,String(input.value).trim()])),
        ...jobSnapshot,
        department:departments.find(d=>d.id===inputs.departmentId.value)?.name||editing.department||'',
        headcount:Number(inputs.headcount.value),years:Number(inputs.years.value)};
    }
    async function saveSnapshot(){
      const body=payload();
      if(!jobSnapshot||body.jobId!==inputs.jobId.value||body.jobAccountKey!==jobAccount)throw Error('请先选择职位并获取 JD');
      const changed=!editing.id||body.department!==editing.department||[...Object.keys(inputs),'position','jobAccountKey','jobDescription','jobFetchedAt']
        .some(key=>String(body[key])!==String(editing[key]??''));
      return changed?await request('/save',body):editing;
    }
    referenceFiles.addEventListener('change',()=>{const files=[...(referenceFiles.files||[])];referenceFiles.value='';void changeReferences(files);});
    async function changeReferences(files,removeId=''){
      if(!allowed()||!dialog.open||saving||uploading||jdLoading||editing?.aiStatus==='running'||!directoryReady||!jobsReady)return;
      if(!removeId&&!files.length)return;
      if(!form.reportValidity())return;
      if(!removeId&&files.length+(editing?.references?.length||0)>10){error.textContent='每项需求最多 10 份参考简历';error.hidden=false;return;}
      for(const file of files){
        if(!/\.(pdf|docx)$/i.test(file.name)||!file.size||file.size>10*1024*1024){
          error.textContent=`${file.name}：支持 PDF、DOCX，每份不超过 10 MB`;error.hidden=false;return;
        }
      }
      const ticket=generation;uploading=true;error.hidden=true;paintState();let completed=0;
      try{
        if(!jobSnapshot)await readSelectedJob();
        const saved=await saveSnapshot();
        if(ticket!==generation||!dialog.open||!allowed())return;
        editing=saved;
        if(removeId){
          const value=await request('/remove',{id:editing.id,version:editing.version,referenceId:removeId});
          if(ticket!==generation||!dialog.open||!allowed())return;
          editing=value;referenceStatus.textContent='参考简历已移除，请重新生成岗位分析。';
        }else for(const file of files){
          referenceStatus.textContent=`正在上传并提取 ${completed+1} / ${files.length}：${file.name}`;
          const base64=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=()=>reject(Error('文件读取失败'));reader.readAsDataURL(file);});
          if(ticket!==generation||!dialog.open||!allowed())return;
          const value=await request('/upload',{id:editing.id,version:editing.version,filename:file.name,fileBase64:base64});
          if(ticket!==generation||!dialog.open||!allowed())return;
          editing=value;completed++;paintState();
        }
        if(!removeId)referenceStatus.textContent=`已保存 ${completed} 份参考简历。点击“一键 AI 生成”后与岗位 JD 一起分析。`;
        title.textContent='招聘需求详情';
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;referenceStatus.textContent=`本次已保存 ${completed} 份；未保存的文件可重新上传。`;}}
      finally{if(ticket===generation){uploading=false;paintState();}}
    }
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(saving||uploading||jdLoading||!directoryReady||!jobsReady||editing?.aiStatus==='running'||!allowed())return;
      saving=true;paintState();error.hidden=true;const ticket=generation;
      try{
        const saved=await saveSnapshot();
        if(ticket!==generation||!allowed())return;
        editing=saved;saveNote.textContent='需求已保存';title.textContent='招聘需求详情';
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;}}
      finally{if(ticket===generation){saving=false;paintState();}}
    });
    async function generate(){
      if(saving||uploading||jdLoading||!directoryReady||!jobsReady||editing?.aiStatus==='running'||!allowed()||!form.reportValidity())return;
      if(editing?.aiResult?.portrait&&!confirm('重新生成会再次调用驱动模型，成功后替换现有分析。继续吗？'))return;
      saving=true;submitPhase='正在读取最新 JD…';paintState();error.hidden=true;const ticket=generation;
      try{
        await readSelectedJob();
        if(ticket!==generation||!dialog.open||!allowed())return;
        submitPhase='正在保存需求…';paintState();
        const saved=await saveSnapshot();
        if(ticket!==generation||!allowed())return;
        editing=saved;
        submitPhase='正在提交模型请求…';paintState();
        const generated=await request('/generate',{id:saved.id,version:saved.version,regenerate:!!saved.aiResult?.portrait});
        if(ticket!==generation||!allowed())return;
        editing=generated;
        saveNote.textContent='需求已保存，生成结果将自动关联到此需求';pollErrors=0;startPoll();
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;}}
      finally{if(ticket===generation){saving=false;submitPhase='';paintState();}}
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
    dialog.addEventListener('cancel',event=>{if(saving||uploading)event.preventDefault();else stopPoll();});
    dialog.addEventListener('close',()=>{stopPoll();if(active&&!saving)load();});
    return {
      enter(){active=true;load();},
      leave(){active=false;++generation;stopPoll();dialog.close();},
      reset(){active=false;++generation;stopPoll();dialog.close();editing=null;saving=uploading=false;page=1;status='all';
        departments=[];directoryReady=false;jobs=[];jobAccount='';jobsReady=false;jobSnapshot=null;inputs.departmentId.replaceChildren();
        inputs.jobId.replaceChildren();jdText.textContent='';jdNote.textContent='';
        q.value='';Object.values(inputs).forEach(input=>input.value='');results.replaceChildren();output.replaceChildren();
        referenceRows.replaceChildren();referenceFiles.value='';referenceStatus.textContent='';
        error.textContent=saveNote.textContent=aiMessage.textContent='';}
    };
  };
})();
