(() => {
  globalThis.createHrDepartments=({root,section,element:el,button,allowed})=>{
    const panel=section('departments');let active=false,generation=0,items=[],editing=null,saving=false,dingtalkConfig=null;
    const header=el('div','',panel,'hr-pool-toolbar'),intro=el('div','',header);
    el('h2','部门管理',intro);el('p','独立维护招聘部门和经理，供招聘需求选择。',intro,'hr-hint');
    const actions=el('div','',header,'hr-actions');
    button('刷新',actions,load);
    const create=button('新建部门',actions,()=>open());create.className='hr-primary';create.disabled=true;
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const result=el('div','',panel,'hr-result');
    const dialog=el('dialog','',root,'hr-pool-dialog hr-department-dialog'),form=el('form','',dialog);
    const title=el('h2','新建部门',form);
    el('p','保存部门后可用手机号绑定钉钉负责人。修改经理姓名后需重新绑定。',form,'hr-hint');
    const grid=el('div','',form,'hr-pool-fields'),inputs={};
    const field=(key,label,tag='input')=>{
      const wrap=el('label','',grid,'hr-field');el('span',label,wrap);const input=el(tag,'',wrap);input.name=key;inputs[key]=input;return input;
    };
    field('name','部门名称').required=true;inputs.name.maxLength=120;
    field('code','部门编码').required=true;inputs.code.maxLength=60;inputs.code.pattern='[A-Za-z0-9_-]+';
    inputs.code.placeholder='例如：ECOMMERCE';
    field('parentId','上级部门','select');field('manager','部门经理').maxLength=120;field('status','部门状态','select');
    [['ACTIVE','启用'],['DISABLED','停用']].forEach(([value,label])=>el('option',label,inputs.status).value=value);
    const error=el('p','',form,'hr-error');error.hidden=true;error.setAttribute('role','alert');
    const formActions=el('div','',form,'hr-actions'),close=button('取消',formActions,()=>dialog.close());
    const save=el('button','保存部门',formActions,'hr-primary');save.type='submit';
    const bindingDialog=el('dialog','',root,'hr-pool-dialog hr-department-binding');
    const bindingHead=el('div','',bindingDialog,'hr-dingtalk-heading');
    el('h2','绑定钉钉负责人',bindingHead);
    const bindingClose=button('关闭',bindingHead,()=>bindingDialog.close());
    const bindingIntro=el('p','',bindingDialog,'hr-hint');
    const bindingForm=el('form','',bindingDialog),mobileWrap=el('label','',bindingForm,'hr-field');
    el('span','负责人钉钉手机号',mobileWrap);
    const mobile=el('input','',mobileWrap);mobile.type='tel';mobile.inputMode='tel';mobile.maxLength=30;mobile.autocomplete='off';mobile.required=true;
    mobile.placeholder='填写企业通讯录中的手机号';
    const identityFeedback=el('p','',bindingForm,'hr-hint');identityFeedback.setAttribute('role','status');
    const identityActions=el('div','',bindingForm,'hr-actions');
    const identify=el('button','识别钉钉身份',identityActions,'hr-primary');identify.type='submit';
    const bind=button('确认绑定',identityActions,confirmBinding);bind.className='hr-primary';bind.hidden=true;
    let bindingRow=null,identityProof='',bindingEpoch=0,bindingBusy=false;
    function resetIdentity(){identityProof='';bind.hidden=true;identityFeedback.textContent='';}
    mobile.addEventListener('input',resetIdentity);
    bindingDialog.addEventListener('close',()=>{++bindingEpoch;identityProof='';bindingRow=null;mobile.value='';});
    bindingDialog.addEventListener('cancel',event=>{if(bindingBusy)event.preventDefault();});
    function openBinding(row){
      ++bindingEpoch;bindingRow=row;mobile.value='';resetIdentity();
      bindingIntro.textContent=`${row.name}：绑定后，关联该部门的招聘需求自动使用这位一面负责人。`+
        (row.dingtalk?` 当前绑定：${row.dingtalk.name} · ${row.dingtalk.mobileMasked}。`:'');
      if(!dingtalkConfig?.verified)identityFeedback.textContent='请先到储备人才库的“钉钉设置”保存并校验企业应用，再回来绑定。';
      identify.disabled=!dingtalkConfig?.verified;mobile.disabled=!dingtalkConfig?.verified;
      bind.disabled=bindingClose.disabled=false;bindingDialog.showModal();
    }
    function busyIdentity(value){
      bindingBusy=value;identify.disabled=value||!dingtalkConfig?.verified;
      bind.disabled=bindingClose.disabled=mobile.disabled=value;
    }
    bindingForm.addEventListener('submit',async event=>{
      event.preventDefault();if(bindingBusy||!bindingRow||!allowed()||identify.disabled)return;
      const epoch=bindingEpoch;resetIdentity();busyIdentity(true);identityFeedback.textContent='正在核对企业钉钉身份…';
      try{
        const identity=await request({id:bindingRow.id,version:bindingRow.version,mobile:mobile.value.trim(),
          configVersion:dingtalkConfig.version},'/identity');
        if(epoch!==bindingEpoch||!active||!allowed())return;
        identityProof=identity.identityProof;
        identityFeedback.textContent=`识别到：${identity.name} · ${identity.mobileMasked}。确认是该部门的一面负责人后绑定，部门经理会同步为此姓名。`;
        bind.hidden=false;
      }catch(err){if(epoch===bindingEpoch&&allowed())identityFeedback.textContent=err.message;}
      finally{busyIdentity(false);}
    });
    async function confirmBinding(){
      if(bindingBusy||!identityProof||!bindingRow||!allowed())return;
      const epoch=bindingEpoch;busyIdentity(true);identityFeedback.textContent='正在保存负责人绑定…';
      try{
        const saved=await request({id:bindingRow.id,identityProof},'/bind');
        if(epoch!==bindingEpoch||!active||!allowed())return;
        bindingDialog.close();await load();if(active&&allowed())feedback.textContent=`已绑定 ${saved.name} · ${saved.mobileMasked}，关联岗位将使用此负责人。`;
      }catch(err){if(epoch===bindingEpoch&&allowed())identityFeedback.textContent=err.message;}
      finally{busyIdentity(false);}
    }
    async function request(body,path=body?'/save':''){
      const response=await api('/api/v1/hr/departments'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
      if(response.success===false||!response.data)throw Error(response.message||'部门操作失败');
      return response.data;
    }
    async function load(){
      if(!active||!allowed())return;const ticket=++generation;
      feedback.textContent='正在读取部门…';create.disabled=true;
      try{
        const data=await request();if(ticket!==generation||!active||!allowed())return;
        items=data.items;dingtalkConfig=data.dingtalkConfig;create.disabled=false;feedback.textContent=`共 ${items.length} 个部门`;
        result.replaceChildren();
        if(!items.length){el('p','暂无部门，点击“新建部门”统一维护。',result,'hr-pool-empty');return;}
        const cards=el('div','',result,'hr-card-grid');
        for(const row of items){
          const card=el('article','',cards,'hr-person-card'),head=el('div','',card,'hr-card-heading');
          el('h3',row.name,head);el('span',row.status==='ACTIVE'?'启用':'停用',head,'hr-badge');
          el('p',row.code,card,'hr-hint');
          const facts=el('dl','',card,'hr-facts');
          for(const [label,value] of [['部门经理',row.manager||'未设置'],['上级部门',items.find(p=>p.id===row.parentId)?.name||'无'],
            ['钉钉负责人',row.dingtalk?`${row.dingtalk.name} · ${row.dingtalk.mobileMasked}${row.dingtalk.valid?'':' · 需重新校验或启用部门'}`:'未绑定']]){
            const item=el('div','',facts);el('dt',label,item);el('dd',value,item);
          }
          const cardActions=el('div','',card,'hr-actions');
          button('编辑部门',cardActions,()=>open(row));
          const binding=button(row.dingtalk?.valid?'更换负责人':'绑定钉钉负责人',cardActions,()=>openBinding(row));
          binding.disabled=row.status!=='ACTIVE';
        }
      }catch(err){if(ticket===generation){feedback.textContent='读取失败';result.replaceChildren();el('p',err.message,result,'hr-error');}}
    }
    function open(row={}){
      editing=row;title.textContent=row.id?'编辑部门':'新建部门';error.hidden=true;
      inputs.name.value=row.name||'';inputs.code.value=row.code||'';inputs.status.value=row.status||'ACTIVE';
      inputs.parentId.replaceChildren();el('option','无上级部门',inputs.parentId).value='';
      const descendants=new Set(row.id?[row.id]:[]);
      for(let i=0;i<items.length;i++)for(const item of items)if(descendants.has(item.parentId))descendants.add(item.id);
      for(const item of items)if(!descendants.has(item.id))el('option',item.name,inputs.parentId).value=item.id;
      inputs.parentId.value=row.parentId||'';
      inputs.manager.value=row.manager||'';dialog.showModal();
    }
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(saving||!allowed())return;
      saving=true;save.disabled=close.disabled=true;error.hidden=true;const ticket=generation;
      try{
        await request({id:editing.id||'',version:editing.version,...Object.fromEntries(Object.entries(inputs).map(([key,input])=>[key,input.value.trim()]))});
        if(ticket!==generation||!allowed())return;
        dialog.close();await load();
      }catch(err){if(ticket===generation){error.textContent=err.message;error.hidden=false;}}
      finally{saving=false;save.disabled=close.disabled=false;}
    });
    dialog.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
    return {enter(){active=true;load();},leave(){active=false;++generation;dialog.close();bindingDialog.close();},
      reset(){active=false;++generation;items=[];editing=null;dingtalkConfig=null;dialog.close();bindingDialog.close();resetIdentity();mobile.value='';result.replaceChildren();
        Object.values(inputs).forEach(input=>{input.value='';if(input.tagName==='SELECT')input.replaceChildren();});error.textContent='';}};
  };
})();
