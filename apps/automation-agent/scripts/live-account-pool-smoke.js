const { BrowserActClient } = require("../src/core/browser-act-client");
const { AccountPoolManager } = require("../src/core/account-pool-manager");

async function main() {
  const browserAct = new BrowserActClient({ timeoutMs: 90000 });
  const events = [];
  const pool = new AccountPoolManager(browserAct, {
    onEvent: (message, level) => events.push({ message, level })
  });
  const config = {
    poolSize: 1,
    startUrl: "https://myaccount.google.com/",
    accounts: [
      {
        id: "wu-yangzu",
        label: "wu yangzu",
        email: "wuyangzuqq@gmail.com",
        browserId: "chrome_local_110556435787546748",
        aliases: ["wu", "账号一"],
        enabled: true
      },
      {
        id: "ji-kz",
        label: "Ji Kz",
        email: "jkz827738@gmail.com",
        browserId: "chrome_local_110556664368726140",
        aliases: ["ji", "账号二"],
        enabled: true
      }
    ]
  };
  const probe = await browserAct.probe();
  const started = await pool.start(config, probe.browsers);
  const rotated = await pool.handleInstruction(
    "明确关闭 wuyangzuqq@gmail.com",
    config,
    probe.browsers
  );
  process.stdout.write(`${JSON.stringify({ started, rotated, events }, null, 2)}\n`);
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
