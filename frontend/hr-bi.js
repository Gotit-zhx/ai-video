/* Tabler-inspired visual hierarchy; self-hosted Apache ECharts. */
(() => {
  const modes=[['day','日 K'],['week','周 K'],['month','月 K'],['quarter','季度 K'],['halfyear','半年 K'],['year','年 K']];
  const colors=['#4263eb','#22b8a8','#a78bfa','#f4b740','#f0788c','#73b8ff'];
  const icons={
    chart:'M4 19h16 M7 15v-4 M12 15V5 M17 15V8',
    resume:'M8 3h8l4 4v14H4V3h4 M14 3v5h6 M8 12h8 M8 16h5',
    clock:'M12 8v4l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
    check:'M7 12l3 3 7-7 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
    people:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M18 8a3 3 0 0 1 0 6 M22 21v-2a4 4 0 0 0-3-4',
    briefcase:'M4 7h16v14H4z M8 7V3h8v4 M4 12h16 M10 12v3h4v-3',
    sparkle:'M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z',
    download:'M12 3v12 M7 10l5 5 5-5 M4 16v5h16v-5',
    refresh:'M20 7v5h-5 M4 17v-5h5 M6 7a7 7 0 0 1 12-2l2 3 M18 17a7 7 0 0 1-12 2l-2-3',
    arrow:'M7 17L17 7 M7 7h10v10'
  };
  let chartLibrary;
  function ensureCharts(){
    if(globalThis.echarts)return Promise.resolve(globalThis.echarts);
    if(!document.head)return Promise.resolve(null);
    if(!chartLibrary)chartLibrary=new Promise(resolve=>{
      const script=document.createElement('script');script.src='/vendor/echarts/echarts.min.js?v=6.1.0';script.async=true;
      const timeout=setTimeout(()=>done(null),12000);
      function done(value){clearTimeout(timeout);script.onload=script.onerror=null;if(!value)script.remove();resolve(value);}
      script.onload=()=>done(globalThis.echarts||null);script.onerror=()=>done(null);document.head.append(script);
    }).then(value=>{if(!value)chartLibrary=null;return value;});
    return chartLibrary;
  }
  globalThis.createHrBi=({root,element:el,button,allowed,hrApi,navigate})=>{
    const shell=el('div','',root,'hr-bi-dashboard');
    function icon(key,parent){
      const node=document.createElementNS('http://www.w3.org/2000/svg','svg');
      node.setAttribute('viewBox','0 0 24 24');node.setAttribute('aria-hidden','true');
      const path=document.createElementNS('http://www.w3.org/2000/svg','path');
      path.setAttribute('d',icons[key]||icons.chart);node.append(path);parent.append(node);
    }
    const head=el('div','',shell,'hr-bi-heading'),copy=el('div','',head);
    el('span','RECRUITMENT / ANALYTICS',copy,'hr-bi-eyebrow');
    el('h2','招聘经营看板',copy);el('p','让招聘进展、团队效率和每一位人才，都清晰可见。',copy,'hr-hint');
    const actions=el('div','',head,'hr-actions'),refresh=button('',actions,load);
    refresh.className='hr-bi-refresh';icon('refresh',refresh);el('span','刷新数据',refresh);
    const download=el('a','',actions,'hr-secondary hr-bi-export');download.download='';download.hidden=true;
    icon('download',download);el('span','导出 Excel 汇总与明细',download);
    const filters=el('form','',shell,'hr-bi-filters');
    const field=(label,tag='input')=>{const wrap=el('label','',filters,'hr-field');el('span',label,wrap);return el(tag,'',wrap);};
    const start=field('开始日期'),end=field('结束日期');start.type=end.type='date';start.required=end.required=true;
    const department=field('部门','select'),job=field('职位','select');
    el('option','全部部门',department).value='';el('option','全部职位',job).value='';
    const period=field('快捷周期','select');
    for(const days of [7,30,90,180,365,1095])el('option',days===1095?'最近 3 年':'最近 '+days+' 天',period).value=String(days);
    const apply=el('button','查看统计',filters,'hr-primary');apply.type='submit';
    const date=value=>new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(value);
    const periodDates=days=>{const today=new Date();end.value=date(today);start.value=date(new Date(today.getTime()-(days-1)*86400000));};
    period.value='30';periodDates(30);
    const state=el('div','',shell,'hr-bi-state'),feedback=el('p','',state,'hr-hint');feedback.setAttribute('role','status');
    const metricRoot=el('div','',shell,'hr-bi-metrics'),detailRoot=el('div','',shell,'hr-bi-details');
    let active=false,generation=0,applied=null,data=null,charts=[],observer=null,themeObserver=null;
    let chartKind='line',seriesName='简历与面试',zoom=null;
    const cycleBar=el('div','',shell,'hr-bi-cyclebar'),cycleCopy=el('div','',cycleBar);
    shell.insertBefore(cycleBar,detailRoot);
    el('span','周期趋势',cycleCopy,'hr-bi-section-kicker');
    const cycleDescription=el('p','按业务数量汇总 · 北京时间',cycleCopy,'hr-hint');
    const cycleActions=el('div','',cycleBar,'hr-bi-cycle-actions'),cycleButtons=el('div','',cycleActions,'hr-bi-segments');
    cycleButtons.setAttribute('aria-label','展示周期快捷切换');
    const cycleLabel=el('label','',cycleActions,'hr-bi-cycle-select');el('span','展示周期',cycleLabel,'hr-bi-sr-only');
    const granularity=el('select','',cycleLabel);for(const [key,label] of modes)el('option',label,granularity).value=key;
    granularity.value='day';
    const kButtons=modes.map(([key,label])=>{
      const node=button(label,cycleButtons,()=>{granularity.value=key;load();});node.dataset.period=key;return node;
    });
    const syncCycles=value=>kButtons.forEach(node=>node.setAttribute('aria-pressed',String(node.dataset.period===value)));
    syncCycles('day');granularity.addEventListener('change',load);
    period.addEventListener('change',()=>{periodDates(Number(period.value));load();});
    filters.addEventListener('submit',event=>{event.preventDefault();load();});
    const heroKeys=['resumes','pending','resumeApprovalRate','talents','joined','averageScore'];
    const groups=[
      ['招聘与审批',[['demands','招聘需求','项','demands','briefcase'],['headcount','需求编制','人','demands','people'],
        ['resumes','收到简历','份','resume-pool','resume'],['pending','待审批简历','份','resume-pool','clock'],
        ['approved','通过简历','份','resume-pool','check'],['resumeApprovalRate','审批通过率','%','resume-pool','check']]],
      ['面试与入职',[['talents','入库人才','人','pool','people'],['firstReviews','已填一面意见','人次','pool','resume'],
        ['firstPassRate','一面通过率','%','pool','check'],['secondPassRate','二面通过率','%','pool','check'],
        ['onboarding','待入职安排','人','onboarding','clock'],['joined','确认已入职','人','onboarding','briefcase']]],
      ['寻源、AI 与跟进',[['sourcingRuns','寻源任务','次','jobs','sparkle'],['examined','寻源评估','人次','jobs','people'],
        ['qualified','符合条件','人次','jobs','check'],['greetings','主动联系送达','条','jobs','chart'],
        ['aiComplete','简历 AI 分析完成','份','resume-pool','sparkle'],['averageScore','平均参考匹配分','分','resume-pool','sparkle']]],
      ['自动化与异常',[['enabledRules','启用被动邀约','职位','jobs','briefcase'],['speechTemplates','话术模板','条','speeches','resume'],
        ['aiFailed','AI 分析失败','份','resume-pool','clock'],['uncertain','发送待人工核对','条','pool','clock'],
        ['notificationFailed','通知发送失败','条','pool','clock'],['rejected','未通过简历','份','resume-pool','resume']]]
    ];
    const descriptions={resumes:'所选期间收到的简历',pending:'等待 HR 审批',resumeApprovalRate:'已处理简历的通过比例',
      talents:'所选期间入库人才',joined:'已确认到岗',averageScore:'已完成 AI 分析 · 仅供参考'};
    const number=value=>value==null?'—':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:1});
    function metric(parent,item,hero=false,index=0){
      const [key,label,unit,route,symbol]=item,tile=button('',parent,()=>navigate(route));
      tile.className='hr-bi-metric'+(hero?' is-featured':'');tile.style.setProperty?.('--bi-accent',colors[index%colors.length]);
      const title=el('div','',tile,'hr-bi-metric-title');el('span',label,title);
      if(hero){const badge=el('span','',title,'hr-bi-icon');icon(symbol,badge);}
      const value=el('div','',tile,'hr-bi-metric-value');el('strong',number(data.metrics[key]),value);el('small',unit,value);
      if(hero){const foot=el('div','',tile,'hr-bi-metric-foot');el('span',descriptions[key],foot);icon('arrow',foot);}
      tile.title='打开'+({demands:'招聘需求池','resume-pool':'简历池',onboarding:'入职安排',pool:'储备人才库',jobs:'职位管理',speeches:'话术管理'}[route]);
    }
    function table(parent,title,records,columns){
      const card=el('section','',parent,'hr-bi-card hr-bi-table-card'),heading=el('div','',card,'hr-bi-card-heading');
      el('h3',title,heading);el('span',records.length+' 条',heading,'hr-bi-tag');
      const scroll=el('div','',card,'hr-table-scroll');scroll.tabIndex=0;scroll.setAttribute('aria-label',title+'，可横向滚动');
      const node=el('table','',scroll,'hr-bi-table'),headers=el('tr','',el('thead','',node));
      for(const [,label] of columns)el('th',label,headers).setAttribute('scope','col');
      const body=el('tbody','',node);
      if(!records.length){el('p','所选范围暂无记录。',card,'hr-bi-empty');return;}
      for(const record of records){const tr=el('tr','',body);for(const [key] of columns)el('td',record[key]??'—',tr);}
    }
    function theme(){
      const dark=document.documentElement?.dataset?.theme==='dark';
      return {dark,ink:dark?'#e2e8f0':'#24304a',muted:dark?'#95a3bb':'#8590a6',grid:dark?'#2a3448':'#edf0f6',surface:dark?'#182236':'#ffffff'};
    }
    function destroyCharts(){
      charts.forEach(record=>record.instance?.dispose());charts=[];
      observer?.disconnect();observer=null;themeObserver?.disconnect();themeObserver=null;
    }
    function baseOptions(){
      const t=theme();return {
        color:colors,animation:!globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches,
        animationDuration:450,animationDurationUpdate:250,
        textStyle:{fontFamily:'Inter, -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif',color:t.ink},
        tooltip:{trigger:'item',renderMode:'richText',backgroundColor:t.surface,borderColor:t.grid,textStyle:{color:t.ink,fontSize:12},confine:true},
        // Keep readable DOM chart labels; generated stacked-series ARIA includes internal dimensions.
        aria:{enabled:false},legend:{icon:'circle',itemWidth:8,itemHeight:8,textStyle:{color:t.muted,fontSize:11},bottom:0},
        grid:{left:8,right:16,top:20,bottom:36,containLabel:true}
      };
    }
    const axis=(type='value')=>({type,axisLine:{show:false},axisTick:{show:false},
      axisLabel:{color:theme().muted,fontSize:11},splitLine:{show:type==='value',lineStyle:{color:theme().grid,type:'dashed'}},
      minInterval:type==='value'?1:undefined});
    function chart(card,label,builder,height=230,nonempty=true){
      const plot=el('div','',card,'hr-bi-plot');plot.style.height=height+'px';
      plot.setAttribute('role','img');plot.setAttribute('aria-label',label);plot.dataset.chart=label;
      const empty=el('p','所选范围暂无数据',card,'hr-bi-chart-empty');empty.hidden=nonempty;
      if(!nonempty)plot.setAttribute('aria-label',label+'，所选范围暂无数据');
      plot.dataset.updateKey='chart:'+label;plot.hrPlot={builder};
    }
    function chartCard(parent,title,subtitle,extra=''){
      const card=el('section','',parent,'hr-bi-card '+extra),heading=el('div','',card,'hr-bi-card-heading'),copy=el('div','',heading);
      card.dataset.updateKey=extra==='hr-bi-trend'?'trend':title;
      el('h3',title,copy);if(subtitle)el('p',subtitle,copy,'hr-hint');return {card,heading};
    }
    function mountCharts(){
      if(!globalThis.echarts)return;
      charts=charts.filter(record=>{if(detailRoot.contains(record.node))return true;record.instance?.dispose();return false;});
      for(const node of detailRoot.querySelectorAll('.hr-bi-plot')){
        let record=charts.find(r=>r.node===node);
        if(!record){
          record={node,instance:globalThis.echarts.init(node,null,{renderer:'svg'})};charts.push(record);
          record.instance.on?.('datazoom',event=>{
            if(node.dataset.chart==='招聘周期趋势'){const bounds=event.batch?.[0]||event;zoom={start:bounds.start,end:bounds.end};}
          });
        }
        record.builder=node.hrPlot.builder;
        IncrementalCharts.update(record.instance,{...baseOptions(),...record.builder()});
      }
      if(!observer&&globalThis.ResizeObserver){
        observer=new ResizeObserver(()=>{if(active)charts.forEach(r=>IncrementalCharts.resize(r.instance,r.node));});observer.observe(shell);
      }
      if(!themeObserver&&globalThis.MutationObserver&&document.documentElement){
        themeObserver=new MutationObserver(()=>{if(active)charts.forEach(r=>IncrementalCharts.update(r.instance,{...baseOptions(),...r.builder()}));});
        themeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
      }
    }
    const seriesGroups=[
      ['简历与面试',[['resumes','收到简历'],['firstReviews','一面意见'],['secondReviews','二面意见']]],
      ['招聘需求',[['demands','招聘需求'],['headcount','需求编制']]],
      ['AI 寻源与主动联系',[['examined','评估人次'],['qualified','符合条件'],['greetings','主动联系送达']]],
      ['入职安排',[['onboarding','待入职'],['joined','已入职']]],
      ['消息与通知',[['notifications','通知记录'],['notificationSent','通知送达']]]
    ];
    function render(){
      const metrics=metricRoot.cloneNode(false),detail=detailRoot.cloneNode(false);
      const allMetrics=groups.flatMap(([,items])=>items),featured=el('div','',metrics,'hr-bi-featured');
      heroKeys.forEach((key,index)=>metric(featured,allMetrics.find(item=>item[0]===key),true,index));
      const grid=el('div','',detail,'hr-bi-chart-grid');
      const trend=chartCard(grid,(modes.find(([key])=>key===data.filters.granularity)?.[1]||'日 K')+' · 招聘进展',
        '观察招聘节奏，悬停查看每个周期的数据。','hr-bi-trend');
      const tools=el('div','',trend.heading,'hr-bi-chart-tools'),series=el('select','',tools);series.setAttribute('aria-label','趋势指标');
      for(const [name] of seriesGroups)el('option',name,series).value=name;series.value=seriesName;
      const kinds=el('div','',tools,'hr-bi-segments is-small');
      const kindButtons=['line','bar'].map((kind,index)=>{
        const node=button(index?'柱状':'面积',kinds,()=>{});node.onclick=()=>{chartKind=kind;updateTrend();};node.dataset.kind=kind;return node;
      });
      const trendBuilder=()=>{
        const columns=seriesGroups.find(([name])=>name===seriesName)[1],long=data.trend.length>12;
        return {
          tooltip:{...baseOptions().tooltip,trigger:'axis',axisPointer:{type:chartKind==='bar'?'shadow':'line'}},
          grid:{left:5,right:15,top:26,bottom:long?82:46,containLabel:true},legend:{...baseOptions().legend,bottom:long?38:0},
          xAxis:{...axis('category'),data:data.trend.map(r=>r.date),boundaryGap:chartKind==='bar',
            axisLabel:{...axis('category').axisLabel,hideOverlap:true,margin:16}},yAxis:axis(),
          dataZoom:long?[{id:'trend-inside',type:'inside'},
            {id:'trend-slider',type:'slider',height:16,bottom:0,borderColor:'transparent',
              backgroundColor:theme().grid,fillerColor:'#4263eb20',showDetail:false,brushSelect:false,handleSize:0}]:[],
          series:columns.map(([key,name],index)=>({name,type:chartKind==='bar'?'bar':'line',data:data.trend.map(r=>r[key]||0),
            smooth:false,showSymbol:data.trend.length<=1,symbolSize:7,lineStyle:{width:2.5},barMaxWidth:26,
            itemStyle:{color:colors[index],borderRadius:chartKind==='bar'?[4,4,0,0]:0},
            areaStyle:chartKind==='line'?{color:{type:'linear',x:0,y:0,x2:0,y2:1,
              colorStops:[{offset:0,color:colors[index]+'35'},{offset:1,color:colors[index]+'02'}]}}:undefined}))
        };
      };
      chart(trend.card,'招聘周期趋势',trendBuilder,250);
      function updateTrend(){
        detailRoot.querySelectorAll('[data-kind]').forEach(node=>node.setAttribute('aria-pressed',String(node.dataset.kind===chartKind)));
        const record=charts.find(r=>r.node.dataset.chart==='招聘周期趋势');
        if(record){record.builder=trendBuilder;IncrementalCharts.update(record.instance,{...baseOptions(),...trendBuilder()});}
      }
      series.onchange=event=>{seriesName=event.currentTarget.value;updateTrend();};
      kindButtons.forEach(node=>node.setAttribute('aria-pressed',String(node.dataset.kind===chartKind)));
      el('span',data.filters.from+' — '+data.filters.to,trend.card,'hr-bi-range');
      const source=chartCard(grid,'简历来源','收到简历的来源构成','hr-bi-source');
      const sourceRows=data.sources||[],sourceTotal=sourceRows.reduce((sum,r)=>sum+r.count,0);
      chart(source.card,'简历来源环形图',()=>({
        legend:{show:false},series:[{type:'pie',radius:['63%','83%'],center:['50%','50%'],avoidLabelOverlap:true,
          label:{show:false},emphasis:{scaleSize:5,label:{show:false}},
          itemStyle:{borderColor:theme().surface,borderWidth:5,borderRadius:7},
          data:sourceTotal?sourceRows.map((r,index)=>({name:r.name,value:r.count,itemStyle:{color:colors[index]}})):
            [{name:'暂无简历',value:1,itemStyle:{color:theme().grid},tooltip:{show:false}}],silent:!sourceTotal}],
        graphic:[{type:'text',left:'center',top:'39%',style:{text:number(sourceTotal),fill:theme().ink,font:'600 34px sans-serif'}},
          {type:'text',left:'center',top:'59%',style:{text:'收到简历 / 份',fill:theme().muted,font:'12px sans-serif'}}]
      }),180,true);
      const sourceLegend=el('div','',source.card,'hr-bi-source-legend');
      for(const [index,row] of sourceRows.entries()){
        const line=el('div','',sourceLegend),dot=el('i','',line);dot.style.backgroundColor=colors[index%colors.length];
        el('span',row.name,line);el('strong',number(row.count),line);el('small',sourceTotal?Math.round(row.count/sourceTotal*100)+'%':'—',line);
      }
      const stages=chartCard(grid,'人才流程分布','所选期间入库人才的当前阶段','hr-bi-third'),stageRows=data.stages||[];
      chart(stages.card,'人才阶段横向条形图',()=>({
        grid:{left:0,right:28,top:8,bottom:8,containLabel:true},xAxis:{...axis(),show:false},
        yAxis:{...axis('category'),inverse:true,data:stageRows.map(r=>r.name)},
        series:[{type:'bar',barWidth:14,showBackground:true,backgroundStyle:{color:theme().grid,borderRadius:5},
          label:{show:true,position:'right',color:theme().ink,fontSize:11},
          data:stageRows.map((r,i)=>({value:r.count,itemStyle:{color:colors[i%colors.length],borderRadius:5}}))}]
      }),175,stageRows.some(r=>r.count>0));
      const approvals=chartCard(grid,'简历审批分布','待审批、通过与未通过的份数','hr-bi-third');
      const approvalValues=[data.metrics.pending||0,data.metrics.approved||0,data.metrics.rejected||0];
      chart(approvals.card,'简历审批堆叠柱形图',()=>({
        tooltip:{...baseOptions().tooltip,trigger:'axis',axisPointer:{type:'shadow'}},
        grid:{left:5,right:5,top:12,bottom:45,containLabel:true},xAxis:{...axis('category'),data:['简历池']},yAxis:axis(),
        series:['待审批','通过','未通过'].map((name,i)=>({name,type:'bar',stack:'total',barWidth:50,
          itemStyle:{color:[colors[3],colors[1],colors[4]][i]},data:[approvalValues[i]]}))
      }),175,approvalValues.some(v=>v>0));
      const aging=chartCard(grid,'待审批等待时长','按收到简历至今天的天数统计','hr-bi-third'),ageRows=data.aging||[];
      chart(aging.card,'待审批时长柱形图',()=>({
        grid:{left:4,right:5,top:22,bottom:5,containLabel:true},
        xAxis:{...axis('category'),data:ageRows.map(r=>r.name),axisLabel:{...axis().axisLabel,fontSize:10}},yAxis:axis(),
        series:[{name:'待审批',type:'bar',barMaxWidth:33,label:{show:true,position:'top',color:theme().muted},
          data:ageRows.map((r,i)=>({value:r.count,itemStyle:{color:[colors[0],colors[2],colors[3],colors[4]][i],borderRadius:[5,5,0,0]}}))}]
      }),175,ageRows.some(r=>r.count>0));
      const ranking=chartCard(grid,'部门招聘表现','按收到简历数排列 · 展示前 8 个部门','hr-bi-half');
      const ranked=[...(data.departments||[])].sort((a,b)=>b.resumes-a.resumes).slice(0,8);
      chart(ranking.card,'部门招聘横向排名图',()=>({
        tooltip:{...baseOptions().tooltip,trigger:'axis',axisPointer:{type:'shadow'}},
        grid:{left:0,right:35,top:10,bottom:40,containLabel:true},xAxis:axis(),
        yAxis:{...axis('category'),inverse:true,data:ranked.map(r=>r.name),axisLabel:{...axis().axisLabel,width:90,overflow:'truncate'}},
        series:[['resumes','收到简历',colors[0]],['approved','审批通过',colors[1]]].map(([key,name,color])=>({
          name,type:'bar',barMaxWidth:12,itemStyle:{color,borderRadius:[0,4,4,0]},data:ranked.map(r=>r[key]||0)}))
      }),210,ranked.some(r=>r.resumes>0));
      const heat=chartCard(grid,'职位进展热力图','每格为实际数量 · 展示简历最多的前 8 个职位','hr-bi-half');
      const jobRows=[...(data.jobs||[])].sort((a,b)=>b.resumes-a.resumes).slice(0,8);
      const dimensions=[['resumes','收到简历'],['pending','待审批'],['approved','审批通过'],['firstReviews','一面意见'],['secondReviews','二面意见'],['joined','已入职']];
      const cells=jobRows.flatMap((row,y)=>dimensions.map(([key],x)=>[x,y,row[key]||0])),maximum=Math.max(1,...cells.map(r=>r[2]));
      chart(heat.card,'职位进展热力图',()=>({
        tooltip:{...baseOptions().tooltip,position:'top',
          formatter:params=>jobRows[params.value[1]]?.name+'\n'+dimensions[params.value[0]][1]+'：'+params.value[2]},
        grid:{left:0,right:3,top:12,bottom:42,containLabel:true},
        xAxis:{...axis('category'),data:['简历','待审','通过','一面','二面','入职'],axisLabel:{...axis().axisLabel,fontSize:10,interval:0}},
        yAxis:{...axis('category'),data:jobRows.map(r=>r.name),inverse:true,axisLabel:{...axis().axisLabel,width:80,overflow:'truncate'}},
        visualMap:{min:0,max:maximum,show:false,inRange:{color:theme().dark?['#26314d','#4263eb']:['#f0f3ff','#c3d0ff','#4263eb']}},
        series:[{type:'heatmap',data:cells,label:{show:true,fontSize:11},
          itemStyle:{borderColor:theme().surface,borderWidth:5,borderRadius:4},emphasis:{itemStyle:{borderColor:colors[1],borderWidth:2}}}]
      }),210,jobRows.length>0);
      const efficiency=chartCard(grid,'寻源与分析','用真实数量观察执行情况','hr-bi-half');
      chart(efficiency.card,'寻源与分析柱形图',()=>({
        grid:{left:5,right:5,top:30,bottom:5,containLabel:true},
        xAxis:{...axis('category'),data:['评估人次','符合条件','联系送达','AI 分析完成']},yAxis:axis(),
        series:[{name:'数量',type:'bar',barMaxWidth:38,label:{show:true,position:'top',color:theme().ink},
          data:['examined','qualified','greetings','aiComplete'].map((key,i)=>({value:data.metrics[key]||0,
            itemStyle:{color:colors[i],borderRadius:[5,5,0,0]}}))}]
      }),185,['examined','qualified','greetings','aiComplete'].some(key=>data.metrics[key]>0));
      const quality=chartCard(grid,'审批与面试通过率','仅统计已处理记录，不包含待处理','hr-bi-half');
      const rateItems=[['resumeApprovalRate','简历'],['firstPassRate','一面'],['secondPassRate','二面']];
      chart(quality.card,'审批和面试通过率图',()=>({
        tooltip:{...baseOptions().tooltip,formatter:params=>{
          const value=data.metrics[rateItems[params.dataIndex][0]];
          return rateItems[params.dataIndex][1]+'：'+(value==null?'暂无已处理记录':value+'%');
        }},
        grid:{left:4,right:36,top:10,bottom:10,containLabel:true},
        xAxis:{...axis(),max:100,axisLabel:{...axis().axisLabel,formatter:'{value}%'}},
        yAxis:{...axis('category'),inverse:true,data:rateItems.map(([,name])=>name)},
        series:[{name:'通过率',type:'bar',barWidth:20,showBackground:true,backgroundStyle:{color:theme().grid,borderRadius:6},
          label:{show:true,position:'right',color:theme().ink,
            formatter:params=>data.metrics[rateItems[params.dataIndex][0]]==null?'—':params.value+'%'},
          data:rateItems.map(([key],i)=>({value:data.metrics[key]??0,label:{formatter:data.metrics[key]==null?'—':'{c}%'},itemStyle:{color:colors[i],borderRadius:6}}))}]
      }),185,rateItems.some(([key])=>data.metrics[key]!=null));
      const additional=el('details','',detail,'hr-bi-all-metrics');el('summary','查看全部 24 项指标',additional);
      for(const [title,items] of groups){
        const group=el('section','',additional,'hr-bi-metric-group');el('h3',title,group);
        const grid=el('div','',group,'hr-bi-metric-grid');items.filter(item=>!heroKeys.includes(item[0])).forEach(item=>metric(grid,item));
      }
      const records=el('details','',detail,'hr-bi-data-details');el('summary','数据明细与统计口径',records);
      const columns=[['name','名称'],['demands','需求'],['headcount','编制'],['resumes','简历'],['pending','待审批'],
        ['approved','简历通过'],['firstReviews','一面意见'],['firstPassed','一面通过'],['secondReviews','二面意见'],
        ['secondPassed','二面通过'],['onboarding','待入职'],['joined','已入职']];
      table(records,'部门招聘明细',data.departments,columns);table(records,'职位招聘明细',data.jobs,columns);
      table(records,'周期明细',data.trend,[['date','周期'],['resumes','简历'],['firstReviews','一面意见'],['secondReviews','二面意见'],
        ['demands','需求'],['headcount','编制'],['examined','评估人次'],['qualified','符合条件'],['greetings','主动联系送达'],
        ['onboarding','待入职'],['joined','已入职'],['notifications','通知记录'],['notificationSent','通知送达']]);
      const definitions=el('section','',records,'hr-bi-card hr-bi-definitions');el('h3','统计口径与导出内容',definitions);
      const list=el('ul','',definitions);data.definitions.forEach(text=>el('li',text,list));
      const foot=el('div','',detail,'hr-bi-footer');el('span','HOYEE · RECRUITMENT INTELLIGENCE',foot);el('span','数据来自平台已保存记录',foot);
      IncrementalDOM.patch(metricRoot,metrics);IncrementalDOM.patch(detailRoot,detail);mountCharts();
    }
    function options(select,rows,allLabel){
      const chosen=select.value;select.replaceChildren();el('option',allLabel,select).value='';
      for(const row of rows)el('option',row.name,select).value=row.id;
      if(select===department)el('option','未关联部门',select).value='unassigned';
      select.value=rows.some(r=>r.id===chosen)||chosen==='unassigned'?chosen:'';
    }
    async function load(){
      if(!active||!allowed())return;
      const ticket=++generation,params=new URLSearchParams({from:start.value,to:end.value,department:department.value,
        job:job.value,granularity:granularity.value});
      apply.disabled=refresh.disabled=true;download.hidden=true;feedback.className='hr-hint';feedback.textContent='正在统计平台记录…';
      shell.setAttribute('aria-busy','true');
      try{
        const [response,library]=await Promise.all([hrApi('/api/v1/hr/bi?'+params,{}),ensureCharts()]);
        if(ticket!==generation||!active||!allowed())return;
        if(response.success===false||!response.data?.metrics)throw Error(response.message||'报表读取失败');
        data=response.data;options(department,data.options.departments,'全部部门');options(job,data.options.jobs,'全部职位');
        applied=data.filters;syncCycles(applied.granularity);render();
        download.href='/api/v1/hr/bi/export?'+new URLSearchParams(applied);download.hidden=false;
        feedback.textContent='更新于 '+new Date(data.generatedAt).toLocaleString('zh-CN')+' · Excel 含汇总与全部业务明细';
        cycleDescription.textContent=applied.from+' — '+applied.to+' · 北京时间';
        if(!library)feedback.textContent+=' · 图表加载失败，可刷新重试或查看数据明细';
      }catch(e){
        if(ticket===generation){
          feedback.textContent=(data?'更新暂未完成，保留上次数据。':'')+e.message;feedback.className='hr-error';
          if(data)download.hidden=false;
        }
      }finally{if(ticket===generation){apply.disabled=refresh.disabled=false;shell.setAttribute('aria-busy','false');}}
    }
    return {
      enter(){active=true;load();},leave(){active=false;++generation;destroyCharts();},
      reset(){active=false;++generation;destroyCharts();metricRoot.replaceChildren();detailRoot.replaceChildren();download.hidden=true;applied=null;data=null;}
    };
  };
})();
