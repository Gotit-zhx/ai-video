(() => {
  const form = document.getElementById("commerce-plan-form");
  const result = document.getElementById("commerce-plan-result");
  if (!form || !result) return;
  const numeric = ["sourceProducts", "designsPerProduct", "imagesPerDesign", "shopCount",
    "dailyListingsPerShop", "dailyBudgetPerShop", "observationDays", "targetRoi"];
  function line(tag, value, parent = result) {
    const element = document.createElement(tag);
    element.textContent = value;
    parent.append(element);
    return element;
  }
  let revision = 0;
  function configuration() {
    const data = Object.fromEntries(new FormData(form));
    numeric.forEach(key => { data[key] = Number(data[key]); });
    ["rightsConfirmed", "supplyConfirmed", "productionFilesConfirmed"].forEach(key => {
      data[key] = form.elements.namedItem(key).checked;
    });
    data.shopIds=[];data.shopTargets=[];data.shopCount=1;
    const print=globalThis.CommercePrintTemplateSelection?.();
    if(print?.dirty&&(print.snapshotId||globalThis.CommercePrintTemplateEditor?.getState?.().hasContent))throw new Error('印花配置尚未保存完成，请点击右上角“保存模板”。');
    data.printTemplateSnapshotId=print?print.snapshotId||null:selected?.configuration?.printTemplateSnapshotId||null;
    return data;
  }
  const selector = document.getElementById("commerce-template-select");
  const templateName = document.getElementById("commerce-template-name");
  const templateStatus = document.getElementById("commerce-template-status");
  const refresh = document.getElementById("commerce-template-refresh");
  const save = document.getElementById("commerce-template-save");
  const newTemplate = document.getElementById("commerce-template-new");
  const saveState = document.getElementById("commerce-template-save-state");
  const designCount = form.elements.namedItem('designsPerProduct');
  let savingTemplate = false;
  let headerDirty = false;
  function showSaveState() {
    if(!saveState)return;
    const state=globalThis.CommercePrintTemplateEditor?.getState?.();
    const dirty=headerDirty||state?.dirty&&(state.hasContent||state.pendingSnapshotId);
    saveState.textContent=savingTemplate?'保存中…':state?.busy?'正在处理素材':dirty?'有未保存修改':selected?'已保存':'未保存';
    saveState.dataset.state=savingTemplate||state?.busy?'busy':dirty?'dirty':selected?'saved':'empty';
    if(save && !savingTemplate)save.disabled=!!state?.busy;
  }
  globalThis.addEventListener?.('heyi:print-template-state-change',showSaveState);
  templateName?.addEventListener('input',()=>{headerDirty=true;showSaveState();});
  designCount?.addEventListener?.('input',()=>{headerDirty=true;showSaveState();});
  let templates = [];
  let selected = null;
  let templateRequest = 0;
  let templatesLoaded=false;
  globalThis.addEventListener?.("heyi:company-reset", () => {
    revision++; templateRequest++; selected=null; templates=[];
    templatesLoaded=false;
    selector?.replaceChildren();
    if(templateName)templateName.value="";
    if(templateStatus)templateStatus.textContent="";
    [refresh,save,selector,newTemplate,templateName,designCount].forEach(control=>{if(control)control.disabled=false;});
    result.replaceChildren();
    headerDirty=false;savingTemplate=false;if(saveState){saveState.textContent="未保存";saveState.dataset.state="empty";}
    form.reset();
  });
  if (selector && refresh && save && newTemplate && templateName && templateStatus) {
    const loadTemplates=async () => {
      const ticket = ++templateRequest;
      refresh.disabled = true; save.disabled = true; selector.disabled = true; newTemplate.disabled = true;
      try {
        const response = await api("/api/v1/commerce-templates");
        if(ticket !== templateRequest) return;
        templates = response.data;
        selector.replaceChildren();
        const empty = document.createElement("option"); empty.value = ""; empty.textContent = "新模板"; selector.append(empty);
        templates.forEach(t => {
          const option = document.createElement("option"); option.value = t.templateId;
          option.textContent = `${t.name} · v${t.version}`; selector.append(option);
        });
        templatesLoaded=true;
        if(selected){
          selector.value=selected.templateId;
          const current=Array.from(selector.options).find(option=>option.value===selected.templateId);
          if(current)current.textContent=`${selected.name} · v${selected.version}（当前编辑）`;
        }
        templateStatus.textContent = selected?`模板列表已更新，当前编辑 v${selected.version}。`:`已读取 ${templates.length} 个模板，选择后载入。`;
      } catch(error) {
        if(ticket !== templateRequest)return;
        templateStatus.textContent = error.message;
      }
      finally { if(ticket===templateRequest){refresh.disabled = false; save.disabled = false; selector.disabled = false; newTemplate.disabled = false;} }
    };
    refresh.addEventListener("click",loadTemplates);
    globalThis.addEventListener?.('hashchange',()=>{if(location.hash==='#commerce-templates'&&!templatesLoaded&&!refresh.disabled)loadTemplates();});
    if(globalThis.location?.hash==='#commerce-templates')loadTemplates();
    selector.addEventListener("change", () => {
      const editorState=globalThis.CommercePrintTemplateEditor?.getState?.();
      if(savingTemplate||editorState?.busy||headerDirty||editorState?.hasContent&&editorState?.dirty){
        selector.value=selected?.templateId||"";
        templateStatus.textContent="当前有未保存的修改，请先保存模板，再切换。";
        return;
      }
      selected = templates.find(t => t.templateId === selector.value) || null;
      if(!selected) { templateName.value=""; return; }
      templateName.value = selected.name;

      Object.entries(selected.configuration).forEach(([key, value]) => {
        const field = form.elements.namedItem(key);
        if(!field) return;
        if(field.type === "checkbox") field.checked = false;
        else field.value = value;
      });
      form.elements.namedItem('shopCount').value='1';
      globalThis.dispatchEvent?.(new CustomEvent('heyi:workflow-print-template-selected',{detail:{snapshotId:selected.configuration.printTemplateSnapshotId||null}}));
      revision++;
      result.textContent = "已载入模板，可继续调整裂变数量和下方印花配置。";
      headerDirty=false;showSaveState();
      templateStatus.textContent = `已载入 v${selected.version}，可直接调整版型和印花。`;
    });
    newTemplate.addEventListener("click", () => {
      selected=null; selector.value=""; templateName.value="";
      headerDirty=true;showSaveState();templateName.focus?.();
      templateStatus.textContent="已复制当前配置，填写新名称后保存为独立模板。";
    });
    save.addEventListener("click", async () => {
      if(!form.reportValidity()) return;
      const name=templateName.value.trim();
      if(!name) { templateStatus.textContent="请填写模板名称。"; return; }
      if(savingTemplate)return;
      savingTemplate=true;globalThis.dispatchEvent?.(new CustomEvent('heyi:template-save-lock',{detail:true}));save.disabled=true; selector.disabled=true; refresh.disabled=true; newTemplate.disabled=true;
      templateName.disabled=true;if(designCount)designCount.disabled=true;showSaveState();
      const sessionTicket=templateRequest;
      // Read the header before disabling fields affects FormData; use the frozen count below.
      const count=Number(designCount?.value);
      try {
        const editor=globalThis.CommercePrintTemplateEditor;
        if(editor){
          const state=editor.getState?.();
          if(state?.busy)throw new Error('四面图片仍在处理中，完成后再保存模板。');
          if(!state?.hasContent&&!state?.snapshotId)throw new Error('请先上传商品图片并设置印花位置，再保存模板。');
          templateStatus.textContent='正在保存四面图片和印花位置…';
          await editor.save({name});
          if(sessionTicket!==templateRequest)return;
        }
        // Disabled numeric inputs do not participate in FormData.
        const config=configuration();config.designsPerProduct=count;
        templateStatus.textContent='正在保存模板配置…';
        const response=await api("/api/v1/commerce-templates", { method:"POST",
          headers:{"Content-Type":"application/json"}, body:JSON.stringify({
            templateId:selected?.templateId || null, expectedVersion:selected?.version || 0,
            name, configuration:config
          }) });
        if(sessionTicket!==templateRequest)return;
        selected=response.data;
        templates=templates.filter(t=>t.templateId!==selected.templateId).concat(selected);
        let option=Array.from(selector.options).find(o=>o.value===selected.templateId);
        if(!option) { option=document.createElement("option"); option.value=selected.templateId; selector.append(option); }
        option.textContent=`${selected.name} · v${selected.version}`; selector.value=selected.templateId;
        headerDirty=false;
        templateStatus.textContent=`已保存「${selected.name}」· v${selected.version}，版型与印花位置已关联，可在新建裂变时选用。`;
      } catch(error) { if(sessionTicket===templateRequest){headerDirty=true;templateStatus.textContent=error.message;} }
      finally { if(sessionTicket===templateRequest){savingTemplate=false;globalThis.dispatchEvent?.(new CustomEvent('heyi:template-save-lock',{detail:false}));save.disabled=false; selector.disabled=false; refresh.disabled=false; newTemplate.disabled=false;templateName.disabled=false;if(designCount)designCount.disabled=false;showSaveState();} }
    });
  }
  form.addEventListener("input", () => {
    revision++;
    result.textContent = "配置已变更，请重新预览；旧结果已清除。";
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const currentRevision = ++revision;
    let data;try{data=configuration();}catch(error){result.textContent=error.message;return;}
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    result.textContent = "正在计算规划，不会执行外部操作…";
    try {
      const response = await api("/api/v1/commerce-plans/preview", {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data)
      });
      if (currentRevision !== revision) return;
      const p = response.data;
      result.replaceChildren();
      line("h3", "规划预览 · 尚未创建任务");
      line("p", `设计／营销版本 ${p.designVersions} 个 · 素材 ${p.materialImages} 张 · 刊登草稿 ${p.listingDrafts} 个 · 理论最少 ${p.minimumPublishingDays} 天`);
      const budgets=p.budgetGroups?.length ? p.budgetGroups : [{currency:p.currency,shopCount:data.shopCount,dailyBudgetAllShops:p.dailyBudgetAllShops,observationBudgetCeiling:p.observationBudgetCeiling,budgetConfigured:p.dailyBudgetAllShops!=null}];
      budgets.forEach(group=>{
        if(!group.budgetConfigured||group.dailyBudgetAllShops==null||group.observationBudgetCeiling==null){line("p",`${group.currency} · ${group.shopCount} 家店铺：广告预算未配置。`);return;}
        const money=amount=>new Intl.NumberFormat((globalThis.HoyeeLanguage?.locale || "zh-CN"),{style:"currency",currency:group.currency}).format(amount);
        line("p", `${group.currency} · ${group.shopCount} 家店铺：每日预算上限 ${money(group.dailyBudgetAllShops)}；${data.observationDays} 天总上限 ${money(group.observationBudgetCeiling)}。`);
      });
      line("p", "不同币种分别计算，不含生成、生产、样品及物流费用。");
      line("p", p.stages.join(" → "));
      line("h4", "理论分配（店铺序号仅为占位）");
      p.schedule.slice(0, 14).forEach(day => line("p", `第 ${day.day} 天：${day.listingsPerShop.map((count, i) => `店${i + 1} ${count}款`).join(" / ")}`));
      if (p.schedule.length > 14) line("p", `共 ${p.schedule.length} 天，此处仅展示前14天。`);
      line("h4", "执行前待解决");
      const blockers = line("ul", "");
      p.blockers.forEach(text => line("li", text, blockers));
      line("h4", "规划边界");
      const warnings = line("ul", "");
      p.warnings.forEach(text => line("li", text, warnings));
    } catch (error) {
      if (currentRevision === revision) result.textContent = error.message || "规划失败，请重试";
    } finally {
      button.disabled = false;
    }
  });
})();
