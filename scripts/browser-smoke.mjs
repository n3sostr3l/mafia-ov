import assert from "node:assert/strict";
import { chromium } from "../.tools/ui-check/node_modules/playwright/index.mjs";
const base = "http://127.0.0.1:4200";
const req = async (path, method = "GET", body) => {
  const r = await fetch(base + "/api/v1" + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  assert.equal(r.status, 200, await r.clone().text());
  return r.json();
};
const t = await req("/tournaments", "POST", {
  name: "Проверка нового голосования",
});
let g = await req("/tournaments/" + t.id + "/games", "POST", {
  tableNumber: 1,
  gameNumber: 1,
  demo: true,
});
await req("/tournaments/" + t.id);
await req("/tournaments/" + t.id + "/players", "POST", { name: "Александр" });
const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
});
const context = await browser.newContext({
  viewport: { width: 1500, height: 1100 },
});
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
try {
  await page.goto(base + "/games/" + g.id);
  await page
    .getByRole("heading", {
      name: "Проверка нового голосования Партия 1",
      exact: true,
    })
    .waitFor();
  await page
    .getByRole("button", { name: "Следующая фаза", exact: true })
    .click();
  const vote = (n, c) =>
    page.getByRole("button", {
      name: `Игрок ${n} за кандидата ${c}`,
      exact: true,
    });
  await vote(1, 2).click();
  await page.waitForFunction(
    () =>
      document.querySelector('[aria-label="Игрок 1 за кандидата 6"]')?.disabled,
  );
  assert.equal(await vote(1, 6).isDisabled(), true);
  await vote(4, 6).click();
  await page
    .getByRole("button", { name: "Завершить голосование", exact: true })
    .click();
  await page
    .getByRole("heading", { name: "Перестрелка · 30 секунд", exact: true })
    .waitFor();
  assert(
    (await page.locator('[role="progressbar"][aria-valuemax="30"]').count()) >
      0,
  );
  await page
    .getByRole("button", { name: "Пропустить речь", exact: true })
    .click();
  await page.waitForFunction(() =>
    document.body.innerText.includes("Говорит № 6. По 30 секунд"),
  );
  await page
    .getByRole("button", { name: "Пропустить речь", exact: true })
    .click();
  await page
    .getByRole("heading", { name: "Переголосование", exact: true })
    .waitFor();
  await vote(1, 2).click();
  await vote(4, 6).click();
  await page.screenshot({
    path: "docs/screenshots/nov14-ballot.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Завершить переголосование", exact: true })
    .click();
  await page
    .getByRole("heading", { name: "Кто за подъём всех?", exact: true })
    .waitFor();
  for (const n of [1, 2, 4, 5])
    await page
      .getByRole("button", { name: `Игрок ${n} за подъём`, exact: true })
      .click();
  await page
    .getByRole("button", {
      name: "Завершить голосование за подъём",
      exact: true,
    })
    .click();
  await page.waitForFunction(
    () => document.querySelectorAll(".player-visual.voted-out").length === 4,
  );
  g = await req("/games/" + g.id);
  assert.deepEqual(g.state.rounds[2].eliminatedSeats, [2, 6]);
  assert.equal(g.state.rounds[2].splitLift, true);
  // Tab completes and cycles roster choices in the existing game.
  const picker = page.getByRole("combobox", {
    name: "Ник игрока турнира",
    exact: true,
  });
  await picker.fill("Але");
  await picker.press("Tab");
  assert((await picker.inputValue()).startsWith("Алекс"));
  await picker.press("Tab");
  assert.equal(await picker.inputValue(), "Александр");
  await picker.press("Escape");
  await page
    .getByRole("button", { name: "Поставить на место № 1", exact: true })
    .click();
  await page.waitForFunction(
    () => document.querySelector("#player-name")?.value === "Александр",
  );
  await picker.fill("Ли");
  await picker.press("Escape");
  await page
    .getByRole("button", { name: "Поставить на место № 1", exact: true })
    .click();
  await page.waitForFunction(
    () => document.querySelector("#player-name")?.value === "Лис",
  );
  const swapped = await req("/games/" + g.id);
  assert.equal(swapped.state.players[1].name, "Александр");
  assert.equal(
    new Set(swapped.state.players.map((p) => p.tournamentPlayerId)).size,
    10,
  );
  // Tournament password, a fresh browser context, direct game route, no email.
  await page.goto(base + "/tournaments/" + t.id);
  await page
    .getByRole("button", { name: "Задать пароль", exact: true })
    .click();
  await page
    .getByLabel("Новый пароль", { exact: true })
    .fill("browser-test-pass");
  await page
    .getByRole("button", { name: "Сохранить доступ", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Сменить пароль", exact: true })
    .waitFor();
  const guest = await browser.newContext({
    viewport: { width: 1280, height: 900 },
  });
  const guestPage = await guest.newPage();
  await guestPage.goto(base + "/games/" + g.id);
  await guestPage
    .getByRole("heading", { name: "Пароль турнира", exact: true })
    .waitFor();
  await guestPage
    .getByLabel("Пароль", { exact: true })
    .fill("browser-test-pass");
  await guestPage
    .getByRole("button", { name: "Открыть турнир", exact: true })
    .click();
  await guestPage
    .getByRole("heading", {
      name: "Проверка нового голосования Партия 1",
      exact: true,
    })
    .waitFor();
  await guestPage.reload();
  await guestPage
    .getByRole("heading", {
      name: "Проверка нового голосования Партия 1",
      exact: true,
    })
    .waitFor();
  assert.equal(
    await guestPage
      .getByRole("heading", { name: "Пароль турнира", exact: true })
      .count(),
    0,
  );
  // Mobile tournament management remains usable.
  await guestPage.setViewportSize({ width: 390, height: 844 });
  await guestPage.goto(base + "/tournaments/" + t.id);
  await guestPage
    .getByRole("button", { name: "Экспорт", exact: true })
    .waitFor();
  assert(
    await guestPage.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  );
  // Export and re-import from UI, then move and delete an isolated game.
  await guestPage.setViewportSize({ width: 1280, height: 1000 });
  await guestPage.getByRole("button", { name: "Экспорт", exact: true }).click();
  const downloadPromise = guestPage.waitForEvent("download");
  await guestPage
    .getByRole("button", { name: "Скачать ZIP", exact: true })
    .click();
  const download = await downloadPromise;
  const archivePath = await download.path();
  assert(archivePath);
  await guestPage.getByRole("button", { name: "Турниры", exact: true }).click();
  await guestPage
    .getByRole("button", { name: "Импорт турнира", exact: true })
    .click();
  await guestPage.locator("#archive-file").setInputFiles(archivePath);
  await guestPage
    .getByRole("button", { name: "Импортировать", exact: true })
    .click();
  await guestPage
    .getByRole("heading", {
      name: "Проверка нового голосования (импорт)",
      exact: true,
    })
    .waitFor();
  await guestPage
    .getByRole("button", { name: "Стол / партия", exact: true })
    .click();
  const dialog = guestPage.getByRole("dialog");
  await dialog.locator('input[name="table"]').fill("2");
  await dialog.locator('input[name="game"]').fill("7");
  await dialog.getByRole("button", { name: "Сохранить", exact: true }).click();
  await guestPage.getByText("Стол 2 · Партия 7", { exact: true }).waitFor();
  await guestPage.getByRole("button", { name: "Удалить", exact: true }).click();
  await guestPage
    .getByRole("dialog")
    .getByRole("button", { name: "Удалить", exact: true })
    .click();
  await guestPage
    .getByRole("heading", { name: "Партий пока нет", exact: true })
    .waitFor();
  await guestPage
    .getByRole("button", { name: "Удалить турнир", exact: true })
    .click();
  await guestPage
    .getByRole("dialog")
    .getByRole("button", { name: "Удалить", exact: true })
    .click();
  await guestPage
    .getByRole("heading", { name: "Турниры", exact: true })
    .waitFor();
  await guest.close();
  assert.deepEqual(errors, []);
  console.log(
    "PASS: voting, shootout, runoff/lift, Tab, player reassignment/swap, password/reload, mobile, archive download/import, game relocation and deletion.",
  );
} finally {
  await browser.close();
}
