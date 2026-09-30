/* Job-bound sourcing. Reading/analyzing never sends a greeting. */
(() => {
  const sendStates={queued:'等待助手发送',sending:'发送中',sent:'已打招呼 · 等待对方回复',
    uncertain:'发送结果待核对',failed:'未发送，请核对助手状态',expired:'卡片已过期，本次未发送'};
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
  globalThis.HeyiHrSourcing={rows};
  globalThis.createHrSourcing=({root,panel,element:el,button,allowed,navigate,hrApi})=>{
    let selected=null,record=null,active=false,generation=0,busy=false,timer=null,polling=false,pollErrors=0;
    let pendingUploads=[],uploading=false;
    let finding=false,searchError='',searchProgress='',searchSession=null,pausedSearch=false;
    let criteriaDraft=[],criteriaScope='',criteriaDirty=false,referenceScope='';
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
    const feedback=el('p','请选择职位卡片里的“上传简历 / AI 寻源”。',panel,'hr-hint');
    feedback.setAttribute('role','status');
    const layout=el('div','',panel,'hr-sourcing-layout');
    const reference=el('section','',layout,'hr-sourcing-card');
    el('span','01 / 寻找条件',reference,'hr-eyebrow');
    el('h3','先确定匹配条件',reference);
    el('p','确认几条关键工作能力，再开始寻找。参考简历提供方向，不要求候选人的经历完全相同。',reference,'hr-hint');
    const referenceTools=el('details','',reference,'hr-sourcing-reference-tools');
    const referenceToolsTitle=el('summary','参考简历与画像',referenceTools);
    el('p','支持多份 PDF、Word，每份不超过 10 MB，最多 10 份。上传后结合本职位 JD 提炼画像。',referenceTools,'hr-hint');
    const uploadLabel=el('label','',referenceTools,'hr-sourcing-upload');
    el('strong','选择参考简历',uploadLabel);
    const files=el('input','',uploadLabel);files.type='file';files.multiple=true;
    files.accept='.pdf,.docx';files.setAttribute('aria-label','上传多份优秀员工参考简历');
    const uploadStatus=el('p','',referenceTools,'hr-hint');uploadStatus.setAttribute('role','status');
    const referenceRows=el('div','',referenceTools,'hr-sourcing-references');
    const profileButton=button('AI 提炼 JD 与人才画像',el('div','',referenceTools,'hr-actions'),generateProfile);
    profileButton.className='hr-primary';
    const profileStatus=el('p','',referenceTools,'hr-hint');
    const criteriaOutput=el('div','',reference,'hr-sourcing-criteria');
    el('h4','匹配条件',criteriaOutput);
    el('p','先确认几条工作条件。相近经历可匹配，卡片未说明的项目保留为待核实。',criteriaOutput,'hr-hint');
    const criteriaRows=el('div','',criteriaOutput,'hr-sourcing-criteria-rows');
    const criteriaActions=el('div','',criteriaOutput,'hr-actions');
    const addCriterion=button('添加条件',criteriaActions,()=>{
      if(criteriaDraft.length>=5)return;
      criteriaDraft.push({text:'',weight:20,required:false});dirtyCriteria();renderCriteria(true);
    });addCriterion.className='hr-secondary';
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
    const profileOutput=el('div','',referenceTools,'hr-sourcing-profile');
    const find=el('section','',layout,'hr-sourcing-card');
    el('span','02 / 人才寻源',find,'hr-eyebrow');
    el('h3','按已确认条件寻找人才',find);
    el('p','按已确认条件持续寻找。只对有事实依据的条件计算匹配分；待核实项另列，由 HR 审核完整简历。',find,'hr-hint');
    const controls=el('div','',find,'hr-actions');
    const countLabel=el('label','',controls,'hr-field');el('span','获取人数',countLabel);
    const count=el('select','',countLabel);count.setAttribute('aria-label','获取人数');
    for(const n of [5,10,15,20])el('option',n+' 人',count).value=String(n);
    el('span','已知条件匹配 ≥ 80',controls,'hr-badge hr-sourcing-threshold');
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
      criteriaRows.replaceChildren();
      const total=criteriaDraft.reduce((sum,row)=>sum+Number(row.weight||0),0);
      const percentages=[];
      criteriaDraft.forEach((row,index)=>{
        const node=el('div','',criteriaRows,'hr-sourcing-condition');
        const text=el('textarea','',node);text.rows=2;text.maxLength=250;text.value=row.text;
        text.setAttribute('aria-label',`匹配条件 ${index+1}`);text.disabled=locked;
        text.addEventListener('input',()=>{row.text=text.value;dirtyCriteria();});
        const options=el('div','',node,'hr-sourcing-condition-options');
        const label=el('label','权重 ',options);
        const weight=el('input','',label);weight.type='number';weight.min='1';weight.max='100';weight.value=String(row.weight);
        weight.setAttribute('aria-label',`条件 ${index+1} 权重`);weight.disabled=locked;
        weight.addEventListener('input',()=>{
          row.weight=Number(weight.value);dirtyCriteria();
          const sum=criteriaDraft.reduce((value,item)=>value+Number(item.weight||0),0);
          for(const item of percentages)item.node.textContent=sum?`${Math.round(item.row.weight/sum*100)}%`:'';
        });
        percentages.push({row,node:el('small',total?`${Math.round(row.weight/total*100)}%`:'',options,'hr-hint')});
        const requiredLabel=el('label','',options);
        const required=el('input','',requiredLabel);required.type='checkbox';required.checked=row.required;required.disabled=locked;
        required.setAttribute('aria-label',`条件 ${index+1} 必须满足`);
        required.addEventListener('change',()=>{row.required=required.checked;dirtyCriteria();});
        el('span','必须满足',requiredLabel);
        const remove=button('移除',options,()=>{criteriaDraft.splice(index,1);dirtyCriteria();renderCriteria(true);});
        remove.className='hr-text-button';remove.disabled=locked||criteriaDraft.length===1;
      });
      addCriterion.disabled=locked||criteriaDraft.length>=5;
      saveCriteria.disabled=locked||record?.profileState!=='complete';
      criteriaStatus.textContent=criteriaDirty?'条件已修改，请确认并保存后再寻找。':record?.criteria?.confirmed
        ?'条件已确认。未说明不等于不符合；至少有一条真实匹配依据才收录。'
        :'请编辑确认后保存条件，再开始寻找。';
    }
    function render(){
      title.textContent=record?.jobName||selected?.name||'从职位开始寻源';
      layout.hidden=!record;
      if(!record)return;
      const referenceState=record.id+':'+record.profileState;
      if(referenceState!==referenceScope){
        referenceScope=referenceState;referenceTools.open=record.profileState!=='complete';
      }
      referenceToolsTitle.textContent=`参考简历与画像 · 已保存 ${record.references.length} 份`;
      const locked=busy||running();
      files.disabled=profileButton.disabled=locked;
      profileButton.disabled ||= !record.references.length||record.profileState==='complete';
      findButton.disabled=locked||record.profileState!=='complete'||record.criteria?.confirmed===false||criteriaDirty;
      const analyzing=record.run?.version===record.version&&record.run?.state==='running';
      findButton.textContent=finding?'正在获取…':analyzing?'AI 分析中…':searchSession?'继续寻找':'一键 AI 获取';
      pauseButton.hidden=!finding;pauseButton.disabled=pausedSearch;
      findButton.setAttribute('aria-busy',String(finding||analyzing));
      refresh.disabled=busy;count.disabled=locked;
      profileButton.textContent=record.profileState==='running'?'正在提炼…':record.profileState==='complete'?'画像已就绪':'AI 提炼 JD 与人才画像';
      profileStatus.textContent=!record.references.length?'尚无已上传的参考简历，请先选择文件并等待上传成功。':record.profileState==='running'
        ?`驱动模型正在分析 · 已等待 ${Math.max(0,Math.floor(Date.now()/1000-record.profileStartedAt))} 秒`
        :record.profileState==='failed'?record.profileError:record.profileState==='stale'?'参考简历或 JD 已更新，请重新提炼。'
        :record.profileState==='complete'?'依据当前职位 JD 与参考简历生成，供 HR 审核。':'上传后可开始提炼。';
      if(!uploading&&!pendingUploads.length)uploadStatus.textContent=record.references.length?`已保存 ${record.references.length} 份参考简历`:'文件上传成功后会显示在下方列表。';
      const scope=JSON.stringify([record.id,record.version,record.criteria]);
      if(scope!==criteriaScope){
        criteriaScope=scope;criteriaDirty=false;criteriaDraft=(record.criteria?.conditions||[]).map(row=>({...row}));
      }
      criteriaOutput.hidden=record.profileState!=='complete';renderCriteria();
      if(changed(referenceRows,[record.references,locked]))for(const item of record.references){
        const row=el('div','',referenceRows,'hr-sourcing-reference');
        const text=el('div','',row);el('strong',item.filename,text);el('small',`已提取 ${item.characters} 字`,text,'hr-hint');
        const remove=button('移除',row,()=>mutate('sourcing/remove',{...params(),referenceId:item.id}));
        remove.disabled=locked;remove.className='hr-text-button';
      }
      if(changed(profileOutput,[record.references.length>0,record.profileState,record.profile])
          &&record.references.length&&record.profile?.summary){
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
        el('span',`${item.score} / 100 · ${item.scoreBasis==='known_conditions'?'已知条件匹配':'AI 参考'}`,head,'hr-badge');
        if(item.checks)el('p',`${item.matchedConditions} / ${item.checks.length} 条有匹配依据 · ${item.pendingConditions} 条待核实`,card,'hr-hint');
        el('p',item.summary,card,'hr-prose');
        for(const evidence of (item.evidence||[]).slice(0,2))el('p',String(evidence).slice(0,160),card,'hr-hint');
        if(item.checks?.length){
          const details=el('details','',card,'hr-sourcing-checks');
          el('summary','逐条匹配依据',details);
          for(const [index,check] of item.checks.entries()){
            const labels={matched:'匹配',partial:'部分匹配',unmatched:'明确不符',unknown:'待核实'};
            el('strong',`条件 ${index+1} · ${labels[check.status]||check.status}`,details);
            el('p',check.text+'：'+check.evidence,details,'hr-hint');
          }
        }
        else if(item.gaps?.length)el('p','待核实：'+item.gaps.join('；'),card,'hr-hint');
        const actions=el('div','',card,'hr-actions');
        if(item.contact)el('span',item.contact.status==='sent'&&!item.contact.friend_id
          ?'已打招呼 · 正在关联对应会话':sendStates[item.contact.status]||item.contact.status,actions,'hr-hint');
        else{
          const greet=button('打招呼',actions,()=>openGreeting(item));greet.className='hr-primary';
          greet.disabled=locked||run.version!==record.version||!item.canGreet;
          if(!item.canGreet)el('span','平台未提供首次打招呼参数',actions,'hr-hint');
        }
      }
      diagnostics.hidden=!run?.reviewExamples?.length;
      if(changed(diagnosticRows,run?.reviewExamples))for(const item of run?.reviewExamples||[]){
        el('strong',`${item.name} · ${item.matchState==='needs_info'?'资料不足，需核实':item.score+' 分，未达已确认条件'}`,diagnosticRows);
        for(const check of item.checks||[])el('p',check.text+'：'+check.evidence,diagnosticRows,'hr-hint');
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
        if(valid(ticket)){record=value;pollErrors=0;render();void uploadPending();}
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
      finally{if(valid(ticket)){busy=false;render();void uploadPending();}}
      return false;
    }
    files.addEventListener('change',async()=>{
      const list=[...(files.files||[])];files.value='';
      if(!list.length||!record||!active||!allowed())return;
      if(list.length+pendingUploads.length+record.references.length>10){feedback.textContent='每个职位最多 10 份参考简历';return;}
      pendingUploads.push(...list);
      if(busy||running())uploadStatus.textContent=`已选择 ${pendingUploads.length} 份，当前操作完成后自动上传。`;
      await uploadPending();
    });
    async function uploadPending(){
      if(!pendingUploads.length||busy||running()||!active||!record||!allowed())return;
      const list=pendingUploads;pendingUploads=[];
      const ticket=generation;busy=uploading=true;render();let completed=0;
      try{
        for(const file of list){
          if(!valid(ticket))return;
          if(!/\.(pdf|docx)$/i.test(file.name))throw Error(`${file.name}：仅支持 PDF、DOCX`);
          if(file.size>10*1024*1024)throw Error(`${file.name} 超过 10 MB`);
          uploadStatus.textContent=feedback.textContent=`正在上传并提取 ${completed+1} / ${list.length}：${file.name}`;
          const base64=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=()=>reject(Error('文件读取失败'));reader.readAsDataURL(file);});
          if(!valid(ticket))return;
          const value=await request('sourcing/upload',{...params(),filename:file.name,fileBase64:base64});
          if(!valid(ticket))return;record=value;completed++;
          render();
        }
        feedback.textContent=`已上传 ${completed} 份参考简历，可以提炼 JD 与人才画像。`;
      }catch(error){if(valid(ticket))feedback.textContent=`本次已保存 ${completed} / ${list.length} 份。${error.message}。未保存的文件请重新选择上传。`;}
      finally{if(valid(ticket)){
        busy=false;render();uploadStatus.textContent=feedback.textContent;uploading=false;void uploadPending();
      }}
    }
    async function generateProfile(){if(record)await mutate('sourcing/profile',params());}
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
        busy=finding=false;searchProgress='';render();void uploadPending();
      }}
    }
    function fillTemplate(){
      const text=speeches.find(row=>row.id===templates.value)?.content||'您好，我们正在招聘{职位}，看到您的相关经历，希望与您进一步沟通。如果您感兴趣，欢迎回复。';
      message.value=text.replace(/\{姓名\}/g,candidate?.name||'您好').replace(/\{职位\}/g,record?.jobName||'该岗位');
    }
    templates.addEventListener('change',fillTemplate);
    async function openGreeting(item){
      const ticket=generation;
      try{
        const state=await accountCheck(record.accountKey);
        if(!state.capabilities?.includes('sourcing-credentials-v2'))throw Error('请更新招聘助手到 0.1.12 或以上版本后再打招呼');
        if(!valid(ticket))return;
        const data=await request('speeches');if(!valid(ticket))return;
        candidate=item;speeches=(data.items||[]).filter(row=>row.active&&['active_greet','general'].includes(row.kind));
        templates.replaceChildren();el('option','自定义话术',templates).value='';
        for(const row of speeches)el('option',row.name,templates).value=row.id;
        templates.value=speeches[0]?.id||'';fillTemplate();
        greetingTitle.textContent='向 '+item.name+' 打招呼';
        greetingHint.textContent=record.jobName+' · 发送成功后跟进对方回复和简历，并归入主动邀约。';
        greetingError.textContent='';dialog.showModal();
      }catch(error){if(valid(ticket))feedback.textContent=error.message;}
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
      if(!selected){feedback.textContent='请返回职位管理，选择职位卡片里的“上传简历 / AI 寻源”。';return;}
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
            description:jd.description,education:jd.education,experience:jd.experience});
        }
        if(valid(ticket)){record=value;selected.refresh=false;feedback.textContent='已关联 '+record.jobName+'，可以上传参考简历或查看寻源结果。';}
      }catch(error){if(valid(ticket)){record=null;feedback.textContent=error.message;}}
      finally{if(valid(ticket)){busy=false;render();void uploadPending();}}
    }
    function leave(){active=false;generation++;stop();busy=uploading=finding=false;searchProgress='';pendingUploads=[];dialog.close();}
    return {select(row){selected={id:String(row.encryptJobId||row.encJobId||row.encryptId||row.jobId||row.id),
      name:row.jobName||row.name||'',refresh:true};record=null;searchError='';},
      enter,leave,reset(){leave();selected=record=candidate=null;searchSession=null;criteriaDraft=[];criteriaScope='';criteriaDirty=false;referenceScope='';speeches=[];
        searchError='';
        referenceRows.replaceChildren();profileOutput.replaceChildren();results.replaceChildren();templates.replaceChildren();
        criteriaRows.replaceChildren();diagnosticRows.replaceChildren();
        for(const node of [referenceRows,profileOutput,results,criteriaRows,diagnosticRows])painted.delete(node);
        files.value=message.value='';greetingError.textContent=greetingHint.textContent='';
        greetingTitle.textContent='打招呼';feedback.textContent='请从职位管理选择具体职位。';render();}};
  };
})();
