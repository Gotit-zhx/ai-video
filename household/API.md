# 拾间 · 家务系统 API contract

Independent app at `/` (proxy on dedicated host). Python + SQLite; static frontend in public/. JSON requests use Content-Type application/json and X-Requested-With: HomeCare. Authenticated long-lived HttpOnly same-site session cookie. Errors: {error: Chinese message}, appropriate HTTP status.

- POST /api/login {password} -> {ok:true}
- POST /api/logout {} -> {ok:true}
- GET /api/state?date=YYYY-MM-DD (optional, defaults today in saved timezone) -> {today,date,tasks,progress,settings,history,push}
- task shape: {id,title,area,period:"morning"|"evening"|"bedtime",minutes,frequency:"daily"|"weekly",weekdays:[0..6 Monday=0],essential:boolean,enabled:boolean,steps:[{id,text}]}. tasks includes all enabled tasks; frontend filter weekly due with selected date.
- progress: object keyed by task id, each {steps:[completed step ids],skipped:boolean}; missing means pending.
- settings: {timezone:"Asia/Shanghai",reminderTimes:{morning:"20:30",evening:"20:30",bedtime:"23:00"},remindersEnabled:false,householdName:"我的家",emailEnabled:false,emailTo:"",emailTime:"23:00"}. Only evening/bedtime are exposed in the default evening plan; morning is retained for compatibility without seeded tasks.
- history: [{date,done,total,minutes}] last 7 dates (today included); done counts completed tasks, skipped excluded from denominator; minutes sums estimates of done tasks.
- push: {supported:boolean,publicKey:string,subscriptions:number}; supported means server has pywebpush and keys configured.
- PUT /api/progress {date,taskId,stepId,done:boolean} -> {ok:true}; atomic set/unset one step, clears skipped when checking. Preserve unrelated concurrent steps. No future writes, allow past 30 days.
- PUT /api/skip {date,taskId,skipped:boolean} -> {ok:true}; mark N/A; do not erase step history.
- POST /api/tasks {title,area,period,minutes,frequency,weekdays,essential,steps:[text,...]} -> {task}
- PUT /api/tasks/<id> same editable properties; preserve step IDs for unchanged text -> {task}
- DELETE /api/tasks/<id> -> {ok:true}; archive only.
- PUT /api/settings {timezone,reminderTimes,remindersEnabled,householdName} -> {settings}
- POST /api/push/subscribe native PushSubscription JSON -> {ok:true}
- POST /api/push/unsubscribe {endpoint} -> {ok:true}
- POST /api/push/test {} -> {ok:true,sent:number}; may error if delivery unavailable.
- GET /api/state also includes email:{supported:boolean}, indicating configured mail transport.
- POST /api/email/test {} -> {ok:true}; test delivery to saved emailTo, with rate limiting.
- GET /api/reminders.ics -> downloadable recurring calendar with alarms and timezone honoring settings.
- GET /api/export -> full household JSON backup with tasks/progress/settings; never credentials.
- GET /health -> {ok:true}

Push payload {title,body,url:"/"}; worker handles push and click. Server only sends when explicitly remindersEnabled and there are pending tasks in that period, at most once per local date/period; journal prevents restarts repeating delivered sends. No external trackers. All labels Chinese. Private app for one household, no public registration. Blank state must explain login/offline errors.

Frontend navigation: 今日 / 记录 / 家务 / 设置. Today has full vs essential-only view, period groupings and expandable steps, persistent server checkmarks, estimated minutes, N/A. Essentials are a reduced selection and must not mark hidden tasks completed. Cross-day handling refreshes from server on visibility/focus. Offline reads may show cached state with clear status; do not claim changes saved when network failed. Service worker cache only public static assets, never API/login data. App install and iOS notifications requirements stated inside settings.
