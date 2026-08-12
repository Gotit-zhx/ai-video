const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("chromeProfiles", {
  snapshot: () => ipcRenderer.invoke("manager:snapshot"),
  refresh: () => ipcRenderer.invoke("manager:refresh"),
  openProfile: (directory) => ipcRenderer.invoke("manager:open-profile", directory),
  recordUsage: (directory) => ipcRenderer.invoke("manager:record-usage", directory),
  updateProfile: (payload) => ipcRenderer.invoke("manager:update-profile", payload),
  chooseDataDirectory: () => ipcRenderer.invoke("manager:choose-data-directory"),
  updateLaunchUrl: (value) => ipcRenderer.invoke("manager:update-launch-url", value),
  resetCounts: (directory = "") => ipcRenderer.invoke("manager:reset-counts", directory),
  openDataDirectory: () => ipcRenderer.invoke("manager:open-data-directory")
});
