/* Company departments come from DingTalk; HR selects a member as the interviewer. */
(() => {
  globalThis.createHrDepartments=({root,section,element:el,button,allowed})=>{
    const panel=section('departments');
    let active=false,generation=0,items=[],config=null,syncBusy=false,syncRunning=false,syncTimer=null,bindingButtons=[];
    let bindingRow=null,bindingEpoch=0,bindingBusy=false,identityProof='',selectedId='';
    let memberEpoch=0,memberTimer=null,memberPage=1,memberPages=1,membersBusy=false;
    let departmentPage=1,departmentPages=1;
    const header=el('div','',panel,'hr-pool-toolbar'),intro=el('div','',header);
    el('h2','部门管理',intro);el('p','同步钉钉组织架构，从部门成员中选择招聘负责人。',intro,'hr-hint');
    const actions=el('div','',header,'hr-actions');
    const syncButton=button('同步钉钉部门',actions,async()=>{if(await load(false))await synchronize(true);});
    syncButton.className='hr-primary';syncButton.disabled=true;
    const filters=el('div','',panel,'hr-form');
    const field=(label,parent,tag='input')=>{
      const wrap=el('label','',parent,'hr-field');el('span',label,wrap);return el(tag,'',wrap);
    };
    const search=field('查询部门',filters);search.placeholder='部门名称或负责人';search.maxLength=100;
    search.addEventListener('input',()=>{departmentPage=1;render();});
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const result=el('div','',panel,'hr-result');
    const departmentPager=el('div','',panel,'hr-pagination hr-pool-pagination');
    departmentPager.setAttribute('role','navigation');departmentPager.setAttribute('aria-label','部门列表分页');
    const departmentPrevious=button('上一页',departmentPager,()=>{departmentPage--;render();});
    departmentPrevious.setAttribute('aria-label','部门上一页');
    const departmentNumbers=el('div','',departmentPager,'hr-pool-page-numbers');
    const departmentNext=button('下一页',departmentPager,()=>{departmentPage++;render();});
    departmentNext.setAttribute('aria-label','部门下一页');
    const departmentPageLabel=el('span','',departmentPager,'hr-hint');departmentPager.hidden=true;
    const dialog=el('dialog','',root,'hr-pool-dialog hr-department-binding');
    const heading=el('div','',dialog,'hr-dingtalk-heading'),title=el('h2','选择部门负责人',heading);
    const close=button('关闭',heading,()=>dialog.close());
    const bindingIntro=el('p','',dialog,'hr-hint');
    const memberFields=el('div','',dialog,'hr-pool-fields');
    const memberQuery=field('查询部门成员',memberFields);memberQuery.placeholder='输入姓名';memberQuery.maxLength=100;
    const memberSelect=field('部门负责人',memberFields,'select');
    const memberFeedback=el('p','',dialog,'hr-hint');memberFeedback.setAttribute('role','status');
    const pager=el('div','',dialog,'hr-actions');pager.hidden=true;
    const previous=button('上一页',pager,()=>{memberPage--;resetIdentity();loadMembers();});
    const next=button('下一页',pager,()=>{memberPage++;resetIdentity();loadMembers();});
    const pageLabel=el('span','',pager,'hr-hint');
    const identityFeedback=el('p','',dialog,'hr-hint');identityFeedback.setAttribute('role','status');
    const bindingActions=el('div','',dialog,'hr-actions');
    const retry=button('重新读取成员',bindingActions,()=>{resetIdentity();loadMembers(true);});
    const bind=button('确认负责人',bindingActions,confirmBinding);bind.className='hr-primary';bind.disabled=true;
    function resetIdentity(){identityProof='';selectedId='';identityFeedback.textContent='';bind.disabled=true;}
    function paintBusy(){
      memberQuery.disabled=memberSelect.disabled=bindingBusy||membersBusy;
      bind.disabled=bindingBusy||!identityProof;close.disabled=bindingBusy;
      retry.disabled=bindingBusy||membersBusy;
      previous.disabled=bindingBusy||membersBusy||memberPage<=1;
      next.disabled=bindingBusy||membersBusy||memberPage>=memberPages;
      syncButton.disabled=!config?.verified||syncBusy||syncRunning||bindingBusy;
      for(const b of bindingButtons)b.disabled=syncBusy||syncRunning||bindingBusy;
    }
    dialog.addEventListener('close',()=>{
      ++bindingEpoch;++memberEpoch;clearTimeout(memberTimer);bindingRow=null;bindingBusy=membersBusy=false;
      memberQuery.value=memberSelect.value='';memberSelect.replaceChildren();memberFeedback.textContent='';resetIdentity();paintBusy();
    });
    dialog.addEventListener('cancel',event=>{if(bindingBusy)event.preventDefault();});
    memberQuery.addEventListener('input',()=>{
      resetIdentity();memberPage=1;++memberEpoch;clearTimeout(memberTimer);
      memberTimer=setTimeout(()=>loadMembers(),300);
    });
    memberQuery.addEventListener('keydown',event=>{
      if(event.key==='Enter'){event.preventDefault();resetIdentity();memberPage=1;loadMembers();}
    });
    memberSelect.addEventListener('change',identifyMember);
    async function request(path='',body){
      const response=await api('/api/v1/hr/departments'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
      if(response.success===false||!response.data)throw Error(response.message||'部门操作失败');
      return response.data;
    }
    async function load(autoSync=true){
      if(!active||!allowed())return;
      const ticket=++generation;feedback.textContent='正在读取部门…';syncButton.disabled=true;
      try{
        const data=await request();if(ticket!==generation||!active||!allowed())return;
        items=data.items||[];config=data.dingtalkConfig;render();paintBusy();
        feedback.textContent=config?.verified?`共 ${items.filter(d=>d.synced).length} 个钉钉部门`:
          '请先在储备人才库的“钉钉设置”保存并校验企业应用，再同步部门。';
        if(autoSync&&config?.verified)await synchronize(false);
        return true;
      }catch(error){if(ticket===generation&&allowed())feedback.textContent=error.message;return false;}
    }
    async function synchronize(refresh=false){
      clearTimeout(syncTimer);
      if(!active||!allowed()||syncBusy||!config?.verified)return;
      const ticket=generation,version=config.version;syncBusy=true;paintBusy();
      feedback.textContent='正在同步钉钉部门与成员…';
      try{
        const data=await request('/sync',{configVersion:version,refresh});
        if(ticket!==generation||!active||!allowed()||config?.version!==version)return;
        syncRunning=data.state==='running';
        feedback.className=data.state==='failed'?'hr-error':'hr-hint';
        feedback.textContent=syncRunning?`正在同步 · 已读取 ${data.departmentCount} 个部门、${data.employeeCount} 人…`:
          data.state==='failed'?data.error:`已同步 ${data.departmentCount} 个部门、${data.employeeCount} 人`;
        if(data.state==='complete'){items=data.items||[];render();}
        if(syncRunning)syncTimer=setTimeout(()=>synchronize(false),1500);
      }catch(error){if(ticket===generation&&allowed()){syncRunning=false;feedback.className='hr-error';feedback.textContent=error.message;}}
      finally{if(ticket===generation){syncBusy=false;paintBusy();}}
    }
    function render(){
      result.replaceChildren();bindingButtons=[];
      const q=search.value.trim().toLocaleLowerCase();
      const visible=items.filter(d=>d.synced&&(!q||(d.name+' '+d.manager).toLocaleLowerCase().includes(q)));
      departmentPages=Math.max(1,Math.ceil(visible.length/12));
      departmentPage=Math.max(1,Math.min(departmentPage,departmentPages));
      departmentPager.hidden=!visible.length;
      departmentPrevious.disabled=departmentPage<=1;departmentNext.disabled=departmentPage>=departmentPages;
      departmentNumbers.replaceChildren();
      const start=Math.max(1,Math.min(departmentPage-2,departmentPages-4));
      for(let number=start;number<=Math.min(departmentPages,start+4);number++){
        const b=button(String(number),departmentNumbers,()=>{departmentPage=number;render();});
        b.setAttribute('aria-label','部门第 '+number+' 页');
        b.setAttribute('aria-current',number===departmentPage?'page':'false');b.disabled=number===departmentPage;
      }
      departmentPageLabel.textContent=`第 ${departmentPage} / ${departmentPages} 页 · 共 ${visible.length} 个部门 · 每页 12 个`;
      if(!visible.length){el('p',q?'没有找到对应部门':'同步钉钉后即可选择部门负责人。',result,'hr-pool-empty');return;}
      const cards=el('div','',result,'hr-card-grid hr-department-grid');
      for(const row of visible.slice((departmentPage-1)*12,departmentPage*12)){
        const card=el('article','',cards,'hr-person-card'),head=el('div','',card,'hr-card-heading');
        const name=el('h3',row.name,head);name.title=row.name;
        el('span',`${row.memberCount||0} 人`,head,'hr-badge');
        const facts=el('dl','',card,'hr-facts');
        for(const [label,value] of [['上级部门',items.find(d=>d.id===row.parentId)?.name||'无'],
          ['招聘负责人',row.dingtalk?.valid?row.dingtalk.name:'待选择']]){
          const item=el('div','',facts);el('dt',label,item);el('dd',value,item);
        }
        const b=button(row.dingtalk?.valid?'更换负责人':'选择负责人',el('div','',card,'hr-actions'),()=>openBinding(row));
        b.disabled=syncRunning||syncBusy;b.className='hr-secondary';
        bindingButtons.push(b);
      }
    }
    function openBinding(row){
      if(!allowed()||bindingBusy||syncBusy||syncRunning||!row.synced)return;
      ++bindingEpoch;++memberEpoch;bindingRow=row;memberPage=1;memberPages=1;membersBusy=false;
      resetIdentity();memberQuery.value='';memberSelect.replaceChildren();memberFeedback.textContent='';pager.hidden=true;
      title.textContent=row.name+' · 选择负责人';
      bindingIntro.textContent='仅显示该钉钉部门的成员。确认后，关联岗位的一面资料发送给此负责人。'+
        (row.dingtalk?.valid?' 当前负责人：'+row.dingtalk.name+'。':'');
      dialog.showModal();loadMembers();
    }
    async function loadMembers(refresh=false){
      clearTimeout(memberTimer);
      if(!bindingRow||!dialog.open||!active||!allowed())return;
      const epoch=bindingEpoch,serial=++memberEpoch,id=bindingRow.id,version=config.version;
      membersBusy=true;paintBusy();memberFeedback.textContent='正在读取部门成员…';
      try{
        const query=new URLSearchParams({id,configVersion:version,q:memberQuery.value,page:memberPage});
        if(refresh)query.set('refresh','1');
        const data=await request('/members?'+query);
        if(epoch!==bindingEpoch||serial!==memberEpoch||!active||!allowed()||config?.version!==version)return;
        memberPage=data.page;memberPages=data.pages;
        memberSelect.replaceChildren();el('option','请选择部门成员',memberSelect).value='';
        for(const person of data.items||[])el('option',person.name+' · ID 尾号 '+person.userId.slice(-4),memberSelect).value=person.userId;
        memberSelect.value=selectedId;
        memberFeedback.className=data.state==='failed'?'hr-error':'hr-hint';
        memberFeedback.textContent=data.state==='running'?'正在更新钉钉通讯录…':data.state==='failed'?data.error:
          `找到 ${data.total} 位部门成员`+(!data.total?'，可更换姓名查询':'');
        pager.hidden=data.state!=='complete'||data.pages<=1;pageLabel.textContent=`第 ${data.page} / ${data.pages} 页`;
        if(data.state==='running')memberTimer=setTimeout(()=>loadMembers(),1500);
      }catch(error){if(epoch===bindingEpoch&&serial===memberEpoch&&allowed())memberFeedback.textContent=error.message;}
      finally{if(epoch===bindingEpoch&&serial===memberEpoch){membersBusy=false;paintBusy();}}
    }
    async function identifyMember(){
      if(bindingBusy||!bindingRow||!allowed())return;
      const uid=memberSelect.value,epoch=bindingEpoch,id=bindingRow.id;
      resetIdentity();if(!uid)return;
      bindingBusy=true;paintBusy();identityFeedback.textContent='正在核对部门成员身份…';
      try{
        const person=await request('/identity',{id,version:bindingRow.version,userId:uid,configVersion:config.version});
        if(epoch!==bindingEpoch||!active||!allowed())return;
        identityProof=person.identityProof;selectedId=uid;
        identityFeedback.textContent='已核对：'+person.name+'。点击“确认负责人”完成设置。';
      }catch(error){if(epoch===bindingEpoch&&allowed())identityFeedback.textContent=error.message;}
      finally{if(epoch===bindingEpoch){bindingBusy=false;paintBusy();}}
    }
    async function confirmBinding(){
      if(bindingBusy||!identityProof||!bindingRow||!allowed())return;
      const epoch=bindingEpoch,id=bindingRow.id;bindingBusy=true;paintBusy();identityFeedback.textContent='正在保存负责人…';
      try{
        const saved=await request('/bind',{id,identityProof});
        if(epoch!==bindingEpoch||!active||!allowed())return;
        dialog.close();await load(false);
        if(active&&allowed())feedback.textContent='已设置负责人：'+saved.name+'，关联岗位将使用此负责人。';
      }catch(error){if(epoch===bindingEpoch&&allowed())identityFeedback.textContent=error.message;}
      finally{if(epoch===bindingEpoch){bindingBusy=false;paintBusy();}}
    }
    function leave(){
      active=false;++generation;clearTimeout(syncTimer);syncBusy=syncRunning=false;
      dialog.close();++bindingEpoch;++memberEpoch;clearTimeout(memberTimer);bindingBusy=membersBusy=false;
    }
    return {enter(){active=true;load();},leave,reset(){leave();items=[];config=null;search.value='';departmentPage=1;departmentPages=1;
      departmentNumbers.replaceChildren();departmentPageLabel.textContent='';departmentPager.hidden=true;
      bindingRow=null;resetIdentity();memberQuery.value=memberSelect.value='';memberSelect.replaceChildren();memberFeedback.textContent='';
      title.textContent='选择部门负责人';bindingIntro.textContent='';result.replaceChildren();feedback.textContent='';}};
  };
})();
