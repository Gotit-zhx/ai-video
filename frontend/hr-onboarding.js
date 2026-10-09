(() => {
  globalThis.createHrOnboarding=({root,section,element:el,button,allowed,hrApi,navigate})=>{
    const panel=section('onboarding');
    let active=false,epoch=0,page=1,status='all',editing=null,saving=false,source=null,completed=null,departing=false;
    let notifying=null,noticeData=null,proof=null,noticeRequest='',noticeBusy=false,noticeEpoch=0,timer=null;
    let directoryPage=1,directorySerial=0,directoryTimer=null;
    let mentorChoice=null,mentorConfig=null,mentorPage=1,mentorSerial=0,mentorTimer=null;
    const labels=[['name','姓名'],['startDate','确认入职时间'],['position','入职岗位'],['supervisor','归属主管'],
      ['department','归属部门'],['company','归属公司'],['fixedSalary','固定薪资'],['performanceSalary','绩效薪资'],
      ['commission','提成'],['mentorName','导师名称'],['joined','是否入职']];
    const field=(label,parent,tag='input')=>{const wrap=el('label','',parent,'hr-field');el('span',label,wrap);return el(tag,'',wrap);};
    const head=el('div','',panel,'hr-pool-toolbar'),intro=el('div','',head);
    el('h2','从确认入职，到有人带教',intro);el('p','二面通过时填写安排，入职后由 HR 发送导师提醒。',intro,'hr-hint');
    button('刷新',head,load);
    const tabs=el('div','',panel,'hr-pool-tabs');
    const options=[['all','全部'],['joined','已入职'],['departed','已离职']].map(([key,label])=>{
      const node=button(label,tabs,()=>{status=key;page=1;load();});return {key,node};});
    const searchForm=el('form','',panel,'hr-form'),search=field('搜索姓名、岗位、部门或导师',searchForm);
    search.maxLength=100;button('搜索',searchForm,()=>{page=1;load();});
    searchForm.addEventListener('submit',event=>{event.preventDefault();page=1;load();});
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const results=el('div','',panel,'hr-table-scroll');
    const pager=el('div','',panel,'hr-pagination'),previous=button('上一页',pager,()=>{page--;load();});
    const pageLabel=el('span','',pager),next=button('下一页',pager,()=>{page++;load();});pager.hidden=true;
    const dialog=el('dialog','',root,'hr-pool-dialog hr-onboarding-dialog');
    const form=el('form','',dialog),title=el('h2','填写入职安排',form);
    const hint=el('p','确认二面通过前填写以下信息，未入职也可先安排。',form,'hr-hint');
    const fields=el('div','',form,'hr-pool-fields'),inputs={};
    for(const [key,label] of labels){
      const input=inputs[key]=field(label+(key.includes('Salary')?'（元/月）':''),fields,key==='joined'?'select':'input');
      if(key==='joined'){el('option','未入职',input).value='false';el('option','已入职',input).value='true';}
      else{input.maxLength=key==='startDate'?10:200;input.required=true;input.autocomplete='off';}
      if(key==='name')input.readOnly=true;
      if(key==='startDate')input.type='date';
      if(key.includes('Salary')){input.type='number';input.min='0';input.step='0.01';}
    }
    inputs.commission.placeholder='如：无，或按业绩的 3%';
    button('选择钉钉导师',inputs.mentorName.parentElement,openMentorPicker);
    inputs.mentorName.addEventListener('input',()=>{mentorChoice=null;});
    const error=el('p','',form,'hr-error');error.setAttribute('role','alert');
    const actions=el('div','',form,'hr-actions'),cancel=button('取消',actions,()=>dialog.close());
    const submit=el('button','确认二面通过并流转',actions,'hr-primary');submit.type='submit';
    dialog.addEventListener('cancel',e=>{if(saving)e.preventDefault();});
    dialog.addEventListener('close',()=>{++epoch;mentorPicker.close();mentorChoice=null;
      editing=source=completed=null;Object.values(inputs).forEach(n=>n.value='');error.textContent='';});
    async function request(path,body){
      const response=await hrApi('/api/v1/hr/'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
      if(response.success===false||!response.data)throw Error(response.message||'读取入职安排失败');
      return response.data;
    }
    function populate(value){
      for(const [key] of labels)inputs[key].value=key==='joined'?String(!!value[key]):value[key]||'';
      inputs.joined.disabled=!!value.departed;
      mentorChoice=value.mentorUserId?{userId:value.mentorUserId,configVersion:value.mentorConfigVersion}:null;
    }
    async function openPass(row,payload,onDone){
      if(!allowed()||saving)return;
      const ticket=++epoch;editing={talentId:row.id};source=payload;completed=onDone;
      title.textContent='二面通过 · 填写入职安排';hint.textContent='填写后确认通过，候选人将流转到“入职安排及跟进”。';
      submit.textContent='确认二面通过并流转';error.textContent='正在读取岗位和部门…';
      populate({name:row.name,position:row.position,performanceSalary:'0',commission:'无'});submit.disabled=true;dialog.showModal();
      try{
        const data=await request('onboarding?talentId='+encodeURIComponent(row.id));
        if(ticket!==epoch||!dialog.open||!allowed())return;
        populate(data.defaults);error.textContent='';
      }catch(e){if(ticket===epoch)error.textContent=e.message;}
      finally{if(ticket===epoch)submit.disabled=false;}
    }
    function openEdit(row){
      if(!allowed()||saving)return;
      ++epoch;clearTimeout(timer);editing=row;source=completed=null;populate(row);error.textContent='';
      title.textContent='编辑 · '+row.name;
      hint.textContent=row.departed?'已离职，保留入职记录；可更新资料。':'更新入职信息；导师提醒需要另外点击发送。';
      submit.textContent='保存';submit.disabled=false;dialog.showModal();
    }
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(!editing||saving||!allowed()||!form.reportValidity())return;
      const ticket=epoch,value=Object.fromEntries(labels.filter(([k])=>k!=='name').map(([k])=>[k,k==='joined'?inputs[k].value==='true':inputs[k].value.trim()]));
      if(mentorChoice)Object.assign(value,{mentorUserId:mentorChoice.userId,mentorConfigVersion:mentorChoice.configVersion});
      saving=true;submit.disabled=cancel.disabled=true;error.textContent='正在保存…';
      try{
        if(source)await request('pool/interview/review',{...source,onboarding:value});
        else await request('onboarding/save',{...value,talentId:editing.talentId,version:editing.version});
        if(ticket!==epoch||!allowed())return;
        const done=completed;dialog.close();
        if(done)done();else if(active)await load();
      }catch(e){if(ticket===epoch)error.textContent=e.message;}
      finally{saving=false;submit.disabled=cancel.disabled=false;}
    });
    const mentorPicker=el('dialog','',root,'hr-pool-dialog hr-mentor-picker');
    el('h2','选择导师 · 钉钉公司人员',mentorPicker);
    const mentorFilters=el('div','',mentorPicker,'hr-directory-filters');
    const mentorQuery=field('查询姓名或部门',mentorFilters);mentorQuery.maxLength=100;
    const mentorDepartment=field('部门',mentorFilters,'select');
    el('option','所有可见部门',mentorDepartment).value='';
    button('刷新通讯录',mentorFilters,()=>{mentorPage=1;loadMentors(true);});
    const mentorStatus=el('p','',mentorPicker,'hr-hint');
    const mentorResults=el('div','',mentorPicker,'hr-directory-results');
    const mentorPager=el('div','',mentorPicker,'hr-actions');
    const mentorPrevious=button('上一页',mentorPager,()=>{mentorPage--;loadMentors();});
    const mentorPageLabel=el('span','',mentorPager);
    const mentorNext=button('下一页',mentorPager,()=>{mentorPage++;loadMentors();});
    button('关闭',mentorPicker,()=>mentorPicker.close());
    mentorPicker.addEventListener('close',()=>{++mentorSerial;clearTimeout(mentorTimer);mentorResults.replaceChildren();});
    mentorQuery.addEventListener('input',()=>{++mentorSerial;mentorPage=1;clearTimeout(mentorTimer);mentorTimer=setTimeout(loadMentors,300);});
    mentorQuery.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();mentorPage=1;loadMentors();}});
    mentorDepartment.addEventListener('change',()=>{mentorPage=1;loadMentors();});
    async function openMentorPicker(){
      if(saving||!editing||!allowed())return;
      const ticket=epoch,serial=++mentorSerial;mentorPage=1;mentorQuery.value='';mentorDepartment.value='';
      mentorConfig=null;mentorResults.replaceChildren();mentorPager.hidden=true;mentorStatus.textContent='正在读取钉钉配置…';mentorPicker.showModal();
      try{
        const data=await request('pool/dingtalk');
        if(ticket!==epoch||serial!==mentorSerial||!mentorPicker.open||!allowed())return;
        mentorConfig=data.config;
        if(!mentorConfig?.verified){mentorStatus.textContent='请先在钉钉设置校验企业应用。';return;}
        await loadMentors();
      }catch(e){if(ticket===epoch&&serial===mentorSerial)mentorStatus.textContent=e.message;}
    }
    async function loadMentors(refresh=false){
      clearTimeout(mentorTimer);
      if(!mentorPicker.open||!dialog.open||!allowed()||!mentorConfig?.verified)return;
      const ticket=epoch,serial=++mentorSerial;
      mentorStatus.textContent='正在查询钉钉人员…';
      try{
        const params=new URLSearchParams({configVersion:mentorConfig.version,q:mentorQuery.value,departmentId:mentorDepartment.value,page:mentorPage});
        if(refresh)params.set('refresh','1');
        const data=await request('pool/dingtalk/directory?'+params);
        if(ticket!==epoch||serial!==mentorSerial||!mentorPicker.open||!allowed())return;
        const dept=mentorDepartment.value;mentorDepartment.replaceChildren();el('option','所有可见部门',mentorDepartment).value='';
        for(const item of data.departments||[])el('option',item.name,mentorDepartment).value=item.id;
        mentorDepartment.value=data.departments?.some(d=>d.id===dept)?dept:'';
        mentorResults.replaceChildren();mentorPage=data.page;
        for(const person of data.items||[]){
          const control=button('',mentorResults,()=>{
            if(!allowed()||!dialog.open||saving)return;
            inputs.mentorName.value=person.name;mentorChoice={userId:person.userId,configVersion:mentorConfig.version};mentorPicker.close();
          });
          control.className='hr-directory-person';control.dataset.userId=person.userId;
          control.setAttribute('aria-pressed',String(mentorChoice?.userId===person.userId));
          el('strong',person.name,control);el('span',person.department||'直属人员',control);el('small','ID 尾号 '+person.userId.slice(-4),control);
        }
        mentorStatus.textContent=data.state==='failed'?data.error:data.state==='running'?`正在同步 · 已读取 ${data.employeeCount} 人…`:`找到 ${data.total} 人，点击选择导师。`;
        mentorPager.hidden=data.state!=='complete'||data.pages<=1;mentorPageLabel.textContent=`第 ${data.page} / ${data.pages} 页`;
        mentorPrevious.disabled=data.page<=1;mentorNext.disabled=data.page>=data.pages;
        if(data.state==='running')mentorTimer=setTimeout(loadMentors,1500);
      }catch(e){if(ticket===epoch&&serial===mentorSerial)mentorStatus.textContent=e.message;}
    }
    const notifyDialog=el('dialog','',root,'hr-pool-dialog hr-mentor-dialog');
    const notifyTitle=el('h2','发送导师提醒',notifyDialog);
    const noticeMessage=el('p','',notifyDialog,'hr-prose');
    const recipient=field('导师钉钉身份',notifyDialog,'select');
    const directory=el('div','',notifyDialog,'hr-directory');directory.hidden=true;
    const directoryFilters=el('div','',directory,'hr-directory-filters');
    const directoryQuery=field('查询钉钉人员',directoryFilters);
    directoryQuery.placeholder='输入姓名或部门';directoryQuery.maxLength=100;
    const directoryDepartment=field('部门',directoryFilters,'select');
    el('option','所有可见部门',directoryDepartment).value='';
    button('刷新通讯录',directoryFilters,()=>{directoryPage=1;loadDirectory(true);});
    const directoryStatus=el('p','',directory,'hr-hint');
    const directoryResults=el('div','',directory,'hr-directory-results');
    const directoryPager=el('div','',directory,'hr-actions');
    const directoryPrevious=button('上一页',directoryPager,()=>{directoryPage--;loadDirectory();});
    const directoryLabel=el('span','',directoryPager);
    const directoryNext=button('下一页',directoryPager,()=>{directoryPage++;loadDirectory();});
    const phoneWrap=el('div','',notifyDialog,'hr-pool-fields'),mobile=field('导师手机号',phoneWrap);
    mobile.inputMode='tel';mobile.maxLength=30;
    const identify=button('识别导师',phoneWrap,async()=>{
      if(noticeBusy||!notifying||!allowed())return;
      const ticket=noticeEpoch;noticeBusy=true;syncNotice();noticeFeedback.textContent='正在核对导师姓名…';
      try{
        const value=await request('onboarding/identity',{talentId:notifying.talentId,version:notifying.version,
          configVersion:noticeData.config.version,mobile:mobile.value});
        if(ticket===noticeEpoch&&allowed()){proof=value;noticeFeedback.textContent='已识别：'+value.name+'，请核对后发送。';}
      }catch(e){if(ticket===noticeEpoch)noticeFeedback.textContent=e.message;}
      finally{noticeBusy=false;if(ticket===noticeEpoch)syncNotice();}
    });
    const noticeFeedback=el('p','',notifyDialog,'hr-hint');noticeFeedback.setAttribute('role','status');
    const noticeActions=el('div','',notifyDialog,'hr-actions');
    const noticeClose=button('关闭',noticeActions,()=>notifyDialog.close());
    const send=button('发送导师提醒',noticeActions,sendNotice);send.className='hr-primary';
    function syncNotice(){
      phoneWrap.hidden=recipient.value!=='mobile';identify.hidden=phoneWrap.hidden;
      directory.hidden=recipient.value!=='directory';
      recipient.disabled=mobile.disabled=identify.disabled=noticeBusy;noticeClose.disabled=noticeBusy;
      send.disabled=noticeBusy||!noticeData?.config?.verified||!(['mobile','directory'].includes(recipient.value)?proof:recipient.value);
    }
    recipient.addEventListener('change',()=>{proof=null;noticeFeedback.textContent='';++directorySerial;clearTimeout(directoryTimer);
      syncNotice();if(recipient.value==='directory')loadDirectory();});
    mobile.addEventListener('input',()=>{proof=null;syncNotice();});
    notifyDialog.addEventListener('close',()=>{++noticeEpoch;++directorySerial;clearTimeout(directoryTimer);
      notifying=noticeData=proof=null;mobile.value='';noticeFeedback.textContent='';directoryResults.replaceChildren();});
    notifyDialog.addEventListener('cancel',e=>{if(noticeBusy)e.preventDefault();});
    directoryQuery.addEventListener('input',()=>{directoryPage=1;++directorySerial;clearTimeout(directoryTimer);
      directoryTimer=setTimeout(loadDirectory,300);});
    directoryQuery.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();directoryPage=1;loadDirectory();}});
    directoryDepartment.addEventListener('change',()=>{directoryPage=1;loadDirectory();});
    async function loadDirectory(refresh=false){
      clearTimeout(directoryTimer);
      if(!notifying||!notifyDialog.open||recipient.value!=='directory'||!allowed()||!noticeData?.config?.verified)return;
      const ticket=noticeEpoch,serial=++directorySerial;
      directoryStatus.textContent='正在读取钉钉人员…';
      try{
        const params=new URLSearchParams({configVersion:noticeData.config.version,q:directoryQuery.value,
          departmentId:directoryDepartment.value,page:directoryPage});
        if(refresh)params.set('refresh','1');
        const data=await request('pool/dingtalk/directory?'+params);
        if(ticket!==noticeEpoch||serial!==directorySerial||!allowed()||recipient.value!=='directory')return;
        const dept=directoryDepartment.value;directoryDepartment.replaceChildren();
        el('option','所有可见部门',directoryDepartment).value='';
        for(const item of data.departments||[])el('option',item.name,directoryDepartment).value=item.id;
        directoryDepartment.value=data.departments?.some(d=>d.id===dept)?dept:'';
        directoryResults.replaceChildren();directoryPage=data.page;
        for(const person of data.items||[]){
          const control=button('',directoryResults,()=>chooseMentor(person.userId));control.className='hr-directory-person';
          control.dataset.userId=person.userId;control.disabled=noticeBusy;
          control.setAttribute('aria-pressed',String(proof?.userId===person.userId));
          el('strong',person.name,control);el('span',person.department||'直属人员',control);
          el('small','ID 尾号 '+person.userId.slice(-4),control);
        }
        directoryStatus.textContent=data.state==='failed'?data.error:data.state==='running'?
          `正在读取通讯录 · 已读取 ${data.employeeCount} 人…`:`找到 ${data.total} 人`;
        directoryPager.hidden=data.state!=='complete'||data.pages<=1;
        directoryLabel.textContent=`第 ${data.page} / ${data.pages} 页`;
        directoryPrevious.disabled=data.page<=1;directoryNext.disabled=data.page>=data.pages;
        if(data.state==='running')directoryTimer=setTimeout(loadDirectory,1500);
      }catch(e){if(ticket===noticeEpoch&&serial===directorySerial)directoryStatus.textContent=e.message;}
    }
    async function chooseMentor(userId){
      if(noticeBusy||!notifying||!allowed())return;
      const ticket=noticeEpoch;noticeBusy=true;proof=null;syncNotice();
      noticeFeedback.textContent='正在核对导师身份…';
      try{
        const person=await request('onboarding/identity',{talentId:notifying.talentId,version:notifying.version,
          configVersion:noticeData.config.version,userId});
        if(ticket!==noticeEpoch||!allowed())return;
        proof=person;noticeFeedback.textContent=`已选择导师：${person.name}。发送时将同步保存导师。`;
        for(const node of directoryResults.children)node.setAttribute('aria-pressed',String(node.dataset.userId===person.userId));
      }catch(e){if(ticket===noticeEpoch)noticeFeedback.textContent=e.message;}
      finally{noticeBusy=false;if(ticket===noticeEpoch)syncNotice();}
    }
    async function openNotice(row){
      if(!allowed()||noticeBusy)return;
      const ticket=++noticeEpoch;notifying=row;proof=null;noticeData=null;noticeRequest=crypto.randomUUID();mobile.value='';
      notifyTitle.textContent='发送导师提醒 · '+row.mentorName;
      noticeMessage.textContent=`${row.name} 已经入职，请准备好 ${row.name} 的带教计划。`;
      directoryPage=1;directoryQuery.value='';directoryDepartment.value='';directoryResults.replaceChildren();
      recipient.replaceChildren();recipient.value='';syncNotice();send.disabled=true;
      noticeFeedback.textContent='正在读取已验证的钉钉人员…';notifyDialog.showModal();
      try{
        const data=await request('onboarding/recipients?talentId='+encodeURIComponent(row.talentId));
        if(ticket!==noticeEpoch||!allowed())return;
        noticeData=data;el('option','请选择导师身份',recipient).value='';
        for(const person of data.items)el('option',person.name+(person.bindingKey===data.defaultRecipient?.bindingKey?' · 部门组长':''),recipient).value=person.bindingKey;
        el('option','从钉钉人员列表选择',recipient).value='directory';
        el('option','使用手机号识别导师',recipient).value='mobile';
        recipient.value=data.defaultRecipient?.bindingKey||'directory';
        notifyTitle.textContent='发送导师提醒';
        noticeFeedback.textContent=data.config.verified?'默认归属部门组长，也可改选其他人员；发送时同步保存导师。':'请先在储备人才库的“钉钉设置”配置企业应用。';
        syncNotice();
        if(recipient.value==='directory'){
          loadDirectory();
          if(data.defaultRecipient)await chooseMentor(data.defaultRecipient.userId);
        }
      }catch(e){if(ticket===noticeEpoch)noticeFeedback.textContent=e.message;}
    }
    async function sendNotice(){
      if(send.disabled||noticeBusy||!notifying||!allowed())return;
      const ticket=noticeEpoch,row=notifying,person=['mobile','directory'].includes(recipient.value)?proof:noticeData.items.find(p=>p.bindingKey===recipient.value);
      noticeBusy=true;syncNotice();noticeFeedback.textContent='正在提交导师通知…';
      try{
        await request('onboarding/send',{talentId:row.talentId,version:row.version,requestId:noticeRequest,
          configVersion:noticeData.config.version,recipientId:person.userId,bindingKey:person.bindingKey,
          identityProof:person.identityProof,updateMentor:true,retryId:row.notice?.status==='failed'?row.notice.id:undefined});
        if(ticket===noticeEpoch&&allowed()){notifyDialog.close();await load();}
      }catch(e){if(ticket===noticeEpoch)noticeFeedback.textContent=e.message;}
      finally{noticeBusy=false;if(ticket===noticeEpoch)syncNotice();}
    }
    const noticeStates={preparing:'准备中',sending:'发送中',submitted:'等待钉钉回执',sent:'已送达导师',failed:'发送未完成',uncertain:'发送待人工核对'};
    async function depart(row,control){
      if(departing||!allowed()||!row.joined||row.departed)return;
      if(!confirm(`确认将 ${row.name} 标记为已离职吗？`))return;
      const ticket=epoch;departing=true;control.disabled=true;
      try{
        await request('onboarding/depart',{talentId:row.talentId,version:row.version,requestId:crypto.randomUUID()});
        if(ticket===epoch&&active&&allowed()){departing=false;status='departed';page=1;await load();}
      }catch(e){if(ticket===epoch&&active&&allowed())feedback.textContent=e.message+'，请刷新核对。';}
      finally{departing=false;if(control.isConnected)control.disabled=!row.joined||!!row.departed;}
    }
    async function load(){
      if(!active||!allowed()||dialog.open)return;
      const ticket=++epoch;feedback.textContent='正在读取入职安排…';clearTimeout(timer);
      try{
        const data=await request('onboarding?'+new URLSearchParams({status,page:String(page),q:search.value.trim()}));
        if(ticket!==epoch||!active||!allowed())return;
        options.forEach(({key,node})=>node.setAttribute('aria-pressed',String(key===status)));
        results.replaceChildren();feedback.textContent=`共 ${data.total} 人`;
        if(!data.items.length)el('p','二面通过并填写入职信息后，安排会自动显示在这里。',results,'hr-hint');
        else{
          const table=el('table','',results,'hr-onboarding-table'),thead=el('tr','',el('thead','',table)),body=el('tbody','',table);
          for(const [,label] of [...labels,['notice','导师提醒'],['actions','操作']])el('th',label,thead);
          for(const row of data.items){
            const tr=el('tr','',body);
            for(const [key] of labels)el('td',key==='joined'?(row.departed?'已离职':row.joined?'已入职':'未入职'):row[key]||'待填写',tr);
            const notice=el('td',row.notice?noticeStates[row.notice.status]:'未发送',tr);
            if(row.notice?.error)el('p',row.notice.error,notice,'hr-error');
            const controls=el('div','',el('td','',tr),'hr-actions');
            button('编辑',controls,()=>openEdit(row));
            const departure=button('员工离职',controls,()=>depart(row,departure));
            departure.disabled=departing||!row.joined||!!row.departed;
            const sendButton=button(row.notice?.status==='failed'?'重试导师提醒':'通知导师',controls,()=>openNotice(row));
            sendButton.disabled=!row.joined||!!row.departed||!!row.notice&&row.notice.status!=='failed';
          }
        }
        pager.hidden=false;page=data.page;pageLabel.textContent=`第 ${data.page} / ${data.pages} 页 · 每页 20 人`;
        previous.disabled=data.page<=1;next.disabled=data.page>=data.pages;
        if(data.items.some(r=>['preparing','sending','submitted'].includes(r.notice?.status)))timer=setTimeout(load,5000);
      }catch(e){if(ticket===epoch)feedback.textContent=e.message;}
    }
    function leave(){active=false;++epoch;clearTimeout(timer);}
    function reset(){leave();dialog.close();notifyDialog.close();page=1;status='all';search.value='';results.replaceChildren();}
    return {enter(){active=true;load();},leave,reset,openPass};
  };
})();
