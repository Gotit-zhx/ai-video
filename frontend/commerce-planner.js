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
    return data;
  }
  const selector = document.getElementById("commerce-template-select");
  const templateName = document.getElementById("commerce-template-name");
  const templateStatus = document.getElementById("commerce-template-status");
  const refresh = document.getElementById("commerce-template-refresh");
  const save = document.getElementById("commerce-template-save");
  const newTemplate = document.getElementById("commerce-template-new");
  let templates = [];
  let selected = null;
  let templateRequest = 0;
  if (selector && refresh && save && newTemplate && templateName && templateStatus) {
    refresh.addEventListener("click", async () => {
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
        selected = null;
        templateStatus.textContent = `已读取本公司最近 ${templates.length} 个模板，请选择要载入的模板。`;
      } catch(error) {
        templates=[]; selected=null; selector.replaceChildren();
        templateStatus.textContent = error.message;
      }
      finally { refresh.disabled = false; save.disabled = false; selector.disabled = false; newTemplate.disabled = false; }
    });
    selector.addEventListener("change", () => {
      selected = templates.find(t => t.templateId === selector.value) || null;
      if(!selected) { templateName.value=""; return; }
      templateName.value = selected.name;
      Object.entries(selected.configuration).forEach(([key, value]) => {
        const field = form.elements.namedItem(key);
        if(!field) return;
        if(field.type === "checkbox") field.checked = false;
        else field.value = value;
      });
      revision++;
      result.textContent = "已载入模板，请重新预览。权利、供货和生产文件需要对本次商品重新核查。";
      templateStatus.textContent = `当前 v${selected.version}，保存会新增版本，不覆盖旧记录。`;
    });
    newTemplate.addEventListener("click", () => {
      selected=null; selector.value=""; templateName.value="";
      templateStatus.textContent="保留当前规划参数，填写新名称后保存为独立模板。";
    });
    save.addEventListener("click", async () => {
      if(!form.reportValidity()) return;
      const name=templateName.value.trim();
      if(!name) { templateStatus.textContent="请填写模板名称。"; return; }
      save.disabled=true; selector.disabled=true; refresh.disabled=true; newTemplate.disabled=true;
      try {
        const response=await api("/api/v1/commerce-templates", { method:"POST",
          headers:{"Content-Type":"application/json"}, body:JSON.stringify({
            templateId:selected?.templateId || null, expectedVersion:selected?.version || 0,
            name, configuration:configuration()
          }) });
        selected=response.data;
        templates=templates.filter(t=>t.templateId!==selected.templateId).concat(selected);
        let option=Array.from(selector.options).find(o=>o.value===selected.templateId);
        if(!option) { option=document.createElement("option"); option.value=selected.templateId; selector.append(option); }
        option.textContent=`${selected.name} · v${selected.version}`; selector.value=selected.templateId;
        templateStatus.textContent=`已保存 v${selected.version}。仅保存配置，未创建执行任务。`;
      } catch(error) { templateStatus.textContent=error.message; }
      finally { save.disabled=false; selector.disabled=false; refresh.disabled=false; newTemplate.disabled=false; }
    });
  }
  form.addEventListener("input", () => {
    revision++;
    result.textContent = "配置已变更，请重新预览；旧结果已清除。";
  });
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const currentRevision = ++revision;
    const data = configuration();
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
      const money = amount => new Intl.NumberFormat("zh-CN", { style: "currency", currency: p.currency }).format(amount);
      line("p", `全店每日预算上限 ${money(p.dailyBudgetAllShops)}；${data.observationDays} 天总上限 ${money(p.observationBudgetCeiling)}。不含生成、生产、样品及物流费用。`);
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
