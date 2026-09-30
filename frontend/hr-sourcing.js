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
    el('span','01 / 参考简历',reference,'hr-eyebrow');
    el('h3','从优秀员工的经历中提炼能力',reference);
    el('p','支持多份 PDF、Word，每份不超过 10 MB，最多 10 份。上传后点击分析，结合本职位 JD 提炼岗位画像。',reference,'hr-hint');
    const uploadLabel=el('label','',reference,'hr-sourcing-upload');
    el('strong','选择参考简历',uploadLabel);
    const files=el('input','',uploadLabel);files.type='file';files.multiple=true;
    files.accept='.pdf,.docx';files.setAttribute('aria-label','上传多份优秀员工参考简历');
    const uploadStatus=el('p','',reference,'hr-hint');uploadStatus.setAttribute('role','status');
    const referenceRows=el('div','',reference,'hr-sourcing-references');
    const profileButton=button('AI 提炼 JD 与人才画像',el('div','',reference,'hr-actions'),generateProfile);
    profileButton.className='hr-primary';
    const profileStatus=el('p','',reference,'hr-hint');
    const profileOutput=el('div','',reference,'hr-sourcing-profile');
    const find=el('section','',layout,'hr-sourcing-card');
    el('span','02 / 人才寻源',find,'hr-eyebrow');
    el('h3','按岗位画像获取人才',find);
    el('p','持续翻页寻找，直到达到所选人数或推荐结束。AI 匹配参考分达到 80 分即可收录，由 HR 决定联系对象。',find,'hr-hint');
    const controls=el('div','',find,'hr-actions');
    const countLabel=el('label','',controls,'hr-field');el('span','获取人数',countLabel);
    const count=el('select','',countLabel);count.setAttribute('aria-label','获取人数');
    for(const n of [5,10,15,20])el('option',n+' 人',count).value=String(n);
    el('span','匹配参考分 ≥ 80',controls,'hr-badge hr-sourcing-threshold');
    const findButton=button('一键 AI 获取',controls,findPeople);findButton.className='hr-primary';
    const pauseButton=button('暂停寻找',controls,()=>{pausedSearch=true;pauseButton.disabled=true;});
    pauseButton.className='hr-secondary';pauseButton.hidden=true;
    const refresh=button('刷新结果',controls,()=>reload());refresh.className='hr-secondary';
    const searchStatus=el('p','',find,'hr-hint');
    searchStatus.setAttribute('role','status');searchStatus.setAttribute('aria-live','polite');
    const results=el('div','',find,'hr-sourcing-results');
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
    function render(){
      title.textContent=record?.jobName||selected?.name||'从职位开始寻源';
      layout.hidden=!record;
      if(!record)return;
      const locked=busy||running();
      files.disabled=profileButton.disabled=locked;
      profileButton.disabled ||= !record.references.length||record.profileState==='complete';
      findButton.disabled=locked||record.profileState!=='complete';
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
      if(changed(referenceRows,[record.references,locked]))for(const item of record.references){
        const row=el('div','',referenceRows,'hr-sourcing-reference');
        const text=el('div','',row);el('strong',item.filename,text);el('small',`已提取 ${item.characters} 字`,text,'hr-hint');
        const remove=button('移除',row,()=>mutate('sourcing/remove',{...params(),referenceId:item.id}));
        remove.disabled=locked;remove.className='hr-text-button';
      }
      if(changed(profileOutput,[record.references.length>0,record.profileState,record.profile])
          &&record.references.length&&record.profile?.summary){
        if(record.profileState!=='complete')el('p','以下为旧版画像，重新提炼完成后才可用于寻源。',profileOutput,'hr-hint');
        el('h4','人才画像',profileOutput);
        el('p',record.profile.summary,profileOutput,'hr-prose hr-sourcing-summary');
        const tags=el('div','',profileOutput,'hr-tags');
        for(const skill of (record.profile.skills||[]).slice(0,6))el('span',skill,tags,'hr-badge');
        if(record.profile.experience?.length){
          const experience=el('ul','',profileOutput,'hr-sourcing-experience');
          for(const item of record.profile.experience.slice(0,3))el('li',item,experience);
        }
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
        ?`已读取 ${run.pagesRead||1} 页 · 分析 ${run.examined||0} 人 · 80 分以上 ${run.candidates?.length||0} / ${run.wanted} 人${run.error?' · '+run.error:''}`:'选择人数后开始获取。';
      if(run&&run.version!==record.version)searchStatus.textContent+=' 这是旧版画像结果，请重新获取后再联系。';
      searchStatus.className=searchError?'hr-error':'hr-hint';
      if(searchError)searchStatus.textContent=searchError;
      else if(finding&&searchProgress)searchStatus.textContent=searchProgress;
      if(changed(results,[run?.candidates,run?.version,record.version,locked]))for(const item of run?.candidates||[]){
        const card=el('article','',results,'hr-person-card');
        const head=el('div','',card,'hr-card-heading');el('h3',item.name,head);
        el('span',`${item.score} / 100 · AI 参考`,head,'hr-badge');
        el('p',item.summary,card,'hr-prose');
        for(const evidence of item.evidence||[])el('p',evidence,card,'hr-hint');
        if(item.gaps?.length)el('p','待核实：'+item.gaps.join('；'),card,'hr-hint');
        const actions=el('div','',card,'hr-actions');
        if(item.contact)el('span',item.contact.status==='sent'&&!item.contact.friend_id
          ?'已打招呼 · 正在关联对应会话':sendStates[item.contact.status]||item.contact.status,actions,'hr-hint');
        else{
          const greet=button('打招呼',actions,()=>openGreeting(item));greet.className='hr-primary';
          greet.disabled=locked||run.version!==record.version||!item.canGreet;
          if(!item.canGreet)el('span','平台未提供首次打招呼参数',actions,'hr-hint');
        }
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
        if(valid(ticket)){record=value;feedback.textContent='已保存';}
      }catch(error){if(valid(ticket))feedback.textContent=error.message;}
      finally{if(valid(ticket)){busy=false;render();void uploadPending();}}
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
      if(!record||busy||running()||record.profileState!=='complete'||!allowed())return;
      const ticket=generation,snapshot=params(),jobId=record.jobId;busy=finding=true;searchError='';searchProgress='';render();
      const progress=text=>{searchProgress=text;feedback.textContent=searchStatus.textContent=text;};
      progress('正在确认招聘账号和岗位…');
      pausedSearch=false;pauseButton.disabled=false;
      const desired=Number(count.value);
      if(!searchSession||searchSession.id!==record.id||searchSession.version!==record.version
        ||searchSession.desired!==desired)searchSession={id:record.id,version:record.version,
          desired,requestId:crypto.randomUUID(),candidates:[],seen:new Set(),page:0,batch:1,ended:false,qualified:0};
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
          const target=Math.min(20,Math.max(10,(desired-qualified)*2));
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
              if(status?.hold?.code==='REQUEST_BUDGET_EXHAUSTED'&&status.reconnecting){
                progress('读取额度已用完，等待助手恢复后继续当前页');await wait();continue;
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
          const current=candidates.slice(0,20);
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
          if(ended&&!candidates.length){session.batch++;break;}
        }
        if(!seen.size)throw Error('BOSS 本次没有返回可用候选人，没有调用 AI');
        feedback.textContent=qualified>=desired?`已获取 ${qualified} 位 80 分以上的人选。`
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
      enter,leave,reset(){leave();selected=record=candidate=null;searchSession=null;speeches=[];
        searchError='';
        referenceRows.replaceChildren();profileOutput.replaceChildren();results.replaceChildren();templates.replaceChildren();
        for(const node of [referenceRows,profileOutput,results])painted.delete(node);
        files.value=message.value='';greetingError.textContent=greetingHint.textContent='';
        greetingTitle.textContent='打招呼';feedback.textContent='请从职位管理选择具体职位。';render();}};
  };
})();
