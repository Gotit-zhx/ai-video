(() => {
  globalThis.createHrBi=({root,element:el,button,allowed,hrApi,navigate})=>{
    const head=el('div','',root,'hr-bi-heading'),copy=el('div','',head);
    el('span','RECRUITMENT INTELLIGENCE',copy,'hr-eyebrow');el('h2','招聘经营看板',copy);
    el('p','从需求、寻源到入职，看清进展，也看清待办。',copy,'hr-hint');
    const actions=el('div','',head,'hr-actions');button('刷新数据',actions,load);
    const download=el('a','导出 Excel 汇总与明细',actions,'hr-secondary hr-interview-download');
    download.download='';download.hidden=true;
    const filters=el('form','',root,'hr-bi-filters');
    const field=(label,tag='input')=>{const wrap=el('label','',filters,'hr-field');el('span',label,wrap);return el(tag,'',wrap);};
    const start=field('开始日期'),end=field('结束日期');start.type=end.type='date';start.required=end.required=true;
    const department=field('部门','select'),job=field('职位','select');
    const granularity=field('展示周期','select');
    const modes=[['day','日 K'],['week','周 K'],['month','月 K'],['quarter','季度 K'],['halfyear','半年 K'],['year','年 K']];
    for(const [key,label] of modes)el('option',label,granularity).value=key;
    granularity.value='day';granularity.addEventListener('change',load);
    el('option','全部部门',department).value='';el('option','全部职位',job).value='';
    const apply=el('button','查看统计',filters,'hr-primary');apply.type='submit';
    const period=field('快捷周期','select');
    for(const days of [7,30,90,180,365,1095])el('option',days===1095?'最近 3 年':'最近 '+days+' 天',period).value=String(days);
    const date=value=>new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(value);
    const periodDates=days=>{const today=new Date();end.value=date(today);start.value=date(new Date(today.getTime()-(days-1)*86400000));};
    period.value='30';periodDates(30);
    period.addEventListener('change',()=>{periodDates(Number(period.value));load();});
    filters.addEventListener('submit',event=>{event.preventDefault();load();});
    const feedback=el('p','',root,'hr-hint');feedback.setAttribute('role','status');
    const metrics=el('div','',root,'hr-bi-metrics'),detail=el('div','',root,'hr-bi-details');
    let active=false,generation=0,applied=null;
    const groups=[
      ['招聘与审批',[['demands','招聘需求','项','demands'],['headcount','需求编制','人','demands'],
        ['resumes','收到简历','份','resume-pool'],['pending','待审批简历','份','resume-pool'],
        ['approved','通过简历','份','resume-pool'],['resumeApprovalRate','审批通过率','%','resume-pool']]],
      ['面试与入职',[['talents','入库人才','人','pool'],['firstReviews','已填一面意见','人次','pool'],
        ['firstPassRate','一面通过率','%','pool'],['secondPassRate','二面通过率','%','pool'],
        ['onboarding','待入职安排','人','onboarding'],['joined','确认已入职','人','onboarding']]],
      ['寻源、AI 与跟进',[['sourcingRuns','寻源任务','次','jobs'],['examined','寻源评估','人次','jobs'],
        ['qualified','符合条件','人次','jobs'],['greetings','主动联系送达','条','jobs'],
        ['aiComplete','简历 AI 分析完成','份','resume-pool'],['averageScore','平均参考匹配分','分','resume-pool']]],
      ['自动化与异常',[['enabledRules','启用被动邀约','职位','jobs'],['speechTemplates','话术模板','条','speeches'],
        ['aiFailed','AI 分析失败','份','resume-pool'],['uncertain','发送待人工核对','条','pool'],
        ['notificationFailed','通知发送失败','条','pool'],['rejected','未通过简历','份','resume-pool']]]
    ];
    function table(parent,title,records,columns){
      const card=el('section','',parent,'hr-bi-card');el('h3',title,card);
      const scroll=el('div','',card,'hr-table-scroll'),node=el('table','',scroll,'hr-bi-table');
      const headers=el('tr','',el('thead','',node));
      for(const [,label] of columns)el('th',label,headers);
      const body=el('tbody','',node);
      if(!records.length){el('p','所选范围暂无记录。',card,'hr-hint');return;}
      for(const record of records){
        const tr=el('tr','',body);
        for(const [key] of columns)el('td',record[key]??'—',tr);
      }
    }
    function bars(parent,title,records){
      const card=el('section','',parent,'hr-bi-card');el('h3',title,card);
      const max=Math.max(1,...records.map(r=>r.count));
      for(const row of records){
        const line=el('div','',card,'hr-bi-bar-row');el('span',row.name,line);
        const track=el('span','',line,'hr-bi-bar-track'),bar=el('span','',track,'hr-bi-bar');
        bar.style.width=(row.count/max*100)+'%';el('strong',row.count,line);
      }
    }
    function render(data){
      metrics.replaceChildren();detail.replaceChildren();
      for(const [title,items] of groups){
        const section=el('section','',metrics,'hr-bi-metric-group');el('h3',title,section);
        const grid=el('div','',section,'hr-bi-metric-grid');
        for(const [key,label,unit,route] of items){
          const tile=button('',grid,()=>navigate(route));tile.className='hr-bi-metric';
          el('span',label,tile);const number=el('div','',tile);
          el('strong',data.metrics[key]??'—',number);el('small',unit,number);
          tile.title='打开'+({demands:'招聘需求池','resume-pool':'简历池',onboarding:'入职安排',pool:'储备人才库',jobs:'职位管理',speeches:'话术管理'}[route]);
        }
      }
      const charts=el('div','',detail,'hr-bi-chart-grid');
      const trend=el('section','',charts,'hr-bi-card hr-bi-trend');
      el('h3',(modes.find(([key])=>key===data.filters.granularity)?.[1]||'日 K')+' · 招聘进展',trend);
      const legend=el('p','收到简历 · 一面意见 · 二面意见',trend,'hr-hint');
      const series=el('select','',trend);series.setAttribute('aria-label','趋势指标');
      const seriesGroups=[['简历与面试',[['resumes','收到简历'],['firstReviews','一面意见'],['secondReviews','二面意见']]],
        ['招聘需求',[['demands','招聘需求'],['headcount','需求编制']]],
        ['AI 寻源与主动联系',[['examined','评估人次'],['qualified','符合条件'],['greetings','主动联系送达']]],
        ['入职安排',[['onboarding','待入职'],['joined','已入职']]],
        ['消息与通知',[['notifications','通知记录'],['notificationSent','通知送达']]]];
      for(const [name] of seriesGroups)el('option',name,series).value=name;series.value=seriesGroups[0][0];
      const plot=el('div','',trend,'hr-bi-trend-plot');plot.setAttribute('role','img');
      const range=el('p','',trend,'hr-hint');
      function draw(){
        plot.replaceChildren();const columns=seriesGroups.find(([name])=>name===series.value)[1];
        legend.textContent=columns.map(([,name])=>name).join(' · ');
        plot.setAttribute('aria-label',legend.textContent+'，所选周期数量可在下方明细查看');
        const maximum=Math.max(0,...data.trend.flatMap(r=>columns.map(([key])=>r[key]||0)));
        for(const row of data.trend){
          const column=el('div','',plot,'hr-bi-trend-day');column.title=row.date+'：'+columns.map(([key,name])=>name+' '+(row[key]||0)).join('，');
          columns.forEach(([key],index)=>{const bar=el('span','',column,'hr-bi-trend-bar '+['resumes','firstReviews','secondReviews'][index]);
            bar.style.height=((row[key]||0)/(maximum||1)*150)+'px';});
        }
        range.textContent=data.filters.from+' — '+data.filters.to+' · 峰值 '+maximum;
      }
      series.addEventListener('change',draw);draw();
      const trendData=el('details','',trend);el('summary','展开周期明细',trendData);
      table(trendData,'周期明细',data.trend,[['date','周期'],['resumes','简历'],['firstReviews','一面意见'],['secondReviews','二面意见'],
        ['demands','需求'],['headcount','编制'],['examined','评估人次'],['qualified','符合条件'],['greetings','主动联系送达'],
        ['onboarding','待入职'],['joined','已入职'],['notifications','通知记录'],['notificationSent','通知送达']]);
      bars(charts,'邀约来源 · 简历份数',data.sources.map(r=>({...r,name:r.name+'（通过 '+r.approved+'）'})));
      bars(charts,'储备人才当前阶段',data.stages);
      bars(charts,'待审批简历等待时长',data.aging||[]);
      const columns=[['name','名称'],['demands','需求'],['headcount','编制'],['resumes','简历'],
        ['pending','待审批'],['approved','简历通过'],['firstReviews','一面意见'],['firstPassed','一面通过'],
        ['secondReviews','二面意见'],['secondPassed','二面通过'],['onboarding','待入职'],['joined','已入职']];
      table(detail,'部门招聘明细',data.departments,columns);
      table(detail,'职位招聘明细',data.jobs,columns);
      const definitions=el('details','',detail,'hr-bi-card');el('summary','统计口径与导出内容',definitions);
      const list=el('ul','',definitions);data.definitions.forEach(text=>el('li',text,list));
    }
    function options(select,rows,allLabel){
      const chosen=select.value;select.replaceChildren();el('option',allLabel,select).value='';
      for(const row of rows)el('option',row.name,select).value=row.id;
      if(select===department)el('option','未关联部门',select).value='unassigned';
      select.value=rows.some(r=>r.id===chosen)||chosen==='unassigned'?chosen:'';
    }
    async function load(){
      if(!active||!allowed())return;
      const ticket=++generation,params=new URLSearchParams({from:start.value,to:end.value,department:department.value,job:job.value,granularity:granularity.value});
      apply.disabled=true;download.hidden=true;feedback.className='hr-hint';feedback.textContent='正在统计平台记录…';
      try{
        const response=await hrApi('/api/v1/hr/bi?'+params,{});
        if(ticket!==generation||!active||!allowed())return;
        if(response.success===false||!response.data?.metrics)throw Error(response.message||'报表读取失败');
        const data=response.data;options(department,data.options.departments,'全部部门');options(job,data.options.jobs,'全部职位');
        applied=data.filters;render(data);
        download.href='/api/v1/hr/bi/export?'+new URLSearchParams(applied);download.hidden=false;
        feedback.textContent='数据截至 '+new Date(data.generatedAt).toLocaleString('zh-CN')+' · 导出包含汇总与各模块明细工作表';
      }catch(e){if(ticket===generation){feedback.textContent=e.message;feedback.className='hr-error';}}
      finally{if(ticket===generation)apply.disabled=false;}
    }
    return {enter(){active=true;load();},leave(){active=false;++generation;},reset(){active=false;++generation;metrics.replaceChildren();detail.replaceChildren();download.hidden=true;applied=null;}};
  };
})();
