(() => {
  globalThis.createHrDepartments=({root,section,element:el,button,allowed})=>{
    const panel=section('departments');let active=false,generation=0,items=[],editing=null,saving=false;
    const header=el('div','',panel,'hr-pool-toolbar'),intro=el('div','',header);
    el('h2','部门管理',intro);el('p','独立维护招聘部门和经理，供招聘需求选择。',intro,'hr-hint');
    const actions=el('div','',header,'hr-actions');
    button('刷新',actions,load);
    const create=button('新建部门',actions,()=>open());create.className='hr-primary';create.disabled=true;
    const feedback=el('p','',panel,'hr-hint');feedback.setAttribute('role','status');
    const result=el('div','',panel,'hr-result');
    const dialog=el('dialog','',root,'hr-pool-dialog hr-department-dialog'),form=el('form','',dialog);
    const title=el('h2','新建部门',form);
    el('p','这里只管理招聘部门，经理可直接填写。',form,'hr-hint');
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
    async function request(body){
      const response=await api('/api/v1/hr/departments'+(body?'/save':''),body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});
      return response.data;
    }
    async function load(){
      if(!active||!allowed())return;const ticket=++generation;
      feedback.textContent='正在读取部门…';create.disabled=true;
      try{
        const data=await request();if(ticket!==generation||!active||!allowed())return;
        items=data.items;create.disabled=false;feedback.textContent=`共 ${items.length} 个部门`;
        result.replaceChildren();
        if(!items.length){el('p','暂无部门，点击“新建部门”统一维护。',result,'hr-pool-empty');return;}
        const cards=el('div','',result,'hr-card-grid');
        for(const row of items){
          const card=el('article','',cards,'hr-person-card'),head=el('div','',card,'hr-card-heading');
          el('h3',row.name,head);el('span',row.status==='ACTIVE'?'启用':'停用',head,'hr-badge');
          el('p',row.code,card,'hr-hint');
          const facts=el('dl','',card,'hr-facts');
          for(const [label,value] of [['部门经理',row.manager||'未设置'],['上级部门',items.find(p=>p.id===row.parentId)?.name||'无']]){
            const item=el('div','',facts);el('dt',label,item);el('dd',value,item);
          }
          button('编辑部门',el('div','',card,'hr-actions'),()=>open(row));
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
    return {enter(){active=true;load();},leave(){active=false;++generation;dialog.close();},
      reset(){active=false;++generation;items=[];editing=null;dialog.close();result.replaceChildren();
        Object.values(inputs).forEach(input=>{input.value='';if(input.tagName==='SELECT')input.replaceChildren();});error.textContent='';}};
  };
})();
