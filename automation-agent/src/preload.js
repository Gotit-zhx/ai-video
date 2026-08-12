const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("heyiAgent", {
  getSnapshot: () => ipcRenderer.invoke("agent:snapshot"),
  probe: () => ipcRenderer.invoke("agent:probe"),
  startLocal: (settings) => ipcRenderer.invoke("agent:start-local", settings),
  saveSettings: (settings) => ipcRenderer.invoke("agent:save-settings", settings),
  startBrowserActAuthorization: () => ipcRenderer.invoke("agent:browser-act-auth-start"),
  pollBrowserActAuthorization: () => ipcRenderer.invoke("agent:browser-act-auth-poll"),
  openBrowserActWebsite: () => ipcRenderer.invoke("agent:browser-act-website"),
  toggleAcceptJobs: (acceptJobs) => ipcRenderer.invoke("agent:toggle-accept-jobs", acceptJobs),
  reset: () => ipcRenderer.invoke("agent:reset"),
  openLogs: () => ipcRenderer.invoke("agent:open-logs"),
  getPoolRecoveryPlan: (accountId) => ipcRenderer.invoke("agent:pool-recovery-plan", accountId),
  recoverPoolAccount: (accountId) => ipcRenderer.invoke("agent:pool-recover", {
    accountId,
    confirmed: true
  }),
  startPoolLoginAssist: (accountId) => ipcRenderer.invoke("agent:pool-login-assist", accountId),
  finishPoolLoginAssist: (accountId) => ipcRenderer.invoke("agent:pool-login-complete", accountId),
  openChromeLogin: (accountId = "") => ipcRenderer.invoke("agent:pool-open-chrome-login", accountId),
  getLocalProfileCreatePlan: (profileId) => ipcRenderer.invoke("agent:pool-local-profile-create-plan", profileId),
  createFromLocalProfile: (request) => ipcRenderer.invoke("agent:pool-local-profile-create", request),
  finishLocalProfileImport: (token) => ipcRenderer.invoke("agent:pool-local-profile-import", {
    token,
    confirmed: true
  }),
  cancelLocalProfileImport: (token) => ipcRenderer.invoke("agent:pool-profile-import-cancel", {
    token,
    confirmed: true
  }),
  getPoolBindPlan: (profileId) => ipcRenderer.invoke("agent:pool-bind-plan", profileId),
  bindPoolAccount: (request) => ipcRenderer.invoke("agent:pool-bind", request),
  getAddAccountPlan: () => ipcRenderer.invoke("agent:pool-add-plan"),
  addBrowserActAccount: (request) => ipcRenderer.invoke("agent:pool-add", request),
  finishAddingAccount: (token) => ipcRenderer.invoke("agent:pool-add-complete", token),
  cancelAddingAccount: (token) => ipcRenderer.invoke("agent:pool-add-cancel", {
    token,
    confirmed: true
  }),
  deletePoolAccount: (accountId) => ipcRenderer.invoke("agent:pool-account-delete", {
    accountId,
    confirmed: true
  }),
  onRuntimeUpdate: (callback) => {
    const listener = () => callback();
    ipcRenderer.on("agent:runtime-update", listener);
    return () => ipcRenderer.removeListener("agent:runtime-update", listener);
  }
});
